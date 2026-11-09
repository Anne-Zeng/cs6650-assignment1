# SkiTrak Assignment 2 architecture

Defaults: Java 11 / Tomcat 9, 10 resorts, Top-N 20. Instructor clarification uses
consumer sweeps 5/10/15/20; values beyond ten useful lanes are expected to plateau.
The official lift mapping is bundled and may be overridden by a validated file.

## Deployment and queue topology

```mermaid
flowchart LR
  C[Load client] --> ALB[AWS ALB]
  ALB --> I[Ingest + WebSocket EC2 x 1/2/4]
  I --> X[skitrak.exchange / topic]
  X --> Q[resort.1 ... resort.10]
  Q --> A[Separate consumer EC2 / ordered resort lanes]
  A --> DB[(Per-resort SQLite / persistent EBS)]
  A --> T["leaderboard.topic / lb.{resortID}"]
  T --> W[One private queue per WebSocket server]
  W --> I
  I --> S[Local WebSocket subscribers]
```

RabbitMQ runs on a dedicated EC2. Durable raw queues bind to `resort.N` and use
TTL 24h, maximum length 100000 and reject-publish overflow by default. The measured
operating target remains depth <1000; capacity/expiration is not a tuning shortcut.
Expired events reach `resort.N.dead` and require investigation. Each raw queue has
a single active consumer; replicas of the broker or consumer are not claimed.

Leaderboard updates use a durable topic exchange and exact routing keys `lb.1`
through `lb.10`. Each WebSocket node owns one exclusive generated queue, binding
only resorts with local subscribers. Notifications are transient complete
snapshots; the durable consumer store is the authoritative bootstrap source.

## Swipe to leaderboard sequence

```mermaid
sequenceDiagram
  participant C as Client
  participant I as Ingest
  participant R as RabbitMQ
  participant A as Consumer
  participant D as SQLite
  participant W as WebSocket node
  C->>I: POST + stable Idempotency-Key UUID
  I->>I: Validate and look up official vertical
  I->>R: Mandatory persistent publish via pooled channel
  R-->>I: Publisher confirm (and no return)
  I-->>C: 201 / eventId
  R->>A: Ordered delivery / manual ack
  A->>D: Atomic inbox + totals + snapshot + outbox
  D-->>A: Commit
  A->>R: Publish changed full snapshot / lb.N
  R-->>A: Confirm
  A->>D: Remove confirmed outbox row
  A->>R: Ack original swipe
  R->>W: Matching server queues receive snapshot
  W-->>C: Versioned real leaderboard
```

## Connection locality and concurrency

The topic backplane decouples aggregation ownership from socket ownership, so
ingest and WebSocket nodes scale horizontally. Co-location would reduce one hop
but couple routing, state ownership and node capacity. Topic bindings also avoid
shipping uninterested resorts to every node; they require subscription lifecycle
coordination and an authoritative snapshot endpoint.

On first subscription, the server installs the binding before querying the
snapshot. Live updates and bootstrap responses reconcile by persistent version
per resort/day. Last-unsubscribe, resubscribe and reconnect are serialized and
stale bootstrap responses are discarded. Periodic snapshots repair ephemeral
notification loss. Per-session bounded serial asynchronous sends isolate slow
clients. The backplane does not promise durable replay of every intermediate view.

One FIFO lane owns each resort. A configurable shared worker pool fairly schedules
the ten lanes; its core size may grow/shrink with queue/in-flight load. More than
ten configured threads cannot increase ordered aggregation parallelism. Prefetch
and contiguous per-channel ack batching are configurable; failures retry within
the same lane. Poison/conflicting events pause that partition rather than silently
skip events or reorder later swipes. Top-N uses all-skier totals plus a bounded
ordered set; ties use numeric skier ID and changed displayed scores trigger updates.

Dynamic scaling is on by default (`CONSUMER_DYNAMIC_SCALING=true`, 5 to 10 workers).
Each second the consumer sets the core size to the number of resorts with queued
work, bounded by the minimum and configured thread counts. In the 1M/512-client
run (4 ingests, 2026-11-08 11:03 to 11:09 UTC) the monitor shows the core size
moving between 5 and 10 as load began, 10 active workers with 82,154 ready messages
at 11:04:52, then 8, 6 and 5 at 11:08:45, 11:08:49 and 11:08:51 as the queue drained.
Controlled sweeps disable scaling to hold the worker count fixed. Lane ownership is
not fixed to a thread: any pool thread takes the next message of a free resort lane,
and one resort never has two threads at once.

## Backplane tradeoffs and the first-subscriber bind/snapshot race

A topic exchange routes `lb.{resortID}` only to WebSocket nodes with local subscribers for that resort. A fanout exchange would be simpler because every node receives every snapshot and filters locally, but it sends uninterested updates across the network and requires deserialization/filtering on every node. Topic routing trades that traffic reduction for binding/unbinding coordination, broker binding state and bootstrap/recovery logic. When every node subscribes to every resort, its filtering advantage largely disappears. One shared competing-consumer queue would not be sufficient: a snapshot could reach a node that does not own the interested sockets. Neither topic routing nor cookie stickiness transfers an existing WebSocket between nodes.

