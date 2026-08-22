#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOME_HOST="${HOME_HOST:-lks@192.168.0.200}"
HOME_DIR="${HOME_DIR:-/home/lks/trading-square}"
OFFHOST_BACKUP_ROOT="${OFFHOST_BACKUP_ROOT:-$HOME/MacroSquareBackups/trading-square}"
BACKUP_PATH="${BACKUP_PATH:-$OFFHOST_BACKUP_ROOT/LATEST}"
REMOTE_DRILL_ROOT="${REMOTE_DRILL_ROOT:-/home/lks/macrosquare-restore-drills}"
POSTGRES_IMAGE="${POSTGRES_RESTORE_IMAGE:-postgres:18.4-alpine3.24}"
MINIO_IMAGE="${MINIO_RESTORE_IMAGE:-quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z}"
MC_IMAGE="${MC_RESTORE_IMAGE:-quay.io/minio/mc:RELEASE.2025-08-13T08-35-41Z}"
SSH_OPTIONS=(-o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4)
export RSYNC_RSH="ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4"

for command in ssh rsync python3; do
  command -v "$command" >/dev/null || {
    echo "required command not found: $command" >&2
    exit 1
  }
done

BACKUP_PATH="$(python3 - "$BACKUP_PATH" <<'PY'
import pathlib, sys
print(pathlib.Path(sys.argv[1]).expanduser().resolve(strict=True))
PY
)"
[[ -d "$BACKUP_PATH" && -f "$BACKUP_PATH/postgres.dump" && -f "$BACKUP_PATH/SHA256SUMS" ]]

python3 - "$BACKUP_PATH" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1]).resolve()
for line in (root / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
    expected, raw_name = line.split(maxsplit=1)
    name = raw_name.lstrip(" *")
    if name.startswith("./"):
        name = name[2:]
    target = (root / name).resolve()
    if root not in target.parents or not target.is_file():
        raise SystemExit(f"unsafe or missing backup member: {name}")
    digest = hashlib.sha256()
    with target.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        raise SystemExit(f"checksum mismatch: {name}")
print("localBackupChecksums=verified")
PY

drill_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
remote_drill="$REMOTE_DRILL_ROOT/$drill_id"
report_root="$OFFHOST_BACKUP_ROOT/restore-drills"
report="$report_root/$drill_id.log"
mkdir -p "$report_root"
chmod 700 "$report_root"

ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" "mkdir -p '$remote_drill/backup' && chmod 700 '$remote_drill'"
rsync -a --delete "$BACKUP_PATH/" "$HOME_HOST:$remote_drill/backup/"

set +e
ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" bash -s -- \
  "$HOME_DIR" "$remote_drill" "$drill_id" "$POSTGRES_IMAGE" "$MINIO_IMAGE" "$MC_IMAGE" \
  2>&1 <<'REMOTE' | tee "$report"
set -Eeuo pipefail
home_dir=$1
drill_root=$2
drill_id=$3
postgres_image=$4
minio_image=$5
mc_image=$6
cd "$home_dir"

backup="$drill_root/backup"
network="macrosquare-restore-$drill_id"
pg_container="macrosquare-restore-pg-$drill_id"
minio_container="macrosquare-restore-minio-$drill_id"
server_container="macrosquare-restore-server-$drill_id"
pg_volume="macrosquare-restore-pg-$drill_id"
minio_volume="macrosquare-restore-minio-$drill_id"
password="$(openssl rand -hex 24)"
minio_user="restoreadmin"
minio_password="$(openssl rand -hex 24)"
bucket="$(sed -n 's/^MINIO_BUCKET=//p' .env | tail -n 1)"
bucket="${bucket:-macrosquare-artifacts}"
[[ "$bucket" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]]

cleanup() {
  local code=$?
  trap - EXIT
  docker logs "$server_container" >"$drill_root/server.log" 2>&1 || true
  docker rm -f "$server_container" "$pg_container" "$minio_container" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  docker volume rm "$pg_volume" "$minio_volume" >/dev/null 2>&1 || true
  # Verification files are written by the root user inside the short-lived mc
  # container. Remove them through the same boundary, then remove the directory
  # as the unprivileged host user without leaving drill data behind.
  docker run --rm -v "$drill_root:/drill" --entrypoint /bin/sh "$mc_image" \
    -ec 'rm -rf /drill/* /drill/.[!.]* /drill/..?* 2>/dev/null || true' \
    >/dev/null 2>&1 || true
  rm -rf "$drill_root" 2>/dev/null || true
  exit "$code"
}
trap cleanup EXIT

python3 - "$backup" <<'PY'
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1]).resolve()
for line in (root / "SHA256SUMS").read_text().splitlines():
    expected, raw_name = line.split(maxsplit=1)
    name = raw_name.lstrip(" *")
    if name.startswith("./"):
        name = name[2:]
    target = (root / name).resolve()
    if root not in target.parents or not target.is_file():
        raise SystemExit(f"unsafe remote backup member: {name}")
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    if digest != expected:
        raise SystemExit(f"remote checksum mismatch: {name}")
