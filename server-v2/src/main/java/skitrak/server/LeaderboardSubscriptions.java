package skitrak.server;

import skitrak.common.LeaderboardSnapshot;
import java.util.*;

/** All binding/session/cache transitions run under this monitor, including last unsubscribe. */
public final class LeaderboardSubscriptions {
  public interface Bindings { void bind(String resort); void unbind(String resort); }
  public interface Sink { String id(); void send(LeaderboardSnapshot snapshot); }
  private static final class Subscriber {
    final String day; final Sink sink; long sentVersion = -1;
    Subscriber(String day, Sink sink) { this.day = day; this.sink = sink; }
  }
  private static final class Resort {
    final Map<String, Subscriber> subscribers = new LinkedHashMap<>();
    final Map<String, LeaderboardSnapshot> latest = new HashMap<>();
  }
  private final Map<String, Resort> resorts = new LinkedHashMap<>();
  private final Bindings bindings;
  public LeaderboardSubscriptions(Bindings bindings) { this.bindings = bindings; }
  public synchronized void add(String resortId, String day, Sink sink) {
    Resort resort = resorts.get(resortId);
    if (resort == null) { bindings.bind(resortId); resort = new Resort(); resorts.put(resortId, resort); }
    Subscriber subscriber = new Subscriber(day, sink); resort.subscribers.put(sink.id(), subscriber);
    LeaderboardSnapshot cached = resort.latest.get(day);
    if (cached != null) deliver(subscriber, cached);
  }
  public synchronized void remove(String resortId, String sessionId) {
    Resort resort = resorts.get(resortId); if (resort == null) return;
    resort.subscribers.remove(sessionId);
    if (resort.subscribers.isEmpty()) { bindings.unbind(resortId); resorts.remove(resortId); }
    else resort.latest.keySet().removeIf(day -> resort.subscribers.values().stream().noneMatch(s -> s.day.equals(day)));
  }
  public synchronized boolean accept(LeaderboardSnapshot snapshot) {
    Resort resort = resorts.get(snapshot.resortID); if (resort == null) return false;
    boolean interested = resort.subscribers.values().stream().anyMatch(s -> s.day.equals(snapshot.dayID));
    if (!interested) return false;
    LeaderboardSnapshot old = resort.latest.get(snapshot.dayID);
    if (old != null && old.version >= snapshot.version) return false;
    resort.latest.put(snapshot.dayID, snapshot);
    for (Subscriber subscriber : resort.subscribers.values()) if (subscriber.day.equals(snapshot.dayID)) deliver(subscriber, snapshot);
    return true;
  }
  private static void deliver(Subscriber subscriber, LeaderboardSnapshot snapshot) {
    if (snapshot.version > subscriber.sentVersion) { subscriber.sentVersion = snapshot.version; subscriber.sink.send(snapshot); }
  }
  public synchronized Set<String> resortIds() { return new LinkedHashSet<>(resorts.keySet()); }
  public synchronized Map<String, Set<String>> activeDays() {
    Map<String, Set<String>> days = new LinkedHashMap<>();
    resorts.forEach((id, resort) -> { Set<String> values = new LinkedHashSet<>(); resort.subscribers.values().forEach(s -> values.add(s.day)); days.put(id, values); });
    return days;
  }
  public synchronized boolean hasDay(String resort, String day) { Resort r = resorts.get(resort); return r != null && r.subscribers.values().stream().anyMatch(s -> s.day.equals(day)); }
  public synchronized int sessions() { return resorts.values().stream().mapToInt(r -> r.subscribers.size()).sum(); }
  public synchronized int bindings() { return resorts.size(); }
}
