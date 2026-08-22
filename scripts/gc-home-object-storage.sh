#!/usr/bin/env bash
set -Eeuo pipefail

HOME_HOST="${HOME_HOST:-lks@192.168.0.200}"
HOME_DIR="${HOME_DIR:-/home/lks/trading-square}"
RETENTION_DAYS="${MINIO_ORPHAN_RETENTION_DAYS:-30}"
MODE="dry-run"
SSH_OPTIONS=(-o BatchMode=yes -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=4)

usage() {
  cat <<'USAGE'
Usage: gc-home-object-storage.sh [--dry-run|--apply] [--retention-days N]

Only versions under the Spring-owned projections/ and sec-filings/ prefixes are
eligible. A version is removed only when it is absent from storage.object_artifact,
is not selected by storage.object_pointer, and is older than the retention period.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) MODE="dry-run"; shift ;;
    --apply) MODE="apply"; shift ;;
    --retention-days) RETENTION_DAYS="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

[[ "$RETENTION_DAYS" =~ ^[0-9]+$ ]] && (( RETENTION_DAYS >= 7 && RETENTION_DAYS <= 3650 )) || {
  echo "retention days must be an integer between 7 and 3650" >&2
  exit 2
}

ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" bash -s -- \
  "$HOME_DIR" "$MODE" "$RETENTION_DAYS" <<'REMOTE'
set -Eeuo pipefail
home_dir=$1
mode=$2
retention_days=$3
cd "$home_dir"

work="$(mktemp -d /tmp/macrosquare-minio-gc.XXXXXX)"
cleanup() { rm -rf "$work"; }
trap cleanup EXIT

inventory="$work/minio-versions.jsonl"
catalog="$work/catalog.tsv"
pointers="$work/pointers.tsv"
candidates="$work/candidates.tsv"

# Root/admin credentials live only inside this short-lived maintenance container.
# The application service account deliberately keeps no delete permission.
docker compose run --rm --no-deps \
  -v "$work:/work" \
  --entrypoint /bin/sh minio-init -ec '
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
    mc ls --versions --recursive --json "local/$MINIO_BUCKET" > /work/minio-versions.jsonl
  ' </dev/null

docker compose exec -T postgres psql -v ON_ERROR_STOP=1 \
  -U macrosquare -d macrosquare -AtF $'\t' -c \
  "select bucket, object_key, version_id from storage.object_artifact order by bucket, object_key, version_id" \
  </dev/null >"$catalog"
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 \
  -U macrosquare -d macrosquare -AtF $'\t' -c \
  "select a.bucket, a.object_key, a.version_id
     from storage.object_pointer p
     join storage.object_artifact a on a.id = p.artifact_id
    order by a.bucket, a.object_key, a.version_id" \
  </dev/null >"$pointers"

python3 - "$inventory" "$catalog" "$pointers" "$candidates" "$retention_days" <<'PY'
import datetime as dt
import json
import pathlib
import sys

inventory_path, catalog_path, pointers_path, output_path, retention_days = sys.argv[1:]
retention = dt.timedelta(days=int(retention_days))
now = dt.datetime.now(dt.timezone.utc)

def identities(path):
    result = set()
    for raw in pathlib.Path(path).read_text(encoding="utf-8").splitlines():
        if not raw:
            continue
        parts = raw.split("\t")
        if len(parts) != 3:
            raise SystemExit(f"invalid catalog row: {raw!r}")
        result.add(tuple(parts))
    return result

catalog = identities(catalog_path)
pointers = identities(pointers_path)
candidates = []
seen = set()
for line in pathlib.Path(inventory_path).read_text(encoding="utf-8").splitlines():
    if not line:
        continue
    value = json.loads(line)
    if value.get("status") != "success" or value.get("type") != "file":
        continue
    key = value.get("key", "")
    if not (key.startswith("projections/") or key.startswith("sec-filings/")):
        continue
    version = value.get("versionId") or ""
    bucket = (value.get("url") or "").rstrip("/").rsplit("/", 1)[-1]
    identity = (bucket, key, version)
    if not bucket or not version or identity in seen:
        continue
    seen.add(identity)
    modified = dt.datetime.fromisoformat(value["lastModified"].replace("Z", "+00:00"))
    if now - modified < retention:
        continue
    if identity in catalog or identity in pointers:
        continue
    candidates.append((bucket, key, version, value["lastModified"], int(value.get("size", 0))))

