#!/usr/bin/env bash
# Run on the Mac with load tests stopped. Update each existing ingest in turn.
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
archive="$root/deployment/artifacts/ingest-diagnostics-image.tar"
test -f "$archive"
options=(-i "$root/cs6650-lab1-key.pem" -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10)
for role in ingest1 ingest2; do
  host=34.213.52.138
  host_options=("${options[@]}")
  if [[ "$role" == ingest1 ]]; then
    host=44.247.123.174
    host_options+=(-o HostKeyAlias=34.219.193.18)
  fi
  printf '\nUpdating %s (%s)...\n' "$role" "$host"
  scp "${host_options[@]}" "$archive" "ec2-user@$host:~/ingest-diagnostics-image.tar"
  ssh "${host_options[@]}" "ec2-user@$host" 'sudo -n bash -s' <<'REMOTE'
set -euo pipefail
curl -fsS --max-time 5 http://127.0.0.1:8080/health >/dev/null
old_image=$(docker inspect --format '{{.Image}}' skitrak-ingest)
stamp=$(date -u +%Y%m%dT%H%M%SZ)
docker tag "$old_image" "skitrak-ingest:before-diagnostics-$stamp"
docker load --input /home/ec2-user/ingest-diagnostics-image.tar
rollback() {
  code=$?
  trap - ERR
  printf 'Update failed; restoring previous image.\n' >&2
  systemctl stop skitrak-ingest || true
  docker tag "$old_image" skitrak-ingest:a2
  systemctl start skitrak-ingest || true
  exit "$code"
}
trap rollback ERR
systemctl stop skitrak-ingest
docker tag skitrak-ingest:diagnostics skitrak-ingest:a2
systemctl start skitrak-ingest
ready=0
for attempt in $(seq 1 90); do
  if metrics=$(curl -fsS --max-time 3 http://127.0.0.1:8080/health 2>/dev/null); then
    if python3 -c 'import sys,json;m=json.load(sys.stdin);assert m["status"]=="UP" and "publishFailureReasons" in m' <<< "$metrics" 2>/dev/null; then ready=1; break; fi
  fi
  sleep 1
done
[[ "$ready" -eq 1 ]] || { printf 'Diagnostic health check failed.\n' >&2; false; }
trap - ERR
python3 -c 'import sys,json;m=json.load(sys.stdin);print(json.dumps({k:m[k] for k in ["serverId","status","publishFailureReasons"]},indent=2))' <<< "$metrics"
printf 'Updated successfully. Rollback image: skitrak-ingest:before-diagnostics-%s\n' "$stamp"
REMOTE
done
printf '\nBoth ingests updated. Wait until both ALB targets are Healthy before testing.\n'
