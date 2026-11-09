import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Locale;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.ArrayList;
import java.util.List;
//SwipeMetrics records HTTP attempts, exports CSV, and
// calculates latency statistics and throughput per resort.
public class SwipeMetrics {
  // shared 保留原实现；buffered 为正式优化模式；off 仅用于诊断开销。
  public enum Mode { SHARED, BUFFERED, OFF }
  private final Mode mode;

  public SwipeMetrics() { this(Mode.SHARED); }
  public SwipeMetrics(Mode mode) { this.mode = java.util.Objects.requireNonNull(mode); }
  public Mode getMode() { return mode; }
  public static Mode parseMode(String text) {
    try { return Mode.valueOf(text.toUpperCase(Locale.ROOT)); }
    catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("metrics mode must be shared, buffered or off", e);
    }
  }

  // 以下汇总 API 只能在所有任务 Future.get() 完成后调用。
  public long attemptCount() {
    return mode == Mode.BUFFERED ? bufferedAttemptCount() : samples.size();
  }

  @FunctionalInterface
  private interface Visitor {
    void accept(Instant sent, Instant received, long nanos, int status, int resort);
  }

  // 导出阶段才构造 Instant；发送热路径不创建每条 Sample/队列节点。
  private void visit(Visitor visitor) {
    if (mode == Mode.BUFFERED) {
      for (AttemptBuffer buffer : taskBuffers) {
        buffer.forEach((sent, received, nanos, status, resort) ->
            visitor.accept(Instant.ofEpochMilli(sent), Instant.ofEpochMilli(received),
                nanos, status, resort));
      }
    } else if (mode == Mode.SHARED) {
      for (Sample sample : samples) visitor.accept(sample.sentAt, sample.receivedAt,
          sample.latencyNanos, sample.statusCode, sample.resortID);
    }
  }

  // 统计仅读取基本类型，避免为了排序和 resort 计数创建时间对象。
  private void visitValues(AttemptBuffer.AttemptConsumer consumer) {
    if (mode == Mode.BUFFERED) {
      for (AttemptBuffer buffer : taskBuffers) buffer.forEach(consumer);
    } else if (mode == Mode.SHARED) {
      for (Sample sample : samples) consumer.accept(0, 0, sample.latencyNanos,
          sample.statusCode, sample.resortID);
    }
  }

  public double[] sortedLatenciesMs() {
    double[] values = new double[Math.toIntExact(attemptCount())];
    int[] index = {0};
    visitValues((sent, received, nanos, status, resort) -> values[index[0]++] = nanos / 1_000_000.0);
    Arrays.sort(values);
    return values;
  }

  public long[] successesPerResort(int resortCount) {
    long[] counts = new long[resortCount + 1];
    visitValues((sent, received, nanos, status, resort) -> {
      if (status == 201 && resort >= 1 && resort <= resortCount) counts[resort]++;
    });
    return counts;
  }

  //允许多个发送线程安全地添加记录。这种实现会占用与请求尝试数量成比例的内存。
  private final ConcurrentLinkedQueue<Sample> samples =
      new ConcurrentLinkedQueue<>();

  // 主线程保存各个发送任务的缓冲区引用。
// 每个缓冲区由一个发送任务独占写入。
  private final List<AttemptBuffer> taskBuffers = new ArrayList<>();

  /**
   * 由 LoadClient 的主线程在提交发送任务前调用。
   * 不允许发送线程调用这个方法。
   */
  public AttemptBuffer createTaskBuffer() {
    AttemptBuffer buffer = new AttemptBuffer();
    taskBuffers.add(buffer);
    return buffer;
  }

  /**
   * 只有所有发送任务结束后才能调用。
   * 返回所有任务实际记录的 HTTP 尝试数，包括重试。
   */
  public long bufferedAttemptCount() {
    long total = 0;
    for (AttemptBuffer buffer : taskBuffers) {
      total += buffer.size();
    }
    return total;
  }

  public void record(Instant sentAt,
      Instant receivedAt,
      long latencyNanos,
      int statusCode,
      int resortID) {
    if (mode != Mode.SHARED) throw new IllegalStateException("Shared recorder used in " + mode);
    samples.add(new Sample(
        sentAt, receivedAt, latencyNanos, statusCode, resortID
    ));
  }

  public void writeCsv(Path path) throws IOException {
    if (mode == Mode.OFF) {
      System.out.println("HTTP CSV disabled: diagnostic off mode");
      return;
    }
    Files.createDirectories(path.toAbsolutePath().getParent());

    try (BufferedWriter writer =
        Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
//responseTimestamp 保存响应完成时间，之后可以按完成时间统计每 10 秒的吞吐量
      writer.write(
          "timestamp,requestType,latency,statusCode,resortID,responseTimestamp"
      );
      writer.newLine();

      try {
        visit((sent, received, nanos, status, resort) -> {
          try {
            writer.write(sent + ",SWIPE," + (nanos / 1_000_000.0) + ","
                + status + "," + resort + "," + received);
            writer.newLine();
          } catch (IOException e) { throw new UncheckedIOException(e); }
        });
      } catch (UncheckedIOException e) { throw e.getCause(); }
    }

    System.out.println("HTTP attempts recorded: " + attemptCount());
    System.out.println("CSV saved to: " + path.toAbsolutePath());
  }

  //HTTP 延迟统计
//  Mean：平均耗时。
//  Median：排序后处于中间的耗时。
//  p95：至少 95% 的样本耗时不超过这个值。
//  p99：至少 99% 的样本耗时不超过这个值。
//  Min / Max：最短、最长耗时。
  public void printLatencySummary() {
    if (mode == Mode.OFF) {
      System.out.println("HTTP latency statistics disabled: diagnostic off mode");
      return;
    }
    double[] latencies = sortedLatenciesMs();

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

    if (mode == Mode.OFF) {
      System.out.println("Per-resort HTTP metrics disabled: diagnostic off mode");
      return;
    }
    long[] successes = successesPerResort(resortCount);

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