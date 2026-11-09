package skitrak.server;

import com.rabbitmq.client.AMQP;
import skitrak.common.Json;
import skitrak.common.SwipeMessage;
import skitrak.common.Topology;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

public final class SwipePublisher {
  private static final Logger LOG = Logger.getLogger(SwipePublisher.class.getName());
  enum Failure { ACQUIRE_TIMEOUT, CONFIRM_TIMEOUT, BROKER_NACK, UNROUTABLE, INTERRUPTED, PUBLISH_ERROR }
  private final EnumMap<Failure, AtomicLong> failureCounts = new EnumMap<>(Failure.class);
  public Map<String, Long> failureCounts() {
    Map<String, Long> values = new LinkedHashMap<>();
    failureCounts.forEach((reason, count) -> values.put(reason.name(), count.get()));
    return values;
  }
  private void recordFailure(Failure reason, String phase, Exception error) {
    long count = failureCounts.get(reason).incrementAndGet();
    // Sample repeated errors; never log payloads, connection URIs or credentials.
    if (count <= 3 || (count & (count - 1)) == 0) {
      LOG.warning("swipe_publish_failure reason=" + reason + " phase=" + phase
          + " exception=" + error.getClass().getSimpleName() + " categoryCount=" + count
          + " poolAvailable=" + pool.available() + " producerReady=" + pool.ready());
    }
  }
  private final ChannelPool pool;
  private final long borrowTimeout, confirmTimeout;
  public final LongAdder confirmed = new LongAdder(), failed = new LongAdder(), unroutable = new LongAdder();
  public SwipePublisher(ChannelPool pool, long borrowTimeout, long confirmTimeout) {
    for (Failure reason : Failure.values()) failureCounts.put(reason, new AtomicLong());
    this.pool = pool; this.borrowTimeout = borrowTimeout; this.confirmTimeout = confirmTimeout;
  }
  public void publish(SwipeMessage event) throws IOException, InterruptedException, TimeoutException {
    String phase = "acquire";
    try (ChannelPool.Lease lease = pool.borrow(borrowTimeout)) {
      phase = "publish";
      try {
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
            .contentType("application/json").contentEncoding("UTF-8").deliveryMode(2).messageId(event.eventId).build();
        lease.channel().basicPublish(Topology.SWIPE_EXCHANGE, "resort." + event.resortID,
            true, props, Json.encode(event).getBytes(StandardCharsets.UTF_8));
        phase = "confirm";
        if (!lease.channel().waitForConfirms(confirmTimeout)) throw new BrokerNackException();
        if (lease.returned() != null) { unroutable.increment(); throw new UnroutableException(); }
        confirmed.increment();
      } catch (UnroutableException error) { throw error;
      } catch (IOException | TimeoutException | RuntimeException error) {
        lease.invalidate(); throw error;
      } catch (InterruptedException error) { lease.invalidate(); throw error; }
    } catch (IOException | InterruptedException | TimeoutException | RuntimeException error) {
      failed.increment();
      Failure reason = error instanceof UnroutableException ? Failure.UNROUTABLE
          : error instanceof BrokerNackException ? Failure.BROKER_NACK
          : error instanceof InterruptedException ? Failure.INTERRUPTED
          : error instanceof TimeoutException ? ("acquire".equals(phase) ? Failure.ACQUIRE_TIMEOUT : Failure.CONFIRM_TIMEOUT)
          : Failure.PUBLISH_ERROR;
      recordFailure(reason, phase, error);
      throw error;
    }
  }
  static final class BrokerNackException extends IOException { BrokerNackException() { super("broker negatively acknowledged publish"); } }
  static final class UnroutableException extends IOException { UnroutableException() { super("no resort queue matched routing key"); } }
}
