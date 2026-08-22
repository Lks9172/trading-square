#!/usr/bin/env bash
set -Eeuo pipefail

HOME_HOST="${HOME_HOST:-lks@192.168.0.200}"
HOME_DIR="${HOME_DIR:-/home/lks/trading-square}"
BACKUP_ROOT="${BACKUP_ROOT:-/home/lks/macrosquare-backups}"
OFFHOST_BACKUP_ROOT="${OFFHOST_BACKUP_ROOT:-$HOME/MacroSquareBackups/trading-square}"
REMOTE_BACKUP_RETENTION="${REMOTE_BACKUP_RETENTION:-3}"
OFFHOST_BACKUP_RETENTION="${OFFHOST_BACKUP_RETENTION:-2}"
BACKUP_MAX_SERVER_PAUSE_SECONDS="${BACKUP_MAX_SERVER_PAUSE_SECONDS:-20}"
SSH_OPTIONS=(-o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4)
export RSYNC_RSH="ssh -o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4"

for command in ssh rsync python3; do
  command -v "$command" >/dev/null || {
    echo "required command not found: $command" >&2
    exit 1
  }
done
[[ "$REMOTE_BACKUP_RETENTION" =~ ^[1-9][0-9]*$ ]]
[[ "$OFFHOST_BACKUP_RETENTION" =~ ^[1-9][0-9]*$ ]]
[[ "$BACKUP_MAX_SERVER_PAUSE_SECONDS" =~ ^[1-9][0-9]*$ ]]

# Failed rsync attempts are never recovery points. Remove only strictly named
# partial directories, then prove that the Mac has enough room for a new copy
# before changing any home-server runtime state.
mkdir -p "$OFFHOST_BACKUP_ROOT"
chmod 700 "$OFFHOST_BACKUP_ROOT"
python3 - "$OFFHOST_BACKUP_ROOT" <<'PY'
import pathlib
import re
import shutil
import sys

root = pathlib.Path(sys.argv[1])
pattern = re.compile(r"^\.\d{8}T\d{6}Z\.partial$")
for path in root.iterdir():
    if path.is_dir() and pattern.fullmatch(path.name):
        shutil.rmtree(path)
PY
latest_remote_size_kb="$(ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" bash -s -- "$BACKUP_ROOT" <<'REMOTE_SIZE'
set -Eeuo pipefail
root=$1
latest="$(find "$root" -mindepth 1 -maxdepth 1 -type d \
  -regextype posix-extended -regex '.*/[0-9]{8}T[0-9]{6}Z' -printf '%p\n' 2>/dev/null \
  | sort | tail -n 1)"
if [[ -n "$latest" ]]; then du -sk "$latest" | awk '{print $1}'; else printf '1048576\n'; fi
REMOTE_SIZE
)"
[[ "$latest_remote_size_kb" =~ ^[1-9][0-9]*$ ]]
local_available_kb="$(df -Pk "$OFFHOST_BACKUP_ROOT" | awk 'NR == 2 {print $4}')"
[[ "$local_available_kb" =~ ^[0-9]+$ ]]
local_required_kb=$((latest_remote_size_kb + latest_remote_size_kb / 10 + 524288))
if (( local_available_kb < local_required_kb )); then
  # Keep the newest verified off-host point and prune only older complete
  # points under actual space pressure. The home server independently retains
  # three recent copies, so this never removes the sole recovery generation.
  python3 - "$OFFHOST_BACKUP_ROOT" "$local_required_kb" <<'PY'
import pathlib
import re
import shutil
import sys

root = pathlib.Path(sys.argv[1])
required = int(sys.argv[2]) * 1024
pattern = re.compile(r"^\d{8}T\d{6}Z$")
backups = sorted(path for path in root.iterdir() if path.is_dir() and pattern.fullmatch(path.name))
while len(backups) > 1 and shutil.disk_usage(root).free < required:
    shutil.rmtree(backups.pop(0))
PY
  local_available_kb="$(df -Pk "$OFFHOST_BACKUP_ROOT" | awk 'NR == 2 {print $4}')"
fi
if (( local_available_kb < local_required_kb )); then
  printf 'backup preflight failed: off-host available=%sKiB estimated-required=%sKiB; home server was not paused\n' \
    "$local_available_kb" "$local_required_kb" >&2
  exit 1
fi

# Pause only for the small relational snapshot boundary. The previous design
# held the JVM paused throughout the full MinIO mirror/checksum pass (about ten
# minutes), which correctly triggered Hikari's scheduler-stall warning and made
# health probes fail. Exact immutable MinIO versions are selected by the captured
# PostgreSQL pointers, so the expensive object copy runs after the JVM resumes.
remote_output="$(ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" bash -s -- \
  "$HOME_DIR" "$BACKUP_ROOT" "$BACKUP_MAX_SERVER_PAUSE_SECONDS" <<'REMOTE'
