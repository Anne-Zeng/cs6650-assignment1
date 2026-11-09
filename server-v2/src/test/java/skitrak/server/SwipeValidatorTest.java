package skitrak.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import skitrak.common.*;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SwipeValidatorTest {
  @TempDir Path directory;
  private LiftCatalog lifts() throws Exception {
    StringBuilder csv = new StringBuilder("liftID,vertical\n");
    for (int i = 1; i <= 40; i++) csv.append(i).append(",200\n");
    Path path = directory.resolve("test-lifts.csv"); Files.writeString(path, csv.toString()); return LiftCatalog.load(path);
  }
  private final String path = "/1/seasons/2026/days/1/skiers/7";
  @Test void identityAndVerticalSurviveRetryAcrossServers() throws Exception {
    String key = UUID.randomUUID().toString(); LiftCatalog lifts = lifts();
    SwipeMessage first = SwipeValidator.parse(path, "{\"time\":217,\"liftID\":21}", key, null, "server-a", lifts);
    SwipeMessage retry = SwipeValidator.parse(path, "{\"time\":217,\"liftID\":21}", key, key, "server-b", lifts);
    assertEquals(key, retry.eventId); assertEquals(200, first.vertical); assertEquals(first.semanticFingerprint(), retry.semanticFingerprint());
  }
  @Test void invalidNumericValuesAndUnroutableResortsFailBeforePublish() throws Exception {
    LiftCatalog lifts = lifts(); String key = UUID.randomUUID().toString();
    for (String body : new String[]{"{}", "null", "[]", "{\"time\":1.5,\"liftID\":1}", "{\"time\":1,\"liftID\":41}", "{\"time\":\"1\",\"liftID\":1}"}) {
      assertThrows(RuntimeException.class, () -> SwipeValidator.parse(path, body, key, null, "a", lifts));
    }
    assertThrows(IllegalArgumentException.class, () -> SwipeValidator.parse(path.replace("/1/seasons", "/11/seasons"), "{\"time\":1,\"liftID\":1}", key, null, "a", lifts));
    assertThrows(IllegalArgumentException.class, () -> SwipeValidator.parse(path, "{\"time\":1,\"liftID\":1}", key, UUID.randomUUID().toString(), "a", lifts));
  }
}