candidates.sort(key=lambda row: (row[3], row[0], row[1], row[2]))
with pathlib.Path(output_path).open("w", encoding="utf-8", newline="") as handle:
    for row in candidates:
        if any("\t" in str(part) or "\n" in str(part) for part in row):
            raise SystemExit("unsafe object identity")
        handle.write("\t".join(map(str, row)) + "\n")

print(f"managedVersions={len(seen)} orphanCandidates={len(candidates)} "
      f"candidateBytes={sum(row[4] for row in candidates)} retentionDays={retention.days}")
for bucket, key, version, modified, size in candidates[:20]:
    print(f"candidate {modified} {size}B {bucket}/{key} version={version}")
if len(candidates) > 20:
    print(f"... {len(candidates) - 20} additional candidates omitted")
PY

if [[ ! -s "$candidates" ]]; then
  echo "MinIO orphan GC: nothing eligible"
  exit 0
fi
if [[ "$mode" != apply ]]; then
  echo "MinIO orphan GC: dry-run only (use --apply after reviewing the list)"
  exit 0
fi

# Re-read PostgreSQL immediately before deletion. Any identity that became
# catalogued or active after inventory is removed from the approved set.
catalog_after="$work/catalog-after.tsv"
pointers_after="$work/pointers-after.tsv"
approved="$work/approved.tsv"
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 \
  -U macrosquare -d macrosquare -AtF $'\t' -c \
  "select bucket, object_key, version_id from storage.object_artifact" \
  </dev/null >"$catalog_after"
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 \
  -U macrosquare -d macrosquare -AtF $'\t' -c \
  "select a.bucket, a.object_key, a.version_id
     from storage.object_pointer p join storage.object_artifact a on a.id = p.artifact_id" \
  </dev/null >"$pointers_after"
python3 - "$candidates" "$catalog_after" "$pointers_after" "$approved" <<'PY'
import pathlib, sys

candidates, catalog, pointers, approved = map(pathlib.Path, sys.argv[1:])
protected = set(catalog.read_text().splitlines()) | set(pointers.read_text().splitlines())
rows = []
for row in candidates.read_text().splitlines():
    identity = "\t".join(row.split("\t")[:3])
    if identity not in protected:
        rows.append(row)
approved.write_text("".join(value + "\n" for value in rows), encoding="utf-8")
print(f"approvedAfterRelationalRecheck={len(rows)}")
PY

if [[ ! -s "$approved" ]]; then
  echo "MinIO orphan GC: all candidates became protected; nothing deleted"
  exit 0
fi

docker compose run --rm --no-deps \
  -v "$work:/work:ro" \
  --entrypoint /bin/sh minio-init -ec '
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
    tab="$(printf "\t")"
    deleted=0
    while IFS="$tab" read -r bucket key version _modified _size; do
      [ -n "$bucket" ] && [ -n "$key" ] && [ -n "$version" ] || exit 1
      mc rm --quiet --version-id "$version" "local/$bucket/$key"
      deleted=$((deleted + 1))
    done </work/approved.tsv
    echo "deletedVersions=$deleted"
  ' </dev/null

docker compose run --rm --no-deps \
  -v "$work:/work" \
  --entrypoint /bin/sh minio-init -ec '
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
    mc ls --versions --recursive --json "local/$MINIO_BUCKET" > /work/minio-after.jsonl
  ' </dev/null
python3 - "$approved" "$work/minio-after.jsonl" <<'PY'
import json, pathlib, sys

approved = {
    tuple(line.split("\t")[:3])
    for line in pathlib.Path(sys.argv[1]).read_text().splitlines()
    if line
}
remaining = set()
for line in pathlib.Path(sys.argv[2]).read_text().splitlines():
    if not line:
        continue
    value = json.loads(line)
    if value.get("status") != "success" or value.get("type") != "file":
        continue
    bucket = (value.get("url") or "").rstrip("/").rsplit("/", 1)[-1]
    identity = (bucket, value.get("key", ""), value.get("versionId") or "")
    if identity in approved:
        remaining.add(identity)
if remaining:
    raise SystemExit(f"deleted MinIO versions still visible: {len(remaining)}")
print(f"verifiedDeletedVersions={len(approved)}")
PY

echo "MinIO orphan GC: apply complete"
REMOTE
