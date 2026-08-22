# Node vs Spring Resource Benchmark

> **최종 컷오버 완료 — 2026-07-21**
> 이 문서의 대부분은 기능 이관 중 측정한 milestone별 역사 기록입니다. 당시의 “아직 Node 소유” 문구는
> 해당 시점의 상태를 뜻하며 현재 상태가 아닙니다. 최종 운영 이미지·메모리·API smoke·재시작 증빙은
> [`CUTOVER-2026-07-21.md`](CUTOVER-2026-07-21.md)를 참조하십시오.

## 비교 원칙

- 동일 홈서버, 동일 Docker 설정, 동일 원시 데이터 스냅샷을 사용한다.
- Node와 Spring 모두 cold/warm 시나리오를 각각 측정한다.
- read-through 캐시는 최초 cold miss, fresh hit, bounded stale 응답, background refresh 완료를 분리한다.
- 외부 API 변동을 제거하는 계산 benchmark와 실제 네트워크 포함 benchmark를 분리한다.
- 정보량이나 계산량을 줄여서 얻은 성능 향상은 인정하지 않는다.
- 최소 3회 반복 후 중앙값과 p95/p99를 기록한다.
- `scaffold`, `read-only parity`, `full parity`, `post-cutover 24h`를 서로 다른 milestone으로 보관한다.
- 기능이 덜 이관된 milestone의 수치로 최종 우열을 결론 내리지 않는다.

## 측정 항목

| 분류 | 항목 |
|---|---|
| 시작 | 프로세스 시작부터 readiness까지, startup snapshot 완료까지 |
| 메모리 | idle/평균/peak RSS, heap/non-heap, container working set |
| CPU | idle/평균/peak CPU, snapshot 1회당 CPU time |
| GC | collection 횟수, pause 합계/최대, allocation rate |
| HTTP | throughput, p50, p95, p99, max, error rate |
| 캐시 | cold miss, fresh hit, stale 반환, background refresh 시간, stale 상한 |
| 계산 | snapshot, company, sector, crypto, backtest 처리 시간 |
| 디스크 | image 크기, writable layer, persistent data, read/write bytes와 IOPS |
| 운영 | thread/PID/connection 수, graceful shutdown, 재시작 복구 시간 |

## 디스크 비교 원칙

- 호스트 원시 디스크 속도는 런타임 언어 차이가 아니므로 공통 환경 확인용으로만 1회 측정한다.
- 실제 비교값은 동일 fixture와 동일 요청에서 발생한 container block read/write, 영속 데이터 증가량,
  snapshot 1회당 쓰기 bytes, fsync 횟수, 캐시 파일 수와 평균 크기다.
- 개별 격리 영속 어댑터만 완성된 단계에서는 image/writable-layer/전용 volume 크기와 원본 불변성을
  참고값으로 남기되, 전체 production writer가 이관되기 전에는 I/O 우열을 판정하지 않는다.
- 데이터 포맷이나 보존 기간을 줄여 얻은 디스크 절감은 성능 개선으로 인정하지 않는다.

## 대표 부하

1. `GET /api/health` 1,000회
2. `GET /api/snapshot` warm cache 200회
3. `GET /api/company/NVDA` warm cache 100회
4. 40개 기업 summary 동시 조회
5. 강제 snapshot refresh 10회
6. 섹터/코인 전체 목록 조회
7. 동일 고정 fixture의 기업 점수 계산 100,000회

쓰기와 Telegram 테스트는 별도 격리 환경에서만 수행한다.

## 합격 게이트

- 응답 JSON 계약과 정보량 100% 유지
- p95가 Node 대비 악화되지 않음
- idle/peak 메모리와 CPU를 함께 보고 단일 수치로 결론 내리지 않음
- startup/readiness가 운영 재배포 요구시간 이내
- GC pause가 사용자 요청 지연을 유발하지 않음
- 지속 데이터 증가율과 디스크 쓰기량이 Node보다 과도하지 않음
- 성능이 미달하면 트래픽 전환을 중지하고 원인을 수정한 뒤 재측정

## 비교 milestone

1. **Scaffold**: JVM/컨테이너 기본 비용과 health transport만 측정. 최종 판정 금지.
2. **Read-only parity**: snapshot/company/sector/crypto의 동일 JSON 계약과 동일 원시 데이터로 비교.
3. **Full parity**: 수집기·영속화·스케줄러를 포함하되 Telegram 전송은 격리 sink로 비교.
4. **Post-cutover 24h**: 실제 운영 workload의 CPU/RSS/GC/I/O/오류율을 Node 최종 24시간 기준선과 비교.

각 결과에는 반드시 `comparableForFinalDecision`과 미이관 기능 목록을 기록한다.

