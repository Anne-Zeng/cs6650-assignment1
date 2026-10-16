import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;

//加入 WebSocket 推送延迟，保存 resort、服务器时间、接收时间和延迟，并导出 CSV
//推送延迟 = 客户端收到完整消息的时间 − 消息中的 serverTimestamp
public class PushMetrics {

  private final ConcurrentLinkedQueue<Sample> samples =
      new ConcurrentLinkedQueue<>();

  public void record(
      int resortID,
      Instant serverTimestamp,
      Instant receivedAt) {

    double latencyMs =
        Duration.between(serverTimestamp, receivedAt).toNanos()
            / 1_000_000.0;

    samples.add(new Sample(
        resortID, serverTimestamp, receivedAt, latencyMs
    ));
  }

  public void writeCsv(Path path) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());

    try (BufferedWriter writer = Files.newBufferedWriter(
        path, StandardCharsets.UTF_8)) {

      writer.write(
          "resortID,serverTimestamp,receivedAt,pushLatencyMs"
      );
      writer.newLine();

      for (Sample sample : samples) {
        writer.write(
            sample.resortID + ","
                + sample.serverTimestamp + ","
                + sample.receivedAt + ","
                + sample.latencyMs
        );
        writer.newLine();
      }
    }

    System.out.println("Push CSV saved to: " + path.toAbsolutePath());
  }

  public void printSummary() {
    double[] sorted = samples.stream()
        .mapToDouble(sample -> sample.latencyMs)
        .sorted()
        .toArray();

    if (sorted.length == 0) {
      System.out.println("No push latency samples.");
      return;
    }

    double sum = 0;
    for (double value : sorted) {
      sum += value;
    }

    int n = sorted.length;

    double median = n % 2 == 0
        ? (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        : sorted[n / 2];

    System.out.println("\n--- WebSocket push latency ---");
    System.out.println("Samples: " + n);
    System.out.printf("Mean: %.3f ms%n", sum / n);
    System.out.printf("Median: %.3f ms%n", median);
    System.out.printf("p95: %.3f ms%n",
        sorted[(int) Math.ceil(n * 0.95) - 1]);
    System.out.printf("p99: %.3f ms%n",
        sorted[(int) Math.ceil(n * 0.99) - 1]);
  }

  private static class Sample {
    final int resortID;
    final Instant serverTimestamp;
    final Instant receivedAt;
    final double latencyMs;

    Sample(int resortID, Instant serverTimestamp,
        Instant receivedAt, double latencyMs) {
      this.resortID = resortID;
      this.serverTimestamp = serverTimestamp;
      this.receivedAt = receivedAt;
      this.latencyMs = latencyMs;
    }
  }
}