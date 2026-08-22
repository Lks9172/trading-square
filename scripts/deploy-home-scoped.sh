#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOME_HOST="${HOME_HOST:-lks@192.168.0.200}"
HOME_DIR="${HOME_DIR:-/home/lks/trading-square}"
scope="${1:-}"
SSH_OPTIONS=(-o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=40 -o TCPKeepAlive=yes)
export RSYNC_RSH="ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=40 -o TCPKeepAlive=yes"

case "$scope" in server|client|scripts|docs|verify) ;; *) echo "invalid scoped deployment: $scope" >&2; exit 2 ;; esac
for command in ssh rsync; do command -v "$command" >/dev/null || { echo "required command not found: $command" >&2; exit 1; }; done

local_available_kb="$(df -Pk "$ROOT_DIR" | awk 'NR == 2 {print $4}')"
remote_available_kb="$(ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" \
  "test -d '$HOME_DIR' && df -Pk '$HOME_DIR' | awk 'NR == 2 {print \$4}'")"
[[ "$local_available_kb" =~ ^[0-9]+$ && "$remote_available_kb" =~ ^[0-9]+$ ]]
(( local_available_kb >= ${DEPLOY_LOCAL_MIN_AVAILABLE_KB:-131072} )) || { echo 'insufficient local deployment disk' >&2; exit 1; }
(( remote_available_kb >= ${DEPLOY_REMOTE_MIN_AVAILABLE_KB:-4194304} )) || { echo 'insufficient home-server deployment disk' >&2; exit 1; }

if [[ "$scope" == verify ]]; then
  HOME_HOST="$HOME_HOST" HOME_DIR="$HOME_DIR" exec "$ROOT_DIR/scripts/verify-home.sh"
fi

if [[ "$scope" == docs ]]; then
  python3 "$ROOT_DIR/scripts/verify-documentation.py"
  rsync -az --delete "$ROOT_DIR/docs/" "$HOME_HOST:$HOME_DIR/docs/"
  rsync -az --delete "$ROOT_DIR/server-spring/docs/" "$HOME_HOST:$HOME_DIR/server-spring/docs/"
  rsync -az "$ROOT_DIR/README.md" "$HOME_HOST:$HOME_DIR/"
  ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" "cd '$HOME_DIR' && python3 scripts/verify-documentation.py"
  echo 'docs deployment complete: application containers were not restarted'
  exit 0
fi

"$ROOT_DIR/scripts/check-cutover-invariants.sh"

sync_common() {
  rsync -az --delete --exclude __pycache__ --exclude '*.pyc' --exclude .pytest_cache \
    "$ROOT_DIR/scripts/" "$HOME_HOST:$HOME_DIR/scripts/"
  rsync -az --delete "$ROOT_DIR/docs/" "$HOME_HOST:$HOME_DIR/docs/"
  rsync -az --delete "$ROOT_DIR/server-spring/docs/" "$HOME_HOST:$HOME_DIR/server-spring/docs/"
  rsync -az "$ROOT_DIR/README.md" "$HOME_HOST:$HOME_DIR/"
}

if [[ "$scope" == scripts ]]; then
  sync_common
  ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" \
    "cd '$HOME_DIR' && bash scripts/check-cutover-invariants.sh && ./scripts/install-home-observability-audit-cron.sh"
  HOME_HOST="$HOME_HOST" HOME_DIR="$HOME_DIR" "$ROOT_DIR/scripts/verify-home.sh"
  echo 'scripts deployment complete: application containers were not restarted'
  exit 0
fi

stamp="$(date +%Y%m%d-%H%M%S)"
sync_common

if [[ "$scope" == server ]]; then
  rsync -az --delete --exclude target --exclude '.idea' \
    "$ROOT_DIR/server-spring/" "$HOME_HOST:$HOME_DIR/server-spring/"

  if [[ "${DEPLOY_SERVER_RELEASE:-false}" == true ]]; then
    # A release-scope persistence change must have a recent verified recovery
    # point, but does not synchronously recopy the entire 4.7GiB object store.
    ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" bash -s -- "$HOME_DIR" <<'REMOTE_RELEASE'
set -Eeuo pipefail
cd "$1"
latest="$(find /home/lks/macrosquare-backups -mindepth 1 -maxdepth 1 -type d \
  -regextype posix-extended -regex '.*/[0-9]{8}T[0-9]{6}Z' -printf '%T@ %p\n' | sort -n | tail -n 1 | cut -d' ' -f2-)"
