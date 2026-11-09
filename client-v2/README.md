# Assignment 2 load client

This module preserves the existing buffered per-task HTTP measurements and adds
stable event UUIDs across retries, classified retry statuses, real snapshot
validation, and a bounded drain phase before WebSocket connections close.
The original `client-part1` and `client-part2` modules remain unchanged.

Build from the repository root: `mvn -pl client-v2 -am package`.
Run using Java11 or later:

```bash
java -jar client-v2/target/client-v2.jar \
  http://localhost:8080 500000 128 1000 10 buffered 6650 http://localhost:8081 180
```

Arguments are baseURL, events, mainThreads, warmupEventsPerThread, resortCount,
metricsMode, seed, consumerURL, and drainTimeoutSeconds. Events include warmup.
Use HTTPS/WSS when the endpoint has an HTTPS listener. ConsumerURL must be
reachable from the client runner; use an SSH tunnel to its private API when
running locally against EC2. Do not publicly expose consumer admin/state APIs.

The input sequence is repeatable with the same seed; each run creates fresh
UUIDs so persistent consumer state does not dedupe a new benchmark away. The
client first requires two healthy, fully drained samples for a durable processed-count
baseline, sends requests, then requires exactly the successful-event count to be
applied and matching final HTTP/WebSocket snapshot versions and content. Run one benchmark at a time without unrelated producers. Finally
failed network requests can have ambiguous broker outcomes, so any nonzero final
failure count makes the run fail; use eventId/business reconciliation to inspect
uncertain outcomes.

`results/final-snapshots-*.json` records the authoritative final snapshots verified
against WebSocket versions and content. HTTP throughput excludes drain; an
additional end-to-end throughput includes it. JSONL/CSV monitoring and small
business-correctness verification are separate tools under `monitoring/`.
No cross-host one-way latency claim is reliable without clock synchronization.
