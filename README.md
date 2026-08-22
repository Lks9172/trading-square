# MacroSquare

거시경제, 시장 국면, 섹터 순환, 기업·코인 펀더멘털과 가격 신호를 한 화면에서 분석하고 실행 계획까지 관리하는 투자 리서치 시스템입니다.

## 운영 상태

**2026-07-21 기준 백엔드의 Java 전환이 완료됐습니다.**

- Backend: **Java 21 LTS + Spring Boot 4.1.0 + Tomcat 11**
- Frontend: **Next.js 16.3.0 + React 19 + TypeScript**
- Runtime: Docker Compose, PostgreSQL 18, MinIO, Actuator/Prometheus, OTel Collector/Jaeger, Alloy/Loki
- 운영 API: Compose 내부 `http://macrosquare-server:5846` (호스트에서는 `127.0.0.1:5846`, SSH 터널로만 접근)
- 운영 UI: `http://192.168.0.200:5847`
- 기존 `server/`의 Node/Express 코드는 감사·역사적 비교용 소스로만 남아 있으며 실행 이미지와 컨테이너는 폐기됐습니다.

최종 컷오버 증빙은 [`server-spring/migration/CUTOVER-2026-07-21.md`](server-spring/migration/CUTOVER-2026-07-21.md)에 있습니다.

## 주요 기능

- FRED, Yahoo, CNN Fear & Greed, AAII/NAAIM, 스테이블코인 등 시장 데이터 수집
- 거시 국면, 자산 신호, 배분, 섹터 순환과 다음·다다음 주도 후보 분석
- 표준 11개 섹터, 전략 테마 6개, 기업 277개 리서치
- SEC Company Facts/Submissions/8-K/6-K/Exhibit/IR 자료와 PDF 본문 분석
- 기업 점수, B 점수, 병목·내러티브, 가이던스, segment/geo mix 분석
- 가격·거래량 기반 바닥 신호와 반전 확인 신호
- BTC/ETH/SOL/XRP/BNB 코인 시장·자산 분석
- 투자 계획, 분할매수 tranche, 거래 로그, 백테스트
- 20개 주요 기관 13F, point-in-time CUSIP→ticker/섹터, 애널리스트↔실제 수량 괴리
- Fed·미 재무부·USTR 공식 원문과 FOMC 결정 기반 walk-forward confidence 진단
- SEC SIC 기반 동적 peer·상장 생존시점 관리, OpenDART 중대공시·연결재무 parser
- Google News RSS·Wikimedia Pageviews·선택적 YouTube API 기반 내러티브 실데이터와 45일 품질·결측·revision 이력
- Telegram 시작 알림, 시장 상태 변경, 조건 충족 기업·코인 편입 알림

## 아키텍처

```text
client (Next.js :5847)
        |
        v
server-spring (Spring Boot :5846)
  bootstrap -> adapters -> application -> domain
                     |             |
                  외부 I/O       순수 정책
```

- `domain`: 프레임워크와 I/O를 모르는 순수 Java 모델·정책
- `application`: 유스케이스와 입출력 포트
- `adapters`: REST, scheduler, SEC/Yahoo/FRED/Telegram, JDBC/MinIO 영속화
- `bootstrap`: Spring Bean 조립, 설정, Actuator
- `architecture-tests`: ArchUnit 의존성 경계 회귀 테스트

Controller, DTO, Jackson, HTTP 클라이언트, 캐시, JDBC/MinIO·ORM 타입은 domain/application 경계 안으로 들어가지 않습니다.

## 디렉터리

```text
trading-square/
├─ client/                         # Next.js UI
├─ server-spring/                  # 현재 운영 Java 백엔드
│  ├─ domain/
│  ├─ application/
│  ├─ adapters/
│  ├─ bootstrap/
│  ├─ architecture-tests/
│  └─ migration/                   # 계약·성능·컷오버 증빙
├─ server/                         # Node 역사적 참조(비실행)
└─ docker-compose.yml              # Spring 운영 구성
```

