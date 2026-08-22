# 장애 재발 방지 체계와 실패 카탈로그

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-17**
- 실시간 탐지: **1분**
- 영속 데이터 무결성 검사: **1분**
- 전체 관측 감사: **매일 07:20 KST**

## 1. 원칙

과거 장애를 로그에만 남기면 다시 발생한다. 한 번 발생한 장애는 다음 네 층 중 최소 두 층, 금융
의사결정 오류는 가능하면 세 층 이상으로 고정한다.

1. Domain/application 정책
2. PostgreSQL constraint/transaction/unique key
3. 회귀 테스트와 golden fixture
4. 1분 integrity/host monitor와 일일 cross-check

장애 설명에는 “수정함”이 아니라 **원인 → 오염 가능 데이터 → 영구 가드 → 탐지 fingerprint → 복구**가
있어야 한다.

## 2. 반복 장애 카탈로그

| ID | 과거/가능 장애 | 금융 영향 | 영구 차단 | 실시간/일일 탐지 |
|---|---|---|---|---|
| FIN-001 | 목표가 업사이드 변화를 EPS revision으로 오인 | 주가 하락만으로 촉매 상향 | EPS trend와 targetUpsideChange 분리, 0/부호전환 결측 | analyst invalid/empty/latest audit |
| FIN-002 | 최신 filing 미반영 재무로 Company/B Score 유지 | stale 기업이 BUY | CURRENT filing readiness, 200/400일 gate, V12/V15 | NONCURRENT_SCORED, summary oldest age |
| FIN-003 | 미조정 split이 폭락·바닥으로 인식 | 거짓 확신형 바닥 | corporate-action factor quality policy | price signal completeness/discontinuity |
| FIN-004 | 바닥 점수를 반전 점수로 복제 | 급락 자체가 STRONG reversal | 독립 가격구조·OBV/VWAP 결합 | policy unit/golden, BUY evidence |
| FIN-005 | 과거/현재 가격을 혼합한 signal date | 신호일 가격과 조회 가격 불일치 | immutable dated points, no-lookahead walk-forward | future/duplicate/date alignment audit |
| FIN-006 | 가격수익률로 장기 섹터 비교 | 고배당 섹터 왜곡 | adjusted-close total return V2 | 17 series, min 2,000, latest alignment |
| FIN-007 | 구형 백테스트를 현재 성능처럼 노출 | 적중률 과대해석 | methodology version, legacy/reference 격리 | API smoke methodology assertions |
| DATA-001 | 일부/0건 저장을 SUCCESS 기록 | stale source가 정상으로 보임 | MarketCollectionReport + V16 check | HARD/STALE_COLLECTION |
| DATA-002 | 한 ticker 갱신이 전체 batch를 정상처럼 표시 | 274개 stale 은폐 | oldest summary, expected exact rows | 1분 DataIntegrityPolicy |
| DATA-003 | partial score/signal bundle 저장 | 다른 시점 하위점수 혼합 | 원자 transaction + V15 num_nonnulls | INCOMPLETE_* metrics |
| DATA-004 | 빈 최신 analyst snapshot | revision이 0/중립처럼 보임 | empty latest 금지·stale fallback 제한 | EMPTY_LATEST_ANALYST_ROWS |
| DATA-005 | retired alias/구 ticker 재유입 | 잘못된 기업 목록·점수 | V12/V13 purge/current query, canonical mapping | RETIRED_OR_ALIAS, MRSH exact row |
| DATA-006 | 미래/NaN/중복 관측 | 파생지표 오염 | domain validation + unique/check | FUTURE/NONFINITE/DUPLICATE metrics |
| DATA-007 | 13F 천달러/달러 단위 오류 | 기관 매집 규모 왜곡 | parser normalization + positive DB check | suspicious implied-price groups |
| DATA-008 | object body와 pointer 불일치 | 손상 projection 공개 | version+SHA/ETag/size pointer protocol | DANGLING_OBJECT_POINTER |
| DATA-009 | Yahoo FX 한 key 순간 공백이 장애/회복 알림을 반복 | alert fatigue, 실제 장애 경보 무시 | host-set 1회 재시도 + fresh prior 30분 bounded 허용, DEGRADED 보존 | key 포함 ERROR, HARD_COLLECTION은 stale/부재 시 승격 |
| DATA-010 | Yahoo가 `JPY=X`를 `USDJPY=X`로 반환해 심볼 불일치 오판 | FX 거시 입력 간헐 결측 | 동일 방향 USD-base alias만 strict allowlist | 5분 collection status, alias fixture |
| DATA-011 | NAAIM 3개월 지연 public 값을 현재 positioning으로 사용 | 심리점수 stale 오염 | 최신 행 선택 후 14일 gate, policy-unavailable 분류, fail-closed | DB DEGRADED·제품 LIMITED·coverage 75% |
| DATA-012 | 부분 coverage 또는 표준/전략 혼합을 V3 원장에 저장 시도 | 섹터 순위 OOS 표본 누락·왜곡 | capture 11개 standard gate + transaction + append-only DB 제약 | CURRENT_SECTOR_ROTATION_READY/INVALID_RUN 1분 metric |
| DATA-013 | Yahoo가 기업분할 반영 중 pre/post basis를 번갈아 반환하고 invalid history가 cache됨 | 가격·바닥 bundle 장시간 격리 또는 거짓 바닥 | split event+직전/직후 비율 ±15% 이중 확인 후 미조정 OHLCV만 현재 basis로 정규화, cache 전 domain 검증, 마지막 정상 cache 보존, 결측 bundle 다음 refresh 최우선 | COMPANY_PRICE_SIGNAL_ROWS + provider degraded ticker |
| DATA-014 | 성공한 가격 갱신이 `계산 대기` 요약·가격축 제외 합성점수를 남김 | 현재 차트와 stale 문구/점수의 자기모순 | price projection이 metric 갱신 직후 합성점수·요약·근거·주의를 원자적으로 재구성 | projection 회귀 테스트 + INCOMPLETE_PRICE_SIGNAL |
| DATA-015 | retained stale 파생값을 Telegram·주간 리포트가 현재값처럼 재사용 | 이미 만료된 유동성 경고·상태 발송 | notification/review anti-corruption adapter에서 `eligibleForSignals=false` 제외, 원천 max-age 계약 재사용 | stale derived·분기 270일 경계 회귀 테스트 |
| DATA-016 | 섹터 catalog만 신규 종목을 보충하고 object/file read·analyst universe·상세 seed lifecycle을 함께 확장하지 않음 | 전체기업 RBLX/EPD 누락 및 상세 502, retired 재유입, alias 응답 identity 오류, 실행 액션 last-valid 저하 | 단일 adapter registry를 sector/theme/object/file/analyst boundary에 적용, immutable artifact key와 현재 응답 ticker 분리, captured detail이 없는 신규 identity는 fail-closed Spring seed에서 자체 원천으로 enrichment, 구 점수 복사 금지 | company-selection E2E union=DB=API 정확히 277 + RBLX/EPD 공식 identity·filing·투자판정 검증 + adapter lifecycle 회귀 테스트 |
| OPS-014 | 배포 cutover가 `total=current>0`만 확인해 일부 기업이 빠진 자기일관 projection을 정상으로 승인 | 목록·점수 일부 누락 상태가 최신 image와 함께 공개 | 실행 컨테이너의 `DATA_INTEGRITY_EXPECTED_COMPANY_UNIVERSE`를 읽어 total/current 모두 정확히 일치할 때만 cutover, replacement 상세 enrichment도 bounded retry로 검증 | full/scoped deploy exact-count invariant + verify-home enriched-current-identity |
| OPS-015 | 기업/earnings membership이 현재 섹터 순환 query에 의존 | startup momentum 0/11에서 summary·analyst 배치 연쇄 실패, stale 무결성 경보 | raw `LoadResearchCatalogPort` membership으로 분리, current rotation 부족은 전용 예외·HTTP 503, neutral/captured fallback 금지 | raw-universe lifecycle tests + dynamic query import invariant + 실시간 ERROR fingerprint |
| NOTI-001 | refresh 실패 후 과거 BUY 후보 부활 | 잘못된 Telegram 진입 알림 | refresh failure→unavailable/HOLD, candidate fingerprint | CANDIDATE_DRIFT |
| NOTI-002 | 상태 저장과 발송 분리 | 누락/중복 startup·편입 메시지 | transactional outbox + lease/retry | outbox retry/dead/stuck |
| OPS-001 | scheduler rolling overlap | 중복 수집·발송·write race | JVM guard + PostgreSQL advisory lock | task logs, lock integration test |
| OPS-002 | 장시간 lock이 API DB pool 점유 | 전체 API 지연 | unpooled coordination source + semaphore 4 | endpoint latency, Hikari/trace |
| OPS-003 | container 기동 중 false incident | 불필요 경보 | 3분 bounded startup grace | host recurrence state machine |
| OPS-004 | 동일 ERROR 지속 중 Telegram flood | alert fatigue | normalized fingerprint, 5분 quiet re-arm | local recurrence state |
| OPS-005 | 로그만 보고 DB 오염을 놓침 | 조용한 금융 오류 방치 | 로그+metric+trace+DB cross-audit | 매일 full audit |
| OPS-006 | 로컬 디스크 고갈을 배포 후 검증에서야 발견 | cutover 성공 후 증빙 누락·운영 상태 오판 | 원격 변경 전 로컬 128MiB·홈서버 4GiB 최소 여유 preflight | deploy fail-fast + host disk audit |
| OPS-007 | rolling startup의 readiness 503을 사용자 API 5xx로 합산 | 정상 배포를 CRITICAL로 오탐 | app URI 5xx와 Actuator health 503 metric 분리 | audit에 두 수치 별도 보존 |
| OPS-008 | 장시간 backup `docker pause`가 Hikari housekeeper를 10분 지연 | scrape 단절·API/scheduler 정지 | 관계형 캡처만 기본 20초 bounded pause, MinIO mirror 전 unpause, EXIT trap | RUNTIME_PAUSED·Hikari WARN 1분 fingerprint |
| OPS-009 | startup 시 이전 source 상태를 collector보다 먼저 판정 | 이미 해소 가능한 과거 gap을 새 incident로 오탐 | collector 초기 실행 뒤인 5분에 첫 integrity 판정, 이후 1분 주기 유지 | startup 로그와 collection status 시각 교차검증 |
| OPS-010 | 같은 영속 무결성 fingerprint를 매분 WARN으로 기록 | Loki·일일 감사 noise와 실제 신규 오류 식별 지연 | 신규 ERROR·회복 INFO는 즉시, 지속 WARN은 첫 회와 30회마다, 중간은 DEBUG | fingerprint/activeChecks와 host monitor dedupe |
| OPS-011 | 배포 후 Prometheus 검증의 `curl | grep -q`가 pipefail에서 curl 23을 발생 | 정상 cutover를 실패로 오판하고 직전 image로 불필요 rollback | 응답을 임시파일에 완전히 저장한 뒤 metric 검사, 성공·실패 모두 정리 | scoped deploy invariant + 배포 exit/image 확인 |
| OPS-012 | 성공한 rollback 뒤 임시 image tag가 잔존 | 다음 정상 배포를 실패로 오판 | 복구된 production tag/container 유지 후 서버·클라이언트 rollback 임시 tag 제거 | 성공·rollback 양 경로 정리 invariant + home verify |
| OPS-013 | 홈서버 build/통합 테스트 포화 중 SSH keepalive 4회가 모두 지연 | production 변경 전 배포 세션 `Broken pipe`, 최신 수정 미반영 | deploy/verify/rsync에 15초 간격·40회 유예와 TCP keepalive를 동일 적용 | cutover invariant가 모든 배포 경로와 auto rsync 설정 검사 |
| OPS-016 | startup company summary·analyst history·후보 전수 스캔이 서로 다른 lock으로 동시에 실행 | readiness 직후 Yahoo/CPU 경쟁, cold API 17.2초, 수집 완료 지연 | 영속 snapshot 우선, startup 3분/15분/20분 순차 지연, 공통 `company:provider-heavy` slot, 후보 5분×6 bounded retry | shared-key/retry tests + lifecycle duration 로그 + `provider-heavy-scheduler-overlap` 일일 감사 fixture |
| OPS-017 | rolling cutover로 종료된 이전 JVM의 후보 scan start를 Loki가 보존 | 존재하지 않는 작업을 20분 stall CRITICAL로 오탐 | container `StartedAt` 이전 unmatched start는 `interruptedByRestart`로 분리, 현 JVM stall만 경보 | audit process-boundary fixture + current restart/health 교차검증 |
| DOC-001 | 코드 변경 후 문서/버전/주기 미갱신 | 잘못된 운영 판단·같은 실수 반복 | documentation verifier + CI/deploy gate | CI failure |

