# Performance comparison from recorded experiments

## 1 / 2 / 4 ingests, 500K events and 512 client threads

| Ingests | Main HTTP/s | End-to-end/s | Drain seconds | Ready peak (RabbitMQ samples) | HTTP retries |
|---|---:|---:|---:|---:|---:|
| 1 | 4500.05 | 3511.24 | 4.494 | 16684 | 214 |
| 2 | 6789.50 | 3506.42 | 41.274 | 155205 | 78 |
| 4 | 7268.32 | 3463.36 | 47.421 | 176534 | 0 |

Across these runs, main HTTP throughput changed by 61.5%, while end-to-end throughput changed by -1.4%. This supports an ingestion-capacity improvement with a downstream completion bottleneck, not linear end-to-end scaling.

**Comparison limitation:** the single-ingest run had 32 publisher channels, while the selected two/four-ingest runs had 64 per node. Consumer parameters match (10 configured threads, dynamic scaling on, prefetch128, maximum DB batch64, ACK batch16), but accumulated database state also differs. Therefore these are deployment/tuning observations, not an isolated causal estimate of ingest count.

## Four-ingest client-thread sweep

| Client threads | Main HTTP/s | End-to-end/s | Ready peak | Mean consumer lag ms |
|---|---:|---:|---:|---:|
| 64 | 1901.10 | 1793.00 | 0 | 21.55 |
| 128 | 3379.94 | 2919.80 | 52 | 38.33 |
| 256 | 5806.07 | 3405.53 | 123598 | 13163.53 |
| 512 | 7268.32 | 3463.36 | 176534 | 18614.52 |

At higher concurrency the ready backlog and drain grow substantially, while completed-event throughput plateaus. Report missed queue/lag targets, even when final HTTP failures are zero. Full client latency and CPU/memory/network details are in each run page and summary.

## Consumer-thread sweep

| Workers | End-to-end/s | Ready peak | Mean lag ms | Run ID |
|---|---:|---:|---:|---|
| 5 | 2724.34 | 27 | 35.84 | 20261108T134214Z (repeat) |
| 10 | 2767.15 | 0 | 37.57 | 20261108T115434Z |
| 15 | 2795.83 | 173 | 46.96 | 20261108T115807Z |
| 20 | 2713.90 | 98 | 38.40 | 20261108T120222Z |

The five-worker row now uses the repeat at 13:42 UTC, with five fixed workers and
automatic scaling disabled throughout. It processed 500,000 additional events,
with zero HTTP failures/retries, no increase in duplicate counts, and no consumer
or RabbitMQ application collection errors. Its sampled Ready peak and mean lag
met the respective 1000-message and 100-ms targets. This does not prove a
continuous bound between samples or a bound on every event's lag.

The 10/15/20-worker rows remain earlier measurements and have not yet been
repeated alongside this run. All Ready peaks in this table use direct RabbitMQ
sampling. Ten ordered resort queues cap useful aggregation parallelism at ten;
15/20 configured workers do not establish extra aggregation capacity. These
single-run differences do not establish a statistically significant winner or
show that five workers necessarily cause higher lag.

## Screenshot-backed final configurations

The stable 500K/c128 run at 20261108T125240Z and stress 1M/c512 run at 20261108T130828Z have dedicated RabbitMQ console captures. Both use20 fixed consumer threads, unlike earlier dynamic10 comparisons. Keep these configuration differences explicit. The draft report output/assignment2-report.md documents their screenshot context.


## Five-worker repeat

The main table uses the repeat from
`results/a2-ec2/instrumented-500000-c128-20261108T134214Z`.
The original 11:51 UTC run remains historical evidence: 2839.37 completed
events/s, Ready peak 6309, and mean lag 581.67 ms. The repeat measured
2724.34 events/s, Ready peak 27, and mean lag 35.84 ms. Database/cache state
and admitted workload rate differ; a single cause is not isolated.

The repeat's RabbitMQ screenshots show load-period activity, but the second
capture still has 13 unacknowledged messages and nonzero rates; final drain is
established by the monitor records. Its ALB screenshot covers only a partial
window. See the run-specific `screenshot-context.json` and draft report for
these evidence limitations.
