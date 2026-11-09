# Assignment 2 requirement-to-evidence map

Updated after the cloud runs of 2026-11-08 and 2026-11-09. It incorporates the
instructor-endorsed clarification supplied in the chat: 10 resorts, default Top-N 20,
consumer sweep 5/10/15/20, freely chosen documented EC2 types, and the supplied
official lift table. The original handout lists consumer settings 10/20/40/80; those
were also run, as a supplementary test. Status values: **Met** (implemented and
evidenced), **Met with limit** (evidenced, limit stated), **Open** (not done).

Main evidence: [test report](output/assignment2-fresh-20261108T135433Z/report.md),
[tuning report](TUNING_PARAMETERS.md), [architecture](ARCHITECTURE_A2.md),
[local verification](results/a2-local/README.md).

## Implementation and behaviour

| Requirement | Implementation | Status and evidence |
|---|---|---|
| Required swipe JSON and official vertical lookup | `common/SwipeMessage`, `LiftCatalog`, bundled official JSON; `server-v2/SwipeValidator` | Met. Shared contract tests; the EC2 pipeline verification ran with the official table (`demoLifts` false, `results/a2-fresh/20261108T135433Z/verification/pipeline.json`) |
| RabbitMQ topic `skitrak.exchange`, `resort.N` routes, 10 queues | `common/Topology` | Met. Topology recorded from the broker in the test report (10 raw queues, 10 bindings, three exchanges) |
| TTL and queue limits | 24 h TTL, `x-max-length` 100,000 per queue, `reject-publish`, per-resort dead-letter queues | Met with limit. Per-queue peak in the 1M run was about 43K, so reject-publish was never triggered on EC2; that path is covered by a unit test only |
| One connection and a reusable bounded Channel pool, one thread per Channel at a time | `server-v2/ChannelPool`, exclusive generation-aware leases | Met. Pool exclusivity, timeout and stale-lease tests; broker-down returns 503 on EC2 (`ec2-recovery.json`) |
| Reconnection and failure behaviour | Application-owned reconnect loops; publisher confirms with mandatory publish; bounded waits | Met with limit. Broker restart recovery verified; no circuit breaker is implemented (optional), requests fail fast while reconnecting |
| Independent consumer process | `consumer/ConsumerMain`, shaded jar, own EC2 | Met. Separate consumer EC2 under systemd with Docker |
| Configurable, fair consumer pool and dynamic scaling | FIFO serial resort lanes, fair yielding, resizable executor, scaling on by default | Met. Core size moved between 5 and 10 in the 1M run (see architecture); sweeps use fixed workers |
| Per-skier totals and Top-N | Durable totals plus an incremental bounded ordered set; numeric ties | Met. Standings match independently computed totals through four ingests (`pipeline.json`) |
| Connection locality / backplane | `leaderboard.topic` keys `lb.N`, one private queue per WebSocket node, dynamic local-interest bindings | Met. Cross-node subscriptions and a WebSocket failover from `ingest-4` to `ingest-1` in 7.2 s (`websocket-failover.json`, `live-websocket-failover.json`) |
| Real leaderboard on change | Complete versioned snapshots, bootstrap and periodic refresh | Met. In all 17 fresh runs the final snapshots' `processed` total rose by exactly the events sent; 903 to 1,023 updates per run, 0 reconnections (test report) |
| At-least-once delivery and manual ACK | ACK after committed aggregation and confirmed notification | Met. Consumer stopped with queued backlog and restarted: 23 events verified (`ec2-recovery.json`) |
| Idempotence | Stable client UUID, per-resort durable inbox | Met. 20 repeated requests across ingests did not change totals; 0 additional duplicates over 9,000,000 events |
| Per-resort order | One active subscription and one FIFO lane per queue, in-place retry | Met by design and serial-lane tests; not separately measured on EC2 |
| Failed-delivery retry | Bounded in-place retry, reconnect and redelivery, outbox replay | Met. 269 application retries across the fresh runs, all succeeded, 0 failed requests |
| Health checks and auto-restart | `/health`, Docker restart, EC2 systemd | Met. Consumer container killed with `docker kill`: restarted in about 7 s with the processed count unchanged (`results/a2-ec2/consumer-kill-restart-20261108T2354Z.txt`); the test was run with the pipeline idle |
| ALB target group, stickiness, literal `/health`, 120 s idle | `deployment/cloudformation/alb.yaml`, ROOT WAR | Met. Console captures show stickiness (5 min cookie), health check (30 s, 5 s, 2/3) and idle timeout 120 s; 1/2/4 healthy targets; distribution probe 51/49/50/50 with sticky pass (`alb.json`) |

