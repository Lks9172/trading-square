# 금융 데이터 원천·수집 주기·신선도 계약

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-16**
- 운영 유니버스: **277개 기업**
- 현재 기업 계산 버전: **6**

## 1. 시간 의미를 분리한다

모든 데이터는 가능한 경우 다음 시간을 분리한다.

| 시간 | 의미 |
|---|---|
| `observed_on` / `asOf` | 금융적으로 해당 값이 가리키는 기준일 |
| `filed_on` / 발표일 | 시장 참여자가 정보를 알 수 있게 된 날짜 |
| `collected_at` | 시스템이 원천을 가져온 시각 |
| `updated_at` | 정규화 projection을 확정한 시각 |

`collected_at`이 방금이어도 오래된 `observed_on`은 신선한 금융 근거가 아니다. 반대로 저빈도 공식
지표는 collector가 오래 실행되지 않았더라도 발표주기 안이면 값 자체는 유효할 수 있다. 원천 신선도와
수집기 상태를 별도 표시한다.

### 자동 대조용 핵심 cache 계약

```text
COMPANY_ANALYST_CONSENSUS_CACHE_TTL=1h
YAHOO_PRICE_HISTORY_CACHE_TTL=15m
SEC_COMPANYFACTS_CACHE_TTL=4h
SEC_SUBMISSIONS_CACHE_TTL=30m
SEC_FILING_DETAIL_CACHE_TTL=6h
```

production compose 값이 바뀌면 표의 금융 의미와 이 블록을 같은 변경에서 수정한다.

## 2. 시장·거시

| 영역 | 원천 | 운영 수집 주기 | stale 운영 탐지 | 용도/주의 |
|---|---|---:|---:|---|
| 주가·ETF·FX·원자재 | Yahoo | 5분 | 30분 | 시장/자산/섹터 현재값 |
| 섹터 총수익률 | Yahoo adjclose | 6시간 | 7일 거래일 기준 | 배당 반영 상대 모멘텀 proxy |
| 섹터 ETF 생성·환매 | State Street 공식 NAV history | 6시간 | 7일 | 발행좌수 변화 기반 1·5·20일 flow; 주문주체는 알 수 없음 |
| 섹터 가격 breadth | Yahoo 가격 이력+catalog 대표 종목 | 6시간 | 7일 | 최소 10종목·70% coverage; ETF 전체 holdings 아님 |
| 금리·신용·고용·유동성 | FRED | 6시간 | 12시간 collector 상태 | 값 자체 신선도는 발표주기별 별도 gate |
| Fear & Greed | CNN, Alternative.me fallback | 1시간 | 3시간 | 심리 보조축 |
| AAII/NAAIM/CBOE | 공식/공개 feed | 6시간 | 12시간 | NAAIM public은 2026-08-01부터 3개월 지연; licensed current source 없으면 현재 점수에서 제외 |
| Stablecoin | DefiLlama | 6시간 | 12시간 | 코인 유동성 proxy |
| KRX 투자자 흐름 | Naver Finance KRX 집계 | 30분 | 90분 | 제공자를 KRX 공식 API로 오표기하지 않음 |
| 시장 snapshot | 정규화 관측으로 재계산 | 5분 | readiness/최신 projection | 원천값을 재수집하는 작업과 분리 |

필수 market collector는 FRED, YAHOO, FEAR_GREED, SENTIMENT, STABLECOIN, KRX 6개다. `SUCCESS`는
수집 건수>0, 저장 건수=수집 건수, 실패 key/type 없음일 때만 허용한다.

### 순유동성 입력 계약

