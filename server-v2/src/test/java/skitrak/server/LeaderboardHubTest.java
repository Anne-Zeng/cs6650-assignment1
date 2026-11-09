package skitrak.server;

import com.rabbitmq.client.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LeaderboardHubTest {
  @Test void connectionThatCompletesAfterShutdownIsClosedInsteadOfInstalled() throws Exception {
    Connection connection = mock(Connection.class); Channel channel = mock(Channel.class);
    when(connection.createChannel()).thenReturn(channel);
    AMQP.Queue.DeclareOk declared = mock(AMQP.Queue.DeclareOk.class);
    when(declared.getQueue()).thenReturn("amq.gen-test");
    when(channel.queueDeclare(anyString(), eq(false), eq(true), eq(true), anyMap())).thenReturn(declared);
    CountDownLatch connecting = new CountDownLatch(1), release = new CountDownLatch(1);
    LeaderboardHub hub = new LeaderboardHub("test", "http://localhost:8081", () -> {
      connecting.countDown();
      // A network connect may complete despite interrupt; shutdown still owns that connection.
      boolean released = false;
      while (!released) try { release.await(); released = true; } catch (InterruptedException ignored) { }
      return connection;
    });
    try {
      hub.start(); assertTrue(connecting.await(2, TimeUnit.SECONDS)); hub.close(); release.countDown();
      verify(connection, timeout(2000)).abort();
      assertFalse(hub.ready()); verify(channel, never()).basicQos(anyInt());
    } finally { release.countDown(); hub.close(); }
  }
}
