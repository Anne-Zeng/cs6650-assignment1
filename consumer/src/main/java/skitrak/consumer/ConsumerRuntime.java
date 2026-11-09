package skitrak.consumer;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Delivery;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import skitrak.common.Json;
import skitrak.common.LeaderboardSnapshot;
import skitrak.common.RabbitConfig;
import skitrak.common.SwipeMessage;
import skitrak.common.Topology;

/** Durable, independently owned resort partitions, sharing a resizable executor. */
final class ConsumerRuntime implements AutoCloseable {
  private final ConsumerConfig config;
  private final ConnectionOpener opener;
  private final ThreadPoolExecutor workers;
  private final ScheduledExecutorService maintenance;
  private final ConsumerStore[] stores = new ConsumerStore[Topology.RESORT_COUNT];
  private final SerialLane[] lanes = new SerialLane[Topology.RESORT_COUNT];
  private final AtomicLong[] duplicates = counters();
  private final AtomicLong[] failedAttempts = counters();
  private final LagHistogram[] lag = histograms();
  private final LagHistogram overallLag = new LagHistogram();
  private final String[] partitionErrors = new String[Topology.RESORT_COUNT];
  private final AtomicLong reconnects = new AtomicLong();
  private final AtomicLong generationIDs = new AtomicLong();
  private final AtomicBoolean stopping = new AtomicBoolean();
  private final AtomicBoolean closing = new AtomicBoolean();
  private final Object lifecycleLock = new Object();
  private volatile BrokerGeneration current;
  private Connection connectingSource;
  private Connection connectingPublisher;
  private volatile String brokerError = "Connecting";
  private final Thread lifecycle;

  ConsumerRuntime(ConsumerConfig config) throws IOException, SQLException {
    this(config, (factory, name) -> factory.newConnection(name));
  }

  ConsumerRuntime(ConsumerConfig config, ConnectionOpener opener) throws IOException, SQLException {
    this.config = config;
    this.opener = opener;
    workers = new ThreadPoolExecutor(config.dynamicScaling ? config.minThreads : config.threads,
        config.threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
        runnable -> new Thread(runnable, "resort-worker"));
    workers.allowCoreThreadTimeOut(true);
    int opened = 0;
    try {
      for (int index = 0; index < stores.length; index++) {
        stores[index] = new ConsumerStore(config.storeDir, Integer.toString(index + 1), config.topN);
        lanes[index] = new SerialLane(workers);
        opened++;
      }
    } catch (IOException | SQLException | RuntimeException e) {
      for (int index = 0; index < opened; index++) stores[index].close();
      workers.shutdownNow();
      throw e;
    }
    maintenance = Executors.newScheduledThreadPool(2, runnable -> new Thread(runnable, "consumer-maintenance"));
    lifecycle = new Thread(this::connectLoop, "consumer-broker-lifecycle");
  }

  void start() {
    lifecycle.start();
    maintenance.scheduleWithFixedDelay(this::flushAcks, config.ackFlushMs, config.ackFlushMs, TimeUnit.MILLISECONDS);
    maintenance.scheduleWithFixedDelay(this::monitorAndScale, 1, 1, TimeUnit.SECONDS);
  }

  LeaderboardSnapshot snapshot(int resort, String dayID) throws SQLException {
    return stores[resort - 1].snapshot(dayID);
  }

  boolean healthy() {
    BrokerGeneration generation = current;
    if (stopping.get() || generation == null || generation.failed.get() || !generation.source.isOpen()
        || !generation.publisher.isOpen() || generation.initializing.get() != 0) return false;
    synchronized (partitionErrors) {
      for (String error : partitionErrors) if (error != null) return false;
    }
    return true;
  }

