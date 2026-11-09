# Assignment 2 AI usage record and reflection draft

This file records the actual collaboration in this chat. The student should review
the implementation, add their own assessment, and verify every statement before
submission. It does not claim that unexpressed personal reasoning came from the student.

## Where AI was used

Codex inspected Assignment 1 and the Assignment 2 specification, proposed the
pipeline, and generated `common`, `server-v2`, `consumer`, `client-v2`, deployment
configuration, monitoring and verification scripts, and documentation. Specific
areas included channel leasing/reconnection, stable event identity, SQLite inbox
and outbox transactions, per-resort serial lanes, incremental Top-N maintenance,
topic backplane bindings, WebSocket bootstrap/version handling, ALB configuration,
and load-test evidence collection. Multiple AI subagents implemented/reviewed
separate modules; the primary agent integrated and tested their changes.

The user supplied instructor-endorsed clarifications and the actual official
40-lift JSON values. These course-specific facts did not come from AI inference.

## Suggestions adopted in the implementation

1. **Stable eventId across HTTP retries.** A UUID is generated once in the client
   and reused through `Idempotency-Key`, including retries reaching another ingest
   server. This covers the case where RabbitMQ accepted a swipe but its HTTP
   response was lost; server-generated fresh IDs alone would double-count it.
2. **Per-resort serial processing with durable inbox/outbox.** An ordered lane
   owns the resort's updates. Dedupe, totals, version and pending broadcast commit
   together; notifications are confirmed before the original delivery is acked.
   This avoids losing a broadcast when an already-applied event is redelivered.
3. **Subscriber-aware topic backplane.** The user proposed `lb.{resortID}` and
   servers binding only to resorts with local subscribers. The implementation uses
   one queue per WebSocket node and exact topic bindings, with snapshot bootstrap.

## Suggestions rejected or corrected

1. **One shared fanout exchange sending every resort to every server** was the
   initial design. After the user raised selective subscriptions, this became a
   topic exchange. A single fanout exchange ignores routing keys and cannot
   implement broker-side resort filtering; a separate fanout per resort would
   also work but was not selected.
2. **Using the original 10/20/40/80 sweep as the final experiment plan** was
   corrected when the user supplied the instructor's clarification. The final
   scripts use 5/10/15/20 and explain why ten ordered resort queues cap useful
   aggregation parallelism. Code still accepts other configured thread counts.
3. **Assuming thread-safe maps were sufficient for restart-safe correctness** was
   rejected during design review. Individual map operations do not make dedupe,
   increments and broadcast atomic or durable; persistent transactional state was
   implemented instead.

## An actual AI mistake and review finding

The initial generated consumer configuration defaulted to Top-N=10. The instructor
clarification supplied by the user required a default of 20, and the configuration,
verification and experiment scripts were corrected. This shows why generated
defaults must be checked against course-specific clarifications.

Cross-module review also found early implementations of shutdown/reconnect
ownership and client drain checks that could leak AMQP resources or declare a run
finished too early. These were treated as implementation defects to fix and test,
not hidden by reporting only a successful compile.

## Student review to complete

- Explain in your own words the selected backplane tradeoff and first-subscriber
  bind/snapshot race.
- Review the actual EC2 measurements and identify the first observed bottleneck;
  do not infer results from thread counts or local functional tests.
- Confirm which generated code you reviewed, changed, or chose to keep, and why.
- Add a genuine shared chat link or actual exported transcript if available. No
  fabricated transcript is included by this implementation.
