# Metrics buffering and controlled experiments

## What changed / 功能说明

- `AttemptBuffer`: each sender task owns fixed-size primitive-array blocks (1024 records each). Growth is per block, not one Sample and queue node per request. The shared **event** queue remains unchanged.
- `SwipeSender`: `shared` preserves the original Instant + ConcurrentLinkedQueue path; `buffered` writes millisecond wall timestamps and nanosecond durations to its own buffer; `off` skips HTTP sample recording and measurement clocks only. Transport counters, retries, event counts and WebSockets remain enabled.
- `SwipeMetrics`: the main thread registers buffers before submitting their tasks. After all workers finish (`Future.get`), it reads buffers directly without copying records back into a shared Sample queue. Sorting/CSV conversion runs after ingest timing.
- `LoadClient`: defaults to `buffered`, validates attempt count against transport count, and prints main throughput. Parameters 6 and 7 select mode and event seed. Buffer records include failed attempts and all retries.
- Existing comments, Part 1 and server behavior are retained. Buffer arrays are not thread safe: exactly one task writes each buffer, and readers wait until completion.

## Run one configuration

Program arguments (replace current EC2 IP):

```text
http://<CURRENT_IP>:8080/ski-servlet 500000 128 1000 10 buffered 6650
```

Use `shared` for the preserved baseline, `off` only for diagnostic comparisons.
Small check: `5000 32 10 2 buffered 6650` after the base URL.
Do not reuse old benchmark filenames. Buffered CSV has the same columns, but wall timestamps are millisecond resolution; elapsed latency still uses nanoTime. CSV rows are grouped by task, not globally chronological. The existing plotter bins by actual completion timestamp and does not require sorted rows.

## Build and compare (Mac terminal)

From `client-part2`, use Java 11 for compilation/tests and the benchmark JVM:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 11)
mvn clean package
mvn dependency:build-classpath -Dmdep.outputFile=target/dependency-classpath.txt
python3 tools/compare_metrics.py --help
```

Explicitly start the remote experiment only when the current EC2 address is known and Tomcat is ready:

```bash
python3 tools/compare_metrics.py \
  --java "$JAVA_HOME/bin/java" \
  --base-url http://<CURRENT_IP>:8080/ski-servlet \
  --events 500000 --threads 128 --warmup 1000 --resorts 10 --rounds 3
```

This is **nine full runs**, sequentially, with order shared/buffered/off, buffered/off/shared, off/shared/buffered. All modes use the same generator seed, JVM/heap, threads, pool, WebSockets, and server address. Keep the deployed WAR fixed and record its SHA-256 separately. Avoid other load clients during the experiment. Do not claim a performance gain until this experiment finishes.

The script saves configuration (including Git dirty state), actual public health response, raw logs, GC logs, measurements.json and SUMMARY.md under `results/experiments/<UTC timestamp>/`. HTTP CSV is intentionally absent for off; push CSV remains enabled in all modes. CPU allocation profiling is not performed: GC totals are supporting measurements, not proof of allocation rate or the cause of every throughput gap. Shared→buffered also changes timestamp allocation/resolution, so the effect is the whole recording strategy, not queue contention alone.

## Health / EC2 evidence still required

Capture the actual public `/health` request + response (HTTP 200 and status UP), alongside a console screenshot showing the instance Running with status checks passed, current IP, ID/type and us-west-2 location. A localhost response is not remote deployment evidence. The benchmark script preserves text evidence; it cannot capture AWS console state. Do not edit or synthesize screenshots.

## Report wording before measurements

The per-task buffering implementation and correctness checks are local evidence only. No new remote improvement has yet been measured. Existing Apache results are historical; retain their original version scope. After the experiment report the median and range for each mode, retries/failures, observed percentage changes and the unchanged environment. If differences overlap or are small, say so rather than attributing the entire Little's Law gap to metrics.
