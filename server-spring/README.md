# MacroSquare Spring Backend

MacroSquare의 현재 운영 백엔드입니다. Java 21 LTS와 Spring Boot 4.1.0을 사용하며, 기존 Express/TypeScript 서버의 공개 API·수집·계산·영속화·Telegram 책임을 인계받았습니다.

## 현재 상태

- 운영 포트: `5846`
- 런타임: Temurin Java 21 LTS
- 프레임워크: Spring Boot 4.1.0, Spring Framework 7, Tomcat 11
- 상태: **2026-07-21 production cutover 완료**
- Node HTTP bridge/subprocess: **없음**
- 기존 Node 이미지/컨테이너: **폐기 완료**
- 최종 증빙: [`migration/CUTOVER-2026-07-21.md`](migration/CUTOVER-2026-07-21.md)

## DDD / Clean Architecture

상세한 객체지향·함수형·동시성·실패 의미와 DB 경계는 [`ARCHITECTURE.md`](ARCHITECTURE.md),
[`docs/ADR-001-storage-and-database-boundaries.md`](docs/ADR-001-storage-and-database-boundaries.md)를 기준으로 합니다.
통합 개발·운영 기준은 [`../docs/development/README.md`](../docs/development/README.md), 데이터 계약은
[`../docs/development/DATA-CONTRACTS-AND-LINEAGE.md`](../docs/development/DATA-CONTRACTS-AND-LINEAGE.md),
반복 장애의 영구 가드는
[`../docs/development/INCIDENT-RECURRENCE-PREVENTION.md`](../docs/development/INCIDENT-RECURRENCE-PREVENTION.md)를
기준으로 합니다.
섹터 순환의 총수익률·위험조정 모멘텀 결정은
[`docs/ADR-002-sector-rotation-total-return-momentum.md`](docs/ADR-002-sector-rotation-total-return-momentum.md),
사용자 해석과 UI 안전 기준은
[`../docs/PDR-001-sector-rotation-product-interpretation.md`](../docs/PDR-001-sector-rotation-product-interpretation.md)를
기준으로 합니다.
백업·복구 절차는 [`docs/BACKUP-RESTORE.md`](docs/BACKUP-RESTORE.md)에 있습니다.

의존성 방향은 한쪽입니다.

```text
bootstrap -> adapters -> application -> domain
```

| 모듈 | 책임 | 금지 사항 |
|---|---|---|
| `domain` | Entity, Value Object, 순수 계산 정책 | Spring, Jackson, HTTP, 파일, 캐시, ORM |
| `application` | 유스케이스, command/query, 입출력 포트 | Controller/DTO, transport 타입, 인프라 구현 |
| `adapters` | REST, scheduler, 외부 API, JDBC/MinIO, JSON mapping | 도메인 규칙 임의 복제 |
| `bootstrap` | Bean 조립, 설정, Actuator, 애플리케이션 시작 | 핵심 비즈니스 규칙 |
| `architecture-tests` | ArchUnit 경계 검증 | 경계 예외의 묵시적 허용 |

Domain과 application은 프레임워크 독립적입니다. REST DTO, Jackson document, SEC/Yahoo 응답, Telegram transport와 파일 저장 형식은 adapter에서 정규화된 포트 모델로 변환합니다.

## 바운디드 컨텍스트

### Market

- FRED, Yahoo, Fear & Greed, AAII/NAAIM, CBOE, stablecoin 직접 수집
- 원시 관측 121개, 파생지표 612개 snapshot 조립
- regime, signals, allocation, correlation, history/series
- 과거 history seed를 첫 실행에 PostgreSQL로 멱등 upsert

### Company

- 277개 기업 catalog/read model
- SEC ticker map, Company Facts, submissions, filing index/attachment 직접 수집
- 8-K Item 2.02, 6-K, Exhibit 99.x, IR 자료와 PDF 본문 추출
- revenue/margin/CAPEX/FCF guidance 구조화
- segment/geography 실제 XBRL mix와 대표기업 fallback
- Yahoo quote, 260 거래일 가격·거래량, analyst consensus/history
- SEC 태그 전환 대응과 FY+YTD 비교 방식의 TTM, ROIC·유효세율·분할조정 희석률·accrual quality
- Company Score, B Score, 바닥·거래량·확신형 바닥·반전 확인, OBV/VWAP
- 단기·스윙·장기별 가중 신호와 causal walk-forward 검증