  Map<String, Object> metrics() throws SQLException {
    BrokerGeneration generation = current;
    Map<String, Object> metrics = new LinkedHashMap<>();
    Map<String, Object> perResort = new LinkedHashMap<>();
    long processed = 0, duplicateCount = 0, pending = 0, failed = 0, inFlight = 0, ready = 0;
    for (int index = 0; index < stores.length; index++) {
      long resortProcessed = stores[index].processed();
      long resortPending = stores[index].pendingOutbox();
      long resortInflight = generation == null ? 0 : generation.partitions[index].inFlight.get();
      long resortReady = generation == null ? 0 : generation.partitions[index].queueReady.get();
      processed += resortProcessed;
      duplicateCount += duplicates[index].get();
      pending += resortPending;
      failed += failedAttempts[index].get();
      inFlight += resortInflight;
      ready += resortReady;
      Map<String, Object> resort = new LinkedHashMap<>();
      resort.put("processed", resortProcessed);
      resort.put("duplicates", duplicates[index].get());
      resort.put("pendingOutbox", resortPending);
      resort.put("inFlight", resortInflight);
      resort.put("queueReady", resortReady);
      resort.put("laneBacklog", lanes[index].size());
      resort.putAll(lag[index].metrics());
      synchronized (partitionErrors) { resort.put("error", partitionErrors[index]); }
      perResort.put(Integer.toString(index + 1), resort);
    }
    metrics.put("healthy", healthy());
    metrics.put("processed", processed);
    metrics.put("swipesProcessed", processed);
    metrics.put("duplicates", duplicateCount);
    metrics.put("pendingOutbox", pending);
    metrics.put("failedAttempts", failed);
    metrics.put("inFlight", inFlight);
    metrics.put("queueReady", ready);
    long sampledAt = generation == null ? 0 : generation.queueMetricsUpdatedAt.get();
    metrics.put("queueMetricsUpdatedAt", sampledAt);
    metrics.put("queueMetricsAgeMs", sampledAt == 0 ? -1 : System.currentTimeMillis() - sampledAt);
    metrics.put("resortCount", stores.length);
    metrics.put("configuredThreads", config.threads);
    metrics.put("coreThreads", workers.getCorePoolSize());
    metrics.put("activeWorkers", workers.getActiveCount());
    metrics.put("poolSize", workers.getPoolSize());
    metrics.put("maxAggregationParallelism", Math.min(config.threads, stores.length));
    metrics.put("dynamicScaling", config.dynamicScaling);
    metrics.put("prefetch", config.prefetch);
    metrics.put("ackBatchSize", config.ackBatchSize);
    metrics.put("storeBatchSize", config.storeBatchSize);
    metrics.put("storeBatchWaitMs", config.storeBatchWaitMs);
    metrics.put("reconnects", reconnects.get());
    metrics.put("brokerError", brokerError);
    metrics.putAll(overallLag.metrics());
    metrics.put("lagClockSource", "ingest-to-consumer wall-clock difference; synchronize host clocks before interpreting the 100ms target");
    metrics.put("perResort", perResort);
    return metrics;
  }

  private void connectLoop() {
    long backoffMs = 250;
    while (!stopping.get()) {
      BrokerGeneration generation = null;
      try {
        generation = connect();
        synchronized (lifecycleLock) {
          if (stopping.get()) { generation.close(); break; }
          current = generation;
        }
        startConsumers(generation);
        brokerError = null;
        backoffMs = 250;
        while (!stopping.get() && !generation.failed.get() && generation.source.isOpen() && generation.publisher.isOpen()) {
          Thread.sleep(100);
        }
      } catch (Exception failure) {
        brokerError = failure.toString();
        if (!stopping.get()) System.err.println("Consumer broker unavailable: " + failure);
      } finally {
        if (!stopping.get()) {
          current = null;
          if (generation != null) generation.close();
        }
      }
      if (stopping.get()) break;
      reconnects.incrementAndGet();
      try { Thread.sleep(backoffMs); } catch (InterruptedException ignored) { if (stopping.get()) break; }
      backoffMs = Math.min(10000, backoffMs * 2);
    }
  }

  private BrokerGeneration connect() throws Exception {
    ConnectionFactory factory = RabbitConfig.fromEnv().newFactory();
    factory.setChannelRpcTimeout(Math.min(5000, config.shutdownMs));
    Connection source = null;
    Connection publisher = null;
    try {
      source = opener.open(factory, "skitrak-consumer-source");
      synchronized (lifecycleLock) {
        if (stopping.get()) throw new InterruptedException("Consumer is stopping");
        connectingSource = source;
      }
      publisher = opener.open(factory, "skitrak-consumer-broadcast");
      synchronized (lifecycleLock) {
        if (stopping.get()) throw new InterruptedException("Consumer is stopping");
        connectingPublisher = publisher;
      }
      Channel monitor = source.createChannel();
      Topology.declare(monitor);
      BrokerGeneration generation = new BrokerGeneration(generationIDs.incrementAndGet(), source, publisher, monitor);
      source.addShutdownListener(signal -> generation.failed.set(true));
      publisher.addShutdownListener(signal -> generation.failed.set(true));
      for (int index = 0; index < stores.length; index++) {
        Channel consume = source.createChannel();
        consume.basicQos(config.prefetch);
        Channel publish = publisher.createChannel();
        publish.confirmSelect();
        final int partition = index;
        consume.addShutdownListener(signal -> generation.failed.set(true));
        publish.addShutdownListener(signal -> generation.failed.set(true));
        generation.partitions[index] = new Partition(consume, publish);
        synchronized (partitionErrors) { partitionErrors[partition] = null; }
      }
      return generation;
    } catch (Exception failure) {
      abort(source);
      abort(publisher);
      throw failure;
    } finally {
      synchronized (lifecycleLock) { connectingSource = null; connectingPublisher = null; }
    }
  }

