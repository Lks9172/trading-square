# 섹터 순환 금융 플레이북

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-08**
- 운영 버전: `CURRENT_TOTAL_RETURN_RISK_ADJUSTED_MOMENTUM_WALK_FORWARD_V2`

## 목적

섹터 순환은 “어떤 기업을 먼저 볼 것인가”를 좁히는 탑다운 필터다. 섹터 점수만으로 기업 BUY를 만들지
않는다.

## 현재 주도

표준 11개 섹터 ETF와 SPY의 배당 반영 조정주가를 사용한다.

1. 최근 1개월을 제외한 6개월 상대 총수익률
2. 최근 1개월을 제외한 12개월 상대 총수익률
3. 각 50% 결합
4. 252일 상대변동성으로 조정
5. 11개 섹터 횡단면 percentile

SOXX·SMH·ITA·GRID·IGF는 전략 테마로 별도 비교한다. 표준 섹터 percentile이나 표준 주도 Top3에
합류시키지 않는다.

중기 상대 총수익률 0 이상, percentile 65 이상, 12개월 절대 추세 양수, 200일 평균 상단을 통과해야
현재 주도로 승격한다.

## 다음·다다음 후보

| 상태 | 기간 해석 | 해야 할 일 |
|---|---|---|
| 현재 주도 | 지금~3개월 | 섹터 내 기업의 품질·가격·촉매 확인 |
| 다음 후보 | 1~3개월 | 이익수정·flow·가격 확인 대기 |
| 다다음 후보 | 3~6개월 | watchlist, 구조 변화 추적 |
| 약화/이탈 | 즉시 | 신규 확대 중단, 기존 기업 가설 재점검 |

후보가 없으면 빈 목록이 정상이다. 개수를 맞추기 위해 임계값을 낮추지 않는다.
다음 후보 목록은 `1~3개월`, 다다음 후보 목록은 `3~6개월` horizon과 반드시 일치한다.

두 후보 목록이 모두 비면 화면은 `LAGGING` 섹터 중 당일 순환 점수 상위 3개를 **승격 전 관찰 순위**로
별도 표시한다. 이는 다음 후보 편입이나 매수 신호가 아니라, 어느 섹터의 추가 확인을 먼저 볼지 정하는
진단 순서다. canonical 후보 배열과 기업 점수에는 입력하지 않는다.

## 확인 근거

- 가격·거시만 있음: `WATCH/관찰 단계`
- 날짜가 있는 revision 또는 독립 flow 중 하나: `BUILDING/확인 진행 중` 가능
- 두 독립 축과 단기·중기 상대강도까지 충족: `CONFIRMED/주도 전환 확인`

확인 상태는 체크리스트이며 상승 확률이 아니다. 기준일 없는 revision과 재가공한 유동성·credit 값은
독립 확인으로 세지 않는다. 자료가 없으면 50점이 아니라 `자료 없음`으로 읽는다.

### 현재 EPS revision breadth

표준 11개 섹터는 구성종목별 30일 forward EPS 추정 변화 방향을 날짜와 함께 집계한다.

```text
상향: revision > +0.10%
하향: revision < -0.10%
보합: -0.10% 이상, +0.10% 이하
score = round(50 + 50 × (상향 수 - 하향 수) / 유효 종목 수)
```

기준일 3일 이내, 최소 5종목, 구성종목 coverage 50% 이상일 때만 현재 증거다. 점수·관측일·coverage·
상향/하향 비율을 같이 읽는다. equal-count breadth라서 시가총액 영향이나 revision 크기를 뜻하지 않으며
상승 확률도 아니다. V17 배포 이후 point-in-time 이력만 축적되므로 전체 composite 장기 검증은 아직
완료되지 않았다.

### 공식 ETF 생성·환매와 가격 breadth

표준 11개 ETF는 State Street 공식 NAV history의 발행좌수 변화로 1·5·20일 생성·환매를 계산한다.

```text
daily flow = (오늘 발행좌수 - 전일 발행좌수) × 오늘 NAV
flow score = clamp(round(50 + 6 × 5일 flow/순자산% + 4 × 20일 flow/순자산%), 0, 100)
```

최소 21개 거래일과 기준일 7일 이내를 요구한다. 이 값은 creation/redemption 근사이지 기관 투자자 주문
방향이나 수익 확률이 아니다. 날짜가 있는 공식 flow만 confirmation 독립축으로 인정한다.

가격 breadth는 catalog 대표 추적 종목 중 20·50·200일 이동평균 위 비율을 20:30:50으로 결합한다.
최소 10종목·coverage 70%·가격일 7일 이내일 때만 표시한다. 현재는 소수 대형주 집중을 확인하는 진단축이고
전체 ETF 보유종목 breadth가 아니며, 장기 OOS 검증 전에는 rotation/confirmation 가중에 추가하지 않는다.

## 실전 사용 순서

1. 현재 주도와 다음 후보를 구분한다.
2. 해당 섹터의 이익수정·거시 적합·자금 흐름 확인도를 본다.
3. crowding 70 이상이면 주도라도 추격을 제한한다.
4. 섹터 안에서 Company/B Score와 최종 액션이 좋은 기업만 남긴다.
5. 기업별 악재 해소, 가이던스, 바닥과 반전 확인을 본다.
6. BUY 이상이어도 가격 구조에 따라 1차 비중을 제한한다.

## 오해 금지

- 주도 섹터 = 모든 구성기업 상승 아님
- 다음 후보 = 1~3개월 후 반드시 주도 아님
- 높은 percentile = 저평가 아님
- 높은 relative momentum = 지금 바닥 아님
- 과거 hit rate = 미래 상승 확률 아님
- Top2·Top3 = Top1과 동일한 검증 강도 아님

## 검증 상태

현재 상대 모멘텀 레이어는 운영 산식과 7년 월말 walk-forward가 일치한다. 거시·이익수정·flow 전체
결합 모델은 아직 완전한 point-in-time 검증이 아니다.

3·6개월 결과는 월별 forward window가 겹치므로 일반 Wilson 구간보다 중첩 조정 95% 구간을 우선 본다.

상세 기술·제품 결정:

- [방법론 V2](../SECTOR-ROTATION-METHODOLOGY-V2.md)
- [ADR-002](../../server-spring/docs/ADR-002-sector-rotation-total-return-momentum.md)
- [PDR-001](../PDR-001-sector-rotation-product-interpretation.md)
- [ADR-003](../../server-spring/docs/ADR-003-sector-rotation-evidence-integrity.md)
- [PDR-002](../PDR-002-sector-rotation-evidence-disclosure.md)
- [ADR-004](../../server-spring/docs/ADR-004-dated-sector-eps-revision-breadth.md)
- [PDR-003](../PDR-003-sector-eps-revision-breadth-disclosure.md)
- [ADR-005](../../server-spring/docs/ADR-005-official-sector-etf-flow-and-price-breadth.md)
- [PDR-004](../PDR-004-sector-flow-and-price-breadth-disclosure.md)
- [PDR-008](../PDR-008-empty-sector-candidate-watchlist.md)
