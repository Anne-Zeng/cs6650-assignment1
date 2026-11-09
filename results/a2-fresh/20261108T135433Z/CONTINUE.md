# Fresh assignment run - continuation notes

The user authorized only the formal17-case matrix and fresh functional/recovery validation. Old reports must be retained and excluded from the new report. Session root is this directory. Do not start a second load while the current phase/controller is active.

- Four-ingest scaling runs in exec session68587.
- finish_fresh_suite.py runs in exec session99096 and waits for that phase, then automatically runs1M, consumer5/10/15/20, restores fixed10, runs fresh pipeline/recovery/failover/ALB validation, and builds the new report/PDFs.
- controller-status.json contains its current state and OS PID.
- Main scaling uses fixed10 workers, prefetch128, DB batch64/10ms, ACK16, pool64 on each ingest. No volume tuning is authorized in this suite.
- All four ALB targets are registered and Healthy (user screenshot supplied; observed routing validated). No further target switches are needed.
- The original drafts remain byte-for-byte unchanged, checked in preserved-draft-check.json.
- New report directory: output/assignment2-fresh-20261108T135433Z. It currently contains progress PDFs; never call these submission-ready while matrix/verification is incomplete.
- New local regression completed61 tests with0 failures/errors/skips; first localhost-restricted attempt is retained separately. JUnit XML and logs are in local-regression/.
- Real RabbitMQ screenshots are automatically captured by capture_rabbitmq_console.py via an owned SSH tunnel and ephemeral Chrome. Credentials remain only in memory. CloudWatch/Targets captures come from the user and must be associated with the right run using screenshot-context.json. Do not fabricate absent screenshots.
- PDF skill was read and announced. mark_artifact_operation_started ran successfully once for two PDFs. Renderer uses ReportLab and PDFium QA, not pdftoppm (font-cache issues earlier). PDF dependencies live in /private/tmp/skitrak-evidence-venv; MPLCONFIGDIR=/private/tmp/skitrak-mpl.
- Before final delivery: inspect controller outcome, all17 distinct cases, fresh verification flags, global count deltas, and final service restoration; render/review final PDFs. Report missed targets and monitoring errors honestly. Review missing AWS console captures. Student AI reflection remains a factual draft requiring their review; no publish/submission is authorized.