[[ -n "$latest" && -s "$latest/MANIFEST" && -s "$latest/SHA256SUMS" && -s "$latest/postgres.dump" ]]
age=$(( $(date +%s) - $(stat -c %Y "$latest/MANIFEST") ))
(( age <= 172800 )) || { echo 'latest verified backup is older than 48 hours' >&2; exit 1; }
./scripts/test-postgres-multi-instance.sh
REMOTE_RELEASE
  fi

  ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" bash -s -- "$HOME_DIR" "$stamp" <<'REMOTE_SERVER'
set -Eeuo pipefail
cd "$1"
stamp=$2
previous_image="$(docker inspect macrosquare-server --format '{{.Image}}')"
rollback_tag="macrosquare-server-spring:rollback-scoped-$stamp"
preflight="macrosquare-server-scoped-preflight-$stamp"
prometheus_metrics="/tmp/macrosquare-scoped-prometheus-$stamp.txt"
cutover=false

wait_url() {
  local url=$1 attempts=${2:-60}
  for _ in $(seq 1 "$attempts"); do curl -fsS --max-time 5 "$url" >/dev/null 2>&1 && return 0; sleep 2; done
  return 1
}
rollback() {
  local code=$?
  trap - ERR
  set +e
  docker rm -f "$preflight" >/dev/null 2>&1 || true
  rm -f "$prometheus_metrics"
  docker tag "$previous_image" macrosquare-server-spring:production || true
  if [[ "$cutover" == true ]]; then
    docker compose up -d --no-deps --force-recreate server || true
    wait_url http://127.0.0.1:5846/actuator/health/readiness 60 || true
  fi
  # The restored production tag/container already retain the previous image.
  # Leaving this temporary tag makes the next healthy deployment fail its
  # stale-rollback-tag invariant.
  docker image rm "$rollback_tag" >/dev/null 2>&1 || true
  exit "$code"
}
trap rollback ERR

docker compose config --quiet
docker tag "$previous_image" "$rollback_tag"
docker compose build server
expected="$(docker image inspect macrosquare-server-spring:production --format '{{.Id}}')"

docker compose run -d --no-deps --name "$preflight" \
  -e PORT=15846 -e TELEGRAM_NOTIFICATIONS_ENABLED=false \
  -e MARKET_COLLECTION_ENABLED=false -e MARKET_HISTORY_SEED_ENABLED=false \
  -e MARKET_SNAPSHOT_REFRESH_ENABLED=false -e COMPANY_ANALYST_HISTORY_ENABLED=false \
  -e COMPANY_RESEARCH_SUMMARY_ENABLED=false -e INSTITUTIONAL_COLLECTION_ENABLED=false \
  -e POLICY_COLLECTION_ENABLED=false -e PEER_DISCOVERY_ENABLED=false \
  -e NARRATIVE_SOURCE_COLLECTION_ENABLED=false -e DART_COLLECTION_ENABLED=false \
  -e TRACING_EXPORT_ENABLED=false server </dev/null >/dev/null
for _ in $(seq 1 60); do
  health="$(docker inspect "$preflight" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>/dev/null || true)"
  [[ "$health" == healthy ]] && break
  [[ "$health" != unhealthy && "$health" != exited && "$health" != dead ]] || { docker logs --tail 200 "$preflight" >&2; false; }
  sleep 2
done
[[ "${health:-}" == healthy ]]
docker rm -f "$preflight" >/dev/null
preflight=""

cutover=true
docker compose up -d --no-deps --force-recreate server
wait_url http://127.0.0.1:5846/actuator/health/readiness 60
[[ "$(docker inspect macrosquare-server --format '{{.Image}}')" == "$expected" ]]
curl -fsS --max-time 120 -X POST -H 'Content-Type: application/json' http://127.0.0.1:5846/api/refresh >/dev/null

