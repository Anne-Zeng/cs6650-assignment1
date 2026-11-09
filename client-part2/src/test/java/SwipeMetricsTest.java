import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** 验证新记录路径的数据完整性；测试使用本地服务，不访问 EC2。 */
public class SwipeMetricsTest {
  // 同一组成功/失败样本在两种存储方式下必须得到相同统计及 CSV。
  @Test public void sharedAndBufferedProduceSameResults() throws Exception {
    String expectedCsv = null;
    for (SwipeMetrics.Mode mode : new SwipeMetrics.Mode[] {
        SwipeMetrics.Mode.SHARED, SwipeMetrics.Mode.BUFFERED}) {
      SwipeMetrics metrics = new SwipeMetrics(mode);
      AttemptBuffer buffer = mode == SwipeMetrics.Mode.BUFFERED ? metrics.createTaskBuffer() : null;
      for (int i = 0; i < 2050; i++) {
        int status = i % 3 == 0 ? 0 : 201;
        if (buffer != null) buffer.record(1000+i, 1010+i, (i+1)*1000000L, status, 1);
        else metrics.record(Instant.ofEpochMilli(1000+i), Instant.ofEpochMilli(1010+i),
            (i+1)*1000000L, status, 1);
      }
      assertEquals(2050, metrics.attemptCount());
      assertEquals(1366, metrics.successesPerResort(2)[1]);
      assertEquals(0, metrics.successesPerResort(2)[2]);
      assertEquals(1.0, metrics.sortedLatenciesMs()[0], 0);
      assertEquals(2050.0, metrics.sortedLatenciesMs()[2049], 0);
      // 校验真正打印的百分位数，而不只检查存储条数。
      java.io.PrintStream original = System.out;
      java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
      java.util.Locale previousLocale = java.util.Locale.getDefault();
      try {
        java.util.Locale.setDefault(java.util.Locale.US);
        System.setOut(new java.io.PrintStream(output));
        metrics.printLatencySummary();
      } finally { System.setOut(original); java.util.Locale.setDefault(previousLocale); }
      String summary = output.toString();
      assertTrue(summary.contains("Mean: 1025.500 ms"));
      assertTrue(summary.contains("Median: 1025.500 ms"));
      assertTrue(summary.contains("p95: 1948.000 ms"));
      assertTrue(summary.contains("p99: 2030.000 ms"));
      Path path = Files.createTempFile("swipe-metrics-", ".csv");
      try {
        metrics.writeCsv(path);
        List<String> lines = Files.readAllLines(path);
        assertEquals(2051, lines.size());
        assertEquals("timestamp,requestType,latency,statusCode,resortID,responseTimestamp", lines.get(0));
        String csv = Files.readString(path);
        if (expectedCsv == null) expectedCsv = csv;
        else assertEquals(expectedCsv, csv);
      } finally { Files.deleteIfExists(path); }
    }
  }

  // 独占写入，Future.get 完成后主线程才读；跨越多块且不能漏掉任务。
  @Test public void concurrentTasksKeepAllSamples() throws Exception {
    SwipeMetrics metrics = new SwipeMetrics(SwipeMetrics.Mode.BUFFERED);
    ExecutorService workers = Executors.newFixedThreadPool(4);
    java.util.List<Future<?>> tasks = new java.util.ArrayList<>();
    try {
      for (int task = 1; task <= 4; task++) {
        AttemptBuffer buffer = metrics.createTaskBuffer();
        int resort = task;
        tasks.add(workers.submit(() -> {
          for (int i = 0; i < 2049; i++) buffer.record(i, i+1, 1000000, 201, resort);
        }));
      }
      for (Future<?> task : tasks) task.get();
      assertEquals(8196, metrics.attemptCount());
      for (int resort = 1; resort <= 4; resort++) assertEquals(2049, metrics.successesPerResort(4)[resort]);
    } finally { workers.shutdownNow(); }
  }

  // 新发送分支既记录失败尝试也记录成功重试，off 不制造空 CSV。
  @Test public void senderRecordsRetriesInEveryMode() throws Exception {
    for (SwipeMetrics.Mode mode : SwipeMetrics.Mode.values()) {
      AtomicInteger received = new AtomicInteger();
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange -> {
        exchange.getRequestBody().readAllBytes();
        exchange.sendResponseHeaders(received.incrementAndGet() == 1 ? 503 : 201, -1);
        exchange.close();
      });
      server.start();
      try (HttpTransport transport = new HttpTransport(1)) {
        SwipeMetrics metrics = new SwipeMetrics(mode);
        BlockingQueue<SwipeEvent> queue = new ArrayBlockingQueue<>(1);
        queue.add(new SwipeEvent(1, 2026, 1, 1, 1, 1));
        AtomicInteger success = new AtomicInteger(), failed = new AtomicInteger();
        new SwipeSender(queue, transport, "http://127.0.0.1:" + server.getAddress().getPort(),
            success, failed, 1, metrics).run();
        assertEquals(1, success.get());
        assertEquals(0, failed.get());
        assertEquals(2, transport.getRequestAttempts());
        assertEquals(1, transport.getRequestRetries());
        assertEquals(mode == SwipeMetrics.Mode.OFF ? 0 : 2, metrics.attemptCount());
        Path dir = Files.createTempDirectory("swipe-retry-");
        Path csv = dir.resolve("attempts.csv");
        try {
          metrics.writeCsv(csv);
          if (mode == SwipeMetrics.Mode.OFF) assertFalse(Files.exists(csv));
          else {
            List<String> lines = Files.readAllLines(csv);
            assertTrue(lines.get(1).contains(",503,1,"));
            assertTrue(lines.get(2).contains(",201,1,"));
          }
        } finally { Files.deleteIfExists(csv); Files.deleteIfExists(dir); }
      } finally { server.stop(0); }
    }
  }

  // 全部失败时仍需保留六次尝试；最终事件失败只增加一次。
  @Test public void bufferedFinalFailureKeepsSixAttempts() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(503, -1);
      exchange.close();
    });
    server.start();
    try (HttpTransport transport = new HttpTransport(1)) {
      SwipeMetrics metrics = new SwipeMetrics(SwipeMetrics.Mode.BUFFERED);
      BlockingQueue<SwipeEvent> queue = new ArrayBlockingQueue<>(1);
      queue.add(new SwipeEvent(1, 2026, 1, 1, 1, 1));
      AtomicInteger success = new AtomicInteger(), failed = new AtomicInteger();
      new SwipeSender(queue, transport, "http://127.0.0.1:" + server.getAddress().getPort(),
          success, failed, 1, metrics).run();
      assertEquals(0, success.get());
      assertEquals(1, failed.get());
      assertEquals(6, metrics.attemptCount());
      assertEquals(5, transport.getRequestRetries());
      assertEquals(0, metrics.successesPerResort(1)[1]);
    } finally { server.stop(0); }
  }

  @Test public void invalidModeFailsEarly() {
    try { SwipeMetrics.parseMode("typo"); fail("Expected invalid mode"); }
    catch (IllegalArgumentException expected) { }
  }
}