## 현재 참고 기준선

| 기준선 | Spring image | Spring idle RSS 중앙값 | Spring idle CPU 중앙값 | 최종 비교 가능 |
|---|---:|---:|---:|---|
| domain foundation | 248,818,498 B | 157.9 MiB | 0.055% | 아니오 |
| sector/narrative domain | 248,873,318 B | 159.4 MiB | 0.055% | 아니오 |
| narrative/rotation live parity slice | 248,905,335 B | 174.9 MiB | 0.070% | 아니오 |
| narrative public read API | 248,936,650 B | 181.5 MiB | 0.055% | 아니오 |
| research theme/sector catalog read API | 248,990,007 B | 185.25 MiB | 0.055% | 아니오 |
| research theme/sector detail read API | 249,028,237 B | 199.7 MiB | 0.060% | 아니오 |
| crypto public read API + gzip + bounded stale refresh | 249,134,676 B | 183.5 MiB | 0.040% | 아니오 |
| company search/summary public read API + bounded key cache | 249,166,807 B | 184.0 MiB | 0.050% | 아니오 |
| company full detail public read API + bounded stale/payload cache | 249,189,365 B | 197.4 MiB | 0.045% | 아니오 |
| company fundamentals direct SEC streaming + Score/Buy Score parallel-run | 249,249,149 B | 221.15 MiB | 0.050% | 아니오 |
| company identity direct SEC ticker map + successor continuity | 249,268,811 B | 222.9 MiB | 0.055% | 아니오 |
| company quote direct Yahoo + separate quote parity | 249,288,347 B | 224.5 MiB | 0.065% | 아니오 |
| company analyst persisted evidence + expectations parity | 249,307,686 B | 229.3 MiB | 0.055% | 아니오 |
| company analyst direct Yahoo consensus + persisted history | 249,337,414 B | 230.1 MiB | 0.060% | 아니오 |
| isolated company analyst history writer + scheduler | 249,358,377 B | 226.7 MiB | 0.060% | 아니오 |
| company analyst history shadow-preferred internal read | 249,371,261 B | 234.3 MiB | 0.050% | 아니오 |
| company direct Yahoo price history + bottom/reversal parity | 249,444,291 B | 236.4 MiB | 0.065% | 아니오 |
| company direct SEC submissions + filing metadata parity | 249,490,548 B | 235.2 MiB | 0.090% | 아니오 |
| company direct SEC filing detail + Exhibit/IR material shadow | 249,547,381 B | 250.0 MiB | 0.050% | 아니오 |
| company bounded SEC PDF slide extraction + coverage gate | 253,331,811 B | 295.3 MiB | 0.070% | 아니오 |
| company structured guidance parsing parity | 253,366,834 B | 236.6 MiB | 0.090% | 아니오 |
| company direct SEC actual revenue-mix parity | 253,435,848 B | 374.7 MiB | 0.065% | 아니오 |
| company detail actual-first revenue-mix shadow composition | 253,459,076 B | 370.05 MiB | 0.050% | 아니오 |
| Java 21 LTS + Spring Boot 4.1 balanced runtime | 235,553,993 B | 301.0 MiB | 0.140% | 아니오 |

