#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="${MACROSQUARE_MAINTENANCE_STATE_DIR:-$HOME/Library/Application Support/MacroSquare}"
RESTORE_DRILL_INTERVAL_DAYS="${RESTORE_DRILL_INTERVAL_DAYS:-7}"
LOCK_DIR="$STATE_DIR/storage-maintenance.lock"
LAST_DRILL_FILE="$STATE_DIR/last-restore-drill-epoch"

mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR"
if ! mkdir "$LOCK_DIR" 2>/dev/null; then
  echo "storage maintenance is already running"
  exit 0
fi
trap 'rmdir "$LOCK_DIR" 2>/dev/null || true' EXIT INT TERM

[[ "$RESTORE_DRILL_INTERVAL_DAYS" =~ ^[1-9][0-9]*$ ]]
echo "maintenanceStartedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# GC is never allowed to run before a fresh, checksummed off-host backup.
"$ROOT_DIR/scripts/backup-home-storage.sh"
"$ROOT_DIR/scripts/gc-home-object-storage.sh" --apply \
  --retention-days "${MINIO_ORPHAN_RETENTION_DAYS:-30}"

now="$(date +%s)"
last=0
if [[ -f "$LAST_DRILL_FILE" ]]; then
  read -r last <"$LAST_DRILL_FILE" || last=0
fi
if [[ ! "$last" =~ ^[0-9]+$ ]]; then last=0; fi
interval_seconds=$((RESTORE_DRILL_INTERVAL_DAYS * 86400))
if (( now - last >= interval_seconds )); then
  "$ROOT_DIR/scripts/restore-drill-home-storage.sh"
  printf '%s\n' "$now" >"$LAST_DRILL_FILE.tmp"
  mv "$LAST_DRILL_FILE.tmp" "$LAST_DRILL_FILE"
  chmod 600 "$LAST_DRILL_FILE"
else
  echo "restoreDrill=not-due nextInSeconds=$((interval_seconds - (now - last)))"
fi

echo "maintenanceCompletedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
