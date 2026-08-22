#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

fail() {
  printf 'cutover invariant failed: %s\n' "$1" >&2
  exit 1
}

python3 scripts/verify-documentation.py \
  || fail "financial/development documentation contracts must match production code"

[[ "$(cat server-spring/.java-version)" == "21" ]] || fail ".java-version must be 21"
grep -q '<java.version>21</java.version>' server-spring/pom.xml \
  || fail "Maven java.version must be 21"
grep -q '<version>4.1.0</version>' server-spring/pom.xml \
  || fail "Spring Boot parent must be 4.1.0"
grep -q '"next": "16.3.0"' client/package.json \
  || fail "Next.js must remain on the audited 16.3.0 patch"

forbidden_boundary_imports='^import (org\.springframework|jakarta\.|com\.fasterxml\.jackson|tools\.jackson|org\.hibernate|org\.postgresql|io\.minio|software\.amazon\.awssdk|java\.net\.http|java\.nio\.file|java\.sql)'
if grep -R -n -E --include='*.java' "$forbidden_boundary_imports" \
  server-spring/domain/src/main server-spring/application/src/main; then
  fail "domain/application contains framework or infrastructure imports"
fi

runtime_bridge_patterns='ProcessBuilder|Runtime\.getRuntime\(\)\.exec|NODE_BASE_URL|LEGACY_NODE_BASE_URL|nodeExecutable|npmExecutable|npxExecutable|localhost:5846|127\.0\.0\.1:5846'
if grep -R -n -E --include='*.java' --include='*.yml' --include='*.yaml' \
  "$runtime_bridge_patterns" \
  server-spring/domain/src/main \
  server-spring/application/src/main \
  server-spring/adapters/src/main \
  server-spring/bootstrap/src/main; then
  fail "production Java runtime still references a legacy bridge"
fi

grep -q 'image: macrosquare-server-spring:production' docker-compose.yml \
  || fail "production compose must use the Spring server image"
grep -q 'image: otel/opentelemetry-collector-contrib:0.156.0' docker-compose.yml \
  || fail "OpenTelemetry Collector must use the audited pinned image"
grep -q 'image: prom/prometheus:v3.13.1' docker-compose.yml \
  || fail "Prometheus must use the audited pinned image"
grep -q 'image: grafana/loki:3.7.3' docker-compose.yml \
  || fail "Loki must use the audited pinned image"
grep -q 'image: grafana/alloy:v1.18.0' docker-compose.yml \
  || fail "Alloy must use the audited pinned image"
grep -q 'image: postgres:18.4-alpine3.24' docker-compose.yml \
  || fail "production compose must pin PostgreSQL 18"
grep -q 'quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z' docker-compose.yml \
  || fail "production compose must pin MinIO"
grep -q 'STORAGE_MODE=postgres-minio' docker-compose.yml \
  || fail "production server must use PostgreSQL + MinIO"
grep -q 'OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://otel-collector:4318/v1/traces' docker-compose.yml \
  || fail "Spring traces must pass through the dedicated OTel Collector"
grep -q '<artifactId>micrometer-registry-prometheus</artifactId>' server-spring/bootstrap/pom.xml \
  || fail "Spring runtime must publish Prometheus metrics"
grep -q 'include: health,info,metrics,prometheus' server-spring/bootstrap/src/main/resources/application.yml \
  || fail "the Prometheus actuator endpoint must be exposed"
grep -q 'LEGACY_IMPORT_ENABLED=false' docker-compose.yml \
  || fail "production legacy relational import must remain disabled"
grep -q 'INVESTMENT_EXECUTION_IMPORT_LEGACY=false' docker-compose.yml \
  || fail "execution must not read legacy command files"
server_compose_block="$(sed -n '/^  server:/,/^  client:/p' docker-compose.yml)"
if grep -E -q '^[[:space:]]+- (\./server/data|macrosquare-spring-data):' <<<"$server_compose_block"; then
  fail "production server still mounts a legacy runtime filesystem"
