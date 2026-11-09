package skitrak.server;

import com.google.gson.*;
import skitrak.common.LiftCatalog;
import skitrak.common.SwipeMessage;
import java.time.Instant;
import java.util.UUID;

/** Transport validation is separate from broker I/O so malformed requests never borrow a channel. */
public final class SwipeValidator {
  private SwipeValidator() { }
  public static SwipeMessage parse(String path, String body, String idempotencyKey,
      String alternateEventId, String serverId, LiftCatalog lifts) {
    String[] p = path == null ? new String[0] : path.split("/", -1);
    if (p.length != 8 || !"seasons".equals(p[2]) || !"days".equals(p[4]) || !"skiers".equals(p[6]))
      throw new IllegalArgumentException("expected /skiers/{resort}/seasons/{season}/days/{day}/skiers/{skier}");
    int resort = positive(p[1]), day = positive(p[5]); positive(p[3]); positive(p[7]);
    if (resort > 10) throw new IllegalArgumentException("resortID must be 1..10");
    JsonElement value = JsonParser.parseString(body);
    if (!value.isJsonObject()) throw new IllegalArgumentException("body must be a JSON object");
    JsonObject json = value.getAsJsonObject();
    int lift = integer(json, "liftID"), time = integer(json, "time");
    if (lift < 1 || lift > 40 || time < 1 || time > 360)
      throw new IllegalArgumentException("liftID must be 1..40; time must be 1..360");
    if (idempotencyKey != null && alternateEventId != null && !idempotencyKey.equals(alternateEventId))
      throw new IllegalArgumentException("Idempotency-Key and X-Event-Id disagree");
    String eventId = idempotencyKey != null ? idempotencyKey : alternateEventId;
    if (eventId == null) eventId = UUID.randomUUID().toString();
    try { if (!UUID.fromString(eventId).toString().equals(eventId.toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException(); }
    catch (IllegalArgumentException error) { throw new IllegalArgumentException("Idempotency-Key must be a canonical UUID"); }
    SwipeMessage event = new SwipeMessage();
    event.eventId = eventId.toLowerCase(java.util.Locale.ROOT); event.resortID = Integer.toString(resort);
    event.dayID = Integer.toString(day); event.skierID = Integer.toString(positive(p[7]));
    event.liftID = Integer.toString(lift); event.vertical = lifts.vertical(lift); event.time = time;
    event.timestamp = Instant.now().toString(); event.serverId = serverId; event.validate();
    return event;
  }
  private static int positive(String value) {
    try { int number = Integer.parseInt(value); if (number > 0 && value.matches("[0-9]+")) return number; }
    catch (NumberFormatException ignored) { }
    throw new IllegalArgumentException("path IDs must be positive integers");
  }
  private static int integer(JsonObject body, String name) {
    try {
      JsonElement value = body.get(name);
      if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
      return value.getAsBigDecimal().intValueExact();
    } catch (RuntimeException error) { throw new IllegalArgumentException(name + " must be an integer"); }
  }
}
