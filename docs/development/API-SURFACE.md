# 공개 API 표면과 소유권

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-17**
- 공개 `/api` route 계약: **45개**
- production smoke checks: **43개**

실제 route 누락 여부는 `PublicApiRouteCoverageTest`, 응답 의미는
`server-spring/migration/tools/smoke-production-api.py`가 배포마다 검증한다. 이 문서는 탐색용이며 Controller
DTO의 완전한 schema를 복제하지 않는다.

## Market

- `GET|POST /api/snapshot`
- `POST /api/refresh`
- `GET /api/history/coverage`
- `GET /api/history/{source}/{key}`
- `GET /api/history-series`
- `GET /api/smart-money`
- `GET /api/correlation`
- `GET /api/backtest/summary`
- `GET /api/backtest/portfolio`
- `GET /api/backtest/user-plan`
- `GET /api/earnings`

Snapshot은 `raw`, `derived`, `signals`, `allocation`을 소유하며 freshness와 methodology를 함께 제공한다.

## Company

- `GET /api/company/{ticker}`
- `GET /api/company-summaries`
- `GET /api/company-search`

기업 상세의 최종 action은 server domain 결정이다. client가 score를 재계산하지 않는다.

## Research

- `GET /api/research/themes`
- `GET /api/research/themes/{id}`
- `GET /api/research/sectors`
- `GET /api/research/sectors/{id}`
- `GET /api/research/sectors/backtest`
- `GET /api/research/sectors/backtest/current`
- `GET /api/research/peers/{ticker}`
- `GET /api/research/companies`
- `GET /api/research/highlights`
- `GET /api/bottleneck/themes`
- `GET /api/bottleneck/themes/{id}`
- `GET /api/narrative/themes`
- `GET /api/narrative/themes/{id}`
- `GET /api/narrative/overview`

현재 섹터 backtest는 V2 total return, 구형 endpoint는 LEGACY_REFERENCE_ONLY를 유지한다.
현재 섹터 순환의 표준 momentum coverage가 70% 미만이거나 핵심 거시 입력이 부족하면 sector/theme current
overlay route는 HTTP 503을 반환한다. 이는 정상적인 후보 공백과 다르며 raw catalog membership을 사용하는
기업·analyst·earnings 배치를 중단시키지 않는다.

## Crypto

- `GET /api/research/crypto`
- `GET /api/research/crypto/{symbol}`

stale freshness일 때 market action HOLD/관찰 대기, target exposure 0을 보장한다.

## Institutional·Policy·Disclosure

- `GET /api/institutional-flows`
- `GET /api/policy-intelligence`
- `GET /api/dart/disclosures/{stockCode}`
- `GET /api/domestic-reports`

optional credential은 `/actuator/info`에서 secret 없이 enabled/configured/status만 진단한다.

## Execution

- `GET|POST /api/execution-plan/tranche`
- `DELETE /api/execution-plan/tranche/{asset}`
- `GET /api/execution-plan/purchasing-power`
- `GET|POST /api/plan`
- `GET|POST /api/trade-log`
- `GET /api/weekly-report`

Command는 aggregate transaction과 audit append를 사용한다. GET smoke는 read-only이며 production smoke가
사용자 plan/trade를 변경하지 않는다.

## Health

- `GET /api/health`
- `GET /actuator/health`
- `GET /actuator/health/readiness`
- `GET /actuator/info`
- `GET /actuator/prometheus` — loopback/private monitoring only

## API 변경 규칙

1. Controller DTO는 adapter에만 둔다.
2. 새 public route는 route coverage expected set과 smoke에 추가한다.
3. 점수에는 version/as-of/freshness/method를 가능한 범위에서 포함한다.
4. proxy/heuristic/legacy를 direct/current로 이름 바꾸지 않는다.
5. partial failure를 HTTP 200 빈 정상값으로 숨기지 않는다.
6. client compatibility가 필요한 field 제거는 2단계 deprecation으로 진행한다.
7. mutation route는 idempotency·transaction·validation·audit를 명시한다.
8. 문서와 테스트를 같은 변경에서 갱신한다.
