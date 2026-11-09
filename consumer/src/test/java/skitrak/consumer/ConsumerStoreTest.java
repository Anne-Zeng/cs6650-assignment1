package skitrak.consumer;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import skitrak.common.LeaderboardSnapshot;
import skitrak.common.SwipeMessage;

public class ConsumerStoreTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  @Test public void restartRetainsInboxTotalsVersionsAndPendingNotifications() throws Exception {
    Path directory = temporary.newFolder().toPath();
    SwipeMessage event = event("1", "7", 200);
    long version;
    try (ConsumerStore store = new ConsumerStore(directory, "1", 20)) {
      assertFalse(store.apply(event).duplicate);
      version = store.snapshot("1").version;
      assertEquals(1, store.processed());
      assertEquals(200, store.total("1", "7"));
      assertEquals(1, store.pendingOutbox());
    }
    try (ConsumerStore store = new ConsumerStore(directory, "1", 20)) {
      event.serverId = "a-different-ingest";
      event.timestamp = Instant.now().plusSeconds(1).toString();
      assertTrue(store.apply(event).duplicate);
      assertEquals(1, store.processed());
      assertEquals(200, store.total("1", "7"));
      assertEquals(version, store.snapshot("1").version);
      List<ConsumerStore.PendingUpdate> pending = store.pendingUpdates(10);
      assertEquals(1, pending.size());
      store.markPublished(pending.get(0).id);
    }
    try (ConsumerStore store = new ConsumerStore(directory, "1", 20)) {
      assertEquals(0, store.pendingOutbox());
      assertTrue(store.apply(event).duplicate);
      assertEquals(200, store.snapshot("1").top.get(0).vertical);
    }
  }

  @Test public void conflictDoesNotChangeAnyBusinessState() throws Exception {
    try (ConsumerStore store = new ConsumerStore(temporary.newFolder().toPath(), "1", 20)) {
      SwipeMessage event = event("1", "9", 100);
      store.apply(event);
      event.vertical = 200;
      assertThrows(ConsumerStore.ConflictingEventException.class, () -> store.apply(event));
      assertEquals(1, store.processed());
      assertEquals(100, store.total("1", "9"));
      assertEquals(1, store.pendingOutbox());
    }
  }

  @Test public void topNHandlesScoreChangesOutsideCandidatesAndNumericTies() throws Exception {
    try (ConsumerStore store = new ConsumerStore(temporary.newFolder().toPath(), "1", 2)) {
      store.apply(event("1", "10", 200));
      store.apply(event("1", "2", 200));
      assertEquals("2", store.snapshot("1").top.get(0).skierID);
      assertEquals("10", store.snapshot("1").top.get(1).skierID);
      long version = store.snapshot("1").version;
      long pending = store.pendingOutbox();
      store.apply(event("1", "3", 60)); // Below top two: no new notification/version.
      assertEquals(version, store.snapshot("1").version);
      assertEquals(pending, store.pendingOutbox());
      assertEquals(3, store.snapshot("1").processed);
      store.apply(event("1", "3", 200));
      LeaderboardSnapshot snapshot = store.snapshot("1");
      assertEquals("3", snapshot.top.get(0).skierID);
      assertEquals(260, snapshot.top.get(0).vertical);
      assertEquals("2", snapshot.top.get(1).skierID);
      assertEquals(version + 1, snapshot.version);
      store.apply(event("1", "3", 60)); // Rank unchanged, score changed: still broadcast.
      assertEquals(version + 2, store.snapshot("1").version);
    }
  }

  @Test public void separateDaysAndReturnedSnapshotsCannotMutateState() throws Exception {
    try (ConsumerStore store = new ConsumerStore(temporary.newFolder().toPath(), "1", 20)) {
      store.apply(event("1", "3", 100));
      store.apply(event("2", "3", 200));
      LeaderboardSnapshot snapshot = store.snapshot("1");
      snapshot.top.get(0).vertical = 99999;
      snapshot.top.clear();
      assertEquals(100, store.snapshot("1").top.get(0).vertical);
      assertEquals(200, store.snapshot("2").top.get(0).vertical);
      assertEquals(1, store.processed("1"));
      assertEquals(1, store.processed("2"));
      assertEquals(2, store.processed());
    }
  }

  @Test public void onlyOneProcessMayOwnAResortAndClosingReleasesTheLock() throws Exception {
    Path directory = temporary.newFolder().toPath();
    try (ConsumerStore first = new ConsumerStore(directory, "1", 20)) {
      assertThrows(IOException.class, () -> new ConsumerStore(directory, "1", 20));
      try (ConsumerStore other = new ConsumerStore(directory, "2", 20)) { assertEquals(0, other.processed()); }
    }
    try (ConsumerStore reopened = new ConsumerStore(directory, "1", 20)) { assertEquals(0, reopened.processed()); }
  }

  @Test public void batchPreservesIntermediateVersionsAndDeduplicatesWithinTransaction() throws Exception {
    Path directory=temporary.newFolder().toPath();
    SwipeMessage first=event("1","7",200),second=event("1","7",100);
    try(ConsumerStore store=new ConsumerStore(directory,"1",20)) {
      List<ConsumerStore.ApplyResult> results=store.applyBatch(java.util.Arrays.asList(first,first,second));
      assertFalse(results.get(0).duplicate);assertTrue(results.get(1).duplicate);assertFalse(results.get(2).duplicate);
      assertEquals(2,store.processed());assertEquals(300,store.total("1","7"));
      List<ConsumerStore.PendingUpdate> pending=store.pendingUpdates(10);
      assertEquals(2,pending.size());
      LeaderboardSnapshot one=skitrak.common.Json.decode(pending.get(0).payload,LeaderboardSnapshot.class);
      LeaderboardSnapshot two=skitrak.common.Json.decode(pending.get(1).payload,LeaderboardSnapshot.class);
      assertEquals(1,one.version);assertEquals(200,one.top.get(0).vertical);
      assertEquals(2,two.version);assertEquals(300,two.top.get(0).vertical);
      store.markPublishedThrough(pending.get(0).id);
      assertEquals(1,store.pendingOutbox()); // Unconfirmed suffix survives.
    }
    try(ConsumerStore reopened=new ConsumerStore(directory,"1",20)) {
      assertEquals(2,reopened.processed());assertEquals(300,reopened.total("1","7"));
      assertEquals(2,reopened.snapshot("1").version);assertEquals(1,reopened.pendingOutbox());
      assertTrue(reopened.apply(first).duplicate);
    }
  }

  @Test public void lateBatchConflictRollsBackEarlierRowsAndMemoryBeforeRetry() throws Exception {
    try(ConsumerStore store=new ConsumerStore(temporary.newFolder().toPath(),"1",20)) {
      SwipeMessage existing=event("1","1",200);store.apply(existing);
      SwipeMessage good=event("2","2",100);
      SwipeMessage conflict=skitrak.common.Json.decode(skitrak.common.Json.encode(existing),SwipeMessage.class);
      conflict.vertical=300;
      assertThrows(ConsumerStore.ConflictingEventException.class,
          () -> store.applyBatch(java.util.Arrays.asList(good,conflict)));
      assertEquals(1,store.processed());assertEquals(0,store.processed("2"));
      assertEquals(0,store.total("2","2"));assertEquals(1,store.pendingOutbox());
      assertFalse(store.apply(good).duplicate);assertEquals(2,store.processed());
      assertEquals(100,store.total("2","2"));assertEquals(1,store.snapshot("2").version);
    }
  }

  @Test public void restartRestoresCountsAndTotalsWhenNoLeaderboardRewriteWasNeeded() throws Exception {
    Path directory=temporary.newFolder().toPath();
    try(ConsumerStore store=new ConsumerStore(directory,"1",1)) {
      store.apply(event("1","1",400));
      store.applyBatch(java.util.Arrays.asList(event("1","2",60),event("1","2",70)));
      assertEquals(1,store.snapshot("1").version);assertEquals(1,store.pendingOutbox());
    }
    try(ConsumerStore reopened=new ConsumerStore(directory,"1",1)) {
      assertEquals(3,reopened.processed());assertEquals(3,reopened.snapshot("1").processed);
      assertEquals(130,reopened.total("1","2"));assertEquals(1,reopened.snapshot("1").version);
      assertEquals("1",reopened.snapshot("1").top.get(0).skierID);
    }
  }

  private static SwipeMessage event(String day, String skier, int vertical) {
    SwipeMessage event = new SwipeMessage();
    event.eventId = UUID.randomUUID().toString();
    event.resortID = "1";
    event.dayID = day;
    event.skierID = skier;
    event.liftID = "1";
    event.vertical = vertical;
    event.time = 217;
    event.timestamp = Instant.now().toString();
    event.serverId = "test-ingest";
    return event;
  }
}
