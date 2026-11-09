# Tuning Parameters

This section records the four tuning parameters required by Assignment 2: prefetch count, consumer concurrency, batch ack size and connection (channel) pool size. Each experiment changed one parameter and kept the others fixed. Every run was a 500K-swipe run through the ALB with seed 6650, Top-N = 20 and SQLite `synchronous=FULL`. All times are UTC, 2026-11-08.

**How to read the results.** Each configuration was run once. The database grew by 500K rows per run, so later runs started with a larger database. Differences of a few percent are within run-to-run noise and are not claimed as significant. The bottleneck throughout was the downstream pipeline (consumer disk writes and RabbitMQ CPU), not the ingest servers.

## Summary

| Parameter | Environment variable | Values tested | Value kept | Effect on end-to-end throughput |
|---|---|---|---|---|
| Prefetch count | `PREFETCH_COUNT` (consumer) | 64 / 128 / 256 / 512 | 128 | None measurable (3,244 to 3,314/s, within 2%) |
| Consumer concurrency | `CONSUMER_THREADS` (consumer) | 5 / 10 / 15 / 20 | 10 | 5 workers were slowest; 10, 15 and 20 were equal. Only 10 aggregation lanes can be used. |
| Batch ack size | `ACK_BATCH_SIZE` (consumer) | 1 / 16 | 16 | +6% at 128 threads, +4% at 256 threads |
| Connection pool size | `CHANNEL_POOL_SIZE` (ingest) | 32 / 64 | 64 | Fewer 503 retries and lower HTTP P99; no change in end-to-end throughput |

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

**Screenshots.**

> *[Insert: RabbitMQ management console, queue depth and message rates during the prefetch 64 run, 21:49 to 21:52 UTC]*
>
> *[Insert: RabbitMQ management console, queue depth and message rates during the prefetch 512 run, 22:01 to 22:04 UTC]*
>
> *[Insert: CloudWatch CPU or ALB metrics for the prefetch sweep, 21:30 to 22:05 UTC]*

---

## 2. Consumer concurrency

**What it is.** `CONSUMER_THREADS` is the number of consumer worker threads. Each of the ten resort queues is aggregated by a single lane, one resort at a time, to preserve per-resort ordering. Because there are only ten resorts, at most ten lanes can run in parallel. The configured thread count can exceed ten, but the reported `maxAggregationParallelism` stays at ten. This limit is about CPU parallelism in the consumer. It is separate from the EBS IOPS limit discussed in the overall conclusion: more lanes cannot help when all lanes are already waiting on the same disk.

**Setup.** 4 ingests, 128 client threads, 500K events, prefetch 128, `ACK_BATCH_SIZE=16`, `STORE_BATCH_SIZE=64`. `CONSUMER_DYNAMIC_SCALING=false` and the consumer was restarted with each thread count using `monitoring/set_consumer_threads.py`. Dynamic scaling was disabled, so this sweep does not evaluate adaptive scaling.

| Consumer threads | Main HTTP throughput | End-to-end throughput | Ready peak | Mean consumer lag | Failed / retries |
|---:|---:|---:|---:|---:|---:|
| 5 | 2,605/s | 2,276/s | 138 | 42.4 ms | 0 / 0 |
| 10 | 3,155/s | 2,651/s | 446 | 35.0 ms | 0 / 0 |
| 15 | 3,032/s | 2,643/s | 1,059 | 38.5 ms | 0 / 0 |
| 20 | 3,036/s | 2,621/s | 111 | 39.4 ms | 0 / 0 |

| Threads | Evidence directory | Client window (UTC) |
|---:|---|---|
| 5 | `instrumented-500000-c128-20261108T151146Z` | 15:12:00 to 15:15:45 |
| 10 | `instrumented-500000-c128-20261108T151722Z` | 15:17:38 to 15:20:52 |
| 15 | `instrumented-500000-c128-20261108T152227Z` | 15:22:40 to 15:25:52 |
| 20 | `instrumented-500000-c128-20261108T152726Z` | 15:27:39 to 15:30:53 |

