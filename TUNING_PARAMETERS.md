# Tuning Parameters

This section records the four tuning parameters required by Assignment 2: prefetch count, consumer concurrency, batch ack size and connection (channel) pool size. Each experiment changed one parameter and kept the others fixed. Every run was a 500K-swipe run through the ALB with seed 6650, Top-N = 20 and SQLite `synchronous=FULL`. All times are UTC; experiments span 2026-11-08 and 2026-11-09. Each run's recorded date and window identify its evidence.

**How to read the results.** Consumer concurrency includes the original/repeated 5 / 10 / 15 / 20 sweep and a supplementary forward/reverse 10 / 20 / 40 / 80 sweep. Channel-pool evidence includes historical 512-client runs, a new two-ingest 512-client repeat and a supplementary 256-client comparison. Other configurations were run once unless stated otherwise. Persistent database state is retained and grows between runs; cache state and host conditions also vary. Small differences are not established as statistically significant, and a larger difference in one sequential pair does not by itself establish causation. The measurements indicate downstream processing pressure at high input rates; they do not establish a single exclusive bottleneck in every run. The worker repeat also records cache/read-workload differences.

## Summary

| Parameter | Environment variable | Values tested | Value kept | Observed result and limitation |
|---|---|---|---|---|
| Prefetch count | `PREFETCH_COUNT` (consumer) | 64 / 128 / 256 / 512 | 128 | End-to-end throughput ranged from 3,244 to 3,314/s (about 2% spread), with no consistent improvement as prefetch increased. All four observations had substantial backlog at 256 client threads. |
| Consumer concurrency | `CONSUMER_THREADS` (consumer) | 5 / 10 / 15 / 20; supplementary 10 / 20 / 40 / 80 | 10 | No reliable throughput benefit above ten workers was established. Ten ordered resort lanes cap useful aggregation parallelism. Repeats expose cache/run-order variation; the five-worker repeat and the first supplementary ten-worker observation had substantial lag. |
| Batch ack size | `ACK_BATCH_SIZE` (consumer) | 1 / 16 | 16 | ACK 16 recorded about +6% end-to-end throughput at 128 client threads and +4% at 256 versus ACK 1. These are observed differences, not a guaranteed gain; ACK 16 did not prevent overload at 256 clients. |
| Connection pool size | `CHANNEL_POOL_SIZE` (ingest) | 32 / 64, at 256 and 512 client threads | 64 | Results vary across sessions. The new two-ingest/512-client pair recorded 2,559.25 → 3,097.55/s end-to-end (+21.0%), but retries increased 0 → 58 and HTTP p99 stayed near 272 ms. Earlier 512-client runs showed fewer retries with pool 64; the 256-client comparison showed similar completion throughput and lower p99 with pool 32. A larger pool does not consistently eliminate retries or improve tail latency. |

**Latest channel-pool repeat (2026-11-09 UTC).** Both settings processed exactly 500,000 unique events, with zero final failures, zero additional duplicates and final Ready / in-flight / outbox counts of zero. Pool 32 / 64 had sampled Ready peaks of **260,244 / 196,473** and mean lag of **42,884 / 24,390 ms**; both missed the queue-depth and lag targets. Consumer monitoring recorded **5 / 11 timeouts**, respectively, which remain disclosed rather than replaced with zero. The two observations were sequential against retained database state, so the throughput difference cannot be attributed exclusively to channel-pool size. All previous runs are retained. See [the complete repeat comparison](output/assignment2-pool-repeat-20261109T013054Z/comparison.md) and Section 4 for original logs, metrics and RabbitMQ screenshots.

Common fixed settings unless a section says otherwise: 10 resorts and queues, consumer DB batch size 64 (`STORE_BATCH_SIZE`) with a 10 ms flush trigger (`STORE_BATCH_WAIT_MS`), 4 ingest instances behind the ALB, 128 consumer prefetch and ACK batch 16.

---

## 1. Prefetch count

**What it is.** `PREFETCH_COUNT` is the maximum number of unacknowledged messages RabbitMQ pushes to the consumer. Too small and the consumer may wait for messages. Too large and more messages are redelivered after a consumer restart, and more memory is held. The consumer limits `STORE_BATCH_SIZE` and `ACK_BATCH_SIZE` to at most the prefetch value.

**Setup.** 4 ingests, 256 client threads, 500K events, `ACK_BATCH_SIZE=16`, `STORE_BATCH_SIZE=64`. Only `PREFETCH_COUNT` changed. Values were set with `monitoring/set_consumer_tuning.py` and confirmed from the consumer `/metrics` endpoint.

| Prefetch | End-to-end throughput | Client runtime | Mean consume rate | Queue peak | Samples with queue > 1000 | RabbitMQ CPU mean / max | Consumer CPU mean / max | Failed / retries / duplicates |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 64 | 3,261/s | 130.7 s | 3,149/s | 76,343 | 51 / 78 | 60.6% / 95.1% | 36.6% / 99.9% | 0 / 0 / 0 |
| 128 | 3,244/s | 133.3 s | 3,143/s | 68,339 | 55 / 78 | 61.8% / 94.7% | 28.9% / 51.5% | 0 / 0 / 0 |
| 256 | 3,314/s | 126.7 s | 3,223/s | 84,288 | 53 / 76 | 62.3% / 93.6% | 36.4% / 100% | 0 / 0 / 0 |
| 512 | 3,270/s | 136.1 s | 3,179/s | 48,565 | 52 / 78 | 62.4% / 93.7% | 36.1% / 100% | 0 / 0 / 0 |

| Prefetch | Evidence directory (under `results/a2-ec2/`) | Client window (UTC) |
|---:|---|---|
| 64 | `instrumented-500000-c256-20261108T214913Z` | 21:49:16 to 21:51:53 |
| 128 | `instrumented-500000-c256-20261108T213151Z` | 21:31:54 to 21:34:32 |
| 256 | `instrumented-500000-c256-20261108T215707Z` | 21:57:11 to 21:59:44 |
| 512 | `instrumented-500000-c256-20261108T220055Z` | 22:00:59 to 22:03:35 |

**Finding.** End-to-end throughput stayed between 3,244 and 3,314/s for all four values, a spread of about 2%. RabbitMQ and consumer CPU did not change. The queue peak does not rise or fall with prefetch; it follows the publish rate of each run. In this range prefetch is not the limiting factor.

**Decision.** Keep 128. Larger values gave no measurable benefit and would increase the number of messages redelivered after a consumer restart. Redelivery is safe because the consumer deduplicates by eventId.

### Supplied screenshots: prefetch comparison

All experiments below use **four ingests, 500K events and 256 client threads**. The saved Consumer `/metrics` samples independently confirm prefetch **64 / 128 / 256 / 512** for the four rows above. In particular, the 22:00:59 run records `prefetch=512`; the last `PREFETCH_COUNT 256` in the supplied description is a transcription mismatch, not the recorded setting. No tuning command or load test is executed by adding these screenshots.

The supplied ALB detail charts show the LoadBalancer dimension, **RequestCount Sum**, **TargetResponseTime Average**, and **1-minute periods**, with UTC ranges matching the corresponding recorded run to whole seconds. The RabbitMQ screenshots display local **Pacific time (UTC-08:00)**: add eight hours to compare their axes with the UTC windows. They show **Queued messages - last ten minutes** and **Message rates - last ten minutes**, including earlier runs where the ranges overlap. Overview aggregates all queues, whereas numerical queue statistics above use the recorded raw resort-queue metrics. Legend values are instantaneous, not the run peak.

Supplied screenshots are now available for all four prefetch values. The 128 baseline at 21:31:54-21:34:32 UTC is the same recorded run as the 256-client-thread / ACK-batch-16 case in Section 3; its images are shared below without relabeling another run.

#### Prefetch 128 - retained baseline and original evidence

The original data is retained in `instrumented-500000-c256-20261108T213151Z`. This run is also the prefetch-128 / ACK-batch-16 baseline referenced in Section 3; it is not another worker-sweep run.

| Verified item | Recorded value |
|---|---|
| Client UTC window | 2026-11-08 21:31:54.544-21:34:32.070 |
| Ingests / events / client threads | 4 / 500,000 / 256 |
| Prefetch / Consumer workers / ACK batch | 128 / 10 / 16 |
| Main HTTP / end-to-end throughput | 4,830.78 / 3,243.59 events/s |
| Sampled Ready peak | 68,339 |
| Final successful / failed / retries | 500,000 / 0 / 0 |
| Additional processed / duplicates | 500,000 / 0 |
| Final Ready / in-flight / pending outbox | 0 / 0 / 0 |

