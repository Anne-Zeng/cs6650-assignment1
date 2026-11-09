package skitrak.server;

import com.rabbitmq.client.*;
import org.junit.jupiter.api.Test;
import skitrak.common.SwipeMessage;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SwipePublisherTest {
  private Channel channel;
  private Connection connection;
  private ChannelPool pool() throws Exception {
    channel = mock(Channel.class); when(channel.isOpen()).thenReturn(true);
    connection = mock(Connection.class); when(connection.isOpen()).thenReturn(true); when(connection.createChannel()).thenReturn(channel);
    ChannelPool pool = new ChannelPool(() -> connection, ignored -> {}, 1); pool.start();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!pool.ready() && System.nanoTime() < end) Thread.sleep(5);
    assertTrue(pool.ready()); return pool;
  }
  private SwipeMessage event() { SwipeMessage e = new SwipeMessage(); e.eventId = "89aa81f9-aa9d-4bcf-bf20-f61233a061ce"; e.resortID = "1"; return e; }
  @Test void confirmIsRequiredAndMandatoryReturnIsNotReportedAsSuccess() throws Exception {
    try (ChannelPool pool = pool()) {
      AtomicReference<ReturnCallback> callback = new AtomicReference<>();
      // Return listener was registered while the pool initialized: use Mockito invocation history.
      callback.set((ReturnCallback) mockingDetails(channel).getInvocations().stream()
          .filter(call -> call.getMethod().getName().equals("addReturnListener")).findFirst().orElseThrow().getArgument(0));
      doAnswer(call -> { callback.get().handle(new Return(312, "NO_ROUTE", "skitrak.exchange", "resort.1", null, new byte[0])); return null; })
          .when(channel).basicPublish(anyString(), anyString(), eq(true), any(), any(byte[].class));
      when(channel.waitForConfirms(anyLong())).thenReturn(true);
      SwipePublisher publisher = new SwipePublisher(pool, 100, 100);
      assertThrows(IOException.class, () -> publisher.publish(event()));
      assertEquals(0, publisher.confirmed.sum()); assertEquals(1, publisher.unroutable.sum()); assertEquals(1, pool.available());
    }
  }
  @Test void successfulPublishReusesPrecreatedChannel() throws Exception {
    try (ChannelPool pool = pool()) {
      when(channel.waitForConfirms(anyLong())).thenReturn(true);
      SwipePublisher publisher = new SwipePublisher(pool, 100, 100);
      publisher.publish(event()); publisher.publish(event());
      assertEquals(2, publisher.confirmed.sum()); verify(connection, times(1)).createChannel();
      verify(channel, times(2)).basicPublish(eq("skitrak.exchange"), eq("resort.1"), eq(true), any(), any(byte[].class));
    }
  }
  @Test void acquisitionTimeoutIsDistinguishedFromConfirmTimeout() throws Exception {
    try (ChannelPool pool = pool()) {
      SwipePublisher publisher = new SwipePublisher(pool, 1, 100);
      try (ChannelPool.Lease held = pool.borrow(100)) {
        assertThrows(TimeoutException.class, () -> publisher.publish(event()));
      }
      assertEquals(1L, publisher.failureCounts().get("ACQUIRE_TIMEOUT"));
      when(channel.waitForConfirms(anyLong())).thenThrow(new TimeoutException());
      assertThrows(TimeoutException.class, () -> publisher.publish(event()));
      assertEquals(1L, publisher.failureCounts().get("CONFIRM_TIMEOUT"));
      assertEquals(2, publisher.failed.sum());
    }
  }
  @Test void negativeConfirmHasItsOwnCounter() throws Exception {
    try (ChannelPool pool = pool()) {
      when(channel.waitForConfirms(anyLong())).thenReturn(false);
      SwipePublisher publisher = new SwipePublisher(pool, 100, 100);
      assertThrows(IOException.class, () -> publisher.publish(event()));
      assertEquals(1L, publisher.failureCounts().get("BROKER_NACK"));
      assertEquals(0, publisher.confirmed.sum());
    }
  }

}
