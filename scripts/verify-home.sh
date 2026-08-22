#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOME_HOST="${HOME_HOST:-lks@192.168.0.200}"
HOME_DIR="${HOME_DIR:-/home/lks/trading-square}"
PUBLIC_HOST="${PUBLIC_HOST:-${HOME_HOST#*@}}"
SSH_OPTIONS=(
  -o BatchMode=yes
  -o ConnectTimeout=10
  -o ServerAliveInterval=15
  -o ServerAliveCountMax=40
  -o TCPKeepAlive=yes
)

printf '== remote container verification ==\n'
ssh "${SSH_OPTIONS[@]}" "$HOME_HOST" bash -s -- "$HOME_DIR" <<'REMOTE'
set -euo pipefail
cd "$1"

docker compose config --quiet
docker compose ps \
  postgres minio jaeger otel-collector prometheus loki alloy server client

server_status="$(docker inspect macrosquare-server --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}no-health{{end}}/{{.RestartCount}}')"
client_status="$(docker inspect macrosquare-client --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}no-health{{end}}/{{.RestartCount}}')"
postgres_status="$(docker inspect macrosquare-postgres --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}no-health{{end}}/{{.RestartCount}}')"
minio_status="$(docker inspect macrosquare-minio --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}no-health{{end}}/{{.RestartCount}}')"
jaeger_status="$(docker inspect macrosquare-jaeger --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}no-health{{end}}/{{.RestartCount}}')"
otel_status="$(docker inspect macrosquare-otel-collector --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}no-health{{end}}/{{.RestartCount}}')"
prometheus_status="$(docker inspect macrosquare-prometheus --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}no-health{{end}}/{{.RestartCount}}')"
loki_status="$(docker inspect macrosquare-loki --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}no-health{{end}}/{{.RestartCount}}')"
alloy_status="$(docker inspect macrosquare-alloy --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}no-health{{end}}/{{.RestartCount}}')"
printf 'server=%s\nclient=%s\npostgres=%s\nminio=%s\njaeger=%s\notel=%s\nprometheus=%s\nloki=%s\nalloy=%s\n' \
  "$server_status" "$client_status" "$postgres_status" "$minio_status" "$jaeger_status" \
  "$otel_status" "$prometheus_status" "$loki_status" "$alloy_status"
[[ "$server_status" == running/healthy/0 ]]
[[ "$client_status" == running/healthy/0 ]]
[[ "$postgres_status" == running/healthy/0 ]]
[[ "$minio_status" == running/healthy/0 ]]
[[ "$jaeger_status" == running/healthy/0 ]]
[[ "$otel_status" == running/no-health/0 ]]
[[ "$prometheus_status" == running/no-health/0 ]]
[[ "$loki_status" == running/no-health/0 ]]
[[ "$alloy_status" == running/no-health/0 ]]

