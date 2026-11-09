package skitrak.consumer;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class SerialLaneTest {
  @Test public void neverRunsOneResortConcurrentlyAndPreservesArrivalOrder() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(5);
    try {
      SerialLane[] lanes = new SerialLane[10];
      List<List<Integer>> observed = new ArrayList<>();
      AtomicInteger failures = new AtomicInteger();
      CountDownLatch completed = new CountDownLatch(2000);
      for (int lane = 0; lane < lanes.length; lane++) {
        lanes[lane] = new SerialLane(pool);
        observed.add(new ArrayList<>());
        AtomicInteger active = new AtomicInteger();
        List<Integer> values = observed.get(lane);
        for (int sequence = 0; sequence < 200; sequence++) {
          int value = sequence;
          lanes[lane].submit(() -> {
            if (active.incrementAndGet() != 1) failures.incrementAndGet();
            values.add(value);
            Thread.yield();
            active.decrementAndGet();
            completed.countDown();
          });
        }
      }
      assertTrue(completed.await(10, TimeUnit.SECONDS));
      assertEquals(0, failures.get());
      for (List<Integer> values : observed) {
        assertEquals(200, values.size());
        for (int value = 0; value < values.size(); value++) assertEquals(value, values.get(value).intValue());
      }
    } finally { pool.shutdownNow(); }
  }

  @Test public void busyResortYieldsSoOtherResortsAreNotStarvedWithFiveOrFewerWorkers() throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      SerialLane busy = new SerialLane(pool);
      SerialLane other = new SerialLane(pool);
      CountDownLatch firstStarted = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch completed = new CountDownLatch(102);
      AtomicInteger busyCompleted = new AtomicInteger();
      AtomicInteger completedWhenOtherRan = new AtomicInteger(-1);
      busy.submit(() -> {
        firstStarted.countDown();
        try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        completed.countDown();
      });
      assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
      for (int index = 0; index < 100; index++) busy.submit(() -> { busyCompleted.incrementAndGet(); completed.countDown(); });
      other.submit(() -> { completedWhenOtherRan.set(busyCompleted.get()); completed.countDown(); });
      release.countDown();
      assertTrue(completed.await(10, TimeUnit.SECONDS));
      assertEquals(0, completedWhenOtherRan.get());
    } finally { pool.shutdownNow(); }
  }
}