## 3. 영속 무결성 검사

Spring `DataIntegrityScheduler`는 startup collector가 durable source 상태를 갱신할 5분을 준 뒤, 1분마다
47개 stable metric을 평가한다.

### 정확히 일치해야 하는 값

- company rows/current calculation/price signal/analyst series: 각 277
- canonical MRSH: 1
- market collection source: 6
- sector price/total-return series: 각 16 + benchmark readiness 1
- 최근 7일 V3 sector rotation 11개 원자적 snapshot readiness: 1

### 0이어야 하는 값

- 범위 밖/부분/stale/future company score
- 근거 없는 BUY와 부분 price signal
- unavailable/retired current company
- hard/stale collection
- sector stale/misaligned/discontinuity
- V3 sector rotation 불완전 run
- future/nonfinite/duplicate market·analyst
- empty/stale analyst
- invalid 13F/단위 이상
- dangling pointer, candidate drift
- outbox retry/dead/stuck

incident fingerprint가 같으면 지속 장애를 중복 발송하지 않는다. 정상으로 회복하면 fingerprint를 비워 같은
장애가 재발할 때 다시 알린다. 지속 장애의 Spring WARN은 첫 지속 확인과 이후 30회(기본 주기에서는 약
30분)마다만 기록하고, 매분 검사는 계속 실행하되 중간 상태는 DEBUG로 남긴다. 이 제한은 검사·Telegram
전이를 줄이는 것이 아니라 동일 fingerprint의 로그 중복만 줄인다.

