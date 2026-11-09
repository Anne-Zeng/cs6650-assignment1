package skitrak.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;

/** Wire contract required by assignment 2. Client retry identity survives every hop. */
public final class SwipeMessage {
  public String eventId, resortID, dayID, skierID, liftID, timestamp, serverId;
  public int vertical, time;
  public SwipeMessage() {}
  public void validate() {
    if (eventId == null || !UUID.fromString(eventId).toString().equalsIgnoreCase(eventId)) throw new IllegalArgumentException("eventId must be a canonical UUID");
    number(resortID, "resortID", 1, 10); number(dayID, "dayID", 1, 366);
    number(skierID, "skierID", 1, Integer.MAX_VALUE); number(liftID, "liftID", 1, 40);
    if (vertical < 60 || vertical > 400) throw new IllegalArgumentException("vertical outside 60..400");
    if (time < 1 || time > 360) throw new IllegalArgumentException("time outside 1..360");
    if (timestamp == null) throw new IllegalArgumentException("timestamp required");
    Instant.parse(timestamp);
    if (serverId == null || serverId.trim().isEmpty() || serverId.length() > 128) throw new IllegalArgumentException("serverId required, max 128 characters");
  }
  private static void number(String value, String field, int min, int max) {
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < min || parsed > max || !Integer.toString(parsed).equals(value)) throw new IllegalArgumentException(field + " outside permitted range or non-canonical");
    } catch (NumberFormatException e) { throw new IllegalArgumentException(field + " must be an integer string", e); }
  }
  /** Retry attempts can have different ingress hosts and receive timestamps. */
  public String semanticFingerprint() {
    String fields = resortID + "|" + dayID + "|" + skierID + "|" + liftID + "|" + vertical + "|" + time;
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256").digest(fields.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(64);
      char[] hex = "0123456789abcdef".toCharArray();
      for (byte b : bytes) { result.append(hex[(b & 255) >>> 4]); result.append(hex[b & 15]); }
      return result.toString();
    } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
}