fi
minio_init_block="$(sed -n '/^  minio-init:/,/^  minio-seed-import:/p' docker-compose.yml)"
if grep -E -q '/seed|/source-cache|/legacy-history|server/data' <<<"$minio_init_block"; then
  fail "default MinIO initializer still mounts legacy seed files"
fi
grep -q 'profiles: \["legacy-seed"\]' docker-compose.yml \
  || fail "preserved seed import must require an explicit compose profile"
grep -q 'MINIO_SECRET_KEY=\${MINIO_APP_SECRET_KEY}' docker-compose.yml \
  || fail "production application must not use the MinIO root credential"
grep -q 'COMPANY_ANALYST_HISTORY_READ_MODE=store-preferred' docker-compose.yml \
  || fail "analyst history must prefer the owned store"
grep -q 'docker compose run --rm --no-deps minio-init </dev/null' scripts/deploy-home-full.sh \
  || fail "MinIO init must not consume the SSH deployment here-doc"
grep -q '"\$ROOT_DIR/observability/"' scripts/deploy-home-full.sh \
  || fail "deployment must synchronize observability configuration"
grep -q '"\$ROOT_DIR/docs/"' scripts/deploy-home-full.sh \
  || fail "deployment must synchronize CURRENT financial/development documentation"
grep -q 'remote_previous_observability' scripts/deploy-home-full.sh \
  || fail "deployment must preserve observability configuration for rollback"
grep -q 'browserRUM=Loki-visible' scripts/verify-home.sh \
  || fail "home verification must prove the complete browser RUM log path"
grep -q 'otelCollectorTrace=Jaeger-visible' scripts/verify-home.sh \
  || fail "home verification must prove Collector trace forwarding"
grep -q 'install-home-observability-audit-cron.sh' scripts/deploy-home-full.sh \
  || fail "deployment must install the daily observability audit idempotently"
grep -q 'dailyOpsAudit=cron-installed' scripts/verify-home.sh \
  || fail "home verification must prove the daily observability audit schedule"
grep -q 'monitor-home-recurrence.py' scripts/install-home-observability-audit-cron.sh \
  || fail "deployment must install the one-minute recurrence monitor"
grep -q 'realtimeRecurrenceMonitor=cron-installed' scripts/verify-home.sh \
  || fail "home verification must prove the one-minute recurrence schedule"
grep -q 'from flyway_schema_history" </dev/null' scripts/verify-home.sh \
  || fail "PostgreSQL verification must not consume the SSH verification here-doc"
grep -q 'from notification.outbox" </dev/null' scripts/verify-home.sh \
  || fail "notification outbox verification must not consume the SSH verification here-doc"
grep -q 'V6__harden_operational_retention_and_reads.sql' scripts/test-postgres-multi-instance.sh \
  || fail "real PostgreSQL tests must apply the operational hardening migration"
grep -q 'V7__add_krx_investor_flow_source.sql' scripts/test-postgres-multi-instance.sh \
  || fail "real PostgreSQL tests must apply the KRX market-source migration"
grep -q 'V11__add_company_fundamentals_freshness.sql' scripts/test-postgres-multi-instance.sh \
  || fail "real PostgreSQL tests must apply the company freshness migration"
grep -q 'V12__harden_current_company_universe.sql' scripts/test-postgres-multi-instance.sh \
  || fail "real PostgreSQL tests must apply current company-universe hardening"
grep -q 'V15__enforce_recurrence_integrity_guards.sql' scripts/test-postgres-multi-instance.sh \
  || fail "real PostgreSQL tests must apply recurrence integrity guards"
grep -q 'V16__guard_market_collection_outcomes.sql' scripts/test-postgres-multi-instance.sh \
  || fail "real PostgreSQL tests must apply collection outcome guards"
grep -q 'V17__persist_dated_eps_revision_evidence.sql' scripts/test-postgres-multi-instance.sh \
  || fail "real PostgreSQL tests must apply dated EPS revision persistence"
grep -q 'V18__persist_sector_fund_flow_and_price_breadth.sql' scripts/test-postgres-multi-instance.sh \
  || fail "real PostgreSQL tests must apply official sector flow and price breadth persistence"