### 2026-08-10~11 MNST 가격 basis incident

- 최초 탐지: `2026-08-10T08:18:52Z`, 재탐지 `2026-08-11T00:26:39Z`
- fingerprint: `6d436e5d17bcf76f80703a1570a6c4bb8a8ece7b63a9a11bc713aeaaa9c04e84`
- 증거: Yahoo OHLCV에 약 2배 corporate-action형 인접 불연속, `COMPANY_PRICE_SIGNAL_ROWS 274/275`
- 영향: MNST의 가격·바닥 bundle과 실행 액션을 null/HOLD로 fail-closed했고 다른 274개에는 오염 없음
- 원인: domain은 basis 불연속을 올바르게 거부했으나 adapter가 거부 전 payload를 정상 cache로 저장했고,
  신규 split 과도기에는 Yahoo가 event와 아직 미조정인 과거 candle을 함께 반환할 수 있었음
- 영구 가드: adapter가 split event를 요청하고 event 비율과 실제 인접 종가 비율이 ±15% 안에서 일치할 때만
  행사 전 OHLC를 나누고 거래량을 곱한다. 이미 수정된 이력은 이중 보정하지 않는다. 그 후 domain quality
  policy를 cache 전에 재사용하며, event 누락·불일치는 fail-closed하고 결측 bundle은 refresh 최우선이다.
