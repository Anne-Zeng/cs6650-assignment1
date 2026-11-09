# CS6650 Assignment 2 — Test Results and Analysis

Submission report draft. This file records verified results for inclusion in the
final submission PDF. Architecture, other experiment comparisons, ALB screenshots,
and the final repository reference will be assembled separately; this is not yet
the complete submission.

## Stable operating configuration: four ingests, 500K swipes

The stable-load experiment used four ingest servers behind the Application Load
Balancer, 128 client ingest threads, ten resorts, Top-N=20, and 20 fixed consumer
threads with automatic worker scaling disabled. The consumer used a maximum
database batch size of 64, a 10 ms batch-flush trigger, prefetch 128, and ACK batch
size 16. SQLite retained FULL synchronous durability. Consumer aggregation had at
most ten-way parallelism because each resort retained a single ordered lane.
The Consumer EBS volume was configured for 6000 IOPS and 125 MiB/s.

Evidence directory:
`results/a2-ec2/instrumented-500000-c128-20261108T125240Z`.
The client ran on November 8, 2026, from 12:52:43 to 12:55:47 UTC
(04:52:43–04:55:47 Pacific Standard Time).

| Measurement | Verified result |
| --- | ---: |
| Successful swipes | 500,000 |
| Final HTTP failures | 0 |
| Application HTTP retries | 0 |
| Main-phase HTTP throughput | 3249.34 swipes/s |
| Overall HTTP ingestion throughput, including warmup | 2798.69 swipes/s |
| End-to-end throughput, including drain/final verification | 2785.50 swipes/s |
| Additional drain and final WebSocket verification time | 0.781 s |
| Mean HTTP attempt latency | 39.053 ms |
| HTTP attempt p95 / p99 | 51.260 ms /92.008 ms |
| Observed aggregate Ready peak, ten raw resort queues | 0 |
| Observed aggregate Unacked peak, ten raw resort queues | 253 |
| Observed aggregate Ready+Unacked peak, ten raw resort queues | 253 |
| Mean consumer lag attributable to this run | 25.61 ms |
| Consumer processed count before / after | 13,000,304 /13,500,304 |
| Increase in consumer processed count | 500,000 |
| Increase in duplicate count / failed aggregation attempts | 0 /0 |
| Final Ready / in-flight / pending outbox | 0 /0 /0 |
| Maximum sampled dead-letter Ready count | 0 |
| WebSocket updates received, including initial snapshots | 920 |
| WebSocket reconnections | 0 |
| Monitor samples per host / application collection errors | 93 /0 |

### Queue-profile interpretation and target assessment

This configuration met the queue-depth target within the observed samples: even
including unacknowledged messages, the ten raw resort queues peaked at 253,
below 1000. The mean ingest-to-consumer lag for this run was 25.61 ms, below 100 ms.
The consumer processed-count increase exactly matched the 500,000 successful
swipes; the duplicate counter did not increase, and all pending work drained.
These counts support no observed loss in this experiment. Separate deterministic
functional tests verify vertical totals, Top-N results, and duplicate replay.

The RabbitMQ Overview chart fluctuates during load, but this is not evidence by
itself of consumers falling behind. Its Total series includes Ready and Unacked
messages across all queues. Batch processing and acknowledgements can contribute
to fluctuations in unacknowledged work. The dedicated raw-resort measurements
showed no sampled Ready backlog, and the workload drained without a persistent
queue. Publish and delivery curves were closely aligned for most of the run.

The first screenshot was supplied as the earlier capture and the second as the
post-exit capture. Both show zero current rates and counts at capture time; the
historical curves provide the load-period evidence. They should not be described
as proof that the first screenshot was captured while requests were still active.

Screenshot files:

- `results/a2-ec2/submission-screenshots/rabbitmq-overview-first.png`
- `results/a2-ec2/submission-screenshots/rabbitmq-overview-after-exit.png`

The Overview chart aggregates all RabbitMQ queues, including backplane queues.
Its scope differs from the ten raw resort queues in the table, although the
observed values are consistent in this experiment.

### Measurement boundaries

Queue peaks are sampled observations, not a continuous guarantee between
samples. Consumer lag is measured from ingest event timestamp to consumer
processing and depends on host clock synchronization. The run-specific mean
was calculated by subtracting the pre-run cumulative lag sum and sample count
from the post-run totals; it is not the dashboard's lifetime average. The saved
lag percentile fields include previous events and must not be presented as
this run's independent p95/p99. A mean below 100 ms does not establish that every
event had lag below 100 ms.