grep -q 'V19__create_sector_rotation_validation_ledger.sql' scripts/test-postgres-multi-instance.sh \
  || fail "real PostgreSQL tests must apply immutable sector rotation validation persistence"
grep -q 'V20__separate_sector_signal_date_and_price_anchor.sql' scripts/test-postgres-multi-instance.sh \
  || fail "real PostgreSQL tests must separate sector signal dates from total-return price anchors"
grep -q 'V21__persist_notification_reversal_evidence.sql' scripts/test-postgres-multi-instance.sh \
  || fail "real PostgreSQL tests must persist notification reversal evidence"
grep -q 'V22__persist_macd_notification_evidence.sql' scripts/test-postgres-multi-instance.sh \
  || fail "real PostgreSQL tests must persist compact MACD notification evidence"
grep -q 'DATA_INTEGRITY_MONITOR_ENABLED=true' docker-compose.yml \
  || fail "production must enable the recurrence integrity monitor"
grep -q 'DATA_INTEGRITY_MONITOR_INITIAL_DELAY=5m' docker-compose.yml \
  || fail "startup collectors must refresh durable source state before the first integrity verdict"
grep -q 'DATA_INTEGRITY_MONITOR_FIXED_DELAY=1m' docker-compose.yml \
  || fail "recurrence integrity checks must run every minute"
grep -q 'recurrenceGuards=v15' scripts/verify-home.sh \
  || fail "home verification must prove the recurrence guard migration"
grep -q 'collectionOutcomeGuard=v16' scripts/verify-home.sh \
  || fail "home verification must prove the market collection outcome guard migration"
grep -q 'datedEpsRevision=v17' scripts/verify-home.sh \
  || fail "home verification must prove the dated EPS revision migration"
grep -q 'sectorMarketEvidence=v18' scripts/verify-home.sh \
  || fail "home verification must prove the sector market evidence migration"
grep -q 'sectorValidationLedger=v19' scripts/verify-home.sh \
  || fail "home verification must prove the immutable sector validation migration"
grep -q 'sectorSignalPriceDates=v20' scripts/verify-home.sh \
  || fail "home verification must prove the sector signal/price-date correction"
grep -q 'notificationReversalEvidence=v21' scripts/verify-home.sh \
  || fail "home verification must prove persisted notification reversal evidence"
grep -q 'macdNotificationEvidence=v22' scripts/verify-home.sh \
  || fail "home verification must prove persisted MACD notification evidence"
grep -q 'COMPANY_RESEARCH_SUMMARY_CONCURRENCY=4' docker-compose.yml \
  || fail "production company summary concurrency must stay thermally bounded"
grep -q 'SEC_13F_STARTUP_FRESHNESS=2h' docker-compose.yml \
  || fail "re-deploys must reuse a recent durable SEC 13F collection"
grep -q 'mem_limit: 384m' docker-compose.yml \
  || fail "production observability memory headroom must remain configured"
grep -q 'balance_power' scripts/install-home-power-profile.sh \
  || fail "home-server power profile installer must preserve balanced power mode"
grep -q 'audit-company-selection-e2e.py' scripts/verify-home.sh \
  || fail "home verification must prove current company selection API/DB consistency"
grep -q 'audit-company-selection-e2e.py </dev/null' scripts/verify-home.sh \
  || fail "stdin-streamed home verification must isolate the company selection checker stdin"
grep -q 'company-selection-e2e' scripts/audit-home-observability.py \
  || fail "daily audit must detect company selection E2E recurrence"
grep -q 'SECTOR_MARKET_EVIDENCE_ENABLED=true' docker-compose.yml \
  || fail "production must schedule official sector flow and price breadth collection"
grep -q 'recurrenceGuards=%s/11' scripts/verify-home.sh \
  || fail "home verification must prove all eleven recurrence constraints"
grep -q 'SimpleDriverDataSource.class' server-spring/bootstrap/src/main/java/io/macrosquare/bootstrap/config/PostgresMinioStorageConfiguration.java \
  || fail "long-lived scheduler locks must use a data source isolated from Hikari"