for container_port in \
  macrosquare-server:5846/tcp \
  macrosquare-postgres:5432/tcp \
  macrosquare-minio:9000/tcp \
  macrosquare-minio:9001/tcp \
  macrosquare-jaeger:16686/tcp \
  macrosquare-jaeger:4318/tcp \
  macrosquare-otel-collector:4318/tcp \
  macrosquare-otel-collector:13133/tcp \
  macrosquare-prometheus:9090/tcp \
  macrosquare-loki:3100/tcp \
  macrosquare-alloy:12345/tcp; do
  container="${container_port%%:*}"
  port="${container_port#*:}"
  host_ip="$(docker inspect "$container" --format "{{(index (index .NetworkSettings.Ports \"$port\") 0).HostIp}}")"
  [[ "$host_ip" == 127.0.0.1 ]]
done

server_image="$(docker inspect macrosquare-server --format '{{.Config.Image}}')"
client_image="$(docker inspect macrosquare-client --format '{{.Config.Image}}')"
[[ "$server_image" == macrosquare-server-spring:production ]]
[[ "$client_image" == macrosquare-client:production ]]
[[ "$(docker inspect macrosquare-server --format '{{.Config.User}}')" == macrosquare ]]
[[ "$(docker inspect macrosquare-client --format '{{.Config.User}}')" == node ]]
for application_container in macrosquare-server macrosquare-client; do
  [[ "$(docker inspect "$application_container" --format '{{.HostConfig.Init}}')" == true ]]
  docker inspect "$application_container" --format '{{json .HostConfig.SecurityOpt}}' \
    | grep -q 'no-new-privileges'
  docker inspect "$application_container" --format '{{json .HostConfig.CapDrop}}' \
    | grep -q 'ALL'
done
retired_runtime_mounts="$(docker inspect macrosquare-server --format \
  '{{range .Mounts}}{{if or (eq .Destination "/app/legacy-data") (eq .Destination "/app/legacy-history") (eq .Destination "/app/legacy-source-cache")}}{{.Destination}}{{"\n"}}{{end}}{{end}}')"
[[ -z "$retired_runtime_mounts" ]]
[[ ! -d server ]]
printf 'retiredNodeBackend=absent runtimeMounts=absent\n'
if docker image ls --format '{{.Repository}}:{{.Tag}}' \
  | grep -E -q '^macrosquare-(server-spring|client):rollback-'; then
  echo 'historical application rollback image tag remains after successful cutover' >&2
  exit 1
fi

processes="$(docker top macrosquare-server -eo pid,comm,args)"
printf '%s\n' "$processes"
printf '%s\n' "$processes" | grep -q '[j]ava'
if printf '%s\n' "$processes" | grep -E -q '(^|[[:space:]])(node|npm|npx)([[:space:]]|$)'; then
  echo 'unexpected Node process in Spring backend' >&2
  exit 1
fi

curl -fsS http://127.0.0.1:5846/actuator/health/readiness >/dev/null
curl -fsS http://127.0.0.1:5846/api/health >/dev/null
curl -fsS http://127.0.0.1:5847/ >/dev/null
curl -fsS http://127.0.0.1:5900/minio/health/ready >/dev/null
curl -fsS http://127.0.0.1:13133/ >/dev/null
curl -fsS http://127.0.0.1:5902/-/ready >/dev/null
curl -fsS http://127.0.0.1:5903/ready >/dev/null
curl -fsS http://127.0.0.1:5904/-/ready >/dev/null

# The log agent needs only read access to Docker's JSON log directory. A
# Docker socket mount would grant control-plane access despite a `:ro` suffix.
if docker inspect macrosquare-alloy --format '{{range .Mounts}}{{.Source}} -> {{.Destination}}{{"\n"}}{{end}}' \
  | grep -q '/var/run/docker.sock'; then
  echo 'Alloy unexpectedly has access to the Docker control socket' >&2
  exit 1
fi

minio_audit="$(docker compose run --rm --no-deps --entrypoint /bin/sh minio-init -ec '
  mc alias set verify http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
  mc version info "verify/$MINIO_BUCKET"
  mc anonymous get "verify/$MINIO_BUCKET"
  mc admin policy info verify macrosquare-app
' </dev/null)"
case "$minio_audit" in
  *[Ee]nabled*) ;;
  *) echo 'MinIO bucket versioning is not enabled' >&2; exit 1 ;;
esac
case "$minio_audit" in
  *private*) ;;
  *) echo 'MinIO bucket is not private' >&2; exit 1 ;;
esac
if grep -q 'DeleteObject' <<<"$minio_audit"; then
  echo 'MinIO application policy unexpectedly grants delete access' >&2
  exit 1
fi

read -r flyway_count outbox_migration product_intelligence_migration research_extension_migration \
  narrative_source_migration operational_hardening_migration krx_source_migration \
  peer_taxonomy_check_migration collection_status_migration recurrence_guard_migration \
  collection_outcome_guard_migration dated_eps_revision_migration sector_market_evidence_migration \
  sector_validation_ledger_migration sector_signal_price_dates_migration \
  notification_reversal_evidence_migration macd_notification_evidence_migration <<<"$(
  docker compose exec -T postgres psql -U macrosquare -d macrosquare -AtF' ' -c \
    "select count(*) filter (where success),
            count(*) filter (where version = '2' and success),
            count(*) filter (where version = '3' and success),
            count(*) filter (where version = '4' and success),
            count(*) filter (where version = '5' and success),
            count(*) filter (where version = '6' and success),
            count(*) filter (where version = '7' and success),
            count(*) filter (where version = '8' and success),
            count(*) filter (where version = '9' and success),
            count(*) filter (where version = '15' and success),
            count(*) filter (where version = '16' and success),
            count(*) filter (where version = '17' and success),
            count(*) filter (where version = '18' and success),
            count(*) filter (where version = '19' and success),
            count(*) filter (where version = '20' and success),
            count(*) filter (where version = '21' and success),
            count(*) filter (where version = '22' and success)
       from flyway_schema_history" </dev/null
)"
[[ "$flyway_count" -ge 22 ]]
[[ "$outbox_migration" -eq 1 ]]
[[ "$product_intelligence_migration" -eq 1 ]]
[[ "$research_extension_migration" -eq 1 ]]
[[ "$narrative_source_migration" -eq 1 ]]
[[ "$operational_hardening_migration" -eq 1 ]]
[[ "$krx_source_migration" -eq 1 ]]
[[ "$peer_taxonomy_check_migration" -eq 1 ]]
[[ "$collection_status_migration" -eq 1 ]]
[[ "$recurrence_guard_migration" -eq 1 ]]
[[ "$collection_outcome_guard_migration" -eq 1 ]]
[[ "$dated_eps_revision_migration" -eq 1 ]]
[[ "$sector_market_evidence_migration" -eq 1 ]]
[[ "$sector_validation_ledger_migration" -eq 1 ]]
[[ "$sector_signal_price_dates_migration" -eq 1 ]]
[[ "$notification_reversal_evidence_migration" -eq 1 ]]
[[ "$macd_notification_evidence_migration" -eq 1 ]]

