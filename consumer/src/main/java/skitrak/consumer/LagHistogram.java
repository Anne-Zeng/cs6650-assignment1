package skitrak.consumer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/** Fixed-memory cumulative histogram; percentiles report bucket upper bounds. */
final class LagHistogram {
  private static final long[] UPPER_MS = {1, 2, 5, 10, 20, 50, 75, 100, 150, 200, 500,
      1000, 2000, 5000, 10000, 30000, 60000, Long.MAX_VALUE};
  private final AtomicLongArray buckets = new AtomicLongArray(UPPER_MS.length);
  private final AtomicLong samples = new AtomicLong();
  private final AtomicLong positiveSamples = new AtomicLong();
  private final AtomicLong negativeSamples = new AtomicLong();
  private final AtomicLong totalNanos = new AtomicLong();

  void record(long nanos) {
    samples.incrementAndGet();
    totalNanos.addAndGet(nanos);
    if (nanos < 0) { negativeSamples.incrementAndGet(); return; }
    positiveSamples.incrementAndGet();
    double millis = nanos / 1_000_000.0;
    for (int index = 0; index < UPPER_MS.length; index++) {
      if (millis <= UPPER_MS[index]) { buckets.incrementAndGet(index); return; }
    }
  }

  Map<String, Object> metrics() {
    Map<String, Object> result = new LinkedHashMap<>();
    long count = samples.get();
    result.put("consumerLagSamples", count);
    result.put("consumerLagNegativeSamples", negativeSamples.get());
    result.put("consumerLagAvgMs", count == 0 ? 0.0 : totalNanos.get() / (count * 1_000_000.0));
    result.put("consumerLagP95Ms", percentile(0.95));
    result.put("consumerLagP99Ms", percentile(0.99));
    result.put("consumerLagPercentilesAreBucketUpperBounds", true);
    return result;
  }

  private Object percentile(double percentile) {
    long count = positiveSamples.get();
    if (count == 0) return null;
    long desired = Math.max(1, (long) Math.ceil(count * percentile));
    long cumulative = 0;
    for (int index = 0; index < UPPER_MS.length; index++) {
      cumulative += buckets.get(index);
      if (cumulative >= desired) return UPPER_MS[index] == Long.MAX_VALUE ? ">60000" : UPPER_MS[index];
    }
    return null; // A concurrent sample has not yet updated its bucket.
  }
}
