package skitrak.server;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import skitrak.common.LeaderboardSnapshot;
import javax.websocket.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionOutboxTest {
  private LeaderboardSnapshot snapshot(long version) {
    LeaderboardSnapshot s = new LeaderboardSnapshot(); s.resortID = "1"; s.dayID = "1"; s.version = version; s.top = new ArrayList<>(); return s;
  }
  @Test void onlyOneSendCanBeInFlightPerSession() {
    Session session = mock(Session.class); RemoteEndpoint.Async remote = mock(RemoteEndpoint.Async.class);
    when(session.getId()).thenReturn("s"); when(session.isOpen()).thenReturn(true); when(session.getAsyncRemote()).thenReturn(remote);
    SessionOutbox outbox = new SessionOutbox(session, 2, () -> {});
    outbox.send(snapshot(1)); outbox.send(snapshot(2));
    ArgumentCaptor<SendHandler> handlers = ArgumentCaptor.forClass(SendHandler.class);
    verify(remote, times(1)).sendText(anyString(), handlers.capture());
    handlers.getValue().onResult(new SendResult());
    verify(remote, times(2)).sendText(anyString(), any(SendHandler.class));
  }
  @Test void slowSubscriberHasBoundedQueueAndIsRemoved() throws Exception {
    Session session = mock(Session.class); RemoteEndpoint.Async remote = mock(RemoteEndpoint.Async.class);
    when(session.getId()).thenReturn("s"); when(session.isOpen()).thenReturn(true); when(session.getAsyncRemote()).thenReturn(remote);
    AtomicInteger cleanups = new AtomicInteger(); SessionOutbox outbox = new SessionOutbox(session, 1, cleanups::incrementAndGet);
    outbox.send(snapshot(1)); outbox.send(snapshot(2)); outbox.send(snapshot(3)); outbox.send(snapshot(4));
    verify(remote, times(1)).sendText(anyString(), any(SendHandler.class));
    verify(session, times(1)).close(any(CloseReason.class)); assertEquals(1, cleanups.get());
  }
}
