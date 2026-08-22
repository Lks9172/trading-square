# MacroSquare 면접 발표·질문 대응 가이드

## 1. 30초 소개

> MacroSquare는 거시경제, 섹터, 기업 펀더멘털, 가격·수급 데이터를 결합해 진입 시점과 비중을 설명하는 투자 리서치 플랫폼입니다. Java 21과 Spring Boot 기반 DDD/Clean Architecture로 백엔드를 전환했고, PostgreSQL과 MinIO의 저장 경계, transactional outbox, advisory lock, point-in-time 금융 모델을 적용했습니다. Next.js UI와 Telegram 알림, Prometheus·Loki·Jaeger 관측, 백업·자동 롤백까지 홈서버에서 직접 운영하며 데이터 오류가 거짓 매수 신호로 번지지 않도록 fail-closed를 핵심 원칙으로 삼았습니다.

## 2. 1분 소개

> 시작은 여러 거시 지표를 한 화면에서 보고 싶다는 문제였습니다. 기능이 늘면서 실제 어려움은 점수 계산보다 데이터의 기준일, 공급자 실패, 기업분할, 스케줄 중복, 알림 소실 같은 운영 문제라는 것을 알게 됐습니다. 그래서 TypeScript 백엔드를 단순 변환하지 않고 Java 21/Spring Boot 멀티모듈로 재설계했습니다. 도메인, 애플리케이션, 어댑터, 부트스트랩 의존 방향을 ArchUnit으로 강제하고, 정형 상태는 PostgreSQL, 원문과 immutable projection은 MinIO에 저장했습니다. Scheduler는 PostgreSQL advisory lock, 투자 계획은 row lock, Telegram은 transactional outbox로 보호했습니다. 금융 모델은 point-in-time과 fail-closed를 적용해 stale·부분 데이터가 BUY로 승격되지 않게 했습니다. 세션 기준 277개 기업, 45개 공개 API, 750개 Surefire test, 43개 운영 smoke를 관리하고 있으며, 중복 계산 제거로 평균 CPU를 약 73% 줄였습니다.

## 3. 3분 발표 구조

### 0:00–0:30 — 문제

- 투자 판단 데이터가 출처·주기·의미별로 흩어짐
- 현재값, 후행값, proxy, heuristic이 섞이면 숫자를 과신
- 공급자/캐시/동시성 장애가 잘못된 신호로 연결될 수 있음

### 0:30–1:20 — 시스템

- Market → Asset → Sector → Company → Bottom/Reversal → Timing → Execution의 다층 의사결정
- Java 21/Spring Boot DDD/Clean Architecture
- Next.js UI, PostgreSQL, MinIO, Telegram
- 공식·공개 원천을 bounded adapter로 수집하고 기준일·신선도·calculation version 보존

### 1:20–2:10 — 어려운 기술 문제

- PostgreSQL–MinIO: immutable body + transactional active pointer
- scheduler: JVM guard + PostgreSQL advisory lock + 별도 coordination connection
- command: transaction advisory lock + `SELECT FOR UPDATE`
- 알림: candidate state + outbox 동일 transaction, lease/retry/dead
- 금융: split, stale, look-ahead, revision, survivorship fail-closed

### 2:10–2:40 — 검증

- Surefire 739 tests, failure/error 0, 외부 통합 18 skip은 별도 PG18에서 실행
- ArchUnit 6 rules, public route 45, smoke 43
- company 277/277, dangling pointer 0, restart 0 세션 검증
- CPU 10.55%→2.82%, 약 73% 감소

### 2:40–3:00 — 배운 점

> 금융 시스템의 품질은 좋은 점수보다 잘못된 데이터를 신호로 승격하지 않는 데서 결정됐습니다. 그래서 모델 정확도, 소프트웨어 경계, 운영 관측을 하나의 의사결정 시스템으로 설계했습니다.

## 4. 이력서 bullet — 한국어

상황에 맞게 3–5개를 선택한다.

