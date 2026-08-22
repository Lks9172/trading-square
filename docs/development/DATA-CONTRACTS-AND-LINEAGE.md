# 데이터 계약·계보·현재성

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-17**
- Flyway: **V1~V22**
- 기업 유니버스: **277**. EA·CTRA 제거와 RBLX·EPD 신규 편입은 alias가 아니라 별도 identity이며
  퇴출 종목의 captured score를 신규 종목에 이관하지 않는다. sector/theme catalog, object-store read,
  file fallback read, analyst 수집 universe가 모두 adapter의 단일 `CurrentResearchUniverseTickerRegistry`를
  사용하고 E2E에서 sector/theme 합집합=DB=전체기업 API를 증명한다.
  captured detail이 없는 replacement는 identity-only, HOLD 기본 seed에서 현재 Spring 원천으로 enrich하며
  `verify-home.sh`가 두 상세 route의 공식 exchange/SIC, filing, 투자판정까지 검사한다. immutable legacy
  artifact를 읽는 현재 ticker alias(MMC→MRSH)는 저장 key만 역매핑하며 응답 identity는 항상 현재 ticker다.
- 기업 요약·analyst history·earnings universe는 위 raw catalog membership을 사용한다. 현재 섹터 순환
  assessment는 별도 point-in-time read model이며, momentum/거시 coverage 부족이 membership 수집을 막거나
  빈 universe로 바뀌지 않는다.
- 기업 계산 버전: **5**

## 1. 공통 계보

```text
외부 원천
 → bounded HTTP adapter
 → source DTO/parser
 → 정규화 application model
 → domain validation/policy
 → PostgreSQL row 또는 MinIO artifact+pointer
 → current projection
 → REST adapter DTO
 → Next.js UI
```

각 단계는 source, provider, financial as-of, filed/published date, collected/updated time, calculation version을
가능한 범위에서 보존한다. UI 갱신시각이 원천 기준일을 덮어쓰지 않는다.

## 2. PostgreSQL schema 소유권

| Schema | 주요 데이터 |
|---|---|
| `market` | 관측 시계열, 총수익률, collector status |
| `company` | analyst history/state, current research summary |
| `research` | peer taxonomy, narrative source history, 섹터 ETF flow·가격 breadth, immutable rotation/OOS ledger |
| `institutional` | manager, filing, holding, identity |
| `policy` | 공식 원문 분석과 calibration |
| `disclosure` | DART directory/disclosure/financial |
| `execution` | plan, tranche, trade log |
| `notification` | delivery state, candidate snapshot, outbox |
| `storage` | object artifact와 active pointer |

DDL은 Flyway만 변경한다. runtime에서 자동 schema update나 JPA DDL을 사용하지 않는다.

## 3. 현재 기업 projection

`company.research_summary`는 ticker당 하나의 현재 의사결정 projection이다.

### 원자 묶음

- Company Score 묶음: total/growth/quality/valuation/balance/buy/appeal/crowding 8개 전부 또는 전부 null
- 바닥 묶음: price bottom/volume/failure/confirmed score/state 5개 전부 또는 전부 null
- V21 알림 근거: confirmed signal date, reversal status/score, 제한된 이유 목록. status/score는 둘 다 있거나
  둘 다 null이며, 현재 바닥 묶음이 없으면 reversal 근거도 존재할 수 없다.
- V22 MACD 알림 근거: 일봉·주봉 교차/위치/histogram/확인 다이버전스와 진행 중 주봉 여부를 하나의
  JSON object로 원자 저장한다. company summary의 현재 알림 bundle과 notification candidate snapshot에서
  누락은 null이며 임의 중립값으로 대체하지 않는다.
- score가 있으면 `fundamentals_status=CURRENT`이고 `valuation_eligible=true`
- BUY/STRONG BUY면 score 묶음과 바닥 묶음이 모두 존재
- action은 STRONG BUY/BUY/HOLD/REDUCE/SELL만 허용
- `calculation_version > 0`, current query는 version 6만 사용

부분 write로 좋은 total score와 오래된 하위 score가 섞일 수 없도록 application transaction과 V15 check
constraint가 동시에 막는다.

알림 경로는 `updated_at`이 미래 허용 오차 5분을 넘지 않고 2시간 이내이며 score·바닥·반전·MACD 묶음이
모두 완전할 때만 이 projection을 재사용한다. 그렇지 않으면 직접 현재 가격 근거를 다시 평가하며,
실패하면 매수 후보에서 제외한다. `reversal_score`와 다른 0~100 점수는 검증된 확률이 아니다.