These runs are recorded in `output/assignment2-fresh-20261108T135433Z/`. The lag value is the mean for each run.

**Finding.** Five workers were the slowest, about 14% lower end-to-end throughput than ten. Ten, fifteen and twenty workers performed the same within noise, as expected because only ten ordered lanes exist. Extra workers added no throughput. This sweep ran at 128 client threads with 6,000 IOPS, a load that did not saturate the consumer, so it shows the lane cap but cannot show how the consumer behaves when the disk is the limit.

**Decision.** Keep 10 workers, one per resort queue.

**Screenshots.**

> *[Insert: consumer `/metrics` or CloudWatch consumer CPU for the 5-thread run, 15:12 to 15:16 UTC]*
>
> *[Insert: consumer `/metrics` or CloudWatch consumer CPU for the 10-thread run, 15:17 to 15:21 UTC]*
>
> *[Insert: consumer `/metrics` showing `configuredThreads` and `maxAggregationParallelism` for 15 or 20 threads]*

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

**Screenshots.**

> *[Insert: RabbitMQ console, queue depth and ack rate, 128 threads with ACK batch 1, 21:11 to 21:16 UTC]*
>
> *[Insert: RabbitMQ console, queue depth and ack rate, 128 threads with ACK batch 16, 21:16 to 21:20 UTC]*
>
> *[Insert: CloudWatch CPU for the consumer and RabbitMQ instances, 256 threads, ACK batch 16 (21:31 to 21:35 UTC) and ACK batch 1 (21:36 to 21:40 UTC)]*

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

**Finding.** Doubling the pool lowered the 503 count from 133 to 78 and the HTTP P99 from 288 ms to 220 ms. End-to-end throughput did not change, because the consumer and RabbitMQ were already saturated, and the queue peak was higher because requests were accepted faster. Another 2-ingest 512-thread run (`...T091512Z`) recorded only 40 503s, so the count varies from run to run and the 133 to 78 difference should be read as a modest improvement, not a precise effect. With 4 ingests at pool 64 and 512 threads there were no 503s.

**Decision.** Keep 64 on every ingest. The cost is small, and it removed most publish waits at high thread counts.

**Screenshots.**

> *[Insert: CloudWatch ALB `HTTPCode_Target_5XX_Count` and `TargetResponseTime`, pool 32 run, 08:46 to 08:50 UTC]*
>
> *[Insert: CloudWatch ALB `HTTPCode_Target_5XX_Count` and `TargetResponseTime`, pool 64 run, 09:28 to 09:32 UTC]*

---

## Overall conclusion

None of the four parameters moved end-to-end throughput by more than a few percent once the system was past its early bottlenecks. Throughput stayed near 3,300 to 3,500/s because the consumer's SQLite writes (disk I/O wait around 36 to 41%, disk utilisation up to about 90%) and RabbitMQ CPU (peaks near 100%) were saturated.

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

> *[Insert screenshot: CloudWatch, consumer volume `vol-09e6260de8702d150`, `VolumeIOPSExceededCheck` and `VolumeThroughputExceededCheck`, 2026-11-08 09:27 to 09:34 UTC]*

 We could not raise IOPS further because the lab's service control policy explicitly denies `ec2:ModifyVolume`. RabbitMQ CPU near 95% to 100% is the next resource to watch, but these runs do not show that it alone limits throughput.

Final configuration: prefetch 128, 10 consumer workers, ACK batch 16, channel pool 64, DB batch 64 with a 10 ms flush trigger.

At 256 and 512 client threads the queue depth exceeded the 1,000 target in every configuration tested, and the consumer lag target of 100 ms was not met in those runs. At 128 client threads both were met in the batch-16 run above. These limits come from the downstream storage and broker capacity, not from these tuning parameters.
