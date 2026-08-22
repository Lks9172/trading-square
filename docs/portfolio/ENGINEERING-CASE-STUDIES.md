# MacroSquare 엔지니어링 사례 연구

각 사례는 포트폴리오와 기술 면접에서 사용할 수 있도록 **상황 → 문제 → 판단 → 구현 → 검증 → 트레이드오프** 순서로 정리했다. 수치는 [근거 문서](EVIDENCE-AND-METRICS.md)의 기준을 따른다.

## 사례 1. TypeScript 백엔드를 Java/Spring 프로덕션 런타임으로 전환

### 상황

초기 MacroSquare는 빠른 기능 검증을 위해 TypeScript 기반 서버에서 데이터 수집, 금융 계산, 파일 영속화, API, Telegram을 함께 처리했다. 기능이 늘면서 다음 문제가 커졌다.

- 시장·기업·리서치·알림 규칙이 같은 런타임과 파일 구조에 결합
- 파일 저장이 정형 상태, transaction, 원문 object 역할을 동시에 담당
- scheduler, provider I/O, 사용자 API가 같은 자원 경계를 공유
- “계층별 디렉터리”는 있었지만 의존 방향을 자동으로 막는 수단 부족
- 운영 중 부분 성공, 동시 수정, 재기동 중복 실행의 의미가 불명확

### 핵심 문제

단순 NestJS/TypeScript 코드를 Java 문법으로 옮기면 기존 결합과 실패 의미도 그대로 이동한다. 목표는 언어 교체가 아니라 **업무 경계와 운영 불변식을 다시 설계한 cutover**였다.

### 판단

1. 도메인 규칙을 먼저 추출하고 transport·provider·storage type을 제거한다.
2. 멀티모듈 의존 방향을 `bootstrap → adapters → application → domain`으로 고정한다.
3. 기존 공개 API의 parity를 측정하되 새로운 운영 경로가 legacy bridge에 의존하지 않게 한다.
4. 기능 단위 전환이 아니라 source of truth, scheduler, storage write ownership까지 Spring으로 넘긴다.
5. cutover 성공을 “서버가 뜸”이 아니라 route·데이터·동시성·백업·관측 기준으로 정의한다.

### 구현

- Maven 5개 모듈: `domain`, `application`, `adapters`, `bootstrap`, `architecture-tests`
- Market, Company, Research, Crypto, Institutional, Policy, Disclosure, Execution, Notification, Integrity 등 bounded context 정리
- domain은 불변 record/value object와 순수 policy로 구성
- application은 use case와 input/output port, transaction 의미만 소유
- REST DTO, JDBC row, MinIO metadata, SEC/Yahoo response는 adapter에 격리
- bootstrap을 명시적 composition root로 사용
- 공개 API route ownership test와 migration parity probe 구축
- 운영 Docker 이미지에서 Node backend bridge, HTTP proxy runtime, subprocess 제거

### 검증

- 공개 API 45개 소유권 자동 검사
- 운영 smoke 43개
- Java Surefire 739 tests, failure/error 0
- ArchUnit 6개 규칙
- PostgreSQL 18 멀티 인스턴스 테스트 별도 실행
- 운영 컨테이너 backend process가 `java -jar` 하나임을 검증
- legacy runtime mount와 Node backend image 재등장 시 배포 차단

### 트레이드오프

- 명시적 port와 mapping 코드가 늘었다.
- JPA 자동화 대신 JDBC를 선택해 SQL·row mapping 부담이 생겼다.
- 반대로 aggregate boundary, query cost, transaction과 데이터 소유권이 명확해졌다.

### 면접용 요약

> “언어 마이그레이션이 아니라 운영 책임 마이그레이션으로 정의했습니다. API parity뿐 아니라 write ownership, scheduler, DB transaction, observability, rollback까지 Spring이 소유하게 했고, 잘못된 의존은 ArchUnit으로 빌드에서 차단했습니다.”

---

## 사례 2. PostgreSQL과 MinIO 사이의 저장 경계와 일관성 설계

### 상황

금융 시스템에는 성격이 다른 데이터가 공존한다.

- 시계열·후보 상태·투자 계획처럼 검색·제약·transaction이 필요한 정형 데이터
- SEC/IR PDF·HTML·XML, source response, 큰 JSON projection처럼 immutable object가 적합한 데이터