print("remoteBackupChecksums=verified")
PY

docker network create "$network" >/dev/null
docker volume create "$pg_volume" >/dev/null
docker volume create "$minio_volume" >/dev/null
docker run -d --name "$pg_container" --network "$network" \
  -e POSTGRES_DB=macrosquare -e POSTGRES_USER=macrosquare -e "POSTGRES_PASSWORD=$password" \
  -v "$pg_volume:/var/lib/postgresql" "$postgres_image" >/dev/null
docker run -d --name "$minio_container" --network "$network" \
  -e "MINIO_ROOT_USER=$minio_user" -e "MINIO_ROOT_PASSWORD=$minio_password" \
  -v "$minio_volume:/data" "$minio_image" server /data --console-address :9001 >/dev/null

for _ in $(seq 1 60); do
  docker exec "$pg_container" pg_isready -U macrosquare -d macrosquare >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$pg_container" pg_isready -U macrosquare -d macrosquare >/dev/null
for _ in $(seq 1 60); do
  docker run --rm --network "$network" "$mc_image" \
    mc alias set restore "http://$minio_container:9000" "$minio_user" "$minio_password" \
    >/dev/null 2>&1 && break
  sleep 1
done

docker run --rm --network "$network" -v "$backup:/backup:ro" \
  --entrypoint /bin/sh "$mc_image" -ec '
    mc alias set restore "http://'"$minio_container"':9000" "'"$minio_user"'" "'"$minio_password"'" >/dev/null
    mc mb --ignore-existing "restore/'"$bucket"'" >/dev/null
    mc version enable "restore/'"$bucket"'" >/dev/null
    mc anonymous set none "restore/'"$bucket"'" >/dev/null
    mc mirror --overwrite /backup/objects "restore/'"$bucket"'" >/dev/null
  '

docker exec -i "$pg_container" pg_restore \
  --username macrosquare --dbname macrosquare --no-owner --no-acl --exit-on-error \
  <"$backup/postgres.dump"

docker exec "$pg_container" psql -v ON_ERROR_STOP=1 -U macrosquare -d macrosquare -AtF $'\t' -c "
  select 'market.observation', count(*) from market.observation
  union all select 'company.analyst_series_state', count(*) from company.analyst_series_state
  union all select 'company.analyst_snapshot', count(*) from company.analyst_snapshot
  union all select 'execution.investment_plan', count(*) from execution.investment_plan
  union all select 'execution.tranche_entry', count(*) from execution.tranche_entry
  union all select 'execution.trade_log', count(*) from execution.trade_log
  union all select 'notification.delivery_state', count(*) from notification.delivery_state
  union all select 'storage.object_artifact', count(*) from storage.object_artifact
  union all select 'storage.object_pointer', count(*) from storage.object_pointer
  order by 1
" >"$drill_root/restored-row-counts.tsv"
if [[ "$(docker exec "$pg_container" psql -U macrosquare -d macrosquare -Atc \
  "select to_regclass('notification.outbox') is not null")" == t ]]; then
  docker exec "$pg_container" psql -U macrosquare -d macrosquare -AtF $'\t' -c \
    "select 'notification.outbox', count(*) from notification.outbox" \
    >>"$drill_root/restored-row-counts.tsv"
else
  printf 'notification.outbox\t0\n' >>"$drill_root/restored-row-counts.tsv"