[Original client log](results/a2-ec2/instrumented-500000-c256-20261108T213151Z/client.log) | [Run configuration and UTC window](results/a2-ec2/instrumented-500000-c256-20261108T213151Z/run.json) | [Consumer configuration and processing samples](results/a2-ec2/instrumented-500000-c256-20261108T213151Z/monitor/consumer.jsonl) | [Direct RabbitMQ samples](results/a2-ec2/instrumented-500000-c256-20261108T213151Z/monitor/rabbitmq.jsonl) | [Verification record and source hashes](output/tuning-parameters-assets/prefetch-128-baseline-verification.json)

The matching original screenshots have now been supplied. They document this same **prefetch-128 / ACK-batch-16 / 256-client-thread** run, also shown in Section 3. RabbitMQ axes around 13:32-13:34 Pacific correspond to 21:32-21:34 UTC. The ALB overview is cropped above its controls; the two metric detail images retain the full UTC range.

**RabbitMQ queue depth and message rates during load - retained prefetch-128 baseline.**

![RabbitMQ queue depth and message rates during load, prefetch 128 baseline](output/tuning-parameters-assets/ack-16-client256-rabbitmq-during.png)

**RabbitMQ history after drain - retained prefetch-128 baseline.**

![RabbitMQ history after drain, prefetch 128 baseline](output/tuning-parameters-assets/ack-16-client256-rabbitmq-drained.png)

**ALB load balancer metrics overview - retained prefetch-128 baseline.**

![ALB load balancer metrics overview, prefetch 128 baseline](output/tuning-parameters-assets/ack-16-client256-alb-overview.png)

**CloudWatch RequestCount - Sum, 1 minute - retained prefetch-128 baseline.**

![CloudWatch RequestCount - Sum, 1 minute, prefetch 128 baseline](output/tuning-parameters-assets/ack-16-client256-request-count.png)

**CloudWatch TargetResponseTime - Average, 1 minute - retained prefetch-128 baseline.**

![CloudWatch TargetResponseTime - Average, 1 minute, prefetch 128 baseline](output/tuning-parameters-assets/ack-16-client256-target-response-time.png)

#### Prefetch 64 - 21:49:16-21:51:53 UTC

Run: [instrumented-500000-c256-20261108T214913Z](results/a2-ec2/instrumented-500000-c256-20261108T214913Z/run.json). Recorded `prefetch=64`, `configuredThreads=10`, `ackBatchSize=16`, `storeBatchSize=64`.

RabbitMQ activity around **13:49-13:52 Pacific** corresponds to **21:49-21:52 UTC**. The after-drain image shows Ready/Unacked/Total at zero; the historical peak remains visible.

**RabbitMQ queue depth and message rates during load - prefetch 64.**

![RabbitMQ queue depth and message rates during load, prefetch 64](output/tuning-parameters-assets/prefetch-64-rabbitmq-during.png)

**RabbitMQ history after drain - prefetch 64.**

![RabbitMQ history after drain, prefetch 64](output/tuning-parameters-assets/prefetch-64-rabbitmq-drained.png)

**Load balancer metrics overview - prefetch 64.**

![Load balancer metrics overview, prefetch 64](output/tuning-parameters-assets/prefetch-64-alb-overview.png)

**CloudWatch RequestCount - Sum, 1 minute - prefetch 64.**

![CloudWatch RequestCount - Sum, 1 minute, prefetch 64](output/tuning-parameters-assets/prefetch-64-request-count.png)

**CloudWatch TargetResponseTime - Average, 1 minute - prefetch 64.**

![CloudWatch TargetResponseTime - Average, 1 minute, prefetch 64](output/tuning-parameters-assets/prefetch-64-target-response-time.png)

#### Prefetch 256 - 21:57:11-21:59:44 UTC

Run: [instrumented-500000-c256-20261108T215707Z](results/a2-ec2/instrumented-500000-c256-20261108T215707Z/run.json). Recorded `prefetch=256`, `configuredThreads=10`, `ackBatchSize=16`, `storeBatchSize=64`.

The current prefetch-256 activity is around **13:57-14:00 Pacific** (**21:57-22:00 UTC**). The older peak near 13:50 belongs to the earlier prefetch-64 run and must not be assigned to prefetch 256.

**RabbitMQ queue depth and message rates during load - prefetch 256.**

![RabbitMQ queue depth and message rates during load, prefetch 256](output/tuning-parameters-assets/prefetch-256-rabbitmq-during.png)

**RabbitMQ history after drain - prefetch 256.**

![RabbitMQ history after drain, prefetch 256](output/tuning-parameters-assets/prefetch-256-rabbitmq-drained.png)

**Load balancer metrics overview - prefetch 256.**

![Load balancer metrics overview, prefetch 256](output/tuning-parameters-assets/prefetch-256-alb-overview.png)

**CloudWatch TargetResponseTime - Average, 1 minute - prefetch 256.**

![CloudWatch TargetResponseTime - Average, 1 minute, prefetch 256](output/tuning-parameters-assets/prefetch-256-target-response-time.png)

**CloudWatch RequestCount - Sum, 1 minute - prefetch 256.**

![CloudWatch RequestCount - Sum, 1 minute, prefetch 256](output/tuning-parameters-assets/prefetch-256-request-count.png)

#### Prefetch 512 - 22:00:59-22:03:35 UTC

Run: [instrumented-500000-c256-20261108T220055Z](results/a2-ec2/instrumented-500000-c256-20261108T220055Z/run.json). Recorded `prefetch=512`, `configuredThreads=10`, `ackBatchSize=16`, `storeBatchSize=64`.

The current prefetch-512 activity is around **14:01-14:04 Pacific** (**22:01-22:04 UTC**). The larger earlier peak around 13:58-14:00 is the preceding prefetch-256 run. The during-load image captures the early 512 workload; its rightmost region, not the older peak, belongs to this run.

**RabbitMQ queue depth and message rates during load - prefetch 512.**

![RabbitMQ queue depth and message rates during load, prefetch 512](output/tuning-parameters-assets/prefetch-512-rabbitmq-during.png)

**RabbitMQ history after drain - prefetch 512.**

![RabbitMQ history after drain, prefetch 512](output/tuning-parameters-assets/prefetch-512-rabbitmq-drained.png)

**Load balancer metrics overview - prefetch 512.**

![Load balancer metrics overview, prefetch 512](output/tuning-parameters-assets/prefetch-512-alb-overview.png)

**CloudWatch RequestCount - Sum, 1 minute - prefetch 512.**

![CloudWatch RequestCount - Sum, 1 minute, prefetch 512](output/tuning-parameters-assets/prefetch-512-request-count.png)

**CloudWatch TargetResponseTime - Average, 1 minute - prefetch 512.**

![CloudWatch TargetResponseTime - Average, 1 minute, prefetch 512](output/tuning-parameters-assets/prefetch-512-target-response-time.png)

---

## 2. Consumer concurrency

**What it is.** `CONSUMER_THREADS` controls Consumer worker threads, not the number of resorts. Ten ordered resort queues provide at most ten independent aggregation lanes. A worker can process different resort lanes over time, while a single resort must not be processed concurrently out of order. Configuring 15 or 20 workers does not create more resort queues.

**Setup.** Both observations use four ingests, 500K events, 128 client threads, ten resorts, Top-N 20 and seed 6650. Prefetch is 128, database batch size 64 with a 10 ms flush trigger, ACK batch 16, and ingest channel pool 64. `CONSUMER_DYNAMIC_SCALING=false`; the Consumer is restarted at each worker setting. Both sweeps use the order 5 / 10 / 15 / 20. The repeat retains the persistent database and has a 70-second quiet interval before each load. It follows an EC2 stop/start, so cache, database size, CPU credits and admitted request rates may differ from the original. No earlier observation is replaced.

### Original observation versus repeat

| Workers | Observation | Main HTTP /s | End-to-end /s | HTTP p99 ms | Ready peak | Ready + Unacked peak | Mean lag ms | Failed / retries |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 5 | Original | 2,604.84 | 2,275.86 | 241.01 | 138 | 1,380 | 42.40 | 0 / 0 |
| 5 | Repeat | 2,906.54 | 2,548.57 | 78.44 | 8,848 | 10,128 | 1,268.99 | 0 / 0 |
| 10 | Original | 3,155.08 | 2,650.72 | 92.10 | 446 | 1,726 | 34.96 | 0 / 0 |
| 10 | Repeat | 2,630.00 | 2,338.03 | 192.48 | 911 | 2,191 | 32.93 | 0 / 0 |
| 15 | Original | 3,032.48 | 2,643.07 | 93.09 | 1,059 | 2,192 | 38.50 | 0 / 0 |
| 15 | Repeat | 2,826.24 | 2,487.89 | 86.22 | 1,204 | 2,168 | 31.22 | 0 / 0 |
| 20 | Original | 3,036.31 | 2,620.63 | 99.31 | 111 | 1,346 | 39.38 | 0 / 0 |
| 20 | Repeat | 2,872.71 | 2,534.38 | 84.47 | 1,233 | 2,174 | 31.07 | 0 / 0 |

