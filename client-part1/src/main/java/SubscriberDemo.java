import java.net.http.HttpClient;
import java.time.Duration;

public class SubscriberDemo {

  public static void main(String[] args) throws Exception {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    try (LeaderboardConnection connection =
        new LeaderboardConnection(
            client, "ws://localhost:8080/ski-servlet", 7)) {

      connection.start();

      System.out.println(
          "观察 60 秒：连接成功后停止 Tomcat，再重新启动 Tomcat。"
      );

      Thread.sleep(60_000);

      System.out.println("Updates: " + connection.getUpdates());
      System.out.println("Connections: " + connection.getConnections());
      System.out.println(
          "Reconnections: " + connection.getReconnections()
      );
    }
  }
}