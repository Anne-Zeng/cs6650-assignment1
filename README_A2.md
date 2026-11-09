# SkiTrak — Assignment 2

SkiTrak accepts skier swipes through Tomcat ingest servers, processes them asynchronously through RabbitMQ, and delivers real Top-N leaderboards over WebSockets. This repository contains the implementation, deployment tools, correctness checks, EC2 measurements, and tuning evidence for Assignment 2.

## Pipeline and design

```text
Client → ALB → Ingest servers → RabbitMQ resort queues → Consumer → SQLite
                     ↑                                    |
                     └── Topic backplane ← changed Top-N ─┘
                     |
                WebSocket subscribers
```

- **Ingestion:** validates requests and resolves vertical distance using the official 40-lift table. Each logical swipe retains its `Idempotency-Key` across retries. A successful HTTP 201 follows a confirmed persistent publish with no mandatory return.
- **Ordered aggregation:** ten queues (`resort.1` through `resort.10`) feed ten serial resort lanes scheduled by a shared Consumer worker pool. One resort never runs concurrently on two workers.
- **Durable state:** per-resort SQLite databases use WAL and `synchronous=FULL`. Transactions persist deduplication records, skier totals, changed snapshots, and an outbox. Raw messages are acknowledged after the required leaderboard publication is confirmed.
- **Connection locality:** `leaderboard.topic` routes full snapshots using `lb.{resortID}`. Each WebSocket server owns an exclusive queue and binds only resorts with local subscribers. The first subscription binds before fetching an authoritative snapshot; version checks reject stale bootstrap responses and duplicate updates.
- **Recovery:** the producer reuses a long-lived connection and an exclusive, bounded channel pool. Application-owned recovery replaces failed pool generations. WebSocket nodes restore subscriptions and resynchronize snapshots after backplane failures.

Topic routing reduces irrelevant traffic compared with fanout, at the cost of subscription lifecycle coordination and snapshot recovery. Self-operated RabbitMQ also makes connection/channel management and broker operation part of the implementation. The architecture document explains these tradeoffs, including the first-subscriber bind/snapshot race and the comparison with a managed queue.

## Repository layout

| Directory | Purpose |
|---|---|
| `common/` | Message models, official lift lookup, configuration, and queue topology |
| `server-v2/` | Tomcat ROOT WAR, swipe validation, confirmed publishing, channel pool, and WebSocket backplane |
| `consumer/` | Ordered resort lanes, durable aggregation/deduplication/outbox, Top-N calculation, and snapshot/metrics APIs |
| `client-v2/` | Concurrent load generation, stable retry IDs, HTTP metrics, WebSocket verification, and pipeline-drain checks |
| `deployment/` | Docker Compose, EC2 role installation, systemd services, and ALB configuration |
| `monitoring/` | Correctness/recovery checks, EC2 monitoring, benchmark orchestration, console capture, and evidence generation |
| `results/` | Original validation and benchmark records |
| `output/` | Reports, PDFs, plots, and archived screenshots |
| `transcripts/` | Assignment AI-use transcripts |

The root Maven reactor builds the Assignment 2 modules. Assignment 1 source and historical measurements remain in their existing directories.

## Build and run locally

Requirements: Java 11, Maven, and a running Docker engine. Docker supplies Tomcat 9, RabbitMQ, and the Consumer; separate EC2 instances are not needed for local verification.

Run from the repository root:

```bash
./deployment/build.sh

# Preserve an existing environment file.
test -f deployment/.env || cp deployment/.env.example deployment/.env
# Set your local RabbitMQ credentials in deployment/.env before starting.
./deployment/local-up.sh
```

On macOS with Corretto 11 installed, select it before building if necessary:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

The normal stack uses the official lift table. Synthetic demo values are reserved for explicitly labeled fixture tests.

| Endpoint | Purpose |
|---|---|
| `http://localhost:8080/health` | Ingest 1 readiness |
| `http://localhost:8082/health` | Ingest 2 readiness |
| `http://localhost:8081/health` | Consumer readiness |
| `http://localhost:8081/metrics` | Processing, queue, worker, lag, and outbox metrics |
| `http://localhost:15672` | RabbitMQ Management; credentials come from the local environment file |
| `ws://localhost:8080/leaderboard/1?dayID=1` | Resort 1 leaderboard subscription |

The WAR is deployed at the root context, so the health path is `/health`.

### Verify business correctness

```bash
python3 monitoring/verify_pipeline.py \
  --ingest-url http://localhost:8080 \
  --ingest-url http://localhost:8082 \
  --consumer-url http://localhost:8081 \
  --lift-file deployment/config/lift_vertical.json \
  --day 363
```

Choose an unused day for each independent verification. The verifier sends 100 unique swipes and 20 duplicate requests across nodes, independently calculates expected standings, and checks authoritative snapshots and real WebSocket results.

### Run the load client

```bash
java -jar client-v2/target/client-v2.jar \
  http://localhost:8080 500000 128 1000 10 buffered 6650 \
  http://localhost:8081 600
```

Arguments: ingest URL, total events, main client threads, warmup events per thread, resort count, metrics mode, seed, Consumer URL, and drain timeout in seconds. Total events include warmup.

Run one benchmark at a time. The client distinguishes HTTP ingestion throughput from end-to-end throughput, waits for durable processing and drain, and verifies final HTTP/WebSocket leaderboard content. A local run is functional evidence; the submitted scaling measurements come from EC2.

## EC2 deployment and experiments

The measured deployment uses four `t3.micro` ingest instances, a separate `t3.medium` RabbitMQ instance, and a separate `t3.medium` Consumer with persistent EBS. The Consumer volume was increased from 3,000 to 6,000 provisioned IOPS during earlier tuning. An internet-facing ALB spans two Availability Zones and forwards to ingest port 8080. Broker and Consumer communication uses private addresses; management access uses SSH tunnels.

