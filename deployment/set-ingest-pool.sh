#!/usr/bin/env bash
# Run on the Mac with load tests stopped. Change only CHANNEL_POOL_SIZE.
set -euo pipefail
size=${1:-64}
[[ "$size" =~ ^[0-9]+$ ]] && (( size >= 1 && size <= 1024 )) || {
  printf 'Usage: bash deployment/set-ingest-pool.sh SIZE (1..1024)\n' >&2; exit 1;
}
root=$(cd "$(dirname "$0")/.." && pwd)
options=(-i "$root/cs6650-lab1-key.pem" -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10)
for role in ingest1 ingest2; do
  host_config=$(PYTHONPATH="$root/monitoring" python3 -c 'import sys;from ec2_monitor import HOSTS;print(" ".join(HOSTS[sys.argv[1]]))' "$role")
  read -r host alias <<< "$host_config"
  host_options=("${options[@]}")
  [[ -z "$alias" ]] || host_options+=(-o "HostKeyAlias=$alias")
  printf '\nSetting %s (%s) channel pool to %s...\n' "$role" "$host" "$size"
  ssh "${host_options[@]}" "ec2-user@$host" "sudo -n bash -s -- $size" <<'REMOTE'
set -euo pipefail
umask 077
size=$1
env_file=/etc/skitrak/ingest.env
curl -fsS --max-time 5 http://127.0.0.1:8080/health >/dev/null
backup="$env_file.before-pool-$(date -u +%Y%m%dT%H%M%SZ)-$$"
cp -p "$env_file" "$backup"
rollback() {
  code=$?
  trap - ERR
  printf 'Update failed; restoring configuration on this host.\n' >&2
  cp -p "$backup" "$env_file"
  systemctl restart skitrak-ingest || true
  exit "$code"
}
trap rollback ERR
python3 - "$env_file" "$size" <<'PY'
import pathlib,re,sys
p=pathlib.Path(sys.argv[1])
lines=[line for line in p.read_text().splitlines() if not re.match(r'^\s*CHANNEL_POOL_SIZE\s*=',line)]
p.write_text('\n'.join(lines)+'\nCHANNEL_POOL_SIZE='+sys.argv[2]+'\n')
PY
systemctl restart skitrak-ingest
ready=0
for attempt in $(seq 1 90); do
  if metrics=$(curl -fsS --max-time 3 http://127.0.0.1:8080/health 2>/dev/null); then
    if python3 -c 'import sys,json;m=json.load(sys.stdin);assert m["status"]=="UP" and m["producerChannelsAvailable"]==int(sys.argv[1])' "$size" <<< "$metrics" 2>/dev/null; then ready=1; break; fi
  fi
  sleep 1
done
[[ "$ready" -eq 1 ]] || { printf 'Expected healthy service with %s available channels.\n' "$size" >&2; false; }
trap - ERR
python3 -c 'import sys,json;m=json.load(sys.stdin);print(json.dumps({k:m[k] for k in ["serverId","status","producerChannelsAvailable","publishFailureReasons"]},indent=2))' <<< "$metrics"
printf 'Configuration backup retained: %s\n' "$backup"
REMOTE
done
printf '\nBoth ingests verified. Wait for both ALB targets to be Healthy before testing.\n'