파일만 사용하면 동시 쓰기·부분 갱신·검색·복구가 약하고, 모든 원문을 PostgreSQL BLOB에 넣으면 DB backup과 query 책임이 비대해진다.

### 핵심 문제

PostgreSQL과 MinIO는 하나의 분산 transaction을 제공하지 않는다. object 저장과 active pointer 갱신 중 하나만 성공하면 last-valid가 손상될 수 있다.

### 판단

- PostgreSQL을 정형 state와 **어떤 object version을 공개할지 결정하는 control plane**으로 사용
- MinIO를 immutable/versioned body의 data plane으로 사용
- body first, pointer last 순서로 write
- reader는 pointer가 지정한 exact version만 읽고 size·ETag·SHA-256을 재검증
- XA/2PC 대신 실패 상태를 명시적으로 운영

### 구현

1. MinIO private bucket에 versioned body PUT
2. returned version ID/ETag, local SHA-256, size 계산
3. PostgreSQL artifact metadata insert
4. active pointer를 DB transaction으로 교체
5. reader는 pointer와 exact object version을 조회
6. checksum 불일치나 dangling pointer는 CRITICAL integrity incident
7. pointer 없는 object는 reader에게 보이지 않으며 retention 기반 GC 대상으로 분류

애플리케이션 계정은 Get/List/Put만 가능하고 delete 권한을 제거했다. root credential은 초기 bucket·policy 구성에만 사용한다.

### 검증

- active pointer 전수 checksum 비교
- dangling pointer 0 확인
- MinIO versioning/private policy 검사
- 동일 SHA-256, restore 후 ETag 변경 시 bounded body 재검증 경로
- PostgreSQL dump와 MinIO manifest를 동일 timestamp로 묶어 restore drill

### 트레이드오프

- 완전한 원자 commit은 아니므로 orphan object가 생길 수 있다.
- 그러나 pointer를 마지막에 commit하므로 기존 current projection은 보존된다.
- orphan은 가용성 문제가 아니라 유지보수 문제로 격리했고, dangling pointer는 즉시 장애로 다룬다.

### 면접용 요약

> “2PC를 도입하지 않고 immutable object와 transactional pointer로 일관성 경계를 단순화했습니다. 실패 시 새 데이터가 공개되지 않을 수는 있어도 기존 정상 projection이 손상되지는 않도록 순서를 설계했습니다.”

---

## 사례 3. 중복 스케줄 실행과 동시 PATCH 유실 방지

### 상황

단일 컨테이너에서는 `fixedDelay`와 local lock만으로 충분해 보였다. 하지만 rolling deploy에서 구·신 컨테이너가 겹치거나 replica가 늘면 같은 수집·알림이 중복 실행될 수 있다. 투자 계획의 partial PATCH도 두 요청이 서로 다른 필드를 수정할 때 마지막 writer가 앞선 변경을 덮을 수 있었다.

### 핵심 문제

- JVM lock은 프로세스 밖 경쟁자를 보지 못한다.
- scheduler가 Hikari connection으로 session advisory lock을 오래 잡으면 사용자 API transaction pool이 고갈될 수 있다.
- partial PATCH를 read-modify-write로 구현하면 필드 유실이 발생한다.

### 판단

- 스케줄 실행권: process-local non-overlap + PostgreSQL session advisory lock의 2중 보호
- coordination connection: Hikari와 분리된 unpooled source
- coordination 물리 동시성: fair semaphore 최대 4
- command aggregate: transaction advisory lock + row lock + version
- DB 조정 실패 시 로컬 우회 실행 금지

### 구현

- 모든 scheduler가 `ExclusiveTaskExecution` application port를 사용
- adapter가 task key를 PostgreSQL advisory lock으로 변환
- lock connection을 작업 종료까지 유지
- 외부 provider 호출에는 공유 throttle과 context별 semaphore 적용
- 투자 계획 PATCH는 transaction 안에서 `SELECT ... FOR UPDATE`로 현재 aggregate를 다시 읽고 부분 command 적용
- file mode는 JVM lock과 single-writer lease로 동일 계약을 제한적으로 구현

### 검증