| 입력 | 원천 series | 단위·주기 | 최대 as-of 간격 | 의미 |
|---|---|---|---:|---|
| 연준 총자산 | FRED `WALCL` / Fed H.4.1 | USD million·주간 | 10일 | 순유동성 유입 파이프 |
| TGA 시점값 | FRED `WDTGAL` / Fed H.4.1 | USD million·수요일 | 10일 | WALCL과 같은 시점; 감소=현재 준비금 공급, 재충전=흡수 |
| TGA 주간평균 | FRED `WTREGEN` / Fed H.4.1 | USD million·주간평균 | 신호 미사용 | 감사·비교용, point-in-time 차감에서 제외 |
| ON RRP | FRED `RRPONTSYD` / NY Fed | USD billion·일간 | 3일 | 감소=준비금 공급 방향, 위험자산 직접 flow 아님 |
| 은행 준비금 | FRED `WRESBAL` / Fed H.4.1 | USD million·주간 | 14일 | 방향과 현재 조 달러 잔액을 분리. 3조 달러는 자체 모니터링선이며 공식 안전선 아님 |
| 시장성 국채 순거래 | FRED `BOGZ1FU313161105Q` | USD million·분기 flow | 270일 freshness | FRED의 분기 시작일 관측 표기와 Z.1 공표 지연을 반영. 방향값은 원천 분기일을 보존하고, 현재 TGA context는 원천이 270일 안일 때만 생성하며 분기일을 별도 표시한다. 최근-직전4분기평균 금액 차이이며 현재 3축 정렬·향후 경매 예측에는 넣지 않음 |
| M2 | FRED `M2SL` | 월간 관측·월간 발표 | 95일 freshness | period date+H.6 발표 지연 반영; 전환 대체 불가 |

세 잔액은 가장 이른 최신일을 공통 anchor로 사용한다. 일간 RRP의 더 늦은 값이 수요일 WALCL/TGA 과거
시점으로 새어 들어가지 않는다. 공식 원천이 제공하는 것은 각 잔액이며 `WALCL-TGA-RRP` 합성값과
±250억/±1,000억달러 임계값과 분기 순발행 ±500억달러 방향 임계값은 자체 분석 proxy다. 부호가 바뀔 수
있는 순거래 flow에는 퍼센트 변화를 사용하지 않는다.

Yahoo `USDKRW`/`USDJPY`는 전체 host pass 실패 시 같은 bounded host 집합을 한 번 더 시도한다.
Yahoo가 요청한 `JPY=X`/`KRW=X` 대신 같은 방향의 명시 alias `USDJPY=X`/`USDKRW=X`를 반환할 수 있으므로
이 두 형태만 동일 quote로 인정한다. 그 외 심볼 불일치는 계속 거부한다.
그래도 실패하면 상태는 `DEGRADED`다. 단, 실패 key가 이 두 FX뿐이고 각 key의 실제 직전
`collected_at`이 30분 이내이면 현재 의사결정 근거는 그 직전값으로 제한해 유지하고 hard integrity
alert만 유예한다. 값을 새 timestamp로 재저장하거나 `SUCCESS`로 바꾸지 않으며, 30분 초과·값 부재·다른
key 공백은 즉시 hard failure다.

NAAIM 공식 public table은 2026-08-01부터 current weekly 값이 아닌 3개월 지연 자료를 제공한다.
collector는 표의 최신 날짜를 선택하지만 14일을 넘은 행을 현재 `NAAIM_EXPOSURE`로 저장하지 않는다.
DB 수집 원장은 이를 `PROVIDER_POLICY_UNAVAILABLE`, 제품 collection health는 `LIMITED`로 구분한다.
심리 composite는 결측을 0/중립으로 대체하지 않고 Fear & Greed·put/call·AAII의 available-component
평균과 coverage 75%로 계산한다. 구독한 current table은 `NAAIM_EXPOSURE_URL`로 연결하며 접근 credential과
provider payload는 domain에 노출하지 않는다.

## 3. 기업

현재 277개 합집합에는 immutable cutover catalog에서 거래 종료된 EA·CTRA를 제거하고 같은 표준 섹터의
신규 분석 종목 RBLX·EPD를 별도 current member로 편입한 결과가 포함된다. 이는 ticker alias가 아니며
EA/CTRA의 과거 점수·재무·가격을 RBLX/EPD에 복사하지 않는다. 새 종목은 자체 SEC/Yahoo 원천 수집과
V5 재계산이 완료되기 전까지 점수·액션을 보류한다. 섹터 페이지와 전체기업 페이지는 같은 lifecycle
registry를 사용하며, 두 화면 간 membership 차이는 배포 E2E 실패로 처리한다.