set -Eeuo pipefail
home_dir=$1
backup_root=$2
max_pause_seconds=$3
cd "$home_dir"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
target="$backup_root/.${stamp}.partial"
final_target="$backup_root/$stamp"
mkdir -p "$target/objects"
chmod 700 "$backup_root" "$target"
backup_complete=false

server_was_running="$(docker inspect macrosquare-server --format '{{.State.Running}}' 2>/dev/null || true)"
server_was_paused="$(docker inspect macrosquare-server --format '{{.State.Paused}}' 2>/dev/null || true)"
paused_by_backup=false

ensure_server_resumed() {
  if [[ "$paused_by_backup" == true ]]; then
    docker unpause macrosquare-server >/dev/null
    paused_by_backup=false
    for attempt in $(seq 1 60); do
      curl -fsS --max-time 5 http://127.0.0.1:5846/actuator/health/readiness >/dev/null 2>&1 && break
      sleep 2
      if [[ "$attempt" -eq 60 ]]; then
        echo 'backend did not recover after backup' >&2
        exit 1
      fi
    done
  fi
}

resume_server_on_exit() {
  local code=$?
  trap - EXIT
  ensure_server_resumed
  if [[ "$backup_complete" != true ]]; then
    rm -rf -- "$target"
  fi
  exit "$code"
}
trap resume_server_on_exit EXIT

capture_relational_snapshot() {
  set -Eeuo pipefail
  docker compose exec -T postgres pg_dump \
    --username macrosquare --dbname macrosquare --format=custom --no-owner --no-acl \
    </dev/null >"$target/postgres.dump"

  docker compose exec -T postgres psql --username macrosquare --dbname macrosquare \
    --no-align --tuples-only --field-separator=$'\t' --command "
      select p.object_key, a.version_id, a.checksum_sha256
      from storage.object_pointer p
      join storage.object_artifact a on a.id = p.artifact_id
      order by p.object_key
    " </dev/null >"$target/active-objects.tsv"

  docker compose exec -T postgres psql --username macrosquare --dbname macrosquare \
    --no-align --tuples-only --field-separator=$'\t' --command "
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
    " </dev/null >"$target/row-counts.tsv"
  if [[ "$(docker compose exec -T postgres psql -U macrosquare -d macrosquare -Atc \
    "select to_regclass('notification.outbox') is not null" </dev/null)" == t ]]; then
    docker compose exec -T postgres psql -U macrosquare -d macrosquare -AtF $'\t' -c \
      "select 'notification.outbox', count(*) from notification.outbox" \
      </dev/null >>"$target/row-counts.tsv"
  else
    printf 'notification.outbox\t0\n' >>"$target/row-counts.tsv"
  fi
  sort -o "$target/row-counts.tsv" "$target/row-counts.tsv"
}
export -f capture_relational_snapshot
export target

if [[ "$server_was_running" == true && "$server_was_paused" != true ]]; then
  docker pause macrosquare-server >/dev/null
  paused_by_backup=true
fi
pause_started_epoch="$(date +%s)"
timeout --foreground "${max_pause_seconds}s" bash -c capture_relational_snapshot
ensure_server_resumed
pause_elapsed_seconds=$(( $(date +%s) - pause_started_epoch ))
printf 'backend relational snapshot pause: %ss (limit=%ss)\n' \
  "$pause_elapsed_seconds" "$max_pause_seconds"

# First copy the complete current bucket. Then overwrite every mutable logical
# key with the exact version selected by PostgreSQL, so an unreferenced newer
# MinIO version can never sneak into a supposedly consistent backup.
cat >"$target/.copy-exact-objects.sh" <<'MINIO_COPY'
#!/bin/sh
set -eu
mc alias set source http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
mc mirror --overwrite "source/$MINIO_BUCKET" /backup/objects >/dev/null
tab="$(printf "\t")"
while IFS="$tab" read -r object_key version_id expected_sha; do
  [ -n "$object_key" ] || continue
  mkdir -p "/backup/objects/$(dirname "$object_key")"
  if [ -n "$version_id" ]; then
    mc cp --quiet --version-id "$version_id" \
      "source/$MINIO_BUCKET/$object_key" "/backup/objects/$object_key" >/dev/null
  else
    mc cp --quiet "source/$MINIO_BUCKET/$object_key" "/backup/objects/$object_key" >/dev/null
  fi
