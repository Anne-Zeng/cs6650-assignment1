import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.WebSocket;
import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;//接收回调更新计数，主线程也可以安全读取。

public class LeaderboardSubscriber implements WebSocket.Listener {

  private final int resortID;
  private final AtomicInteger updates = new AtomicInteger();
  private final StringBuilder messageBuffer = new StringBuilder();
  private final Runnable onDisconnect;
  //LoadClient 创建一个 PushMetrics
  //    → 传给 10 个 LeaderboardConnection
  //    → 每次连接或重连，都传给 LeaderboardSubscriber
  //    → 收到消息时记录延迟
  private final PushMetrics pushMetrics;

  //三个构造器方法，前两个构造方法保留了之前示例程序的调用方式；第三个允许传入共享的统计对象。
  public LeaderboardSubscriber(int resortID) {
    this(resortID, () -> {}, new PushMetrics());
  }

  public LeaderboardSubscriber(int resortID, Runnable onDisconnect) {
    this(resortID, onDisconnect, new PushMetrics());
  }

  public LeaderboardSubscriber(
      int resortID,
      Runnable onDisconnect,
      PushMetrics pushMetrics) {
    this.resortID = resortID;
    this.onDisconnect = onDisconnect;
    this.pushMetrics = pushMetrics;
  }

  //连接成功时执行
  @Override
  public void onOpen(WebSocket webSocket) {
    System.out.println("Connected to resort " + resortID);
    webSocket.request(1);//允许客户端继续接收下一个消息回调。
  }

  //收到文本消息时执行；last 表示这条消息是否收完整。
  @Override
  public CompletionStage<?> onText(
      WebSocket webSocket,
      CharSequence data,
      boolean last) {

    // 一条消息可能被拆成多个片段。
    messageBuffer.append(data);

    if (last) {
      Instant receivedAt = Instant.now();
      String message = messageBuffer.toString();
      messageBuffer.setLength(0);

      try {
        JsonObject json =
            JsonParser.parseString(message).getAsJsonObject();

        Instant serverTimestamp = Instant.parse(
            json.get("serverTimestamp").getAsString()
        );
        //每收到一条完整且能解析的排行榜消息，就记录一次推送延迟。原始记录能对应到具体订阅的 resort
        pushMetrics.record(resortID, serverTimestamp, receivedAt);

        int count = updates.incrementAndGet();

        // 这里只打印前三条，避免频繁打印影响性能。
        if (count <= 3) {
          System.out.println("Leaderboard JSON: " + message);
          System.out.println("Resort " + resortID
              + ", update " + count
              + ", server time: " + serverTimestamp
              + ", received at: " + receivedAt);
        }
      } catch (RuntimeException e) {
        System.err.println(
            "Invalid leaderboard message: " + e.getMessage()
        );
      }
    }

    webSocket.request(1);
    return null;
  }

  @Override
  public CompletionStage<?> onClose(
      WebSocket webSocket, int statusCode, String reason) {
    onDisconnect.run();
    return null;
  }

  @Override
  public void onError(WebSocket webSocket, Throwable error) {
    onDisconnect.run();
  }

  public int getUpdates() {
    return updates.get();
  }
}