- ArchUnit이 모든 `*Scheduler`의 cluster-exclusive port 의존을 검사
- 두 인스턴스가 같은 task key를 경쟁할 때 하나만 실행
- lock 해제 후 다른 인스턴스 재획득
- 서로 다른 필드를 동시에 PATCH해도 둘 다 보존
- DB coordination 실패가 성공 metric으로 기록되지 않는지 검사

### 트레이드오프

- advisory lock은 PostgreSQL에 운영 조정을 의존하게 한다.
- 장시간 작업이 lock을 오래 보유할 수 있다.
- 대신 별도 connection source와 상한으로 API pool 영향을 차단했고, 중복 부수효과보다 일시적 skip을 선택했다.

### 면접용 요약

> “동시성은 thread-safe만으로 끝나지 않았습니다. 프로세스 내부, replica 간 실행권, DB aggregate write를 각각 다른 lock 수준으로 분리했고, scheduler lock이 API pool을 잠식하지 않도록 connection pool도 분리했습니다.”

---

## 사례 4. 알림 소실을 transactional outbox로 전환

### 상황

초기 알림은 후보 상태를 먼저 저장한 뒤 Telegram API를 호출했다. 상태 저장 후 전송이 실패하면 다음 스캔에서 이미 알려진 후보로 간주해 재시도하지 않을 수 있었다. 반대로 전송 후 상태 저장이 실패하면 중복 전송 위험이 있었다.

### 핵심 문제

외부 메시지 provider와 PostgreSQL 사이에는 단일 transaction이 없다. 후보 상태 변화와 “보내야 할 메시지” 기록을 함께 보존해야 했다.

### 판단

- 후보 상태와 outbound message enqueue를 동일 PostgreSQL transaction에 저장
- 외부 전송은 별도 dispatcher가 lease 기반으로 수행
- 재시도·dead letter·stuck 상태를 운영 질의 가능한 데이터로 관리
- provider가 idempotency를 지원하지 않는 한 exactly-once를 주장하지 않음

### 구현

- dedupe key가 있는 outbox row
- `PENDING → IN_FLIGHT → DELIVERED/RETRY/DEAD` 상태 기계
- `FOR UPDATE SKIP LOCKED`를 사용한 다중 worker lease
- lease timeout 회수
- 지수 backoff와 최대 시도 횟수
- startup, 시장 변화, 신규 후보, 신호 강화, 주간 리포트가 동일 outbox 경로 사용
- delivered/dead audit retention과 cluster-exclusive maintenance

### 제품 정책

기업 후보는 실행 액션과 분리했다.

- 기업점수 70 이상
- B점수 70 이상
- 바닥 `CANDIDATE` 이상
- 반전 `ON` 이상
- `ON→STRONG`, total/B 70·75·80… 상향 돌파 시 강화 알림
- MACD·섹터·실행 액션은 참고 근거

이는 “알림은 추가 조사 후보이며 자동 주문이 아니다”라는 제품 의미를 코드 policy로 고정한 것이다.

### 검증

- 상태 변경과 outbox enqueue의 transaction rollback/commit
- 여러 dispatcher가 같은 row를 가져가지 않음
- 실패 후 retry, lease expiry, dead 전이
- 동일 score band 내 움직임은 재알림하지 않고 다음 5점 band만 알림
- server startup과 신규 편입이 동일 자격 정책을 사용

### 트레이드오프

Telegram 수락 직후 DB ack 전에 crash하면 극소 중복 가능성이 남는다. 운영 문서에서 이를 at-least-once로 명시하고 사용자가 감당할 수 있는 결과인지 구분했다. 메시지 누락보다 드문 중복을 선택한 것이다.

---

## 사례 5. “많이 하락함”과 “반전 확인”을 분리한 금융 도메인 모델

### 상황

과거 반전 점수에서 바닥 점수의 비중이 너무 크면, 급락이 심한 종목이 가격 구조와 수급 회복 없이도 강한 반전처럼 보일 수 있었다. 실제로 “바닥 신호는 강한데 매도 압력이 끝났는가?”는 별도 질문이다.

### 핵심 문제

- capitulation intensity와 reversal confirmation의 인과 의미 혼합
- 가격 반등 하나가 거래량·구조 확인을 대신하는 문제
- 사용자가 `CONVICTION`을 수익 확신으로 오해할 위험