ALB settings include a 120-second idle timeout, target cookie stickiness with a 300-second duration, and `/health` checks. Existing upgraded WebSocket connections stay on their accepting server; recovery requires reconnection and snapshot bootstrap.

Deployment commands and security-group requirements are documented in `deployment/README.md`. Monitoring prerequisites and experiment commands are documented in `monitoring/README.md`. After an EC2 stop/start, update the public addresses in `monitoring/ec2_monitor.py`; retain verified SSH host-key aliases. Configure the intended ALB targets and confirm actual routing before a benchmark.

| Experiment | Evidence collected |
|---|---|
| 1 / 2 / 4 ingests, 500K events, 64 / 128 / 256 / 512 client threads | Runtime, throughput, HTTP latency/failures/retries, queue depth/rates, host resources, and request-counter distribution |
| 4 ingests, 1M events, 512 client threads | Stress behavior, drain time, backlog, lag, and resource pressure |
| Consumer 5 / 10 / 15 / 20 workers | Instructor-clarified sweep, plus a retained repeat |
| Consumer 10 / 20 / 40 / 80 workers | Supplementary forward/reverse sweep of the original handout values |
| Prefetch 64 / 128 / 256 / 512; ACK batch 1 / 16; channel pool 32 / 64 | Tuning comparisons, original logs, sampled metrics, and available console screenshots |
| Functional and recovery checks | Official vertical values, duplicate replay, cross-node leaderboard delivery, restart behavior, ALB distribution/stickiness, and WebSocket failover |

The primary fresh session (`20261108T135433Z`) contains 17 runs totaling 9,000,000 processed events, zero final failed requests, zero additional duplicates, and 269 application retries. Supplementary tuning and repeat sessions are identified separately rather than merged into that total. Dates and chart windows are UTC.

## Findings and evidence limits

- **Ingestion and completion scale differently.** In the primary session, four ingests at 512 client threads increased main HTTP throughput by 25.8% over one ingest, while end-to-end throughput fell by 5.16%. Additional admitted work accumulated downstream.
- **The earliest measured constraint was the Consumer durable-write path.** Batching SQLite commits and subsequently increasing EBS IOPS were followed by substantial backlog/drain improvements. Later storage waiting and broker CPU pressure support downstream constraints, without proving one exclusive bottleneck in every run.
- **Ten resort lanes limit aggregation parallelism.** Configuring more than ten workers did not establish a reliable throughput advantage. Sequential runs retain changing database/cache state, so differences are descriptive observations.
- **Pool tuning has mixed results.** The latest two-ingest/512-client pool 32 → 64 pair recorded 2,559 → 3,098/s end-to-end, but retries increased from 0 to 58 and both settings had substantial backlog. It does not establish that pool 64 consistently eliminates retries or improves tail latency.
- **Overload targets were not met universally.** Several lower-load observations met sampled Ready depth below 1,000 and average lag below 100 ms. Higher-load runs and some 128-client worker observations did not. The primary 1M stress run recorded Ready peak 377,327 and mean lag about 49.4 seconds.
- **Monitoring gaps remain disclosed.** The primary 1M ingest counters cover only 3,716 requests, so they cannot establish full-run request distribution. The latest pool repeat contains Consumer monitoring timeouts. Sampled peaks and average lag do not prove per-event bounds.
- **Console scope matters.** ALB charts must use the correct UTC window and metric dimensions. Available original captures are linked with their runs; the latest pool repeat's ALB captures are collected separately. Generated plots are labeled separately from actual console screenshots.
- **Recovery has boundaries.** The recorded live WebSocket failover demonstrates Python-harness-managed reconnection, not proof of automatic reconnection by the Java load client. Same-volume Consumer restart is supported; independent multi-host Consumer HA, permanent disk loss, and replay of every transient leaderboard version are not claimed.

Retained tuning configuration: prefetch 128, ten fixed Consumer workers for controlled comparisons, ACK batch 16, channel pool 64, and database batches of at most 64 with a 10 ms partial-batch flush trigger. SQLite FULL durability remains enabled. Dynamic scaling is disabled during fixed-worker comparisons.

## Documentation and submission evidence

The links below point to the `improve-metrics-buffering` branch and group the supporting material in reading order.

- [Requirements and verification matrix](https://github.com/Anne-Zeng/cs6650-assignment1/blob/improve-metrics-buffering/REQUIREMENTS_A2.md)
- [Local verification results](https://github.com/Anne-Zeng/cs6650-assignment1/blob/improve-metrics-buffering/results/a2-local/README.md)
- [Two-page architecture PDF](https://github.com/Anne-Zeng/cs6650-assignment1/blob/improve-metrics-buffering/output/pdf/assignment2-architecture.pdf)
- [Architecture Markdown source](https://github.com/Anne-Zeng/cs6650-assignment1/blob/improve-metrics-buffering/ARCHITECTURE_A2.md)
- [Tuning parameters and supplementary experiments](https://github.com/Anne-Zeng/cs6650-assignment1/blob/improve-metrics-buffering/TUNING_PARAMETERS.md)
- [Fresh EC2 test report](https://github.com/Anne-Zeng/cs6650-assignment1/blob/improve-metrics-buffering/output/assignment2-fresh-20261108T135433Z/report.md)
- [Assignment 2 AI-use transcript](https://github.com/Anne-Zeng/cs6650-assignment1/blob/improve-metrics-buffering/transcripts/assignment2_ai_usage.md)
- [AI usage summary](https://github.com/Anne-Zeng/cs6650-assignment1/blob/improve-metrics-buffering/AI_USAGE.md)