  private void startConsumers(BrokerGeneration generation) throws IOException {
    // Pending durable notifications are placed on each lane before newly delivered swipes.
    for (int index = 0; index < stores.length; index++) {
      final int partition = index;
      lanes[index].submit(() -> {
        try { relayWithRetry(generation, partition); }
        finally { generation.initializing.decrementAndGet(); }
      });
      generation.partitions[index].batcher = new ResortBatcher<>(config.storeBatchSize,
          config.storeBatchWaitMs, maintenance, task -> lanes[partition].submit(task),
          batch -> processBatch(generation, partition, batch));
      generation.partitions[index].consumerTag = generation.partitions[index].consume.basicConsume("resort." + (index + 1), false,
          (tag, delivery) -> {
            generation.partitions[partition].inFlight.incrementAndGet();
            generation.partitions[partition].batcher.offer(delivery);
          }, tag -> {
            brokerError = "Broker cancelled resort " + (partition + 1);
            generation.failed.set(true);
          });
    }
  }

  private void processBatch(BrokerGeneration generation, int partition, List<Delivery> deliveries) {
    if (!isCurrent(generation) || partitionPaused(partition)) return;
    List<SwipeMessage> messages = new ArrayList<>(deliveries.size());
    try {
      for (Delivery delivery : deliveries) {
        SwipeMessage message = Json.decode(new String(delivery.getBody(), StandardCharsets.UTF_8), SwipeMessage.class);
        if (message == null) throw new IllegalArgumentException("Null swipe message");
        message.validate();
        if (!message.resortID.equals(Integer.toString(partition + 1))) throw new IllegalArgumentException("Routing key/resort mismatch");
        messages.add(message);
      }
    } catch (RuntimeException invalid) {
      pause(partition, "Invalid message; partition paused to preserve order: " + invalid);
      return;
    }
    for (int attempt = 0; attempt < config.retryAttempts && isCurrent(generation); attempt++) {
      try {
        List<ConsumerStore.ApplyResult> results = stores[partition].applyBatch(messages);
        for (int index=0; index<results.size(); index++) {
          if (results.get(index).duplicate) duplicates[partition].incrementAndGet();
          else recordLag(partition, messages.get(index).timestamp);
        }
        relay(generation, partition);
        for (Delivery delivery : deliveries)
          acknowledge(generation, partition, delivery.getEnvelope().getDeliveryTag());
        return;
      } catch (ConsumerStore.ConflictingEventException | ArithmeticException invalid) {
        pause(partition, "Conflicting/corrupt message; partition paused to preserve order: " + invalid);
        return;
      } catch (Exception failure) {
        failedAttempts[partition].incrementAndGet();
        brokerError = failure.toString();
        if (!retryDelay(attempt, generation)) return;
      }
    }
    if (isCurrent(generation)) {
      brokerError = "Retry budget exhausted for resort " + (partition + 1) + "; reconnecting before ordered redelivery";
      generation.failed.set(true);
    }
  }

  private void relayWithRetry(BrokerGeneration generation, int partition) {
    for (int attempt = 0; attempt < config.retryAttempts && isCurrent(generation); attempt++) {
      try { relay(generation, partition); return; }
      catch (Exception failure) {
        failedAttempts[partition].incrementAndGet();
        brokerError = failure.toString();
        if (!retryDelay(attempt, generation)) return;
      }
    }
    if (isCurrent(generation)) generation.failed.set(true);
  }