Node는 동일 시간창의 실제 운영 workload이고 Spring은 회사 read-only 분석 조각과 격리 analyst-history만
이관한 shadow이므로 위 수치로 런타임 우열을 결론 내리지 않는다. production 전체
수집기·writer·스케줄러와 동일 workload가 이관된 뒤에 최종 비교한다.
세 번째 행도 전체 read-only API parity가 아니라 Node snapshot을 소비하는 제한된 dual-run slice다.
세부 수치는 `migration/baseline/spring-shadow-research-parity-baseline.json`에 기록한다.
첫 공개 read API의 계약·HTTP·JVM 수치는
`migration/baseline/spring-shadow-narrative-public-read-baseline.json`에 기록한다.
Research theme/sector 목록 계약과 3회 반복 HTTP 수치는
`migration/baseline/spring-shadow-research-catalog-public-read-baseline.json`에 기록한다.
Research theme/sector 상세의 33개 live 계약, 3회 반복 HTTP, JVM/컨테이너 수치는
`migration/baseline/spring-shadow-research-detail-public-read-baseline.json`에 기록한다.
Crypto 목록/상세의 8개 live 계약, 기존 Research 5개 회귀, loopback/identity/gzip HTTP와
JVM/컨테이너 수치는
`migration/baseline/spring-shadow-crypto-public-read-baseline.json`에 기록한다.
기업 검색/요약의 경계값 계약, bounded key cache, loopback identity/gzip HTTP와 JVM/컨테이너 수치는
`migration/baseline/spring-shadow-company-directory-public-read-baseline.json`에 기록한다.
기업 전체 상세의 25개 최상위/중첩 계약, 다종목·404, bounded stale/payload cache,
loopback identity/gzip HTTP와 JVM/컨테이너 수치는
`migration/baseline/spring-shadow-company-detail-public-read-baseline.json`에 기록한다.
SEC 직접 fundamentals 정규화, Score/Buy Score live parity, cold/fresh HTTP와 JVM/컨테이너 수치는
`migration/baseline/spring-shadow-company-fundamentals-parity-baseline.json`에 기록한다.
SEC ticker directory 직접 해석, 48종목 identity parity, successor CIK 연속성과 배포 리소스 수치는
`migration/baseline/spring-shadow-company-identity-parity-baseline.json`에 기록한다.
Yahoo 현재가 직접 수집, 48종목 quote/fundamentals/Score/Buy Score parity, 반복 HTTP와 JVM/컨테이너 수치는
`migration/baseline/spring-shadow-company-quote-parity-baseline.json`에 기록한다.
Node 영속 analyst evidence의 read-only 직접 파싱, 48종목 expectations/Score/Buy Score parity,
정확한 30일 revision 정책과 배포 리소스 수치는
`migration/baseline/spring-shadow-company-analyst-expectations-parity-baseline.json`에 기록한다.
Yahoo current analyst consensus 직접 수집, 실패 시 영속 snapshot fallback, read-only history 결합,
48종목 current consensus/expectations/Score/Buy Score parity와 배포 리소스 수치는
`migration/baseline/spring-shadow-company-analyst-direct-yahoo-parity-baseline.json`에 기록한다.
격리 analyst history writer/scheduler의 원자적 쓰기, 첫 seed, 재시작 영속성, legacy 원본 불변성과
48종목 회귀·배포 리소스 수치는
`migration/baseline/spring-shadow-company-analyst-history-writer-baseline.json`에 기록한다.
Analyst history dual-compare와 shadow-preferred 선택, legacy fallback, 48종목 회귀·staged 배포 수치는
`migration/baseline/spring-shadow-company-analyst-history-read-cutover-baseline.json`에 기록한다.
Yahoo daily 가격·거래량 direct read, chart/marker와 바닥·반전 정책 48종목 parity, 기존 company/public
회귀 및 홈서버 배포·성능 수치는
`migration/baseline/spring-shadow-company-price-signal-parity-baseline.json`에 기록한다.
SEC submissions profile·최근 filing metadata direct read, earnings-related 8-K 분류, CIK 승계,
기존 company/price/public 회귀 및 홈서버 배포·성능 수치는
`migration/baseline/spring-shadow-company-submissions-parity-baseline.json`에 기록한다.
SEC accession index 직접 조회, Exhibit 99.x/IR 자료 추적, bounded HTML/TXT 요약, 6-K 외국 발행사 보강,
기존 submissions/company/price/public 회귀 및 홈서버 배포·성능 수치는
`migration/baseline/spring-shadow-company-filing-detail-parity-baseline.json`에 기록한다.
공식 SEC PDF 슬라이드의 bounded 본문 추출, PDF coverage gate, 실데이터 probe와 동일 회귀 수치는
`migration/baseline/spring-shadow-company-filing-pdf-parity-baseline.json`에 기록한다.
SEC attachment 기반 guidance 구조화, 방향 판정과 오탐 억제 회귀 수치는
`migration/baseline/spring-shadow-company-guidance-parity-baseline.json`에 기록한다.
SEC Inline XBRL actual revenue-mix와 additive reconciliation 수치는
`migration/baseline/spring-shadow-company-revenue-mix-parity-baseline.json`에 기록한다.
actual-first/legacy-fallback 회사 상세 shadow 조합과 공개 계약 불변 수치는
`migration/baseline/spring-shadow-company-detail-revenue-mix-shadow-baseline.json`에 기록한다.

Crypto identity LAN benchmark는 매 요청 새 TCP 연결을 만드는 표준 라이브러리 측정에서 Spring
목록 p95 tail이 높았지만, 같은 시점 Actuator 처리 평균과 홈서버 loopback p95는 각각 낮았다.
따라서 애플리케이션 처리와 LAN 전송을 분리해 기록한다. 실제 브라우저의 `Accept-Encoding: gzip`
경로에서는 JSON 정보량을 유지한 채 wire bytes와 p50/p95/p99가 모두 개선됐다.
실제 fresh TTL 만료 검증에서는 catalog 요청이 `46.211ms`에 stale 성공값을 반환한 동안 Node의
collector/derived refresh가 약 `27.626s` 백그라운드에서 수행됐고, 완료 후 새 응답이 다시 byte-exact로
교체됐다. 최초 성공값이 없는 재배포 직후 cold miss는 이 보호를 받을 수 없어 read-only warm-up을
배포 검증 단계에 포함한다.