fi
sort -o "$drill_root/restored-row-counts.tsv" "$drill_root/restored-row-counts.tsv"
diff -u "$backup/row-counts.tsv" "$drill_root/restored-row-counts.tsv"
echo "relationalRowCounts=verified"

dangling="$(docker exec "$pg_container" psql -U macrosquare -d macrosquare -Atc \
  'select count(*) from storage.object_pointer p left join storage.object_artifact a on a.id = p.artifact_id where a.id is null')"
[[ "$dangling" -eq 0 ]]

mkdir -p "$drill_root/verified-objects"
docker exec "$pg_container" psql -U macrosquare -d macrosquare -AtF $'\t' -c \
  'select p.object_key, a.checksum_sha256 from storage.object_pointer p join storage.object_artifact a on a.id = p.artifact_id order by p.object_key' \
  >"$drill_root/restored-active.tsv"
docker run --rm --network "$network" \
  -v "$drill_root:/drill" --entrypoint /bin/sh "$mc_image" -ec '
    mc alias set restore "http://'"$minio_container"':9000" "'"$minio_user"'" "'"$minio_password"'" >/dev/null
    tab="$(printf "\t")"
    while IFS="$tab" read -r key expected; do
      [ -n "$key" ] || continue
      mkdir -p "/drill/verified-objects/$(dirname "$key")"
      mc cp --quiet "restore/'"$bucket"'/$key" "/drill/verified-objects/$key" >/dev/null
    done </drill/restored-active.tsv
  '
while IFS=$'\t' read -r key expected; do
  [[ -n "$key" ]] || continue
  actual="$(sha256sum "$drill_root/verified-objects/$key" | awk '{print $1}')"
  [[ "$actual" == "$expected" ]]
done <"$drill_root/restored-active.tsv"
echo "restoredActiveObjectHashes=verified count=$(wc -l <"$drill_root/restored-active.tsv")"

server_image="$(docker inspect macrosquare-server --format '{{.Config.Image}}')"
docker run -d --name "$server_container" --network "$network" --memory 1g \
  -e PORT=5846 \
  -e STORAGE_MODE=postgres-minio \
  -e "DATABASE_URL=jdbc:postgresql://$pg_container:5432/macrosquare" \
  -e DATABASE_USERNAME=macrosquare -e "DATABASE_PASSWORD=$password" \
  -e FLYWAY_ENABLED=true \
  -e "OBJECT_STORAGE_ENDPOINT=http://$minio_container:9000" \
  -e "MINIO_ACCESS_KEY=$minio_user" -e "MINIO_SECRET_KEY=$minio_password" -e "MINIO_BUCKET=$bucket" \
  -e TELEGRAM_NOTIFICATIONS_ENABLED=false \
  -e MARKET_COLLECTION_ENABLED=false -e MARKET_HISTORY_SEED_ENABLED=false \
  -e MARKET_SNAPSHOT_REFRESH_ENABLED=false -e COMPANY_ANALYST_HISTORY_ENABLED=false \
  -e TRACING_EXPORT_ENABLED=false \
  "$server_image" >/dev/null

for _ in $(seq 1 90); do
  status="$(docker inspect "$server_container" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')"
  [[ "$status" == healthy ]] && break
  [[ "$status" != unhealthy && "$status" != exited && "$status" != dead ]] || {
    docker logs --tail 240 "$server_container" >&2
    exit 1
  }
  sleep 2
done
[[ "$(docker inspect "$server_container" --format '{{.State.Health.Status}}')" == healthy ]]
docker exec "$server_container" wget -q -O /dev/null http://127.0.0.1:5846/actuator/health/readiness
docker exec "$server_container" wget -q -O /dev/null http://127.0.0.1:5846/api/snapshot
echo "restoredSpringReadiness=healthy"

dangling_after="$(docker exec "$pg_container" psql -U macrosquare -d macrosquare -Atc \
  'select count(*) from storage.object_pointer p left join storage.object_artifact a on a.id = p.artifact_id where a.id is null')"
[[ "$dangling_after" -eq 0 ]]
echo "restoreDrill=PASS id=$drill_id"
REMOTE
status=${PIPESTATUS[0]}
set -e

if [[ $status -ne 0 ]]; then
  echo "restore drill failed; report: $report" >&2
  exit "$status"
fi
echo "restore drill passed; report: $report"