notification_evidence_constraint_count="$(docker compose exec -T postgres psql -U macrosquare -d macrosquare -Atc \
  "select count(*) from pg_constraint where conname in (
      'company_research_summary_reversal_status_ck',
      'company_research_summary_reversal_score_ck',
      'company_research_summary_price_signal_reasons_ck',
      'company_research_summary_macd_timing_ck',
      'notification_candidate_snapshot_macd_timing_ck'
  )" </dev/null)"
[[ "$notification_evidence_constraint_count" -eq 5 ]]

sector_evidence_constraint_count="$(docker compose exec -T postgres psql -U macrosquare -d macrosquare -Atc \
  "select count(*) from pg_constraint where conname in (
      'sector_fund_flow_key_ck', 'sector_fund_flow_ticker_ck',
      'sector_fund_flow_positive_ck', 'sector_fund_flow_numeric_finite_ck', 'sector_fund_flow_finite_ck',
      'sector_fund_flow_score_ck', 'sector_price_breadth_key_ck',
      'sector_price_breadth_dates_ck', 'sector_price_breadth_coverage_ck',
      'sector_price_breadth_score_ck'
  )" </dev/null)"
[[ "$sector_evidence_constraint_count" -eq 10 ]]
read -r sector_flow_rows sector_breadth_rows <<<"$(
  docker compose exec -T postgres psql -U macrosquare -d macrosquare -AtF' ' -c \
    "select (select count(*) from research.sector_fund_flow_snapshot where observed_on >= current_date - 7),
            (select count(*) from research.sector_price_breadth_snapshot where observed_on >= current_date - 7)" </dev/null
)"

read -r sector_validation_runs sector_validation_items <<<"$(
  docker compose exec -T postgres psql -U macrosquare -d macrosquare -AtF' ' -c \
    "select (select count(*) from research.sector_rotation_run),
            (select count(*) from research.sector_rotation_item_snapshot)" </dev/null
)"
[[ "$sector_validation_runs" -ge 1 ]]
[[ "$sector_validation_items" -eq $((sector_validation_runs * 11)) ]]
read -r sector_signal_on sector_price_anchor_on invalid_sector_validation_dates <<<"$(
  docker compose exec -T postgres psql -U macrosquare -d macrosquare -AtF' ' -c \
    "select coalesce(to_char(as_of_date, 'YYYY-MM-DD'), 'none'),
            coalesce(to_char(price_anchor_on, 'YYYY-MM-DD'), 'none'),
            (select count(*) from research.sector_rotation_run where price_anchor_on > as_of_date)
       from research.sector_rotation_run
      order by price_anchor_on desc, calculated_at desc
      limit 1" </dev/null
)"
[[ "$sector_signal_on" != "none" ]]
[[ "$sector_price_anchor_on" != "none" ]]
[[ "$invalid_sector_validation_dates" -eq 0 ]]

integrity_guard_count="$(docker compose exec -T postgres psql -U macrosquare -d macrosquare -Atc \
  "select count(*) from pg_constraint where conname in (
      'company_research_summary_fundamentals_status_ck',
      'company_research_summary_execution_action_ck',
      'company_research_summary_price_signal_completeness_ck',
      'company_research_summary_score_bundle_ck',
      'company_research_summary_score_evidence_ck',
      'company_research_summary_buy_evidence_ck',
      'company_research_summary_calculation_version_ck',
      'analyst_upside_plausibility_ck',
      'institutional_holding_value_ck',
      'notification_integrity_fingerprint_ck',
      'market_collection_status_outcome_consistency_ck'
  )" </dev/null)"