- 회귀 테스트: 신규 split 정규화/기수정 이력 이중보정 방지/event-가격 불일치 미보정, basis-break
  미cache/정상 payload 재시도, 결측 bundle stable priority, WARN cadence
- 데이터 repair: 불필요. `2026-08-11T04:46:27Z` 회복 후 현재 V5 가격 bundle `275/275`

### 2026-08-16 가격 projection 자기모순

- 증거: MNST 직접 split 정규화 후 현재 차트·확신형 판정은 존재했지만 상위 요약은 `계산 대기`, 합성점수는
  현재 가격 metric 갱신 전 값으로 남았음
- 영향: 원시 신호나 최종 액션 계산 오염이 아니라 상세 설명·탐색 합성점수의 시점 불일치
- 원인: `priceSignals` projection이 가격 metric을 교체하면서 `summary`, 가중 합성점수, reasons/cautions를
  재구성하지 않았음
- 영구 가드: 가격 metric 직후 8축 합성점수를 재정규화하고 현재 domain 판정으로 요약·주의 문구를 전부
  교체한다. pending 문구와 현재 신호가 한 projection에 공존하지 않는 회귀 테스트를 추가했다.
- 데이터 repair: 영속 V5 summary의 다음 refresh로 교체하며 schema repair는 없음

### 2026-08-16 표준 섹터 20종목 underfill

- 증거: 운영 `/api/research/sectors`에서 커뮤니케이션·에너지만 19개, 나머지 9개는 20개
- 원인: immutable cutover catalog의 EA·CTRA를 현재 universe에서 올바르게 제거했지만 같은 섹터의 신규
  종목을 보충하지 않아 UI 최소 20개 계약과 2개 기업 분석이 함께 빠짐
- 영구 가드: EA/CTRA를 alias 변환하지 않고 RBLX/EPD를 별도 current identity로 편입한다. captured
  점수는 복사하지 않으며 자체 SEC/Yahoo 수집과 V5 재계산 전까지 unavailable로 둔다. alias·retired·replacement
  정의는 `CurrentResearchUniverseTickerRegistry` 한 곳에서 섹터 상세와 전체기업 compatibility projection이
  함께 사용한다.
