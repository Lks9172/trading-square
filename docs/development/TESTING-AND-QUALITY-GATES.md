# 테스트 전략·품질 게이트

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-08**

## 1. 현재 자동 테스트 기준선

| 영역 | 현재 테스트 수 | 목적 |
|---|---:|---|
| Domain | 241 | 금융 산식·불변식·edge case |
| Application | 149 | use case·fail-closed·port orchestration |
| Adapters | 257 | parser·DB/HTTP/REST mapping |
| Bootstrap | 26 | wiring·route/config/health |
| Architecture | 6 | 의존 방향·bounded-context 경계 |
| Frontend | 9 | server API resilience 중심 |

숫자는 품질의 전부가 아니지만 갑작스러운 감소는 회귀 신호다. `mvn clean verify`와 CI 결과가 실제
기준이다.

## 2. 로컬 필수 명령

```bash
./scripts/test-tier.sh fast server
./scripts/test-tier.sh fast client
./scripts/test-tier.sh fast scripts
./scripts/test-tier.sh standard
./scripts/test-tier.sh release # schema/persistence/release 계약 변경에만
```

`standard`는 backend verify, frontend test/lint/build, 운영·문서 검증을 병렬 실행한다. `release`는 clean
backend build와 disposable PostgreSQL 18 multi-instance 검증을 추가한다. 배포 중 production smoke와
`verify-home.sh`는 테스트 tier와 무관하게 유지한다.

## 3. 테스트 층

### Domain unit

- 경계값 바로 아래/같음/바로 위
- 결측, NaN/Infinity, 빈 목록, 오래된 날짜
- 미래정보를 입력하지 않는 순수 policy
- 점수와 action의 상한/하한
- 위험 gate가 좋은 점수를 실제로 차단하는지

### Application

- source 하나 실패 시 last-valid 보존
- stale window 초과 시 BUY 금지
- batch 일부 실패가 전체 성공으로 기록되지 않음
- single-flight, rejected execution, timeout
- current projection과 legacy/reference 분리

### Adapter

- SEC/Yahoo/FRED 실제 shape와 bounded malformed fixture
- body size/row count/date/unit/corporate action
- SQL transaction과 check/unique constraint
- REST 필드와 methodology/freshness/version 노출
- secret redaction

### Integration

- disposable PostgreSQL 18에 V1~V22 적용
- multi-instance advisory lock
- concurrent PATCH field-loss 방지
- outbox lease/SKIP LOCKED/retry
- MinIO pointer/checksum 경로

### API/UI

- 45개 public route 소유권
- production smoke 43 checks
- `/research/sectors`, `/research/companies`, `/company/NVDA`, `/research/crypto` route smoke
- `audit-company-selection-e2e.py`: 277개 catalog/DB 합집합, V5 현재 점수·바닥 bundle·buy 정렬·순환 sector key
- stale/disabled/error boundary와 클릭 가능한 공통 컴포넌트

## 4. 장애 수정 완료 기준

같은 장애가 다시 발생하지 않으려면 최소 다음 세 가지가 필요하다.

1. 원인 수정
2. 재현 테스트 또는 DB constraint
3. 운영 탐지 fingerprint/metric/query

금융 데이터 오류는 가능하면 네 번째로 기존 오염 데이터 audit/repair 증거까지 남긴다.

예: 미조정 split이 바닥으로 계산된 경우

- 가격 품질 policy에서 split형 불연속 차단
- unit test로 2:1/10:1 discontinuity 재현
- current price signal을 unavailable로 저장
- integrity/audit에서 incomplete/invalid signal 0 확인

## 5. 변경 유형별 필수 범위

| 변경 | 필수 검증 |
|---|---|
| 점수/임계값 | domain 경계값 + golden + walk-forward + ADR/PDR |
| source parser | 정상/결측/형식변경/크기초과 + last-valid |
| DB schema | Flyway + PostgreSQL integration + rollback/restore 영향 |
| scheduler | non-overlap + advisory lock + partial failure + timing docs |
| notification | candidate policy + fingerprint + outbox state + 실제 message rendering |
| API DTO | route coverage + smoke + frontend build |
| UI interaction | keyboard/pointer/mobile target + loading/error + browser 실측 |
| cache/성능 | stale semantics + single-flight + p50/p95 + 정보량 보존 |
| 문서 | 링크/버전/schedule/ADR registry 자동 검증 |

섹터 거시 composite처럼 과거 당시 가용 component가 저장되지 않은 산식은 현재 revision/flow를 과거에
붙여 만든 pseudo walk-forward로 출시를 승인하지 않는다. 먼저 label/alias invariance와 결측 gate를
검증하고, immutable point-in-time ledger 이후 forward outcome만 OOS 성능으로 승격한다.

## 6. 배포 전 차단 조건

- 테스트 실패 또는 skip 증가 원인 불명
- 문서 contract 불일치
- legacy Node bridge/mount 재등장
- domain/application infrastructure import
- Flyway 미적용 또는 현재 DB와 checksum 불일치
- 실제 PostgreSQL multi-instance 테스트 실패
- expected universe/calculation version 불일치
- current score/action integrity 위반
- API smoke 또는 frontend health 실패
- rollback image/compose 확보 실패

## 7. 배포 후 검증

- container health/restart/OOM
- 실제 running image ID
- Flyway V1~V22
- company 277/current version 6
- total-return series/history/latest alignment
- collection SUCCESS consistency
- outbox pending/retry/dead/stuck
- MinIO dangling pointer
- Prometheus target, Loki RUM, Jaeger trace
- 43 API smoke와 frontend routes
- 최근 ERROR/FATAL/Exception

## 8. Flaky·known noise

테스트나 관측 noise를 무조건 무시 목록에 넣지 않는다. 원인을 확인한 뒤 정확한 source/logger/message만
bounded suppression한다. 현재 PDFBox의 unmapped glyph와 Loki query cancellation처럼 알려진 noise도 raw
증거는 보존하고 actionable count에서만 분리한다.
