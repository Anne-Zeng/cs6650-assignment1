package skitrak.consumer;

import static org.junit.Assert.*;

import com.rabbitmq.client.Connection;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ConsumerLifecycleTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test public void stoppingDuringBlockedConnectCannotInstallConnectionOrLeakStoreOwnership() throws Exception {
    Path directory = temporary.newFolder().toPath();
    CountDownLatch connecting = new CountDownLatch(1);
    CountDownLatch interruptedByClose = new CountDownLatch(1);
    CountDownLatch releaseConnection = new CountDownLatch(1);
    AtomicInteger aborts = new AtomicInteger();
    AtomicInteger channelOpens = new AtomicInteger();
    Connection lateConnection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
        new Class<?>[] {Connection.class}, (proxy, method, args) -> {
          if (method.getName().equals("abort")) { aborts.incrementAndGet(); return null; }
          if (method.getName().equals("createChannel")) { channelOpens.incrementAndGet(); throw new AssertionError("Shutdown connection used"); }
          if (method.getName().equals("isOpen")) return true;
          if (method.getReturnType().equals(boolean.class)) return false;
          if (method.getReturnType().equals(int.class)) return 0;
          if (method.getReturnType().equals(long.class)) return 0L;
          return null;
        });
    ConsumerRuntime consumer = new ConsumerRuntime(new ConsumerConfig(directory), (factory, name) -> {
      connecting.countDown();
      while (releaseConnection.getCount() > 0) {
        try { releaseConnection.await(); }
        catch (InterruptedException ignored) { interruptedByClose.countDown(); }
      }
      return lateConnection;
    });
    Thread close = new Thread(consumer::close, "test-consumer-close");
    try {
      consumer.start();
      assertTrue(connecting.await(5, TimeUnit.SECONDS));
      close.start();
      assertTrue(interruptedByClose.await(5, TimeUnit.SECONDS));
      releaseConnection.countDown();
      close.join(10000);
      assertFalse("Shutdown completed", close.isAlive());
      assertTrue("Late AMQP connection aborted", aborts.get() > 0);
      assertEquals("Never installed or used after stopping", 0, channelOpens.get());
      try (ConsumerStore reopened = new ConsumerStore(directory, "1", 20)) {
        assertEquals(0, reopened.processed());
      }
    } finally {
      releaseConnection.countDown();
      consumer.close();
      close.join(10000);
    }
  }
}
