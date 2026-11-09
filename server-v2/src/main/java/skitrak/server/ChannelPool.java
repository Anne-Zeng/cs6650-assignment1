package skitrak.server;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Return;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** One producer connection, fixed pre-created channels, and generation-aware leases.
 * Recovery runs only on one scheduler; library automatic recovery must be disabled. */
public final class ChannelPool implements AutoCloseable {
  @FunctionalInterface public interface Connector { Connection connect() throws Exception; }
  public static final class Lease implements AutoCloseable {
    private final ChannelPool owner;
    private final Generation generation;
    private final Slot slot;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Lease(ChannelPool owner, Generation generation, Slot slot) {
      this.owner = owner; this.generation = generation; this.slot = slot;
    }
    public Channel channel() { return slot.channel; }
    public Return returned() { return slot.returned.get(); }
    public void invalidate() { owner.invalidate(generation); }
    @Override public void close() {
      if (closed.compareAndSet(false, true)) owner.release(generation, slot);
    }
  }
  private static final class Slot {
    final Channel channel;
    final AtomicReference<Return> returned = new AtomicReference<>();
    Slot(Channel channel) { this.channel = channel; }
  }
  private static final class Generation {
    final Connection connection;
    final BlockingQueue<Slot> available;
    final List<Slot> all = new ArrayList<>();
    Generation(Connection c, int capacity) { connection = c; available = new ArrayBlockingQueue<>(capacity); }
    void close() { try { connection.abort(); } catch (RuntimeException ignored) { } }
  }
  private final Connector connector;
  private final Consumer<Channel> topology;
  private final int size;
  private final ScheduledExecutorService recovery;
  private final AtomicReference<Generation> current = new AtomicReference<>();
  private final AtomicBoolean scheduled = new AtomicBoolean();
  private final AtomicBoolean stopped = new AtomicBoolean();
  private final AtomicLong recoveryCount = new AtomicLong();
  private volatile String lastError = "connecting";

  public ChannelPool(Connector connector, Consumer<Channel> topology, int size) {
    if (size < 1) throw new IllegalArgumentException("pool size must be positive");
    this.connector = connector; this.topology = topology; this.size = size;
    recovery = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "producer-recovery"));
  }
  public void start() { recoverAfter(0); }
  private void recoverAfter(long delay) {
    if (stopped.get() || !scheduled.compareAndSet(false, true)) return;
    recovery.schedule(() -> {
      Generation fresh = null;
      boolean retry = false;
      try {
        if (stopped.get() || ready()) return;
        Connection connection = connector.connect();
        fresh = new Generation(connection, size);
        for (int i = 0; i < size; i++) {
          Channel channel = connection.createChannel();
          if (channel == null) throw new IOException("broker channel limit reached");
          if (i == 0) topology.accept(channel);
          channel.confirmSelect();
          Slot slot = new Slot(channel);
          channel.addReturnListener((com.rabbitmq.client.ReturnCallback) slot.returned::set);
          fresh.all.add(slot); fresh.available.add(slot);
        }
        Generation installed = fresh;
        connection.addShutdownListener(cause -> invalidate(installed));
        for (Slot slot : fresh.all) slot.channel.addShutdownListener(cause -> invalidate(installed));
        if (stopped.get()) { fresh.close(); return; }
        current.set(fresh);
        if (stopped.get()) { current.compareAndSet(fresh, null); fresh.close(); return; }
        if (!connection.isOpen() || fresh.all.stream().anyMatch(s -> !s.channel.isOpen())) {
          current.compareAndSet(fresh, null); fresh.close(); retry = true;
        } else { lastError = ""; recoveryCount.incrementAndGet(); }
      } catch (Exception error) {
        lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
        if (fresh != null) fresh.close();
        retry = true;
      } finally {
        scheduled.set(false);
        if (retry || !ready()) recoverAfter(1000);
      }
    }, delay, TimeUnit.MILLISECONDS);
  }
  public Lease borrow(long timeoutMillis) throws InterruptedException, TimeoutException {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    do {
      Generation generation = current.get();
      if (generation == null || !generation.connection.isOpen()) {
        if (generation != null) invalidate(generation); else recoverAfter(0);
        throw new TimeoutException("broker is reconnecting");
      }
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) break;
      Slot slot = generation.available.poll(remaining, TimeUnit.NANOSECONDS);
      if (slot == null) break;
      if (current.get() == generation && slot.channel.isOpen()) {
        slot.returned.set(null);
        return new Lease(this, generation, slot);
      }
      invalidate(generation);
    } while (!stopped.get());
    throw new TimeoutException("producer channel pool exhausted");
  }
  private void release(Generation generation, Slot slot) {
    if (current.get() == generation && generation.connection.isOpen() && slot.channel.isOpen()) {
      if (!generation.available.offer(slot)) throw new IllegalStateException("channel returned twice");
    } else if (current.get() == generation) invalidate(generation);
  }
  private void invalidate(Generation generation) {
    if (current.compareAndSet(generation, null)) {
      // abort is non-blocking; borrowed old-generation channels can never re-enter a new pool.
      generation.close();
    }
    recoverAfter(0);
  }
  public boolean ready() { Generation g = current.get(); return !stopped.get() && g != null && g.connection.isOpen(); }
  public int available() { Generation g = current.get(); return g == null ? 0 : g.available.size(); }
  public long connectionsEstablished() { return recoveryCount.get(); }
  public String lastError() { return lastError; }
  @Override public void close() {
    stopped.set(true); Generation g = current.getAndSet(null); if (g != null) g.close(); recovery.shutdownNow();
  }
}
