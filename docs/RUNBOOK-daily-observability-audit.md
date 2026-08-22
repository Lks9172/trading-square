# 실시간·일일 관측 및 장애 대응 Runbook

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-11**
- 실시간 recurrence monitor: **매 1분**
- 전체 read-only audit: **매일 07:20 KST**
- 운영 서버 경로: `/home/lks/trading-square`

## 1. 목적과 안전 경계

이 Runbook은 최근 24시간의 Prometheus metrics, Loki logs, Jaeger traces, PostgreSQL read model,
43개 API smoke, Docker 상태를 교차해 로그가 없는 조용한 데이터 장애까지 찾는다.

- 감사와 1차 조사는 **읽기 전용**이다.
- 자동 코드 수정, 데이터 repair, container restart, image 배포를 하지 않는다.
- 전체 증거는 `.ops-audit/reports/`에 mode `0600`으로 보존하고 Telegram에는 bounded summary만 보낸다.
- source token, Telegram token, cookie, DB/MinIO secret을 명령·문서·incident 본문에 복사하지 않는다.
- 쓰기/repair는 오염 범위, 백업, rollback SQL 또는 복원 절차가 승인된 뒤 별도 실행한다.

## 2. 탐지 체계

### Host recurrence monitor

`scripts/monitor-home-recurrence.py`가 Spring이 DB incident를 남기기 전에 죽는 blind spot을 맡는다.

- Spring `ERROR`, HTTP 5xx
- Hikari thread-starvation/clock-leap 경고와 같은 시각의 scrape broken pipe
- container unhealthy/exited/restart/OOM/paused
- Prometheus/Loki/Jaeger 자체 조회 실패
- URL·UUID·hex·숫자·token을 정규화한 fingerprint
- 동일 active fingerprint는 한 번만 알림
- 5분 quiet 뒤 같은 오류가 발생하면 재발로 다시 알림
- rolling startup의 `starting`은 최대 3분 grace, 그 이후는 장애

Spring data-integrity incident는 transactional outbox가 발송하므로 host monitor가 동일 메시지를 중복
발송하지 않는다.

### Daily audit

`scripts/audit-home-observability.py`가 매일 최근 24시간을 교차한다.

- Prometheus: HTTP 5xx, degraded, latency, scheduler/collector
- Loki: application/infrastructure ERROR·WARN과 browser RUM
- Jaeger: sampled error span, endpoint p95, collector 전달
- PostgreSQL: collection, company, analyst, sector, 13F, outbox, object pointer
- 기업 선별 E2E: 표준 섹터·전략 테마 membership 합집합=277개 V5 DB/API, 현재 점수·바닥 bundle·정렬 일치
- Docker: health, exit, restart, OOM, memory event
- API: production smoke 43개

사용자/API 5xx는 `uri!~"/actuator.*"`로 계산한다. rolling startup 중 readiness 503은 별도
`healthProbe5xx` 증거로 보존하고, 현재 health·restart/OOM 검사를 통해 실제 runtime 장애인지 판정한다.

### Spring data integrity

`DataIntegrityScheduler`가 1분마다 영속 데이터의 완전성·현재성·단위·중복을 검사한다. 정확한 계약은
[장애 재발 방지 카탈로그](development/INCIDENT-RECURRENCE-PREVENTION.md)를 따른다.

같은 active fingerprint는 검사를 멈추지 않지만 WARN을 첫 지속 확인과 30회마다만 남긴다. 신규 incident는
즉시 ERROR·Telegram, 회복은 즉시 INFO·recovery 알림이다. 따라서 WARN 줄 수가 적어졌다고 검사 주기가
느려진 것으로 판단하지 말고 `activeChecks`, integrity metric과 outbox 전이를 함께 확인한다.

## 3. 설치와 수동 실행

홈서버 timezone은 UTC다. cron의 `22:20 UTC`가 `07:20 KST`이며 두 작업 모두 `flock`으로 겹침을
차단한다.

```bash
./scripts/install-home-observability-audit-cron.sh
crontab -l | grep macrosquare

python3 scripts/monitor-home-recurrence.py --no-notify
python3 scripts/audit-company-selection-e2e.py
python3 scripts/audit-home-observability.py --lookback-hours 24 --no-notify
```

보고서는 30일 보존하고 실시간 상태는 `.ops-audit/realtime-error-state.json`에 유지한다.

## 4. 표준 조사 순서

