package skitrak.server;

import skitrak.common.Json;
import skitrak.common.LeaderboardSnapshot;
import javax.websocket.*;
import java.io.IOException;
import java.util.ArrayDeque;

/** One in-flight async send per Session; an overloaded client is disconnected for snapshot resync. */
final class SessionOutbox implements LeaderboardSubscriptions.Sink {
  private final Session session;
  private final int capacity;
  private final Runnable cleanup;
  private final ArrayDeque<String> pending = new ArrayDeque<>();
  private boolean sending, stopped;
  SessionOutbox(Session session, int capacity, Runnable cleanup) {
    this.session = session; this.capacity = capacity; this.cleanup = cleanup;
    session.getAsyncRemote().setSendTimeout(5000);
  }
  @Override public String id() { return session.getId(); }
  @Override public synchronized void send(LeaderboardSnapshot snapshot) {
    if (stopped) return;
    if (!session.isOpen() || pending.size() >= capacity) { stop("subscriber cannot keep up"); return; }
    pending.add(Json.encode(snapshot));
    if (!sending) next();
  }
  private synchronized void next() {
    if (stopped) return;
    String value = pending.poll();
    if (value == null) { sending = false; return; }
    sending = true;
    try {
      session.getAsyncRemote().sendText(value, result -> {
        synchronized (SessionOutbox.this) {
          if (result.isOK()) next(); else stop("websocket send failed");
        }
      });
    } catch (RuntimeException error) { stop("websocket send failed"); }
  }
  private void stop(String reason) {
    if (stopped) return; stopped = true; pending.clear();
    try { session.close(new CloseReason(CloseReason.CloseCodes.TRY_AGAIN_LATER, reason)); } catch (IOException | RuntimeException ignored) { }
    cleanup.run();
  }
}
