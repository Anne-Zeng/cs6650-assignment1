# Assignment 2 verification and evidence

All scripts use Python3 standard library. No script deploys AWS resources or stops
hosts. Keep raw output and record the code revision/configuration with each run.
Assignment1 measurements do not reuse  as Assignment2 evidence.

## Business correctness and connection locality

After starting the stack with the official table:

```bash
python3 monitoring/verify_pipeline.py \
  --ingest-url http://localhost:8080 --ingest-url http://localhost:8082 \
  --consumer-url http://localhost:8081 \
  --lift-file deployment/config/lift_vertical.json --day 366
```

For the explicit local demo stack, replace `--lift-file ...` with `--demo-lifts`.
The verifier checks that its chosen day is unused; choose another day1–366 for
subsequent tests. It opens subscriptions on both nodes, publishes100 distinct
events and20 cross-node duplicate requests, independently computes totals and
Top20 from all40 lift values, and checks both authoritative HTTP snapshots and
both nodes' WebSocket results. It exercises ties and more skiers than Top-N.
Use a disposable test environment or an unused day, not production ski data.
This proves the tested scenarios, not crash recovery or large-load performance.

For restart recovery, keep consumer/broker volumes, stop and restart only the
consumer, query the same snapshot again and replay existing event IDs; totals
must not change. Unit tests also reopen durable stores. For ingest failure,
stop one node and observe remaining-target requests and subscriber reconnects
through the ALB. Keep logs showing when failure started, recovery completed,
and which snapshot versions were observed. For broker recovery, stop/restart
RabbitMQ while sending a small load with stable IDs; inspect client retries,
readiness recovery and final business totals. Do not call a failed run lossless
just because its queue becomes empty.

## Queue, app and host monitoring

For the currently configured three-host EC2 deployment, `ec2_monitor.py` runs
read-only probes over SSH without installing an agent or opening management ports:

```bash
python3 monitoring/ec2_monitor.py --duration 1800 --interval 2 \
  --output results/a2-ec2/MY_RUN-monitor
```

The public host addresses are declared in `ec2_monitor.py`; update them after EC2
public-IP changes and add ingest hosts when scaling. It verifies existing SSH host
keys (the broker uses its previously trusted IP as a host-key alias), uses the
project key, and requires existing passwordless sudo on the course instances.
RabbitMQ credentials are read only on the broker; they are never printed or saved
in the returned data. `status.json` reports samples/errors for each host. Each host
gets JSONL raw data, host CSV (CPU, memory, RX/TX rates) and app CSV. The broker app
CSV includes raw-queue depths/rates, while consumer JSON/CSV includes lag metrics.
The bounded process stops after the requested duration. No services are restarted.
Filter samples to the actual HTTP start/end timestamps from the paired client CSV;
do not include the idle monitoring tail in load averages. Keep RabbitMQ console
screenshots and ALB CloudWatch/access-log evidence separately. T3 CPU credit balance
is a CloudWatch metric, not a Linux host counter.

```bash
export RABBITMQ_USER=skitrak
# Set RABBITMQ_PASSWORD in the shell without putting it into a command-line argument.
python3 monitoring/collect.py --duration 300 --interval 1 \
  --rabbit-url http://localhost:15672 --consumer-url http://localhost:8081 \
  --ingest-url http://localhost:8080 --ingest-url http://localhost:8082 \
  --output results/a2-monitoring/run1
```

Run on the EC2 hosts to collect Linux `/proc` CPU ticks, memory and RX/TX byte
counters; those host fields describe the machine running the script, not remote
hosts. Run one collector on each host or use CloudWatch for the other machines.
To derive host CPU%, take deltas of total and idle/iowait ticks; derive network
bytes/sec from byte deltas divided by elapsed seconds. JSONL preserves raw
counters and full consumer/ingest metrics. CSV includes aggregate raw-queue
ready/unacked depths and publish/delivery/ack rates. API errors remain errors,
not false zero-depth samples. Capture RabbitMQ console graphs as the assignment
also specifically requests screenshots. Render the recorded CSV with
`python3 monitoring/plot_queue.py results/a2-monitoring/run1.csv` after installing
matplotlib in a virtual environment. Raw `.dead` queues are recorded separately
from the ten main resort queues.

