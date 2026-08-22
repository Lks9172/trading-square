#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOME_HOST="${HOME_HOST:-lks@192.168.0.200}"
HOME_DIR="${HOME_DIR:-/home/lks/trading-square}"
mode="${1:---auto}"

usage() {
  cat <<'USAGE'
usage: ./scripts/deploy-home.sh [--plan|--auto|--server|--client|--scripts|--docs|--verify|--full]

  --plan     print the auto-selected scope without changing the home server
  --auto     compare local inputs with the home server and choose the smallest safe scope (default)
  --server   build, cut over and verify only the Spring backend
  --client   build, cut over and verify only the Next.js client
  --scripts  sync operational scripts/docs and verify without restarting applications
  --docs     sync documentation only; no container restart
  --verify   read-only home-server health and contract verification
  --full     run the complete server+client+observability transaction
USAGE
}

case "$mode" in
  --plan|--auto|--server|--client|--scripts|--docs|--verify|--full) ;;
  -h|--help) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac

if [[ "$mode" == --full ]]; then
  exec "$ROOT_DIR/scripts/deploy-home-full.sh"
fi

if [[ "$mode" != --auto && "$mode" != --plan ]]; then
  exec "$ROOT_DIR/scripts/deploy-home-scoped.sh" "${mode#--}"
fi

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
ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" "test -d '$HOME_DIR'"

dry_run_tree() {
  local source=$1 destination=$2
  shift 2
  rsync -ain --delete --omit-dir-times --itemize-changes "$@" \
    "$source/" "$HOME_HOST:$HOME_DIR/$destination/" \
    | awk '$1 == "*deleting" || substr($1, 1, 1) == ">" || substr($1, 1, 1) == "<" || substr($1, 1, 1) == "c"'
}

server_changes="$(dry_run_tree "$ROOT_DIR/server-spring" server-spring \
  --exclude target --exclude '.idea' --exclude docs)"
server_doc_changes="$(dry_run_tree "$ROOT_DIR/server-spring/docs" server-spring/docs)"
client_changes="$(dry_run_tree "$ROOT_DIR/client" client \
  --exclude node_modules --exclude .next --exclude '.env*')"
script_changes="$(dry_run_tree "$ROOT_DIR/scripts" scripts \
  --exclude __pycache__ --exclude '*.pyc' --exclude .pytest_cache)"
doc_changes="$(dry_run_tree "$ROOT_DIR/docs" docs)"
observability_changes="$(dry_run_tree "$ROOT_DIR/observability" observability)"

remote_hash() {
  ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" \
    "test -f '$HOME_DIR/$1' && sha256sum '$HOME_DIR/$1' | awk '{print \$1}' || true"
}
local_hash() { sha256sum "$ROOT_DIR/$1" | awk '{print $1}'; }

compose_changed=false
readme_changed=false
[[ "$(local_hash docker-compose.yml)" == "$(remote_hash docker-compose.yml)" ]] || compose_changed=true
[[ "$(local_hash README.md)" == "$(remote_hash README.md)" ]] || readme_changed=true

has_server=false; [[ -z "$server_changes" ]] || has_server=true
has_client=false; [[ -z "$client_changes" ]] || has_client=true
has_scripts=false; [[ -z "$script_changes" ]] || has_scripts=true
has_docs=false; [[ -z "$doc_changes" && -z "$server_doc_changes" ]] || has_docs=true
has_observability=false; [[ -z "$observability_changes" ]] || has_observability=true

classifier_args=(--server-changes "$server_changes")
[[ "$has_server" == true ]] && classifier_args+=(--server)
[[ "$has_client" == true ]] && classifier_args+=(--client)
[[ "$has_scripts" == true ]] && classifier_args+=(--scripts)
[[ "$has_docs" == true ]] && classifier_args+=(--docs)
[[ "$has_observability" == true ]] && classifier_args+=(--observability)
[[ "$compose_changed" == true ]] && classifier_args+=(--compose)
[[ "$readme_changed" == true ]] && classifier_args+=(--readme)
classification="$(python3 "$ROOT_DIR/scripts/classify-deploy-scope.py" "${classifier_args[@]}")"
read -r selected server_release <<<"$(python3 -c \
  'import json,sys; value=json.load(sys.stdin); print(value["scope"], str(value["serverRelease"]).lower())' \
  <<<"$classification")"

printf 'auto deployment scope: %s (server=%s client=%s scripts=%s docs=%s observability=%s compose=%s release=%s)\n' \
  "$selected" "$has_server" "$has_client" "$has_scripts" "$has_docs" \
  "$has_observability" "$compose_changed" "$server_release"

[[ "$mode" != --plan ]] || exit 0

case "$selected" in
  full) exec "$ROOT_DIR/scripts/deploy-home-full.sh" ;;
  server) DEPLOY_SERVER_RELEASE="$server_release" exec "$ROOT_DIR/scripts/deploy-home-scoped.sh" server ;;
  *) exec "$ROOT_DIR/scripts/deploy-home-scoped.sh" "$selected" ;;
esac
