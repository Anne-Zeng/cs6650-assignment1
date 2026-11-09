import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.Assert.*;

public class DrainVerifierTest {
  private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
  private static String metrics(boolean healthy, int ready, int processed) {
    return "{\"healthy\":" + healthy + ",\"queueReady\":" + ready
        + ",\"processed\":" + processed + ",\"inFlight\":0,\"pendingOutbox\":0,\"queueMetricsUpdatedAt\":1,\"queueMetricsAgeMs\":0}";
  }
  private static String snapshot(int version, int score) {
    return "{\"resortID\":\"1\",\"dayID\":\"1\",\"version\":" + version
        + ",\"top\":[{\"rank\":1,\"skierID\":\"1\",\"vertical\":" + score + "}]}";
  }
  private static HttpServer server(Function<String, String> handler) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      byte[] bytes = handler.apply(exchange.getRequestURI().getPath()).getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes); exchange.close();
    });
    server.start(); return server;
  }
  private static DrainVerifier verifier(HttpServer server) {
    return new DrainVerifier("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @Test public void baselineWaitsForReconnectAndEarlierBacklogToFinish() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    HttpServer server = server(path -> {
      int count = calls.incrementAndGet();
      if (count == 1) return metrics(false, 0, 2);
      if (count == 2) return metrics(true, 5, 2);
      return metrics(true, 0, 7);
    });
    try {
      assertEquals(7, verifier(server).awaitIdle(3));
      assertTrue(calls.get() >= 4);
    } finally { server.stop(0); }
  }

  @Test public void finalVerificationRefetchesWhenObservedVersionIsNewer() throws Exception {
    AtomicInteger metricsCalls = new AtomicInteger();
    AtomicInteger snapshotCalls = new AtomicInteger();
    HttpServer server = server(path -> {
      if (path.equals("/metrics")) {
        int call = metricsCalls.incrementAndGet();
        if (call == 1) return metrics(false, 0, 10);
        if (call == 2) return metrics(true, 4, 10);
        return metrics(true, 0, 10);
      }
      return snapshot(snapshotCalls.incrementAndGet() == 1 ? 4 : 5, 500);
    });
    DrainVerifier.SnapshotSource source = new DrainVerifier.SnapshotSource() {
      public int resortID() { return 1; }
      public JsonObject snapshot() { return json(DrainVerifierTest.snapshot(5, 500)); }
    };
    try {
      assertEquals(5, verifier(server).awaitSnapshots(10, Collections.singletonList(source), 3)
          .get(1).get("version").getAsLong());
      assertEquals(2, snapshotCalls.get());
      assertTrue(metricsCalls.get() >= 5);
    } finally { server.stop(0); }
  }

  @Test public void unrelatedOrAmbiguousExtraEventsInvalidateIsolatedRun() throws Exception {
    HttpServer server = server(path -> metrics(true, 0, 11));
    try {
      verifier(server).awaitSnapshots(10, Collections.emptyList(), 1);
      fail("Extra events must not masquerade as successful reconciliation");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("Unexpected processed-count"));
    } finally { server.stop(0); }
  }

  @Test public void missingQueueDepthCannotEstablishIdle() {
    try {
      DrainVerifier.idle(json("{\"healthy\":true,\"processed\":10,\"pendingOutbox\":0,\"inFlight\":0}"));
      fail("Missing readiness evidence must fail");
    } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("queueReady")); }
  }
}
