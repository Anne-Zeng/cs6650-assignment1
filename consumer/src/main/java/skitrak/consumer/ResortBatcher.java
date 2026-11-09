package skitrak.consumer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Bounded-by-prefetch mailbox; only its resort's serial lane runs handlers. */
final class ResortBatcher<T> implements AutoCloseable {
  private final int size;
  private final long waitNanos;
  private final ScheduledExecutorService timer;
  private final Consumer<Runnable> dispatch;
  private final Consumer<List<T>> handler;
  private final ArrayDeque<Entry<T>> pending = new ArrayDeque<>();
  private ScheduledFuture<?> alarm;
  private long alarmEpoch;
  private boolean running, closed;

  ResortBatcher(int size, int waitMs, ScheduledExecutorService timer,
      Consumer<Runnable> dispatch, Consumer<List<T>> handler) {
    if (size<1 || waitMs<1) throw new IllegalArgumentException("Invalid batch limits");
    this.size=size; this.waitNanos=TimeUnit.MILLISECONDS.toNanos(waitMs);
    this.timer=timer; this.dispatch=dispatch; this.handler=handler;
  }

  synchronized void offer(T value) {
    if (closed) return; // Source remains unacknowledged and is redelivered after disconnect.
    pending.add(new Entry<>(value, System.nanoTime()));
    schedule();
  }

  private void schedule() {
    if (running || pending.isEmpty()) return;
    if (closed || pending.size()>=size || System.nanoTime()-pending.peek().at>=waitNanos) {
      cancelAlarm();
      running=true;
      try { dispatch.accept(this::runBatch); }
      catch (RuntimeException failure) { running=false; throw failure; }
    } else if (alarm==null) {
      long epoch=++alarmEpoch;
      long delay=Math.max(0, waitNanos-(System.nanoTime()-pending.peek().at));
      alarm=timer.schedule(() -> {
        synchronized (ResortBatcher.this) {
          if (epoch!=alarmEpoch) return;
          alarm=null;
          schedule();
        }
      },delay,TimeUnit.NANOSECONDS);
    }
  }

  private void cancelAlarm() {
    alarmEpoch++;
    if (alarm!=null) { alarm.cancel(false); alarm=null; }
  }

  private void runBatch() {
    List<T> batch=new ArrayList<>(size);
    synchronized (this) {
      while (!pending.isEmpty() && batch.size()<size) batch.add(pending.remove().value);
    }
    try { handler.accept(batch); }
    finally {
      synchronized (this) { running=false; schedule(); }
    }
  }

  @Override public synchronized void close() { closed=true; cancelAlarm(); schedule(); }
  private static final class Entry<T> {
    final T value;
    final long at;
    Entry(T value,long at) { this.value=value; this.at=at; }
  }
}