**First-subscriber race.** If the node fetches snapshot version 10 before binding and version 11 is committed and published between that fetch and binding, it can miss version 11 and show stale standings until a later update or resynchronization. The implementation instead completes `queueBind` before starting the HTTP snapshot request. Subscription changes, live deliveries and snapshot completions enter one lifecycle executor. The per-resort/day version check prevents an older snapshot response from overwriting a newer live update: if live version 12 arrives before HTTP version 11, version 11 is discarded; equal versions are also ignored. Generation and request tokens invalidate responses from disconnected connections or abandoned subscriptions. A failed bind marks the backplane unavailable, and reconnection restores active bindings before fetching snapshots again. Periodic resynchronization (default five seconds) repairs transient notification loss. This supports convergence to current standings; it does not guarantee replay of every intermediate version or instantaneous freshness during a disconnect.

Implementation: `server-v2/src/main/java/skitrak/server/LeaderboardHub.java` (`join`, `bindResort`, `fetchSnapshot`, `connect`) and `LeaderboardSubscriptions.java` (`accept`, `deliver`).

## Self-operated RabbitMQ versus a managed queue

RabbitMQ gives this assignment explicit control over exchanges, bindings, per-resort queues, prefetch, manual acknowledgements and publisher confirms. The deployment also owns broker operation, resource provisioning, connection lifecycle, exclusive channel leases and failure recovery. Our producer keeps one long-lived connection per ingest with a bounded channel pool; a channel remains exclusively leased through confirmation. Automatic RabbitMQ client recovery is deliberately disabled in `RabbitConfig`, and `ChannelPool` owns generation replacement so stale leases cannot reenter a recovered pool. RabbitMQ clients can support automatic recovery; implementing recovery ourselves is a project choice, not an inherent inability of the library.

A managed queue such as SQS shifts broker infrastructure operation to the provider, while the AWS SDK for Java 2.x supplies thread-safe service clients with internal HTTP connection pools ([AWS documentation](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/singleton-service-clients.html)). It would remove our AMQP channel-pool implementation, while application ordering, deduplication, subscriber delivery and retry semantics still require design. It also changes the messaging API and topology rather than directly replacing the topic exchange unchanged. We retain RabbitMQ to implement and measure the connection/channel/reconnection behavior required here. This illustrates the same responsibility-versus-control decision as operating an application on EC2 versus delegating more runtime operation to Lambda; those services do not have identical execution models.

## Failure handling, ALB and verification

Each producer reuses one connection and a pre-created bounded Channel pool. A
lease is exclusive through publisher confirm. One recovery owner rebuilds the
entire pool after failure; stale leases cannot enter its replacement. Uncertain
publishes return 503 and are retried with the same eventId. Consumer inbox dedupe
and outbox persist across restart on the same EBS volume. Repeated notifications
are harmless because WebSocket nodes discard older/equal versions. No subscribed
queue is a valid notification outcome, not a failed raw swipe.

ALB uses round robin, cookie stickiness, /health every 30s, timeout 5s, healthy=2,
unhealthy=3 and idle timeout 120s. ROOT WAR makes /health directly reachable.
Upgraded WebSocket connections stay on their accepted target; failed sockets
reconnect and bootstrap on another healthy node. systemd/Docker restart failed
processes: on 2026-11-08 the consumer container was killed with `docker kill`
(exit 137, 23:54:04 UTC); systemd restarted it after its 5 s delay and `/health`
returned 200 by 23:54:11, about 7 s in all, with the processed count unchanged at
30,692,311. Broker, consumer and management traffic stays on private networks or
SSH tunnels; real credentials stay in ignored env files.

Validation separates HTTP acceptance from pipeline completion and verifies final
business totals/Top-N, repeated IDs, cross-node sockets, restart and reconnect.
Cloud runs: 500K at 1/2/4 ingest nodes plus 1M at four; record client latency and
throughput, queue depth/rates, lag, host resources and ALB distribution. Synchronize
clocks before interpreting cross-host latency. SQLite FULL durability and
confirmed ordered broadcasts are explicit throughput tradeoffs to measure. Final
measured configuration uses STORE_BATCH_SIZE=64, a10ms partial-batch flush
trigger, PREFETCH_COUNT=128, ACK_BATCH_SIZE=16, and CHANNEL_POOL_SIZE=64.
Snapshots are rewritten only for changed Top-N standings; every changed version
is retained for ordered publication. Controlled consumer sweeps disable automatic
scaling. Four-ingest500K/128-client/20-worker measurements show a sampled raw
queue total peak253 and mean lag25.61ms; four-ingest1M/512-client stress misses
queue and lag targets. Detailed evidence and limitations are in
`output/assignment2-report.md`. Persistent-disk
loss, TTL expiration and multi-consumer-host HA require additional design; this
implementation does not claim to solve those failures.
