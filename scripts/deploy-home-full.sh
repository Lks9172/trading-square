#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOME_HOST="${HOME_HOST:-lks@192.168.0.200}"
HOME_DIR="${HOME_DIR:-/home/lks/trading-square}"
SSH_OPTIONS=(
  -o BatchMode=yes
  -o ConnectTimeout=10
  -o ServerAliveInterval=15
  -o ServerAliveCountMax=40
  -o TCPKeepAlive=yes
)
export RSYNC_RSH="ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=40 -o TCPKeepAlive=yes"

for command in ssh rsync; do
  command -v "$command" >/dev/null || {
    echo "required command not found: $command" >&2
    exit 1
  }
done

local_minimum_kb="${DEPLOY_LOCAL_MIN_AVAILABLE_KB:-131072}"
remote_minimum_kb="${DEPLOY_REMOTE_MIN_AVAILABLE_KB:-4194304}"
local_available_kb="$(df -Pk "$ROOT_DIR" | awk 'NR == 2 {print $4}')"
[[ "$local_available_kb" =~ ^[0-9]+$ ]] || {
  echo "cannot determine local deployment disk availability" >&2
  exit 1
}
if (( local_available_kb < local_minimum_kb )); then
  printf 'deployment preflight failed: local disk available=%sKiB required=%sKiB; no remote state changed\n' \
    "$local_available_kb" "$local_minimum_kb" >&2
  exit 1
fi

remote_available_kb="$(ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" \
  "test -d '$HOME_DIR' && df -Pk '$HOME_DIR' | awk 'NR == 2 {print \$4}'")"
[[ "$remote_available_kb" =~ ^[0-9]+$ ]] || {
  echo "cannot determine home-server deployment disk availability" >&2
  exit 1
}
if (( remote_available_kb < remote_minimum_kb )); then
  printf 'deployment preflight failed: home-server disk available=%sKiB required=%sKiB; no remote state changed\n' \
    "$remote_available_kb" "$remote_minimum_kb" >&2
  exit 1
fi

"$ROOT_DIR/scripts/check-cutover-invariants.sh"

stamp="$(date +%Y%m%d-%H%M%S)"
remote_backup_dir="$HOME_DIR/.deploy-backups"
remote_staged_compose="$remote_backup_dir/docker-compose.deploying-$stamp.yml"
remote_previous_compose="$remote_backup_dir/docker-compose.previous-$stamp.yml"
remote_previous_observability="$remote_backup_dir/observability.previous-$stamp"

# Compose 설정과 이미지는 하나의 rollback 단위다. 새 입력을 동기화하기
# 전에 운영 compose를 보존해 구 이미지와 신 설정이 섞이지 않게 한다.
ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" bash -s -- \
  "$HOME_DIR" "$remote_backup_dir" "$remote_previous_compose" \
  "$remote_previous_observability" <<'REMOTE_BACKUP'
set -Eeuo pipefail
home_dir=$1
backup_dir=$2
previous_compose=$3
previous_observability=$4
mkdir -p "$backup_dir"
test -f "$home_dir/docker-compose.yml"
cp "$home_dir/docker-compose.yml" "$previous_compose"
rm -rf "$previous_observability" "$previous_observability.absent"
if [[ -d "$home_dir/observability" ]]; then
  cp -a "$home_dir/observability" "$previous_observability"
else
  touch "$previous_observability.absent"
fi
REMOTE_BACKUP

printf '== sync application sources ==\n'
rsync -az --delete \
  --exclude target \
  --exclude '.idea' \
  "$ROOT_DIR/server-spring/" "$HOME_HOST:$HOME_DIR/server-spring/"

rsync -az --delete \
  --exclude node_modules \
  --exclude .next \
  --exclude '.env*' \
  "$ROOT_DIR/client/" "$HOME_HOST:$HOME_DIR/client/"

rsync -az --delete --exclude __pycache__ --exclude '*.pyc' --exclude .pytest_cache \
  "$ROOT_DIR/scripts/" "$HOME_HOST:$HOME_DIR/scripts/"
rsync -az --delete "$ROOT_DIR/observability/" "$HOME_HOST:$HOME_DIR/observability/"
rsync -az --delete "$ROOT_DIR/docs/" "$HOME_HOST:$HOME_DIR/docs/"
rsync -az "$ROOT_DIR/README.md" "$HOME_HOST:$HOME_DIR/"
rsync -az "$ROOT_DIR/docker-compose.yml" "$HOME_HOST:$remote_staged_compose"

printf '== real PostgreSQL multi-instance integration test ==\n'
ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" \
  "cd '$HOME_DIR' && ./scripts/test-postgres-multi-instance.sh"

