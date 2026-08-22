# 시스템 아키텍처

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-08**
- Backend: Java 21 · Spring Boot 4.1.0 · Spring Framework 7 · Tomcat 11
- Frontend: Next.js 16.3.0 · Node.js 22 runtime image
- Data: PostgreSQL 18.4 · MinIO S3-compatible object storage

## 1. 런타임 구성

```text
Browser
  └─ Next.js :5847
       └─ Spring REST :5846
            ├─ PostgreSQL :5432  ── 정형 상태·시계열·outbox·metadata
            ├─ MinIO :9000       ── SEC/IR 원문·projection body
            ├─ External sources  ── SEC, Yahoo, FRED, Fed, DART ...
            └─ OTel Collector ── Jaeger

Prometheus ← Spring Actuator
Loki ← Alloy ← Docker JSON logs
Host cron → 1분 recurrence monitor + 일일 observability audit
```

LAN에 직접 노출되는 애플리케이션 포트는 Next.js 5847이다. Spring, PostgreSQL, MinIO, 관측 도구는
loopback 또는 compose private network에 둔다.

## 2. 클린 아키텍처

```text
bootstrap → adapters → application → domain
```

| 계층 | 책임 | 금지 |
|---|---|---|
| domain | value object, aggregate, 순수 policy, 불변식 | Spring/Jackson/JDBC/MinIO/HTTP/파일/캐시 |
| application | use case, port, orchestration, transaction 의미 | Controller DTO, SQL, 공급자 응답 타입 |
| adapters | REST, scheduler, JDBC, MinIO, 외부 API, JSON mapping | 비즈니스 임계값 복제 |
| bootstrap | bean/config/health/Flyway/runtime composition | 핵심 금융 규칙 |
| architecture-tests | ArchUnit 의존 방향 강제 | 묵시적 예외 |

Domain policy는 Clock, 네트워크, 파일을 직접 읽지 않는다. 동일 입력은 동일 출력을 만든다. 외부 원천의
불완전 응답은 adapter에서 정규화한 뒤 application port 모델로 전달한다.

## 3. Bounded Context

| Context | 소유 책임 |
|---|---|
| Market | 거시·시장 수집, 파생지표, regime, 자산 신호, allocation, 총수익률 |
| Company | SEC/Yahoo 기업 근거, 점수, guidance/mix, 바닥/반전, 최종 투자 판단 |
| Research | 표준 섹터·전략 테마, peer, bottleneck, narrative, 섹터 순환 |
| Crypto | persisted crypto research와 live market freshness overlay |
| Institutional | SEC 13F, holding 변화, CUSIP identity, consensus/divergence |
| Policy | Fed/Treasury/USTR 원문, tone, confidence calibration |
| Disclosure | OpenDART 기업·공시·연결재무 |
| Execution | 투자계획, tranche, trade log, purchasing power |
| Notification | 후보 상태, startup/market/weekly 메시지, transactional outbox |
| Integrity | 정형 증거 기반 반복 장애 탐지와 incident transition |
| Storage | MinIO artifact와 active pointer |

한 context의 domain 타입을 다른 context가 shared kernel처럼 사용하지 않는다. 필요한 결합은 scalar/value
snapshot을 application port 또는 outer anti-corruption adapter로 번역한다.

## 4. 주요 요청 경로

### 기업 상세

```text
CompanyReadController
 → QueryCompanyReadService
 → persisted company.research_summary / active projection
 → stale-while-revalidate enrichment
 → SEC/Yahoo evidence별 독립 refresh
 → CompanyInvestmentDecisionComposer
 → domain policy
 → 원자 projection 저장
 → REST DTO
```

한 외부 source가 실패하면 해당 operation을 degraded로 기록하고 마지막 정상값을 유지한다. 다만 stale
근거로 현재 BUY를 재생성하지 않도록 최종 decision은 fail-closed한다.

### 시장 snapshot