## Experiments and tuning

| Requirement | Implementation | Status and evidence |
|---|---|---|
| Tune prefetch, consumer concurrency, ACK batch size, connection pool size | Environment configuration; contiguous ACK high-water marks; `monitoring/set_consumer_tuning.py`, `set_consumer_threads.py`, `deployment/set-ingest-pool.sh` | Met. Controlled comparisons in [TUNING_PARAMETERS.md](TUNING_PARAMETERS.md). The largest effects came from batched DB writes and EBS IOPS, not from these four settings |
| Client 64/128/256/512 threads | `monitoring/run_instrumented.py`, `run_fresh_phase.py` | Met. Every level at 1, 2 and 4 ingests |
| Consumer 5/10/15/20 workers | `set_consumer_threads.py` | Met with limit. One original and one repeat observation each; the repeat did not reproduce the original ordering and cache state drifted (test report, repeat comparison) |
| Consumer 10/20/40/80 workers (original handout) | same | Met with limit. Forward then reverse, 8 runs: no measurable throughput change above ten workers, active workers never above 10; first run was cold-cache ([TUNING_PARAMETERS.md](TUNING_PARAMETERS.md) section 2) |
| 1/2/4 ingest at 500K, and 4 ingests at 1M | ALB and deployment scripts | Met. 1M run: 3,323/s end-to-end, Ready peak 377,327, mean lag 49.4 s, 77 retries, 0 failed |
| Queue depth <1000, lag <100 ms, no loss under load | Monitoring, lag histograms, raw queues, count reconciliation | Met with limit. Both targets hold at up to 128 client threads; they do not hold at 256, 512 or in the 1M stress run. No loss: processed delta equals events sent, failed 0, duplicates 0 in every run |
| Client, queue, system and ALB metrics | Collector CSV/JSONL, run evidence pages, CloudWatch captures | Met. Per run, plus RabbitMQ console screenshots; ALB charts are whole-load-balancer, per-target distribution comes from ingest counters. The 1M ingest counter delta covers only 3,716 requests and is not used for a balance claim |
| Performance improvement analysis | Test report section, tuning report | Met. Scaling is limited downstream of ingest (consumer storage and RabbitMQ CPU); gains from batching (about +26% at 500K) and EBS 3,000 to 6,000 IOPS (+31%) |

## Deliverables

| Requirement | Evidence | Status |
|---|---|---|
| Two-page architecture incl. diagrams | `ARCHITECTURE_A2.md`; `output/pdf/assignment2-architecture.pdf` | **Open.** The PDF was generated before the latest markdown edits; regenerate with `tools/render_a2_architecture.py` and confirm it is still two pages |
| Test results PDF | `output/assignment2-fresh-20261108T135433Z/test-report.pdf` | Met. Rendered from `report.md` on 2026-11-09; the earlier layout is kept as `test-report.previous.pdf` |
| AI usage reflection | `AI_USAGE.md` | **Open.** A factual draft; the student must review and add their own reflection |
| Git URL and publication | `origin` is `https://github.com/Anne-Zeng/cs6650-assignment1.git`, branch `improve-metrics-buffering` | Open. At the last check the branch matched `origin`; later documentation edits are not committed or pushed |
| Screenshots | RabbitMQ console and ALB captures per run in the evidence folders, `output/tuning-parameters-assets/` | Met. Superseded zone-scoped ALB captures are kept on disk but not linked |

Detailed local results: `results/a2-local/README.md`.