  private void relay(BrokerGeneration generation, int partition) throws Exception {
    while (isCurrent(generation)) {
      List<ConsumerStore.PendingUpdate> updates = stores[partition].pendingUpdates(100);
      if (updates.isEmpty()) return;
      for (ConsumerStore.PendingUpdate update : updates) {
        if (!isCurrent(generation)) throw new IOException("Obsolete broker generation");
        Channel publish = generation.partitions[partition].publish;
        // No binding is normal when nobody currently subscribes to this resort.
        publish.basicPublish(Topology.LEADERBOARD_EXCHANGE, "lb." + (partition + 1), false,
            new AMQP.BasicProperties.Builder().contentType("application/json").build(),
            update.payload.getBytes(StandardCharsets.UTF_8));
      }
      // All snapshots retain channel order; delete only after the entire group is confirmed.
      generation.partitions[partition].publish.waitForConfirmsOrDie(config.publishConfirmMs);
      stores[partition].markPublishedThrough(updates.get(updates.size()-1).id);
    }
    throw new IOException("Broker connection no longer current");
  }

  private void acknowledge(BrokerGeneration generation, int index, long tag) throws IOException {
    Partition partition = generation.partitions[index];
    partition.pendingAckCount++;
    partition.lastDeliveryTag = tag;
    if (partition.pendingAckCount >= config.ackBatchSize) {
      try { flushAck(generation, partition); }
      catch (IOException failure) {
        // An ACK may already have reached the broker. Never ACK the same tag twice.
        generation.failed.set(true);
        throw failure;
      }
    }
  }

  private void flushAck(BrokerGeneration generation, Partition partition) throws IOException {
    if (partition.pendingAckCount == 0 || !isCurrent(generation)) return;
    // One ordered lane owns this channel, so all tags up to the high-water mark completed.
    partition.consume.basicAck(partition.lastDeliveryTag, config.ackBatchSize > 1);
    partition.inFlight.addAndGet(-partition.pendingAckCount);
    partition.pendingAckCount = 0;
  }

  private void flushAcks() {
    BrokerGeneration generation = current;
    if (generation == null || generation.failed.get()) return;
    for (int index = 0; index < stores.length; index++) {
      final int partition = index;
      Partition state = generation.partitions[index];
      if (state == null || state.inFlight.get() == 0 || !state.flushQueued.compareAndSet(false, true)) continue;
      lanes[index].submit(() -> {
        try { flushAck(generation, generation.partitions[partition]); }
        catch (IOException failure) { brokerError = failure.toString(); generation.failed.set(true); }
        finally { state.flushQueued.set(false); }
      });
    }
  }

  private void monitorAndScale() {
    BrokerGeneration generation = current;
    try {
      int busyResorts = 0;
      for (int index = 0; index < stores.length; index++) {
        long ready = 0;
        if (generation != null && !generation.failed.get()) {
          ready = generation.monitor.queueDeclarePassive("resort." + (index + 1)).getMessageCount();
          generation.partitions[index].queueReady.set(ready);
        }
        if (ready > 0 || (generation != null && generation.partitions[index].inFlight.get() > 0)) busyResorts++;
      }
      if (generation != null && !generation.failed.get()) generation.queueMetricsUpdatedAt.set(System.currentTimeMillis());
      if (config.dynamicScaling) {
        // More than 10 useful aggregation workers would violate resort ordering.
        int desired = Math.max(config.minThreads, Math.min(config.threads, busyResorts));
        if (desired != workers.getCorePoolSize()) workers.setCorePoolSize(desired);
      }
    } catch (Exception failure) {
      if (generation != null) generation.failed.set(true);
      brokerError = failure.toString();
    }
  }

  private void recordLag(int partition, String timestamp) {
    try {
      long nanos = Duration.between(Instant.parse(timestamp), Instant.now()).toNanos();
      lag[partition].record(nanos);
      overallLag.record(nanos);
    } catch (RuntimeException ignored) { /* Invalid timestamp is rejected by shared validation. */ }
  }