```text
source collector → market.observation + collection_status
 → CoreDerivedIndicatorPolicy
 → MacroRegimePolicy
 → CoreAssetSignalPolicy
 → CoreAllocationPolicy
 → versioned snapshot object + DB pointer
```

### 알림

```text
현재 company/crypto refresh
 → InvestmentCandidatePolicy
 → 신규 편입 및 ON→STRONG/70·75·80… 점수 구간 강화 판정
 → candidate snapshot state transition + outbox enqueue (한 DB transaction)
 → leased dispatcher
 → Telegram
 → delivered/retry/dead
```

## 5. 영속성

- PostgreSQL: 정형 상태, 시계열, transaction, unique/foreign/check constraint
- MinIO: bounded 원문과 immutable/versioned projection body
- PostgreSQL pointer: 현재 공개할 정확한 object version·ETag·SHA-256·size
- Flyway: 유일한 DDL 소유자, 현재 V1~V22

Company analyst snapshot은 개별 기업의 날짜가 있는 forward-EPS revision을 소유한다. Research의 섹터
breadth는 read-only application port/JDBC ACL로 이 데이터를 번역한다. Research domain이 Company SQL,
JDBC 또는 provider DTO를 직접 참조하지 않는다.

Research의 live sector composite는 Market snapshot 생성 경로에서만 capture use case로 전달된다. Market
total-return 이력은 `LoadSectorRotationPriceWindowPort` ACL을 통해 완료 공통 거래일과 21/63/126-session
outcome으로 번역되며, Research domain은 Market repository 타입을 참조하지 않는다. V19/V20 run+11 items는 한
transaction의 append-only aggregate이고 REST GET은 이 원장을 쓰지 않는다.

MinIO body를 먼저 저장하고 PostgreSQL pointer commit 후 공개한다. pointer 이전 실패는 orphan일 뿐 기존
last-valid를 오염시키지 않는다. DB와 MinIO 사이에 XA/2PC를 도입하지 않는다.

## 6. 동시성

- I/O 작업은 Java 21 virtual thread를 사용할 수 있으나 provider별 semaphore로 제한
- `parallelStream`과 global common pool 금지
- ticker/source refresh는 single-flight
- scheduler는 JVM non-overlap + PostgreSQL session advisory lock
- advisory lock connection은 Hikari와 분리된 unpooled source, 공정 semaphore 최대 4
- command aggregate는 transaction advisory lock + row lock + version
- outbox는 `FOR UPDATE SKIP LOCKED` lease

DB coordination 실패를 로컬 실행으로 우회하지 않는다. 중복 부수효과보다 일시적 미실행이 안전하다.

## 7. 실패 의미

| 상황 | 동작 |
|---|---|
| 명령 핵심 결과 무효 | 예외 전파, transaction rollback |
| 제품 계약상 last-valid 허용 | 기존값 유지 + degraded log/metric |
| 현재 투자 근거 불완전 | HOLD/숨김, BUY 금지 |
| 손상된 mutable state | 빈 상태로 초기화하지 않고 fail-closed |
| scheduler 예외 | 로그 후 재전파, 실패 metric 보존 |
| optional integration 미설정 | DISABLED/MISSING, 가짜 값 금지 |

## 8. 보안·운영 경계

- app은 MinIO root가 아닌 Get/List/Put 전용 계정, delete 권한 없음
- 컨테이너 `no-new-privileges`, capability drop, client non-root
- 관측 포트 loopback only
- Alloy는 Docker socket 없이 read-only log directory만 사용
- 로그/트레이스에 Telegram token, query, cookie, session을 남기지 않음
- DB statement 30초, lock 5초, idle transaction 60초 timeout
- Hikari max 8, leak detection 60초

상세 원칙은 [`server-spring/ARCHITECTURE.md`](../../server-spring/ARCHITECTURE.md)와
[ADR-001](../../server-spring/docs/ADR-001-storage-and-database-boundaries.md)을 따른다.