현재 운영은 Docker Compose이며 SigNoz/Kubernetes를 사용하지 않는다. 따라서 **관측 증거 → Docker 상태 →
코드/설정 → 데이터 증상** 순서로 조사한다. 향후 SigNoz/Kubernetes로 전환하면 SigNoz trace/log/metric을
1순위, Kubernetes event/pod 상태를 2순위로 둔다.

1. latest audit `findings`와 Telegram fingerprint 확인
2. Prometheus → Loki → Jaeger에서 같은 시각·endpoint·trace 교차
3. Docker health/restart/OOM과 host 자원 확인
4. owning service/context와 배포 image·config 확인
5. PostgreSQL/MinIO에서 오염 범위를 **읽기 전용**으로 확인
6. 재현 테스트 → 원인 수정 → 영구 가드 → 배포/rollback 검증

데이터를 먼저 고쳐서 증거를 없애거나 로그 한 줄만 보고 원인을 확정하지 않는다.

## 5. 5분 초기 분류

```bash
cd /home/lks/trading-square

# 최신 감사 증거
ls -1t .ops-audit/reports/*.json 2>/dev/null | head -3
tail -n 100 .ops-audit/realtime-cron.log
tail -n 100 .ops-audit/cron.log

# runtime 상태와 재시작/OOM
docker compose ps
docker inspect macrosquare-server macrosquare-client \
  --format '{{.Name}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restart={{.RestartCount}} oom={{.State.OOMKilled}} image={{.Image}}'

# readiness와 사용자 경로
curl -fsS http://127.0.0.1:5846/actuator/health/readiness
curl -fsS http://127.0.0.1:5846/api/health
curl -fsS -o /dev/null -w '%{http_code} %{time_total}\n' http://127.0.0.1:5847/
```

`unhealthy`, restart 증가, OOM, readiness 비정상은 데이터 수집보다 runtime incident로 먼저 분류한다.
모두 정상인데 UI 값이 오래됐으면 collection/projection integrity를 조사한다.

Hikari `Thread starvation or clock leap`가 backup 시각과 겹치면 host suspend로 추정하지 않는다. 먼저
`journalctl -u docker`의 `Container ... is paused`, backup log의 `backend relational snapshot pause`,
Prometheus scrape gap을 같은 시각으로 대조한다. pause는 health가 과거 `healthy`로 남을 수 있으므로
`.State.Paused`를 반드시 확인한다.

## 6. 관측 증거 조회

### Prometheus

```bash
curl -fsSG http://127.0.0.1:5902/api/v1/query \
  --data-urlencode 'query=up'
curl -fsSG http://127.0.0.1:5902/api/v1/query \
  --data-urlencode 'query=sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))'
```

먼저 실패율과 latency가 시작된 시각을 잡고 같은 구간의 로그와 trace를 본다. 메트릭 이름을 추측해
결론 내리지 말고 `/actuator/prometheus`와 audit JSON의 실제 series를 확인한다.

### Loki

```bash
curl -fsSG http://127.0.0.1:5903/loki/api/v1/query_range \
  --data-urlencode 'query={stack="macrosquare-host"} |~ " ERROR |Exception|FATAL"' \
  --data-urlencode 'since=2h' --data-urlencode 'limit=200' \
  --data-urlencode 'direction=backward'
```

민감정보가 보이면 incident에 원문을 복사하지 말고 먼저 redaction 결함으로 분류한다.

### Jaeger

```bash
curl -fsSG http://127.0.0.1:16686/api/traces \
  --data-urlencode 'service=macrosquare-server-spring' \
  --data-urlencode 'limit=100' --data-urlencode 'lookback=1h'
```

느린 endpoint의 전체 wall time을 DB, provider HTTP, serialization span으로 분해한다. trace가 전혀 없으면
“오류 없음”이 아니라 collector/샘플링/전달 경로 장애 가능성을 먼저 확인한다.

## 7. 데이터 계층 읽기 전용 점검

```bash
# migration과 수집 상태
docker compose exec -T postgres psql -U macrosquare -d macrosquare -P pager=off -c \
  'select installed_rank, version, description, success from flyway_schema_history order by installed_rank desc limit 5'
docker compose exec -T postgres psql -U macrosquare -d macrosquare -P pager=off -c \
  'select source, status, collected_count, persisted_count, completed_at from market.collection_status order by source'

# 기업 batch의 개수·가장 오래된 계산
docker compose exec -T postgres psql -U macrosquare -d macrosquare -P pager=off -c \
  'select calculation_version, count(*), min(calculated_at), max(calculated_at) from company.research_summary group by calculation_version order by calculation_version'

# outbox 이상 행
docker compose exec -T postgres psql -U macrosquare -d macrosquare -P pager=off -c \
  "select status, count(*), min(created_at) from notification.outbox where status <> 'DELIVERED' group by status order by status"
```