- Java 21/Spring Boot 멀티모듈로 투자 리서치 백엔드를 재설계하고 `domain → application → adapters → bootstrap` 경계를 ArchUnit 6개 규칙으로 자동 강제
- 277개 기업·11개 섹터·20개 13F 운용사를 처리하는 수집–정규화–점수–알림 파이프라인을 PostgreSQL 18·MinIO 기반으로 운영
- PostgreSQL transaction advisory lock, row lock, `FOR UPDATE SKIP LOCKED`를 적용해 스케줄 중복, partial PATCH 유실, 다중 worker 알림 경쟁을 방지
- 후보 상태와 outbound message를 동일 transaction에 저장하는 transactional outbox를 구현하고 retry/backoff/dead-letter/lease recovery 제공
- immutable MinIO object와 PostgreSQL active pointer·SHA-256 검증으로 2PC 없이 last-valid projection을 보호하고 dangling pointer 무결성 감사 구축
- 금융 시계열의 기준일·공시 가능일·revision을 보존하는 point-in-time 계약과 split/stale/partial data fail-closed 정책으로 거짓 매수 신호 방지
- 기업 목록 경로의 중복 5년 walk-forward와 재기동 13F 재처리를 제거해 평균 CPU 약 73% 절감하면서 277개 데이터·상세 정보 유지
- 변경 위험 기반 fast/standard/release 테스트 tier와 service-scoped 배포·자동 rollback을 구축해 고위험 DB 변경의 gate는 유지하고 불필요한 전체 재배포 제거
- Spring Actuator, Micrometer, OpenTelemetry, Prometheus, Loki, Jaeger를 연결하고 1분 반복 장애 감시·일일 운영 감사를 자동화

## 5. Resume bullets — English

- Re-architected a financial research backend into Java 21/Spring Boot multi-modules and enforced Clean Architecture and bounded-context isolation with six ArchUnit rules.
- Operated a data-to-decision pipeline covering 277 companies, 11 standard sectors, and 20 SEC 13F managers using PostgreSQL 18, MinIO, and Next.js.
- Prevented duplicate scheduled side effects and lost concurrent updates using PostgreSQL advisory locks, row locks, isolated coordination connections, and versioned aggregate writes.
- Implemented a transactional outbox with `SKIP LOCKED` leasing, exponential retry, dead-letter isolation, and lease recovery for at-least-once Telegram delivery.
- Protected last-valid projections without distributed transactions by combining immutable versioned MinIO objects with transactional PostgreSQL active pointers and SHA-256 verification.
- Designed point-in-time financial data contracts and fail-closed guards for stale evidence, corporate actions, partial batches, revisions, and look-ahead bias.
- Reduced average backend CPU from approximately 10.55% to 2.82% by removing redundant walk-forward computation, reusing current evidence, and staggering provider-heavy startup work.

## 6. 예상 기술 질문과 답변

### Q1. 왜 Java/Spring으로 전환했나요?

**핵심 답변:** 언어 선호가 아니라 커진 시스템의 업무 경계, transaction, lifecycle, 운영 관측을 명시하기 위해서였다.

- Spring의 configuration binding, Actuator, transaction, scheduler lifecycle 활용
- Java type/record와 Maven module로 계약을 컴파일 수준에 고정
- ArchUnit으로 경계를 실행 가능한 규칙으로 전환
- 기존 TypeScript 구현의 API parity는 유지하되 runtime bridge는 제거

**피해야 할 답:** “Java가 더 빠르기 때문”만 말하지 않는다. 성능은 일부 결과이고 핵심은 설계·운영 책임이다.

### Q2. 왜 JPA 대신 JDBC를 썼나요?

- 시계열 upsert, advisory lock, `SKIP LOCKED`, 명시적 row lock, append-only ledger 등 SQL 의미가 중요
- lazy loading과 implicit flush보다 transaction과 query 비용을 드러내는 편이 적합
- ORM model을 domain으로 누출하지 않기 위함
- 단점은 row mapping과 SQL 유지보수 증가이며 adapter 테스트와 migration으로 보완

### Q3. PostgreSQL과 MinIO를 왜 분리했나요?

- 정형 상태·constraint·transaction·query는 PostgreSQL
- 큰 원문·immutable/versioned body는 MinIO
- DB backup 비대화와 파일 기반 정형 상태의 동시성 문제를 동시에 피함
- PostgreSQL active pointer가 정확히 어떤 object version을 공개할지 소유

### Q4. 2PC 없이 어떻게 일관성을 보장했나요?