[[ "$integrity_guard_count" -eq 11 ]]
docker inspect macrosquare-server --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | grep -qx 'DATA_INTEGRITY_MONITOR_ENABLED=true'

read -r tr_series tr_min_points tr_distinct_latest tr_stale tr_provider_mismatch <<<"$(
  docker compose exec -T postgres psql -U macrosquare -d macrosquare -AtF' ' -c \
    "with required(series_key) as (
       values ('SPY_TR'), ('XLK_TR'), ('XLF_TR'), ('XLE_TR'), ('XLV_TR'), ('XLI_TR'),
              ('XLY_TR'), ('XLC_TR'), ('XLB_TR'), ('XLRE_TR'), ('XLU_TR'), ('XLP_TR'),
              ('SOXX_TR'), ('SMH_TR'), ('ITA_TR'), ('GRID_TR'), ('IGF_TR')
     ), coverage as (
       select r.series_key, count(o.series_key) point_count, max(o.observed_on) latest,
              count(*) filter(where o.provider_code not like '%:ADJCLOSE_TOTAL_RETURN') provider_mismatch
       from required r
       left join market.observation o on o.source = 'YAHOO' and o.series_key = r.series_key
       group by r.series_key
     )
     select count(*) filter(where point_count > 0), min(point_count), count(distinct latest),
            count(*) filter(where latest < current_date - 7 or latest is null), sum(provider_mismatch)
       from coverage" </dev/null
)"
[[ "$tr_series" -eq 17 ]]
[[ "$tr_min_points" -ge 2000 ]]
[[ "$tr_distinct_latest" -eq 1 ]]
[[ "$tr_stale" -eq 0 ]]
[[ "$tr_provider_mismatch" -eq 0 ]]
printf 'sectorTotalReturn series=%s/17 minPoints=%s alignedLatest=%s stale=%s providerMismatch=%s\n' \
  "$tr_series" "$tr_min_points" "$tr_distinct_latest" "$tr_stale" "$tr_provider_mismatch"

# Peer directory/taxonomy are deliberately broader SEC discovery datasets and
# may contain securities outside the curated current research universe.
# Current-universe exclusion belongs to company projections and public APIs.
retired_current_rows="$(
  docker compose exec -T postgres psql -U macrosquare -d macrosquare -Atc \
    "select
       (select count(*) from company.analyst_series_state where ticker in ('EA', 'CTRA'))
       + (select count(*) from company.research_summary where ticker in ('EA', 'CTRA'))" </dev/null
)"
[[ "$retired_current_rows" -eq 0 ]]
v6_index_count="$(docker compose exec -T postgres psql -U macrosquare -d macrosquare -Atc \
  "select count(*) from pg_indexes
    where (schemaname, indexname) in (
      ('notification', 'notification_outbox_terminal_retention_idx'),
      ('research', 'narrative_source_observation_date_idx'),
      ('research', 'peer_taxonomy_industry_group_idx'),
      ('research', 'peer_taxonomy_major_group_idx'),
      ('disclosure', 'dart_company_collected_idx'),
      ('disclosure', 'dart_filing_collected_idx'),
      ('disclosure', 'dart_financial_collected_idx')
    )" </dev/null)"
[[ "$v6_index_count" -eq 7 ]]