grep -q 'EXCLUSIVE_TASK_MAX_CONCURRENCY=4' docker-compose.yml \
  || fail "physical scheduler lock connections must remain bounded"
grep -q 'new Semaphore(maximumConcurrentLocks, true)' server-spring/adapters/src/main/java/io/macrosquare/shared/adapter/out/persistence/PostgresAdvisoryTaskExecution.java \
  || fail "scheduler lock connection bound must be fair and explicit"
grep -q 'USER node' client/Dockerfile \
  || fail "production Next.js runtime must be non-root"
grep -q 'condition: service_healthy' docker-compose.yml \
  || fail "frontend cutover must wait for Spring readiness"
grep -q 'wait_healthy macrosquare-client' scripts/deploy-home-full.sh \
  || fail "deployment must wait for the Next.js healthcheck before commit"
grep -q 'DEPLOY_LOCAL_MIN_AVAILABLE_KB:-131072' scripts/deploy-home-full.sh \
  || fail "deployment must fail before cutover when local disk space is exhausted"
grep -q 'DEPLOY_REMOTE_MIN_AVAILABLE_KB:-4194304' scripts/deploy-home-full.sh \
  || fail "deployment must fail before cutover when home-server disk space is unsafe"
grep -q 'no-new-privileges:true' docker-compose.yml \
  || fail "application containers must prevent privilege escalation"
if grep -q '/var/run/docker.sock' docker-compose.yml observability/alloy.alloy; then
  fail "log collection must not receive Docker control-socket access"
fi
grep -q '/var/lib/docker/containers:/var/lib/docker/containers:ro' docker-compose.yml \
  || fail "Alloy must read container logs through the read-only log directory"
alloy_compose_block="$(sed -n '/^  alloy:/,/^  server:/p' docker-compose.yml)"
grep -q 'group_add:' <<<"$alloy_compose_block" \
  || fail "capability-free Alloy must retain its image storage group"
grep -q '"473"' <<<"$alloy_compose_block" \
  || fail "Alloy storage group must match the pinned image"
for loopback_binding in \
  '127.0.0.1:4338:4318' \
  '127.0.0.1:13133:13133' \
  '127.0.0.1:5902:9090' \
  '127.0.0.1:5903:3100' \
  '127.0.0.1:5904:12345'; do
  grep -q "$loopback_binding" docker-compose.yml \
    || fail "observability port must stay loopback-only: $loopback_binding"
done
grep -F -q '/api/traces?service=macrosquare-server-spring&limit=100&lookback=1h' scripts/verify-home.sh \
  || fail "Jaeger verification must use the bounded query API"
grep -q '</dev/null >"\$target/postgres.dump"' scripts/backup-home-storage.sh \
  || fail "PostgreSQL backup must not consume the SSH backup here-doc"
grep -q 'BACKUP_MAX_SERVER_PAUSE_SECONDS' scripts/backup-home-storage.sh \
  || fail "storage backup must bound the backend pause window"
grep -q 'timeout --foreground "${max_pause_seconds}s" bash -c capture_relational_snapshot' \
  scripts/backup-home-storage.sh \
  || fail "relational snapshot capture must fail closed at the pause deadline"
preflight_line="$(grep -n '^remote_output=' scripts/backup-home-storage.sh | head -n 1 | cut -d: -f1)"
capacity_line="$(grep -n '^local_required_kb=' scripts/backup-home-storage.sh | head -n 1 | cut -d: -f1)"
[[ -n "$preflight_line" && -n "$capacity_line" && "$capacity_line" -lt "$preflight_line" ]] \
  || fail "off-host capacity must be proven before the home server can be paused"
resume_line="$(grep -n '^ensure_server_resumed$' scripts/backup-home-storage.sh | tail -n 1 | cut -d: -f1)"
mirror_line="$(grep -n '^docker compose run --rm --no-deps' scripts/backup-home-storage.sh | head -n 1 | cut -d: -f1)"
[[ -n "$resume_line" && -n "$mirror_line" && "$resume_line" -lt "$mirror_line" ]] \
  || fail "backend must resume before the full MinIO mirror begins"
