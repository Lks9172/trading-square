#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tier="${1:-fast}"
scope="${2:-server}"
JAVA_HOME_VALUE="${TEST_JAVA_HOME:-${JAVA_HOME:-}}"
if [[ "$(uname -s)" == Darwin ]]; then
  JAVA_HOME_VALUE="$(/usr/libexec/java_home -v 21)"
fi
[[ -n "$JAVA_HOME_VALUE" ]] || {
  echo 'Java 21 home is required (set TEST_JAVA_HOME or JAVA_HOME)' >&2
  exit 1
}

run_server() {
  local lifecycle=${1:-test}
  JAVA_HOME="$JAVA_HOME_VALUE" "$ROOT_DIR/server-spring/mvnw" -B -ntp \
    -f "$ROOT_DIR/server-spring/pom.xml" "$lifecycle"
}
run_client_fast() {
  cd "$ROOT_DIR/client"
  [[ -d node_modules ]] || npm ci
  npm test
  npm run lint
}
run_client_standard() {
  cd "$ROOT_DIR/client"
  [[ -d node_modules ]] || npm ci
  npm test
  npm run lint
  npm run build
}
run_ops() {
  cd "$ROOT_DIR"
  python3 -m unittest discover -s scripts/tests -p 'test_*.py'
  python3 scripts/verify-documentation.py
  bash scripts/check-cutover-invariants.sh
}

case "$tier" in
  fast)
    case "$scope" in
      server) run_server test ;;
      client) run_client_fast ;;
      scripts) run_ops ;;
      docs) python3 "$ROOT_DIR/scripts/verify-documentation.py" ;;
      *) echo "fast scope must be server, client, scripts or docs" >&2; exit 2 ;;
    esac
    ;;
  standard)
    logs="$(mktemp -d "${TMPDIR:-/tmp}/macrosquare-standard.XXXXXX")"
    trap 'rm -rf "$logs"' EXIT
    (run_server verify >"$logs/server.log" 2>&1) & server_pid=$!
    (run_client_standard >"$logs/client.log" 2>&1) & client_pid=$!
    (run_ops >"$logs/ops.log" 2>&1) & ops_pid=$!
    failed=0
    wait "$server_pid" || failed=1
    wait "$client_pid" || failed=1
    wait "$ops_pid" || failed=1
    for name in server client ops; do echo "--- $name ---"; tail -30 "$logs/$name.log"; done
    (( failed == 0 )) || exit 1
    ;;
  release)
    run_server clean
    "$0" standard
    "$ROOT_DIR/scripts/test-postgres-multi-instance.sh"
    ;;
  *) echo "tier must be fast, standard or release" >&2; exit 2 ;;
esac

suffix=""
[[ "$tier" != fast ]] || suffix="/$scope"
printf 'test tier passed: %s%s\n' "$tier" "$suffix"