## 빌드와 테스트

### Java 백엔드

정확한 운영 런타임은 Docker의 Temurin 21 builder/runner로 고정됩니다.

```bash
cd server-spring
./mvnw clean verify
```

로컬 JDK는 21 이상이어야 하며 컴파일 결과는 `--release 21`/class major `65`로 고정됩니다. 프로젝트에는 `.java-version`이 포함되어 있습니다.

```bash
docker build -t macrosquare-server-spring:local ./server-spring
```

### 프런트엔드

```bash
cd client
npm ci
npm run lint
npm run build
```

최종 검증 기준: Java **391 tests**(실 PostgreSQL opt-in 7개 포함), 프런트 lint/build, 운영 API smoke **41/41**.

## 실행

루트 `.env`에 비밀값을 두고 저장소에는 커밋하지 않습니다.

```env
FRED_API_KEY=...
TELEGRAM_BOT_TOKEN=...
TELEGRAM_CHAT_ID=...
HOST_IP=192.168.0.200
# 한국 OpenDART 수집을 활성화할 때만 설정
DART_COLLECTION_ENABLED=true
DART_API_KEY=...
# 선택 사항: 없으면 YouTube만 MISSING으로 제외하고 뉴스/Wikimedia는 계속 수집
YOUTUBE_API_KEY=...
```

```bash
docker compose up -d --build
```

상태 확인:

```bash
curl -fsS http://localhost:5846/actuator/health/readiness
curl -fsS http://localhost:5846/api/health
```

운영 관측성 포트는 홈서버 loopback에만 열립니다. 홈서버 내부 또는 SSH 터널에서 확인합니다.

```bash
curl -fsS http://localhost:5846/actuator/prometheus
curl -fsS http://localhost:5902/-/ready       # Prometheus, 15일/2GB 보존
curl -fsS http://localhost:5903/ready         # Loki, 7일 보존
curl -fsS http://localhost:13133/             # OpenTelemetry Collector

# 로컬 PC에서 Jaeger UI를 볼 때
ssh -L 16687:127.0.0.1:16687 lks@192.168.0.200
# http://127.0.0.1:16687
```

Next.js는 CLS/FCP/FID/INP/LCP/TTFB를 `/api/rum`으로 전송합니다. 쿼리스트링, IP,
user-agent, cookie, 세션 식별자는 저장하지 않으며 경로와 bounded Web Vital만 구조화 로그로 남깁니다.
Alloy는 Docker 제어 소켓 대신 `/var/lib/docker/containers`를 읽기 전용으로 읽어 Loki에 전달합니다.
배포 검증은 Spring metric scrape, 강제 표본 trace의 Collector→Jaeger 전달, synthetic RUM의
Next→Alloy→Loki 전달까지 실제로 확인합니다.

## 데이터와 수집 주기

| 데이터 | 운영 주기/TTL |
|---|---:|
| Yahoo 시장 관측 | 5분 |
| 섹터 ETF/SPY 배당 반영 총수익률 | 6시간 / 최소 2,000거래일 |
| 시장 snapshot | 5분 |
| Fear & Greed | 1시간 |
| FRED | 6시간 |
| analyst consensus | 1시간 |
| Yahoo 기업 가격 history | 15분 |
| SEC submissions | 30분 |
| SEC Company Facts | 4시간 |
| SEC filing detail/IR | 6시간 |
| 주요 기관 SEC 13F 20곳 | 24시간 |
| Fed·Treasury·USTR 정책 원문 | 6시간 |
| SEC SIC peer taxonomy | 6시간 배치 / 종목별 30일 TTL |
| narrative 뉴스·관심도·영상 | 6시간 / 소스별 18~48시간 freshness |
| OpenDART 공시·재무 | 6시간 / 기업코드 목록 24시간 TTL |

