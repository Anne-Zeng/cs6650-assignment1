package skitrak.consumer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import skitrak.common.Json;
import skitrak.common.Topology;

/** Standalone consumer process; the HTTP API is intended for private-network access. */
public final class ConsumerMain {
  private ConsumerMain() { }

  public static void main(String[] args) throws Exception {
    ConsumerConfig config = new ConsumerConfig();
    ConsumerRuntime consumer = new ConsumerRuntime(config);
    HttpServer http;
    try { http = HttpServer.create(new InetSocketAddress(config.port), 128); }
    catch (IOException | RuntimeException failure) { consumer.close(); throw failure; }
    ExecutorService requests = Executors.newFixedThreadPool(4, task -> new Thread(task, "consumer-http"));
    CountDownLatch closed = new CountDownLatch(1);
    try {
      http.setExecutor(requests);
      http.createContext("/", exchange -> handle(exchange, consumer));
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        http.stop(1);
        requests.shutdownNow();
        consumer.close();
        closed.countDown();
      }, "consumer-shutdown"));
      consumer.start();
      http.start();
    } catch (RuntimeException failure) {
      http.stop(0);
      requests.shutdownNow();
      consumer.close();
      throw failure;
    }
    System.out.printf("Consumer listening on %d, stores=%s, configuredThreads=%d, prefetch=%d%n",
        config.port, config.storeDir.toAbsolutePath(), config.threads, config.prefetch);
    closed.await();
  }

  private static void handle(HttpExchange exchange, ConsumerRuntime consumer) throws IOException {
    try {
      if (!"GET".equals(exchange.getRequestMethod())) {
        exchange.getResponseHeaders().set("Allow", "GET");
        respond(exchange, 405, Collections.singletonMap("error", "GET required"));
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if ("/health".equals(path)) {
        respond(exchange, consumer.healthy() ? 200 : 503, Collections.singletonMap("healthy", consumer.healthy()));
      } else if ("/metrics".equals(path)) {
        respond(exchange, 200, consumer.metrics());
      } else if ("/drain".equals(path)) {
        Map<String, Object> metrics = consumer.metrics();
        long queueAge = (long) metrics.get("queueMetricsAgeMs");
        boolean drained = (long) metrics.get("inFlight") == 0 && (long) metrics.get("pendingOutbox") == 0
            && (long) metrics.get("queueReady") == 0 && queueAge >= 0 && queueAge <= 3000 && consumer.healthy();
        metrics.put("drained", drained);
        respond(exchange, drained ? 200 : 202, metrics);
      } else if (path.startsWith("/leaderboards/")) {
        String resort = path.substring("/leaderboards/".length());
        if (!resort.matches("[0-9]{1,2}")) throw new IllegalArgumentException("Invalid resortID");
        int resortID = Integer.parseInt(resort);
        if (resortID < 1 || resortID > Topology.RESORT_COUNT) throw new IllegalArgumentException("resortID must be 1..10");
        String dayID = query(exchange.getRequestURI().getRawQuery()).getOrDefault("dayID", "1");
        if (!dayID.matches("[0-9]{1,3}") || Integer.parseInt(dayID) < 1 || Integer.parseInt(dayID) > 366) {
          throw new IllegalArgumentException("dayID must be 1..366");
        }
        respond(exchange, 200, consumer.snapshot(resortID, Integer.toString(Integer.parseInt(dayID))));
      } else {
        respond(exchange, 404, Collections.singletonMap("error", "Not found"));
      }
    } catch (IllegalArgumentException invalid) {
      respond(exchange, 400, Collections.singletonMap("error", invalid.getMessage()));
    } catch (Exception failure) {
      System.err.println("Consumer API failed: " + failure);
      respond(exchange, 500, Collections.singletonMap("error", "Consumer state unavailable"));
    } finally {
      exchange.close();
    }
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> values = new LinkedHashMap<>();
    if (raw == null) return values;
    for (String part : raw.split("&")) {
      String[] pair = part.split("=", 2);
      values.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
          pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
    }
    return values;
  }

  private static void respond(HttpExchange exchange, int status, Object value) throws IOException {
    byte[] body = Json.encode(value).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
  }
}