ready=false
expected_summary_total="$(
  docker inspect macrosquare-server --format '{{range .Config.Env}}{{println .}}{{end}}' \
    | sed -n 's/^DATA_INTEGRITY_EXPECTED_COMPANY_UNIVERSE=//p' \
    | tail -n 1
)"
[[ "$expected_summary_total" =~ ^[1-9][0-9]*$ ]]
for _ in $(seq 1 450); do
  read -r total current <<<"$(docker compose exec -T postgres psql -U macrosquare -d macrosquare -AtF' ' -c \
    'select count(*), count(*) filter (where calculation_version = 6) from company.research_summary' </dev/null)"
  if [[ "$total" -eq "$expected_summary_total" \
      && "$current" -eq "$expected_summary_total" ]]; then ready=true; break; fi
  sleep 2
done
[[ "$ready" == true ]] || {
  printf 'company decision projection did not converge: current=%s total=%s expected=%s\n' \
    "${current:-unknown}" "${total:-unknown}" "$expected_summary_total" >&2
  false
}

smoke="/tmp/macrosquare-scoped-smoke-$stamp.json"
python3 server-spring/migration/tools/smoke-production-api.py --base-url http://127.0.0.1:5846 >"$smoke"
python3 - "$smoke" <<'PY'
import json,sys
result=json.load(open(sys.argv[1],encoding='utf-8'))
assert result['passed'] == 43, result
PY
rm -f "$smoke"
# Do not pipe a large Prometheus response into `grep -q` under pipefail.
# grep exits after the first match and makes curl report a write error (23),
# which would roll back an otherwise valid cutover.
curl -fsS --max-time 15 http://127.0.0.1:5846/actuator/prometheus >"$prometheus_metrics"
grep -q '^jvm_memory_used_bytes' "$prometheus_metrics"
rm -f "$prometheus_metrics"

trap - ERR
docker image rm "$rollback_tag" >/dev/null 2>&1 || true
printf 'scoped server deployed image=%s\n' "$expected"
REMOTE_SERVER

elif [[ "$scope" == client ]]; then
  rsync -az --delete --exclude node_modules --exclude .next --exclude '.env*' \
    "$ROOT_DIR/client/" "$HOME_HOST:$HOME_DIR/client/"

  ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" bash -s -- "$HOME_DIR" "$stamp" <<'REMOTE_CLIENT'
set -Eeuo pipefail
cd "$1"
stamp=$2
previous_image="$(docker inspect macrosquare-client --format '{{.Image}}')"
rollback_tag="macrosquare-client:rollback-scoped-$stamp"
cutover=false
wait_url() {
  local url=$1 attempts=${2:-60}
  for _ in $(seq 1 "$attempts"); do curl -fsS --max-time 5 "$url" >/dev/null 2>&1 && return 0; sleep 2; done
  return 1
}
wait_healthy() {
  local container=$1 attempts=${2:-45} health
  for _ in $(seq 1 "$attempts"); do
    health="$(docker inspect "$container" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>/dev/null || true)"
    [[ "$health" == healthy ]] && return 0
    [[ "$health" != unhealthy && "$health" != exited && "$health" != dead ]] || return 1
    sleep 2
  done
  return 1
}
rollback() {
  local code=$?
  trap - ERR
  set +e
  docker tag "$previous_image" macrosquare-client:production || true
  if [[ "$cutover" == true ]]; then docker compose up -d --no-deps --force-recreate client || true; wait_url http://127.0.0.1:5847/ 45 || true; fi
  docker image rm "$rollback_tag" >/dev/null 2>&1 || true
  exit "$code"
}
trap rollback ERR
docker compose config --quiet
docker tag "$previous_image" "$rollback_tag"
docker compose build client
expected="$(docker image inspect macrosquare-client:production --format '{{.Id}}')"
cutover=true
docker compose up -d --no-deps --force-recreate client
wait_healthy macrosquare-client 45
wait_url http://127.0.0.1:5847/ 45
[[ "$(docker inspect macrosquare-client --format '{{.Image}}')" == "$expected" ]]
for route in / /research/sectors '/research/companies?page=2' /company/NVDA /research/crypto; do curl -fsS --max-time 30 "http://127.0.0.1:5847$route" >/dev/null; done
trap - ERR
docker image rm "$rollback_tag" >/dev/null 2>&1 || true
printf 'scoped client deployed image=%s\n' "$expected"
REMOTE_CLIENT
fi

ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" "cd '$HOME_DIR' && ./scripts/install-home-observability-audit-cron.sh"
HOME_HOST="$HOME_HOST" HOME_DIR="$HOME_DIR" "$ROOT_DIR/scripts/verify-home.sh"
printf '%s deployment complete\n' "$scope"