Queue-depth threshold is not the same as the configured queue limit: a capacity
limit of100000 protects the broker while the measured operating target remains
<1000. Set TTL longer than the entire test/recovery window. Monitor dead letters
and expirations; those do not count as successful processing. Consumer lag p95/p99
and receivedAt−serverTimestamp use different timing boundaries. Synchronize EC2
clocks and do not clamp negative cross-host timestamp differences into fake zero
latencies. A stable queue graph alone does not prove<100ms lag or no loss.

## ALB distribution, stickiness and failover

```bash
python3 monitoring/verify_alb.py http://ALB_DNS --expected-targets 2 \
  --output results/alb-2-instances.json
```

The first phase sends requests without shared cookies and records `X-Server-Id`
counts. The second preserves the `AWSALB` cookie and checks repeated requests
stay on one target. The cookie file can be reused after deliberately stopping
that target and waiting for unhealthy detection; pass
`--expect-target-removed TARGET_SERVER_ID` on the next invocation. For actual
WebSocket failover, also preserve the load-client reconnect logs and final
snapshot evidence: cookie stickiness alone does not prove socket recovery.

CloudWatch `RequestCountPerTarget` is a target-group aggregate, not a separate
per-instance count. For request distribution, use ALB access logs grouped by
`target:port` and/or per-server counters. Keep the ALB CloudWatch dashboard and
access-log evidence alongside `verify_alb.py` output. Use
`monitoring/aws_metrics.sh` with explicit UTC run boundaries to save CloudWatch
metrics, and `monitoring/summarize_alb_logs.py FILES...` to count swipe requests
per actual target from downloaded ALB access logs. The optional bucket in the
CloudFormation template requires the official ELB delivery bucket policy.

## Required load matrix

Instructor clarification uses consumer configurations **5/10/15/20**, Top-N20,
and client configurations64/128/256/512. Ten queues mean at most ten ordered
aggregation lanes, so performance is expected to flatten beyond ten useful lanes.
Record configured threads, active workers and dynamic-scaling state separately.
For controlled thread sweeps set `CONSUMER_DYNAMIC_SCALING=false` and restart the
consumer with each requested `CONSUMER_THREADS`, retaining its persistent volume.
A separate dynamic-scaling run should show actual worker-count changes under load.

For each deployed consumer configuration, run:

```bash
python3 monitoring/run_benchmarks.py --base-url http://ALB_DNS \
  --consumer-url http://CONSUMER_PRIVATE_IP:8081 --instances 2 --consumer-threads 10
```

The script sweeps client threads. Repeat on1,2,4 ingest instances with500000 events;
then run the4-instance stress case with `--events 1000000`. The `--instances` argument labels the actual deployment; the script verifies
`--consumer-threads` against live consumer metrics before every run. These
arguments do not create instances or remotely change threads. Each run gets
its own directory containing client logs, CSVs, final snapshots, configuration
and before/after metrics. The small `local_matrix.sh` performs a
Docker-only consumer-thread sweep and is explicitly not cloud evidence.

For each run collect client runtime/throughput/failures/retries/latency percentiles,
queue peak and average depth and producer/consumer rate, per-host CPU/memory/network,
ALB distribution, and final real leaderboard snapshots. Compare single-instance
and load-balanced results using identical input seed and broker/consumer settings.
State bottlenecks and failures honestly. If any target is missed, report measured
values and the limiting resource rather than asserting all requirements passed.

## Automated local recovery checks

After the normal official-table local stack is running, with no concurrent load:

```bash
python3 monitoring/verify_local_recovery.py --day 365 --resort 3
```

This tool refuses non-loopback endpoints and targets only the `skitrak-a2`
Docker Compose project's consumer/RabbitMQ services. It verifies first/last
subscriber binding transitions, consumer restart with queued events and duplicate
requests, broker outage503 responses, producer/backplane recovery while sockets
stay open, same-ID replay and new events after recovery. It independently checks
final totals and both servers' real WebSocket snapshots. The selected resort/day
must be unused. Services are restarted in `finally`; persistent volumes are never
deleted. Evidence is written to `results/a2-local/recovery-verification.json`.
Do not run it concurrently with the benchmark client or another fault test.
