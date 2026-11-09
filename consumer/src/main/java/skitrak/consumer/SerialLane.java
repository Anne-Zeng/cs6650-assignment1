package skitrak.consumer;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** FIFO application order per resort, with parallel work only across different resorts. */
final class SerialLane {
  private final Executor executor;
  private final Queue<Runnable> pending = new ArrayDeque<>();
  private boolean running;

  SerialLane(Executor executor) { this.executor = executor; }

  synchronized void submit(Runnable task) {
    pending.add(task);
    if (!running) {
      running = true;
      executor.execute(this::runOne);
    }
  }

  synchronized int size() { return pending.size() + (running ? 1 : 0); }

  private void runOne() {
    Runnable task;
    synchronized (this) { task = pending.poll(); }
    try { task.run(); } catch (Throwable failure) {
      // Keep the lane alive: delivery processing records its own failure and controls ACKs.
      System.err.println("Unexpected resort lane error: " + failure);
    } finally {
      synchronized (this) {
        if (pending.isEmpty()) running = false;
        else {
          try { executor.execute(this::runOne); } // Yield to other resorts between messages.
          catch (RejectedExecutionException shutdown) {
            // Unacknowledged AMQP deliveries are requeued when shutdown closes their channel.
            pending.clear();
            running = false;
          }
        }
      }
    }
  }
}
