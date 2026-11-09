# Assignment 2 ingest / WebSocket server

Java 11, Tomcat 9 (`javax.servlet`, `javax.websocket`). Build from the repository root:

```
mvn -pl server-v2 -am package
```

Deploy `server-v2/target/ROOT.war` as Tomcat's ROOT application. Existing Assignment 1
`server/` is retained independently. Remove Tomcat's default ROOT app before installing
the new WAR so `/health` is the actual ALB health path.

Endpoints:

- `POST /skiers/{resort}/seasons/{season}/days/{day}/skiers/{skier}`;
  body `{"time":217,"liftID":21}`, header `Idempotency-Key: <UUID>`.
  `X-Event-Id` is an alias; if both headers exist they must agree. Generate the UUID
  once per logical swipe and reuse it on every retry, including after timeouts or
  across ALB nodes. Legacy requests without a key get a generated UUID and therefore
  cannot deduplicate separate HTTP retries.
- `201` means a mandatory, persistent publish was routed and positively confirmed
  by RabbitMQ. It does not mean aggregation has completed. `503` means the outcome
  is unavailable or ambiguous; retry with the same key. Invalid requests return
  `400`; oversized bodies return `413`. All HTTP responses include `X-Server-Id`.
- `GET /health`: `200` only when producer and backplane connections are ready;
  `503` during startup/recovery. `GET /metrics` includes readiness, publish counters,
  current local subscriber/binding counts, and snapshot failures.
- WebSocket `/leaderboard/{resort}?dayID=1` streams authoritative snapshots;
  resort is 1–10 and day is 1–366. An initial empty version-0 snapshot is valid.

## Configuration

| Environment variable | Default | Purpose |
|---|---|---|
| `LIFT_VERTICAL_FILE` | bundled official JSON | Optional official JSON or CSV lift-table override, loaded through common `LiftCatalog` |
| `ALLOW_DEMO_LIFTS` | `false` | Explicitly allow the labelled local demonstration fixture |
| `SERVER_ID` | generated UUID-based name | Instance identity in events, responses, and metrics |
| `RABBITMQ_HOST` | `localhost` | Broker host |
| `RABBITMQ_PORT` | `5672` | Broker AMQP port |
| `RABBITMQ_USER` | `skitrak` | Broker user |
| `RABBITMQ_PASSWORD` | `skitrak-local-only` | Local-only default; configure for deployment |
| `RABBITMQ_VHOST` | `/` | Broker virtual host |
| `CHANNEL_POOL_SIZE` | `32` | Pre-created producer channels on one producer connection |
| `CHANNEL_BORROW_TIMEOUT_MS` | `1000` | Bounded request wait for a free producer channel |
| `PUBLISH_CONFIRM_TIMEOUT_MS` | `3000` | Broker confirm deadline |
| `CONSUMER_URL` | `http://localhost:8081` | Authoritative snapshot HTTP service |
| `WS_SEND_QUEUE_SIZE` | `16` | Pending snapshots per session, in addition to one in-flight send |
| `SNAPSHOT_RESYNC_INTERVAL_MS` | `5000` | Authoritative refresh for active subscriptions; repairs missed ephemeral updates |
| `QUEUE_TTL_MS` | `86400000` | Shared durable swipe-queue TTL |
| `QUEUE_MAX_LENGTH` | `100000` | Shared raw-queue limit; overflow rejects publishing |

Production uses the supplied official lift table bundled in `common`; an explicit
`LIFT_VERTICAL_FILE` overrides it and is validated for all 40 lifts. Demo mode is
visible as `demoLifts:true` in health and accepted-swipe responses. The current
repository does not ship a demo fixture, so `ALLOW_DEMO_LIFTS=true` fails fast. Use
the official defaults for local checks and submitted benchmarks.

## Threading and failure behavior

A producer connection owns a fixed bounded channel pool. A request leases one
channel exclusively for publish+confirm, then returns it. No channel is created on
the normal publish path. Connection/channel failure invalidates that entire pool
generation; one recovery scheduler reconnects and pre-creates its replacement.
Borrowed stale channels never re-enter the new generation. Automatic RabbitMQ
recovery is disabled so it cannot compete with application recovery.

The WebSocket backplane has a separate connection and one broker-generated,
exclusive, auto-delete queue per server. Its durable topic exchange is
`leaderboard.topic`. Only resorts with local sessions are bound, using exact
routing keys `lb.1` … `lb.10`; the last unsubscribe removes that resort binding.
Binding, unbinding, registry updates and message acknowledgements run serially.
Manual acknowledgements and prefetch 100 bound the backplane delivery handoff.

On first subscription, binding completes before the authoritative HTTP snapshot
request begins. Backplane updates and bootstrap results reconcile by monotonically
increasing `(resort,day)` versions; stale responses cannot roll the display back.
Requests belonging to a removed subscription are invalidated before any later
re-subscription. Reconnect creates a fresh exclusive queue, restores current
bindings and refreshes snapshots. Periodic snapshots repair transient missed
backplane updates even when no later swipe changes the leaderboard.

Each WebSocket session has one asynchronous send in flight and a bounded pending
queue. A slow or failed session closes with code 1013 so its reconnect obtains a
fresh snapshot. It cannot stall the aggregation consumer. Shutdown stops recovery,
backplane and HTTP snapshot tasks and closes AMQP connections.

## Verification

The module's tests exercise exclusive channel leases and bounded borrow timeout,
connection-driven recovery, stale generation rejection, mandatory-return handling,
confirm-gated success, HTTP validation and stable retry identity, first/last binding
transitions, bootstrap version reconciliation, initial empty snapshots, serial async
sends and slow-session queue bounds. End-to-end broker/Tomcat tests are in deployment.