done </backup/active-objects.tsv
chown -R "$BACKUP_UID:$BACKUP_GID" /backup
MINIO_COPY
chmod 700 "$target/.copy-exact-objects.sh"
docker compose run --rm --no-deps \
  -e BACKUP_UID="$(id -u)" \
  -e BACKUP_GID="$(id -g)" \
  -v "$target:/backup" \
  --entrypoint /bin/sh minio-init /backup/.copy-exact-objects.sh </dev/null
rm -f "$target/.copy-exact-objects.sh"

# Verify body hashes selected by relational pointers before declaring success.
while IFS=$'\t' read -r object_key _ expected_sha; do
  [[ -n "$object_key" ]] || continue
  actual_sha="$(sha256sum "$target/objects/$object_key" | awk '{print $1}')"
  [[ "$actual_sha" == "$expected_sha" ]]
done <"$target/active-objects.tsv"

cat >"$target/MANIFEST" <<MANIFEST
schema=macrosquare-backup-v1
created_at=$stamp
postgres_format=custom
object_mode=current-plus-exact-active-versions
MANIFEST
(
  cd "$target"
  find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum >SHA256SUMS
)
chmod -R go-rwx "$target"
mv "$target" "$final_target"
backup_complete=true
printf 'backup complete: %s\n' "$final_target"
printf 'BACKUP_PATH=%s\n' "$final_target"
REMOTE
)"

printf '%s\n' "$remote_output"
remote_target="$(printf '%s\n' "$remote_output" | sed -n 's/^BACKUP_PATH=//p' | tail -n 1)"
stamp="${remote_target##*/}"
[[ "$remote_target" == "$BACKUP_ROOT/$stamp" && "$stamp" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || {
  echo "unable to identify the completed remote backup" >&2
  exit 1
}
local_temporary="$OFFHOST_BACKUP_ROOT/.${stamp}.partial"
local_target="$OFFHOST_BACKUP_ROOT/$stamp"
rm -rf "$local_temporary"
mkdir -p "$local_temporary"
local_copy_complete=false
cleanup_local_partial() {
  local code=$?
  trap - EXIT
  if [[ "$local_copy_complete" != true ]]; then
    rm -rf -- "$local_temporary"
  fi
  exit "$code"
}
trap cleanup_local_partial EXIT

echo "copying backup to off-host path: $local_target"
rsync -a --delete --partial \
  "$HOME_HOST:$remote_target/" "$local_temporary/"

python3 - "$local_temporary" <<'PY'
import hashlib
import pathlib
import sys

root = pathlib.Path(sys.argv[1]).resolve()
manifest = (root / "MANIFEST").read_text(encoding="utf-8")
if "schema=macrosquare-backup-v1" not in manifest:
    raise SystemExit("unsupported backup manifest")
lines = (root / "SHA256SUMS").read_text(encoding="utf-8").splitlines()
if not lines:
    raise SystemExit("empty backup checksum manifest")
for line in lines:
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
print(f"offHostChecksumFiles={len(lines)}")
PY

rm -rf "$local_target"
mv "$local_temporary" "$local_target"
local_copy_complete=true
trap - EXIT
ln -sfn "$stamp" "$OFFHOST_BACKUP_ROOT/LATEST"

# Retain a small same-host staging set and a longer off-host recovery history.
ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" bash -s -- \
  "$BACKUP_ROOT" "$REMOTE_BACKUP_RETENTION" <<'REMOTE_PRUNE'
set -Eeuo pipefail
root=$1
keep=$2
mapfile -t backups < <(find "$root" -mindepth 1 -maxdepth 1 -type d \
  -regextype posix-extended -regex '.*/[0-9]{8}T[0-9]{6}Z' -printf '%f\n' | sort)
remove_count=$((${#backups[@]} - keep))
if (( remove_count > 0 )); then
  for name in "${backups[@]:0:remove_count}"; do
    rm -rf -- "$root/$name"
  done
fi
REMOTE_PRUNE

python3 - "$OFFHOST_BACKUP_ROOT" "$OFFHOST_BACKUP_RETENTION" <<'PY'
import pathlib, re, shutil, sys
root = pathlib.Path(sys.argv[1])
keep = int(sys.argv[2])
pattern = re.compile(r"^[0-9]{8}T[0-9]{6}Z$")
backups = sorted(path for path in root.iterdir() if path.is_dir() and pattern.match(path.name))
for path in backups[:-keep]:
    shutil.rmtree(path)
print(f"offHostBackup={root / backups[-1].name} retained={min(len(backups), keep)}")
PY