### 판단

바닥과 반전을 두 domain policy로 분리했다.

#### 바닥 후보

- 급락·이격·최근 하락일 거래량
- 낙폭 축소
- 신호 경과와 신호 후 이미 오른 폭
- `UNMET / CANDIDATE / CONVICTION`

#### 반전 확인

- bottom score 45%
- 독립 OBV/VWAP 30%
- 독립 가격 구조 25%
- 확인 marker와 구조 상태를 별도 gate
- `OFF / EARLY / ON / STRONG`

### 구현

- `STRONG`: CONVICTION + 구조적 바닥 + marker + 수급 72+ + 가격 68+ + 종합 78+
- `ON`: CANDIDATE 이상 + 1차/구조 확인 + marker + 수급 62+ + 가격 60+ + 종합 68+
- 바닥 후보만 있고 독립 확인 부족: `EARLY`
- evidence 부족: `OFF`
- reasons와 cautions를 각각 bounded list로 구성해 UI·Telegram이 설명 가능하도록 함

### 검증

- 임계값 바로 아래/같음/위 경계 테스트
- CANDIDATE가 독립 가격·수급을 충족하면 ON 가능
- CONVICTION이어도 독립 evidence가 부족하면 EARLY
- source failure나 stale evidence가 과거 ON을 되살리지 않음
- 실제 전체 기업 재계산에서 ORCL이 `후보 + ON`으로 자격을 얻는 경로 확인

### 트레이드오프

임계값은 검증된 수익 확률이 아니라 관찰 정책이다. 제품 문구와 PDR에서 이를 분명히 하고, 후보 수를 늘리기 위해 즉흥 완화하지 않도록 재검토 조건을 문서화했다.

---

## 사례 6. MACD·다이버전스를 미래 데이터 누수 없이 공통 커널로 구현

### 상황

기업 상세와 시장 대시보드에서 MACD 신호를 보여달라는 요구가 있었다. 단순 시각화는 쉽지만, 다이버전스는 우측 pivot 확인 전에는 그 저점·고점이 실제 pivot인지 알 수 없으므로 잘못 구현하면 미래 데이터를 사용한다.

### 핵심 문제

- 기업과 시장에서 서로 다른 MACD 산식이 생길 가능성
- 일봉·주봉 진행 중 bar 처리
- crossover와 50/200일선 golden cross 명칭 혼동
- pivot divergence의 look-ahead bias
- 검증 전 기술지표가 기존 점수를 오염할 위험

### 판단

- `technical` 공통 domain kernel에서 MACD 12·26·9 계산
- 기업·시장 adapter는 동일 `TechnicalClosePoint`로 번역
- crossover, histogram state, divergence를 각각 독립 결과로 반환
- divergence는 오른쪽 radius만큼 데이터가 지난 confirmed date부터 활성화
- 점수에는 넣지 않고 timing evidence로만 노출

### 구현

- EMA seed와 finite value 검증
- histogram 0선 교차로 bullish/bearish cross 판정
- positive/negative 영역에서 expanding/contracting/flat 구분
- 일봉을 거래 주의 마지막 close로 압축해 주봉 생성
- 고점/저점 pivot 두 개의 가격 변화와 histogram 변화를 비교
- 다이버전스 signal date가 아니라 **confirmed date**와 age를 저장
- 기업 요약 V22에 compact MACD evidence를 원자 JSON으로 영속
- Telegram startup/신규/강화 알림과 주요 지수 알림에 재사용

### 검증

- 합성 시계열로 bullish/bearish crossover
- 주봉 집계와 진행 중 주 표시
- 우측 pivot 확인 전 divergence 비활성
- 데이터 부족 시 `UNAVAILABLE`, 임의 계산 금지
- Next UI·Telegram message rendering·DB round-trip

### 트레이드오프

MACD는 후행 지표이며 단독 매수 신호가 아니다. 따라서 구현을 완료했어도 장기 walk-forward 전에는 Company/B/바닥 점수에 합치지 않았다.

---

## 사례 7. 포인트인타임 섹터 순환과 검증 원장

### 상황