정형 상태와 시계열은 `macrosquare-postgres`, PDF/HTML/JSON object는 private·versioned
`macrosquare-minio` volume에 저장됩니다. PostgreSQL에는 MinIO object metadata와 active version pointer만
두며 BLOB은 넣지 않습니다. 기존 `macrosquare-spring-data`와 `server/data` 원본은 checksum/archive와
함께 보존하지만 운영 컨테이너에는 더 이상 mount하지 않습니다. 런타임 Node 호출이나 Node subprocess는 없습니다.

OpenDART는 공식 API 키가 없는 환경에서도 서버와 UI가 정상 기동하며 `collecting` 상태를 명시합니다.
키를 주입하고 `DART_COLLECTION_ENABLED=true`로 설정하면 같은 PostgreSQL/MinIO 경계에서 자동 수집합니다.
내러티브는 키가 없어도 Google News RSS와 Wikimedia Pageviews를 수집합니다. YouTube 키가 없거나 소스가
실패하면 해당 소스를 중립점수로 간주하지 않고 가중치 0의 `MISSING`/`FAILED`로 명시합니다.
선택 연동의 활성화 여부는 비밀값을 출력하지 않는 `GET /actuator/info`의 `optionalIntegrations`에서
운영자가 확인할 수 있습니다.

## 배포

홈서버 운영 배포(소스 동기화, 빌드, 순차 교체, API/UI 검증, 실패 시 트랜잭션 내 자동 롤백):

```bash
./scripts/deploy-home.sh --plan
./scripts/deploy-home.sh          # 변경 범위 자동 선택
./scripts/deploy-home.sh --full   # 강제 전체 배포
```

단일 server/client, scripts, docs 변경은 관계없는 image/container를 재빌드·재시작하지 않는다. 공통
compose/observability 또는 server/client 동시 변경은 자동으로 full 배포로 승격한다. 로컬 검증은
`./scripts/test-tier.sh fast|standard|release`로 실행한다.

배포 없이 현재 홈서버 상태만 검증:

```bash
./scripts/verify-home.sh
```

로컬 전환 불변식만 검증:

```bash
./scripts/check-cutover-invariants.sh

# 별도 Mac 호스트 backup + 격리 restore drill
./scripts/backup-home-storage.sh
./scripts/restore-drill-home-storage.sh
```

Node 런타임은 완전히 폐기됐습니다. 배포 중에는 직전 Spring/Next image ID와 compose를 임시 보존해 검증 실패 시 자동 복원하고, 성공하면 임시 rollback tag도 제거합니다. 볼륨과 다른 서비스를 보존하기 위해 `--remove-orphans`를 사용하지 않습니다.

## 문서

- [통합 문서 허브](docs/README.md)
- [금융 관점 문서](docs/finance/README.md)
- [개발·운영 관점 문서](docs/development/README.md)
- [문서 거버넌스와 변경 규칙](docs/DOCUMENT-GOVERNANCE.md)
- [장애 재발 방지 카탈로그](docs/development/INCIDENT-RECURRENCE-PREVENTION.md)
- [실시간·일일 관측 Runbook](docs/RUNBOOK-daily-observability-audit.md)
- [Spring 백엔드 운영 가이드](server-spring/README.md)
- [최종 컷오버 증빙](server-spring/migration/CUTOVER-2026-07-21.md)
- [PostgreSQL·MinIO 저장 경계 ADR](server-spring/docs/ADR-001-storage-and-database-boundaries.md)
- [섹터 순환 총수익률 위험조정 모멘텀 ADR](server-spring/docs/ADR-002-sector-rotation-total-return-momentum.md)
- [섹터 순환 제품 해석 PDR](docs/PDR-001-sector-rotation-product-interpretation.md)
- [ADR·PDR 의사결정 기록 목록](docs/DECISION-RECORDS.md)
- [PostgreSQL·MinIO 백업·복구](server-spring/docs/BACKUP-RESTORE.md)
- [마이그레이션 이력](server-spring/migration/MIGRATION.md)
- [성능 측정 이력](server-spring/migration/BENCHMARK.md)
- [섹터 순환 상대 모멘텀 V2](docs/SECTOR-ROTATION-METHODOLOGY-V2.md)
