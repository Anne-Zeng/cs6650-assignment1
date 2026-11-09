# Assignment 2 consumer

This standalone Java 11 application consumes `resort.1` through `resort.10`, aggregates vertical feet, and publishes complete top-20 snapshots to the topic exchange `leaderboard.topic` with routing keys `lb.1` through `lb.10`. Every WebSocket server has its own queue and binds only resorts with local subscribers. A publish with no matching binding is normal; the snapshot API remains the source for subscription bootstrap.

Build from the repository root with `mvn -pl consumer -am package`, then run `java -jar consumer/target/consumer.jar`. The repository deployment scripts provide the broker environment and a persistent data volume. Keep port 8081 accessible only to ingest servers and monitoring hosts on the private network.

## Processing and recovery

Each resort has one active RabbitMQ subscription, a dedicated consumption channel, a dedicated confirmation channel, and one FIFO application lane. Lanes share a resizable executor and yield between events so five workers fairly service all ten resorts. Different resorts run concurrently; one resort never has overlapping aggregation. Configuring 15 or 20 workers does not create more than ten useful aggregation lanes.

A resort coalesces up to32 deliveries into one ordered SQLite transaction, flushing a partial batch after roughly10ms. Each event still inserts its own inbox identity and updates the skier's day total. The stored snapshot/version and outbox are written only when top-N membership or displayed scores change. The timeout schedules a flush; it does not bound end-to-end latency under overload. Only business fields enter the identity fingerprint: a retry through a different ingest host may have a different server timestamp and server ID. Reusing an identity with different business fields is rejected. The in-memory score index is changed after the database commit, and is rebuilt from durable totals on restart.

The resort lane publishes pending outbox rows in order, waits for confirmation of the complete published group, deletes only the confirmed prefix, then acknowledges the source deliveries. Every changed leaderboard still produces a snapshot; batching does not merge those changes into one notification. A crash after publishing and before deleting can duplicate a snapshot; subscribers must ignore versions they already accepted. A crash after a database commit and before ACK redelivers a swipe without double counting. Confirming a notification does not prove a WebSocket client received it; new subscriptions and backplane reconnections must bootstrap from the latest snapshot. Notification messages are transient; the authoritative snapshot and outbox are durable.

The application explicitly reconnects the two long-lived AMQP connections, rebuilds its channels/subscriptions, and retries pending outbox rows. Application shutdown cancels intake, drains bounded work, flushes completed ACKs, aborts remaining connections, and releases store locks. SQLite uses WAL and `synchronous=FULL`.

## HTTP API

All endpoints accept GET. Responses are JSON, and `/metrics` remains available while broker connectivity is degraded.

| Endpoint | Response |
| --- | --- |
| `/health` | 200 when both AMQP connections and all resort partitions are healthy and restored outboxes finished startup replay; otherwise 503. |
| `/leaderboards/1?dayID=1` | Full snapshot: `resortID`, `dayID`, `version`, `processed`, `serverTimestamp`, `top` containing `rank`, `skierID`, `vertical`. An untouched day returns version 0 and an empty top array. |
| `/metrics` | Durable cumulative `processed` / `swipesProcessed`, runtime duplicates/retries, `pendingOutbox`, AMQP `inFlight`, sampled `queueReady`, sample time `queueMetricsUpdatedAt` (epoch milliseconds), `queueMetricsAgeMs` (-1 until first sample), configured/core/active thread counts, and per-resort counters/errors. |
| `/drain` | Same metrics with a sampled `drained` indicator; 200 when healthy with a depth sample at most three seconds old, no ready messages, inflight deliveries or pending outbox, otherwise 202. Stop producers and also verify an expected durable processed count before declaring a benchmark complete. |

`processed` survives restarts; use a before/after baseline for each run. `duplicates`, failed attempts, lag histograms and reconnection counts restart at zero. Consumer lag measures the signed wall-clock interval from ingest receipt to completed aggregation. Negative samples are exposed rather than clamped; p95/p99 use upper bounds of fixed histogram buckets and exclude negative samples. Synchronize host clocks before interpreting the 100 ms target. Queue-ready counts are sampled every second, so they do not replace business reconciliation.