회사 상세는 컷오버 seed를 last-valid projection으로 사용하면서 각 SEC/Yahoo evidence를 독립적으로 비동기 갱신합니다. 한 외부 소스가 실패해도 다른 필드나 마지막 정상 응답을 지우지 않습니다.

### Research

- 표준 11개 섹터, 전략 테마 6개
- 섹터 순환, 다음·다다음 주도 후보, narrative heat/stage
- bottleneck, 전체 기업 pagination/highlight
- SEC SIC exact→industry group→major group→표준 섹터 순의 동적 peer
- `valid_from`/`valid_to`와 30일 missing grace로 상장 universe survivorship 관리
- Google News RSS·Wikimedia Pageviews·선택적 YouTube Data API 기반 외부 내러티브 신호
- 소스 품질등급, 신선도, 연속 결측, 당일 immutable revision과 제한적 last-valid 감쇠
- 정규화 관측은 PostgreSQL, 원문 RSS/JSON은 MinIO에 저장하고 UI에서 45일 관측·결측·실패·revision 이력 제공

### Crypto

- BTC, ETH, SOL, XRP, BNB
- 코인장 우선 판단, 유동성·알트 시즌·공급 압력·ETF/온체인 proxy
- 자산별 점수, 바닥·반전, 실행 정보

### Institutional

- 공식 SEC submissions/index/information-table에서 13F-HR 원문 직접 수집
- 최근 두 분기 보고 주식 수로 new/increase/reduce/exit 분류
- 보고 가격 기반 추정 순매매와 복수 기관 공통 보유 제공
- Berkshire·Bridgewater·Citadel·Renaissance 등 주요 관리자 20곳 bounded universe
- SEC issuer directory의 보수적 명칭 매칭으로 CUSIP→ticker/섹터를 point-in-time 관리
- 현재 analyst score와 실제 13F 주식 수 방향의 괴리를 별도 진단(단독 매매신호 아님)
- 정규화 결과는 PostgreSQL, 원문 XML은 MinIO에 저장

### Policy

- Federal Reserve RSS/과거 FOMC 성명, U.S. Treasury, USTR 공식 원문 직접 수집
- 설명 가능한 매파/비둘기 lexicon, 원문 excerpt, confidence 제공
- 명시적 FOMC 금리결정을 정답으로 한 인과적 walk-forward confidence calibration
- confidence·신선도 gate를 통과한 값만 market `policyDirection` 자동 입력으로 변환
- 분석 결과는 PostgreSQL, 공식 원문 HTML은 MinIO에 저장

### Disclosure

- 금융감독원 OpenDART 기업코드 ZIP, 공시목록, 연결재무 전계정 공식 API 수집
- M&A·경영진 변경·자본행위·소송/제재·구조조정·실적 이벤트 분류
- 공시 가능일과 빈 응답을 고려한 분기→반기→연간 순차 fallback
- 정규화 결과는 PostgreSQL, 원본 ZIP/JSON은 MinIO에 저장
- API 키 미설정 시 수집만 비활성화하고 query/UI는 명시적 `collecting` 상태 유지

### Execution

- 투자 계획
- 1·2·3차 tranche
- 거래 로그
- startup cutover에서 legacy 파일을 빈 PostgreSQL table에만 안전하게 import
- 계획·tranche·거래 로그를 명시적 SQL과 ACID transaction으로 영속화
- 투자계획 partial PATCH를 aggregate advisory lock + row lock으로 직렬화해 동시 요청의 필드 유실 방지

### Notification

- startup snapshot과 현재 조건 충족 기업·코인 목록
- 시장 상태 변화, 신규 편입 및 기존 기업 신호 강화 Telegram 알림
- 기업: 찐바닥 후보 이상 + 반전 ON 이상 + B 70+ + 총점 70+ (실행 액션 무관)
- 기존 기업은 반전 ON→STRONG 또는 기업/B점수가 70 이상에서 5점 구간(70·75·80…)을 상향 돌파할 때 재알림
- 코인: 후보/확신 + B 70+ + 총점 70+ + STRONG BUY만
- 전체 기업 universe를 최신 Spring 점수·가격 신호로 재평가한 뒤 필터링
- candidate 상세와 fingerprint를 PostgreSQL transaction으로 영속화하여 재시작 직후에도 무거운 재계산 없이 알림
- 상태 전이와 발송 요청을 transactional outbox로 함께 commit하고 lease/retry/dead-letter로 전달
- DELIVERED/DEAD audit row는 30일 보존 후 매일 cluster-exclusive maintenance로 정리
- 전체 scheduler를 PostgreSQL advisory lock으로 단일 실행해 rolling deploy 중 중복 수집·알림 방지
- 장시간 scheduler lock connection을 Hikari transaction pool에서 분리하고 동시 4개로 제한해 pool starvation과 leak 오탐 방지