신규 상세 seed의 identity metadata도 추측하지 않는다. SEC submissions 기준 RBLX는 CIK 0001315098,
NYSE, SIC 7372이고 EPD는 CIK 0001061219, NYSE, SIC 4922다. 이 값은 상세 enrichment가 SEC submissions,
filing detail, revenue mix를 조회하기 위한 identity일 뿐 퇴출 종목의 재무·점수는 포함하지 않는다.

| 영역 | 원천 | cache/갱신 계약 | stale fallback | 현재 액션 사용 조건 |
|---|---|---:|---:|---|
| 현재 quote | Yahoo | 1분 | 15분 | 가격 기준일 7일 이내 |
| 5년+ OHLCV+split event | Yahoo | 15분 | 2시간 | split event/인접비율 이중 확인 후 현재 basis 정규화, 날짜/OHLCV 품질 통과 |
| Company Facts | SEC | 4시간 | 6시간 | 최신 주기보고서와 정규화 TTM 일치 |
| Submissions | SEC | 30분 운영 override | 6시간 | 최신 10-Q/10-K/20-F 탐색 |
| Filing detail/Exhibit/PDF | SEC | 6시간 | 6시간 | bounded 32MiB, 120 PDF pages |
| Analyst consensus | Yahoo earningsTrend | 1시간 | 6시간 | 빈 최신값 금지, 실제 forward EPS revision만 사용 |
| Analyst history | PostgreSQL | 평일·주말 매시간 15분 | store-preferred | ticker별 관측일 unique |
| 섹터 EPS revision breadth | 위 analyst history의 30일 revision | 현재 순환 계산 시 조회 | 3일 | 최소 5종목·coverage 50%, 방향 breadth |
| Company summary | 위 원천 조합 | 30분, 동시성 8 | last-valid + fail-closed | 전체 277개, 가장 오래된 행 2시간 이내 |

재무 기준일이 200일을 넘으면 STRONG BUY를 금지하고 400일을 넘으면 신규 BUY를 금지한다. 달력상
최근이어도 최신 알려진 주기보고서보다 정규화 TTM이 뒤처지면 `LAGGING`으로 간주한다.

Yahoo가 명시한 split event의 시각·분자·분모가 유효하고 행사 직전/직후 종가 비율이 공표 비율의 ±15%
안에 있을 때만 신규 split 반영 과도기로 판정한다. 이 경우 행사 전 OHLC를 비율로 나누고 거래량을 같은
비율로 곱해 현재 basis로 통일한다. 이미 수정된 이력은 다시 조정하지 않는다. event가 없거나 비율이
불일치하면 2·3·4·5·10·20배 인접 불연속을 임의 보정하지 않고 payload를 cache에 저장하지 않는다. 마지막
정상 cache는 2시간까지만 사용하며 그 뒤에는 가격·바닥 bundle을 비우고 실행 액션을 `HOLD`로 제한한다.
결측 bundle은 다음 30분 summary refresh에서 다른 정상 기업보다 먼저 재시도한다. Yahoo event는 issuer나
거래소의 독립 원천이 아니므로 event 누락·오류 가능성은 domain fail-closed 검증으로 방어한다.

## 4. Research·정책·기관·공시

| 영역 | 원천 | 주기 | 지연·한계 |
|---|---|---:|---|
| 13F | SEC 13F-HR/Information Table | 24시간 | 분기말 후 최대 45일 지연, 실시간 주문 신호 아님 |
| 정책 | Fed, Treasury, USTR 공식 원문 | 6시간 | confidence·발표일 gate 필요 |
| Peer taxonomy | SEC ticker/SIC/submissions | 6시간, taxonomy TTL 30일 | 30일 missing grace, point-in-time 유효기간 |
| Narrative | Google News, Wikimedia, 선택적 YouTube | 6시간 | source quality·missing streak·revision 별도 |
| OpenDART | 금융감독원 공식 API | 6시간 | 키 없으면 disabled/unavailable, 가짜 중립값 금지 |
| Earnings calendar | Nasdaq | 1시간 | 이벤트 일정이며 실적 방향 신호 아님 |