Company 검색/요약은 홈서버 loopback 100건·동시성 10·3회 측정에서 Spring p50과 처리량이 모두
Node보다 좋았다. 새 연결과 호스트 스케줄링 tail이 섞인 검색 p95는 Node/Spring `144.307/152.440ms`로
근접했으므로 별도 동시성 1 측정을 수행했고, 이때 검색 p95는 `2.993/1.949ms`, 요약 p95는
`3.203/1.676ms`였다. 요약 gzip 협상 시 Node는 identity `1,082B`를 유지했고 Spring은 같은 JSON을
`504B`로 전송했다. 전체 계산·수집 소유권이 이관되지 않아 이 결과만으로 최종 런타임 우열을 판정하지 않는다.

Company 전체 상세 NVDA warm cache는 홈서버 loopback 100건·동시성 10·3회 중앙값에서 Node/Spring
p50 `23.209/9.420ms`, p95 `30.017/16.539ms`, 처리량 `403.691/956.252 req/s`였다. 동시성 1
200건에서도 p95 `3.891/2.086ms`였고 오류는 없었다. gzip 협상 시 Spring wire bytes는
`30,664B -> 8,446B`로 줄었으며 해제 후 Node identity bytes와 완전 일치했다. 상세 계산·수집·영속화는
아직 Node가 소유하므로 이는 warm read bridge/직렬화 성능이며 최종 런타임 비교값이 아니다.

Company fundamentals parallel-run은 direct SEC ticker-map/Company Facts cold miss와 fresh hit를 분리한다. cold 수치는 SEC
네트워크 지연을 포함하므로 계산 성능으로 해석하지 않고, fresh hit와 고정 evidence domain benchmark를
별도로 기록한다. 현재가와 current analyst consensus는 Yahoo에서 직접 읽고, 30일 revision용 history만
read-only source-cache evidence에서 읽어 Spring 정책으로 계산한다. 이 시점에는 analyst history writer와
Yahoo price history 기반 바닥·반전 신호가 아직 Node 소유였으므로 이 milestone 역시 전체 company pipeline의 최종
런타임 비교값이 아니다.

Analyst expectations 단계의 홈서버 warm 48종목 합계는 `204.636ms`, 중앙값 `3.628ms`였고 전 항목이
`48/48` 일치했다. 내부 NVDA endpoint 100건·동시성 10·3회 중앙값은 처리량 `395.617 req/s`,
p50 `10.094ms`, p95 `147.541ms`, p99 `172.780ms`, 오류 0이었다. 직전 quote 단계보다 응답에 expectations
비교가 추가되어 p50은 증가했지만 p95/p99는 개선됐다. 10회 idle 표본의 Spring RSS 중앙값은
`229.3MiB`, CPU 중앙값은 `0.055%`; Node는 같은 시간창에 전체 운영 workload를 처리했으므로 직접 우열
판정에는 사용하지 않는다.

Direct analyst 단계는 영속 consensus 파일을 제거한 격리 환경에서도 AAPL current consensus를 Yahoo에서
직접 수집해 legacy와 일치함을 확인했다. 홈서버 cold 48종목은 모든 비교 항목 `48/48`, 중앙값
`736.247ms`였고 첫 전역 7종목 Yahoo batch가 포함된 최대 지연은 `5,041.218ms`였다. warm loopback 합계는
`163.770ms`, 중앙값 `2.622ms`였다. 내부 NVDA endpoint 100건·동시성 10·3회 중앙값은 처리량
`391.113 req/s`, p50 `8.677ms`, p95 `158.922ms`, p99 `186.825ms`, 오류 0이었다. 같은 시간창 10회
표본의 Spring RSS/CPU 중앙값은 `230.1MiB/0.060%`이며 Node는 전체 운영 workload를 처리 중이므로 최종
우열 비교값이 아니다.

