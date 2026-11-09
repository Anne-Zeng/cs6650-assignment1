#!/usr/bin/env bash
# Run on EACH dedicated EC2 host after copying an image tar + role env file.
set -euo pipefail
role=${1:?Usage: sudo ./install-ec2-role.sh rabbitmq|consumer|ingest [image-tar]}
[[ "$EUID" -eq 0 ]] || { printf 'Run with sudo on the EC2 host.\n' >&2; exit 1; }
case "$role" in rabbitmq|consumer|ingest) ;; *) printf 'Unknown role\n' >&2; exit 1;; esac
if ! command -v docker >/dev/null; then
  if command -v dnf >/dev/null; then dnf install -y docker;
  elif command -v apt-get >/dev/null; then apt-get update; apt-get install -y docker.io;
  else printf 'Install Docker first on this Linux distribution.\n' >&2; exit 1; fi
fi
systemctl enable --now docker
install -d -m 0750 /etc/skitrak /var/lib/skitrak /var/lib/skitrak/rabbitmq
if [[ -n ${2:-} ]]; then docker load --input "$2"; fi
env_file="/etc/skitrak/$role.env"
[[ -f "$env_file" ]] || { printf 'Create %s from the examples before running.\n' "$env_file" >&2; exit 1; }
chmod 0600 "$env_file"
case "$role" in
  rabbitmq)
    image=rabbitmq:4.1-management
    options='--hostname skitrak-rabbitmq -p 5672:5672 -p 127.0.0.1:15672:15672 -v /var/lib/skitrak/rabbitmq:/var/lib/rabbitmq'
    docker pull "$image"
    ;;
  consumer)
    image=skitrak-consumer:a2
    # The consumer image runs UID 10001. Keep the entire SQLite directory persistent.
    install -d -o 10001 -g 10001 -m 0750 /var/lib/skitrak/consumer
    options='-p 8081:8081 -v /var/lib/skitrak/consumer:/data/consumer'
    ;;
  ingest)
    image=skitrak-ingest:a2
    [[ -f /etc/skitrak/lift_vertical.json ]] || { printf 'Official lift table /etc/skitrak/lift_vertical.json is required.\n' >&2; exit 1; }
    options='-p 8080:8080 -v /etc/skitrak:/config:ro'
    ;;
esac
cat > "/etc/systemd/system/skitrak-$role.service" <<UNIT
[Unit]
Description=SkiTrak $role container
After=docker.service network-online.target
Requires=docker.service
Wants=network-online.target
[Service]
ExecStartPre=-/usr/bin/docker rm skitrak-$role
ExecStart=/usr/bin/docker run --name skitrak-$role --env-file $env_file $options $image
ExecStop=/usr/bin/docker stop --time 35 skitrak-$role
Restart=always
RestartSec=5
TimeoutStopSec=45
[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload
systemctl enable --now "skitrak-$role"
printf 'Started %s. Restrict inbound ports using the security-group matrix in deployment/README.md.\n' "$role"