# The outbox is the durability boundary between the committed notification
# state and Telegram. Validate its migration and lease invariants without
# requiring an empty queue: a short-lived PENDING/IN_FLIGHT row is healthy,
# while an already expired lease after startup indicates a stuck dispatcher.
read -r outbox_total outbox_pending outbox_in_flight outbox_retry \
  outbox_delivered outbox_dead outbox_expired <<<"$(
  docker compose exec -T postgres psql -U macrosquare -d macrosquare -AtF' ' -c \
    "select count(*),
            count(*) filter (where status = 'PENDING'),
            count(*) filter (where status = 'IN_FLIGHT'),
            count(*) filter (where status = 'RETRY'),
            count(*) filter (where status = 'DELIVERED'),
            count(*) filter (where status = 'DEAD'),
            count(*) filter (where status = 'IN_FLIGHT' and leased_until <= now())
       from notification.outbox" </dev/null
)"
[[ "$outbox_expired" -eq 0 ]]
read -r summary_current_version summary_legacy_version <<<"$(
  docker compose exec -T postgres psql -U macrosquare -d macrosquare -AtF' ' -c \
    "select count(*) filter (where calculation_version = 6),
            count(*) filter (where calculation_version <> 6)
       from company.research_summary" </dev/null
)"
printf 'flywayMigrations=%s outboxMigration=v2 intelligenceMigration=v3 researchExtension=v4 narrativeSources=v5 operationalHardening=v6 krxSource=v7 peerChecks=v8 collectionStatus=v9 retiredUniverseCleanup=v13 decisionProjection=v14 recurrenceGuards=v15 collectionOutcomeGuard=v16 datedEpsRevision=v17 sectorMarketEvidence=v18 sectorValidationLedger=v19 sectorSignalPriceDates=v20 notificationReversalEvidence=v21 macdNotificationEvidence=v22\n' "$flyway_count"
printf 'sectorMarketEvidence constraints=%s/10 currentFundFlowRows=%s currentPriceBreadthRows=%s\n' \
  "$sector_evidence_constraint_count" "$sector_flow_rows" "$sector_breadth_rows"
printf 'sectorValidationLedger runs=%s immutableItems=%s expectedItems=%s\n' \
  "$sector_validation_runs" "$sector_validation_items" "$((sector_validation_runs * 11))"
printf 'sectorValidationDates signalOn=%s priceAnchorOn=%s invalid=%s\n' \
  "$sector_signal_on" "$sector_price_anchor_on" "$invalid_sector_validation_dates"
printf 'dataIntegrityMonitor=enabled recurrenceGuards=%s/11\n' "$integrity_guard_count"
printf 'companyDecisionProjection currentV6=%s safelyHiddenLegacy=%s\n' \
  "$summary_current_version" "$summary_legacy_version"
# This verification body itself is streamed to the remote shell over stdin.
# Keep the child checker from consuming the remaining verification script.
company_selection_audit="$(python3 scripts/audit-company-selection-e2e.py </dev/null)"
printf 'companySelectionE2E=%s\n' "$company_selection_audit"
sector_catalog_probe="$(mktemp)"
trap 'rm -f "$sector_catalog_probe"' EXIT
curl -fsS --max-time 30 http://127.0.0.1:5846/api/research/sectors >"$sector_catalog_probe"
python3 - "$sector_catalog_probe" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
sectors = payload.get("sectors", [])
assert len(sectors) == 11, len(sectors)
counts = {item["id"]: len(item.get("tickers") or []) for item in sectors}
assert all(value >= 20 for value in counts.values()), counts
members = {item["id"]: set(item.get("tickers") or []) for item in sectors}
assert "RBLX" in members["communication-services"], members["communication-services"]
assert "EPD" in members["energy"], members["energy"]
print("standardSectorDisplay=" + ",".join(f"{key}:{counts[key]}" for key in sorted(counts)))
PY
rm -f "$sector_catalog_probe"
trap - EXIT
for replacement_contract in 'RBLX NYSE 7372' 'EPD NYSE 4922'; do
  read -r replacement_ticker replacement_exchange replacement_sic <<<"$replacement_contract"
  replacement_detail_probe="$(mktemp)"
  trap 'rm -f "$replacement_detail_probe"' EXIT
  replacement_detail_ready=false
  for _ in $(seq 1 30); do
    # Enrichment is asynchronous immediately after deployment. Keep expected
    # retry assertions quiet; the loop emits one actionable error on timeout.
    if curl -fsS --max-time 45 "http://127.0.0.1:5846/api/company/$replacement_ticker" \
        >"$replacement_detail_probe" \
      && python3 - "$replacement_detail_probe" "$replacement_ticker" \
        "$replacement_exchange" "$replacement_sic" 2>/dev/null <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
ticker = sys.argv[2]
exchange = sys.argv[3]
sic = sys.argv[4]
assert payload.get("profile", {}).get("ticker") == ticker, payload.get("profile")
assert payload.get("profile", {}).get("exchange") == exchange, payload.get("profile")
assert str(payload.get("profile", {}).get("sic")) == sic, payload.get("profile")
assert payload.get("financials", {}).get("ticker") == ticker, payload.get("financials")
assert len(payload.get("filings") or []) >= 1, payload.get("filings")
action = payload.get("verdicts", {}).get("investmentDecision", {}).get("action")
assert action in {"SELL", "REDUCE", "HOLD", "BUY", "STRONG_BUY"}, action
PY
    then
      replacement_detail_ready=true
      break
    fi
    sleep 2
  done
  [[ "$replacement_detail_ready" == true ]] || {
    printf 'replacement company detail did not enrich: %s\n' "$replacement_ticker" >&2
    false
  }
  rm -f "$replacement_detail_probe"
  trap - EXIT
