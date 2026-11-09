#!/usr/bin/env bash
# Run on the Mac. Copies the running source image and private configuration.
set -euo pipefail
umask 077
root=$(cd "$(dirname "$0")/.." && pwd)
key="$root/cs6650-lab1-key.pem"
source_host=ec2-user@44.247.123.174
target_ip=${1:?Usage: bash deployment/add-ingest.sh PUBLIC_IP SERVER_ID}
server_id=${2:?Supply a unique SERVER_ID}
[[ "$target_ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ && "$server_id" =~ ^ingest-[3-4]$ ]] || exit 1
target_host=ec2-user@$target_ip
common=(-i "$key" -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10)
source_options=("${common[@]}" -o HostKeyAlias=34.219.193.18)
work=$(mktemp -d "${TMPDIR:-/tmp}/skitrak-add-ingest.XXXXXX")
trap 'rm -rf "$work"' EXIT
ssh "${common[@]}" "$target_host" 'test ! -e /etc/skitrak/ingest.env || { echo "Target already has an ingest configuration; stopping to avoid overwriting it." >&2; exit 1; }'
printf 'Copying configuration privately from ingest-1...\n'
ssh "${source_options[@]}" "$source_host" 'sudo -n tar -C /etc/skitrak -czf - ingest.env lift_vertical.json' > "$work/config.tar.gz"
image_id=$(ssh "${source_options[@]}" "$source_host" 'sudo -n docker inspect --format "{{.Image}}" skitrak-ingest')
[[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo 'Unexpected source image ID' >&2; exit 1; }
printf '%s\n' "$image_id" > "$work/image-id"
printf 'Exporting the exact running ingest-1 image; this may take a few minutes...\n'
ssh "${source_options[@]}" "$source_host" "sudo -n docker save '$image_id'" | gzip > "$work/image.tar.gz"
scp "${common[@]}" "$work/config.tar.gz" "$work/image.tar.gz" "$work/image-id" "$root/deployment/install-ec2-role.sh" "$target_host:~/"
ssh "${common[@]}" "$target_host" "sudo -n bash -s -- $server_id" <<'REMOTE'
set -euo pipefail
umask 077
server_id=$1
cd /home/ec2-user
test ! -e /etc/skitrak/ingest.env
dnf install -y docker
systemctl enable --now docker
docker load --input image.tar.gz
image_id=$(cat image-id)
[[ "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]]
docker tag "$image_id" skitrak-ingest:a2
install -d -m 0750 /etc/skitrak
tar -xzf config.tar.gz -C /etc/skitrak
chown root:root /etc/skitrak/ingest.env /etc/skitrak/lift_vertical.json
chmod 0600 /etc/skitrak/ingest.env
sed -i '/^SERVER_ID=/d' /etc/skitrak/ingest.env
printf '\nSERVER_ID=%s\n' "$server_id" >> /etc/skitrak/ingest.env
# The official table is mounted for the container's unprivileged user.
chmod 0644 /etc/skitrak/lift_vertical.json
bash install-ec2-role.sh ingest
rm -f config.tar.gz image.tar.gz image-id
for attempt in $(seq 1 90); do
  if curl -fsS --max-time 3 http://127.0.0.1:8080/health; then
    printf '\nSUCCESS: %s is healthy. It is ready for ALB registration.\n' "$server_id"
    exit 0
  fi
  sleep 1
done
printf 'Health check did not pass. Inspect: sudo docker logs --tail 80 skitrak-ingest\n' >&2
exit 1
REMOTE
