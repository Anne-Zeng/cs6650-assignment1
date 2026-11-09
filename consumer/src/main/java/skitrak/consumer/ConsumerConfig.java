package skitrak.consumer;

import java.nio.file.Path;
import skitrak.common.Env;

final class ConsumerConfig {
  final int threads = Env.integer("CONSUMER_THREADS", 10, 1, 80);
  final int minThreads = Env.integer("CONSUMER_MIN_THREADS", Math.min(5, threads), 1, Math.min(10, threads));
  final boolean dynamicScaling = Env.bool("CONSUMER_DYNAMIC_SCALING", true);
  final int prefetch = Env.integer("PREFETCH_COUNT", 50, 1, 10000);
  final int storeBatchSize = Env.integer("STORE_BATCH_SIZE", Math.min(32, prefetch), 1, prefetch);
  final int storeBatchWaitMs = Env.integer("STORE_BATCH_WAIT_MS", 10, 1, 100);
  final int ackBatchSize = Env.integer("ACK_BATCH_SIZE", 1, 1, prefetch);
  final int ackFlushMs = Env.integer("ACK_FLUSH_MS", 25, 1, 1000);
  final int retryAttempts = Env.integer("RETRY_ATTEMPTS", 5, 1, 100);
  final int publishConfirmMs = Env.integer("PUBLISH_CONFIRM_TIMEOUT_MS", 5000, 100, 60000);
  final int topN = Env.integer("TOP_N", 20, 1, 1000);
  final int port = Env.integer("CONSUMER_PORT", 8081, 1, 65535);
  final Path storeDir;
  final int shutdownMs = Env.integer("CONSUMER_SHUTDOWN_MS", 15000, 1000, 60000);

  ConsumerConfig() { this(Path.of(Env.get("STORE_DIR", "data/consumer"))); }
  ConsumerConfig(Path storeDir) { this.storeDir = storeDir; }
}