done
printf 'replacementCompanyDetail=RBLX/EPD HTTP200 enriched-current-identity\n'
printf 'retiredCurrentUniverseRows=%s\n' "$retired_current_rows"
printf 'operationalHardeningIndexes=%s/7\n' "$v6_index_count"
printf 'notificationOutbox total=%s pending=%s inFlight=%s retry=%s delivered=%s dead=%s expiredLeases=%s\n' \
  "$outbox_total" "$outbox_pending" "$outbox_in_flight" "$outbox_retry" \
  "$outbox_delivered" "$outbox_dead" "$outbox_expired"

# Verify the cluster-exclusion primitive without touching application rows.
# The first PostgreSQL session owns a disposable advisory lock while a second
# session must fail fast; after release the same key must be acquirable again.
advisory_log="$(mktemp)"
advisory_owner_pid=""
cleanup_advisory_probe() {
  if [[ -n "$advisory_owner_pid" ]]; then
    kill "$advisory_owner_pid" >/dev/null 2>&1 || true
    wait "$advisory_owner_pid" >/dev/null 2>&1 || true
  fi
  [[ -z "$advisory_log" ]] || rm -f "$advisory_log"
}
trap cleanup_advisory_probe EXIT
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U macrosquare -d macrosquare -Atc \
  'select pg_advisory_lock(2147483000, 2147483001);
   select pg_sleep(2);
   select pg_advisory_unlock(2147483000, 2147483001);' \
  >"$advisory_log" 2>&1 </dev/null &
advisory_owner_pid=$!
advisory_visible=0
for _ in $(seq 1 20); do
  advisory_visible="$(docker compose exec -T postgres psql -U macrosquare -d macrosquare -Atc \
    "select count(*) from pg_locks where locktype = 'advisory' and classid = 2147483000 and objid = 2147483001 and granted" \
    </dev/null)"
  [[ "$advisory_visible" -eq 1 ]] && break
  sleep 0.1
done
[[ "$advisory_visible" -eq 1 ]]
advisory_contender="$(docker compose exec -T postgres psql -U macrosquare -d macrosquare -Atc \
  'select pg_try_advisory_lock(2147483000, 2147483001)' </dev/null)"
[[ "$advisory_contender" == f ]]
wait "$advisory_owner_pid"
advisory_owner_pid=""
rm -f "$advisory_log"
advisory_log=""
advisory_reacquired="$(docker compose exec -T postgres psql -U macrosquare -d macrosquare -Atc \
  'select pg_try_advisory_lock(2147483000, 2147483001);
   select pg_advisory_unlock(2147483000, 2147483001)' </dev/null)"
[[ "$advisory_reacquired" == $'t\nt' ]]
printf 'postgresAdvisoryLock=exclusive/released\n'
trap - EXIT

read -r active_objects artifact_rows dangling_pointers <<<"$(
  docker compose exec -T postgres psql -U macrosquare -d macrosquare -AtF' ' -c \
    'select (select count(*) from storage.object_pointer),
            (select count(*) from storage.object_artifact),
            (select count(*) from storage.object_pointer p left join storage.object_artifact a on a.id = p.artifact_id where a.id is null)' \
    </dev/null
)"
[[ "$active_objects" -ge 1 ]]
[[ "$artifact_rows" -ge "$active_objects" ]]
[[ "$dangling_pointers" -eq 0 ]]
printf 'objectPointers=%s objectArtifacts=%s dangling=%s\n' \
  "$active_objects" "$artifact_rows" "$dangling_pointers"

smoke_file="/tmp/macrosquare-api-smoke-verify.json"
python3 server-spring/migration/tools/smoke-production-api.py \
  --base-url http://127.0.0.1:5846 >"$smoke_file"
python3 - "$smoke_file" <<'PY'
import json, sys
result = json.load(open(sys.argv[1], encoding='utf-8'))
print(f"passed={result['passed']} p50={result['p50Ms']}ms max={result['maxMs']}ms")
assert result['passed'] == 43
PY
rm -f "$smoke_file"