schema나 column 이름이 migration과 다르면 임의로 쓰기 query를 만들지 말고 `\d+ schema.table`로 먼저
확인한다. object 장애는 DB pointer의 object key/version/SHA/size와 MinIO 실제 object를 대조하되 delete나
pointer 교체를 하지 않는다.

## 8. 증상별 분기

| 증상 | 1차 증거 | 확인할 계약 | 금지 |
|---|---|---|---|
| UI 전체 느림 | HTTP p95, slow trace, container memory | DB pool 8, scheduler lock pool 분리, single-flight | 정보 필드 삭제로 숨기기 |
| 버튼 무반응 | browser RUM, Next log, network status | 공통 interaction/loading/error, 45 route | z-index만 임시 증가 |
| 데이터 오래됨 | collection status, observed/as-of, oldest summary | source별 stale, last-valid, 277개 batch | timestamp만 현재로 갱신 |
| BUY가 이상함 | summary evidence/version/readiness | v8 gate, score/signal 원자 묶음 | UI에서 액션 재계산 |
| 바닥 거짓 신호 | OHLCV 품질, split 불연속, signal date | corporate action/no-lookahead | 현재 가격으로 과거 신호 덮기 |
| 알림 누락/중복 | candidate transition, outbox lease/status | fingerprint+transactional outbox | Telegram 직접 재발송 반복 |
| 수집 SUCCESS인데 0건 | report와 DB row count | V16 SUCCESS constraint | 정상 로그로 downgrade |
| 섹터 EPS revision 미노출 | V17, 최신 analyst revision non-null 수, sector coverage/date | 3일·5종목·50% gate 확인 | 정적 50 또는 현재값 backfill 금지 |
| 배포 후 startup 알림 없음 | readiness, outbox, dispatcher trace | persisted startup snapshot | 기동 sleep만 무한 증가 |
| 배포 검증 중 `No space left` | local/remote `df -Pk`, deploy 단계 | 원격 변경 전 128MiB/4GiB preflight | 검증 생략·볼륨 삭제 |

## 9. 수정·재발 방지 완료 조건

1. incident fingerprint/최초 시각/사용자 영향을 기록한다.
2. 오염 가능 ticker/source/date 범위를 읽기 전용으로 확정한다.
3. root cause를 owning Domain/application/adapter/infrastructure 중 하나로 지정한다.
4. 원인 수정을 하고 과거 입력을 재현하는 테스트를 추가한다.
5. 저장 가능한 잘못된 상태라면 Flyway constraint/unique/transaction을 추가한다.
6. 1분 integrity/host monitor 또는 daily audit이 동일 재발을 찾게 한다.
7. 기존 데이터 repair가 필요하면 백업·dry-run·영향 row·rollback을 문서화한다.
8. ADR/PDR와 CURRENT 문서를 갱신하고 문서 검증을 통과한다.
9. 표준 배포 후 container/image/Flyway/43 smoke/로그/DB를 실측한다.

자세한 실패 ID와 템플릿은
[장애 재발 방지](development/INCIDENT-RECURRENCE-PREVENTION.md), 테스트 기준은
[테스트·품질 게이트](development/TESTING-AND-QUALITY-GATES.md)를 따른다.

## 10. 배포와 rollback

```bash
python3 scripts/verify-documentation.py
./scripts/check-cutover-invariants.sh
./scripts/deploy-home.sh
```

배포 스크립트가 readiness, image ID, Flyway V17, 43 smoke, UI route를 확인하고 실패하면 직전
compose/image로 자동 복원한다. 데이터 volume을 삭제하거나 `--remove-orphans`로 무관 서비스를 내리지
않는다. 전체 절차는 [배포·롤백·복구](development/DEPLOYMENT-ROLLBACK-RECOVERY.md)를 따른다.

## 11. Incident 기록 템플릿

```text
ID / 최초 탐지 시각 / fingerprint:
사용자·금융 영향:
관측 증거(metric/log/trace/runtime):
오염 가능 source·ticker·date·row:
root cause와 owning context:
코드 수정:
DB migration/constraint:
회귀 테스트:
운영 탐지 추가:
repair/dry-run/rollback:
배포 image/Flyway/smoke 증거:
ADR/PDR/CURRENT 문서:
```