현재 섹터 순위를 만드는 것은 가능하지만, “이 모델의 과거 적중률”을 말하려면 당시 알 수 있었던 데이터가 보존돼야 한다. 현재 EPS revision, flow, constituent를 과거 날짜에 붙이면 그럴듯한 수치가 나오지만 실제 검증이 아니다.

### 핵심 문제

- price return과 total return 혼용
- 현재 구성종목을 과거 전체에 적용하는 survivorship bias
- 현재 revision을 과거에 붙이는 look-ahead
- 신호일과 완료 가격 기준일 혼동
- GET 요청 시 validation row를 쓰면 관측 시점이 오염

### 판단

- 상대 모멘텀은 배당 반영 total return과 SPY benchmark 사용
- 완료 공통 거래일을 price anchor로 사용
- 신호 계산일과 가격 기준일 분리
- V19/V20 이후 current composite는 immutable forward ledger로만 성과 축적
- 과거 재현이 가능한 상대 모멘텀 layer와 전체 composite의 검증 상태를 분리

### 구현

- `sector_rotation_run`: methodology version + price anchor당 최초 1회
- `sector_rotation_item_snapshot`: run마다 표준 11개 component/rank/state
- `sector_rotation_outcome`: 21/63/126 session이 실제로 지난 후 append
- run + 11 items 한 transaction
- current GET은 write 금지
- coverage 부족·핵심 거시 결측은 HTTP 503 unavailable과 정상 후보 0개를 구분
- 표준 섹터와 전략 테마 namespace 충돌 방지

### 검증

- 독립 Python 재계산과 운영 percentile 오차 5e-7 미만
- 7년 월말 walk-forward에서 1/3/6개월 결과와 benchmark 비교
- Wilson 95% interval, 중첩 window용 Newey-West/Bartlett 보정
- 원장 run당 11 items, signal date/price anchor 분리
- 결측을 0으로 대체해 후보가 승격되지 않는지 검사

### 트레이드오프

전체 composite의 point-in-time 이력이 아직 짧아 강한 성과 주장을 할 수 없다. 대신 “현재 관찰 우선순위”와 “검증된 상대 모멘텀 layer”를 UI와 문서에서 구분했다.

---

## 사례 8. 런타임 증거로 CPU·팬·재기동 비용 최적화

### 상황

홈서버 팬이 지속적으로 도는 현상이 있었다. 요청 시점의 CPU는 대부분 idle이었으므로 단순히 “현재 정상”이라고 결론 내리면 재발 원인을 놓칠 수 있었다.

### 조사 순서

1. Prometheus에서 시스템·JVM CPU의 시간대별 평균/P95와 반복 spike 확인
2. 컨테이너 restart/OOM, 메모리, 디스크, 온도, governor 확인
3. scheduler 로그와 spike 시각을 대조
4. company summary·candidate scan·13F startup path를 코드로 추적

### 발견

- 매시간 277개 기업 후보 전체 재계산
- 30분마다 277개 기업 요약에서 목록에 불필요한 5년 walk-forward 수행
- 재배포 때 13F 약 9만 holdings를 다시 parse·delete·insert
- startup에서 company summary, analyst history, candidate scan이 provider 호출을 중첩
- company concurrency 8이 4코어 홈서버에서 순간 turbo와 발열을 유발

### 개선

- summary 목록 경로에서 5년 walk-forward 제거, 상세 화면에는 유지
- 2시간 이내 완전한 company evidence를 Telegram scan에서 재사용
- 20개 manager 모두 durable recent evidence가 있을 때만 첫 13F invocation skip
- 이후 24시간 정규 주기는 shortcut을 사용하지 않아 재배포가 수집을 무기한 연기하지 않게 함
- startup 작업을 3분/15분/20분으로 stagger
- provider-heavy 공통 advisory slot
- concurrency 8→4, MinIO/Alloy memory headroom 조정, power profile `balance_power`

### 결과

- 평균 CPU 약 10.55% → 2.82%, 약 73% 감소
- 277/277 기업 정합과 상세 검증 정보 유지
- 13F 20개 manager completeness를 확인한 경우에만 startup skip
- API smoke 43/43, restart 0, 새로운 ERROR/WARN 없음

### 핵심 교훈

