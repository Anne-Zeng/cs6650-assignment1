import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

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

  public SwipeSender(BlockingQueue<SwipeEvent> queue,
      HttpTransport client,
      String baseUrl,
      AtomicInteger successful,
      AtomicInteger failed,
      int maxEvents) {
    this.queue = queue;
    this.client = client;
    this.baseUrl = baseUrl;
    this.successful = successful;
    this.failed = failed;
    this.maxEvents = maxEvents;
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

  private boolean sendWithRetries(SwipeEvent event)
      throws InterruptedException {

    for (int attempt = 0; attempt <= 5; attempt++) {
      if (Thread.currentThread().isInterrupted()) {
        throw new InterruptedException("Sender interrupted");
      }
      try {
        int responseCode = client.post(
            baseUrl + event.path(), event.body(), attempt > 0);

        if (responseCode == 201) {
          return true;
        }
      } catch (java.io.IOException e) {
        // 网络错误也进入重试流程。
      }

      if (attempt < 5) {
        long delayMs = 100L << attempt;
        Thread.sleep(delayMs);
      }
    }

    return false;
  }
}