Analyst history writer 단계는 홈서버 최초 실행에서 7종목을 모두 legacy history로 seed한 뒤 direct Yahoo
값을 기록했고, 재시작에서는 `seeded=0`으로 전용 volume만 재사용했다. 두 실행 전후 shadow `value`는
`7/7`, legacy source-cache의 281개 history 파일은 `281/281` 해시가 동일했다. writer 추가 뒤에도 48종목
identity/quote/analyst/expectations/fundamentals/Score/Buy Score는 모두 `48/48`, 기존 공개 API는 `8/8`
바이트 일치했다. 내부 NVDA endpoint 100건·동시성 10·3회 중앙값은 처리량 `408.503 req/s`, p50
`9.027ms`, p95 `142.670ms`, p99 `168.326ms`, 오류 0이었다. 동일 시간창 10회 표본의 Spring RSS/CPU
중앙값은 `226.7MiB/0.060%`, Node는 `259.1MiB/0.685%`였다. 전용 volume은 7개 파일 `16.8kB`, container
writable layer는 `32,768B`였지만 production Node writer와 전체 I/O workload는 아직 비교하지 않았다.
이후 parity가 기존 Node 회사 GET을 호출하자 요청한 48종목 중 47개 legacy history가 Node 자체 정책으로
갱신됐다(NVDA만 불변, 요청하지 않은 파일 변경 0). 따라서 이 후속 변경은 Spring writer 원본 불변성
판정에서 제외하고, read-only mount와 parity 호출 전후의 단계별 해시로 쓰기 주체를 구분한다.

Analyst history read 컷오버 단계는 홈서버에서 `dual-compare`를 먼저 통과한 뒤 지정 7종목만
`shadow-preferred`로 전환했다. 최종 48종목은 모든 계산 flag `48/48`, history source는 shadow `7`/legacy
`41`, 공개 API는 `8/8` 상태·바이트 일치였다. warm 48종목 합계는 `326.394ms`, 중앙값 `5.272ms`였다.
내부 NVDA endpoint 100건·동시성 10·3회 중앙값은 처리량 `389.990 req/s`, p50 `9.083ms`,
p95 `148.956ms`, p99 `181.149ms`, 오류 0이고, 동시성 1의 200건 p95는 `2.897ms`였다. 10회 idle 표본의
Spring RSS/CPU 중앙값은 `234.3MiB/0.050%`다. 두 번의 Spring-only 재생성에서 shadow `value` 해시는
`7/7` 불변, `seeded=0`, 임시 파일 0, 로그 WARN/ERROR/Exception 0이었다. 이 단계도 내부 parity의 일부
입력만 Spring store로 바꾼 것이며 공개 회사 계산과 production writer는 Node가 소유하므로 최종 런타임
판정에는 사용하지 않는다.

Price signal parity 단계는 Yahoo chart를 Spring이 직접 읽어 모든 48종목에서 최근 260개 close/date,
marker, 가격 context 점수·상태, 확신형 바닥과 반전 확인을 각각 `48/48` 일치시켰다. post-deploy 가격
parity 전체의 endpoint duration 합계/중앙값/최대는 `9,908.219/196.606/438.445ms`였고, 기존 company
research도 `48/48`, 공개 API도 `8/8` 상태·바이트 일치했다. NVDA warm 내부 endpoint 100건·동시성
10·3회 중앙값은 처리량 `401.182 req/s`, p50 `10.027ms`, p95 `159.861ms`, p99 `170.703ms`, 오류 0;
동시성 1의 200건 p95는 `2.896ms`였다. 10회 표본의 Spring RSS/CPU 중앙값은
`236.4MiB/0.065%`, Node는 `172.4MiB/0.655%`였지만 Node만 전체 production workload를 수행하므로 최종
우열 비교값이 아니다. 가격 계산을 격리하기 위해 세 비가격 scalar는 legacy projection을 양쪽에 동일하게
주입했고, public 계산·알림·나머지 enrichment도 아직 Node 소유다.

SEC submissions parity 단계는 `data.sec.gov/submissions/CIK##########.json`을 Spring이 직접 streaming
parse해 48종목 profile을 일치시키고 최근 filing 100개를 보존하되 legacy 비교 projection은 최근 10개를
`48/48` 일치시켰다. 홈서버 전체 endpoint duration
합계/중앙값/최대는 `17,912.793/385.798/695.914ms`였고, XOM은 registry CIK `0002115436`과 현재
serving submissions CIK `0000034088`을 분리해 검증했다. 기존 price/company/public 회귀도
`48/48`, `48/48`, `8/8`을 유지했다. NVDA warm 내부 endpoint 100건·동시성 10·3회 중앙값은 처리량
`392.738 req/s`, p50 `9.228ms`, p95 `153.613ms`, p99 `188.619ms`, 오류 0; 동시성 1의 200건
p95는 `2.479ms`였다. 10회 표본의 Spring RSS/CPU 중앙값은 `235.2MiB/0.090%`, Node는
`173.6MiB/0.660%`였지만 Node만 전체 production workload를 수행하므로 최종 우열 비교값이 아니다.

