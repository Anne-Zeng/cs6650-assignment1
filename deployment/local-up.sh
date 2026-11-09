#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if [[ ! -f .env ]]; then
  printf 'Copy deployment/.env.example to deployment/.env and configure credentials + lift table first.\n' >&2
  exit 1
fi
docker compose --env-file .env -f compose.yaml up --build -d
printf 'Local endpoints: ingest1 http://localhost:8080, ingest2 http://localhost:8082, consumer http://localhost:8081\n'
printf 'RabbitMQ console: http://localhost:15672 . This is local Docker, not EC2 deployment evidence.\n'
