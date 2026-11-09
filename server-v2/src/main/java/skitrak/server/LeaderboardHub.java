package skitrak.server;

import com.rabbitmq.client.*;
import skitrak.common.*;
import javax.websocket.Session;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** RabbitMQ backplane connection is separate from the producer pool: one exclusive generated queue
 * per live server, dynamically bound exactly to lb.{resortID} while local sessions exist. */
public final class LeaderboardHub implements AutoCloseable {
  private final String serverId, consumerUrl;
  private final ChannelPool.Connector connector;
  private final ScheduledExecutorService lifecycle = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "leaderboard-lifecycle"));
  private final ExecutorService snapshots = Executors.newFixedThreadPool(4, r -> new Thread(r, "snapshot-fetch"));
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final AtomicBoolean stopped = new AtomicBoolean();
  private volatile boolean ready;
  private final Object connectionOwnership = new Object();
  private volatile Connection connection;
  private Channel channel;
  private String queue;
  private long generation;
  private final Map<String, Long> pendingSnapshots = new HashMap<>();
  private long snapshotRequestSequence;
  private final LeaderboardSubscriptions subscriptions;
  public final LongAdder snapshotFailures = new LongAdder(), updates = new LongAdder();
  public LeaderboardHub(String serverId, String consumerUrl) {
    this(serverId, consumerUrl, () -> RabbitConfig.fromEnv().newFactory().newConnection("backplane-" + serverId));
  }
  LeaderboardHub(String serverId, String consumerUrl, ChannelPool.Connector connector) {
    this.connector = connector;
    this.serverId = serverId; this.consumerUrl = consumerUrl.replaceAll("/+$", "");
    subscriptions = new LeaderboardSubscriptions(new LeaderboardSubscriptions.Bindings() {
      public void bind(String resort) { bindResort(resort, true); }
      public void unbind(String resort) { bindResort(resort, false); }
    });
  }
  public void start() {
    execute(this::connect);
    long interval = Env.longValue("SNAPSHOT_RESYNC_INTERVAL_MS", 5000, 1000, 600000);
    lifecycle.scheduleWithFixedDelay(() -> {
      if (ready) for (Map.Entry<String, Set<String>> entry : subscriptions.activeDays().entrySet())
        for (String day : entry.getValue()) fetchSnapshot(entry.getKey(), day);
    }, interval, interval, TimeUnit.MILLISECONDS);
  }
  private void execute(Runnable task) { if (!stopped.get()) try { lifecycle.execute(task); } catch (RejectedExecutionException ignored) { } }
  private void connect() {
    if (stopped.get() || ready) return;
    Connection fresh = null;
    try {
      fresh = connector.connect();
      Channel ch = fresh.createChannel();
      ch.exchangeDeclare(Topology.LEADERBOARD_EXCHANGE, "topic", true);
      String q = ch.queueDeclare("", false, true, true,
          Map.of("x-max-length", 10000, "x-message-ttl", 60000)).getQueue();
      synchronized (connectionOwnership) {
        if (stopped.get()) throw new IOException("backplane is stopping");
        connection = fresh; channel = ch; queue = q;
      }
      long installed = ++generation;
      fresh.addShutdownListener(cause -> execute(() -> disconnected(installed)));
      ch.addShutdownListener(cause -> execute(() -> disconnected(installed)));
      for (String resort : subscriptions.resortIds()) ch.queueBind(q, Topology.LEADERBOARD_EXCHANGE, "lb." + resort);
      ch.basicQos(100);
      // Manual ack plus prefetch bounds the broker-to-lifecycle handoff to 100 updates.
      ch.basicConsume(q, false, (tag, message) -> {
        String json = new String(message.getBody(), StandardCharsets.UTF_8);
        execute(() -> {
          if (installed != generation || !ready) return;
          try {
            LeaderboardSnapshot snapshot = Json.decode(json, LeaderboardSnapshot.class);
            if (valid(snapshot)) { subscriptions.accept(snapshot); updates.increment(); }
          } catch (RuntimeException ignored) { /* malformed backplane updates never terminate the lifecycle loop */ }
          try { ch.basicAck(message.getEnvelope().getDeliveryTag(), false); }
          catch (IOException | RuntimeException error) { disconnected(installed); }
        });
      }, tag -> execute(() -> disconnected(installed)));
      ready = true;
      for (Map.Entry<String, Set<String>> entry : subscriptions.activeDays().entrySet())
        for (String day : entry.getValue()) fetchSnapshot(entry.getKey(), day);
    } catch (Exception error) {
      ready = false;
      if (fresh != null) try { fresh.abort(); } catch (RuntimeException ignored) { }
      connection = null; channel = null; queue = null;
      if (!stopped.get()) lifecycle.schedule(this::connect, 1, TimeUnit.SECONDS);
    }
  }
  private static boolean valid(LeaderboardSnapshot s) {
    return s != null && s.resortID != null && s.dayID != null && s.top != null && s.version >= 0;
  }
  private void disconnected(long installed) {
    if (installed != generation || stopped.get()) return;
    // Invalidate callbacks and bootstrap responses from the old connection before replacing it.
    generation++; ready = false; pendingSnapshots.clear();
    if (connection != null) try { connection.abort(); } catch (RuntimeException ignored) { }
    connection = null; channel = null; queue = null;
    lifecycle.schedule(this::connect, 1, TimeUnit.SECONDS);
  }
  private void bindResort(String resort, boolean bind) {
    if (!ready || channel == null) return; // reconnect restores bindings from the registry
    try {
      if (bind) channel.queueBind(queue, Topology.LEADERBOARD_EXCHANGE, "lb." + resort);
      else channel.queueUnbind(queue, Topology.LEADERBOARD_EXCHANGE, "lb." + resort);
    } catch (Exception error) { disconnected(generation); }
  }
  public void join(Session session, String resort, String day) {
    execute(() -> {
      if (!session.isOpen()) return;
      subscriptions.add(resort, day, new SessionOutbox(session,
          Env.integer("WS_SEND_QUEUE_SIZE", 16, 1, 10000), () -> leave(session.getId(), resort)));
      // Subscription binding is complete before HTTP snapshot begins. Concurrent update and
      // bootstrap responses enter the same serial loop; monotonically increasing versions win.
      if (ready) fetchSnapshot(resort, day);
    });
  }
  public void leave(String sessionId, String resort) { execute(() -> {
    subscriptions.remove(resort, sessionId);
    // Forget requests for abandoned subscriptions. A later new subscriber must obtain a fresh
    // snapshot, not reuse an HTTP response captured before an unbind/rebind gap.
    pendingSnapshots.keySet().removeIf(key -> {
      String[] partition = key.split(":", 2);
      return !subscriptions.hasDay(partition[0], partition[1]);
    });
  }); }
  private void fetchSnapshot(String resort, String day) {
    if (!ready || !subscriptions.hasDay(resort, day)) return;
    String key = resort + ":" + day;
    if (pendingSnapshots.containsKey(key)) return;
    long token = ++snapshotRequestSequence; pendingSnapshots.put(key, token);
    long installed = generation;
    snapshots.execute(() -> {
      LeaderboardSnapshot fetched = null;
      try {
        HttpRequest request = HttpRequest.newBuilder(URI.create(consumerUrl + "/leaderboards/" + resort + "?dayID=" + day))
            .timeout(Duration.ofSeconds(3)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("snapshot HTTP " + response.statusCode());
        fetched = Json.decode(response.body(), LeaderboardSnapshot.class);
        if (!valid(fetched) || !resort.equals(fetched.resortID) || !day.equals(fetched.dayID)) throw new IOException("snapshot partition mismatch");
      } catch (Exception error) { fetched = null; snapshotFailures.increment(); if (error instanceof InterruptedException) Thread.currentThread().interrupt(); }
      LeaderboardSnapshot result = fetched;
      execute(() -> {
        if (installed != generation || !Long.valueOf(token).equals(pendingSnapshots.get(key))) return;
        pendingSnapshots.remove(key);
        if (result != null) subscriptions.accept(result);
        else if (subscriptions.hasDay(resort, day)) lifecycle.schedule(() -> fetchSnapshot(resort, day), 1, TimeUnit.SECONDS);
      });
    });
  }
  public boolean ready() { return ready && !stopped.get(); }
  public int sessionCount() { return subscriptions.sessions(); }
  public int bindingCount() { return subscriptions.bindings(); }
  @Override public void close() {
    if (!stopped.compareAndSet(false, true)) return;
    ready = false; lifecycle.shutdownNow(); snapshots.shutdownNow();
    Connection old;
    synchronized (connectionOwnership) { old = connection; connection = null; }
    if (old != null) try { old.abort(); } catch (RuntimeException ignored) { }
  }
}
