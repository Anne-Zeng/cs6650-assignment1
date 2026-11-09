#!/usr/bin/env bash
# LOCAL tuning only. Does not stand in for EC2 1/2/4-instance results.
set -euo pipefail
cd "$(dirname "$0")/.."
for consumer_threads in 5 10 15 20; do
  CONSUMER_THREADS="$consumer_threads" CONSUMER_DYNAMIC_SCALING=false \
    docker compose --env-file deployment/.env -f deployment/compose.yaml up -d --no-deps consumer
  for attempt in $(seq 1 60); do
    if curl --fail --silent http://localhost:8081/health >/dev/null; then break; fi
    sleep 1
  done
  python3 monitoring/run_benchmarks.py --base-url http://localhost:8080 \
    --consumer-url http://localhost:8081 --instances 1 --consumer-threads "$consumer_threads"
done
