package skitrak.consumer;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import skitrak.common.Json;
import skitrak.common.LeaderboardEntry;
import skitrak.common.LeaderboardSnapshot;
import skitrak.common.SwipeMessage;

/** One durable resort partition. The OS lock prevents two processes from owning its files. */
public final class ConsumerStore implements AutoCloseable {
  private static final Comparator<Score> RANKING = Comparator
      .comparingLong((Score score) -> score.vertical).reversed()
      .thenComparing(score -> score.skierID, ConsumerStore::compareSkierIds);
  private final String resortID;
  private final int topSize;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private final Connection connection;
  private final Map<String, DayState> days = new HashMap<>();
  private long processed;

  public ConsumerStore(Path directory, String resortID, int topSize) throws IOException, SQLException {
    if (topSize < 1) throw new IllegalArgumentException("TOP_N must be positive");
    this.resortID = resortID;
    this.topSize = topSize;
    Files.createDirectories(directory);
    FileChannel acquiredChannel = FileChannel.open(directory.resolve("resort-" + resortID + ".lock"),
        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    FileLock acquiredLock;
    try {
      acquiredLock = acquiredChannel.tryLock();
    } catch (OverlappingFileLockException e) {
      acquiredChannel.close();
      throw new IOException("Resort " + resortID + " already has a store owner", e);
    }
    if (acquiredLock == null) {
      acquiredChannel.close();
      throw new IOException("Resort " + resortID + " already has a store owner");
    }
    Connection opened = null;
    try {
      opened = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("resort-" + resortID + ".db").toAbsolutePath());
      try (Statement sql = opened.createStatement()) {
        sql.execute("PRAGMA journal_mode=WAL");
        sql.execute("PRAGMA synchronous=FULL");
        sql.execute("PRAGMA busy_timeout=5000");
        sql.execute("CREATE TABLE IF NOT EXISTS inbox (event_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, day_id TEXT NOT NULL)");
        sql.execute("CREATE INDEX IF NOT EXISTS inbox_day ON inbox(day_id)");
        sql.execute("CREATE TABLE IF NOT EXISTS totals (day_id TEXT NOT NULL, skier_id TEXT NOT NULL, vertical INTEGER NOT NULL, PRIMARY KEY(day_id,skier_id))");
        sql.execute("CREATE TABLE IF NOT EXISTS snapshots (day_id TEXT PRIMARY KEY, version INTEGER NOT NULL, payload TEXT NOT NULL)");
        sql.execute("CREATE TABLE IF NOT EXISTS outbox (id INTEGER PRIMARY KEY AUTOINCREMENT, day_id TEXT NOT NULL, version INTEGER NOT NULL, payload TEXT NOT NULL)");
        try (ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM inbox")) {
          rows.next();
          processed = rows.getLong(1);
        }
      }
    } catch (SQLException | RuntimeException e) {
      if (opened != null) opened.close();
      acquiredLock.release();
      acquiredChannel.close();
      throw e;
    }
    lockChannel = acquiredChannel;
    lock = acquiredLock;
    connection = opened;
  }

  /** A single-event transaction is also available for diagnostics and tests. */
  public synchronized ApplyResult apply(SwipeMessage event) throws SQLException {
    return applyBatch(java.util.Collections.singletonList(event)).get(0);
  }

  /** One durable commit per ordered batch. Readers cannot observe uncommitted in-memory state. */
  public synchronized List<ApplyResult> applyBatch(List<SwipeMessage> events) throws SQLException {
    List<ApplyResult> results = new ArrayList<>(events.size());
    if (events.isEmpty()) return results;
    long previousProcessed = processed;
    connection.setAutoCommit(false);
    try {
      for (SwipeMessage event : events) results.add(applyInTransaction(event));
      connection.commit();
      return results;
    } catch (SQLException | RuntimeException failure) {
      try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
      // Reload from the rolled-back database on next access, including versions and Top-N.
      days.clear();
      processed = previousProcessed;
      throw failure;
    } finally {
      connection.setAutoCommit(true);
    }
  }

