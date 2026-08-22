#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRON_MARKER="# macrosquare-daily-observability-audit"
REALTIME_MARKER="# macrosquare-realtime-recurrence-monitor"
CRON_LOG="$ROOT_DIR/.ops-audit/cron.log"
REALTIME_LOG="$ROOT_DIR/.ops-audit/realtime-cron.log"

mkdir -p "$ROOT_DIR/.ops-audit"
chmod 700 "$ROOT_DIR/.ops-audit"
touch "$CRON_LOG"
chmod 600 "$CRON_LOG"
touch "$REALTIME_LOG"
chmod 600 "$REALTIME_LOG"

# Home server timezone is UTC. 22:20 UTC is 07:20 Asia/Seoul year-round.
entry="20 22 * * * cd '$ROOT_DIR' && /usr/bin/flock -n '$ROOT_DIR/.ops-audit/audit.lock' /usr/bin/python3 '$ROOT_DIR/scripts/audit-home-observability.py' >> '$CRON_LOG' 2>&1 $CRON_MARKER"
realtime_entry="* * * * * cd '$ROOT_DIR' && /usr/bin/flock -n '$ROOT_DIR/.ops-audit/realtime.lock' /usr/bin/python3 '$ROOT_DIR/scripts/monitor-home-recurrence.py' >> '$REALTIME_LOG' 2>&1 $REALTIME_MARKER"
current="$(crontab -l 2>/dev/null || true)"
filtered="$(printf '%s\n' "$current" | grep -F -v "$CRON_MARKER" | grep -F -v "$REALTIME_MARKER" || true)"
{
  printf '%s\n' "$filtered"
  printf '%s\n' "$entry"
  printf '%s\n' "$realtime_entry"
} | sed '/^[[:space:]]*$/d' | crontab -

echo "installed daily observability audit: 22:20 UTC / 07:20 KST"
crontab -l | grep -F "$CRON_MARKER"
echo "installed real-time recurrence monitor: every minute"
crontab -l | grep -F "$REALTIME_MARKER"
