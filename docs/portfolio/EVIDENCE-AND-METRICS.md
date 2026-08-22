# MacroSquare 포트폴리오 근거·측정 기준

이 문서는 공개용 포트폴리오의 수치와 기술 주장을 추적하기 위한 내부 근거표다. 채용 문서에 모든 경로를 노출하기보다, 여기에서 **정적 코드 근거**, **테스트 산출물**, **세션 운영 결과**, **설계상 추론**을 구분한다.

## 1. 기준 스냅샷

| 항목 | 기준 |
|---|---|
| Codex 세션 | `019fe11e-396d-7373-91eb-d21a348c69cd` |
| 세션 제목 | 프로젝트 구조 파악 (복구) |
| 세션 상태 | idle / 최근 작업 완료 |
| 소스 기준 | 최신 구현 브랜치 |
| 포트폴리오 위치 | 동일 브랜치의 저장소 루트와 `docs/portfolio/` |
| 측정일 | 2026-08-22 KST |

### Git 통합 상태

이 포트폴리오는 Java/Spring 전환을 포함한 최신 구현 소스와 동일 브랜치에 통합했다. 공개 전에는 다음 항목을 다시 확인한다.

- 구현 브랜치가 공개 저장소의 `main`에 병합됐는지 확인
- 코드·테스트·운영 수치를 게시 시점 기준으로 재산출
- 저장소 공개 범위에 맞춰 내부 운영 경로와 세션 메타데이터를 정리

`main` 병합 전에는 “기본 브랜치에 최신 구현이 반영됐다”고 표현하지 않는다.

## 2. 수치의 증거 등급

| 등급 | 의미 | 예 |
|---|---|---|
| A — 정적 재산출 | 코드·report·파일을 로컬에서 다시 계산 | Java 파일/LOC, Surefire tests, migration 수 |
| B — 저장소 계약 | CURRENT 문서와 테스트가 함께 고정 | 공개 route 45, smoke 43, company 277 |
| C — 세션 운영 실측 | 해당 세션이 홈서버에서 측정·검증한 완료 결과 | CPU 감소, restart 0, disk 회수 |
| D — 설계 설명 | 코드와 문서에서 합리적으로 설명 가능한 의도 | 2PC 대신 pointer, fail-closed 선택 이유 |
| E — 사용자 확인 필요 | 저장소로 확인 불가능한 개인 경력 정보 | 역할 비율, 팀 규모, 실제 기간, 사용자 수 |

공개 포트폴리오에는 A–D를 사용할 수 있지만, C는 “세션 완료 시점 실측”이라고 표현한다. E는 사용자가 직접 확정해야 한다.

## 3. 정적 코드 규모

`target`, `node_modules`, `.next`, data/tmp artifact를 제외하고 세션 작업 트리의 `server-spring/*/src`를 집계했다.

### Java 계층별 규모

| 모듈 | main 파일 | main LOC | test 파일 | test LOC | test methods |
|---|---:|---:|---:|---:|---:|
| Domain | 240 | 21,290 | 54 | 6,377 | 265 |
| Application | 289 | 18,679 | 47 | 7,647 | 162 |
| Adapters | 195 | 28,986 | 87 | 11,038 | 280 |
| Bootstrap | 58 | 5,015 | 16 | 765 | 26 |
| Architecture tests | 0 | 0 | 1 | 126 | 6 |
| **합계** | **782** | **73,970** | **205** | **25,953** | **739** |

LOC는 품질 지표가 아니라 범위 설명용이다. 생성 코드·빈 줄·주석을 별도로 제거하지 않은 물리 줄 수다.

### 주요 컨텍스트별 main Java 파일 수

| Context | Domain | Application | Adapters | 해석 |
|---|---:|---:|---:|---|
| Company | 90 | 87 | 55 | 기업 재무·가격·바닥·반전·SEC/Yahoo 경로 |
| Research | 68 | 68 | 33 | 섹터·테마·peer·narrative·bottleneck |
| Market | 19 | 41 | 31 | 시장 수집·파생·regime·signal·allocation |
| Execution | 18 | 18 | 9 | 계획·tranche·trade log·구매력 |
| Institutional | 12 | 12 | 13 | 13F·identity·holding 변화 |
| Policy | 12 | 10 | 10 | 공식 원문·tone·calibration |
| Notification | 6 | 18 | 7 | 후보 policy·state·outbox·Telegram |
| Disclosure | 6 | 9 | 5 | OpenDART |
| Integrity | 5 | 6 | 3 | 반복 장애·무결성 상태 |
| Crypto | — | 10 | 6 | persisted research + live overlay |
| Technical | 4 | — | — | 공통 point-in-time MACD kernel |

