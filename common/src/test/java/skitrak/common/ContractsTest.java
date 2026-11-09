package skitrak.common;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
public class ContractsTest {
  @Test public void retryFingerprintIgnoresIngressMetadata() {
    SwipeMessage first = message(); first.validate();
    SwipeMessage retry = Json.decode(Json.encode(first), SwipeMessage.class);
    retry.serverId = "another-node"; retry.timestamp = Instant.now().plusSeconds(1).toString();
    assertEquals(first.semanticFingerprint(), retry.semanticFingerprint());
    retry.vertical++;
    assertNotEquals(first.semanticFingerprint(), retry.semanticFingerprint());
  }
  @Test public void productionUsesOfficialTableAndRejectsMissingExplicitOverride() throws Exception {
    System.setProperty("ALLOW_DEMO_LIFTS", "false"); System.setProperty("LIFT_VERTICAL_FILE", "");
    try {
      LiftCatalog table=LiftCatalog.fromEnv(); assertFalse(table.isDemo());
      assertEquals(167,table.vertical(1)); assertEquals(267,table.vertical(21)); assertEquals(269,table.vertical(40));
      System.setProperty("LIFT_VERTICAL_FILE", "/private/tmp/skitrak-nonexistent-official-table.json");
      try { LiftCatalog.fromEnv(); fail("must reject missing explicit override"); } catch (java.io.IOException expected) { }
    }
    finally { System.clearProperty("ALLOW_DEMO_LIFTS"); System.clearProperty("LIFT_VERTICAL_FILE"); }
  }
  @Test public void completeTableRequiredAndDuplicateRejected() throws Exception {
    Path table = Files.createTempFile("lifts-test", ".csv");
    try {
      Files.writeString(table, "liftID,vertical\n1,100\n1,200\n");
      try { LiftCatalog.load(table); fail("duplicate"); } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("Invalid lift table")); }
      Files.writeString(table, "liftID,vertical\n1,100\n");
      try { LiftCatalog.load(table); fail("incomplete"); } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("missing lift 2")); }
    } finally { Files.deleteIfExists(table); }
  }
  @Test public void demoMustBeExplicitAndContainsAllFortyLifts() throws Exception {
    System.setProperty("ALLOW_DEMO_LIFTS", "true");
    try { LiftCatalog table = LiftCatalog.fromEnv(); assertTrue(table.isDemo()); for (int id=1;id<=40;id++) assertTrue(table.vertical(id)>=60 && table.vertical(id)<=400); }
    finally { System.clearProperty("ALLOW_DEMO_LIFTS"); }
  }
  @Test(expected=IllegalArgumentException.class) public void resortElevenHasNoQueue() { SwipeMessage m=message();m.resortID="11";m.validate(); }
  @Test public void officialJsonMappingAndArrayFormats() throws Exception {
    Path table=Files.createTempFile("lift_vertical", ".json");
    try {
      StringBuilder mapping=new StringBuilder("{");
      StringBuilder array=new StringBuilder("[");
      for(int id=1;id<=40;id++) {
        if(id>1){mapping.append(',');array.append(',');}
        mapping.append('"').append(id).append("\":").append(60+id);
        array.append("{\"liftID\":").append(id).append(",\"vertical\":").append(60+id).append('}');
      }
      Files.writeString(table,mapping.append('}').toString());
      assertEquals(100,LiftCatalog.load(table).vertical(40));
      Files.writeString(table,array.append(']').toString());
      assertEquals(61,LiftCatalog.load(table).vertical(1));
    } finally { Files.deleteIfExists(table); }
  }
  private SwipeMessage message() {
    SwipeMessage m = new SwipeMessage(); m.eventId=UUID.randomUUID().toString(); m.resortID="1";m.dayID="1";m.skierID="123";m.liftID="21";m.vertical=200;m.time=217;m.timestamp=Instant.now().toString();m.serverId="test";return m;
  }
}
