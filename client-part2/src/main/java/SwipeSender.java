import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;

//发送任务 SwipeSender：从队列取事件，通过共享的 HttpClient 发给 Tomcat。
//首次发送之外，再尝试最多 5 次。
//计数也很重要：同一条事件即使尝试了多次，最终也只计为 一次成功或一次失败。
// 网络超时时，服务器可能已经收到事件，因此重试可能产生重复请求；当前 dummy server
// 不保存数据，但后续真实系统需要处理这个问题。
public class SwipeSender implements Runnable {

  private final BlockingQueue<SwipeEvent> queue;
  private final HttpTransport client;
  private final String baseUrl;
  private final AtomicInteger successful;
  private final AtomicInteger failed;
  private final int maxEvents;
  private final SwipeMetrics metrics;

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
  }

  @Override
  public void run() {
    for (int sent = 0; sent < maxEvents; sent++) {
      SwipeEvent event = queue.poll();

      if (event == null) {
        return;
      }

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
  private boolean sendWithRetries(SwipeEvent event)
      throws InterruptedException {

    for (int attempt = 0; attempt <= 5; attempt++) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("Sender interrupted");
      }
      Instant sentAt = Instant.now();
      long start = System.nanoTime();

      // 0 表示没有收到 HTTP 响应，不是真实 HTTP 状态码。
      int statusCode = 0;

      try {
        int responseCode = client.post(
            baseUrl + event.path(), event.body(), attempt > 0);

        statusCode = responseCode;

      } catch (java.io.IOException e) {
        // 记录此次网络失败，然后按原逻辑重试。
      } finally {
        long latencyNanos = System.nanoTime() - start;
        Instant receivedAt = Instant.now();

        metrics.record(
            sentAt,
            receivedAt,
            latencyNanos,
            statusCode,
            event.getResortID()
        );
      }

      if (statusCode == 201) {
        return true;
      }

      if (attempt < 5) {
        Thread.sleep(100L << attempt);
      }
    }

    return false;
  }
}