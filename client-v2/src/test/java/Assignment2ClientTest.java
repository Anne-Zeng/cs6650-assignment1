import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class Assignment2ClientTest {
  @Test public void retryIdentityIsStableAndTransmittedOnEveryAttempt() throws Exception {
    SwipeEvent event = new SwipeEvent(1, 2026, 1, 17, 120, 3);
    String id = event.getEventId();
    assertEquals(id, event.getEventId());
    assertNotEquals(id, new SwipeEvent(1, 2026, 1, 17, 120, 3).getEventId());
    List<String> headers = new ArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      headers.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
      exchange.getRequestBody().readAllBytes();
      exchange.sendResponseHeaders(201, 2);
      exchange.getResponseBody().write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      exchange.close();
    });
    server.start();
    try (HttpTransport transport = new HttpTransport(1)) {
      String url = "http://127.0.0.1:" + server.getAddress().getPort() + event.path();
      assertEquals(201, transport.post(url, event.body(), false, id));
      assertEquals(201, transport.post(url, event.body(), true, id));
      assertEquals(java.util.Arrays.asList(id, id), headers);
    } finally { server.stop(0); }
  }

  @Test public void permanentValidationFailuresDoNotConsumeRetryBudget() {
    for (int status : new int[]{400, 401, 403, 404, 409, 422}) assertFalse(SwipeSender.isRetryable(status));
    for (int status : new int[]{0, 408, 429, 500, 502, 503, 504}) assertTrue(SwipeSender.isRetryable(status));
  }

  @Test public void subscriberRejectsSyntheticOrMalformedStandings() {
    JsonObject valid = JsonParser.parseString("{\"resortID\":\"1\",\"dayID\":\"1\",\"version\":5,"
        + "\"top\":[{\"rank\":1,\"skierID\":\"2\",\"vertical\":400},"
        + "{\"rank\":2,\"skierID\":\"3\",\"vertical\":300}]}").getAsJsonObject();
    LeaderboardSubscriber.validateSnapshot(valid, 1);
    valid.getAsJsonArray("top").get(1).getAsJsonObject().addProperty("vertical", 500);
    try { LeaderboardSubscriber.validateSnapshot(valid, 1); fail("Unsorted scores must fail"); }
    catch (IllegalArgumentException expected) { }
  }
}