`Crypto` domain 파일이 별도 root로 집계되지 않은 이유와 shared/value type 배치는 최종 merge 후 다시 확인한다. 포트폴리오에는 “context가 존재한다”는 사실만 사용하고 파일 수를 홍보 수치로 쓰지 않는다.

### Frontend 표면

| 항목 | 정적 수치 |
|---|---:|
| Next App Router page | 12 |
| Next API proxy route 파일 | 33 |
| TypeScript/TSX source | 현재 추적 기준 94개 파일, 약 15.2K physical LOC |

TypeScript/TSX LOC에는 test·구형 source가 섞일 수 있으므로 공개 본문의 핵심 지표로 사용하지 않았다.

## 4. 테스트 근거

### Surefire report 재집계

| 모듈 | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| Domain | 265 | 0 | 0 | 0 |
| Application | 162 | 0 | 0 | 0 |
| Adapters | 280 | 0 | 0 | 18 |
| Bootstrap | 26 | 0 | 0 | 0 |
| Architecture | 6 | 0 | 0 | 0 |
| **합계** | **739** | **0** | **0** | **18** |

정확한 공개 표현은 다음과 같다.

> “Surefire report 기준 739 tests, failure 0, error 0. 표준 Maven 실행에서 외부 PostgreSQL 통합 18개는 skip하며 별도 disposable PostgreSQL 18 스크립트로 실행한다.”

“739개 전부 실행 통과”라고 단순 표현하면 18 skip을 숨기므로 사용하지 않는다. 과거 세션 표현보다 현재 로컬 report 재집계를 우선한다.

### 품질 gate 계약

- 공개 `/api` route: 45
- production smoke: 43
- ArchUnit rules: 6
- Flyway: V1–V22, 22개 migration
- frontend current 문서 기준 test: 9
- company E2E: catalog/DB/API 277개 정합
- 실제 PostgreSQL 18 multi-instance: advisory lock, concurrent PATCH, outbox lease 경로

## 5. 시스템 규모·운영 계약

| 지표 | 값 | 등급 | 근거 |
|---|---:|---|---|
| 기업 universe | 277 | B | `server-spring/README.md`, data contract, E2E verifier |
| 표준 섹터 | 11 | B | research contract, validation ledger run당 11 items |
| 전략 테마 | 6 | B | research catalog docs·세션 검증 |
| 공개 API | 45 | B | `docs/development/API-SURFACE.md`, route coverage test |
| production smoke | 43 | B | API surface, migration smoke tool |
| 13F manager | 20 | B | Spring README, institutional config·startup freshness policy |
| 시장 총수익률 series | 17 | C | 배포 검증 세션 결과 |
| Flyway | V1–V22 | A/B | 실제 migration 파일 22개 |
| ADR | 18 | A | `server-spring/docs/ADR-001..018` |
| PDR | 14 | A | `docs/PDR-001..014` |

## 6. 운영 성과 수치

다음은 세션 완료 답변과 운영 검증의 실측 스냅샷이다. 지속 SLA가 아니다.

| 항목 | 이전 | 이후 | 설명 |
|---|---:|---:|---|
| 기업 갱신 정상 경로 평균 CPU | 10.55% | 2.82% | 목록용 5년 walk-forward 제거, evidence 재사용, 동시성 조정 |
| CPU 상대 감소 | — | 약 73% | `(10.55-2.82)/10.55` |
| 홈서버 디스크 사용 | 370GB | 60GB | 미사용 volume/cache/image 정리 |
| 회수 공간 | — | 약 310GB | 운영 volume과 백업 보존 |
| company summary concurrency | 8 | 4 | 4코어 홈서버 순간 발열 제한 |
| MinIO limit | 512MiB | 768MiB | headroom 보강 |
| Alloy limit | 256MiB | 384MiB | headroom 보강 |
| 13F startup 재처리 | 약 90K holdings | recent durable evidence 시 skip | 20 manager 모두 최신일 때만 |
| backup backend pause | 약 10분 문제 사례 | 실측 약 2초 | relational dump/pointer capture에만 pause |
| backup checksum | — | 20,048 files | 세션 실측 |

그 외 세션 완료 시점 결과:

