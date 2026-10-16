import javax.websocket.ClientEndpoint;
import javax.websocket.ContainerProvider;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

//增加了 30 秒等待超时和 finally 关闭连接，避免收不到回声时一直等待
@ClientEndpoint
public class EchoClient {

  private final CountDownLatch responsesRemaining;

  public EchoClient(CountDownLatch latch) {
    this.responsesRemaining = latch;
  }

  @OnMessage
  public void onMessage(String message) {
    responsesRemaining.countDown();
  }

  public static void main(String[] args) throws Exception {
    int messages = 100;
    CountDownLatch latch = new CountDownLatch(messages);

    WebSocketContainer container =
        ContainerProvider.getWebSocketContainer();

    Session session = container.connectToServer(
        new EchoClient(latch),
        URI.create("ws://localhost:8080/ski-servlet/echo")
    );

    try {
      long start = System.nanoTime();

      for (int i = 0; i < messages; i++) {
        session.getBasicRemote().sendText("msg-" + i);
      }

      boolean completed = latch.await(30, TimeUnit.SECONDS);
      double elapsedMs =
          (System.nanoTime() - start) / 1_000_000.0;

      long received = messages - latch.getCount();

      System.out.println("Sent: " + messages);
      System.out.println("Received: " + received);

      if (!completed) {
        throw new IllegalStateException(
            "Timed out waiting for all echoes"
        );
      }

      System.out.printf(
          "%d echoes received in %.3f ms%n",
          received, elapsedMs
      );
    } finally {
      session.close();
    }
  }
}