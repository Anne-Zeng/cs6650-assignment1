package skitrak.server;

import com.rabbitmq.client.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChannelPoolTest {
  private static Connection connection(Channel... channels) throws Exception {
    Connection connection = mock(Connection.class); when(connection.isOpen()).thenReturn(true);
    AtomicInteger next = new AtomicInteger(); when(connection.createChannel()).thenAnswer(call -> channels[next.getAndIncrement()]);
    return connection;
  }
  private static Channel channel() { Channel ch = mock(Channel.class); when(ch.isOpen()).thenReturn(true); return ch; }
  private static void awaitReady(ChannelPool pool) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    while (!pool.ready() && System.nanoTime() < end) Thread.sleep(5);
    assertTrue(pool.ready());
  }
  @Test void borrowedChannelIsExclusiveAndTimeoutDoesNotOpenAnother() throws Exception {
    Channel ch = channel(); Connection c = connection(ch);
    try (ChannelPool pool = new ChannelPool(() -> c, ignored -> {}, 1)) {
      pool.start(); awaitReady(pool);
      try (ChannelPool.Lease first = pool.borrow(100)) {
        assertSame(ch, first.channel());
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try { assertTrue(caller.submit(() -> { try { pool.borrow(40); return false; } catch (TimeoutException expected) { return true; } }).get()); }
        finally { caller.shutdownNow(); }
        verify(c, times(1)).createChannel();
      }
      try (ChannelPool.Lease again = pool.borrow(100)) { assertSame(ch, again.channel()); }
      assertEquals(1, pool.available());
    }
  }
  @Test void staleBorrowedLeaseCannotEnterRecoveredGeneration() throws Exception {
    Channel old = channel(), fresh = channel(); Connection first = connection(old), second = connection(fresh);
    AtomicInteger attempts = new AtomicInteger();
    try (ChannelPool pool = new ChannelPool(() -> attempts.incrementAndGet() == 1 ? first : second, ignored -> {}, 1)) {
      pool.start(); awaitReady(pool);
      ChannelPool.Lease oldLease = pool.borrow(100); oldLease.invalidate();
      awaitReady(pool); oldLease.close(); oldLease.close();
      assertEquals(1, pool.available());
      try (ChannelPool.Lease replacement = pool.borrow(100)) { assertSame(fresh, replacement.channel()); }
      verify(first).abort(); verify(second, times(1)).createChannel();
    }
  }
  @Test void droppedConnectionRecoversWithoutRequestTriggeringIt() throws Exception {
    Channel old = channel(), fresh = channel(); Connection first = connection(old), second = connection(fresh);
    List<ShutdownListener> listeners = new CopyOnWriteArrayList<>();
    doAnswer(call -> { listeners.add(call.getArgument(0)); return null; }).when(first).addShutdownListener(any());
    AtomicInteger attempts = new AtomicInteger();
    try (ChannelPool pool = new ChannelPool(() -> attempts.incrementAndGet() == 1 ? first : second, ignored -> {}, 1)) {
      pool.start(); awaitReady(pool);
      when(first.isOpen()).thenReturn(false);
      listeners.get(0).shutdownCompleted(new ShutdownSignalException(true, false, null, first));
      awaitReady(pool); assertEquals(2, pool.connectionsEstablished());
      try (ChannelPool.Lease lease = pool.borrow(100)) { assertSame(fresh, lease.channel()); }
    }
  }
}
