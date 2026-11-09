package skitrak.common;
public final class LeaderboardEntry {
  public int rank;
  public String skierID;
  public long vertical;
  public LeaderboardEntry() {}
  public LeaderboardEntry(int rank, String skierID, long vertical) { this.rank = rank; this.skierID = skierID; this.vertical = vertical; }
}