- body를 먼저 immutable version으로 저장
- checksum/metadata를 기록
- pointer를 마지막 DB transaction으로 commit
- pointer commit 전 object는 비노출 orphan일 뿐 last-valid를 깨지 않음
- reader가 exact version과 checksum을 재검증
- orphan GC와 dangling pointer incident를 분리

### Q5. Scheduler lock을 왜 Hikari pool에서 분리했나요?

Session advisory lock은 외부 수집이 끝날 때까지 connection을 유지한다. SEC/Yahoo가 느리면 장시간 lock connection이 Hikari를 점유해 사용자 API transaction이 고갈될 수 있다. 그래서 unpooled coordination source와 fair semaphore 4개를 별도로 두었다.

### Q6. Virtual thread를 쓰면서 semaphore가 왜 필요한가요?

Virtual thread는 대기 비용을 줄이지만 외부 provider의 허용 QPS, 홈서버 CPU, DB connection 수를 늘려주지 않는다. 무제한 fan-out은 rate limit과 memory/connection 압력을 키운다. 따라서 I/O 실행 모델과 외부 자원 한도를 분리했다.

### Q7. Outbox가 exactly-once인가요?

아니다. DB 내부에서 후보 상태와 outbox enqueue는 원자적이고 dispatcher 간 중복 lease를 막지만, Telegram이 idempotency key를 지원하지 않아 provider 수락 후 ack commit 전 crash에는 중복 가능성이 있다. 따라서 at-least-once이며, 메시지 소실보다 드문 중복을 선택했다.

### Q8. last-valid와 fail-closed는 언제 구분하나요?

- UI 연속성·감사에 이전 정상값을 보여도 되는 경우: last-valid + degraded
- 현재 BUY/후보 자격에 필요한 핵심 evidence가 stale/불완전한 경우: fail-closed
- 즉, “보여줄 수 있음”과 “현재 의사결정에 사용할 수 있음”을 별도 flag로 관리

### Q9. 금융 모델의 look-ahead bias를 어떻게 막았나요?

- 신호일 당시 공개 가능한 값만 사용
- 발표/공시 가능일과 단순 관측일 구분
- 날짜 교집합 또는 latest-known-before 방식
- 다이버전스는 우측 pivot이 지난 confirmed date부터 활성
- 현재 revision/constituent를 과거에 붙이지 않음
- 전체 composite는 immutable forward ledger 이후 outcome만 OOS로 인정

### Q10. 바닥과 반전을 왜 분리했나요?

급락 강도는 투매의 증거이고, 반전은 가격 구조와 수급 회복의 증거다. 두 점수를 섞으면 많이 떨어진 종목이 강한 반전처럼 보일 수 있다. 그래서 bottom state와 independent OBV/VWAP·price structure confirmation을 별도 policy로 만들었다.

### Q11. 임계값 70, 62, 68 등은 수익 확률인가요?

아니다. 운영상 후보를 제한하고 설명하기 위한 정책 threshold다. PDR에서 자동 주문이 아닌 관찰 후보라고 명시했고, 장기 OOS가 쌓이면 재검토한다. score heuristic과 walk-forward 통계를 UI에서 구분한다.

### Q12. 테스트가 많으면 품질이 보장되나요?

아니다. 테스트 수는 급감 회귀를 보는 기준선일 뿐이다. 더 중요한 것은:

- domain 경계값과 미래정보 미사용
- DB constraint와 multi-instance integration
- route ownership과 production smoke
- 장애 재현 + 운영 fingerprint
- 배포 후 실제 data drift·outbox·pointer 검사

또한 739 Surefire tests 중 외부 integration 18 skip을 숨기지 않고 별도 실행한다고 설명한다.

### Q13. 왜 공급자 실패에 빈 200 응답을 주지 않나요?

빈 후보가 “조건을 통과한 후보가 없음”인지 “근거를 계산할 수 없음”인지 구분해야 한다. 현재 섹터 근거가 불충분하면 HTTP 503 unavailable로 처리하고, 정상적으로 조건을 만족한 후보가 0개인 경우만 빈 목록을 반환한다.

### Q14. 배포 속도와 안전을 어떻게 함께 확보했나요?

테스트를 삭제하지 않고 변경을 `docs/scripts/server/client/release/full`로 분류했다. 문서 변경은 앱을 재시작하지 않지만 Flyway/persistence 변경은 자동으로 release tier, backup, 실제 PG18 integration을 요구한다. 모든 scope에서 readiness, running image, production smoke는 유지한다.