The repeat sent and processed exactly 2,000,000 events in total. All four runs finished with zero final failures, zero retries, zero added duplicates and zero final Ready/in-flight/outbox counts. The Consumer was restored to ten fixed workers. The five-worker run recorded one Consumer API collection timeout; direct RabbitMQ samples are retained independently. Lag is a run-specific mean, not a per-event latency limit. Queue values are sampled peaks, not continuously observed upper bounds.

| Workers | Original UTC window | Repeat UTC window | Repeat evidence |
|---:|---|---|---|
| 5 | 15:12:00.633 to 15:15:45.724 | 20:16:34.626 to 20:19:54.404 | [instrumented-500000-c128-20261108T201618Z](results/a2-repeat/20261108T201347Z/phase-4ingest-workers5/instrumented-500000-c128-20261108T201618Z/run.json) |
| 10 | 15:17:38.725 to 15:20:52.587 | 20:21:39.768 to 20:25:16.749 | [instrumented-500000-c128-20261108T202127Z](results/a2-repeat/20261108T201347Z/phase-4ingest-workers10/instrumented-500000-c128-20261108T202127Z/run.json) |
| 15 | 15:22:40.634 to 15:25:52.373 | 20:27:06.061 to 20:30:29.855 | [instrumented-500000-c128-20261108T202651Z](results/a2-repeat/20261108T201347Z/phase-4ingest-workers15/instrumented-500000-c128-20261108T202651Z/run.json) |
| 20 | 15:27:39.783 to 15:30:53.922 | 20:32:14.440 to 20:35:34.212 | [instrumented-500000-c128-20261108T203202Z](results/a2-repeat/20261108T201347Z/phase-4ingest-workers20/instrumented-500000-c128-20261108T203202Z/run.json) |

**Finding.** In the original sweep, five workers had the lowest end-to-end throughput. In the repeat, five workers delivered 2,548.57/s but also had Ready peak 8,848 and mean lag 1,268.99 ms. Ten, fifteen and twenty workers had mean lag close to 31-33 ms; throughput was 2,338.03 / 2,487.89 / 2,534.38 per second, respectively. The measurements therefore do not establish that throughput increases monotonically with worker count, or that five workers always have less capacity. More workers beyond ten do not add independent resort lanes.

**Cache and input-rate limitation.** Consumer device reads declined across the repeat: 1,072.65 MiB (5 workers), 149.11 MiB (10), 24.01 MiB (15), and 4.48 MiB (20). These OS-device counters are consistent with cache warming and a changing storage-read workload. The five-worker repeat also admitted requests faster than the ten-worker repeat. Consequently, its higher backlog and lag cannot be attributed only to worker count. Neither sweep randomizes run order or resets cache state.

**Targets and decision.** Keep ten workers as the configuration aligned with the ten ordered resort lanes. In the repeat, ten workers was the only setting with both a sampled **Ready** peak below 1,000 and mean lag below 100 ms. Fifteen and twenty workers exceeded 1,000 Ready despite low mean lag. All four repeats exceeded 1,000 when **Ready + Unacked** is counted, so none is claimed to satisfy that stricter queue-depth definition. This is not evidence that ten workers is optimal under every workload.

[Full original/repeat comparison and raw evidence](output/assignment2-repeat-20261108T201347Z/comparison.md) | [CSV](output/assignment2-repeat-20261108T201347Z/comparison.csv) | [Count and restoration checks](output/assignment2-repeat-20261108T201347Z/validation.json)

![Original versus repeat: throughput, Ready peak and mean lag](output/assignment2-repeat-20261108T201347Z/comparison.png)

### Supplementary test: 10 / 20 / 40 / 80 workers, run forward then in reverse

The assignment text lists consumer thread counts of 10, 20, 40 and 80, while the sweeps above used 5 / 10 / 15 / 20. This supplementary test runs the listed values. Nothing above is replaced.

**Setup.** 4 ingests behind the ALB, 500K events, 128 client threads, seed 6650, Top-N 20. Per sample of the Consumer monitor: prefetch 128, database batch 64, ACK batch 16, `CONSUMER_DYNAMIC_SCALING=false`, and the pipeline was drained (Ready, in-flight and outbox 0) at the start of every run. Ingest channel pool 64 on all four ingests (read from `/health` after the sweep). The worker count was changed with `monitoring/set_consumer_threads.py`, which restarts the Consumer; the monitored `configuredThreads` matched the intended value in every run. The order was 10, 20, 40, 80, 80, 40, 20, 10, so that each setting is measured once early and once late and drift over the session can be told apart from a worker-count effect. This session follows an EC2 restart.

| Run | Workers | Evidence directory (`results/a2-ec2/`) | Client window (UTC) | End-to-end /s | Main HTTP /s | HTTP P99 ms | Ready peak | Mean lag ms | Consumer CPU mean / max % | Consumer I/O wait % | Max active workers |
|---:|---:|---|---|---:|---:|---:|---:|---:|---|---:|---:|
| 1 | 10 | `instrumented-500000-c128-20261109T040639Z` | 04:06:42 to 04:10:00 | 2,604 | 3,208 | 77.3 | 31,061 | 5,924.8 | 32.8 / 99.8 | 55.1 | 10 |
| 2 | 20 | `instrumented-500000-c128-20261109T041528Z` | 04:15:31 to 04:18:35 | 2,768 | 3,208 | 80.1 | 608 | 34.1 | 39.7 / 100.0 | 20.9 | 7 |
| 3 | 40 | `instrumented-500000-c128-20261109T042706Z` | 04:27:09 to 04:30:18 | 2,701 | 3,150 | 83.5 | 1,199 | 35.0 | 38.9 / 99.9 | 19.1 | 6 |
| 4 | 80 | `instrumented-500000-c128-20261109T043211Z` | 04:32:14 to 04:35:25 | 2,682 | 3,144 | 85.8 | 648 | 35.2 | 39.6 / 100.0 | 18.1 | 8 |
| 5 | 80 | `instrumented-500000-c128-20261109T043641Z` | 04:36:44 to 04:39:57 | 2,649 | 3,063 | 99.5 | 1,552 | 37.9 | 38.9 / 100.0 | 17.5 | 10 |
| 6 | 40 | `instrumented-500000-c128-20261109T044106Z` | 04:41:10 to 04:44:21 | 2,651 | 3,073 | 94.2 | 0 | 37.2 | 39.2 / 99.8 | 17.7 | 8 |
| 7 | 20 | `instrumented-500000-c128-20261109T044456Z` | 04:45:00 to 04:48:12 | 2,641 | 3,111 | 93.7 | 1,035 | 34.4 | 38.7 / 100.0 | 18.0 | 6 |
| 8 | 10 | `instrumented-500000-c128-20261109T044828Z` | 04:48:31 to 04:51:42 | 2,654 | 3,127 | 83.6 | 0 | 39.4 | 38.5 / 99.1 | 18.8 | 8 |

All eight runs: 500,000 processed, 0 duplicates, 0 failed requests, 0 application retries, final Ready, in-flight and outbox 0. Ready peak and I/O wait are over the client window (plus one minute for Ready); mean lag is the run-specific mean from the Consumer's cumulative lag counters; active workers are sampled every 2 s.

**Finding.**
- **Worker count above ten did not change throughput.** Excluding run 1, end-to-end throughput was 2,641 to 2,768/s for 10, 20, 40 and 80 workers. The two runs of the same setting differ by up to 4.8% (20 workers: 2,768 and 2,641), which is as large as the difference between settings, so no setting is better than another on this evidence. Mean lag was 34 to 39 ms and the HTTP P99 77 to 99 ms for all of them.
- **More configured threads did not become more active workers.** With 80 configured workers the sampled active workers peaked at 8 and 10, and in no run did any setting exceed 10 active workers; this matches the ten ordered resort lanes. Consumer CPU averaged 39% in every warm run, so the extra threads did not cost measurable CPU either.
- **The later run of each pair was lower.** Run 5 to run 8 (80, 40, 20, 10 workers) were 0 to 5% below the matching earlier runs for 20, 40 and 80 workers and had a higher P99 (94 to 99 ms against 80 to 86 ms). The direction is the same for every setting, which points to drift over the session, not to the worker count.
- **Run 1 is a cold-cache observation, not a 10-worker result.** It had I/O wait 55% against 17 to 21% in every other run, Ready peak 31,061 and mean lag 5,925 ms; the same 10 workers in run 8 had Ready 0 and 39 ms lag. This is consistent with the cache-warming effect already seen in the earlier repeat, and it is why the first measurement of any setting after a restart should not be compared with later ones.
- **Queue target.** Sampled Ready exceeded 1,000 in runs 3 (1,199), 5 (1,552) and 7 (1,035), and was below 1,000 in the other warm runs; this varies between runs of the same setting, so it is not attributable to the worker count.