- API smoke 43/43
- company DB/API 277/277
- 13F 20 manager durable evidence 확인
- container restart 0
- 새로운 ERROR/WARN 없음
- dangling object pointer 0
- 과거 cutover 검증의 API p50 약 4.96ms — 현재 지속 성능 SLO로 사용하지 않음

## 7. 금융 검증 수치

세션의 섹터 순환 2차 감사에서 보고된 독립 재계산 결과:

| horizon | Top1 hit rate | 평균 SPY 초과수익 | 비고 |
|---|---:|---:|---|
| 1개월 | 40.96% | — | 21 session |
| 3개월 | 53.09% | +2.00%p | 63 session |
| 6개월 | 61.54% | +3.93%p | 126 session |

- 6개월 중첩 보정 95% 구간: 47.12–75.96%
- 독립 Python과 운영 상대강도 percentile 오차: 5e-7 미만
- 해석 제한: 과거 재현 가능한 상대 모멘텀 layer 결과이며 전체 거시·revision·flow composite의 확정 성과가 아님
- 제품 해석: 자동 매수 확률이 아니라 관찰 우선순위

## 8. 핵심 코드 근거 지도

아래 경로는 세션 작업 트리 기준이다.

### 경계·아키텍처

| 주장 | 코드/문서 |
|---|---|
| domain/framework 독립 | `server-spring/architecture-tests/.../CleanArchitectureTest.java` |
| application/transport 독립 | 같은 ArchUnit test의 `applicationMustRemainFrameworkAndTransportFree` |
| scheduler cluster lock 강제 | 같은 test의 `scheduledInboundAdaptersMustUseTheClusterExclusiveExecutionPort` |
| bounded-context inner-layer 분리 | 같은 test의 `boundedContextsMustCommunicateThroughPortsOrOuterAdapters` |
| 설계 원칙 | `server-spring/ARCHITECTURE.md` |
| 전체 런타임 | `docs/development/SYSTEM-ARCHITECTURE.md` |

### 동시성·영속성

| 주장 | 코드/문서 |
|---|---|
| advisory lock adapter | `server-spring/adapters/.../PostgresAdvisoryTaskExecution.java` |
| 실행권 port | `server-spring/application/.../ExclusiveTaskExecution.java` |
| investment PATCH 원자성 | `ManageInvestmentExecutionService.java`, `JdbcInvestmentExecutionAdapter.java` |
| object storage | `MinioObjectStorageAdapter.java`, `JdbcObjectArtifactCatalog.java` |
| storage 결정 | `server-spring/docs/ADR-001-storage-and-database-boundaries.md` |
| 스케줄 계약 | `docs/development/SCHEDULERS-CONCURRENCY-IDEMPOTENCY.md` |

### 알림

| 주장 | 코드/문서 |
|---|---|
| 자격 policy | `server-spring/domain/.../notification/domain/InvestmentCandidatePolicy.java` |
| 상태+outbox orchestration | `NotificationOrchestrationService.java` |
| JDBC state/outbox | `JdbcNotificationStateRepository.java` |
| outbox schema | `V2__create_notification_outbox.sql` |
| 반전 evidence | `V21__persist_notification_reversal_evidence.sql` |
| MACD evidence | `V22__persist_macd_notification_evidence.sql` |
| 제품 임계값 결정 | `docs/PDR-014-relax-company-telegram-bottom-threshold.md` |

### 금융 policy

| 주장 | 코드/문서 |
|---|---|
| 바닥/반전 분리 | `ReversalConfirmationPolicy.java` |
| MACD point-in-time kernel | `MacdSignalPolicy.java` |
| 다이버전스 우측 pivot 확인 | 같은 policy의 `latestDivergence`/`pivots` |
| 금융 모델 | `docs/finance/FINANCIAL-DECISION-MODEL.md` |
| 기업 점수 | `docs/finance/COMPANY-SCORECARD.md` |
| 백테스트 거버넌스 | `docs/finance/BACKTEST-AND-MODEL-GOVERNANCE.md` |
| MACD 제품/기술 결정 | ADR-017, ADR-018, PDR-012, PDR-013 |

### 운영·배포