### Q15. 단일 홈서버 운영의 한계는 무엇인가요?

- host failure와 네트워크 단절에 대한 multi-AZ HA 없음
- 공개 무료 source의 SLA 없음
- rolling replace는 가능하지만 완전한 무중단 보장 아님
- restore drill과 외부 backup으로 복구 가능성을 높였지만 RTO/RPO를 기업급으로 주장하지 않음

## 7. STAR 답변 1 — 알림 소실

### Situation

후보 상태를 저장한 뒤 Telegram 전송이 실패하면 다음 스캔에서 이미 보낸 후보로 판단해 알림이 사라질 수 있었다.

### Task

후보 상태와 발송 의도를 원자적으로 보존하고, 실패를 재시도 가능하게 만들어야 했다.

### Action

- candidate state와 outbox enqueue를 동일 DB transaction으로 변경
- `SKIP LOCKED` lease, retry/backoff, dead-letter, lease timeout 회수
- startup/시장/후보/주간 메시지를 공통 경로로 통합
- PENDING/RETRY/DEAD/stuck 운영 감시 추가

### Result

전송 실패가 후보 상태에서 사라지지 않게 됐고, 다중 worker도 같은 메시지를 동시에 lease하지 않았다. provider 제약 때문에 exactly-once가 아니라 at-least-once임을 명시했다.

## 8. STAR 답변 2 — 팬 소음과 CPU

### Situation

홈서버 팬이 자주 돌았지만 조사 시점의 CPU는 대부분 idle이었다.

### Task

순간 상태가 아니라 반복 부하의 원인을 찾아 정보량과 수집 주기를 유지하면서 줄여야 했다.

### Action

- Prometheus의 시간축 CPU와 scheduler 로그를 대조
- 277개 company summary의 불필요한 5년 walk-forward 확인
- candidate scan의 최신 evidence 재사용
- 13F startup durable evidence shortcut, startup stagger, concurrency 8→4

### Result

평균 CPU가 약 10.55%에서 2.82%로 감소했고, 기업 277/277과 상세 검증 정보, 기존 수집 주기를 유지했다.

## 9. STAR 답변 3 — 기업분할 가격 오류

### Situation

Yahoo 가격에 약 2배 basis 불연속이 발생해 바닥 신호를 왜곡할 위험이 있었다.

### Task

거짓 매수 신호와 cache 오염을 막되 정상 split은 처리해야 했다.

### Action

- corporate-action형 ratio 탐지
- split event 분자/분모와 전후 가격 비율 교차검증
- 이미 조정된 이력의 이중 조정 방지
- 불확실한 payload cache 격리, signal fail-closed, 다음 수집 우선 재시도
- 재현 테스트와 반복 WARN reminder 제한

### Result

해당 종목이 `null/HOLD`로 격리돼 거짓 후보가 발생하지 않았고, 정상 수집 후 자동 복구됐다.

## 10. 면접에서 보여줄 다이어그램 순서

1. [전체 런타임](ARCHITECTURE-DIAGRAMS.md#1-프로덕션-런타임)
2. [Clean Architecture](ARCHITECTURE-DIAGRAMS.md#2-clean-architecture와-bounded-context)
3. [PostgreSQL–MinIO 일관성](ARCHITECTURE-DIAGRAMS.md#5-postgresqlminio-일관성)
4. [Transactional outbox](ARCHITECTURE-DIAGRAMS.md#6-알림-transactional-outbox)
5. [Scheduler 동시성](ARCHITECTURE-DIAGRAMS.md#7-scheduler동시성rate-limit)
6. [배포·롤백](ARCHITECTURE-DIAGRAMS.md#9-배포자동-롤백)

## 11. 마무리 문장

> 이 프로젝트를 통해 금융 계산 자체보다 데이터가 언제·어떤 상태로 들어왔고 실패 시 어떤 행동을 해야 하는지 정의하는 것이 더 어렵고 중요하다는 것을 배웠습니다. 그래서 도메인 모델, 데이터 계약, 동시성, 배포와 관측을 분리하지 않고 하나의 신뢰 가능한 의사결정 흐름으로 설계했습니다.