printf '== build and rolling cutover ==\n'
ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" bash -s -- \
  "$HOME_DIR" "$stamp" "$remote_staged_compose" "$remote_previous_compose" \
  "$remote_previous_observability" <<'REMOTE'
set -Eeuo pipefail
cd "$1"
stamp=$2
staged_compose=$3
previous_compose=$4
previous_observability=$5
remote_backup_dir="$PWD/.deploy-backups"
test -s "$staged_compose"
test -s "$previous_compose"
[[ -d "$previous_observability" || -f "$previous_observability.absent" ]]

# Backing-store credentials are generated once on the home server and never
# copied through rsync or printed in deployment output.
touch .env
chmod 600 .env
ensure_env_secret() {
  local key=$1
  if ! grep -q "^${key}=" .env; then
    printf '%s=%s\n' "$key" "$(openssl rand -hex 32)" >> .env
  fi
}
ensure_env_value() {
  local key=$1 value=$2
  grep -q "^${key}=" .env || printf '%s=%s\n' "$key" "$value" >> .env
}
ensure_env_secret POSTGRES_PASSWORD
ensure_env_secret MINIO_SECRET_KEY
ensure_env_secret MINIO_APP_SECRET_KEY
ensure_env_value MINIO_ACCESS_KEY macrosquare
ensure_env_value MINIO_APP_ACCESS_KEY macrosquare-app
ensure_env_value MINIO_BUCKET macrosquare-artifacts

# Preserve the detached inputs without mounting them into the new runtime.
# The large source tree remains untouched; a checksum inventory proves it.
# The small historical named volume is also archived as a standalone tarball.
legacy_preservation_dir=""
if [[ ! -f .legacy-runtime-detached-v1 ]]; then
  legacy_preservation_dir="$remote_backup_dir/legacy-runtime-$stamp"
  mkdir -p "$legacy_preservation_dir"
  if [[ -d server/data ]]; then
    (
      cd server/data
      find . -type f -print0 | sort -z | xargs -0 sha256sum
    ) >"$legacy_preservation_dir/server-data.sha256"
    du -sb server/data >"$legacy_preservation_dir/server-data.size"
  fi
  if docker volume inspect macrosquare-spring-data >/dev/null 2>&1; then
    docker run --rm \
      -v macrosquare-spring-data:/source:ro \
      -v "$legacy_preservation_dir:/archive" \
      -e "ARCHIVE_UID=$(id -u)" -e "ARCHIVE_GID=$(id -g)" \
      alpine:3.22 sh -ec \
      'tar -czf /archive/macrosquare-spring-data.tar.gz -C /source . &&
       chown "$ARCHIVE_UID:$ARCHIVE_GID" /archive/macrosquare-spring-data.tar.gz'
    sha256sum "$legacy_preservation_dir/macrosquare-spring-data.tar.gz" \
      >"$legacy_preservation_dir/macrosquare-spring-data.tar.gz.sha256"
  fi
  chmod -R go-rwx "$legacy_preservation_dir"
fi

previous_server="$(docker inspect macrosquare-server --format '{{.Image}}' 2>/dev/null || true)"
previous_client="$(docker inspect macrosquare-client --format '{{.Image}}' 2>/dev/null || true)"
previous_server_ref="$(docker inspect macrosquare-server --format '{{.Config.Image}}' 2>/dev/null || true)"
previous_client_ref="$(docker inspect macrosquare-client --format '{{.Config.Image}}' 2>/dev/null || true)"
server_rollback_tag="macrosquare-server-spring:rollback-$stamp"
client_rollback_tag="macrosquare-client:rollback-$stamp"
preflight_name=""
cutover_started=false
previous_observability_enabled=false
if grep -q '^  otel-collector:' "$previous_compose"; then
  previous_observability_enabled=true
fi

wait_url() {
  local url=$1
  local attempts=${2:-60}
  for ((attempt=1; attempt<=attempts; attempt++)); do
    if curl -fsS --max-time 5 "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_healthy() {
  local container=$1
  local attempts=${2:-60}
  local status
  for ((attempt=1; attempt<=attempts; attempt++)); do
    status="$(docker inspect "$container" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>/dev/null || true)"
    if [[ "$status" == healthy || "$status" == running ]]; then
      return 0
    fi
    [[ "$status" != unhealthy && "$status" != exited && "$status" != dead ]] || return 1
    sleep 2
  done
  return 1
}