grep -F -q 'chown -R "$BACKUP_UID:$BACKUP_GID" /backup' scripts/backup-home-storage.sh \
  || fail "MinIO backup files must be returned to the host backup owner"
grep -q "grep -q 'DeleteObject'" scripts/verify-home.sh \
  || fail "MinIO application policy must be checked for delete access"
grep -q -- '--force-recreate server' scripts/deploy-home-full.sh \
  || fail "deployment must force the Spring container onto the built image"
grep -q 'actual_server_image' scripts/deploy-home-full.sh \
  || fail "deployment must verify the running Spring image ID"
for deploy_script in scripts/deploy-home.sh scripts/deploy-home-full.sh scripts/deploy-home-scoped.sh scripts/verify-home.sh; do
  grep -q 'ServerAliveCountMax=40' "$deploy_script" \
    || fail "$deploy_script must tolerate home-server build saturation without dropping SSH"
  grep -q 'TCPKeepAlive=yes' "$deploy_script" \
    || fail "$deploy_script must enable TCP keepalive for deployment transport"
done
grep -q '^export RSYNC_RSH=' scripts/deploy-home.sh \
  || fail "auto deployment rsync inspection must inherit the hardened SSH transport"
grep -q 'macrosquare-server-spring:rollback-\*|macrosquare-client:rollback-\*' scripts/deploy-home-full.sh \
  || fail "successful deployment must prune historical application rollback tags"
grep -q 'historical application rollback image tag remains' scripts/verify-home.sh \
  || fail "home verification must reject historical application rollback tags"
[[ "$(grep -c 'docker image rm \"\$rollback_tag\"' scripts/deploy-home-scoped.sh)" -ge 4 ]] \
  || fail "scoped deploy must clean temporary rollback tags on server/client success and rollback paths"
grep -q 'macrosquare-server-preflight-' scripts/deploy-home-full.sh \
  || fail "deployment must preflight the production storage profile before cutover"
grep -q -- '--plan|--auto|--server|--client|--scripts|--docs|--verify|--full' scripts/deploy-home.sh \
  || fail "deployment dispatcher must expose explicit scopes"
grep -q 'classify-deploy-scope.py' scripts/deploy-home.sh \
  || fail "auto deployment must use the tested fail-full classifier"
grep -F -q 'substr($1, 1, 1) == "<"' scripts/deploy-home.sh \
  || fail "auto deployment must detect local-to-remote rsync transfers on macOS"
grep -q 'scope = "server"' scripts/classify-deploy-scope.py \
  || fail "auto deployment must support server-only cutover"
grep -q 'scope = "client"' scripts/classify-deploy-scope.py \
  || fail "auto deployment must support client-only cutover"
grep -q -- '--exclude target --exclude '\''\.idea'\'' --exclude docs' scripts/deploy-home.sh \
  || fail "server documentation changes must not restart the backend"
grep -q 'server-spring/docs/' scripts/deploy-home-scoped.sh \
  || fail "documentation scope must synchronize backend decision records"
grep -q 'replacementCompanyDetail=RBLX/EPD' scripts/verify-home.sh \
  || fail "home verification must prove new universe replacements have working detail routes"
for deploy_script in scripts/deploy-home-full.sh scripts/deploy-home-scoped.sh; do
  grep -q 'DATA_INTEGRITY_EXPECTED_COMPANY_UNIVERSE' "$deploy_script" \
    || fail "$deploy_script must read the exact company-universe cutover contract"
  grep -q 'expected_summary_total' "$deploy_script" \
    || fail "$deploy_script must reject a self-consistent but incomplete company projection"
done
grep -q 'enriched-current-identity' scripts/verify-home.sh \
  || fail "home verification must prove replacement detail enrichment, not only HTTP 200"
