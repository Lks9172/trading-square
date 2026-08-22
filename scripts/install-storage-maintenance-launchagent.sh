#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LABEL="io.macrosquare.storage-maintenance"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG_DIR="$HOME/Library/Logs/MacroSquare"
STATE_DIR="$HOME/Library/Application Support/MacroSquare"
RUNTIME_ROOT="$STATE_DIR/maintenance-runtime"
RUNTIME_SCRIPTS="$RUNTIME_ROOT/scripts"
DOMAIN="gui/$(id -u)"

mkdir -p "$(dirname "$PLIST")" "$LOG_DIR" "$STATE_DIR"
chmod 700 "$LOG_DIR" "$STATE_DIR"

if [[ "${1:-}" == --uninstall ]]; then
  launchctl bootout "$DOMAIN/$LABEL" >/dev/null 2>&1 || true
  rm -f "$PLIST"
  rm -rf "$RUNTIME_ROOT"
  echo "uninstalled $LABEL"
  exit 0
fi
[[ $# -eq 0 ]] || { echo "usage: $0 [--uninstall]" >&2; exit 2; }

# launchd cannot execute scripts below Desktop when macOS TCC denies that
# directory to background processes. Install a minimal, secret-free runtime in
# Application Support instead of asking for broad Full Disk Access.
mkdir -p "$RUNTIME_SCRIPTS"
chmod 700 "$RUNTIME_ROOT" "$RUNTIME_SCRIPTS"
for script in \
  maintenance-home-storage.sh \
  backup-home-storage.sh \
  gc-home-object-storage.sh \
  restore-drill-home-storage.sh; do
  install -m 700 "$ROOT_DIR/scripts/$script" "$RUNTIME_SCRIPTS/$script"
done

python3 - "$PLIST" "$RUNTIME_ROOT" "$LOG_DIR" "$HOME" <<'PY'
import pathlib
import plistlib
import sys

plist_path, runtime_root, log_dir, home = map(pathlib.Path, sys.argv[1:])
payload = {
    "Label": "io.macrosquare.storage-maintenance",
    "ProgramArguments": ["/bin/bash", str(runtime_root / "scripts/maintenance-home-storage.sh")],
    "WorkingDirectory": str(runtime_root),
    "EnvironmentVariables": {
        "HOME": str(home),
        "PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
    },
    "StartCalendarInterval": {"Hour": 3, "Minute": 20},
    "RunAtLoad": False,
    "ProcessType": "Background",
    "LowPriorityIO": True,
    "Nice": 10,
    "StandardOutPath": str(log_dir / "storage-maintenance.log"),
    "StandardErrorPath": str(log_dir / "storage-maintenance-error.log"),
}
temporary = plist_path.with_suffix(".plist.tmp")
with temporary.open("wb") as handle:
    plistlib.dump(payload, handle, sort_keys=True)
temporary.chmod(0o600)
temporary.replace(plist_path)
PY

plutil -lint "$PLIST" >/dev/null
launchctl bootout "$DOMAIN/$LABEL" >/dev/null 2>&1 || true
launchctl bootstrap "$DOMAIN" "$PLIST"
launchctl enable "$DOMAIN/$LABEL"
launchctl print "$DOMAIN/$LABEL" >/dev/null
echo "installed $LABEL (daily 03:20 local time)"
