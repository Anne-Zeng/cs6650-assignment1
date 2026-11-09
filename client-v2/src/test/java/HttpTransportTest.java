import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpTransportTest {
  private static void reply(HttpExchange exchange, int code, boolean close) throws IOException {
    exchange.getRequestBody().readAllBytes();
    if (close) exchange.getResponseHeaders().set("Connection", "close");
    exchange.sendResponseHeaders(code, -1);
    exchange.close();
  }
  private static String url(HttpServer server) {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }
  private static HttpServer server() throws IOException {
    return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
  }
  private static SwipeSender sender(HttpTransport transport, String url,
      AtomicInteger success, AtomicInteger failed) {
    BlockingQueue<SwipeEvent> queue = new ArrayBlockingQueue<>(1);
    queue.add(new SwipeEvent(1, 2026, 1, 123, 217, 21));
    return new SwipeSender(queue, transport, url, success, failed, 1, new SwipeMetrics());
  }

  @Test public void sequentialRequestsReuseOneConnection() throws Exception {
    HttpServer server = server();
    AtomicInteger received = new AtomicInteger();
    server.createContext("/", ex -> {
      received.incrementAndGet();
      byte[] data = "acknowledged".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      ex.getRequestBody().readAllBytes();
      ex.sendResponseHeaders(201, data.length);
      ex.getResponseBody().write(data);
      ex.close();
    });
    server.start();
    try (HttpTransport transport = new HttpTransport(4)) {
      for (int i = 0; i < 10; i++) assertEquals(201, transport.post(url(server), "{}", false));
      assertEquals(10, received.get());
      assertEquals(1, transport.getConnectionsEstablished());
      assertEquals(0, transport.getPoolStats().getLeased());
      assertEquals(1, transport.getPoolStats().getAvailable());
      assertEquals(0, transport.getRequestRetries());
    } finally { server.stop(0); }
  }

  @Test public void failedHttpStatusesHaveExactlyFiveApplicationRetries() throws Exception {
    HttpServer server = server();
    AtomicInteger received = new AtomicInteger();
    server.createContext("/", ex -> { received.incrementAndGet(); reply(ex, 503, false); });
    server.start();
    try (HttpTransport transport = new HttpTransport(1)) {
      AtomicInteger success = new AtomicInteger(), failed = new AtomicInteger();
      sender(transport, url(server), success, failed).run();
      assertEquals(6, received.get());
      assertEquals(6, transport.getRequestAttempts());
      assertEquals(5, transport.getRequestRetries());
      assertEquals(0, success.get());
      assertEquals(1, failed.get());
      assertEquals(1, transport.getConnectionsEstablished());
      assertEquals(0, transport.getConnectionsOnRetry());
      assertEquals(0, transport.getPoolStats().getLeased());
    } finally { server.stop(0); }
  }

  @Test public void serverCloseThenRetryCountsNewSocket() throws Exception {
    HttpServer server = server();
    AtomicInteger received = new AtomicInteger();
    server.createContext("/", ex -> {
      boolean first = received.incrementAndGet() == 1;
      reply(ex, first ? 503 : 201, first);
    });
    server.start();
    try (HttpTransport transport = new HttpTransport(1)) {
      AtomicInteger success = new AtomicInteger(), failed = new AtomicInteger();
      sender(transport, url(server), success, failed).run();
      assertEquals(1, success.get());
      assertEquals(0, failed.get());
      assertEquals(2, transport.getConnectionsEstablished());
      assertEquals(1, transport.getConnectionsOnRetry());
      assertEquals(1, transport.getRequestRetries());
    } finally { server.stop(0); }
  }

  @Test public void failedConnectDoesNotCountAsEstablished() throws Exception {
    int port;
    try (ServerSocket unused = new ServerSocket(0)) { port = unused.getLocalPort(); }
    try (HttpTransport transport = new HttpTransport(1, 300, 300, 300)) {
      try {
        transport.post("http://127.0.0.1:" + port, "{}", false);
        fail("Expected connection refusal");
      } catch (IOException expected) { }
      assertEquals(1, transport.getRequestAttempts());
      assertEquals(1, transport.getConnectAttempts());
      assertEquals(0, transport.getConnectionsEstablished());
      assertEquals(0, transport.getPoolStats().getLeased());
    }
  }

  @Test public void closedResponseIsNotAutomaticallyRetried() throws Exception {
    try (ServerSocket server = new ServerSocket(0)) {
      ExecutorService acceptor = Executors.newSingleThreadExecutor();
      Future<?> accepted = acceptor.submit(() -> {
        try (Socket socket = server.accept()) {
          socket.getInputStream().read();
          // Drop the connection without sending a response.
        } catch (IOException e) { throw new RuntimeException(e); }
      });
      try (HttpTransport transport = new HttpTransport(1, 500, 500, 500)) {
        try {
          transport.post("http://127.0.0.1:" + server.getLocalPort(), "{}", false);
          fail("Expected dropped response");
        } catch (IOException expected) { }
        accepted.get(2, TimeUnit.SECONDS);
        assertEquals(1, transport.getConnectAttempts());
        assertEquals(1, transport.getConnectionsEstablished());
        assertEquals(0, transport.getRequestRetries());
        assertEquals(0, transport.getPoolStats().getLeased());
      } finally { acceptor.shutdownNow(); }
    }
  }

  @Test public void poolLimitCreatesPendingRequestAndReleasesAll() throws Exception {
    HttpServer server = server();
    ExecutorService handlers = Executors.newCachedThreadPool();
    ExecutorService callers = Executors.newFixedThreadPool(3);
    CountDownLatch twoHandlers = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    server.setExecutor(handlers);
    server.createContext("/", ex -> {
      twoHandlers.countDown();
      try { release.await(3, TimeUnit.SECONDS); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      reply(ex, 201, false);
    });
    server.start();
    try (HttpTransport transport = new HttpTransport(2, 3000, 1000, 3000)) {
      Future<Integer> a = callers.submit(() -> transport.post(url(server), "{}", false));
      Future<Integer> b = callers.submit(() -> transport.post(url(server), "{}", false));
      assertTrue(twoHandlers.await(2, TimeUnit.SECONDS));
      Future<Integer> c = callers.submit(() -> transport.post(url(server), "{}", false));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (transport.getPoolStats().getPending() == 0 && System.nanoTime() < deadline) {
        Thread.sleep(5);
      }
      assertEquals(2, transport.getPoolStats().getLeased());
      assertEquals(1, transport.getPoolStats().getPending());
      assertEquals(2, transport.getConnectionsEstablished());
      release.countDown();
      assertEquals(201, (int) a.get(3, TimeUnit.SECONDS));
      assertEquals(201, (int) b.get(3, TimeUnit.SECONDS));
      assertEquals(201, (int) c.get(3, TimeUnit.SECONDS));
      assertEquals(2, transport.getConnectionsEstablished());
      assertEquals(0, transport.getPoolStats().getLeased());
      assertEquals(0, transport.getPoolStats().getPending());
    } finally {
      release.countDown(); server.stop(0); callers.shutdownNow(); handlers.shutdownNow();
    }
  }

  @Test public void responseReadTimeoutReleasesConnection() throws Exception {
    HttpServer server = server();
    CountDownLatch release = new CountDownLatch(1);
    server.createContext("/", ex -> {
      try { release.await(2, TimeUnit.SECONDS); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      ex.close();
    });
    server.start();
    try (HttpTransport transport = new HttpTransport(1, 500, 500, 100)) {
      try {
        transport.post(url(server), "{}", false);
        fail("Expected read timeout");
      } catch (java.net.SocketTimeoutException expected) { }
      assertEquals(1, transport.getConnectionsEstablished());
      assertEquals(0, transport.getPoolStats().getLeased());
    } finally { release.countDown(); server.stop(0); }
  }

  @Test public void leaseTimeoutDoesNotOpenAnExtraSocket() throws Exception {
    HttpServer server = server();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    ExecutorService caller = Executors.newSingleThreadExecutor();
    server.createContext("/", ex -> {
      entered.countDown();
      try { release.await(3, TimeUnit.SECONDS); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      reply(ex, 201, false);
    });
    server.start();
    try (HttpTransport transport = new HttpTransport(1, 100, 500, 3000)) {
      Future<Integer> first = caller.submit(() -> transport.post(url(server), "{}", false));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      try {
        transport.post(url(server), "{}", false);
        fail("Expected connection pool timeout");
      } catch (org.apache.hc.core5.http.ConnectionRequestTimeoutException expected) { }
      assertEquals(1, transport.getConnectAttempts());
      assertEquals(1, transport.getConnectionsEstablished());
      assertEquals(2, transport.getRequestAttempts());
      release.countDown();
      assertEquals(201, (int) first.get(2, TimeUnit.SECONDS));
      assertEquals(0, transport.getPoolStats().getLeased());
    } finally { release.countDown(); server.stop(0); caller.shutdownNow(); }
  }
}