  private boolean retryDelay(int attempt, BrokerGeneration generation) {
    if (!isCurrent(generation)) return false;
    try { Thread.sleep(Math.min(2000, 50L << Math.min(attempt, 5))); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    return isCurrent(generation);
  }

  private boolean isCurrent(BrokerGeneration generation) {
    return current == generation && !generation.failed.get() && !closing.get();
  }

  private boolean partitionPaused(int partition) {
    synchronized (partitionErrors) { return partitionErrors[partition] != null; }
  }

  private void pause(int partition, String error) {
    failedAttempts[partition].incrementAndGet();
    synchronized (partitionErrors) { partitionErrors[partition] = error; }
    System.err.println("Resort " + (partition + 1) + ": " + error);
  }

  @Override public void close() {
    BrokerGeneration generation;
    Connection pendingSource;
    Connection pendingPublisher;
    synchronized (lifecycleLock) {
      if (!stopping.compareAndSet(false, true)) return;
      generation = current;
      pendingSource = connectingSource;
      pendingPublisher = connectingPublisher;
    }
    maintenance.shutdownNow();
    lifecycle.interrupt();
    abort(pendingSource);
    abort(pendingPublisher);
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.shutdownMs);
    try { lifecycle.join(Math.min(6000, config.shutdownMs)); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    if (generation != null) {
      for (Partition partition : generation.partitions)
        if (partition != null && partition.batcher != null) partition.batcher.close();
      // Cancellation shares the channel's lane with ACKs, and RPCs have a bounded timeout.
      for (int index = 0; index < stores.length; index++) {
        Partition partition = generation.partitions[index];
        if (partition == null || partition.consumerTag == null) continue;
        lanes[index].submit(() -> {
          try { partition.consume.basicCancel(partition.consumerTag); } catch (Exception ignored) { }
        });
      }
      while (System.nanoTime() < deadline && pendingLaneWork() > 0) {
        try { Thread.sleep(10); } catch (InterruptedException ignored) { break; }
      }
      for (int index = 0; index < stores.length; index++) {
        final int partition = index;
        lanes[index].submit(() -> {
          try { flushAck(generation, generation.partitions[partition]); } catch (IOException ignored) { }
        });
      }
      while (System.nanoTime() < deadline && pendingLaneWork() > 0) {
        try { Thread.sleep(10); } catch (InterruptedException ignored) { break; }
      }
    }
    workers.shutdown();
    try { workers.awaitTermination(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    closing.set(true);
    current = null;
    if (generation != null) generation.close();
    workers.shutdownNow();
    try { workers.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    for (ConsumerStore store : stores) {
      try { store.close(); } catch (Exception failure) { System.err.println("Store close failed: " + failure); }
    }
  }

  private int pendingLaneWork() {
    int count = 0;
    for (SerialLane lane : lanes) count += lane.size();
    return count;
  }

  private static AtomicLong[] counters() {
    AtomicLong[] result = new AtomicLong[Topology.RESORT_COUNT];
    for (int index = 0; index < result.length; index++) result[index] = new AtomicLong();
    return result;
  }

  private static LagHistogram[] histograms() {
    LagHistogram[] result = new LagHistogram[Topology.RESORT_COUNT];
    for (int index = 0; index < result.length; index++) result[index] = new LagHistogram();
    return result;
  }

  private static void abort(Connection connection) {
    if (connection != null) { try { connection.abort(); } catch (Exception ignored) { } }
  }

  @FunctionalInterface interface ConnectionOpener {
    Connection open(ConnectionFactory factory, String name) throws Exception;
  }

  private static final class BrokerGeneration implements AutoCloseable {
    final long id;
    final Connection source;
    final Connection publisher;
    final Channel monitor;
    final AtomicBoolean failed = new AtomicBoolean();
    final AtomicInteger initializing = new AtomicInteger(Topology.RESORT_COUNT);
    final AtomicLong queueMetricsUpdatedAt = new AtomicLong();
    final Partition[] partitions = new Partition[Topology.RESORT_COUNT];
    BrokerGeneration(long id, Connection source, Connection publisher, Channel monitor) {
      this.id = id; this.source = source; this.publisher = publisher; this.monitor = monitor;
    }
    @Override public void close() { abort(source); abort(publisher); }
  }

  private static final class Partition {
    final Channel consume;
    final Channel publish;
    final AtomicLong inFlight = new AtomicLong();
    final AtomicLong queueReady = new AtomicLong();
    final AtomicBoolean flushQueued = new AtomicBoolean();
    volatile String consumerTag;
    ResortBatcher<Delivery> batcher;
    int pendingAckCount;
    long lastDeliveryTag;
    Partition(Channel consume, Channel publish) { this.consume = consume; this.publish = publish; }
  }
}