# The public company catalog and the Spring-owned current-universe projection
# must describe exactly the same identities. This catches stale captured rows
# (for example EA/CTRA) and ticker-successor leaks (MMC -> MRSH) that a route
# status smoke test cannot detect.
current_universe_db="$(mktemp)"
trap 'rm -f "$current_universe_db"' EXIT
docker compose exec -T postgres psql -U macrosquare -d macrosquare -Atc \
  'select ticker from company.research_summary order by ticker' </dev/null >"$current_universe_db"
python3 - "$current_universe_db" <<'PY'
import json
import sys
import urllib.parse
import urllib.request

expected = {line.strip() for line in open(sys.argv[1], encoding="utf-8") if line.strip()}
items = []
page = 1
while True:
    query = urllib.parse.urlencode({"sort": "buy", "page": page, "pageSize": 100})
    with urllib.request.urlopen(f"http://127.0.0.1:5846/api/research/companies?{query}", timeout=30) as response:
        payload = json.load(response)
    items.extend(payload["items"])
    if page >= payload["totalPages"]:
        break
    page += 1

tickers = [item["ticker"] for item in items]
actual = set(tickers)
assert len(tickers) == len(actual), "duplicate company tickers in public catalog"
assert actual == expected, {
    "missing": sorted(expected - actual),
    "unexpected": sorted(actual - expected),
}
assert not ({"EA", "CTRA", "MMC"} & actual), actual & {"EA", "CTRA", "MMC"}
assert tickers.count("MRSH") == 1, tickers.count("MRSH")
print(f"currentCompanyUniverse=api/db-aligned count={len(actual)} canonicalMRSH=1")
PY
rm -f "$current_universe_db"
trap - EXIT

metrics_probe="$(mktemp)"
trap 'rm -f "$metrics_probe"' EXIT
curl -fsS --max-time 15 http://127.0.0.1:5846/actuator/prometheus >"$metrics_probe"
grep -q '^jvm_memory_used_bytes' "$metrics_probe"
grep -E -q '^http_server_(request_duration|requests)_seconds' "$metrics_probe"
rm -f "$metrics_probe"
trap - EXIT

prometheus_probe="$(mktemp)"
trap 'rm -f "$prometheus_probe"' EXIT
curl -fsS --max-time 15 http://127.0.0.1:5902/api/v1/targets >"$prometheus_probe"
python3 - "$prometheus_probe" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
assert payload.get("status") == "success", payload
targets = payload.get("data", {}).get("activeTargets", [])
spring = [target for target in targets if target.get("labels", {}).get("job") == "macrosquare-spring"]
assert len(spring) == 1, spring
assert spring[0].get("health") == "up", spring[0]
print(f"prometheusSpringTarget={spring[0]['health']}")
PY
rm -f "$prometheus_probe"
trap - EXIT

# Prove the complete browser-RUM -> Next stdout -> Docker JSON log -> Alloy ->
# Loki path with a unique synthetic route that carries no user identifier.
rum_probe_id="verify-$(date +%s)-$$"
rum_probe_path="/__verification_probe__/$rum_probe_id"
rum_status="$(curl -sS --max-time 10 -o /dev/null -w '%{http_code}' \
  -H 'content-type: application/json' \
  -X POST http://127.0.0.1:5847/api/rum \
  --data "{\"name\":\"INP\",\"value\":1,\"delta\":1,\"rating\":\"good\",\"id\":\"$rum_probe_id\",\"navigationType\":\"navigate\",\"path\":\"$rum_probe_path\"}")"
[[ "$rum_status" == 204 ]]
loki_probe="$(mktemp)"
trap 'rm -f "$loki_probe"' EXIT
rum_visible=false
for _ in $(seq 1 30); do
  if curl -fsSG --max-time 10 \
      --data-urlencode 'query={stack="macrosquare-host"} |= "browser_web_vital"' \
      --data-urlencode 'limit=100' \
      --data-urlencode 'since=5m' \
      --data-urlencode 'direction=backward' \
      http://127.0.0.1:5903/loki/api/v1/query_range >"$loki_probe" && \
    grep -F -q "$rum_probe_path" "$loki_probe"; then
    rum_visible=true
    break
  fi
  sleep 2
done
[[ "$rum_visible" == true ]]
printf 'browserRUM=Loki-visible\n'
rm -f "$loki_probe"
trap - EXIT

if docker logs --since 10m macrosquare-server 2>&1 \
  | grep -E -q 'OutOfMemoryError|Application run failed|BUILD FAILURE'; then
  echo 'fatal backend log signature detected' >&2
  docker logs --since 10m --tail 200 macrosquare-server >&2
  exit 1
