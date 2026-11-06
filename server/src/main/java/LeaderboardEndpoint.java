import com.google.gson.Gson;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
//管理排行榜订阅和推送内容,对应 WebSocket 地址：/leaderboard/{resortID}
@ServerEndpoint("/leaderboard/{resortID}")//defines the WebSocket URL.
public class LeaderboardEndpoint {

  private static final Gson GSON = new Gson();

  private static final Map<String, Set<Session>> SUBSCRIBERS =
      new ConcurrentHashMap<>();

  //客户端连接成功,将连接加入对应 resort 的集合
  @OnOpen//adds a connection to its resort’s subscriber set.
  public void onOpen(Session session,
      @PathParam("resortID") String resortID) {
    //添加和删除都通过 map 的原子更新操作完成，避免新 session 加入已被移除的集合
    SUBSCRIBERS.compute(resortID, (key, sessions) -> {
      if (sessions == null) {
        sessions = ConcurrentHashMap.newKeySet();
      }
      sessions.add(session);
      return sessions;
    });
//    SUBSCRIBERS.computeIfAbsent(
//        resortID, key -> ConcurrentHashMap.newKeySet()
//    ).add(session);
  }

  @OnClose//连接关闭,从集合中移除连接removes connection.
  public void onClose(Session session,
      @PathParam("resortID") String resortID) {
    removeSession(session, resortID);
  }

  @OnError//出现错误,记录错误，并清理订阅记录
  public void onError(Session session, Throwable error,
      @PathParam("resortID") String resortID) {
    error.printStackTrace();
    if (session != null) {
      removeSession(session, resortID);
    }
  }

  private static void removeSession(Session session, String resortID) {
    SUBSCRIBERS.computeIfPresent(resortID, (key, sessions) -> {
      sessions.remove(session);
      return sessions.isEmpty() ? null : sessions;
    });
  }

  //定时器调用,生成假排行榜，向各 resort 的订阅者发送
  //creates a fake leaderboard and sends it to connected subscribers.
  public static void pushAll() {//
    SUBSCRIBERS.forEach((resortID, sessions) -> {
      Map<String, Object> message = new LinkedHashMap<>();
      message.put("resortID", resortID);
      message.put("serverTimestamp", Instant.now().toString());
      message.put("top", Arrays.asList(
          skier(1, 40213, 44120),
          skier(2, 11872, 43880)
      ));

      String json = GSON.toJson(message);

      for (Session session : sessions) {
        if (session.isOpen()) {
          try {
            session.getAsyncRemote().sendText(json);
          } catch (RuntimeException error) {
            error.printStackTrace();
          }
        }
      }
    });
  }

  private static Map<String, Object> skier(
      int rank, int skierID, int vertical) {
    Map<String, Object> skier = new LinkedHashMap<>();
    skier.put("rank", rank);
    skier.put("skierID", skierID);
    skier.put("vertical", vertical);
    return skier;
  }
}