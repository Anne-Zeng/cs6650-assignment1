# Metrics experiment

| Mode | Main throughput median (min–max) | Median whole-program GC pause ms |
|---|---:|---:|
| shared | 4065.10 (4052.40–4113.66) | 2373.280 |
| buffered | 4120.18 (4072.83–4127.62) | 203.960 |
| off | 4173.36 (3822.28–4176.19) | 185.183 |

Buffered versus shared median throughput change: +1.35%.
Buffered throughput shortfall versus off: +1.27%.

These are observed differences, not isolated CPU-cost percentages. Shared → buffered changes both storage and timestamp representation.
GC pauses cover the entire JVM run, not just main. Network/server drift and retries can still confound results.
off is a diagnostic only and cannot replace the full Part 2 submission.
Check configuration.json, raw logs, retry counts, and min–max variation before drawing conclusions.
