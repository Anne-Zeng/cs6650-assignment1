# Assignment 2 deployment

No Tomcat or EC2 connection is needed to compile or run unit tests. Java 11 and
Maven build the project; Docker Desktop must be running for local integration.
The supplied images use Java 11 and Tomcat 9, and deploy `ROOT.war`, so `/health`
is the actual root health path.

## Build and local test

From the repository root:

```bash
./deployment/build.sh
cp deployment/.env.example deployment/.env
# Edit the password. The provided official JSON table is already included.
./deployment/local-up.sh
```

For explicitly labelled synthetic-fixture testing only:

```bash
cd deployment
docker compose --env-file .env -f compose.yaml -f compose.demo.yaml up --build -d
```

This override uses a synthetic table and must not be used for submitted course
performance results. The normal configuration mounts the supplied official table.
Use `docker compose ... logs -f consumer ingest1 ingest2` to inspect startup.
Check `http://localhost:8080/health`, `http://localhost:8082/health`, and
`http://localhost:8081/health`. Consumer may require several seconds to connect.
RabbitMQ management is `http://localhost:15672`; credentials come from `.env`.

The local stack deliberately has no ALB. Use the two direct ingest endpoints for
cross-host locality tests and the EC2 ALB for required load-balanced evidence.
Volumes preserve broker and consumer state. `docker compose down` preserves them;
`down -v` permanently deletes test state and should only be used deliberately.

## Separate EC2 roles

Use the same VPC for all hosts and the ALB. Provision a dedicated RabbitMQ EC2,
a dedicated consumer EC2 with persistent EBS, and 1, 2, or 4 ingest EC2s. The ALB
needs at least two public subnets in different Availability Zones. Applications
use private addresses for RabbitMQ and consumer communication.

| Host / port | Permit inbound from |
|---|---|
| Ingest TCP 8080 | ALB security group; test runner only if bypassing ALB for diagnostics |
| RabbitMQ TCP 5672 | Ingest and consumer security groups |
| Consumer TCP 8081 | Ingest security group and benchmark/monitoring runner |
| SSH TCP 22 | Your own IP or approved bastion |
| RabbitMQ management 15672 | Bound to loopback; access through SSH tunnel |

Build architecture-appropriate images with `PLATFORM=linux/amd64 ./deployment/package-images.sh`
(or `linux/arm64` for Graviton). Copy `install-ec2-role.sh`, the role image tar,
and a configured role env file to the host. Install the env file as root-owned
`/etc/skitrak/{role}.env`, mode 600. Keep actual credentials out of Git.

On each host, execute the appropriate command:

```bash
sudo ./install-ec2-role.sh rabbitmq
sudo ./install-ec2-role.sh consumer consumer-image.tar
sudo ./install-ec2-role.sh ingest ingest-image.tar
```

The script installs Docker on Amazon Linux or Ubuntu and creates a systemd
service that restarts the role after failure and reboot. Before installing ingest,
copy the official table to `/etc/skitrak/lift_vertical.json` and set its env
`LIFT_VERTICAL_FILE=/config/lift_vertical.json`, `ALLOW_DEMO_LIFTS=false`, a unique
`SERVER_ID`, `CONSUMER_URL=http://CONSUMER_PRIVATE_IP:8081`, and the broker private
address. For container consumers use `STORE_DIR=/data/consumer`.
The optional native `systemd/skitrak-consumer.service` is an alternative for
hosts with Java 11 installed: install its jar in `/opt/skitrak` and use
`STORE_DIR=/var/lib/skitrak` instead. Do not run both consumer installations against
one database directory.

Inspect `sudo journalctl -u skitrak-consumer -f` or `docker logs skitrak-consumer`.
Keep consumer SQLite files and broker volume on persistent EBS. Replacing either
host without restoring its data is not a state-preserving failover.

## ALB

`cloudformation/alb.yaml` creates the ALB and target group for existing ingest
instances, with the exact required `/health` settings (30s / 5s / 2 / 3), sticky
cookies, round robin, cross-zone distribution, and 120s idle timeout. Set
`IngestCount` to 1/2/4 and supply the matching instance IDs. The template does not
create or charge for application EC2s until you explicitly deploy infrastructure.

```bash
aws cloudformation create-stack --stack-name skitrak-a2-alb \
  --template-body file://deployment/cloudformation/alb.yaml \
  --parameters file://deployment/cloudformation/parameters.json
aws cloudformation wait stack-create-complete --stack-name skitrak-a2-alb
aws cloudformation describe-stacks --stack-name skitrak-a2-alb
```

Create `parameters.json` from the example first. Updating from 1 to 2 to 4 uses
`update-stack` with the revised parameters. ALB supports WebSocket Upgrade
natively; no separate WebSocket switch is required. Once upgraded, a socket stays
on its accepted target; failure means client reconnect, not socket migration.
Use the verification script for sticky cookies and per-target request counts.
For public authenticated deployments add an HTTPS listener and ACM certificate;
the template's HTTP listener follows the existing course client setup.

The pooled load client intentionally does not share ALB cookies across all sender
threads. This allows measuring HTTP distribution. `monitoring/verify_alb.py`
separately verifies cookie persistence and target failover. Using one cookie jar
for an entire load generator would bias all requests to one target.

## Backplane and correctness

The common topology declares `skitrak.exchange` (topic), queues `resort.1`–`resort.10`,
and `leaderboard.topic` (topic). Raw swipe keys are `resort.N`; leaderboard keys
are `lb.N`. Each WebSocket node declares its own exclusive queue and binds only
to resorts with local subscribers. Binding precedes authoritative snapshot fetch;
versions reconcile concurrent notifications. Late subscribers and reconnecting
nodes fetch snapshots again. No subscriber is a valid notification outcome.

Raw queues have finite TTL and max length with `reject-publish`; short TTLs or
dropping old events are not valid ways of meeting depth targets. Monitor expired
messages and dead letters. A dead-lettered event is not a successfully aggregated
swipe and must be investigated/replayed according to ordering policy.

Ten ordered resort queues limit useful aggregation concurrency to ten lanes.
15/20 configured threads are still experimental settings, not a promise of
higher parallelism. Record configured and active worker counts separately.

## Required evidence still to collect

See `monitoring/README.md`. Unit tests and local demo integration cannot replace
real 500K/1M EC2 runs, RabbitMQ console screenshots, ALB request-distribution
metrics, CPU/memory/network samples, failure tests, and real leaderboard evidence.
No performance claims are inferred from Assignment 1 results.

## Updating the consumer after the write-batching optimization

Build/package the new consumer image as `skitrak-consumer:a2` for linux/amd64.
Upload `consumer-batch-image.tar` and `deployment/update-consumer.sh` to the consumer
EC2, then run `sudo bash ~/update-consumer.sh ~/consumer-batch-image.tar`.
Stop load generation first. The update requires a healthy drained pipeline, retains
the previous image under a rollback tag, stops the consumer, creates a consistent
backup of its data directory, loads the image and restarts the service. It verifies
restored processed counts plus `storeBatchSize=32` and `storeBatchWaitMs=10`.
On failure it restores the previous image without deleting the database. Existing
credentials and storage paths are retained. No schema migration is required.
