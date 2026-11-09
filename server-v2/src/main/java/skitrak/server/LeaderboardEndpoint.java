package skitrak.server;

import javax.websocket.*;
import javax.websocket.server.*;
import java.io.IOException;
import java.util.List;

@ServerEndpoint("/leaderboard/{resortID}")
public final class LeaderboardEndpoint {
  @OnOpen public void open(Session session, @PathParam("resortID") String resort) throws IOException {
    try {
      int id = Integer.parseInt(resort);
      if (id < 1 || id > 10 || !resort.equals(Integer.toString(id))) throw new IllegalArgumentException();
      List<String> query = session.getRequestParameterMap().get("dayID");
      String day = query == null || query.isEmpty() ? "1" : query.get(0);
      int dayId = Integer.parseInt(day);
      if (dayId < 1 || dayId > 366 || !day.equals(Integer.toString(dayId))) throw new IllegalArgumentException();
      ServerRuntime.get().hub.join(session, resort, day);
    } catch (RuntimeException error) { session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "resort must be 1..10 and dayID must be 1..366")); }
  }
  @OnClose public void close(Session session, @PathParam("resortID") String resort) { cleanup(session, resort); }
  @OnError public void error(Session session, Throwable error, @PathParam("resortID") String resort) { if (session != null) cleanup(session, resort); }
  private void cleanup(Session session, String resort) {
    try { ServerRuntime.get().hub.leave(session.getId(), resort); } catch (IllegalStateException ignored) { }
  }
}
