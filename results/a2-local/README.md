# Assignment 2 local verification

These are local Docker functional results, not the required EC2/ALB benchmarks.
The official 40-lift table is used, demo mode is false, and Top-N is20.

## Build and regression tests

Initial Java11 full Maven reactor `verify`: **51 tests,0 failures,0 errors,0 skipped**.
The latest saved Surefire reports now contain **61 tests,0 failures,0 errors,
0 skipped** (common6, server-v2 15, consumer16, client-v2 24). These are saved
reports, not a fresh verification of every subsequent source change. The table
below and `build.log`/`unit-tests.json` describe the original51-test run.

| Module | Tests |
|---|---:|
| common |6|
| server-v2 |13|
| consumer |9|
| client-v2 |23|

See `build.log` and `unit-tests.json`. Coverage includes channel leases/reconnect,
stale resources, shutdown during connect, durable dedupe/outbox, per-resort order
and fairness, signed lag, bounded async sessions, and HTTP/WebSocket drain races.

## Two-node real pipeline

`pipeline-verification-final.json`: **PASS**,100unique swipes and20cross-node
duplicate requests on an unused day. Independent offline totals/Top20 agree with
authoritative consumer snapshots and both Tomcat nodes' WebSocket payloads.
Requests were explicitly alternated50/50 between the nodes; this is not evidence
of ALB load distribution. `pipeline-verification.json` records the earlier run.

## Runnable client smoke

`client-5000.log`: **5000successful,0failed,0retries**;64mainthreads,
32warmupworkers x10events, all10resorts. The consumer baseline was200unique events
from earlier isolated correctness runs, then the run added exactly5000. All final
WebSocket versions and standings were reconciled before completion.

Measured local sample: ingest4.142s /1207.12swipes/s, main1240.07swipes/s,
additional pipeline verification1.126s, end-to-end947.52swipes/s. HTTP p95
132.497ms/p99 182.602ms. These are a short local smoke sample, not a claim about
EC2 capacity, steady state, optimal parameters or the consumer-lag target.

Raw attempt/push CSVs and final snapshots are in `client-v2/results/`. The log
names those exports. The local stack shares one laptop/Docker VM, unlike the
assignment's separateEC2roles.

## Real failure and recovery

`recovery-verification.json`: **PASS**.

- Verified subscriber-driven bind/unbind0→1, secondsubscriberwithoutsecondbinding,
  lastleave→0, and successful re-subscription on both local servers.
- Applied3unique events, stoppedconsumer, queued20new events and replayed3old IDs,
  restartedconsumer with itsvolume, and verified exactly23unique events/real totals.
- StoppedRabbitMQ, observed bothproducer/backplane readiness fail and ingest503,
  restartedRabbitMQ, verified bothproducerpools andbackplanes reconnect, and
  retried the sameidentity acrossnodes plusanew event.
- Final25unique events, retainedmonotonic versions, correctstandings onboth
  existingWebSocketconnections; all touchedservices restored, no volumes deleted.

## Monitoring smoke

`monitor-idle.csv`, `.jsonl`, and `-summary.json`:8idle samples, noRabbitMQAPIerrors,
readydepth0. This verifies collector wiring only; an idlequeue says nothing about
loadcapacity or the required lag/throughputtargets. Linuxhost resourcecounters must
be collected on theEC2hosts; they are unavailable in the macOShost sample.

## Later batching verification and EC2 evidence

`batched-pipeline.json`: PASS,100unique events and20duplicate requests using
the official lift table after consumer batching was introduced.
`batched-recovery.json`: PASS, local consumer/broker restart and subscription
binding recovery with persistent volumes retained.

Subsequent EC2 experiments are stored separately in `../a2-ec2/`:1/2/4ingest
500Kruns, four-ingest1Mstress, client and clarified consumer thread sweeps,
ALBdistribution/cookie verification, real pipeline verification, and live ingest
failover. Local functional tests do not substitute for those cloud experiments.
High-load runs did not all meet queue-depth/lag targets. The cloud results, queue-console
and ALB screenshots and analysis are consolidated in
`../../output/assignment2-fresh-20261108T135433Z/report.md` and `../../TUNING_PARAMETERS.md`;
the requirement status is in `../../REQUIREMENTS_A2.md`.
