# Evidence coverage and remaining captures

All 28 instrumented runs have their full original client logs, queue metrics, system-resource plots, application request distribution, and summary JSON. Original raw files remain under results/a2-ec2; summary files record source hashes. No AWS workload was rerun.

Generated plots are not RabbitMQ or AWS console screenshots. Historical console captures cannot be recreated from CSV. Existing screenshots are copied into console-screenshots and must be matched to their actual time windows.

| Run | Console captures identified | Collection gaps |
|---|---|---|
| instrumented-100000-c64-20261108T033004Z | Not identified | None detected |
| instrumented-100000-c64-20261108T044946Z | Not identified | None detected |
| instrumented-100000-c64-20261108T052219Z | Not identified | None detected |
| instrumented-1000000-c512-20261108T110340Z | Not identified | consumer collection errors: {"timeout": 55} |
| instrumented-1000000-c512-20261108T130828Z | rabbitmq-1m-stress-during-load.png, rabbitmq-1m-stress-drained.png | consumer collection errors: {"timeout": 36} |
| instrumented-500000-c128-20261108T054925Z | Not identified | consumer collection errors: {"timeout": 42} |
| instrumented-500000-c128-20261108T063752Z | Not identified | consumer collection errors: {"timeout": 43} |
| instrumented-500000-c128-20261108T065453Z | Not identified | None detected |
| instrumented-500000-c128-20261108T081825Z | Not identified | None detected |
| instrumented-500000-c128-20261108T082726Z | Not identified | None detected |
| instrumented-500000-c128-20261108T112105Z | Not identified | None detected |
| instrumented-500000-c128-20261108T115107Z | Not identified | consumer collection errors: {"timeout": 1} |
| instrumented-500000-c128-20261108T115434Z | Not identified | None detected |
| instrumented-500000-c128-20261108T115807Z | Not identified | None detected |
| instrumented-500000-c128-20261108T120222Z | Not identified | None detected |
| instrumented-500000-c128-20261108T125240Z | rabbitmq-overview-first.png, rabbitmq-overview-after-exit.png | None detected |
| instrumented-500000-c256-20261108T070938Z | Not identified | consumer collection errors: {"timeout": 8} |
| instrumented-500000-c256-20261108T083922Z | Not identified | consumer collection errors: {"timeout": 20} |
| instrumented-500000-c256-20261108T112830Z | Not identified | consumer collection errors: {"timeout": 22} |
| instrumented-500000-c512-20261108T071551Z | Not identified | consumer collection errors: {"timeout": 13} |
| instrumented-500000-c512-20261108T084645Z | Not identified | consumer collection errors: {"timeout": 18} |
| instrumented-500000-c512-20261108T091512Z | Not identified | consumer collection errors: {"timeout": 21} |
| instrumented-500000-c512-20261108T092852Z | Not identified | consumer collection errors: {"timeout": 16} |
| instrumented-500000-c512-20261108T100608Z | Not identified | consumer collection errors: {"timeout": 18} |
| instrumented-500000-c512-20261108T101917Z | Not identified | consumer collection errors: {"timeout": 17} |
| instrumented-500000-c512-20261108T105318Z | Not identified | consumer collection errors: {"timeout": 14} |
| instrumented-500000-c64-20261108T052951Z | Not identified | None detected |
| instrumented-500000-c64-20261108T111554Z | Not identified | None detected |

## Earlier non-instrumented baseline

The original IntelliJ 500K baseline at results/a2-ec2/single-500k-c64 has summary evidence and CPU screenshots, but its old monitor did not overlap the load. It cannot supply historical queue/network metrics for that run. Use a later instrumented single-ingest 500K as the full-metric baseline, and label the original as an earlier tuning experiment.

## To satisfy a strict console-screenshot-per-run interpretation

For each missing run, the original historical RabbitMQ console chart may no longer be available. A replay with the same recorded application settings, same target count, and contemporaneous console captures would create NEW evidence; it does not fill gaps in the old run. Label replays with new UTC run IDs.

Before replay: verify ALB healthy target count; restore the specific consumer/channel configuration; start monitoring; open RabbitMQ Overview/Queues charts with a window long enough to include warmup, sending and drain; record UTC. Capture depth and publish/deliver/ACK rates during load and after drain. Save ALB RequestCount/TargetResponseTime charts for the same window and application per-target deltas. Preserve client.log, monitor JSONL, run.json, final snapshots and before/after counts.

RequestCountPerTarget is a target-group aggregate; application counter bars here are not ALB access-log or per-instance CloudWatch evidence.

## Functional evidence

alb-four-ingest-pipeline.json and live-websocket-failover.json verify deterministic vertical totals, duplicate replay, real updates, and Python-harness reconnection. Load logs and final-snapshots files show subscription activity for each benchmark; they are not a separate independent full-ID reconciliation.

## Measurement limits

Consumer API errors remain visible in per-run summaries. RabbitMQ queue collection is independent, so Consumer timeouts do not automatically invalidate broker queue samples. All means/maxima are sample statistics over the client window including warmup/drain. Network uses physical interfaces rather than summing Docker interfaces. Run-specific lag is derived from cumulative sample counts and sums; no fabricated per-run lag percentiles are provided.