fi
if docker logs macrosquare-server 2>&1 | grep -q 'api\.telegram\.org/bot'; then
  echo 'Telegram credential-bearing URL leaked into backend logs' >&2
  exit 1
fi
[[ -f .jaeger-sanitized-v1 ]]
trace_probe="$(mktemp)"
trap 'rm -f "$trace_probe"' EXIT
curl -fsS --max-time 15 \
  'http://127.0.0.1:16687/api/traces?service=macrosquare-server-spring&limit=100&lookback=1h' \
  >"$trace_probe"
if grep -q 'api\.telegram\.org/bot' "$trace_probe"; then
  echo 'Telegram credential-bearing URL leaked into trace storage' >&2
  exit 1
fi

# A caller-sampled W3C trace makes the Collector forwarding check deterministic
# even though normal production sampling remains at 10%.
server_environment="$(docker inspect macrosquare-server --format '{{range .Config.Env}}{{println .}}{{end}}')"
grep -F -q 'OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector:4318/v1/traces' \
  <<<"$server_environment"
trace_id="$(openssl rand -hex 16)"
span_id="$(openssl rand -hex 8)"
curl -fsS --max-time 20 \
  -H "traceparent: 00-$trace_id-$span_id-01" \
  http://127.0.0.1:5846/api/health >/dev/null
trace_visible=false
for _ in $(seq 1 30); do
  if curl -fsS --max-time 15 \
      "http://127.0.0.1:16687/api/traces/$trace_id" >"$trace_probe" 2>/dev/null && \
    python3 - "$trace_probe" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
assert payload.get("data"), payload
PY
  then
    trace_visible=true
    break
  fi
  sleep 2
done
[[ "$trace_visible" == true ]]
printf 'otelCollectorTrace=Jaeger-visible traceId=%s\n' "$trace_id"
rm -f "$trace_probe"
trap - EXIT

docker stats --no-stream --format \
  'resource {{.Name}} cpu={{.CPUPerc}} memory={{.MemUsage}} pids={{.PIDs}}' \
  macrosquare-postgres macrosquare-minio macrosquare-jaeger \
  macrosquare-otel-collector macrosquare-prometheus macrosquare-loki macrosquare-alloy \
  macrosquare-server macrosquare-client \
  </dev/null

[[ -x scripts/audit-home-observability.py ]]
[[ -x scripts/monitor-home-recurrence.py ]]
[[ -x scripts/install-home-observability-audit-cron.sh ]]
python3 -m py_compile scripts/audit-home-observability.py scripts/monitor-home-recurrence.py
python3 scripts/monitor-home-recurrence.py --no-notify >/dev/null
command -v flock >/dev/null
audit_cron_count="$(crontab -l 2>/dev/null \
  | grep -F -c '# macrosquare-daily-observability-audit' || true)"
[[ "$audit_cron_count" == 1 ]]
realtime_cron_count="$(crontab -l 2>/dev/null \
  | grep -F -c '# macrosquare-realtime-recurrence-monitor' || true)"
[[ "$realtime_cron_count" == 1 ]]
audit_dir_mode="$(stat -c '%a' .ops-audit)"
audit_log_mode="$(stat -c '%a' .ops-audit/cron.log)"
realtime_log_mode="$(stat -c '%a' .ops-audit/realtime-cron.log)"
[[ "$audit_dir_mode" == 700 ]]
[[ "$audit_log_mode" == 600 ]]
[[ "$realtime_log_mode" == 600 ]]
printf 'dailyOpsAudit=cron-installed schedule=22:20UTC/07:20KST\n'
printf 'realtimeRecurrenceMonitor=cron-installed schedule=every-minute\n'
REMOTE

printf '== frontend route smoke ==\n'
python3 - "$PUBLIC_HOST" <<'PY'
import sys
import urllib.request

host = sys.argv[1]
checks = {
    "/": 5_000,
    "/research/sectors": 5_000,
    "/research/companies?page=2": 5_000,
    "/company/NVDA": 10_000,
    "/research/crypto": 5_000,
}
for path, minimum in checks.items():
    with urllib.request.urlopen(f"http://{host}:5847{path}", timeout=20) as response:
        body = response.read()
        assert response.status == 200, (path, response.status)
        assert len(body) >= minimum, (path, len(body), minimum)
        print(f"{path}: {response.status}, {len(body)} bytes")
PY

printf 'home verification: OK\n'
