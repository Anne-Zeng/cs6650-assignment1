import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
// coordinates event generation, subscriptions, warmup, main-phase workers, resource cleanup, and final metrics
//reporting.
//创建客户端工具
//    ↓
//    为每个 resort 创建连接对象并调用 start()
//    ↓
//    等待所有订阅至少成功连接一次
//    ↓
//    运行 warmup 和 main，期间持续接收排行榜（32 个发送线程 × 每个处理 1,000 条 = 32,000 条）
//    warmup 全部结束后，启动 128 个发送线程，继续处理：500,000 − 32,000 = 468,000 条
//    ↓
//    关闭连接、打印 WebSocket 统计/导出细 HTTP CSV
public class LoadClient {

  public static void main(String[] args) throws Exception {
    long programStart = System.nanoTime();
    String baseUrl = args.length > 0 ? args[0] : "http://localhost:8080";
    //第一步：生成 500,000 个事件
    int eventCount = args.length > 1 ? Integer.parseInt(args[1]) : 500_000;
    int mainThreads = args.length > 2 ? Integer.parseInt(args[2]) : 128;
    int warmupThreads = 32;
    int eventsPerWarmupThread = args.length > 3 ? Integer.parseInt(args[3]) : 1_000;
    //第五个运行参数指定 resort 数量；不填写时使用 10。
    int resortCount = args.length > 4 ? Integer.parseInt(args[4]) : 10;
    // 第六参数选择统计方式；第七参数固定事件序列，方便控制变量实验。
    SwipeMetrics.Mode metricsMode = SwipeMetrics.parseMode(args.length > 5 ? args[5] : "buffered");
    long seed = args.length > 6 ? Long.parseLong(args[6]) : 6650L;
    System.out.printf("Metrics mode: %s; seed=%d; Java=%s%n", metricsMode,
        seed, System.getProperty("java.version"));
    String consumerUrl = args.length > 7 ? args[7]
        : System.getenv().getOrDefault("CONSUMER_URL", "http://localhost:8081");
    int drainTimeoutSeconds = args.length > 8 ? Integer.parseInt(args[8]) : 120;
    if ((!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) || mainThreads < 1
        || resortCount < 1 || resortCount > 10 || drainTimeoutSeconds < 1
        || eventsPerWarmupThread < 1
        || eventCount < (long) warmupThreads * eventsPerWarmupThread) {
      throw new IllegalArgumentException(
          "Usage: LoadClient [http-base-url] [events] [main-threads] "
          + "[warmup-per-thread] [resort-count] [shared|buffered|off] [seed] [consumer-url] [drain-timeout-seconds]; "
          + "resort-count must be positive; events must cover 32 warmup workers");
    }
    DrainVerifier verifier = new DrainVerifier(consumerUrl);
    long processedBefore = verifier.awaitIdle(drainTimeoutSeconds);
    System.out.println("Consumer baseline unique events: " + processedBefore);
    baseUrl = baseUrl.replaceAll("/+$", "");
    String wsBaseUrl = baseUrl.startsWith("https://")
        ? "wss://" + baseUrl.substring("https://".length()) : "ws://" + baseUrl.substring("http://".length());
    System.out.printf("HTTP transport: Apache HttpClient 5.6.4; events=%d, mainThreads=%d%n",
        eventCount, mainThreads);

    //创建一个共享的线程安全队列，因为128 个线程同时从同一个队列取事件，需要避免两个线程取到同一个事件
    BlockingQueue<SwipeEvent> queue =//SwipeEvent 表示队列存放的是刷卡事件
        new ArrayBlockingQueue<>(eventCount);//eventCount 是队列容量 ArrayBlockingQueue 内部处理线程同步，不用自己加锁

    long generationStart = System.nanoTime();

    //然后把同一个 queue 交给生成器：创建线程和生成任务
    Thread generator = new Thread(
        new SwipeGenerator(queue, eventCount, resortCount, 2026, 1, seed),
        "swipe-generator"
    );

    //启动线程，由新线程执行 SwipeGenerator.run()生成事件并放入队列
    generator.start();
    //当前 main 线程等待生成线程结束
    generator.join();

    if (queue.size() != eventCount) {
      throw new IllegalStateException("Event generation incomplete");
    }

    double generationSeconds = secondsSince(generationStart);

    //第二步：建立排行榜订阅：创建 WebSocket 使用的客户端，用于建立排行榜连接。
    HttpClient webSocketClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    AtomicInteger successful = new AtomicInteger();
    AtomicInteger failed = new AtomicInteger();
    SwipeMetrics metrics = new SwipeMetrics(metricsMode);
    PushMetrics pushMetrics = new PushMetrics();

    List<LeaderboardConnection> subscribers = new ArrayList<>();
    String runId = Long.toString(System.currentTimeMillis());

    //创建 HTTP 发送工具，负责刷卡 POST 请求的 HTTP 连接池，try (...) 保证离开这个代码块时自动关闭 HttpTransport
    try (HttpTransport client = new HttpTransport(Math.max(warmupThreads, mainThreads))) {
      // 创建订阅: 每个 resort 建立并启动订阅。
      for (int resortID = 1; resortID <= resortCount; resortID++) {
        LeaderboardConnection connection =//创建管理该 resort 连接的对象
            new LeaderboardConnection(
                webSocketClient,
                wsBaseUrl,
                resortID,
                pushMetrics
            );
        //保存对象，方便之后检查、统计和关闭
        subscribers.add(connection);
        connection.start();//开始连接该 resort 的排行榜。
      }

      // 等待所有订阅至少成功建立过一次连接，最多等待 15 秒。
      //等待循环：等所有订阅首次连接成功后，再开始发送负载；这段等待不计入 ingest runtime。
      long deadline =
          System.nanoTime() + TimeUnit.SECONDS.toNanos(15);

      //等待所有订阅至少成功连接一次
      while (subscribers.stream()
          .anyMatch(connection -> connection.getConnections() == 0)) {

        //等待超过截止时间就报错，避免程序无限等待。循环里的 Thread.sleep(50) 则让主线程每隔约 50 ms 再检查一次
        if (System.nanoTime() >= deadline) {
          throw new IllegalStateException(
              "Some WebSocket subscriptions did not connect within 15 seconds"
          );
        }

        Thread.sleep(50);
      }

      // 预热开始，也是整个发送阶段的开始。32 个发送线程，每个线程重复 1,000 次，queue.poll() → 发送事件 → 等待结果/重试
      //等所有 warmup 线程完成，共处理 32,000 条，queue 剩余 468,000 条
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
      System.out.printf("Main throughput: %.6f swipes/sec%n",
          (successful.get() - warmupSuccessful) / mainSeconds);
      // 任务已结束：检查统计样本与真实 HTTP 尝试数一致，包括重试。
      if (metricsMode != SwipeMetrics.Mode.OFF
          && metrics.attemptCount() != client.getRequestAttempts()) {
        throw new IllegalStateException("HTTP attempt sample count mismatch");
      }

      System.out.println("\n--- Total ingest ---");
      System.out.println("Successful: " + successful.get());
      System.out.println("Failed: " + failed.get());
      System.out.println("Remaining in queue: " + queue.size());
      System.out.printf("Runtime: %.3f seconds%n", ingestSeconds);
      System.out.printf("Throughput: %.2f swipes/sec%n",//打印整体吞吐量
          successful.get() / ingestSeconds);//它衡量的是发送期间每秒成功完成多少条。完整运行时间单独报告，便于看清初始化和导出开销
      metrics.printResortThroughput(ingestSeconds, resortCount);
      client.printSummary();
      long drainStarted = System.nanoTime();
      java.util.Map<Integer, com.google.gson.JsonObject> finalSnapshots = verifier.await(
          processedBefore + successful.get(), subscribers, drainTimeoutSeconds);
      System.out.printf("Pipeline drain and final WebSocket verification: %.3f seconds%n", secondsSince(drainStarted));
      System.out.printf("End-to-end throughput: %.2f swipes/sec%n", successful.get() / secondsSince(ingestStart));
      java.nio.file.Files.createDirectories(Path.of("results"));
      java.nio.file.Files.writeString(Path.of("results", "final-snapshots-" + System.currentTimeMillis() + ".json"),
          new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(finalSnapshots));
      if (failed.get() > 0) throw new IllegalStateException("Run contained " + failed.get()
          + " finally failed logical events; inspect ambiguous outcomes by eventId");
    } finally {//发送结束后关闭订阅，避免后台继续重连。
      // WebSocket 断线由 LeaderboardConnection 负责：无论正常结束还是发生异常，都关闭订阅并停止重连。
      for (LeaderboardConnection connection : subscribers) {
        connection.close();
      }
      // Preserve attempt/push evidence even when drain or business checks fail.
      metrics.writeCsv(Path.of("results", "swipes-" + runId + ".csv"));
      pushMetrics.writeCsv(Path.of("results", "pushes-" + runId + ".csv"));
    }

    //测试结束后的统计
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
    // Matching CSV evidence was already exported in finally, including failed runs.
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

  // 让多个发送任务共享这个队列
  // 新增了两层检查：
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
    //在提交任务时，每个 SwipeSender 都收到同一个队列。SwipeSender.run()：取出并移除一个事件
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