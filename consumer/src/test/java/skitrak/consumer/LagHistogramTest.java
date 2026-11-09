package skitrak.consumer;

import static org.junit.Assert.*;

import java.util.Map;
import org.junit.Test;

public class LagHistogramTest {
  @Test public void negativeClockOffsetsRemainVisibleAndPercentilesUseOnlyNonnegativeSamples() {
    LagHistogram histogram = new LagHistogram();
    histogram.record(-2_000_000);
    histogram.record(1_000_000);
    histogram.record(50_000_000);
    histogram.record(500_000_000);
    Map<String, Object> metrics = histogram.metrics();
    assertEquals(4L, metrics.get("consumerLagSamples"));
    assertEquals(1L, metrics.get("consumerLagNegativeSamples"));
    assertEquals(137.25, (double) metrics.get("consumerLagAvgMs"), 0.00001);
    assertEquals(500L, metrics.get("consumerLagP95Ms"));
    assertEquals(500L, metrics.get("consumerLagP99Ms"));
  }
}