rollback() {
  local code=$?
  trap - ERR
  set +e
  echo "deployment failed; restoring previous compose contract and images" >&2
  [[ -z "$preflight_name" ]] || docker rm -f "$preflight_name" >/dev/null 2>&1 || true
  cp "$previous_compose" docker-compose.yml || true
  rm -rf observability
  if [[ -d "$previous_observability" ]]; then
    cp -a "$previous_observability" observability || true
  fi
  if [[ -n "$previous_server" ]]; then
    docker tag "$previous_server" macrosquare-server-spring:production
    if [[ -n "$previous_server_ref" && "$previous_server_ref" != sha256:* ]]; then
      docker tag "$previous_server" "$previous_server_ref" || true
    fi
  fi
  if [[ -n "$previous_client" ]]; then
    docker tag "$previous_client" macrosquare-client:production
    if [[ -n "$previous_client_ref" && "$previous_client_ref" != sha256:* ]]; then
      docker tag "$previous_client" "$previous_client_ref" || true
    fi
  fi
  docker compose config --quiet || true
  if [[ "$cutover_started" == true ]]; then
    # After replacement started, retagging alone is insufficient: force both
    # containers back onto the captured rollback images.
    docker compose up -d --no-deps --force-recreate server || true
    wait_url http://127.0.0.1:5846/actuator/health/readiness 60 || true
    docker compose up -d --no-deps --force-recreate client || true
    wait_url http://127.0.0.1:5847/ 45 || true
  fi
  if [[ "$previous_observability_enabled" == true ]]; then
    docker compose up -d --force-recreate \
      jaeger loki otel-collector prometheus alloy || true
  else
    docker rm -f \
      macrosquare-alloy macrosquare-prometheus macrosquare-loki \
      macrosquare-otel-collector >/dev/null 2>&1 || true
  fi
  exit "$code"
}
trap rollback ERR

docker compose --project-directory "$PWD" -f "$staged_compose" config --quiet

if [[ -n "$previous_server" ]]; then
  docker tag "$previous_server" "$server_rollback_tag"
fi
if [[ -n "$previous_client" ]]; then
  docker tag "$previous_client" "$client_rollback_tag"
fi

cp "$staged_compose" docker-compose.yml
rm -f docker-compose.spring-shadow.yml
rm -f docker-compose.node-rollback.yml

docker compose config --quiet
docker compose build server client
expected_server_image="$(docker image inspect macrosquare-server-spring:production --format '{{.Id}}')"
expected_client_image="$(docker image inspect macrosquare-client:production --format '{{.Id}}')"

# One-time security sanitation: historical traces were created before the
# dedicated Telegram client disabled URL tracing. Purge that trace volume once,
# then keep the new sanitized Jaeger volume across normal deployments.
if [[ ! -f .jaeger-sanitized-v1 ]]; then
  jaeger_volume="$(docker inspect macrosquare-jaeger --format '{{range .Mounts}}{{if eq .Destination "/badger"}}{{.Name}}{{end}}{{end}}' 2>/dev/null || true)"
  docker rm -fv macrosquare-jaeger >/dev/null 2>&1 || true
  if [[ -n "$jaeger_volume" ]]; then
    docker volume rm "$jaeger_volume" >/dev/null
  fi
fi
docker compose up -d jaeger
wait_healthy macrosquare-jaeger 60
touch .jaeger-sanitized-v1
chmod 600 .jaeger-sanitized-v1

# Keep telemetry failure isolated from the application cutover. Each service is
# reachable only through loopback host bindings; the application uses the
# private compose network. Starting and probing them before the server ensures
# tracing never relies on a race against Collector startup.
docker compose up -d loki otel-collector prometheus alloy
wait_url http://127.0.0.1:5903/ready 60
wait_url http://127.0.0.1:13133/ 60
wait_url http://127.0.0.1:5902/-/ready 60
wait_url http://127.0.0.1:5904/-/ready 60

# Start stateful dependencies first. The idempotent initializer now creates
# only the private bucket and least-privilege app account; preserved legacy
# seed files are available solely through the explicit legacy-seed profile.
docker compose up -d postgres minio
wait_healthy macrosquare-postgres 60
# `docker compose run` inherits SSH stdin by default. Without this redirect it
# can consume the remainder of this here-doc and silently skip the cutover.
docker compose run --rm --no-deps minio-init </dev/null