### Current/Lagging

`fundamentals_status=CURRENT`는 단지 기준일이 최근이라는 뜻이 아니다. 최신 알려진 주기보고서와 정규화된
재무 snapshot이 일치해야 한다. 최신 10-Q/10-K/20-F가 있는데 TTM이 이전 보고서라면 LAGGING이다.

## 4. 시장 관측

`market.observation`의 unique identity는 `(source, series_key, observed_on)`이다. 수집 재실행은 upsert하며
중복 행을 만들지 않는다. 미래 날짜, NaN/Infinity는 무결성 위반이다.

`market.collection_status`는 금융 값이 아니라 collector 실행 결과다.

- SUCCESS: collected>0, persisted=collected, failure key/type 없음
- DEGRADED: 일부 저장 또는 optional sub-source 실패
- ERROR: 필수 결과 실패

V16은 partial/zero persistence를 SUCCESS로 저장하는 것을 DB 경계에서 거부한다.

### 순유동성 파생 계보

```text
FRED adapter: WALCL / WDTGAL (WTREGEN audit only) / RRPONTSYD / Treasury net transactions
 → market.observation (원천 단위·observed_on 유지)
 → MarketInputFreshnessPolicy
 → CoreDerivedIndicatorPolicy common-anchor as-of join
 → NET_LIQUIDITY_LEVEL/IMPULSE/ACCELERATION/TURN + transmission stress
 → snapshot derived projection
 → MacroRegime/SectorRotation/CoreAssetSignal + 메인 UI/Telegram
```

domain은 FRED DTO를 모르며 단위 변환과 날짜 정렬만 소유한다. UI·notification adapter는 임계값을
재계산하지 않고 domain 결과를 설명한다. 순유동성은 자유형 derived projection에 영속되므로 schema migration은
없으며 원천 관측을 현재 날짜로 다시 찍지 않는다.

### 파생지표 평가일과 원천 관측일

snapshot 최상위 `timestamp`는 UTC 계산시각이다. 파생지표 `date`는 산식 유형에 따라 다음 계약을 따른다.

- 원천 정렬형: 순유동성은 공통 anchor, TGA/RRP/준비금 방향은 해당 원천 최신 관측일, 분기 국채 flow는
  대표 분기일을 보존한다.
- 현재 3축 합성값: 가용 구성축 중 가장 오래된 최신 관측일을 사용해 일부 최신 입력만으로 전체가 새것처럼
  보이지 않게 한다.
- 평가일형 상태/percentile: 신선도 검사를 통과한 입력으로 성공적으로 재계산된 UTC 평가일을 사용한다.

입력이 stale하거나 계산 history가 부족해 새 값이 `null`이면 마지막 정상 값과 기존 날짜를 감사용으로만
보존하고 `eligibleForSignals=false`로 제외한다. API가 계산시각과 금융 기준일을 같은 의미로 사용해서는 안 된다.
Telegram·주간 리포트 anti-corruption adapter도 이 적격성 값을 확인하며, retained stale 숫자를 현재 경고나
시장 상태로 다시 승격하지 않는다. Telegram은 4주 금액에서 임계값을 다시 계산하지 않고 domain의
`NET_LIQUIDITY_IMPULSE_STATE`를 번역한다. 혼합 주기의 TGA/분기 국채 맥락은 분기 원천 기준일을 문구에
함께 싣는다.

## 5. Analyst revision

- 현재 forward EPS와 7/30/90일 전 snapshot을 비교
- EPS가 0 부근이거나 부호가 바뀌면 percentage revision을 결측 처리
- 목표가 업사이드와 목표가 업사이드 변화는 별도 field
- latest analyst row가 score/upside 모두 null이면 무결성 위반
- ticker별 observed date 중복, 미래 날짜, score -2~2 범위 이탈, upside -100~1000 이탈 금지
- V17부터 7/30/90일 EPS revision을 snapshot 당시 값으로 같은 행에 영속하고 finite constraint를 적용
- Research는 read-only ACL port로 ticker별 최신 30일 revision만 읽으며 Company schema write ownership을 침범하지 않음
- 섹터 breadth는 기준일 3일 이내·최소 5종목·coverage 50% 이상일 때만 current
- 과거 null 행은 현재 응답으로 backfill하지 않으며, unavailable과 실제 중립 breadth를 구분

## 6. 가격·기업행위

