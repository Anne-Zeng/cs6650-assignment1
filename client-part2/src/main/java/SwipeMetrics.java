import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SwipeMetrics {

  //允许多个发送线程安全地添加记录。这种实现会占用与请求尝试数量成比例的内存。
  private final ConcurrentLinkedQueue<Sample> samples =
      new ConcurrentLinkedQueue<>();

  public void record(Instant sentAt,
      Instant receivedAt,
      long latencyNanos,
      int statusCode,
      int resortID) {
    samples.add(new Sample(
        sentAt, receivedAt, latencyNanos, statusCode, resortID
    ));
  }

  public void writeCsv(Path path) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());

    try (BufferedWriter writer =
        Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
//responseTimestamp 保存响应完成时间，之后可以按完成时间统计每 10 秒的吞吐量
      writer.write(
          "timestamp,requestType,latency,statusCode,resortID,responseTimestamp"
      );
      writer.newLine();

      for (Sample sample : samples) {
        writer.write(
            sample.sentAt + ",SWIPE,"
                + (sample.latencyNanos / 1_000_000.0) + ","
                + sample.statusCode + ","
                + sample.resortID + ","
                + sample.receivedAt
        );
        writer.newLine();
      }
    }

    System.out.println("HTTP attempts recorded: " + samples.size());
    System.out.println("CSV saved to: " + path.toAbsolutePath());
  }

  //HTTP 延迟统计
//  Mean：平均耗时。
//  Median：排序后处于中间的耗时。
//  p95：至少 95% 的样本耗时不超过这个值。
//  p99：至少 99% 的样本耗时不超过这个值。
//  Min / Max：最短、最长耗时。
  public void printLatencySummary() {
    double[] latencies = samples.stream()
        .mapToDouble(sample -> sample.latencyNanos / 1_000_000.0)
        .sorted()
        .toArray();

    if (latencies.length == 0) {
      System.out.println("No HTTP latency samples.");
      return;
    }

    double sum = 0;
    for (double latency : latencies) {
      sum += latency;
    }

    int n = latencies.length;

    double median = n % 2 == 0
        ? (latencies[n / 2 - 1] + latencies[n / 2]) / 2.0
        : latencies[n / 2];

    System.out.println("\n--- HTTP attempt latency ---");
    System.out.println("Samples: " + n);
    System.out.printf("Mean: %.3f ms%n", sum / n);
    System.out.printf("Median: %.3f ms%n", median);
    System.out.printf("p95: %.3f ms%n", percentile(latencies, 0.95));
    System.out.printf("p99: %.3f ms%n", percentile(latencies, 0.99));
    System.out.printf("Min: %.3f ms%n", latencies[0]);
    System.out.printf("Max: %.3f ms%n", latencies[n - 1]);
  }

  private static double percentile(double[] sorted, double fraction) {
    int index = (int) Math.ceil(fraction * sorted.length) - 1;
    return sorted[index];
  }

  //某个 resort 的吞吐量= 该 resort 成功的请求数 ÷ 整个 ingest 阶段耗时
  public void printResortThroughput(
      double ingestSeconds, int resortCount) {

    long[] successes = new long[resortCount + 1];

    for (Sample sample : samples) {
      if (sample.statusCode == 201
          && sample.resortID >= 1
          && sample.resortID <= resortCount) {
        successes[sample.resortID]++;
      }
    }

    System.out.println("\n--- Throughput per resort ---");

    for (int resortID = 1; resortID <= resortCount; resortID++) {
      System.out.printf(
          "Resort %d: successful=%d, throughput=%.2f swipes/sec%n",
          resortID,
          successes[resortID],
          successes[resortID] / ingestSeconds
      );
    }
  }

  private static class Sample {
    final Instant sentAt;
    final Instant receivedAt;
    final long latencyNanos;
    final int statusCode;
    final int resortID;

    Sample(Instant sentAt, Instant receivedAt,
        long latencyNanos, int statusCode, int resortID) {
      this.sentAt = sentAt;
      this.receivedAt = receivedAt;
      this.latencyNanos = latencyNanos;
      this.statusCode = statusCode;
      this.resortID = resortID;
    }
  }
}