# Exercise the exact production image, PostgreSQL migration and one-way legacy
# importer before interrupting the healthy container. The preflight uses a
# private port and disables all schedulers/notifications, while retaining the
# real backing-store contract.
preflight_name="macrosquare-server-preflight-$stamp"
docker compose run -d --no-deps --name "$preflight_name" \
  -e PORT=15846 \
  -e TELEGRAM_NOTIFICATIONS_ENABLED=false \
  -e MARKET_COLLECTION_ENABLED=false \
  -e MARKET_HISTORY_SEED_ENABLED=false \
  -e MARKET_SNAPSHOT_REFRESH_ENABLED=false \
  -e COMPANY_ANALYST_HISTORY_ENABLED=false \
  -e COMPANY_RESEARCH_SUMMARY_ENABLED=false \
  -e INSTITUTIONAL_COLLECTION_ENABLED=false \
  -e POLICY_COLLECTION_ENABLED=false \
  -e PEER_DISCOVERY_ENABLED=false \
  -e NARRATIVE_SOURCE_COLLECTION_ENABLED=false \
  -e DART_COLLECTION_ENABLED=false \
  -e TRACING_EXPORT_ENABLED=false \
  server </dev/null >/dev/null
if ! wait_healthy "$preflight_name" 60; then
  docker logs --tail 240 "$preflight_name" >&2 || true
  false
fi
docker rm -f "$preflight_name" >/dev/null
preflight_name=""

cutover_started=true
docker compose up -d --no-deps --force-recreate server
wait_url http://127.0.0.1:5846/actuator/health/readiness 60
actual_server_image="$(docker inspect macrosquare-server --format '{{.Image}}')"
[[ "$actual_server_image" == "$expected_server_image" ]] || {
  printf 'server image mismatch: expected=%s actual=%s\n' \
    "$expected_server_image" "$actual_server_image" >&2
  false
}

# Persist one projection produced by the new decision contract before the
# public client is replaced.  This is deliberately part of the rollback unit:
# an older snapshot may be structurally valid yet still contain a score that a
# new freshness policy must neutralize.  Rebuilding it while only the loopback
# backend is reachable prevents that compatibility window from leaking into
# the UI after an upgrade.
curl -fsS --max-time 120 -X POST \
  -H 'Content-Type: application/json' \
  http://127.0.0.1:5846/api/refresh >/dev/null

# A calculation-contract migration intentionally hides older persisted
# company scores. Keep the old client serving until every existing universe
# row has either been recomputed or explicitly quarantined under v2, so the
# cutover never exposes stale scores or a partially empty company directory.
summary_projection_ready=false
expected_summary_total="$(
  docker inspect macrosquare-server --format '{{range .Config.Env}}{{println .}}{{end}}' \
    | sed -n 's/^DATA_INTEGRITY_EXPECTED_COMPANY_UNIVERSE=//p' \
    | tail -n 1
)"
[[ "$expected_summary_total" =~ ^[1-9][0-9]*$ ]] || {
  printf 'invalid DATA_INTEGRITY_EXPECTED_COMPANY_UNIVERSE on running server: %s\n' \
    "${expected_summary_total:-missing}" >&2
  false
}
# The 277-company universe is intentionally source-throttled; a cold rebuild
# can take 8-10 minutes on the home server without indicating a fault.
for _ in $(seq 1 450); do
  read -r summary_total summary_current <<<"$(
    docker compose exec -T postgres psql -U macrosquare -d macrosquare -AtF' ' -c \
      "select count(*), count(*) filter (where calculation_version = 6)
         from company.research_summary" </dev/null
  )"
  if [[ "$summary_total" -eq "$expected_summary_total" \
      && "$summary_current" -eq "$expected_summary_total" ]]; then
    summary_projection_ready=true
    break
  fi
  sleep 2
done
[[ "$summary_projection_ready" == true ]] || {
  printf 'company decision projection did not converge: current=%s total=%s expected=%s\n' \
    "${summary_current:-unknown}" "${summary_total:-unknown}" "$expected_summary_total" >&2
  false
}

docker compose up -d --no-deps --force-recreate client
wait_url http://127.0.0.1:5847/ 45
wait_healthy macrosquare-server 60
wait_healthy macrosquare-client 60
actual_client_image="$(docker inspect macrosquare-client --format '{{.Image}}')"
[[ "$actual_client_image" == "$expected_client_image" ]] || {
  printf 'client image mismatch: expected=%s actual=%s\n' \
    "$expected_client_image" "$actual_client_image" >&2
  false
}

# Contract smoke is part of the transaction. A build that starts but loses an
# API or a data shape is rolled back before the deployment is declared valid.
smoke_file="/tmp/macrosquare-api-smoke-$stamp.json"
python3 server-spring/migration/tools/smoke-production-api.py \
  --base-url http://127.0.0.1:5846 >"$smoke_file"
python3 - "$smoke_file" <<'PY'
import json
import sys

