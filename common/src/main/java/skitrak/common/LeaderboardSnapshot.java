package skitrak.common;
import java.util.ArrayList;
import java.util.List;
/** version is monotonic per resort/day and stored durably by the consumer. */
public final class LeaderboardSnapshot {
  public String resortID, dayID, serverTimestamp;
  public long version, processed;
  public List<LeaderboardEntry> top = new ArrayList<>();
  public LeaderboardSnapshot() {}
}
