# Assignment 2 requirement-to-evidence map

This map incorporates the instructor-endorsed clarification supplied in the chat:
10 resorts, default Top-N20, consumer sweep5/10/15/20, freely chosen documented EC2
types, and the supplied official lift table. It separates implemented behavior
from cloud experiments that still require a real deployment.

| Requirement | Implementation | Validation / remaining evidence |
|---|---|---|
| Required swipe JSON and official vertical lookup | `common/SwipeMessage`, `LiftCatalog`, bundled official JSON; `server-v2/SwipeValidator` | Shared contract tests; both official table copies match; real HTTP/WS business test uses all40 lifts |
| RabbitMQ topic `skitrak.exchange`, resort.N routes and10 queues | `common/Topology` | Local RabbitMQ4.1 queue topology exercised; deploy broker on its own EC2 using role script |
| TTL and queue limits | 24h/default100000, reject-publish overflow, per-resort dead queues | Configured in real local broker; monitor expirations/rejections/dead queues during cloud runs |
| One production connection and reusable bounded Channel pool | `server-v2/ChannelPool`, exclusive generation-aware leases | Pool exclusivity/timeout/stalelease tests; broker outage/reconnect integration passes |
| Reconnection and failure behavior | Application-owned reconnect loops; publisher confirms+mandatory; bounded waits | Shutdown-during-connect regressions; broker-down503 and recovery on both nodes pass |
| Independent consumer process | `consumer/ConsumerMain`, shaded `consumer.jar` | Real separate local container; EC2 install/systemd supplied |
| Configurable/fair consumer pool and dynamic scaling | FIFO serial resort lanes, fair yielding, resizable executor | 5worker/10resort ordering and fairness tests; actual cloud5/10/15/20 sweep pending |
| Per-skier totals and Top-N | Durable totals + incremental bounded ordered set; numeric ties | Store tests; independently computed Top20 matches both server subscriptions |
| Connection locality | `leaderboard.topic` keys `lb.N`, one private queue perWSnode, dynamic localinterestbindings | Cross-node subscriptions, first/last binding changes, live WS afterbrokerreconnect pass |
| Real leaderboard on change | Complete versioned snapshots; initial/bootstrap/periodic authoritative refresh |100unique/20crossnode duplicate test passes; final snapshots preserved |
| At-least-once/manual ACK | Consumer ACK after committed aggregation and confirmed notifications | Store/outbox tests, backlog duringconsumerstop and restart verified |
| Idempotence | Stable client UUID, per-resort durable inbox semanticfingerprint | Cross-ingest replay and postrestart replay do not change totals |
| Per-resort order | One active subscription and one FIFO application lane perqueue; in-place retry | Serial lane tests; no parallel resort aggregation; poison messages pause explicitly |
| Failed-delivery retry | Bounded in-place retry, then reconnect/redelivery; pending outbox replay | Unit/restart/broker fault verification; invaliddata recovery procedure documented |
| Health checks and autorestart | Ingest/consumer health, Docker restart, EC2systemd | Local readiness and stoppedbroker503 verified; EC2service evidence pending |
| ALB target group, stickiness, literal /health,120s idle | `deployment/cloudformation/alb.yaml`, ROOT WAR | Template/config and verification scripts supplied; real ALB distribution/stickiness/failover pending |
| Tune prefetch/concurrency/ackbatch/pool | Environment config, safe contiguous ACK high-water marks | Defaults verified; controlled cloud sweeps pending |
| Client64/128/256/512 and consumer5/10/15/20 | `monitoring/run_benchmarks.py`, `local_matrix.sh` | Actual consumerconfiguration checked before labeling runs; cloud matrix pending |
| 1/2/4ingest500K +4ingest1M | Parameterized ALB/deployment and benchmark commands | Not executed onEC2 inthischat; local5000run is functional evidence only |
| Queue depth<1000, lag<100ms, no loss underload | Monitoring, laghistograms, rawqueues, ID/topNreconciliation | Targets need workload-specific measurement; no blanket performance guarantee claimed |
| Client/queue/system/ALB metrics | CollectorCSV/JSONL, CloudWatchscript, accesslogtargetcounts, ALBverifier | Localcollector smoke passes; cloudmetrics/screenshots pending |
| Two-page architecture incl diagrams | `output/pdf/assignment2-architecture.pdf`, source and renderer | Exactly2pages; rendered and visually reviewed |
| AI usage reflection | `AI_USAGE.md` | Actual interaction facts recorded; student review/personal reflection remains necessary |
| Submission PDF, GitURL and screenshots | ArchitecturePDF + documented evidence checklist | Assemble finalCanvassubmission aftercloudruns; no fabricated screenshots/results |

Detailed local results: `results/a2-local/README.md`.