result = json.load(open(sys.argv[1], encoding="utf-8"))
assert result["passed"] == 43, result
PY
for route in / /research/sectors '/research/companies?page=2' /company/NVDA /research/crypto; do
  curl -fsS --max-time 30 "http://127.0.0.1:5847$route" >/dev/null
done
rm -f "$smoke_file"

# Observability is part of the deploy transaction rather than a best-effort
# sidecar. Validate application metrics, Prometheus ingestion and one synthetic
# browser RUM event before releasing the rollback trap.
metrics_probe="/tmp/macrosquare-metrics-$stamp.txt"
curl -fsS --max-time 15 http://127.0.0.1:5846/actuator/prometheus \
  >"$metrics_probe"
grep -q '^jvm_memory_used_bytes' "$metrics_probe"
rm -f "$metrics_probe"
prometheus_targets="/tmp/macrosquare-prometheus-targets-$stamp.json"
prometheus_up=false
for _ in $(seq 1 30); do
  if curl -fsS --max-time 10 http://127.0.0.1:5902/api/v1/targets \
      >"$prometheus_targets" && \
    python3 - "$prometheus_targets" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
targets = payload.get("data", {}).get("activeTargets", [])
assert any(
    target.get("labels", {}).get("job") == "macrosquare-spring"
    and target.get("health") == "up"
    for target in targets
), targets
PY
  then
    prometheus_up=true
    break
  fi
  sleep 2
done
[[ "$prometheus_up" == true ]]
rm -f "$prometheus_targets"

# Alloy starts at EOF to avoid ingesting historical host logs. Give its file
# discovery one sync interval after the recreated client creates a new log,
# then send a unique no-PII RUM probe and require it to arrive in Loki.
sleep 12
rum_probe_path="/__deployment_probe__/$stamp"
rum_status="$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' \
  -H 'content-type: application/json' \
  -X POST http://127.0.0.1:5847/api/rum \
  --data "{\"name\":\"LCP\",\"value\":1,\"delta\":1,\"rating\":\"good\",\"id\":\"deploy-$stamp\",\"navigationType\":\"navigate\",\"path\":\"$rum_probe_path\"}")"
[[ "$rum_status" == 204 ]]
loki_probe="/tmp/macrosquare-loki-rum-$stamp.json"
rum_visible=false
for _ in $(seq 1 30); do
  if curl -fsSG --max-time 10 \
      --data-urlencode 'query={stack="macrosquare-host"} |= "browser_web_vital"' \
      --data-urlencode "limit=100" \
      --data-urlencode "since=5m" \
      --data-urlencode "direction=backward" \
      http://127.0.0.1:5903/loki/api/v1/query_range >"$loki_probe" && \
    grep -F -q "$rum_probe_path" "$loki_probe"; then
    rum_visible=true
    break
  fi
  sleep 2
done
[[ "$rum_visible" == true ]]
rm -f "$loki_probe"

trap - ERR
rm -f "$staged_compose"
# Rollback tags are needed only while the deployment transaction is open.
# A successful verified cutover keeps the home server free of historical
# application images; failed deployments retain their tags for diagnosis.
docker image rm "$server_rollback_tag" >/dev/null 2>&1 || true
docker image rm "$client_rollback_tag" >/dev/null 2>&1 || true
# Failed preflights intentionally retain their rollback tags for diagnosis.
# Once a later cutover succeeds, none of those historical tags are needed.
while IFS= read -r rollback_ref; do
  case "$rollback_ref" in
    macrosquare-server-spring:rollback-*|macrosquare-client:rollback-*)
      docker image rm "$rollback_ref" >/dev/null 2>&1 || true
      ;;
  esac
done < <(docker image ls --format '{{.Repository}}:{{.Tag}}')
if [[ -n "$legacy_preservation_dir" ]]; then
  printf '%s\n' "$legacy_preservation_dir" >.legacy-runtime-detached-v1
  chmod 600 .legacy-runtime-detached-v1
fi
printf 'deployed server=%s client=%s\n' \
  "$(docker inspect macrosquare-server --format '{{.Image}}')" \
  "$(docker inspect macrosquare-client --format '{{.Image}}')"
REMOTE

# The audit job is host-level operational plumbing rather than an application
# container. Re-installing it after every successful cutover keeps its absolute
# path and schedule aligned with the synchronized scripts, without duplicating
# crontab entries.
printf '== install daily audit and one-minute recurrence monitor ==\n'
ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" \
  "cd '$HOME_DIR' && ./scripts/install-home-observability-audit-cron.sh"

HOME_HOST="$HOME_HOST" HOME_DIR="$HOME_DIR" "$ROOT_DIR/scripts/verify-home.sh"