## 데이터 소유권

```text
PostgreSQL  market/company/execution/notification 정형 상태와 object pointer
MinIO       projection, SEC/IR 원문, immutable seed/source/history
server/data 및 macrosquare-spring-data  보존된 역사 원본(운영 서비스에는 미마운트)
```

MinIO seed projection은 런타임 서비스가 아니라 정적 last-valid 자료입니다. mutable projection은
MinIO version과 PostgreSQL active pointer를 함께 사용합니다. 운영 중 Node 프로세스, Node HTTP endpoint,
npm/npx 실행에 의존하지 않으며 모든 신규 쓰기는 Spring이 소유합니다.

## 공개 API

전체 공개 경로 45개를 Spring이 소유하고 `PublicApiRouteCoverageTest`로 누락을 막습니다.

주요 그룹:

```text
GET/POST /api/snapshot
POST     /api/refresh
GET      /api/history/**
GET      /api/company/{ticker}
GET      /api/company-search
GET      /api/company-summaries
GET      /api/institutional-flows
GET      /api/policy-intelligence
GET      /api/research/peers/{ticker}
GET      /api/dart/disclosures/{stockCode}
GET      /api/research/**
GET      /api/narrative/**
GET      /api/bottleneck/**
GET      /api/earnings
GET      /api/backtest/**
GET/POST /api/plan
GET/POST /api/trade-log
GET/POST/DELETE /api/execution-plan/tranche/**
GET      /api/execution-plan/purchasing-power
GET      /api/health
```

`/internal/v1/migration/**` 경로는 과거 Node와의 parity 증빙을 재현하기 위한 읽기 전용 진단 API입니다. 운영 공개 API는 이 경로에 의존하지 않습니다.

따라서 해당 진단 경로의 응답 필드와 일부 타입에는 당시 계약인 `legacy`/`shadow` 명칭이 호환 목적으로 남아 있습니다. 운영 설정·스케줄러·영속 저장소는 `seed`/`store` 모델을 사용하며 `shadow` 런타임은 존재하지 않습니다.

## 빌드와 테스트

### JDK 21 로컬 빌드

```bash
cd server-spring
./mvnw clean verify
```

`.java-version`은 `21`, Maven release는 `21`, class major는 `65`입니다. 호스트 기본 JDK가 21 미만이면 `JAVA_HOME`을 JDK 21 이상으로 설정하십시오.

### 운영과 동일한 Docker 빌드

```bash
docker build -t macrosquare-server-spring:local .
```

Dockerfile builder가 Temurin 21에서 `mvn clean verify`를 실행하고, runner는 비-root Temurin 21 JRE Alpine입니다.

최종 테스트 수:

| 모듈 | 테스트 |
|---|---:|
| domain | 99 |
| application | 85 |
| adapters | 177 |
| bootstrap | 24 |
| architecture | 6 |
| **합계** | **391** |

adapters의 실제 PostgreSQL 18 멀티 인스턴스 테스트 7개는 일반 Maven 실행에서는 skip되고,
`scripts/test-postgres-multi-instance.sh`에서 별도로 모두 실행됩니다.

`GET /actuator/info`는 `optionalIntegrations.youtube/openDart`의 수집 활성화·credential 설정 여부만
노출하며 API 키 값은 절대 응답에 포함하지 않습니다.

### 정적 경계 검사

```bash
rg -n --glob '*.java' \
  '^import (org\.springframework|jakarta\.|tools\.jackson|org\.hibernate|org\.postgresql|io\.minio|java\.net\.http|java\.nio\.file|java\.sql)' \
  domain/src application/src
```

정상 결과는 0건입니다.

### 운영 API smoke