## Configuration

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `CONSUMER_THREADS` | `10` | Configured executor cap, 1–80; instructor sweep: 5, 10, 15, 20. |
| `CONSUMER_MIN_THREADS` | `min(5, cap)` | Lower bound for dynamic worker scaling, capped at ten. |
| `CONSUMER_DYNAMIC_SCALING` | `true` | Resize the core pool using ready/inflight resort work; no same-resort parallelism. |
| `PREFETCH_COUNT` | `50` | Maximum unacknowledged deliveries per resort. |
| `ACK_BATCH_SIZE` | `1` | Contiguous completed deliveries per channel ACK, at most prefetch. |
| `ACK_FLUSH_MS` | `25` | Flush partially completed ACK batches. |
| `STORE_BATCH_SIZE` | `min(32,prefetch)` | Maximum events per durable transaction; use1 for the earlier behavior. |
| `STORE_BATCH_WAIT_MS` | `10` | Partial-batch flush scheduling delay,1..100ms. |
| `TOP_N` | `20` | Number of leaderboard entries. Keep this fixed for an existing store directory. |
| `RETRY_ATTEMPTS` | `5` | In-place transient attempts before reconnect/redelivery. |
| `PUBLISH_CONFIRM_TIMEOUT_MS` | `5000` | Maximum notification confirmation wait. |
| `CONSUMER_PORT` | `8081` | Private HTTP listener. |
| `STORE_DIR` | `data/consumer` | Persistent SQLite and ownership-lock directory. |
| `CONSUMER_SHUTDOWN_MS` | `15000` | Graceful shutdown deadline. |
| `RABBITMQ_HOST`, `RABBITMQ_PORT` | `localhost`, `5672` | Broker address. |
| `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | Local development credentials from shared configuration | Supply deployment credentials through environment/secrets. |
| `RABBITMQ_VHOST` | `/` | RabbitMQ virtual host. |

Shared topology configuration also reads `QUEUE_TTL_MS` and `QUEUE_MAX_LENGTH`. TTL and limits are broker safeguards, not evidence of loss-free processing; monitor dead queues and reject counts. The default raw-queue overflow rejects new publications instead of discarding old accepted swipes.

## Partition errors and storage boundaries

A malformed message or conflicting event identity pauses that resort without ACKing it or applying later swipes. `/health` becomes 503 and `/metrics.perResort[resort].error` identifies the cause. Other resorts continue. This deliberately preserves order instead of silently skipping a poison message. Inspect the error and the producer first; stop the consumer, retain a backup, and use a controlled single-resort recovery consumer to inspect/quarantine the offending delivery to its dead queue before restarting. Reconcile and replay quarantined data after correction. Do not purge raw queues or delete inbox rows to make health green.

Transient database/publish failures retry in-place with bounded exponential delays. If the attempt budget is exhausted, connections are replaced and unacknowledged deliveries are replayed. This can pause all partitions briefly but does not run two writers for one resort.

There is one OS ownership lock per resort. This application owns all ten local partitions; a second process must not open the same files. Moving a resort to another EC2 host requires stopping the old owner, transferring its complete consistent SQLite state or reattaching the persistent volume, then starting the new owner. RabbitMQ single-active-consumer alone does not move totals, dedupe state, or outbox ownership.

Use a persistent EBS-backed volume and backups for `STORE_DIR`. Restarting the same instance or process preserves state; losing that volume loses already-ACKed totals and inbox history because RabbitMQ has correctly removed those messages. The single broker and single consumer host remain availability limits of this assignment deployment. FULL synchronization and serial notification confirms favor clear recovery semantics; measure their throughput before claiming the load-test performance target.

Run the consumer tests with `mvn -pl consumer -am test`. They verify restart persistence, dedupe/conflicts, transactional pending outbox recovery, ranking and day isolation, single-owner locks, fair ordered execution with fewer workers than resorts, and shutdown during a blocked connection attempt.

Batch rollback invalidates the in-memory day cache and restores its processed counter; the next read rebuilds from the rolled-back database. A conflicting event inside a batch rolls back the whole batch and pauses the resort. Restart counts are rebuilt from inbox rows even when no snapshot rewrite was needed. SQLite FULL durability remains enabled.