**Decision.** Keep ten workers: the ten ordered resort lanes cap aggregation parallelism at ten, and 20, 40 and 80 workers gave no measurable benefit and no measurable cost in this test. Limits: two runs per setting, one load level (128 client threads), and a first run affected by cold cache.

#### Screenshots for the 10 / 20 / 40 / 80 test

**ALB view (runs 1 to 8).** The CloudWatch captures use each run's client window with 1-minute buckets (first and last points are partial minutes, lines between points are interpolation, and the y-axis range differs per chart). As read from the chart axes, the peak one-minute request count was 180K to 194K and the peak one-minute average TargetResponseTime 8.3 to 10.5 ms in every run, with no pattern by worker count. That is expected: the worker count acts after the ingest tier, so ingest-side metrics should not move. For run 8 the Metrics chart covers runs 7 and 8 together (04:45:00 to 04:51:42 UTC), so it is not specific to run 8.

#### Run 1: 10 workers, 04:06:42 to 04:10:00 UTC

![Run 1, 10 workers: RabbitMQ Management overview, queued messages and message rates](output/tuning-parameters-assets/sweep-run1-w10-rabbitmq.png)

RabbitMQ Overview, last ten minutes (axis in the browser's local time, UTC-8, so 20:06 is 04:06 UTC); totals are Ready plus Unacked and Unacked alone can reach 1,280 (ten queues times prefetch 128). Queued messages rise to about 32K during the run and drain by about 20:10 (cold-cache run; Ready peak 31,061 by the Consumer monitor).

![Run 1, 10 workers: ALB load balancer metrics](output/tuning-parameters-assets/sweep-run1-w10-alb-overview.png)

![Run 1, 10 workers: RequestCount and TargetResponseTime](output/tuning-parameters-assets/sweep-run1-w10-cloudwatch-metrics.png)

#### Run 2: 20 workers, 04:15:31 to 04:18:35 UTC

![Run 2, 20 workers: RabbitMQ Management overview, queued messages and message rates](output/tuning-parameters-assets/sweep-run2-w20-rabbitmq.png)

RabbitMQ Overview, last ten minutes (axis in the browser's local time, UTC-8, so 20:06 is 04:06 UTC); totals are Ready plus Unacked and Unacked alone can reach 1,280 (ten queues times prefetch 128). A short rise to about 1.5K at the start, then a few hundred (Ready peak 608).

![Run 2, 20 workers: ALB load balancer metrics](output/tuning-parameters-assets/sweep-run2-w20-alb-overview.png)

![Run 2, 20 workers: RequestCount and TargetResponseTime](output/tuning-parameters-assets/sweep-run2-w20-cloudwatch-metrics.png)

#### Run 3: 40 workers, 04:27:09 to 04:30:18 UTC

![Run 3, 40 workers: RabbitMQ Management overview, queued messages and message rates](output/tuning-parameters-assets/sweep-run3-w40-rabbitmq.png)

RabbitMQ Overview, last ten minutes (axis in the browser's local time, UTC-8, so 20:06 is 04:06 UTC); totals are Ready plus Unacked and Unacked alone can reach 1,280 (ten queues times prefetch 128). A spike of about 2.5K at 20:27 at the start, then a few hundred (Ready peak 1,199).

![Run 3, 40 workers: ALB load balancer metrics](output/tuning-parameters-assets/sweep-run3-w40-alb-overview.png)

![Run 3, 40 workers: RequestCount and TargetResponseTime](output/tuning-parameters-assets/sweep-run3-w40-cloudwatch-metrics.png)

#### Run 4: 80 workers, 04:32:14 to 04:35:25 UTC

![Run 4, 80 workers: RabbitMQ Management overview, queued messages and message rates](output/tuning-parameters-assets/sweep-run4-w80-rabbitmq.png)

RabbitMQ Overview, last ten minutes (axis in the browser's local time, UTC-8, so 20:06 is 04:06 UTC); totals are Ready plus Unacked and Unacked alone can reach 1,280 (ten queues times prefetch 128). The chart also holds run 3: its 2.5K spike is on the left and this run's spike of about 1.1K at 20:32 on the right.

![Run 4, 80 workers: ALB load balancer metrics](output/tuning-parameters-assets/sweep-run4-w80-alb-overview.png)

![Run 4, 80 workers: RequestCount and TargetResponseTime](output/tuning-parameters-assets/sweep-run4-w80-cloudwatch-metrics.png)

#### Run 5: 80 workers, 04:36:44 to 04:39:57 UTC

![Run 5, 80 workers: RabbitMQ Management overview, queued messages and message rates](output/tuning-parameters-assets/sweep-run5-w80-rabbitmq.png)

RabbitMQ Overview, last ten minutes (axis in the browser's local time, UTC-8, so 20:06 is 04:06 UTC); totals are Ready plus Unacked and Unacked alone can reach 1,280 (ten queues times prefetch 128). Run 4's spike of about 1.1K is on the left; this run's spike of about 2.8K is at 20:37.

![Run 5, 80 workers: ALB load balancer metrics](output/tuning-parameters-assets/sweep-run5-w80-alb-overview.png)

![Run 5, 80 workers: RequestCount and TargetResponseTime](output/tuning-parameters-assets/sweep-run5-w80-cloudwatch-metrics.png)

#### Run 6: 40 workers, 04:41:10 to 04:44:21 UTC

![Run 6, 40 workers: RabbitMQ Management overview, queued messages and message rates](output/tuning-parameters-assets/sweep-run6-w40-rabbitmq.png)

RabbitMQ Overview, last ten minutes (axis in the browser's local time, UTC-8, so 20:06 is 04:06 UTC); totals are Ready plus Unacked and Unacked alone can reach 1,280 (ten queues times prefetch 128). Run 5's 2.8K spike is on the left; this run's spike of about 1.0K is at 20:41.

![Run 6, 40 workers: ALB load balancer metrics](output/tuning-parameters-assets/sweep-run6-w40-alb-overview.png)

![Run 6, 40 workers: RequestCount and TargetResponseTime](output/tuning-parameters-assets/sweep-run6-w40-cloudwatch-metrics.png)

#### Run 7: 20 workers, 04:45:00 to 04:48:12 UTC

![Run 7, 20 workers: RabbitMQ Management overview, queued messages and message rates](output/tuning-parameters-assets/sweep-run7-w20-rabbitmq.png)

RabbitMQ Overview, last ten minutes (axis in the browser's local time, UTC-8, so 20:06 is 04:06 UTC); totals are Ready plus Unacked and Unacked alone can reach 1,280 (ten queues times prefetch 128). Run 6's spike (about 1.0K) is on the left; this run's spike of about 2.2K is at 20:45, and run 8 is starting at the right edge.

![Run 7, 20 workers: ALB load balancer metrics](output/tuning-parameters-assets/sweep-run7-w20-alb-overview.png)

![Run 7, 20 workers: RequestCount and TargetResponseTime](output/tuning-parameters-assets/sweep-run7-w20-cloudwatch-metrics.png)

#### Run 8: 10 workers, 04:48:31 to 04:51:42 UTC

![Run 8, 10 workers: RabbitMQ Management overview, queued messages and message rates](output/tuning-parameters-assets/sweep-run8-w10-rabbitmq.png)

RabbitMQ Overview, last ten minutes (axis in the browser's local time, UTC-8, so 20:06 is 04:06 UTC); totals are Ready plus Unacked and Unacked alone can reach 1,280 (ten queues times prefetch 128). Run 7's 2.2K spike is on the left; this run's spike of about 1.0K is at 20:48.

![Run 8, 10 workers: ALB load balancer metrics](output/tuning-parameters-assets/sweep-run8-w10-alb-overview.png)

![Runs 7 and 8 window: RequestCount and TargetResponseTime](output/tuning-parameters-assets/sweep-run8-w10-cloudwatch-metrics-runs7-8-window.png)

This RequestCount and TargetResponseTime chart covers 04:45:00 to 04:51:42 UTC, that is runs 7 and 8 together, not run 8 alone; the dip near 04:48 is the interval between the two runs.

### RabbitMQ console screenshots: the four repeated worker settings

These are unchanged screenshots of the actual RabbitMQ Management UI, showing **Queued messages - last ten minutes** and **Message rates - last ten minutes**. Overview includes all queues, while the numeric table above covers only the ten raw resort queues. Each ten-minute chart can include the preceding run; use the stated run window and screenshot timestamp. Screenshots show history and an instantaneous legend, not necessarily the sampled maximum in the table.

**5 workers - repeat, run 20:16:34.626 to 20:19:54.404 UTC; console captured at 20:18:05 UTC.**

![RabbitMQ queued messages and message rates, 5 workers](output/tuning-parameters-assets/consumer-5-workers-repeat-rabbitmq.png)

**10 workers - repeat, run 20:21:39.768 to 20:25:16.749 UTC; console captured at 20:21:39 UTC.**

![RabbitMQ queued messages and message rates, 10 workers](output/tuning-parameters-assets/consumer-10-workers-repeat-rabbitmq.png)

**15 workers - repeat, run 20:27:06.061 to 20:30:29.855 UTC; console captured at 20:27:05 UTC.**

![RabbitMQ queued messages and message rates, 15 workers](output/tuning-parameters-assets/consumer-15-workers-repeat-rabbitmq.png)

**20 workers - repeat, run 20:32:14.440 to 20:35:34.212 UTC; console captured at 20:32:14 UTC.**

![RabbitMQ queued messages and message rates, 20 workers](output/tuning-parameters-assets/consumer-20-workers-repeat-rabbitmq.png)

### Supplied ALB screenshots: run-matched Consumer concurrency repeat

The eight supplied screenshots below match the four repeated worker runs on **2026-11-08, UTC**. Each setting has a Load balancer metrics overview and a combined CloudWatch RequestCount/TargetResponseTime chart. The detailed charts show the LoadBalancer dimension, **RequestCount = Sum**, **TargetResponseTime = Average**, and **period = 1 minute**; Count uses the left axis and Seconds the right axis. The screenshot ranges truncate the recorded subsecond boundaries to whole seconds. These are one-minute bucket trends, not exact event-count reconciliation or Consumer lag measurements.

#### 5 workers - 20:16:34-20:19:54 UTC

**Load balancer metrics overview; 5 workers, 20:16:34-20:19:54 UTC.**

![Load balancer metrics overview, 5 workers](output/tuning-parameters-assets/consumer-5-workers-repeat-alb-overview.png)

**CloudWatch RequestCount and TargetResponseTime; 5 workers, 20:16:34-20:19:54 UTC.**

![CloudWatch RequestCount and TargetResponseTime, 5 workers](output/tuning-parameters-assets/consumer-5-workers-repeat-alb-metrics.png)

#### 10 workers - 20:21:39-20:25:16 UTC

**Load balancer metrics overview; 10 workers, 20:21:39-20:25:16 UTC.**

![Load balancer metrics overview, 10 workers](output/tuning-parameters-assets/consumer-10-workers-repeat-alb-overview.png)

**CloudWatch RequestCount and TargetResponseTime; 10 workers, 20:21:39-20:25:16 UTC.**

![CloudWatch RequestCount and TargetResponseTime, 10 workers](output/tuning-parameters-assets/consumer-10-workers-repeat-alb-metrics.png)

#### 15 workers - 20:27:06-20:30:29 UTC

**Load balancer metrics overview; 15 workers, 20:27:06-20:30:29 UTC.**

![Load balancer metrics overview, 15 workers](output/tuning-parameters-assets/consumer-15-workers-repeat-alb-overview.png)

**CloudWatch RequestCount and TargetResponseTime; 15 workers, 20:27:06-20:30:29 UTC.**

![CloudWatch RequestCount and TargetResponseTime, 15 workers](output/tuning-parameters-assets/consumer-15-workers-repeat-alb-metrics.png)

#### 20 workers - 20:32:14-20:35:34 UTC

**Load balancer metrics overview; 20 workers, 20:32:14-20:35:34 UTC.**

![Load balancer metrics overview, 20 workers](output/tuning-parameters-assets/consumer-20-workers-repeat-alb-overview.png)

**CloudWatch RequestCount and TargetResponseTime; 20 workers, 20:32:14-20:35:34 UTC.**

![CloudWatch RequestCount and TargetResponseTime, 20 workers](output/tuning-parameters-assets/consumer-20-workers-repeat-alb-metrics.png)

---

## 3. Batch ack size

**What it is.** `ACK_BATCH_SIZE` is how many processed messages are acknowledged with one cumulative (multi-ack) message to RabbitMQ, instead of one acknowledgement per message. `ACK_FLUSH_MS` forces a flush if the batch does not fill. A larger batch reduces acknowledgement traffic. If the consumer crashes, up to one batch is redelivered, and duplicates are rejected by eventId. This is separate from `STORE_BATCH_SIZE`, which groups database writes.

**Setup.** 4 ingests, 500K events, prefetch 128, `STORE_BATCH_SIZE=64`. Only `ACK_BATCH_SIZE` changed, at 128 and at 256 client threads. At 256 threads the batch-16 run was done first, so the batch-1 run started with a larger database.

| Client threads | ACK batch | End-to-end throughput | Client runtime | Mean consume rate | Queue peak | Samples with queue > 1000 | RabbitMQ CPU mean / max | Consumer CPU mean / max |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 128 | 1 | 2,293/s | 219.7 s | 2,247/s | 1,363 | 3 / 110 | 61.6% / 93.0% | 33.3% / 99.5% |
| 128 | 16 | 2,435/s | 207.0 s | 2,384/s | 521 | 0 / 103 | 60.8% / 94.6% | 34.8% / 99.7% |
| 256 | 1 | 3,114/s | 146.0 s | 3,016/s | 44,483 | 42 / 81 | 62.7% / 96.1% | 37.6% / 99.7% |
| 256 | 16 | 3,244/s | 133.3 s | 3,143/s | 68,339 | 55 / 78 | 61.8% / 94.7% | 28.9% / 51.5% |

All runs finished with 0 failed requests, 0 retries and 0 duplicates, and the processed count rose by exactly 500,000 each time.

| Threads | ACK batch | Evidence directory | Client window (UTC) |
|---:|---:|---|---|
| 128 | 1 | `instrumented-500000-c128-20261108T211142Z` | 21:11:45 to 21:15:26 |
| 128 | 16 | `instrumented-500000-c128-20261108T211628Z` | 21:16:31 to 21:19:59 |
| 256 | 16 | `instrumented-500000-c256-20261108T213151Z` | 21:31:54 to 21:34:32 |
| 256 | 1 | `instrumented-500000-c256-20261108T213649Z` | 21:36:53 to 21:39:36 |

**Finding.** Batch 16 gave 6% higher end-to-end throughput at 128 threads and 4% higher at 256 threads. At 128 threads the queue peak fell from 1,363 to 521, and it stayed under 1,000 for the whole batch-16 run. At 256 threads consumer CPU was lower with batch 16 (mean 28.9% against 37.6%, peak 51.5% against 99.7%). RabbitMQ CPU did not change, so the gain came from less work in the consumer and not from lower broker CPU. At 256 threads the queue peak was higher with batch 16 (68,339 against 44,483) because that run also published faster (3,288/s against 3,191/s), so queue peak is not a clean measure of consume efficiency.

**Limitations.** Each setting ran once, and the differences are small. The result is that batch 16 was not worse and was slightly better, not that 16 is the optimum.

**Decision.** Keep 16.

### Supplied screenshots: ACK batch 1 versus 16

The following **20 original screenshots** are grouped by client concurrency and recorded `ACK_BATCH_SIZE`, rather than by attachment order. All four runs use four ingests and 500K events. Captured Consumer samples verify `prefetch=128`, ten fixed workers, database batch 64 and the ACK setting shown for each group.

ALB detail charts display **LoadBalancer**, **RequestCount Sum**, **TargetResponseTime Average**, and **1-minute periods**; the selected ranges match the client windows to whole seconds. RabbitMQ axes display **Pacific time (UTC-08:00)**, so add eight hours to compare with UTC. Its ten-minute history can contain preceding runs, and Overview aggregates all queues. Screenshot legends are instantaneous values, not the numerical sampled peaks reported in the comparison table. Final count and drain checks come from the saved metrics, not from interpolated chart lines.

#### 128 client threads / ACK batch 1 - 21:11:45-21:15:26 UTC

Run: [instrumented-500000-c128-20261108T211142Z](results/a2-ec2/instrumented-500000-c128-20261108T211142Z/run.json). Verified `ackBatchSize=1`, `prefetch=128`, `configuredThreads=10`.

Read the current activity around 13:11-13:15 Pacific (21:11-21:15 UTC). Activity around 13:05 belongs to an earlier run.

**RabbitMQ queue depth and message rates during load - 128 client threads, ACK batch 1.**

![RabbitMQ queue depth and message rates during load, 128 client threads, ACK batch 1](output/tuning-parameters-assets/ack-1-client128-rabbitmq-during.png)

**RabbitMQ history after drain - 128 client threads, ACK batch 1.**

![RabbitMQ history after drain, 128 client threads, ACK batch 1](output/tuning-parameters-assets/ack-1-client128-rabbitmq-drained.png)

**ALB load balancer metrics overview - 128 client threads, ACK batch 1.**

![ALB load balancer metrics overview, 128 client threads, ACK batch 1](output/tuning-parameters-assets/ack-1-client128-alb-overview.png)

**CloudWatch RequestCount - Sum, 1 minute - 128 client threads, ACK batch 1.**

![CloudWatch RequestCount - Sum, 1 minute, 128 client threads, ACK batch 1](output/tuning-parameters-assets/ack-1-client128-request-count.png)

**CloudWatch TargetResponseTime - Average, 1 minute - 128 client threads, ACK batch 1.**

![CloudWatch TargetResponseTime - Average, 1 minute, 128 client threads, ACK batch 1](output/tuning-parameters-assets/ack-1-client128-target-response-time.png)

#### 128 client threads / ACK batch 16 - 21:16:31-21:19:59 UTC

Run: [instrumented-500000-c128-20261108T211628Z](results/a2-ec2/instrumented-500000-c128-20261108T211628Z/run.json). Verified `ackBatchSize=16`, `prefetch=128`, `configuredThreads=10`.

Read the current activity around 13:16-13:20 Pacific (21:16-21:20 UTC). The earlier peak around 13:12 is the preceding ACK-batch-1 run.

**RabbitMQ queue depth and message rates during load - 128 client threads, ACK batch 16.**

![RabbitMQ queue depth and message rates during load, 128 client threads, ACK batch 16](output/tuning-parameters-assets/ack-16-client128-rabbitmq-during.png)

**RabbitMQ history after drain - 128 client threads, ACK batch 16.**

![RabbitMQ history after drain, 128 client threads, ACK batch 16](output/tuning-parameters-assets/ack-16-client128-rabbitmq-drained.png)

**ALB load balancer metrics overview - 128 client threads, ACK batch 16.**

![ALB load balancer metrics overview, 128 client threads, ACK batch 16](output/tuning-parameters-assets/ack-16-client128-alb-overview.png)

**CloudWatch RequestCount - Sum, 1 minute - 128 client threads, ACK batch 16.**

![CloudWatch RequestCount - Sum, 1 minute, 128 client threads, ACK batch 16](output/tuning-parameters-assets/ack-16-client128-request-count.png)

**CloudWatch TargetResponseTime - Average, 1 minute - 128 client threads, ACK batch 16.**

![CloudWatch TargetResponseTime - Average, 1 minute, 128 client threads, ACK batch 16](output/tuning-parameters-assets/ack-16-client128-target-response-time.png)

#### 256 client threads / ACK batch 16 - 21:31:54-21:34:32 UTC

Run: [instrumented-500000-c256-20261108T213151Z](results/a2-ec2/instrumented-500000-c256-20261108T213151Z/run.json). Verified `ackBatchSize=16`, `prefetch=128`, `configuredThreads=10`.

Read the activity around 13:32-13:34 Pacific (21:32-21:34 UTC). This is also the prefetch-128 baseline used in Section 1. The ALB overview image is cropped above the time controls; its visible axis aligns with this run, and the adjacent detail images retain the full UTC range.

**RabbitMQ queue depth and message rates during load - 256 client threads, ACK batch 16.**

![RabbitMQ queue depth and message rates during load, 256 client threads, ACK batch 16](output/tuning-parameters-assets/ack-16-client256-rabbitmq-during.png)

**RabbitMQ history after drain - 256 client threads, ACK batch 16.**

![RabbitMQ history after drain, 256 client threads, ACK batch 16](output/tuning-parameters-assets/ack-16-client256-rabbitmq-drained.png)

**ALB load balancer metrics overview - 256 client threads, ACK batch 16.**

![ALB load balancer metrics overview, 256 client threads, ACK batch 16](output/tuning-parameters-assets/ack-16-client256-alb-overview.png)

**CloudWatch RequestCount - Sum, 1 minute - 256 client threads, ACK batch 16.**

![CloudWatch RequestCount - Sum, 1 minute, 256 client threads, ACK batch 16](output/tuning-parameters-assets/ack-16-client256-request-count.png)

**CloudWatch TargetResponseTime - Average, 1 minute - 256 client threads, ACK batch 16.**

![CloudWatch TargetResponseTime - Average, 1 minute, 256 client threads, ACK batch 16](output/tuning-parameters-assets/ack-16-client256-target-response-time.png)

#### 256 client threads / ACK batch 1 - 21:36:53-21:39:36 UTC

Run: [instrumented-500000-c256-20261108T213649Z](results/a2-ec2/instrumented-500000-c256-20261108T213649Z/run.json). Verified `ackBatchSize=1`, `prefetch=128`, `configuredThreads=10`.

Read the current activity around 13:37-13:40 Pacific (21:37-21:40 UTC). The earlier peak around 13:32-13:34 belongs to ACK batch 16; it must not be assigned to this ACK-batch-1 run.

**RabbitMQ queue depth and message rates during load - 256 client threads, ACK batch 1.**

![RabbitMQ queue depth and message rates during load, 256 client threads, ACK batch 1](output/tuning-parameters-assets/ack-1-client256-rabbitmq-during.png)

**RabbitMQ history after drain - 256 client threads, ACK batch 1.**

![RabbitMQ history after drain, 256 client threads, ACK batch 1](output/tuning-parameters-assets/ack-1-client256-rabbitmq-drained.png)

**ALB load balancer metrics overview - 256 client threads, ACK batch 1.**

![ALB load balancer metrics overview, 256 client threads, ACK batch 1](output/tuning-parameters-assets/ack-1-client256-alb-overview.png)

**CloudWatch RequestCount - Sum, 1 minute - 256 client threads, ACK batch 1.**

![CloudWatch RequestCount - Sum, 1 minute, 256 client threads, ACK batch 1](output/tuning-parameters-assets/ack-1-client256-request-count.png)

**CloudWatch TargetResponseTime - Average, 1 minute - 256 client threads, ACK batch 1.**

![CloudWatch TargetResponseTime - Average, 1 minute, 256 client threads, ACK batch 1](output/tuning-parameters-assets/ack-1-client256-target-response-time.png)

---

## 4. Connection pool size

**What it is.** Each ingest keeps a pool of RabbitMQ publisher channels (`CHANNEL_POOL_SIZE`). A request borrows a channel, publishes, waits for the publisher confirm and returns the channel. In this implementation the pool holds channels on a shared connection, so the assignment's "connection pool size" corresponds to this setting. If the pool is too small, request threads wait for a channel, and a request can end with HTTP 503 (`broker publish not confirmed`) that the client retries with the same Idempotency-Key.

**Setup.** 2 ingests, 512 client threads, 500K events, consumer settings unchanged between the two runs. Only `CHANNEL_POOL_SIZE` changed, applied to both ingests with `deployment/set-ingest-pool.sh`.

| Metric | Pool 32 | Pool 64 |
|---|---:|---:|
| HTTP 503 responses (all retried successfully) | 133 | 78 |
| Total throughput | 4,713/s | 4,935/s |
| End-to-end throughput | 3,537/s | 3,506/s |
| HTTP P99 latency | 288 ms | 220 ms |
| Queue peak | 133,473 | 155,205 |
| Failed requests / duplicates | 0 / 0 | 0 / 0 |

| Pool | Evidence directory | Client window (UTC) |
|---:|---|---|
| 32 | `instrumented-500000-c512-20261108T084645Z` | 08:46:48 to 08:49:13 |
| 64 | `instrumented-500000-c512-20261108T092852Z` | 09:28:55 to 09:31:21 |

**Finding.** Doubling the pool lowered the 503 count from 133 to 78 and the HTTP P99 from 288 ms to 220 ms. End-to-end throughput stayed close while admission throughput increased and the queue peak rose. This is consistent with a downstream processing limit, but these two observations do not isolate storage, broker CPU or confirmation waiting as the sole cause. Another 2-ingest 512-thread run (`...T091512Z`) recorded only 40 503s, so the count varies from run to run and the 133 to 78 difference should be read as a modest improvement, not a precise effect. With 4 ingests at pool 64 and 512 threads there were no 503s.

**Decision.** Keep 64 on every ingest. These observations support additional channel headroom under high concurrency, but variation between repeated runs means that the reduction in 503s is not a precise causal estimate.

### Supplementary test: 256 client threads, pool 32 / 64 / 64 / 32

The 512-thread comparison above has one run per pool value. To check whether the difference is repeatable, and whether it comes from the pool or from drift between runs, the comparison was repeated at a lower load in the order 32, 64, 64, 32. Nothing above is replaced.

**Setup.** 2 ingests behind the ALB, 256 client threads, 500K events, seed 6650. Consumer unchanged: 10 workers, prefetch 128, ACK batch 16, DB batch 64, dynamic scaling off, EBS 6,000 IOPS. Only `CHANNEL_POOL_SIZE` changed, with `deployment/set-ingest-pool.sh`, and each switch was verified through `/health` (`producerChannelsAvailable` equal to the target on both ingests). The queue was empty at the start of every run. `/health` was read on both ingests after each run, before the next switch, so the publish-failure counters cover that run only. A separate 50K warm-up was not run (its monitor start failed on two unreachable hosts and it was skipped), so run 1 had only the client's own 32,000-event warm-up phase.

| Run | Pool | Evidence directory (`results/a2-ec2/`) | Client window (UTC) |
|---:|---:|---|---|
| 1 | 32 | `instrumented-500000-c256-20261109T015546Z` | 01:55:49 to 01:58:28 |
| 2 | 64 | `instrumented-500000-c256-20261109T020023Z` | 02:00:26 to 02:03:04 |
| 3 | 64 | `instrumented-500000-c256-20261109T020439Z` | 02:04:41 to 02:07:22 |
| 4 | 32 | `instrumented-500000-c256-20261109T020900Z` | 02:09:03 to 02:11:43 |

| Metric | Run 1 (32) | Run 2 (64) | Run 3 (64) | Run 4 (32) |
|---|---:|---:|---:|---:|
| End-to-end throughput | 3,189/s | 3,243/s | 3,174/s | 3,182/s |
| Admission (main phase) throughput | 5,512/s | 4,883/s | 4,582/s | 5,163/s |
| HTTP median | 43.1 ms | 43.3 ms | 46.3 ms | 45.1 ms |
| HTTP P95 | 62.1 ms | 92.5 ms | 104.3 ms | 68.0 ms |
| HTTP P99 | 102.1 ms | 266.9 ms | 266.6 ms | 126.4 ms |
| Max latency | 730 ms | 513 ms | 859 ms | 1,035 ms |
| Queue peak (all queues) | 120,028 | 71,085 | 53,300 | 93,811 |
| `ACQUIRE_TIMEOUT` / other failure counters | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Failed requests / application retries | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |

**Finding.**
- End-to-end throughput did not depend on the pool (3,174 to 3,243/s, within 2%); the consumer and RabbitMQ CPU (peaks 96 to 99%) were the limit, as in the 512-thread runs.
- The pool never ran out at this load: `ACQUIRE_TIMEOUT` stayed 0 and there were no 503s, so this is not a "pool too small" case.
- The two pool-64 runs had P99 of 267 ms, the two pool-32 runs 102 and 126 ms; the groups do not overlap. Admission throughput split the same way (pool 64: 4,883 and 4,582/s; pool 32: 5,512 and 5,163/s).
- Admission throughput and queue peak fell across runs 1 to 3, which alone could be drift. Run 4 returned to pool 32 and recovered (P99 126 ms, admission 5,163/s), so drift does not explain the difference.
- Limits: two runs per value, no confidence interval, and the cause was not isolated. A plausible explanation (more concurrent channels adding broker-side queueing while RabbitMQ CPU is saturated) is not directly measured here.

**How this fits the 512-thread result.** At 512 threads each ingest carries about 256 concurrent requests and pool 32 ran out of channels (133 503s versus 78 at pool 64). At 256 threads each ingest carries about 128 and neither pool ran out. Together the two tests suggest that the pool should match the concurrency per ingest: extra channels help when the pool is the limit, and in the unsaturated case a larger pool gave a worse P99 here. The decision to keep 64 therefore rests on the 512-thread, high-concurrency case; at 256 threads pool 32 was sufficient and had the lower tail latency.

**Where the bottleneck is.** This supplementary test does not change the storage conclusion. End-to-end throughput stayed near 3,200/s regardless of pool size, which matches the consumer-storage and RabbitMQ-CPU limit found in the IOPS steps (#7 to #8) in the Overall conclusion.

#### Screenshots for the 256-thread test

How to read them:
- CloudWatch charts use the **client window** shown in each title (the date range is typed into the console) with 1-minute buckets, so the first and last points are partial minutes, and the lines between points are interpolation. The y-axis range differs from chart to chart, so do not compare heights between runs by eye.
- ALB `TargetResponseTime` is the **average** time the targets took, about 14 to 16 ms in all four runs. It does not show the client-side P99 difference in the table above (102 and 126 ms at pool 32, 267 ms at pool 64); that comes from the client logs.
- The RabbitMQ Management overview shows the **last ten minutes** and sums all queues. The console labels its time axis in the browser's local time, which is UTC-8 here (17:58 on the axis is 01:58 UTC), so a screenshot can also contain the neighbouring run. The run's own peak is the one matching the queue peak in the table (about 120K, 71K, 53K and 94K).

**Run 1, pool 32, 01:55:49-01:58:28 UTC**

![Run 1, pool 32: ALB overview](output/tuning-parameters-assets/pool256-run1-pool32-alb-overview.png)

![Run 1, pool 32: RequestCount and TargetResponseTime](output/tuning-parameters-assets/pool256-run1-pool32-cloudwatch-metrics.png)

![Run 1, pool 32: RabbitMQ overview (peak about 120K)](output/tuning-parameters-assets/pool256-run1-pool32-rabbitmq.png)

**Run 2, pool 64, 02:00:26-02:03:04 UTC**

![Run 2, pool 64: ALB overview](output/tuning-parameters-assets/pool256-run2-pool64-alb-overview.png)

![Run 2, pool 64: RequestCount and TargetResponseTime](output/tuning-parameters-assets/pool256-run2-pool64-cloudwatch-metrics.png)

![Run 2, pool 64: RabbitMQ overview (run 1 peak at left, run 2 peak about 71K at right)](output/tuning-parameters-assets/pool256-run2-pool64-rabbitmq.png)

**Run 3, pool 64, 02:04:41-02:07:22 UTC**

![Run 3, pool 64: ALB overview](output/tuning-parameters-assets/pool256-run3-pool64-alb-overview.png)

![Run 3, pool 64: RequestCount and TargetResponseTime](output/tuning-parameters-assets/pool256-run3-pool64-cloudwatch-metrics.png)

![Run 3, pool 64: RabbitMQ overview (run 3 peak about 53K at right)](output/tuning-parameters-assets/pool256-run3-pool64-rabbitmq.png)

**Run 4, pool 32, 02:09:03-02:11:43 UTC**

The CloudWatch charts for this run were taken with the window extended to 02:14:22 to include the drain. There are two RabbitMQ screenshots: the first was taken **during** the run (ready 76,348, publish 5,360/s, still rising), the second after the queue drained.

![Run 4, pool 32: ALB overview](output/tuning-parameters-assets/pool256-run4-pool32-alb-overview.png)

![Run 4, pool 32: RequestCount and TargetResponseTime](output/tuning-parameters-assets/pool256-run4-pool32-cloudwatch-metrics.png)

![Run 4, pool 32: RabbitMQ overview during the run](output/tuning-parameters-assets/pool256-run4-pool32-rabbitmq-during.png)

![Run 4, pool 32: RabbitMQ overview after the drain (peak about 94K)](output/tuning-parameters-assets/pool256-run4-pool32-rabbitmq-drained.png)

### Screenshot evidence and scope

The pool-32 and pool-64 comparison above uses the **two-ingest** runs at **08:46:48-08:49:13** and **09:28:55-09:31:21 UTC**. No run-matched RequestCount/TargetResponseTime or RabbitMQ Management screenshot pair for those exact runs was located among the supplied files. Their original logs and recorded metrics remain linked below. The supplied screenshots included here instead show the later **four-ingest, pool-64, 500K/512-client reference**; they must not be interpreted as a visual before/after pool-size comparison.

[Pool 32 client output](output/assignment2-evidence/instrumented-500000-c512-20261108T084645Z/client-output.txt) | [Pool 32 metrics](output/assignment2-evidence/instrumented-500000-c512-20261108T084645Z/summary.json) | [Pool 64 client output](output/assignment2-evidence/instrumented-500000-c512-20261108T092852Z/client-output.txt) | [Pool 64 metrics](output/assignment2-evidence/instrumented-500000-c512-20261108T092852Z/summary.json)

**Four-ingest pool-64 reference:** 500K events, 512 client threads, ten Consumer workers; actual client window **15:00:30.011-15:03:20.129 UTC**. This run completed with zero final failures and zero application retries. That result reflects both four ingest instances and pool 64; it cannot isolate the effect of changing pool 32 to 64. The supplied ALB images select 15:00:30-15:03:20 UTC, with 60-second buckets.

**Supplied Load balancer metrics overview; 15:00:30-15:03:20 UTC.**

![Supplied Load balancer metrics overview](output/tuning-parameters-assets/pool64-four-ingest-alb-overview.png)

**Supplied RequestCount - Sum, one minute; 15:00:30-15:03:20 UTC.**

![Supplied RequestCount - Sum, one minute](output/tuning-parameters-assets/pool64-four-ingest-request-count.png)

**Supplied TargetResponseTime - Average, one minute; 15:00:30-15:03:20 UTC.**

![Supplied TargetResponseTime - Average, one minute](output/tuning-parameters-assets/pool64-four-ingest-target-response-time.png)

**Actual RabbitMQ Overview - Queued messages and Message rates, last ten minutes.** Captured at 15:02:46 UTC; client window 15:00:30-15:03:20 UTC. The chart can include preceding activity and aggregates all queues.

![RabbitMQ queue depth and message rates, four-ingest pool-64 reference](output/tuning-parameters-assets/pool64-four-ingest-rabbitmq.png)
### Supplied screenshot matching the two-ingest pool-64 run

The following original CloudWatch screenshot was supplied during the pool-size investigation. It shows the **Consumer EBS volume**, with `VolumeIOPSExceededCheck` and `VolumeThroughputExceededCheck`, over **2026-11-08 09:27-09:34 UTC**. This range contains the two-ingest **pool 64** client window **09:28:55-09:31:21 UTC**. It is run-matched storage-pressure evidence; it is not an ALB RequestCount/TargetResponseTime chart or a RabbitMQ queue screenshot.

![Consumer EBS IOPS and throughput exceeded checks during the two-ingest pool-64 test](output/tuning-parameters-assets/pool64-two-ingest-consumer-ebs-exceeded-checks.png)

`VolumeIOPSExceededCheck` is 1 around 09:30-09:31 while the throughput check remains 0. This supports an IOPS constraint during that workload, without quantifying the excess or isolating the causal effect of changing the publisher pool.

---


### Fresh two-ingest channel-pool repeat

[Complete repeat comparison, client output, system/queue metrics and original RabbitMQ screenshots](output/assignment2-pool-repeat-20261109T013054Z/comparison.md)

| Pool | Main HTTP/s | End-to-end/s | HTTP p99 ms | Retries | Final failures | Ready peak | Ready + Unacked peak | Mean lag ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 32 | 6,344.82 | 2,559.25 | 271.70 | 0.00 | 0.00 | 260,244.00 | 261,524.00 | 42,884.24 |
| 64 | 6,677.33 | 3,097.55 | 272.78 | 58.00 | 0.00 | 196,473.00 | 197,753.00 | 24,390.37 |

**Pool 32: 2026-11-09T01:32:39.401542+00:00 – 2026-11-09T01:35:58.165305+00:00 UTC.**

![Actual RabbitMQ queue depth and rates, pool 32](output/assignment2-pool-repeat-20261109T013054Z/evidence/instrumented-500000-c512-20261109T013223Z/screenshots/rabbitmq-20261109T013455650595Z.png)

![Actual RabbitMQ queue depth and rates, pool 32](output/assignment2-pool-repeat-20261109T013054Z/evidence/instrumented-500000-c512-20261109T013223Z/screenshots/rabbitmq-20261109T013612981091Z-final.png)


**Pool 64: 2026-11-09T01:37:53.138892+00:00 – 2026-11-09T01:40:37.698745+00:00 UTC.**

![Actual RabbitMQ queue depth and rates, pool 64](output/assignment2-pool-repeat-20261109T013054Z/evidence/instrumented-500000-c512-20261109T013739Z/screenshots/rabbitmq-20261109T013923823914Z.png)

![Actual RabbitMQ queue depth and rates, pool 64](output/assignment2-pool-repeat-20261109T013054Z/evidence/instrumented-500000-c512-20261109T013739Z/screenshots/rabbitmq-20261109T014051755312Z-final.png)

These new observations supplement the historical runs above. Both settings were tested sequentially against retained database state. The linked comparison includes all captured RabbitMQ screenshots; matching ALB captures are added separately.

## Overall conclusion

The results do not support a universal claim that every tuning parameter changes throughput by only a few percent. Prefetch and worker counts above ten showed no reliable completion-throughput benefit, while ACK batching showed modest observed gains. Channel-pool results depend on load and session: the new 512-client pair differed by 21.0% in end-to-end throughput, but did not reproduce the earlier retry reduction and neither setting met queue/lag targets. Several earlier high-load runs completed near 3,200 to 3,500/s; the new pair completed at 2,559 and 3,098/s. Consumer storage waiting and broker CPU pressure are supported candidates for downstream limits, without establishing one exclusive bottleneck across all observations.

Two earlier changes had much larger effects, and the order of the experiments separates them (single ingest, 500K events, 128 client threads unless noted):

| Step | Change | Ready peak | End-to-end throughput | Drain time | Consumer I/O wait mean / max | Disk utilisation mean / max |
|---|---|---:|---:|---:|---:|---:|
| #6 | Default batching (batch 32, prefetch 64, ACK batch 1), EBS 3,000 IOPS | 64,166 | 2,223/s | 30.5 s | 54% / 80% | 73% / 97% |
| #7 | Larger batch 64, prefetch 128, ACK batch 16, ACK flush 25 ms, EBS 3,000 IOPS | 94,252 | 2,198/s | 44.3 s | 57% / 83% | 75% / 99% |
| #8 | Same as #7, EBS raised to 6,000 IOPS | 0 | 2,874/s | 0.76 s | 21% / 27% | 46% / 58% |

Evidence for #6 to #8 (under `results/a2-ec2/`), all single ingest and 128 threads; the I/O wait, disk and CPU figures were recomputed from the monitor files within each client window:

| Step | Evidence directory | Client window (UTC) |
|---|---|---|
| #6 | `instrumented-500000-c128-20261108T054925Z` | 05:49:28 to 05:53:16 |
| #7 | `instrumented-500000-c128-20261108T063752Z` | 06:37:56 to 06:41:46 |
| #8 | `instrumented-500000-c128-20261108T065453Z` | 06:54:57 to 06:57:54 |

- **Grouping database writes (single ingest, 64 threads).** Merging up to 32 events per resort into one commit was tested with the same 100K workload before and after (old image, then new image, both 3,000 IOPS; evidence `instrumented-100000-c64-20261108T044946Z` and `...T052219Z`). The ready peak fell from 15,516 to 19, drain time from 13.2 s to 0.74 s, end-to-end throughput rose from 1,148/s to 1,253/s (+9%), mean consumer lag fell from about 3.8 s to 28 ms, and peak disk utilisation fell from 89% to 42%. The 500K run with batching (`...T052951Z`) had a ready peak of 0, 1,503/s end-to-end in 331.8 s and mean lag 17 ms, against 1,193/s (419 s) for the 500K run before optimisation, about +26%. That pre-optimisation 500K run is the first client log of this project and was not collected with the monitoring tools, so its queue and disk figures are not available.
- **Larger batch, prefetch and ACK batch at 3,000 IOPS (#6 to #7).** No improvement. The consume rate stayed near 2,200/s and peak disk utilisation stayed at 97 to 99%, so the disk, not these parameters, was the limit.
- **EBS IOPS 3,000 to 6,000 (#7 to #8).** Application settings were identical, so this isolates the storage effect: the ready peak fell to 0, drain time fell from 44.3 s to 0.76 s, I/O wait fell from 57% to 21%, and disk writes rose from about 2,640/s to about 4,630/s on average. This is the controlled evidence that consumer storage IOPS was the main bottleneck at that stage.
- **After 6,000 IOPS.** At 512 client threads the end-to-end rate still plateaued near 3,500/s. For the 2-ingest, 512-thread run `instrumented-500000-c512-20261108T092852Z` (client window 09:28:55 to 09:31:21 UTC), the CloudWatch graph of the consumer volume (1-minute data, 09:27 to 09:34 UTC) shows `VolumeIOPSExceededCheck` at 1 from about 09:30 to 09:31, returning to 0 by about 09:32, while `VolumeThroughputExceededCheck` stayed 0 throughout. The graph draws straight lines between 1-minute points, so the sloped edges are interpolation, not gradual change. This indicates demand exceeded the provisioned IOPS while the run was under load, without a detected throughput breach, and it does not show by how much. We did not capture the same graph for the 4-ingest runs.

The supplied Consumer-volume CloudWatch screenshot is embedded in **Section 4, "Supplied screenshot matching the two-ingest pool-64 run"** above.

 We could not raise IOPS further because the lab's service control policy explicitly denies `ec2:ModifyVolume`. RabbitMQ CPU near 95% to 100% is the next resource to watch, but these runs do not show that it alone limits throughput.

Final configuration: prefetch 128, 10 consumer workers, ACK batch 16, channel pool 64, DB batch 64 with a 10 ms flush trigger.

At 256 and 512 client threads the queue depth exceeded the 1,000 target in every configuration tested, and the consumer lag target of 100 ms was not met in those runs. At 128 client threads both were met in the batch-16 run above. The backlog shows that admitted load exceeded completed-processing capacity under those conditions. Storage and broker measurements support downstream pressure, but the runs do not isolate a single cause or establish that tuning alone can meet the targets.