WebSocket snapshot-to-receipt differences are a separate cross-host measurement
that includes the Mac client's clock offset. Negative values are not valid
one-way network latency measurements and are not used to claim that the
consumer-lag target passed.

This is a workload-specific stable result, not a claim that all 128-thread
configurations or all higher loads met the targets. Earlier 256/512-client-thread
and 1M stress runs developed substantial downstream backlog. Their failures to
meet queue/lag targets must remain visible in the final performance comparison.

## Stress configuration: four ingests, 1M swipes, 512 client threads

The screenshot-backed stress run used four ingests, 512 client threads, 20 fixed
consumer threads, and the same downstream configuration as the stable run.
Evidence directory:
`results/a2-ec2/instrumented-1000000-c512-20261108T130828Z`.
The client ran from 13:08:31 to 13:13:23 UTC on November 8, 2026
(05:08:31–05:13:23 Pacific Standard Time).

| Measurement | Verified result |
| --- | ---: |
| Successful swipes / final failures | 1,000,000 /0 |
| HTTP 503 attempts, retried successfully | 2 |
| Main-phase HTTP throughput | 7233.35 swipes/s |
| Overall HTTP ingestion throughput | 5976.64 swipes/s |
| End-to-end throughput | 3471.64 swipes/s |
| Additional drain and final verification | 120.675 s |
| Mean HTTP attempt latency /p99 | 69.531 ms /201.416 ms |
| Observed aggregate raw-resort Ready peak | 417,048 |
| Observed aggregate raw-resort Ready+Unacked peak | 418,328 |
| Run-specific mean consumer lag | 52.684 s |
| Processed count before /after | 13,500,304 /14,500,304 |
| Processed-count increase /duplicate-count increase | 1,000,000 /0 |
| Final Ready /in-flight /pending outbox | 0 /0 /0 |
| Maximum sampled dead-letter Ready count | 0 |
| Consumer application-metric collection errors | 36 |

The first screenshot shows 303,151 Ready messages and 1,279 Unacked messages while
the displayed publish rate is 7642/s and the consumer ACK rate 3951/s. It therefore
shows genuine backlog growth, rather than only small fluctuations in unacknowledged
work. The later screenshot shows the accumulated backlog draining after publishing
stops, eventually reaching zero. Independent raw-queue monitoring observed a peak
of 417,048 Ready messages. These are sampled peaks, not exact continuous maxima.

Screenshots:

- `results/a2-ec2/submission-screenshots/rabbitmq-1m-stress-during-load.png`
- `results/a2-ec2/submission-screenshots/rabbitmq-1m-stress-drained.png`

The run completed with no count discrepancy, but **failed the queue-depth and
consumer-lag performance targets**. HTTP acceptance substantially exceeded
sustainable downstream processing; fast HTTP responses alone did not establish
pipeline stability. The 36 consumer-metric collection errors must be disclosed.
The lag mean above is derived from complete cumulative endpoint counters, not
an average of only the successful intermediate polling samples. This experiment
complements the stable 128-client-thread result; it does not replace or invalidate
that result. EBS IOPS saturation was demonstrated in an earlier experiment, but
these screenshots alone cannot isolate storage from all other bottlenecks.

## ALB monitoring, distribution, stickiness, and failover

The supplied ALB console graphs cover 12:50–13:15 UTC, including the stable 500K
run and the 1M stress run described above. RequestCount shows two distinct load
periods. TargetResponseTime shows higher target response times during the stress
period. The screenshots do not expose their aggregation statistic or period, so
the report does not infer exact request rates or percentile latencies from them.
TargetResponseTime measures the target response phase, not complete client
latency or consumer processing lag. Consequently, modest ALB response times can
coexist with the substantial consumer backlog observed in the stress test.

The target-group screenshot identifies `skitrak-ingest-tg`, HTTP port 8080, its
association with `skitrak-alb`, and four registered instances marked Healthy:

| Ingest | EC2 instance ID | Zone |
|---|---|---|
|1|i-0d12f2707bcc779d5|us-west-2b|
|2|i-06948d3fca4be453a|us-west-2a|
|3|i-03c2a5b0bae438fd1|us-west-2b|
|4|i-04c845f4c06b8904a|us-west-2b|

These health states were observed at screenshot capture time, not continuously
throughout every historical run. Console screenshots are saved as:

- `results/a2-ec2/submission-screenshots/alb-request-count.png`
- `results/a2-ec2/submission-screenshots/alb-target-response-time.png`
- `results/a2-ec2/submission-screenshots/alb-four-healthy-targets.png`

