#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POSTGRES_IMAGE="${POSTGRES_TEST_IMAGE:-postgres:18.4-alpine3.24}"
MAVEN_IMAGE="${MAVEN_TEST_IMAGE:-maven:3.9.11-eclipse-temurin-21-alpine}"
RUN_ID="$$-$(date +%s)"
POSTGRES_CONTAINER="macrosquare-postgres-it-${RUN_ID}"
PASSWORD="integration-only-${RUN_ID}"

cleanup() {
  docker rm -f "${POSTGRES_CONTAINER}" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

command -v docker >/dev/null 2>&1 || {
  echo "docker is required for the PostgreSQL integration test" >&2
  exit 1
}

echo "[postgres-it] starting disposable PostgreSQL ${POSTGRES_IMAGE}"
docker run -d --rm \
  --name "${POSTGRES_CONTAINER}" \
  -e POSTGRES_DB=macrosquare \
  -e POSTGRES_USER=macrosquare \
  -e "POSTGRES_PASSWORD=${PASSWORD}" \
  "${POSTGRES_IMAGE}" >/dev/null

# The official image briefly exposes its bootstrap PostgreSQL before stopping
# it and exec'ing the final server. pg_isready alone can therefore produce a
# false positive in that transition window. Require the init-complete marker
# and a real query against the final process before applying migrations.
postgres_ready=false
for _ in $(seq 1 90); do
  if docker logs "${POSTGRES_CONTAINER}" 2>&1 \
      | grep -q 'PostgreSQL init process complete; ready for start up' \
      && docker exec "${POSTGRES_CONTAINER}" \
        psql -v ON_ERROR_STOP=1 -U macrosquare -d macrosquare -Atc 'select 1' \
        >/dev/null 2>&1; then
    postgres_ready=true
    break
  fi
  sleep 1
done
[[ "${postgres_ready}" == true ]] || {
  docker logs "${POSTGRES_CONTAINER}" >&2 || true
  echo "disposable PostgreSQL did not reach final readiness" >&2
  exit 1
}

echo "[postgres-it] applying production Flyway migrations"
cat \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V1__create_owned_storage.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V2__create_notification_outbox.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V3__create_institutional_policy_storage.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V4__extend_research_policy_and_dart.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V5__create_narrative_source_history.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V6__harden_operational_retention_and_reads.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V7__add_krx_investor_flow_source.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V8__track_peer_taxonomy_checks.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V9__track_market_collection_status.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V10__create_company_research_summary.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V11__add_company_fundamentals_freshness.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V12__harden_current_company_universe.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V13__purge_retired_current_universe_state.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V14__version_company_research_decisions.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V15__enforce_recurrence_integrity_guards.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V16__guard_market_collection_outcomes.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V17__persist_dated_eps_revision_evidence.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V18__persist_sector_fund_flow_and_price_breadth.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V19__create_sector_rotation_validation_ledger.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V20__separate_sector_signal_date_and_price_anchor.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V21__persist_notification_reversal_evidence.sql" \
  "${ROOT_DIR}/server-spring/bootstrap/src/main/resources/db/migration/V22__persist_macd_notification_evidence.sql" \
  | docker exec -i "${POSTGRES_CONTAINER}" \
      psql -v ON_ERROR_STOP=1 -U macrosquare -d macrosquare >/dev/null

integrity_guard_count="$(docker exec "${POSTGRES_CONTAINER}" \
  psql -v ON_ERROR_STOP=1 -U macrosquare -d macrosquare -Atc \
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
  )")"
[[ "${integrity_guard_count}" -eq 11 ]] || {
  echo "expected 11 recurrence integrity guards, found ${integrity_guard_count}" >&2
  exit 1
}

sector_evidence_tables="$(docker exec "${POSTGRES_CONTAINER}" \
  psql -v ON_ERROR_STOP=1 -U macrosquare -d macrosquare -Atc \
  "select count(*) from information_schema.tables
    where table_schema = 'research'
      and table_name in ('sector_fund_flow_snapshot', 'sector_price_breadth_snapshot')")"
[[ "${sector_evidence_tables}" -eq 2 ]] || {
  echo "expected both V18 sector evidence tables, found ${sector_evidence_tables}" >&2
  exit 1
}

validation_tables="$(docker exec "${POSTGRES_CONTAINER}" \
  psql -v ON_ERROR_STOP=1 -U macrosquare -d macrosquare -Atc \
  "select count(*) from information_schema.tables
    where table_schema = 'research'
      and table_name in ('sector_rotation_run', 'sector_rotation_item_snapshot', 'sector_rotation_outcome')")"
[[ "${validation_tables}" -eq 3 ]] || {
  echo "expected all V19/V20 sector validation ledger tables, found ${validation_tables}" >&2
  exit 1
}

validation_date_guard_count="$(docker exec "${POSTGRES_CONTAINER}" \
  psql -v ON_ERROR_STOP=1 -U macrosquare -d macrosquare -Atc \
  "select count(*) from pg_constraint where conname in (
      'sector_rotation_run_price_session_uk',
      'sector_rotation_run_price_date_ck'
  )")"
[[ "${validation_date_guard_count}" -eq 2 ]] || {
  echo "expected both V20 signal/price-date guards, found ${validation_date_guard_count}" >&2
  exit 1
}

notification_evidence_guard_count="$(docker exec "${POSTGRES_CONTAINER}" \
  psql -v ON_ERROR_STOP=1 -U macrosquare -d macrosquare -Atc \
  "select count(*) from pg_constraint where conname in (
      'company_research_summary_reversal_status_ck',
      'company_research_summary_reversal_score_ck',
      'company_research_summary_price_signal_reasons_ck',
      'company_research_summary_macd_timing_ck',
      'notification_candidate_snapshot_macd_timing_ck'
  )")"
[[ "${notification_evidence_guard_count}" -eq 5 ]] || {
  echo "expected all V21/V22 notification evidence guards, found ${notification_evidence_guard_count}" >&2
  exit 1
}

echo "[postgres-it] running real concurrency and multi-instance tests"
docker run --rm \
  --network "container:${POSTGRES_CONTAINER}" \
  -v "${ROOT_DIR}/server-spring:/workspace" \
  -v macrosquare-maven-cache:/root/.m2 \
  -w /workspace \
  -e MACROSQUARE_TEST_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/macrosquare \
  -e MACROSQUARE_TEST_POSTGRES_USERNAME=macrosquare \
  -e "MACROSQUARE_TEST_POSTGRES_PASSWORD=${PASSWORD}" \
  "${MAVEN_IMAGE}" \
  mvn -q -pl adapters -am \
    -Dtest=PostgresMultiInstanceIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false test

echo "[postgres-it] PASS"