for membership_adapter in \
  server-spring/adapters/src/main/java/io/macrosquare/company/adapter/out/research/ResearchCatalogCompanyAnalystUniverseAdapter.java \
  server-spring/adapters/src/main/java/io/macrosquare/compatibility/adapter/out/earnings/ResearchCatalogEarningsUniverseAdapter.java; do
  grep -q 'LoadResearchCatalogPort' "$membership_adapter" \
    || fail "$membership_adapter must read raw catalog membership"
  if grep -q 'QueryResearchCatalogUseCase' "$membership_adapter"; then
    fail "$membership_adapter must not depend on current sector-rotation assessment"
  fi
done
grep -q 'COMPANY_RESEARCH_SUMMARY_STARTUP_DELAY:3m' server-spring/bootstrap/src/main/resources/application.yml \
  || fail "company summary startup must wait for initial market/sector collection"
grep -q 'COMPANY_ANALYST_HISTORY_STARTUP_DELAY:15m' server-spring/bootstrap/src/main/resources/application.yml \
  || fail "analyst startup must not overlap the company summary full-universe refresh"
grep -q 'TELEGRAM_POST_STARTUP_RECALCULATION_DELAY:20m' server-spring/bootstrap/src/main/resources/application.yml \
  || fail "post-startup candidate recalculation must wait for provider-heavy refreshes"
grep -q 'provider-heavy-scheduler-overlap' scripts/audit-home-observability.py \
  || fail "daily audit must detect cross-job provider-heavy startup overlap"
for provider_heavy_scheduler in \
  server-spring/adapters/src/main/java/io/macrosquare/company/adapter/in/scheduling/CompanyResearchSummaryScheduler.java \
  server-spring/adapters/src/main/java/io/macrosquare/company/adapter/in/scheduling/CompanyAnalystHistoryScheduler.java \
  server-spring/adapters/src/main/java/io/macrosquare/notification/adapter/in/scheduling/NotificationScheduler.java; do
  grep -q 'ScheduledTaskNames.COMPANY_PROVIDER_HEAVY' "$provider_heavy_scheduler" \
    || fail "$provider_heavy_scheduler must use the shared provider-heavy company slot"
done
grep -q 'POST_STARTUP_MAX_ATTEMPTS = 6' \
  server-spring/adapters/src/main/java/io/macrosquare/notification/adapter/in/scheduling/NotificationScheduler.java \
  || fail "post-startup candidate recalculation must retain bounded retries"
grep -q 'DEPLOY_SERVER_RELEASE' scripts/deploy-home.sh \
  || fail "persistence-sensitive server changes must escalate to release verification"
grep -q 'docker compose build server' scripts/deploy-home-scoped.sh \
  || fail "server scope must build only the backend"
grep -q 'docker compose build client' scripts/deploy-home-scoped.sh \
  || fail "client scope must build only the frontend"
grep -q 'wait_healthy macrosquare-client' scripts/deploy-home-scoped.sh \
  || fail "client scoped cutover must wait for container health before verification"
grep -q 'prometheus_metrics' scripts/deploy-home-scoped.sh \
  || fail "scoped server deployment must avoid pipefail curl 23 during Prometheus verification"
grep -q 'application containers were not restarted' scripts/deploy-home-scoped.sh \
  || fail "scripts/docs scope must preserve application uptime"
[[ ! -e docker-compose.node-rollback.yml ]] \
  || fail "decommissioned Node rollback compose must not be restored"

if command -v docker >/dev/null 2>&1; then
  # Compose requires production secrets at interpolation time even though this
  # local check never starts containers. Use process-local non-secret sentinels
  # so a clean developer machine can validate the contract; the deployment
  # host still generates and persists its real credentials independently.
  POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-local-invariant-only}" \
  MINIO_SECRET_KEY="${MINIO_SECRET_KEY:-local-invariant-only}" \
  MINIO_APP_SECRET_KEY="${MINIO_APP_SECRET_KEY:-local-invariant-only}" \
    docker compose config --quiet
else
  printf 'docker CLI unavailable locally; deployment host will validate compose\n'
fi
printf 'cutover invariants: OK\n'