- 재발 탐지: `verify-home.sh`가 표준 11개, 섹터별 최소 20개, RBLX/EPD membership을 API에서 직접 검증하고
  일일 E2E가 catalog membership 합집합과 277개 DB/API를 대조한다. 또한 RBLX/EPD 상세를 직접 호출해
  HTTP 200과 profile/financial identity 일치를 증명한다.
- 후속 점검에서 발견한 누락: 첫 수정은 전체기업 목록까지 맞췄지만 captured detail artifact가 없는 두 신규
  종목의 `/api/company/{ticker}`가 502였다. 신규 replacement에 한해서 identity-only seed를 만들고 현재
  SEC/Yahoo/analyst/price enrichment가 모든 점수·판정을 채우도록 수정했다. seed 자체는 어떤 매수 액션도
  허용하지 않는다. 첫 seed에는 exchange/SIC가 빠져 supporting enrichment가 fail-closed WARN을 냈으므로,
  SEC submissions에서 검증한 RBLX `NYSE/7372`, EPD `NYSE/4922`까지 identity registry에 포함했다.

### 2026-08-16 후행 분기값 current-date laundering

- 증거: `TGA_LAGGED_ISSUANCE_CONTEXT`가 현재 주간 anchor를 날짜로 갖기 때문에, 분기 Z.1 원천이 270일을
  넘겨도 산식 내부 source-age 재검증이 없으면 최신 파생값처럼 남을 수 있었음
- 영향: 현재 2026-01-01 원천은 허용 구간 안이라 운영 오염은 없었지만 다음 공표 누락 시 잘못된 경고 가능
- 영구 가드: context 생성 직전에 분기 원천일을 270일 gate로 재검증하고, 만료 시 context와 호환 alias를
  모두 생성하지 않는다. 현재 TGA 4주 기여 자체는 후행 분기값과 독립적으로 유지한다.
- 회귀 테스트: 만료된 양의 분기 방향과 현재 TGA 공급이 함께 있어도 context가 생성되지 않는 domain test

## 4. Host 1분 monitor

Spring이 DB에 incident를 남기기 전에 죽는 blind spot을 담당한다.

- Spring ERROR
- HTTP 5xx
- container unhealthy/exited/restart/OOM
- health가 green으로 남은 container pause
- Hikari `Thread starvation or clock leap detected` 재발
- Loki/Prometheus/Jaeger source 자체 실패

메시지의 URL, UUID, 긴 hex, 숫자, token을 정규화·redact해 fingerprint를 만든다. 동일 active error는 한 번,
5분 조용한 뒤 같은 오류가 발생하면 recurrence로 다시 알린다. 최근 event ID는 30분 보존한다.

## 5. 일일 audit

최근 24시간의 다음 증거를 한 보고서로 교차한다.

- Prometheus: request/5xx/degraded/latency
- Loki: application/infrastructure ERROR/WARN
- Jaeger: sampled trace/error span/p95
- PostgreSQL: collection/company/market/analyst/13F/notification/storage
- Docker: health/restart/OOM/memory events
- API: 43개 smoke

자동 코드 수정·자동 배포는 하지 않는다. 탐지와 수정 권한을 분리해 잘못된 자동 복구가 금융 데이터를
더 오염시키지 않게 한다.

## 6. 장애 처리 템플릿

새 장애가 발생하면 이 문서에 아래 형식으로 추가한다.

```text
ID:
최초 탐지 시각/증거:
사용자·금융 영향:
원인:
오염 범위와 검증 SQL:
코드 수정:
DB constraint/migration:
회귀 테스트:
실시간 fingerprint/metric:
기존 데이터 repair:
배포·롤백 결과:
ADR/PDR/문서:
```

## 7. 조사 순서

홈서버 Docker 운영에서는 다음 순서를 따른다.

1. latest `.ops-audit` finding과 Telegram fingerprint
2. Prometheus/Loki/Jaeger 증거
3. container/host 상태
4. service code/config
5. PostgreSQL/MinIO 증상과 오염 범위

쓰기·repair 전에 읽기 전용 SQL과 object checksum으로 범위를 확정한다. 데이터 repair는 별도 백업과
rollback plan 없이 실행하지 않는다.

상세 명령은 [관측 Runbook](../RUNBOOK-daily-observability-audit.md)을 사용한다.
