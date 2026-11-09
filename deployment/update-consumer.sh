#!/usr/bin/env bash
# Existing course EC2 installation only. Keeps data, credentials and a rollback image.
set -euo pipefail
umask 077
[[ "$EUID" -eq 0 ]] || { printf 'Run with sudo.\n' >&2; exit 1; }
archive=${1:?Usage: sudo bash update-consumer.sh /path/consumer-batch-image.tar}
[[ -f "$archive" && -d /var/lib/skitrak/consumer && -f /etc/skitrak/consumer.env ]]
before=$(curl -fsS --max-time 10 http://127.0.0.1:8081/metrics)
baseline=$(python3 -c 'import json,sys;m=json.load(sys.stdin);assert m["healthy"] and m["queueReady"]==0 and m["inFlight"]==0 and m["pendingOutbox"]==0 and 0<=m["queueMetricsAgeMs"]<=3000,"Stop producers and wait for a healthy drained pipeline";print(m["processed"])' <<< "$before")
old_image=$(docker inspect --format '{{.Image}}' skitrak-consumer)
stamp=$(date -u +%Y%m%dT%H%M%SZ)
docker tag "$old_image" "skitrak-consumer:before-batch-$stamp"
backup=/var/lib/skitrak/backups/consumer-$stamp.tar.gz
install -d -m 0700 /var/lib/skitrak/backups
changed=0
rollback() {
  code=$?
  if [[ "$changed" -eq 1 ]]; then
    printf 'Update failed; restoring the previous image. Persistent data is retained.\n' >&2
    systemctl stop skitrak-consumer || true
    docker tag "$old_image" skitrak-consumer:a2
    systemctl start skitrak-consumer || true
  fi
  exit "$code"
}
trap rollback ERR
systemctl stop skitrak-consumer
changed=1
tar -C /var/lib/skitrak -czf "$backup" consumer
docker load --input "$archive"
systemctl start skitrak-consumer
ready=0
for attempt in $(seq 1 90); do
  if current=$(curl -fsS --max-time 3 http://127.0.0.1:8081/metrics 2>/dev/null); then
    if python3 -c 'import json,sys;m=json.load(sys.stdin);assert m["healthy"] and m["processed"]>=int(sys.argv[1]) and m.get("storeBatchSize")==32 and m.get("storeBatchWaitMs")==10' "$baseline" <<< "$current" 2>/dev/null; then ready=1; break; fi
  fi
  sleep 1
done
[[ "$ready" -eq 1 ]] || { printf 'New consumer did not become healthy with batching32/10.\n' >&2; false; }
changed=0
printf 'Consumer updated successfully. Previous unique events: %s\n' "$baseline"
printf 'Database backup: %s\nRollback image: skitrak-consumer:before-batch-%s\n' "$backup" "$stamp"
python3 -c 'import json,sys;m=json.load(sys.stdin);print(json.dumps({k:m[k] for k in ["healthy","processed","pendingOutbox","storeBatchSize","storeBatchWaitMs"]},indent=2))' <<< "$current"