가격 이력은 날짜 정렬, 양의 종가·거래량, OHLC 범위, corporate-action형 2/3/4/5/10/20배 불연속을 검증한다.
Yahoo split event의 유효한 분자/분모와 event 전후 종가 비율이 ±15% 안에서 일치할 때만 미조정 행사 전
OHLC를 현재 basis로 나누고 거래량을 곱한다. 이미 수정된 이력은 다시 조정하지 않는다. event가 없거나
가격비율과 불일치하면 신호를 숨기는 것이 잘못된 매수 알림보다 안전하므로 fail-closed한다.

섹터 가격/총수익률은 benchmark와 최신 거래일이 맞아야 하며 최근 구간 45% 초과 불연속을 탐지한다.

### 섹터 시장 증거 V18

- `research.sector_fund_flow_snapshot`: State Street NAV/발행좌수에서 계산한 일별·5일·20일 flow,
  순자산 대비 비율, 휴리스틱 score와 provider/수집시각
- `research.sector_price_breadth_snapshot`: catalog 대표 종목 수, 유효 수, MA20/50/200 상단 수,
  score와 가장 오래된/최신 component date
- identity는 두 테이블 모두 `(sector_key, observed_on)`이고 upsert는 같은 금융 기준일을 멱등 갱신한다.
- Research domain은 provider/JDBC를 모르며 Company 가격을 read-only ACL로 변환한다.
- current read는 7일 gate를 넘기면 null을 반환하고 마지막 정상 행의 기준일을 현재 날짜로 다시 찍지 않는다.

### 섹터 composite 검증 원장 V19/V20

- `research.sector_rotation_run`: `methodology_version + price_anchor_on`당 최초 한 번 저장한다.
  `as_of_date`는 UTC 신호 계산일, `price_anchor_on`은 완료된 공통 총수익률 거래일이다.
- `research.sector_rotation_item_snapshot`: run별 표준 11개 component/rank/state와 source date/coverage
- `research.sector_rotation_outcome`: 21/63/126 공통 거래 세션이 실제로 지난 뒤에만 수익률 저장
- run+11 items는 하나의 transaction이며 재실행은 기존 행을 update하지 않는다.
- V19 이전 날짜를 현재 데이터로 backfill하지 않는다. 과거 모멘텀 walk-forward와 V19/V20 이후 전체 composite
  forward validation은 서로 다른 검증 범위다.

## 7. MinIO artifact/pointer

```text
PUT versioned body
 → SHA-256/ETag/size/version metadata insert
 → active pointer transaction commit
 → reader exact version read
 → size/ETag/SHA-256 verify
```

pointer가 없는 object는 공개되지 않는다. pointer가 가리키는 artifact가 없으면 CRITICAL 무결성 위반이다.
동일 SHA-256인데 restore 후 ETag만 달라진 경우 bounded body를 재검증한 뒤 새 version metadata로 복구한다.

## 8. 알림 상태

후보 상태와 outbox enqueue는 한 transaction이다. candidate snapshot은 최신 company summary와 total/B/action/
bottom/reversal state·score가 같아야 한다. 기업 알림 적격성은 실행 액션과 분리하며, 저장된 이전 snapshot과 비교해
반전 `ON→STRONG` 및 total/B의 5점 구간 상향 돌파를 원자적으로 탐지한다. 불일치는 candidate drift로 탐지한다.

outbox 상태:

```text
PENDING → IN_FLIGHT → DELIVERED
                 └→ RETRY → IN_FLIGHT
                         └→ DEAD
```

IN_FLIGHT lease 만료, 10분 넘은 PENDING, RETRY/DEAD는 운영 이상이다. Telegram provider가 idempotency key를
지원하지 않아 provider 수락 직후 DB ack 전 crash의 극소 중복 창은 남는다.

## 9. 배치 완전성

유니버스 수집은 성공한 ticker 수만 보지 않는다.

- current company rows 정확히 277
- current calculation rows 정확히 277
- analyst series rows 정확히 277
- price signal rows 정확히 277
- 가장 오래된 company summary가 2시간 이내
- 비교 가능한 score 최소 80%

최신 한 행의 timestamp로 전체 batch를 정상 판정하지 않는다.

## 10. 읽기 계약

- current API는 retired alias와 구 calculation version을 숨긴다.
- API adapter는 domain 의미를 문자열로 변환하지만 산식을 재계산하지 않는다.
- UI는 서버 액션과 점수를 다시 계산하지 않는다.
- stale 값은 감사용으로 보여줄 수 있으나 `eligibleForDecision=false`를 유지한다.
- current projection을 읽을 수 없을 때 손상된 body를 빈 정상 응답으로 바꾸지 않는다.