| 주장 | 코드/문서 |
|---|---|
| scope-aware deploy | `scripts/classify-deploy-scope.py`, `scripts/deploy-home.sh` |
| 운영 검증 | `scripts/verify-home.sh` |
| PostgreSQL integration | `scripts/test-postgres-multi-instance.sh` |
| backup/restore | `scripts/backup-home-storage.sh`, `scripts/restore-drill-home-storage.sh` |
| recurrence audit | `scripts/monitor-home-recurrence.py`, `scripts/audit-home-observability.py` |
| rollback 계약 | `docs/development/DEPLOYMENT-ROLLBACK-RECOVERY.md` |

## 9. 세션 작업 흐름에서 확인한 주요 결정 연대기

| 순서 | 문제/요청 | 결과 |
|---:|---|---|
| 1 | Java/Spring 철학·DDD·OOP·FP·동시성·DB 감사 | 경계 감사, 저장 구조와 운영 위험 식별 |
| 2 | RDB + Docker S3-compatible object storage | PostgreSQL 18 + MinIO 전환, ADR-001 |
| 3 | 다중 인스턴스·동시 수정 안정화 | advisory lock, row lock, ArchUnit scheduler rule |
| 4 | 알림 소실·재시도 | transactional outbox, lease/retry/dead |
| 5 | 섹터 순환 전수 감사 | total return, point-in-time, immutable OOS ledger |
| 6 | 반복 Yahoo/기업분할 오류 | bounded retry, cache quarantine, fail-closed |
| 7 | 백업 중 10분 pause·Broken pipe | relational capture만 bounded pause, keepalive |
| 8 | 배포 50분 이상 | scope-aware test/deploy tiers |
| 9 | 홈서버 팬·CPU | 중복 walk-forward 제거, evidence 재사용, startup stagger |
| 10 | MACD·다이버전스 노출 | 공통 technical kernel, pivot confirmation, UI/Telegram |
| 11 | 기업 알림 조건 완화 | CANDIDATE + 독립 반전 ON, PDR-014 |

## 10. 공개 문서에서 피해야 할 과장

### 사용하지 말 것

- “750개 테스트가 모두 실행됐다” — 18개 skip 존재
- “exactly-once Telegram” — provider idempotency 미지원
- “AI가 수익을 예측한다” — 설명 가능한 rule/policy 기반 의사결정
- “검증된 승률 61.54%” — 상대 모멘텀 특정 layer/horizon의 제한된 결과
- “무중단 고가용성” — 단일 홈서버와 rolling replace, multi-AZ 아님
- “모든 데이터 실시간” — source별 5분~24시간 cadence와 발표 지연 존재
- “현재 Git main에 최신 구현이 모두 있다” — 구현 브랜치가 `main`에 병합된 뒤에만 사용

### 권장 표현

- “세션 완료 시점 운영 검증에서 restart 0”
- “공급자 실패 시 last-valid를 감사용으로 보존하되 현재 BUY는 fail-closed”
- “transactional outbox 기반 at-least-once 전달”
- “상대 모멘텀 layer의 point-in-time walk-forward 결과”
- “홈서버 자원 제약을 고려한 production-like 운영”

## 11. 사용자 확인이 필요한 이력서 정보

다음은 코드에서 알 수 없으므로 최종 포트폴리오에서 직접 확정해야 한다.

- 프로젝트가 완전 개인 프로젝트인지, 협업자가 있는지
- 실제 기여율과 본인이 직접 수행한 범위
- 운영 시작일과 실제 사용자 수
- 외부 공개 가능한 URL·GitHub repository
- 비용, 월간 트래픽, 데이터 용량, 알림 건수
- 이 프로젝트를 통해 얻은 실제 비즈니스/사용자 성과
- 공개 가능한 스크린샷과 데이터

## 12. 수치 재산출 명령

최신 source가 현재 checkout에 통합된 뒤 다음 형태로 재산출한다.

```bash
# Java test report
find server-spring -path '*/target/surefire-reports/TEST-*.xml' -print

# Flyway 수
find server-spring/bootstrap/src/main/resources/db/migration \
  -maxdepth 1 -name 'V*.sql' | wc -l

# public route와 smoke 계약
rg -n '공개 `/api` route 계약|production smoke checks' \
  docs/development/API-SURFACE.md

# ADR/PDR 수
find server-spring/docs -maxdepth 1 -name 'ADR-*.md' | wc -l
find docs -maxdepth 1 -name 'PDR-*.md' | wc -l
```

운영 수치는 과거 세션 값을 그대로 복사하지 말고 Prometheus, PostgreSQL read-only query, container inspect, 배포 검증 결과로 기준일과 함께 다시 기록한다.
