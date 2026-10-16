import java.net.http.HttpClient;
import java.time.Duration;

public class SubscriberDemo {

  public static void main(String[] args) throws Exception {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    try (LeaderboardConnection connection =
        new LeaderboardConnection(
            client, "ws://34.220.189.189:8080/ski-servlet", 7)) {

      connection.start();

      System.out.println(
          "Receive leaderboard updates from EC2 for 10 seconds, then exit."
      );

      Thread.sleep(10_000);

      System.out.println("Updates: " + connection.getUpdates());
      System.out.println("Connections: " + connection.getConnections());
      System.out.println(
          "Reconnections: " + connection.getReconnections()
      );
    }
  }
}