13F 평가액은 달러 단위로 정규화하고 주식 수와 평가액이 모두 양수여야 한다. 매수/매도 분류는 평가액
변화가 아니라 보고 주식 수 변화가 기준이다.

## 5. 코인

현재 코인 research는 BTC·ETH·SOL·XRP·BNB의 persisted research projection에 시장 시계열 신선도를
덧씌운다. 시장 데이터와 stablecoin/ETF/alt-season/exchange-flow 보조 근거의 최신일을 각각 평가한다.

- stale이면 시장 regime 액션을 `관찰 대기`로 제한
- 자산별 액션은 HOLD, 목표 비중은 0으로 fail-closed
- 거래소 순유입이라고 표시된 값이 실제 온체인 거래소 잔고가 아닌 경우 `proxy`를 유지
- 코인 간 순위보다 코인장 전체 risk-on/off가 먼저

코인 모델은 기업의 SEC 기반 펀더멘털과 같은 검증 밀도를 주장하지 않는다.

## 6. 결측·fallback 규칙

1. 결측을 0 또는 중립 50으로 채워 강한 액션을 만들지 않는다.
2. last-valid는 허용된 stale window 안에서만 참고하고 경고를 남긴다.
3. 현재 원천 검증 실패 시 이전 BUY/바닥 후보를 알림에 재사용하지 않는다.
4. 한 ticker의 성공이 나머지 276개 실패를 가리지 못하도록 가장 오래된 summary 시각을 감시한다.
5. 부분 수집은 `DEGRADED`, 전체 실패는 `ERROR`; 둘 다 `SUCCESS`가 아니다.
6. source, provider, observed date, collection status를 서로 대체하지 않는다.
7. optional credential이 없으면 `MISSING/DISABLED`, 가짜 proxy로 채우지 않는다.

## 7. 운영 무결성 기준

- 기업 current rows: 277
- 계산버전 5 rows: 277
- 비교 가능한 Company Score: 최소 80%
- 가격·바닥 signal rows: 277
- analyst series rows: 277, 2시간 이내
- 총수익률 series: 16개 섹터/전략 ETF + SPY benchmark, 각 최소 2,000개 관측
- 미래 날짜, NaN/Infinity, 중복 시장·analyst 관측: 0
- stale score, 근거 없는 BUY, 부분 점수/신호 묶음: 0
- 알림 candidate drift, dangling object pointer, outbox retry/dead/stuck: 0

섹터 EPS revision은 ticker별 최신 30일 forward-EPS 변화율을 +0.10% 초과 상향, -0.10% 미만 하향,
그 사이 보합으로 분류한다. 기준일 3일 이내의 최신 행만 사용하며 V17 이전 행은 revision 열이 null이다.
현재 공급자 값을 과거 행에 backfill하지 않는다.

V18 이후 섹터 flow/breadth snapshot은 `(sector_key, observed_on)`으로 저장한다. 공식 flow는 21개
관측을, 가격 breadth는 200개 가격 관측과 10종목·70% coverage를 요구한다. 현재 workbook이 제공하는 과거
전체를 과거 당시 수집값으로 간주하지 않으며 V18 snapshot부터 point-in-time 관측으로 보존한다.

V19/V20부터 완료된 SPY+표준 11개 총수익률 공통 거래일을 가격 anchor로 live composite를 하루 한 번
append-only 저장한다. UTC 신호 계산일과 가격 anchor를 분리하며, component 근거일은 신호일 이후일 수 없다.
21/63/126 공통 거래 세션이 지나기 전 outcome을 만들지 않으며 V19 이전 시점은 backfill하지 않는다.

이 기준은 `DataIntegrityPolicy`, PostgreSQL V15/V16/V17/V18/V19/V20 제약, 1분 recurrence monitor와 일일 audit에서
독립적으로 확인한다.
