#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# EC2 x86_64 default; pass linux/arm64 for Graviton. Native SQLite must match the image architecture.
platform=${PLATFORM:-linux/amd64}
docker build --platform "$platform" -t skitrak-ingest:a2 -f deployment/Dockerfile.server .
docker build --platform "$platform" -t skitrak-consumer:a2 -f deployment/Dockerfile.consumer .
mkdir -p deployment/artifacts
docker save --output deployment/artifacts/ingest-image.tar skitrak-ingest:a2
docker save --output deployment/artifacts/consumer-image.tar skitrak-consumer:a2
printf 'Transfer image tar files only to their dedicated EC2 roles. Artifacts may be large.\n'