The console attribute screenshots confirm an ALB connection idle timeout of
120 seconds and enabled target-group stickiness using a load-balancer-generated
cookie for five minutes (300 seconds). Evidence is preserved in
`submission-screenshots/alb-idle-timeout-120s.png` and
`submission-screenshots/alb-cookie-stickiness-300s.png` under `results/a2-ec2/`.
These configuration observations complement the cookie-routing test.

The target-group health-check screenshot confirms HTTP on the traffic port,
path `/health`, interval 30 seconds, timeout 5 seconds, healthy threshold 2,
unhealthy threshold 3, and success code 200. These match the assignment
requirements. Evidence: `results/a2-ec2/submission-screenshots/alb-health-check-settings.png`.

The aggregate RequestCount graph is not per-instance distribution evidence.
Independent fresh-session probes in `alb-four-ingest-verification.json` recorded
51 requests to ingest-1,50 to ingest-2,50 to ingest-3, and49 to ingest-4, out of 200.
Ten consecutive cookie-bearing requests all reached ingest-4; AWSALB and
AWSALBCORS cookies were present. The corrected verification script distinguishes
an unperformed failure test from a passed one.

Live WebSocket failure evidence is in `live-websocket-failover.json`. Stopping
ingest-3 disconnected its existing subscription. The Python harness retried a
new WebSocket through ALB and reached ingest-4 in 2.981 seconds measured from the
start of the stop operation. One lift 21 event before the failure produced 267 feet;
a second after reconnection produced 534 feet with exactly two events processed.
The stopped ingest was restored and all four targets resumed routing. This
demonstrates harness-managed reconnection and state bootstrap; it is not a test
of the Java load client's automatic reconnect implementation.

## Local regression evidence

The final local Java11 Maven reactor verification completed successfully with
61 tests, 0 failures, 0 errors, and 0 skipped tests. Reports and the build log are
preserved in `results/a2-local/final-verification/`. Earlier real-RabbitMQ local
integration tests and restart tests are recorded separately in
`results/a2-local/batched-pipeline.json` and `batched-recovery.json`.
Local functional evidence does not replace EC2 performance evidence.


## Rebuilt per-run evidence archive

All 28 completed instrumented runs have been reprocessed into
`output/assignment2-evidence/index.html`. Each run page includes the complete
original client log, latency/runtime/throughput/failure/retry summary, RabbitMQ
ready/unacked/total depth and publish/delivery/ACK rate curves, CPU/memory/physical
network curves, and application request-counter distribution. The archive includes
`all-runs.csv`, `all-runs.json`, `performance-analysis.md`, and `EVIDENCE_GAPS.md`.

These reconstructed charts come from recorded measurements and are not console
screenshots. Existing run-specific RabbitMQ screenshots cover the final stable
500K/c128 run and final 1M/c512 run; captures for earlier experiments are not
identified. The archive explicitly records collection errors and configuration
differences. It does not repair historical gaps or claim all performance targets
were achieved. Original benchmark data remains under `results/a2-ec2/`.


## Repeat verification: five consumer workers

The repeated four-ingest500K/c128 run at
`results/a2-ec2/instrumented-500000-c128-20261108T134214Z` verified five fixed
consumer workers throughout, dynamic scaling disabled, prefetch128, database
batch maximum64 and ACK batch16.

| Metric | Original5-worker run (11:51UTC) | Repeat (13:42UTC) |
|---|---:|---:|
| End-to-end throughput, events/s | 2839.37 | 2724.34 |
| Ready peak, direct RabbitMQ sampling | 6309 | 27 |
| Mean consumer lag, ms | 581.67 | 35.84 |

The repeat processed500000 additional events, duplicate-count delta0, HTTP
failures0, retries0, with final ready/in-flight/outbox0. Raw queues peaked at
268 unacknowledged and295 total messages. Consumer and RabbitMQ collection had
no application errors. This run meets the queue target in observed samples and
the mean-lag target. Both runs must be retained; the original result does not
establish that five workers always cause a backlog.

The repeat admitted events slightly more slowly, had lower sampled Consumer I/O
wait (about18% versus33% across the whole recording), and ran against a later
database/cache state. These observations do not isolate a single cause.

Screenshot evidence is preserved in the repeat directory under `screenshots/`.
RabbitMQ Overview includes all queues. Its second capture still shows13 unacked
and nonzero rates, so it is a near-end capture, not proof of complete drain. The
final monitor measurements establish drain. The ALB capture shows only a partial
time window/data through approximately13:43UTC, while the client finished at
13:45:24UTC; obtain a full-window capture before using it as full-run evidence.