```bash
python3 migration/tools/smoke-production-api.py \
  --base-url http://192.168.0.200:5846
```

읽기 API와 안전한 snapshot 재계산 43개 smoke check를 검증합니다. 투자 계획·거래 로그에는 쓰지 않습니다.

## 운영 설정

핵심 환경변수:

```text
PORT=5846
STORAGE_MODE=postgres-minio
DATABASE_URL=jdbc:postgresql://postgres:5432/macrosquare
OBJECT_STORAGE_ENDPOINT=http://minio:9000
MARKET_READ_MODE=spring-native
MARKET_COLLECTION_ENABLED=true
MARKET_SNAPSHOT_REFRESH_ENABLED=true
TELEGRAM_NOTIFICATIONS_ENABLED=true
PEER_DISCOVERY_ENABLED=true
NARRATIVE_SOURCE_COLLECTION_ENABLED=true
YOUTUBE_API_KEY=
DART_COLLECTION_ENABLED=false
DART_API_KEY=
```

비밀값은 compose가 루트 `.env`에서 주입합니다. 토큰과 API key를 YAML/Java/문서에 직접 넣지 않습니다.

운영 cadence:

| 항목 | 주기/TTL |
|---|---:|
| Yahoo 시장 관측 / snapshot | 5분 / 5분 |
| Fear & Greed | 1시간 |
| FRED | 6시간 |
| KRX 투자자별 수급(Naver Finance 집계) | 30분; 매회 6페이지/60영업일 멱등 갱신 |
| analyst consensus | 1시간 |
| 기업 가격 history | 15분 |
| SEC submissions | 30분 |
| SEC Company Facts | 4시간 |
| SEC filing detail | 6시간 |
| 주요 기관 SEC 13F 20곳 | 시작 30초 후, 이후 24시간 |
| Fed·Treasury·USTR 정책 원문 | 시작 40초 후, 이후 6시간 |
| SEC SIC peer taxonomy | 시작 2분 후, 이후 6시간; 종목별 30일 TTL |
| 외부 내러티브 소스 | 시작 90초 후, 이후 6시간; freshness 18~48시간 |
| OpenDART 공시·재무 | 시작 3분 후, 이후 6시간; 기업코드 24시간 TTL |
| candidate scan | 평일 1시간, 주말 4시간 |

## 운영 확인

```bash
curl -fsS http://localhost:5846/actuator/health/readiness
curl -fsS http://localhost:5846/api/health
docker top macrosquare-server
docker logs --since 10m macrosquare-server
```

정상 운영 컨테이너의 backend process는 `java -jar /app/app.jar` 하나입니다.

## 배포

```bash
# 저장소 루트에서 홈서버 자동 배포/검증
./scripts/deploy-home.sh

# 재배포 없이 홈서버 검증
./scripts/verify-home.sh

# 실제 PostgreSQL 18 멀티 인스턴스 동시성 검증
./scripts/test-postgres-multi-instance.sh

# 오프호스트 백업 / 격리 복구 리허설
./scripts/backup-home-storage.sh
./scripts/restore-drill-home-storage.sh
```

named volume과 다른 서비스를 보존하기 위해 `--remove-orphans`를 사용하지 않습니다.

자동 배포는 운영 compose와 현재 서버·클라이언트 image ID를 배포 트랜잭션 동안 하나의 rollback 단위로 보존합니다. 빌드, readiness, 43개 API smoke, 핵심 UI route 중 하나라도 실패하면 직전 조합으로 복원합니다. 성공하면 임시 rollback tag를 제거하므로 운영 호스트에는 현재 production 이미지 두 개만 남습니다. Node 런타임으로 되돌리는 경로는 없습니다.

## 이력 문서

- [`migration/CUTOVER-2026-07-21.md`](migration/CUTOVER-2026-07-21.md): 최종 운영 전환 증빙
- [`migration/MIGRATION.md`](migration/MIGRATION.md): 단계별 이관 이력
- [`migration/BENCHMARK.md`](migration/BENCHMARK.md): 단계별 성능 측정 이력

현재 금융 산식·운영 계약은 이력 문서가 아니라 [`../docs/README.md`](../docs/README.md)의 `CURRENT`
문서를 사용합니다. 이력의 과거 route/test/version 수치를 현재 기준으로 해석하지 않습니다.