  private ApplyResult applyInTransaction(SwipeMessage event) throws SQLException {
    event.validate();
    if (!resortID.equals(event.resortID)) throw new IllegalArgumentException("Wrong resort partition");
    String fingerprint = event.semanticFingerprint();
    try (PreparedStatement query = connection.prepareStatement("SELECT fingerprint FROM inbox WHERE event_id=?")) {
      query.setString(1, event.eventId);
      try (ResultSet rows = query.executeQuery()) {
        if (rows.next()) {
          if (!fingerprint.equals(rows.getString(1))) throw new ConflictingEventException(event.eventId);
          return new ApplyResult(true, snapshot(event.dayID));
        }
      }
    }
    DayState day = day(event.dayID);
    long newTotal = Math.addExact(day.totals.getOrDefault(event.skierID, 0L), event.vertical);
    TreeSet<Score> nextTop = new TreeSet<>(RANKING);
    nextTop.addAll(day.top);
    Long previous = day.totals.get(event.skierID);
    if (previous != null) nextTop.remove(new Score(event.skierID, previous));
    nextTop.add(new Score(event.skierID, newTotal));
    if (nextTop.size() > topSize) nextTop.pollLast();
    boolean changed = !sameScores(day.top, nextTop);
    LeaderboardSnapshot nextSnapshot = buildSnapshot(event.dayID,
        day.version + (changed ? 1 : 0), day.processed + 1, nextTop);
    String payload = changed ? Json.encode(nextSnapshot) : null;
    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO inbox(event_id,fingerprint,day_id) VALUES(?,?,?)")) {
      insert.setString(1, event.eventId);
      insert.setString(2, fingerprint);
      insert.setString(3, event.dayID);
      insert.executeUpdate();
    }
    try (PreparedStatement upsert = connection.prepareStatement("INSERT INTO totals(day_id,skier_id,vertical) VALUES(?,?,?) ON CONFLICT(day_id,skier_id) DO UPDATE SET vertical=excluded.vertical")) {
      upsert.setString(1, event.dayID);
      upsert.setString(2, event.skierID);
      upsert.setLong(3, newTotal);
      upsert.executeUpdate();
    }
    if (changed) {
      try (PreparedStatement upsert = connection.prepareStatement("INSERT INTO snapshots(day_id,version,payload) VALUES(?,?,?) ON CONFLICT(day_id) DO UPDATE SET version=excluded.version,payload=excluded.payload")) {
        upsert.setString(1, event.dayID);
        upsert.setLong(2, nextSnapshot.version);
        upsert.setString(3, payload);
        upsert.executeUpdate();
      }
      try (PreparedStatement insert = connection.prepareStatement("INSERT INTO outbox(day_id,version,payload) VALUES(?,?,?)")) {
        insert.setString(1, event.dayID);
        insert.setLong(2, nextSnapshot.version);
        insert.setString(3, payload);
        insert.executeUpdate();
      }
    }
    day.totals.put(event.skierID, newTotal);
    day.top = nextTop;
    day.version = nextSnapshot.version;
    day.processed++;
    day.snapshot = nextSnapshot;
    processed++;
    return new ApplyResult(false, copy(nextSnapshot));
  }

  public synchronized LeaderboardSnapshot snapshot(String dayID) throws SQLException {
    return copy(day(dayID).snapshot);
  }

  public synchronized List<PendingUpdate> pendingUpdates(int limit) throws SQLException {
    List<PendingUpdate> updates = new ArrayList<>();
    try (PreparedStatement query = connection.prepareStatement("SELECT id,payload FROM outbox ORDER BY id LIMIT ?")) {
      query.setInt(1, limit);
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) updates.add(new PendingUpdate(rows.getLong(1), rows.getString(2)));
      }
    }
    return updates;
  }

  /** Only call after the broker confirm. A crash before deletion safely repeats the snapshot. */
  public synchronized void markPublished(long id) throws SQLException {
    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM outbox WHERE id=?")) {
      delete.setLong(1, id);
      delete.executeUpdate();
    }
  }

  /** All IDs through this high-water mark were positively confirmed on one ordered channel. */
  public synchronized void markPublishedThrough(long id) throws SQLException {
    try (PreparedStatement delete = connection.prepareStatement("DELETE FROM outbox WHERE id<=?")) {
      delete.setLong(1, id);
      delete.executeUpdate();
    }
  }

  public synchronized long pendingOutbox() throws SQLException {
    try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("SELECT COUNT(*) FROM outbox")) {
      rows.next();
      return rows.getLong(1);
    }
  }

  public synchronized long processed() { return processed; }

  public synchronized long processed(String dayID) throws SQLException { return day(dayID).processed; }

  public synchronized long total(String dayID, String skierID) throws SQLException {
    return day(dayID).totals.getOrDefault(skierID, 0L);
  }

  private DayState day(String dayID) throws SQLException {
    DayState existing = days.get(dayID);
    if (existing != null) return existing;
    DayState loaded = new DayState();
    try (PreparedStatement query = connection.prepareStatement("SELECT skier_id,vertical FROM totals WHERE day_id=?")) {
      query.setString(1, dayID);
      try (ResultSet rows = query.executeQuery()) {
        while (rows.next()) {
          String skier = rows.getString(1);
          long vertical = rows.getLong(2);
          loaded.totals.put(skier, vertical);
          loaded.top.add(new Score(skier, vertical));
          if (loaded.top.size() > topSize) loaded.top.pollLast();
        }
      }
    }
    try (PreparedStatement query = connection.prepareStatement("SELECT version FROM snapshots WHERE day_id=?")) {
      query.setString(1, dayID);
      try (ResultSet rows = query.executeQuery()) { if (rows.next()) loaded.version = rows.getLong(1); }
    }
    try (PreparedStatement query = connection.prepareStatement("SELECT COUNT(*) FROM inbox WHERE day_id=?")) {
      query.setString(1, dayID);
      try (ResultSet rows = query.executeQuery()) { rows.next(); loaded.processed = rows.getLong(1); }
    }
    loaded.snapshot = buildSnapshot(dayID, loaded.version, loaded.processed, loaded.top);
    days.put(dayID, loaded);
    return loaded;
  }

  private LeaderboardSnapshot buildSnapshot(String dayID, long version, long count, TreeSet<Score> top) {
    LeaderboardSnapshot snapshot = new LeaderboardSnapshot();
    snapshot.resortID = resortID;
    snapshot.dayID = dayID;
    snapshot.version = version;
    snapshot.processed = count;
    snapshot.serverTimestamp = Instant.now().toString();
    snapshot.top = new ArrayList<>();
    int rank = 1;
    for (Score score : top) snapshot.top.add(new LeaderboardEntry(rank++, score.skierID, score.vertical));
    return snapshot;
  }

  private static LeaderboardSnapshot copy(LeaderboardSnapshot original) {
    LeaderboardSnapshot copy = new LeaderboardSnapshot();
    copy.resortID = original.resortID;
    copy.dayID = original.dayID;
    copy.serverTimestamp = original.serverTimestamp;
    copy.version = original.version;
    copy.processed = original.processed;
    copy.top = new ArrayList<>();
    for (LeaderboardEntry entry : original.top) copy.top.add(new LeaderboardEntry(entry.rank, entry.skierID, entry.vertical));
    return copy;
  }

  private static boolean sameScores(TreeSet<Score> first, TreeSet<Score> second) {
    if (first.size() != second.size()) return false;
    java.util.Iterator<Score> left = first.iterator();
    java.util.Iterator<Score> right = second.iterator();
    while (left.hasNext()) if (RANKING.compare(left.next(), right.next()) != 0) return false;
    return true;
  }

  private static int compareSkierIds(String first, String second) {
    try {
      int value = Long.compare(Long.parseLong(first), Long.parseLong(second));
      return value == 0 ? first.compareTo(second) : value;
    } catch (NumberFormatException e) {
      return first.compareTo(second);
    }
  }

  @Override public synchronized void close() throws SQLException, IOException {
    try { connection.close(); } finally {
      try { lock.release(); } finally { lockChannel.close(); }
    }
  }

  private static final class DayState {
    private final Map<String, Long> totals = new HashMap<>();
    private TreeSet<Score> top = new TreeSet<>(RANKING);
    private long version;
    private long processed;
    private LeaderboardSnapshot snapshot;
  }

  private static final class Score {
    private final String skierID;
    private final long vertical;
    private Score(String skierID, long vertical) { this.skierID = skierID; this.vertical = vertical; }
  }

  public static final class ApplyResult {
    public final boolean duplicate;
    public final LeaderboardSnapshot snapshot;
    private ApplyResult(boolean duplicate, LeaderboardSnapshot snapshot) { this.duplicate = duplicate; this.snapshot = snapshot; }
  }

  public static final class PendingUpdate {
    public final long id;
    public final String payload;
    private PendingUpdate(long id, String payload) { this.id = id; this.payload = payload; }
  }

  public static final class ConflictingEventException extends IllegalArgumentException {
    public ConflictingEventException(String eventID) { super("Conflicting payload for eventId " + eventID); }
  }
}
