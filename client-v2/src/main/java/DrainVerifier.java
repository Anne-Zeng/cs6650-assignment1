import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Requires a healthy, quiet pipeline and exact isolated-run counts before verification. */
public final class DrainVerifier {
  interface SnapshotSource { int resortID(); JsonObject snapshot(); }
  private final String base;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public DrainVerifier(String base) { this.base = base.replaceAll("/+$", ""); }

  public JsonObject get(String path) throws IOException, InterruptedException {
    HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + path))
        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) throw new IOException("Consumer API " + path
        + " returned " + response.statusCode());
    return JsonParser.parseString(response.body()).getAsJsonObject();
  }

  static boolean idle(JsonObject metrics) {
    for (String required : new String[]{"healthy", "queueReady", "pendingOutbox", "inFlight", "processed", "queueMetricsUpdatedAt", "queueMetricsAgeMs"}) {
      if (!metrics.has(required)) throw new IllegalStateException("Consumer metrics lacks " + required
          + "; cannot establish a drained pipeline");
    }
    return metrics.get("healthy").getAsBoolean()
        && metrics.get("queueMetricsUpdatedAt").getAsLong() > 0
        && metrics.get("queueMetricsAgeMs").getAsLong() >= 0
        && metrics.get("queueMetricsAgeMs").getAsLong() <= 3000
        && metrics.get("queueReady").getAsLong() == 0
        && metrics.get("pendingOutbox").getAsLong() == 0 && metrics.get("inFlight").getAsLong() == 0;
  }

  /** Two consecutive quiet samples prevent earlier-run backlog from contaminating the baseline. */
  public long awaitIdle(int timeoutSeconds) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
    Long previous = null;
    IOException lastFailure = null;
    while (System.nanoTime() < deadline) {
      try {
        JsonObject metrics = get("/metrics");
        boolean pipelineIdle = idle(metrics);
        long processed = metrics.get("processed").getAsLong();
        if (pipelineIdle) {
          if (previous != null && previous == processed) return processed;
          previous = processed;
        } else previous = null;
      } catch (IOException failure) { lastFailure = failure; previous = null; }
      Thread.sleep(100);
    }
    throw new IllegalStateException("Consumer did not become healthy and fully drained before baseline", lastFailure);
  }

  public Map<Integer, JsonObject> await(long expectedExact, List<LeaderboardConnection> subscriptions,
      int timeoutSeconds) throws Exception {
    List<SnapshotSource> sources = new ArrayList<>();
    for (LeaderboardConnection subscription : subscriptions) sources.add(new SnapshotSource() {
      public int resortID() { return subscription.getResortID(); }
      public JsonObject snapshot() { return subscription.getLatestSnapshot(); }
    });
    return awaitSnapshots(expectedExact, sources, timeoutSeconds);
  }

  Map<Integer, JsonObject> awaitSnapshots(long expectedExact, List<SnapshotSource> sources,
      int timeoutSeconds) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
    IOException lastFailure = null;
    while (System.nanoTime() < deadline) {
      try {
        JsonObject metrics = get("/metrics");
        boolean pipelineIdle = idle(metrics);
        long processed = metrics.get("processed").getAsLong();
        if (processed > expectedExact) throw new IllegalStateException("Unexpected processed-count increase: expected "
            + expectedExact + ", observed " + processed + ". Isolate the benchmark or investigate ambiguous outcomes.");
        if (pipelineIdle && processed == expectedExact) {
          Map<Integer, JsonObject> finalSnapshots = new LinkedHashMap<>();
          boolean observed = true;
          for (SnapshotSource source : sources) {
            JsonObject expected = get("/leaderboards/" + source.resortID() + "?dayID=1");
            JsonObject actual = source.snapshot();
            // A newer observed version means the HTTP snapshot raced an update: refetch it.
            if (actual == null || actual.get("version").getAsLong() != expected.get("version").getAsLong()) {
              observed = false;
              break;
            }
            if (!actual.get("resortID").getAsString().equals(expected.get("resortID").getAsString())
                || !actual.get("dayID").getAsString().equals(expected.get("dayID").getAsString())
                || !actual.get("top").equals(expected.get("top"))) throw new IllegalStateException(
                "WebSocket differs from authoritative snapshot at the same version");
            finalSnapshots.put(source.resortID(), expected);
          }
          if (observed) {
            JsonObject after = get("/metrics");
            if (idle(after) && after.get("processed").getAsLong() == expectedExact) return finalSnapshots;
          }
        }
      } catch (IOException failure) { lastFailure = failure; }
      Thread.sleep(100);
    }
    throw new IllegalStateException("Pipeline did not drain with exact counts and matching final WebSocket snapshots within "
        + timeoutSeconds + " seconds", lastFailure);
  }
}
