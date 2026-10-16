import com.google.gson.Gson;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@ServerEndpoint("/leaderboard/{resortID}")//defines the WebSocket URL.
public class LeaderboardEndpoint {

  private static final Gson GSON = new Gson();

  private static final Map<String, Set<Session>> SUBSCRIBERS =
      new ConcurrentHashMap<>();

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

  @OnClose//removes it.
  public void onClose(Session session,
      @PathParam("resortID") String resortID) {
    removeSession(session, resortID);
  }

  @OnError
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