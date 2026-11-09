#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -B clean verify
printf 'Built server-v2/target/ROOT.war, consumer/target/consumer.jar, client-v2/target/client-v2.jar\n'
