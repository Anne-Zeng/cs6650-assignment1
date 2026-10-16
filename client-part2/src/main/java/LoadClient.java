import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

public class LoadClient {

  public static void main(String[] args) throws Exception {
    long programStart = System.nanoTime();
    String baseUrl = args.length > 0 ? args[0] : "http://34.220.189.189:8080/ski-servlet";
    int eventCount = args.length > 1 ? Integer.parseInt(args[1]) : 500_000;
    int mainThreads = args.length > 2 ? Integer.parseInt(args[2]) : 128;
    int warmupThreads = 32;
    int eventsPerWarmupThread = args.length > 3 ? Integer.parseInt(args[3]) : 1_000;
    //第五个运行参数指定 resort 数量；不填写时使用 10。
    int resortCount = args.length > 4 ? Integer.parseInt(args[4]) : 10;
    if (!baseUrl.startsWith("http://") || mainThreads < 1
        || resortCount < 1
        || eventsPerWarmupThread < 1
        || eventCount < (long) warmupThreads * eventsPerWarmupThread) {
      throw new IllegalArgumentException(
          "Usage: LoadClient [http-base-url] [events] [main-threads] "
          + "[warmup-per-thread] [resort-count]; "
          + "resort-count must be positive; events must cover 32 warmup workers");
    }
    String wsBaseUrl = "ws://" + baseUrl.substring("http://".length());
    System.out.printf("HTTP transport: Apache HttpClient 5.6.4; events=%d, mainThreads=%d%n",
        eventCount, mainThreads);

    BlockingQueue<SwipeEvent> queue =
        new ArrayBlockingQueue<>(eventCount);

    long generationStart = System.nanoTime();

    Thread generator = new Thread(
        new SwipeGenerator(queue, eventCount, resortCount, 2026, 1),
        "swipe-generator"
    );

    generator.start();
    generator.join();

    if (queue.size() != eventCount) {
      throw new IllegalStateException("Event generation incomplete");
    }

    double generationSeconds = secondsSince(generationStart);

    HttpClient webSocketClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    AtomicInteger successful = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();
    SwipeMetrics metrics = new SwipeMetrics();
    PushMetrics pushMetrics = new PushMetrics();

    List<LeaderboardConnection> subscribers = new ArrayList<>();

    try (HttpTransport client = new HttpTransport(Math.max(warmupThreads, mainThreads))) {
      // 创建订阅: 每个 resort 建立订阅。
      for (int resortID = 1; resortID <= resortCount; resortID++) {
        LeaderboardConnection connection =
            new LeaderboardConnection(
                webSocketClient,
                wsBaseUrl,
                resortID,
                pushMetrics
            );

        subscribers.add(connection);
        connection.start();//开始连接该 resort 的排行榜。
      }

      // 等待所有订阅至少成功建立过一次连接，最多等待 15 秒。
      //等待循环：等所有订阅首次连接成功后，再开始发送负载；这段等待不计入 ingest runtime。
      long deadline =
          System.nanoTime() + TimeUnit.SECONDS.toNanos(15);

      while (subscribers.stream()
          .anyMatch(connection -> connection.getConnections() == 0)) {

        if (System.nanoTime() >= deadline) {
          throw new IllegalStateException(
              "Some WebSocket subscriptions did not connect within 15 seconds"
          );
        }

        Thread.sleep(50);
      }

      // 预热开始，也是整个发送阶段的开始。
      long ingestStart = System.nanoTime();

      runPhase(
          warmupThreads, eventsPerWarmupThread,
          queue, client, baseUrl, successful, failed, metrics
      );

      // 共享一个阶段分界点，避免计时范围重叠。
      long mainStart = System.nanoTime();

      double warmupSeconds =
          (mainStart - ingestStart) / 1_000_000_000.0;

      int warmupSuccessful = successful.get();
      int warmupFailed = failed.get();

      runPhase(
          mainThreads, Integer.MAX_VALUE,
          queue, client, baseUrl, successful, failed, metrics
      );

      //两个阶段在计算上满足：预热时间 + 主阶段时间 = 总发送时间
      long ingestEnd = System.nanoTime();

      double mainSeconds =
          (ingestEnd - mainStart) / 1_000_000_000.0;

      double ingestSeconds =
          (ingestEnd - ingestStart) / 1_000_000_000.0;

      System.out.printf("Generation time: %.3f seconds%n",
          generationSeconds);

      System.out.println("\n--- Warmup ---");
      System.out.println("Successful: " + warmupSuccessful);
      System.out.println("Failed: " + warmupFailed);
      System.out.printf("Runtime: %.3f seconds%n", warmupSeconds);

      System.out.println("\n--- Main ---");
      System.out.println("Successful: "
          + (successful.get() - warmupSuccessful));
      System.out.println("Failed: "
          + (failed.get() - warmupFailed));
      System.out.printf("Runtime: %.3f seconds%n", mainSeconds);

      System.out.println("\n--- Total ingest ---");
      System.out.println("Successful: " + successful.get());
      System.out.println("Failed: " + failed.get());
      System.out.println("Remaining in queue: " + queue.size());
      System.out.printf("Runtime: %.3f seconds%n", ingestSeconds);
      System.out.printf("Throughput: %.2f swipes/sec%n",//打印整体吞吐量
          successful.get() / ingestSeconds);//它衡量的是发送期间每秒成功完成多少条。完整运行时间单独报告，便于看清初始化和导出开销
      metrics.printResortThroughput(ingestSeconds, resortCount);
      client.printSummary();
    } finally {//发送结束后关闭订阅，避免后台继续重连。
      // 无论正常结束还是发生异常，都关闭订阅并停止重连。
      for (LeaderboardConnection connection : subscribers) {
        connection.close();
      }
    }

    System.out.println("\n--- WebSocket subscriptions ---");

    int totalUpdates = 0;

    for (int i = 0; i < subscribers.size(); i++) {
      LeaderboardConnection connection = subscribers.get(i);
      int updates = connection.getUpdates();
      totalUpdates += updates;

      System.out.printf(
          "Resort %d: updates=%d, connections=%d, "
              + "reconnections=%d, avgConnectMs=%.3f%n",
          i + 1,
          updates,
          connection.getConnections(),
          connection.getReconnections(),
          connection.getAverageConnectionMs()
      );
    }

    System.out.println("Total leaderboard updates: " + totalUpdates);
    //文件名包含时间，避免每次运行覆盖上一次结果。文件写入发生在发送计时结束之后。
    String runId = Long.toString(System.currentTimeMillis());

    //同一次测试生成的两个文件使用相同编号，便于配对
    metrics.writeCsv(Path.of(
        "results", "swipes-" + runId + ".csv"
    ));

    pushMetrics.writeCsv(Path.of(
        "results", "pushes-" + runId + ".csv"
    ));
//    metrics.writeCsv(Path.of(
//        "results", "swipes-" + System.currentTimeMillis() + ".csv"
//    ));
    metrics.printLatencySummary();
    pushMetrics.printSummary();
    System.out.printf(
        "%nTotal program runtime: %.3f seconds%n",
        secondsSince(programStart)
    );
  }

