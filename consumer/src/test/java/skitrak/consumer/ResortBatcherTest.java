package skitrak.consumer;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ResortBatcherTest {
  @Test public void fullBatchDoesNotWaitForLongDeadline() throws Exception {
    ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    ExecutorService executor=Executors.newSingleThreadExecutor();
    CountDownLatch done=new CountDownLatch(1);List<Integer> actual=new CopyOnWriteArrayList<>();
    ResortBatcher<Integer> batcher=new ResortBatcher<>(3,1000,timer,executor::execute,
        values->{actual.addAll(values);done.countDown();});
    try {
      batcher.offer(1);batcher.offer(2);assertFalse(done.await(30,TimeUnit.MILLISECONDS));
      batcher.offer(3);assertTrue(done.await(2,TimeUnit.SECONDS));assertEquals(Arrays.asList(1,2,3),actual);
    } finally {batcher.close();timer.shutdownNow();executor.shutdownNow();}
  }

  @Test public void lowTrafficFlushesWithoutAnotherDelivery() throws Exception {
    ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    ExecutorService executor=Executors.newSingleThreadExecutor();CountDownLatch done=new CountDownLatch(1);
    ResortBatcher<Integer> batcher=new ResortBatcher<>(32,10,timer,executor::execute,
        values->{assertEquals(Collections.singletonList(7),values);done.countDown();});
    try {batcher.offer(7);assertTrue(done.await(2,TimeUnit.SECONDS));}
    finally {batcher.close();timer.shutdownNow();executor.shutdownNow();}
  }

  @Test public void closeFlushesPartialBatchAndHandlersRemainOrderedOnParallelExecutor() throws Exception {
    ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    ExecutorService executor=Executors.newFixedThreadPool(4);
    CountDownLatch done=new CountDownLatch(101);List<Integer> actual=new CopyOnWriteArrayList<>();
    AtomicInteger active=new AtomicInteger(),max=new AtomicInteger();
    ResortBatcher<Integer> batcher=new ResortBatcher<>(8,1000,timer,executor::execute,values->{
      max.accumulateAndGet(active.incrementAndGet(),Math::max);
      try {actual.addAll(values);}finally {active.decrementAndGet();for(Integer value:values)done.countDown();}
    });
    try {
      for(int i=0;i<101;i++)batcher.offer(i);
      batcher.close();timer.shutdownNow();
      assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(1,max.get());
      List<Integer> expected=new ArrayList<>();for(int i=0;i<101;i++)expected.add(i);
      assertEquals(expected,actual);
    } finally {batcher.close();timer.shutdownNow();executor.shutdownNow();}
  }
}
