import javax.websocket.ContainerProvider;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

//这段代码记录实际收到的回声与失败数，避免把失败线程原本计划发送的消息也当成成功吞吐量。
public class MultiThreadEchoClient {

  public static void main(String[] args) throws InterruptedException {
    int numThreads = args.length > 0
        ? Integer.parseInt(args[0]) : 8;
    int messagesPerThread = 100;

    CountDownLatch threadsDone = new CountDownLatch(numThreads);
    AtomicInteger successfulConnections = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();
    AtomicLong receivedTotal = new AtomicLong();

    long start = System.nanoTime();

    for (int t = 0; t < numThreads; t++) {
      final int threadId = t;

      new Thread(() -> {
        Session session = null;
        CountDownLatch echoes =
            new CountDownLatch(messagesPerThread);

        try {
          WebSocketContainer container =
              ContainerProvider.getWebSocketContainer();

          session = container.connectToServer(
              new EchoClient(echoes),
              URI.create("ws://localhost:8080/ski-servlet/echo")
          );

          for (int i = 0; i < messagesPerThread; i++) {
            session.getBasicRemote().sendText(
                "thread-" + threadId + "-msg-" + i
            );
          }

          if (!echoes.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                "Timed out waiting for echoes"
            );
          }

          successfulConnections.incrementAndGet();
        } catch (Exception e) {
          failures.incrementAndGet();
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
          System.err.println(
              "Thread " + threadId + " failed: " + e
          );
        } finally {
          try {
            if (session != null && session.isOpen()) {
              session.close();
            }
          } catch (Exception e) {
            System.err.println(
                "Close failed for thread " + threadId
                    + ": " + e
            );
          } finally {
            receivedTotal.addAndGet(
                messagesPerThread - echoes.getCount()
            );
            threadsDone.countDown();
          }
        }
      }, "echo-worker-" + t).start();
    }

    threadsDone.await();

    double elapsedMs =
        (System.nanoTime() - start) / 1_000_000.0;
    long expected = (long) numThreads * messagesPerThread;

    System.out.println("Connections requested: " + numThreads);
    System.out.println(
        "Connections completing all echoes: "
            + successfulConnections.get()
    );
    System.out.println("Failed workers: " + failures.get());
    System.out.println("Expected echoes: " + expected);
    System.out.println("Received echoes: " + receivedTotal.get());
    System.out.printf("Duration: %.3f ms%n", elapsedMs);
    System.out.printf(
        "Received throughput: %.2f msg/s%n",
        receivedTotal.get() * 1000.0 / elapsedMs
    );
  }
}