> 순간 상태보다 반복 주기와 시간축을 봐야 한다. 성능 최적화도 데이터 신선도나 금융 정보량을 줄이는 대신, 중복 계산·잘못된 lifecycle·불필요한 startup work를 제거하는 쪽을 선택했다.

---

## 사례 9. 기업분할·공급자 공백·부분 성공의 금융 무결성 방어

### 상황

실운영에서 Yahoo가 환율 일부를 불완전하게 반환하거나, 기업분할 전후 가격 basis가 약 2배 불연속으로 들어오는 사건이 있었다. 값 하나가 비정상이어도 서버가 200을 반환하면 사용자는 이를 정상 신호로 오해할 수 있다.

### 문제

- split event가 있는지와 가격이 이미 조정됐는지 구분하지 못하면 이중 조정 가능
- 일시 FX 공백과 장기 데이터 장애가 같은 hard alert로 반복될 수 있음
- 일부 ticker 성공만 보고 batch 전체를 SUCCESS로 기록할 위험
- 잘못된 가격이 cache를 오염하면 다음 요청까지 거짓 바닥이 지속

### 구현

- 2/3/4/5/10/20배 corporate-action형 불연속 검출
- Yahoo split 분자/분모와 event 전후 가격 비율이 ±15% 내 일치할 때만 미조정 OHLCV 보정
- 이미 조정된 이력은 재조정하지 않음
- 불확실하면 price signal unavailable, BUY fail-closed
- 검증 실패 payload는 정상 cache에 넣지 않고 해당 ticker를 다음 수집 우선순위로 올림
- fresh last-valid가 30분 이내이고 FX 두 종목만 일시 실패한 경우 bounded retry와 hard alert 유예
- 전체/다른 source 실패, 값 없음, 30분 초과는 즉시 장애
- `collection_status`의 SUCCESS/DEGRADED/ERROR를 DB constraint로 강제

### 결과

- 거짓 매수 신호 없이 MNST 가격 basis incident 격리
- 동일 WARN을 첫 회와 30회 reminder로 제한하되 1분 무결성 감시는 유지
- Yahoo 정상 회복 시 90/90 source 성공 확인
- 실제 장기 공백은 가짜 중립값으로 숨기지 않음

---

## 사례 10. 변경 범위 기반 테스트·배포와 복구 가능한 운영

### 상황

초기 배포는 문서나 작은 UI 수정에도 backend 전체 테스트, PostgreSQL 통합, server/client 이미지 빌드, 백업, 전체 배포를 반복해 50분 이상 걸릴 수 있었다. 검증을 줄이면 금융·DB 회귀 위험이 커지고, 매번 전체 실행하면 피드백이 느려졌다.

### 판단

검증을 없애는 대신 **변경 위험에 따라 gate를 승격**한다.

- `fast`: compile, 영향 테스트, ArchUnit
- `standard`: 전체 backend, frontend test/lint/build, 문서·운영 검증
- `release`: clean build, disposable PostgreSQL 18 multi-instance, backup requirement
- production smoke와 `verify-home`은 scope와 무관하게 유지

### 구현

- Git 상태가 아니라 실제 로컬/홈서버 content diff로 `server/client/scripts/docs/verify/full` 판별
- POM/Flyway/persistence는 자동 release 승격
- compose/observability 또는 server+client 동시 변경은 full 승격
- docs/scripts scope는 애플리케이션 재시작 없음
- 새 server readiness 후 client replace
- ERR trap으로 직전 compose/config/image를 force recreate
- 디스크 preflight, SSH/rsync keepalive, running image ID 확인
- `verify-home` child command가 SSH stdin을 소비해 후속 검증을 건너뛰지 않도록 stdin 격리

### 결과

- 표준 전체 검증 약 51초로 단축된 세션 측정 사례
- 문서·스크립트 변경 시 server/client restart 0
- DB·schema 변경은 안전 gate를 유지
- 실패 배포는 데이터 volume을 건드리지 않고 직전 실행 unit으로 복구

### 핵심 교훈

> 빠른 배포는 테스트를 빼는 것이 아니라, 무엇이 바뀌었는지 정확히 분류해 관련 없는 비용을 제거하고 고위험 변경에는 오히려 더 강한 gate를 적용하는 것이다.