SEC filing detail/IR 단계는 공식 accession index를 Spring이 직접 읽고 8-K Item 2.02, 8-K, 6-K 순으로
최대 3개 index를 검사했다. 홈서버 대표 12종목은 migration-ready/legacy metadata 보존이 각각 `12/12`,
직접 발견 개선도 `12/12`였고, attachment `40개` 중 HTML/TXT `35개`를 요약했다. TSM과 ASML의 6-K도
각각 `2개/8개` 자료를 발견했다. AAPL 1건은 현재 원문 재파싱 위치가 과거 영속 summary와 달라 summary
문자열 일치만 `11/12`였으나 자료 identity와 metadata는 보존되어 비차단 차이로 기록했다. submissions,
company research, price signal 회귀는 각각 `48/48`, 공개 API는 `8/8` 상태·바이트 일치를 유지했다.

같은 홈서버의 warm 내부 endpoint 100건·동시성 10·3회 중앙값은 처리량 `244.949 req/s`, p50
`22.972ms`, p95 `177.236ms`, p99 `189.251ms`, 오류 0이었다. 동시성 1의 200건은 p50/p95/p99
`10.584/12.500/17.036ms`, 처리량 `90.494 req/s`, 오류 0이었다. 10회 동일 시간창 표본의 Spring
RSS/CPU 중앙값은 `250.0MiB/0.050%`, Node는 `184.65MiB/4.455%`였지만 Node만 production 요청과
백그라운드 수집을 담당하므로 최종 런타임 우열 비교값이 아니다. 이 시점에는 PDF 슬라이드 본문,
구조화 guidance, segment/geography enrichment, public orchestration과 production scheduler/알림이 Node 소유였다.

PDF 슬라이드 단계는 PDFBox 3.0.8을 outbound adapter에만 추가하고 공식 SEC ADP Investor Day 2025
`96페이지/4,466,113B` 문서로 경로를 검증했다. 3만 자 상한에서 `92페이지/29,598자`를 추출했고,
홈서버 cold/warm은 `1,727.100/146.647ms`였다. warm PDF probe 100건·동시성 10·3회 중앙값은 처리량
`81.239 req/s`, p50/p95/p99 `111.403/169.708/248.471ms`, 오류 0이었다. 동일 filing-detail endpoint는
응답에 PDF coverage 필드를 추가한 뒤 100건·동시성 10·3회 중앙값 처리량 `311.626 req/s`,
p50/p95/p99 `20.211/80.543/85.130ms`, 오류 0이었다. 동시성 1·200건·3회의 p95 중앙값은 `23.833ms`다.

최종 10회 표본의 Spring RSS/CPU 중앙값은 `295.3MiB/0.070%`, JVM used/committed는
`180,393,072/235,610,112B`, GC pause `32회/합계 0.338s/최대 0.121s`다. image는 PDFBox 추가로
`249,547,381B -> 253,331,811B`(`+3,784,430B`)가 됐고 writable layer `32.8kB`, shadow volume
`17,228B/7파일`은 유지됐다. Node만 production workload를 수행하므로 이 수치도 최종 언어 런타임
우열 비교값은 아니다. 구조화 guidance, segment/geography, public orchestration과 production side effect는
아직 Node가 소유한다.

구조화 guidance 단계는 같은 bounded HTML/TXT/PDF 본문에서 revenue, margin, CAPEX, FCF를 순수 domain
policy로 파싱했다. local/home 대표 12종목 모두 extraction coverage와 migration-ready `12/12`, eligible/
analyzed material `41/41`, relevant `23`, structured material/metric `17/24`였다. NVDA Q1 FY27의
revenue `$89.18B-$92.82B`, margin `74.4%-75.5%`, TSM revenue `$44.6B-$45.8B`, margin `65%-67%`를
exact 검증했다. META prior CAPEX range 증가는 `raised/mixed`, XOM guidance 일치는 `affirmed`로 분류했고
ASML safe-harbor margin 오탐 한 건을 제거했다.

최종 warm filing/guidance endpoint 100건·동시성 10·3회 중앙값은 처리량 `42.712 req/s`, p50/p95/p99
`209.932/337.750/464.200ms`, 오류 0이었다. 동시성 1·200건은 p50/p95/p99
`80.289/87.931/105.686ms`, 처리량 `12.258 req/s`, 오류 0이다. 응답은 전체 본문 없이 `7,067B`다.
현재 internal parity는 캐시된 본문을 매 요청 다시 파싱하므로 public cutover 전 parsed-summary 영속화 또는
bounded infrastructure cache가 필요하다.

