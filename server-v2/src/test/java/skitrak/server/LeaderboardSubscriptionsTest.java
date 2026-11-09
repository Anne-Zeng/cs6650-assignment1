package skitrak.server;

import org.junit.jupiter.api.Test;
import skitrak.common.LeaderboardSnapshot;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LeaderboardSubscriptionsTest {
  static final class Bindings implements LeaderboardSubscriptions.Bindings {
    List<String> changes = new ArrayList<>();
    public void bind(String resort) { changes.add("+" + resort); }
    public void unbind(String resort) { changes.add("-" + resort); }
  }
  static final class Sink implements LeaderboardSubscriptions.Sink {
    final String id; final List<Long> versions = new ArrayList<>();
    Sink(String id) { this.id = id; }
    public String id() { return id; }
    public void send(LeaderboardSnapshot snapshot) { versions.add(snapshot.version); }
  }
  static LeaderboardSnapshot snapshot(String resort, String day, long version) {
    LeaderboardSnapshot s = new LeaderboardSnapshot(); s.resortID = resort; s.dayID = day; s.version = version; s.top = new ArrayList<>(); return s;
  }
  @Test void oneBindingPerResortUntilLastLocalSessionLeaves() {
    Bindings b = new Bindings(); LeaderboardSubscriptions registry = new LeaderboardSubscriptions(b);
    registry.add("1", "1", new Sink("a")); registry.add("1", "2", new Sink("b")); registry.add("2", "1", new Sink("c"));
    registry.remove("1", "a"); assertEquals(List.of("+1", "+2"), b.changes);
    registry.remove("1", "b"); registry.add("1", "1", new Sink("d"));
    assertEquals(List.of("+1", "+2", "-1", "+1"), b.changes); assertEquals(2, registry.sessions());
  }
  @Test void updateWhileBootstrapInFlightCannotBeOverwrittenByOlderSnapshot() {
    LeaderboardSubscriptions registry = new LeaderboardSubscriptions(new Bindings()); Sink sink = new Sink("a");
    registry.add("1", "1", sink);
    assertTrue(registry.accept(snapshot("1", "1", 8)));
    assertFalse(registry.accept(snapshot("1", "1", 7)));
    assertFalse(registry.accept(snapshot("1", "1", 8)));
    assertFalse(registry.accept(snapshot("1", "2", 9)));
    assertEquals(List.of(8L), sink.versions);
    Sink joining = new Sink("b"); registry.add("1", "1", joining);
    assertEquals(List.of(8L), joining.versions);
  }
  @Test void emptyVersionZeroIsDeliveredToFirstSubscriber() {
    LeaderboardSubscriptions registry = new LeaderboardSubscriptions(new Bindings()); Sink sink = new Sink("a");
    registry.add("1", "1", sink); assertTrue(registry.accept(snapshot("1", "1", 0)));
    assertEquals(List.of(0L), sink.versions);
  }
}