  //新增了两层检查：
  //- 任务异常检查：发送任务意外抛出异常时，明确报告错误。
  //- 数量检查：本阶段的成功数加最终失败数，必须等于应处理的事件数；队列也必须减少相同数量。
  private static void runPhase(
      int threads,
      int maxEventsPerTask,
      BlockingQueue<SwipeEvent> queue,
      HttpTransport client,
      String baseUrl,
      AtomicInteger successful,
      AtomicInteger failed,
      SwipeMetrics metrics
  ) throws InterruptedException {

    ExecutorService executor =
        Executors.newFixedThreadPool(threads);

    List<Future<?>> tasks = new ArrayList<>();

    int completedBefore = successful.get() + failed.get();
    int queuedBefore = queue.size();

    int expected = (int) Math.min(
        (long) queuedBefore,
        (long) threads * maxEventsPerTask
    );

    try {
      for (int i = 0; i < threads; i++) {
        tasks.add(executor.submit(new SwipeSender(
            queue, client, baseUrl,
            successful, failed, maxEventsPerTask, metrics
        )));
      }

      executor.shutdown();

      if (!executor.awaitTermination(30, TimeUnit.MINUTES)) {
        throw new IllegalStateException("Phase timed out");
      }

      // 线程结束不一定代表成功，需要检查任务结果。
      for (Future<?> task : tasks) {
        try {
          task.get();
        } catch (ExecutionException e) {
          throw new IllegalStateException(
              "Sender task failed", e.getCause()
          );
        }
      }

      int completed =
          successful.get() + failed.get() - completedBefore;

      if (completed != expected
          || queue.size() != queuedBefore - expected) {
        throw new IllegalStateException(
            "Incomplete phase: expected=" + expected
                + ", completed=" + completed
                + ", remaining=" + queue.size()
        );
      }

    } finally {
      executor.shutdownNow();
    }
  }

  private static double secondsSince(long start) {
    return (System.nanoTime() - start) / 1_000_000_000.0;
  }
}