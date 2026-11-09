import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

public class PushMetricsTest {
  @Test public void historicalBootstrapDoesNotBiasUpdateSummaryAndRawValuesAreRetained() throws Exception {
    PushMetrics metrics = new PushMetrics();
    Instant received = Instant.parse("2026-11-08T02:00:00Z");
    metrics.record(1, received.minusSeconds(7200), received, true);
    metrics.record(1, received.plusMillis(65), received, false);
    metrics.record(1, received.minusMillis(25), received, false);
    PrintStream previous=System.out;
    ByteArrayOutputStream output=new ByteArrayOutputStream();
    try { System.setOut(new PrintStream(output,true,StandardCharsets.UTF_8)); metrics.printSummary(); }
    finally { System.setOut(previous); }
    String summary=output.toString(StandardCharsets.UTF_8);
    assertTrue(summary.contains("Samples: 2"));
    assertTrue(summary.contains("excluded from summary (retained in CSV): 1"));
    assertTrue(summary.contains("Negative update samples (clock offset): 1"));
    Path csv=Files.createTempFile("push-metrics-", ".csv");
    try {
      metrics.writeCsv(csv); String raw=Files.readString(csv);
      assertTrue(raw.contains("7200000.0,initial_snapshot"));
      assertTrue(raw.contains("-65.0,update"));
    } finally { Files.deleteIfExists(csv); }
  }
}
