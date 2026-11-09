import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;
//SwipeSender removes events from the queue and sends them through the shared HttpTransport,
// applying application-level retries.
//发送任务 SwipeSender：从队列取事件，通过共享的 HttpClient 发给 Tomcat。
//首次发送之外，再尝试最多 5 次。
//计数也很重要：同一条事件即使尝试了多次，最终也只计为 一次成功或一次失败。
// 网络超时时，服务器可能已经收到事件；同一 SwipeEvent 的 UUID 在所有重试中保持不变。
public class SwipeSender implements Runnable {

  private final BlockingQueue<SwipeEvent> queue;
  private final HttpTransport client;
  private final String baseUrl;
  private final AtomicInteger successful;
  private final AtomicInteger failed;
  private final int maxEvents;
  private final SwipeMetrics metrics;
  // 主线程在提交任务前创建；当前任务独占写入。
  private final AttemptBuffer attemptBuffer;

  public SwipeSender(BlockingQueue<SwipeEvent> queue,
      HttpTransport client,
      String baseUrl,
      AtomicInteger successful,
      AtomicInteger failed,
      int maxEvents,
      SwipeMetrics metrics) {
    this.queue = queue;
    this.client = client;
    this.baseUrl = baseUrl;
    this.successful = successful;
    this.failed = failed;
    this.maxEvents = maxEvents;
    this.metrics = metrics;
    this.attemptBuffer = metrics.getMode() == SwipeMetrics.Mode.BUFFERED
        ? metrics.createTaskBuffer() : null;
  }

  //queue.poll()：把“取出”和“移除”作为一次线程安全操作完成
  @Override
  public void run() {
    for (int sent = 0; sent < maxEvents; sent++) {
      SwipeEvent event = queue.poll();

      if (event == null) {
        return;
      }

      //取出后，当前线程负责这条事件的发送和重试
      try {
        if (sendWithRetries(event)) {
          successful.incrementAndGet();
        } else {
          failed.incrementAndGet();
        }
      } catch (InterruptedException e) {
        failed.incrementAndGet();
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  //每次尝试记录一行，包括重试。退避等待时间不算入单次请求延迟。 没有收到响应时，responseTimestamp 表示该尝试结束的时间
  static boolean isRetryable(int status) {
    return status == 0 || status == 408 || status == 429 || status == 500
        || status == 502 || status == 503 || status == 504;
  }

  private boolean sendWithRetries(SwipeEvent event)
      throws InterruptedException {
//HTTP 失败由 SwipeSender 负责，首次尝试失败等待 100 ms，重试，最多重试 5 次（包括首次共 6 次尝试）
    for (int attempt = 0; attempt <= 5; attempt++) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("Sender interrupted");
      }
      // 对照模式保持原 Instant 记录方式；off 连测量时钟调用也省略。
      boolean shared = metrics.getMode() == SwipeMetrics.Mode.SHARED;
      boolean enabled = metrics.getMode() != SwipeMetrics.Mode.OFF;
      Instant sentAt = shared ? Instant.now() : null;
      long sentMillis = attemptBuffer != null ? System.currentTimeMillis() : 0;
      long start = enabled ? System.nanoTime() : 0;

      // 0 表示没有收到 HTTP 响应，不是真实 HTTP 状态码。
      int statusCode = 0;

      try {//把事件转换成 HTTP 请求并发送
        //event.path() 生成 URL 中的路径， event.body() 生成 JSON 请求体
        int responseCode = client.post(
            baseUrl + event.path(), event.body(), attempt > 0, event.getEventId());
        //把返回的 HTTP 状态码存进 responseCode
        statusCode = responseCode;

      } catch (java.io.IOException e) {
        // 记录此次网络失败，然后按原逻辑重试。
      } finally {
        if (enabled) {
          long latencyNanos = System.nanoTime() - start;
          if (attemptBuffer != null) {
            attemptBuffer.record(sentMillis, System.currentTimeMillis(),
                latencyNanos, statusCode, event.getResortID());
          } else {
            metrics.record(sentAt, Instant.now(), latencyNanos,
                statusCode, event.getResortID());
          }
        }
      }
      // EC2 返回 201->SwipeSender 增加成功计数，并记录测量数据->SwipeMetrics
      if (statusCode == 201) {
        return true;
      }

      if (!isRetryable(statusCode)) return false;
      if (attempt < 5) {
        Thread.sleep((100L << attempt) + java.util.concurrent.ThreadLocalRandom.current().nextLong(50));
      }
    }

    return false;
  }
}