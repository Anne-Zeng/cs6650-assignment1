package skitrak.consumer;

import static org.junit.Assert.*;
import com.rabbitmq.client.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import skitrak.common.*;

/** Exercises the real runtime/store with a controlled broker confirm boundary. */
public class ConsumerBatchRuntimeTest {
  @Rule public TemporaryFolder temporary=new TemporaryFolder();

  @Test public void orderedBatchIsDurableButNeverAckedUntilAllSnapshotsAreConfirmed() throws Exception {
    String oldSize=System.getProperty("STORE_BATCH_SIZE"),oldWait=System.getProperty("STORE_BATCH_WAIT_MS");
    System.setProperty("STORE_BATCH_SIZE","3");System.setProperty("STORE_BATCH_WAIT_MS","100");
    FakeBroker broker=new FakeBroker();Path directory=temporary.newFolder().toPath();
    ConsumerRuntime runtime=new ConsumerRuntime(new ConsumerConfig(directory),(factory,name)->broker.connection(name));
    try {
      runtime.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
      while(!runtime.healthy() && System.nanoTime()<deadline)Thread.sleep(5);
      assertTrue(runtime.healthy());
      List<SwipeMessage> sent=new ArrayList<>();
      for(int i=1;i<=3;i++) {
        SwipeMessage event=new SwipeMessage();event.eventId=UUID.randomUUID().toString();
        event.resortID="1";event.dayID="1";event.skierID="7";event.liftID="1";
        event.vertical=100;event.time=i;event.timestamp=Instant.now().toString();event.serverId="test";
        sent.add(event);
        broker.callbacks.get("resort.1").handle("tag",new Delivery(new Envelope(i,false,Topology.SWIPE_EXCHANGE,"resort.1"),
            new AMQP.BasicProperties.Builder().build(),Json.encode(event).getBytes(StandardCharsets.UTF_8)));
      }
      assertTrue(broker.confirmEntered.await(5,TimeUnit.SECONDS));
      assertTrue("No ACK before broadcast confirm",broker.acks.isEmpty());
      assertEquals(3,((Number)runtime.metrics().get("processed")).intValue());
      assertEquals(3,((Number)runtime.metrics().get("pendingOutbox")).intValue());
      assertEquals(300,runtime.snapshot(1,"1").top.get(0).vertical);
      assertEquals(3,broker.published.size());
      for(int i=0;i<3;i++) {
        LeaderboardSnapshot snapshot=Json.decode(broker.published.get(i),LeaderboardSnapshot.class);
        assertEquals(i+1,snapshot.version);assertEquals(100*(i+1),snapshot.top.get(0).vertical);
      }
      broker.releaseConfirm.countDown();
      assertTrue(broker.acked.await(5,TimeUnit.SECONDS));
      assertEquals(Arrays.asList(1L,2L,3L),broker.acks);
      assertEquals(0,((Number)runtime.metrics().get("pendingOutbox")).intValue());
      runtime.close();
      try(ConsumerStore reopened=new ConsumerStore(directory,"1",20)) {
        assertEquals(3,reopened.processed());assertEquals(300,reopened.total("1","7"));
        assertTrue(reopened.apply(sent.get(0)).duplicate);assertEquals(300,reopened.total("1","7"));
      }
    } finally {
      broker.releaseConfirm.countDown();runtime.close();
      if(oldSize==null)System.clearProperty("STORE_BATCH_SIZE");else System.setProperty("STORE_BATCH_SIZE",oldSize);
      if(oldWait==null)System.clearProperty("STORE_BATCH_WAIT_MS");else System.setProperty("STORE_BATCH_WAIT_MS",oldWait);
    }
  }

  private static final class FakeBroker {
    final Map<String,DeliverCallback> callbacks=new ConcurrentHashMap<>();
    final List<String> published=new CopyOnWriteArrayList<>();
    final List<Long> acks=new CopyOnWriteArrayList<>();
    final CountDownLatch confirmEntered=new CountDownLatch(1),releaseConfirm=new CountDownLatch(1),acked=new CountDownLatch(3);
    Connection connection(String name) {
      AtomicBoolean open=new AtomicBoolean(true);
      return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
        switch(method.getName()) {
          case "isOpen":return open.get();
          case "abort":case "close":open.set(false);return null;
          case "createChannel":return channel(open);
          default:return empty(method.getReturnType());
        }
      });
    }
    Channel channel(AtomicBoolean open) {
      return (Channel)Proxy.newProxyInstance(Channel.class.getClassLoader(),new Class<?>[]{Channel.class},(proxy,method,args)->{
        switch(method.getName()) {
          case "isOpen":return open.get();
          case "basicConsume":callbacks.put((String)args[0],(DeliverCallback)args[2]);return "tag";
          case "basicPublish":published.add(new String((byte[])args[args.length-1],StandardCharsets.UTF_8));return null;
          case "waitForConfirmsOrDie":confirmEntered.countDown();
            if(!releaseConfirm.await(5,TimeUnit.SECONDS))throw new java.io.IOException("Test confirmation deadline");return null;
          case "basicAck":acks.add((Long)args[0]);acked.countDown();return null;
          case "queueDeclarePassive":return Proxy.newProxyInstance(AMQP.Queue.DeclareOk.class.getClassLoader(),new Class<?>[]{AMQP.Queue.DeclareOk.class},
              (p,m,a)->m.getName().equals("getQueue")?args[0]:empty(m.getReturnType()));
          default:return empty(method.getReturnType());
        }
      });
    }
    static Object empty(Class<?> type) {
      if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;return null;
    }
  }
}