최종 10회 idle 표본의 Spring RSS/CPU 중앙값은 `236.6MiB/0.090%`, Node는
`158.85MiB/0.665%`다. JVM used/committed는 `153,407,168/197,459,968B`, GC pause는
`162회/합계 0.297s/최대 0.006s`였다. image는 직전 PDF milestone보다 `35,023B` 증가한
`253,366,834B`, writable layer는 `32,768B`다. Node는 production과 scheduler를 모두 담당하고 Spring은
shadow만 담당하므로 최종 런타임 우열 비교값이 아니다. 공개 API `8/8`은 상태·바이트 일치했고 가격 신호는
`48/48`이다. submissions `45/48`과 research `47/48`은 각각 실시간 SEC 행 추가와 AAPL analyst refresh
경계로 별도 증거화했으며, guidance 코드의 회귀는 아니다.

SEC actual revenue-mix 단계는 최신 10-Q와 최신 10-K/20-F/40-F Inline XBRL에서 segment/product/
end-market/geography 값을 직접 읽고 consolidated revenue와 reconcile해 비중을 생성했다. 로컬·홈서버
대표 12종목은 migration/direct coverage/percentage/legacy coverage가 모두 `12/12`, actual segment
`12/12`, geography `11/12`, dimensional fact `829개`였다. INTU geography는 양쪽 모두 미공시이며,
TSM은 customer market과 geography, ASML은 product와 geography, XOM은 aggregate revenue qualifier가
붙은 geography를 실제 값으로 복원해 각 비중 합계 `100.0%`를 확인했다. 홈서버 전체 endpoint duration
합계/중앙값/최대는 `7,637.571/564.535/1,151.183ms`였다.

NVDA warm 내부 endpoint 100건·동시성 10·3회 중앙값은 처리량 `433.868 req/s`, p50/p95/p99
`17.680/46.934/52.983ms`, 오류 0, 응답 `2,521B`다. 최종 10회 idle 표본의 Spring RSS/CPU 중앙값은
`374.7MiB/0.065%`, Node는 `148.2MiB/0.685%`였다. 대형 ASML/TSM 문서와 48종목 regression cache가
warm 상태인 수치이며 Node만 production workload와 scheduler를 담당하므로 최종 언어 런타임 우열로
사용하지 않는다. JVM heap used/committed는 `206,562,440/213,438,464B`, GC pause는
`48회/합계 0.273s/최대 0.009s`다. image는 `69,014B` 증가한 `253,435,848B`, writable layer는
`32,768B`다.

가격 신호는 `48/48`, 공개 API는 cache refresh 후 `8/8` 상태·바이트 일치다. company research
`47/48`의 MSFT는 live Yahoo/history refresh 경계지만 total/buy score `73/74`가 같고, submissions
`45/48`의 JPM/GS/MS는 Node cache와 새 SEC structured-product filings 간 시간차다. Spring-only 재배포로
Node/프런트 ID와 restart count는 불변이고 Spring 로그 WARN/ERROR/Exception은 0이다. 상세 증거는
`migration/baseline/spring-shadow-company-revenue-mix-parity-baseline.json`에 기록한다.

Company detail revenue-mix shadow 단계는 기존 공개 상세를 수정하지 않고 actual 결과를 세 revenue-mix
필드에만 조합한다. 로컬과 홈서버 대표 12종목 모두
contract-compatible/serving-snapshot-matched/shadow-serve-ready `12/12`,
actual segment `12/12`, geography `11/12`이며 INTU geography는 `unavailable`이다. 별도 실데이터 probe에서
UNH geography는 `legacy-fallback`으로 기존 값을 정확히 보존했다. WMT는 브랜드 segment의 `US/International`
문구를 geography로 오인하던 domain 휴리스틱을 수정한 뒤 segment와 실제 `United States/Non-U.S.` geography가
각각 100.0%로 분리됐다. detail/parity 사이 legacy refresh 경계는 `servingSnapshotMatched=false`로
cutover readiness를 차단한다. 공개 company 상세 8종목은 Node/Spring 상태·byte가 `8/8` 완전 일치했다.

NVDA warm shadow endpoint 200건·동시성 10은 로컬에서 처리량 `740.026 req/s`, p50/p95/p99
`9.782/40.341/56.529ms`, 홈서버 LAN에서 `573.759 req/s`, `15.016/23.865/50.344ms`였고
오류는 모두 0, 응답은 `2,097B`다. 대표 12종목 cold/warm 혼합 합계/중앙값/최대는 로컬
`9,036.452/624.201/1,788.072ms`, 홈서버 `8,182.852/612.268/1,307.591ms`였다.

검증 후 10회 idle 표본의 Spring RSS/CPU 중앙값은 `370.05MiB/0.050%`, Node는
`142.8MiB/0.655%`다. JVM used/committed는 `284,125,880/302,624,768B`, GC pause는
`62회/합계 0.293s/최대 0.016s`다. image는 `253,435,848B -> 253,459,076B`(`+23,228B`)이고
legacy source-cache mount는 계속 read-only다. Node만 production workload와 scheduler를 담당하므로
이 수치는 최종 언어 런타임 우열이 아니다. direct revenue mix `12/12`, company research `48/48`,
price signal `48/48`, 공개 API `8/8`을 재확인했고 Node/프런트 container ID와 restart count는 불변이다.
상세 증거는 `migration/baseline/spring-shadow-company-detail-revenue-mix-shadow-baseline.json`에 기록한다.

Java 21 런타임 전환은 Spring Boot 4.1과 응답 계약을 그대로 유지하고 Maven release,
Docker builder/runner, Actuator runtime metadata만 21 LTS로 일치시켰다. Temurin 21 builder에서 전체
`251` tests와 class major version `65`를 검증했고, 홈서버 실데이터 shadow/direct mix `12/12`,
fallback `2/2`, company research·price signal `48/48`, public company byte `8/8`을 유지했다.

동일 시점 Java 25 rollback/Java 21 이미지를 같은 홈서버에 read-only로 기동하고 실행 순서를
교차해 loopback 5회·회당 500건·동시성 10으로 비교했다. Java 21의 회사 상세는
처리량 `+2.01%`, p50/p95 `-8.65%/-12.00%`, revenue-mix shadow는 처리량 `+9.37%`,
p50/p95/p99 `-10.18%/-10.52%/-3.00%`였고 양쪽 오류는 0이다. 회사 상세 p99의 `+1.14%`는
원 응답 30,664B에서 발생한 tail 노이즈로 보며 p50/p95와 처리량 방향은 일치했다.

image는 `253,459,076B -> 235,553,993B`(`-17,905,083B`, `-7.06%`), 각 런타임을 단독
warm 상태로 측정한 Spring RSS 중앙값은 `376.9MiB -> 301.0MiB`(`-75.9MiB`, `-20.14%`)다.
idle CPU `0.070% -> 0.140%`는 절대차 `0.07%p`로 홈서버 background 작업 노이즈 범위이며 우열
판정에 사용하지 않는다. Node가 아직 production workload를 담당하므로 최종 런타임 결론은
full cutover 후 24시간 동일 workload 비교까지 보류한다. 상세 증거는
`migration/baseline/spring-shadow-java21-runtime-baseline.json`에 기록한다.

Snapshot/history 공개 read 단계는 Node 계산·history 파일을 source of truth로 둔 채 Spring이 검증된
application projection, bounded stale-while-revalidate와 serialized payload cache를 소유한다. 홈서버
실데이터 8개 case에서 status/content-type/body가 모두 byte-exact였고 snapshot `186,643B`, NASDAQ history
`60,231B`, 정상 series `504B`를 정보 손실 없이 보존했다. Spring snapshot gzip은 해제 후 Node identity
body와 같은 SHA-256이며 기존 company/research/crypto/narrative 공개 경로도 `5/5` 회귀를 통과했다.

같은 홈서버 loopback warm 상태에서 snapshot은 200건·동시성 10으로 Node/Spring 처리량
`222.581/577.700 req/s`, p50/p95 `36.721/55.410ms` 대 `9.294/23.022ms`였다. history-series는
500건·동시성 10에서 처리량 `259.064/950.433 req/s`, p50/p95 `34.480/44.219ms` 대
`6.896/13.993ms`였고 오류는 모두 0이다. 검증 직후 10회 표본의 Spring RSS/CPU 중앙값은
`210.75MiB/0.19%`다. 이 차이는 동일 immutable projection과 JSON payload를 재사용하는 read bridge
효과이며, Node의 계산·수집·persistence가 아직 남아 있으므로 최종 언어 런타임 우열로 해석하지 않는다.
상세 증거는 `migration/baseline/spring-shadow-market-read-public-baseline.json`과
`migration/benchmark-results/market-read-public/`에 기록한다.

초기 전체 `JsonNode` 방식은 40개 CIK 연속 파싱 후 실제 장기 데이터보다 큰 임시 객체가 old generation으로
승격되어 RSS `783.4MiB`, heap committed `682.7MB`, GC pause 합계 `1.355s`를 기록했다. 필요한 taxonomy/tag만
token streaming하고 정규화가 관측할 point만 보존한 최종본은 동일 `40/40` 결과를 유지하면서 각각
`203.6MiB`, `105.6MB`, `0.082s`로 감소했다. 이후 8개 동시 cold 요청도 fetch/parse 동시성 2 제한 아래
`8/8` 일치했고 오류·재시작은 없었다. HTTP warm-up까지 마친 10회 idle RSS 중앙값은 `221.15MiB`다.
