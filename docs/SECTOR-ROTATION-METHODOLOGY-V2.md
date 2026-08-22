# 섹터 순환 상대 모멘텀 V2

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-08**
기준일: 2026-08-08
운영 버전: `CURRENT_TOTAL_RETURN_RISK_ADJUSTED_MOMENTUM_WALK_FORWARD_V2`

## 관련 의사결정 기록

- 기술·아키텍처 결정: [`ADR-002`](../server-spring/docs/ADR-002-sector-rotation-total-return-momentum.md)
- 증거·유니버스 무결성: [`ADR-003`](../server-spring/docs/ADR-003-sector-rotation-evidence-integrity.md)
- 제품 해석·노출 결정: [`PDR-001`](PDR-001-sector-rotation-product-interpretation.md)
- 결측·확인 노출 결정: [`PDR-002`](PDR-002-sector-rotation-evidence-disclosure.md)
- 전체 기록 목록: [`DECISION-RECORDS`](DECISION-RECORDS.md)

## 목적과 검증 범위

이 레이어는 표준 11개 미국 섹터 ETF 중 **현재 상대 모멘텀 순위**를 계산한다. 미래 수익 확률이나 매수 신호가 아니며, 거시·이익추정·수급·밸류를 포함하는 전체 섹터 순환 모델의 적중률로 해석하지 않는다.

## 원천 데이터

- 섹터: XLK, XLF, XLE, XLV, XLI, XLY, XLC, XLB, XLRE, XLU, XLP
- 벤치마크: SPY
- 값: Yahoo `adjclose` 기반 분배금 재투자 총수익률 프록시
- 정렬: 섹터와 SPY가 같은 거래일에 모두 존재하는 관측값만 사용
- 보존: 최대 공통 이력 확보를 위해 9년 요청, 최소 2,000개 관측값을 운영 무결성 계약으로 강제
- 갱신: SPY+표준 11개는 12/12 시계열, 동일 최신일을 만족할 때만 하나의 단면으로 저장

SOXX, SMH, ITA, GRID, IGF는 표준 섹터가 아니라 중복 가능한 전략 테마다. 별도 5개 유니버스에서만
비교하며 표준 11개 percentile과 현재/다음 주도 목록을 바꾸지 않는다.

## V2 산식

월말 기준으로 다음 값을 계산한다.

1. 최근 1개월을 제외한 6개월 섹터/SPY 총수익률 비율 변화
2. 최근 1개월을 제외한 12개월 섹터/SPY 총수익률 비율 변화
3. 1과 2를 각각 50%로 결합
4. 같은 기준시점 이전 252거래일 섹터/SPY 로그수익률의 연율화 변동성으로 나눔
5. 11개 섹터의 횡단면 백분위로 0~100 상대강도 점수를 생성

최근 1개월은 단기 반전 잡음을 줄이기 위해 형성 수익률에서 제외하지만, 별도의 1개월 상대강도를 주도 추세 확인에 사용한다. 12개월 절대 총수익률과 200일 평균을 함께 통과하지 못한 섹터는 상대 순위만으로 `LEADING`에 올리지 않는다.

이 설계는 [MSCI Momentum Indexes Methodology](https://www.msci.com/indexes/documents/methodology/2_MSCI_Momentum_Indexes_Methodology_20250725.pdf)의 6·12개월 결합/최근 1개월 제외 원칙과 [S&P Momentum Indices Methodology](https://www.spglobal.com/spdji/en/documents/methodologies/methodology-sp-momentum-indices.pdf)의 최근 1개월 제외/변동성 조정 원칙을 섹터-SPY 상대 총수익률에 맞게 적용한 **내부 프록시**다. 어느 공급자의 지수를 그대로 복제한다고 주장하지 않는다.

## 워크포워드 계약

- 완료된 월말만 리밸런싱
- 해당 월말까지 공개된 조정주가만 사용
- 이후 21/63/126 거래일 결과 측정
- SPY 대비 초과수익, 섹터 11개 동일가중 대비 초과수익, 절대 양(+) 수익을 별도로 측정
- 적중률과 함께 Wilson 95% 구간, 중첩 forward window를 감안한 Newey-West/Bartlett 조정 95% 구간,
  표본 수, 월평균 Top3 교체율 표시
- 동일 기간 V1(`1M 15% + 3M 35% + 6M 35% + 12M 15%`)은 `COMPARISON_ONLY_NOT_LIVE`로만 제공

## 해석 제한

- 조정주가는 세금, 실제 거래비용 및 ETF 구성 변경 효과를 완전히 재현하지 않는다.
- 표본은 XLC 상장 이후로 제한된다.
- Top1 결과와 Top3 동일가중 결과를 구분해야 한다. Top3는 분산 관찰 목록이지 Top1과 동일한 예측 신호가 아니다.
- 전체 순환 모델의 거시·실적·수급 축은 point-in-time 이력이 완성되기 전까지 별도 검증 완료로 표시하지 않는다.
- 기준일 없는 catalog revision은 참고값으로만 보존하며 현재 확인 또는 순환 점수를 올리지 않는다.
- 현재 EPS revision은 구성종목별 30일 forward-EPS 변화의 날짜 있는 방향 breadth만 사용한다. 기준일
  3일 이내, 최소 5종목, coverage 50% 이상을 요구하며 점수는
  `50 + 50 × (상향 수 - 하향 수) / 유효 종목 수`다.
- valuation/revision/flow가 없으면 API와 UI에서 `null`/자료 없음으로 유지한다.
- flow는 State Street 공식 섹터 ETF 발행좌수 변화 `(shares_t-shares_t-1)×NAV_t`의 5·20일 합을
  순자산 대비 비율로 바꾼 휴리스틱 지수만 사용한다. 날짜 없는 기존 스타일 flow는 독립 증거가 아니다.
- 가격 breadth는 대표 추적 종목의 MA20/50/200 상단 비율을 20:30:50으로 결합하며 최소 10종목,
  coverage 70%, 7일 freshness를 요구한다. 현재는 진단 표시이고 V2 점수/확인축에는 추가하지 않는다.
- 전체 current composite의 거시 국면은 유동성·실질금리·금리곡선·유가·달러·신용/스트레스와 3상태
  event만 사용한다. 같은 입력으로 만든 상위 `macroRegime` label을 0/100으로 재입력하지 않으며,
  실질금리·유동성·HY OAS를 별도 financial-conditions 8%로 다시 더하지 않는다.
- current composite 가중은 macro fit 30%, momentum 28%, fundamental 22%, dated revision 12%,
  crowding relief 4%, dated official ETF flow 4%다. 이 전체 composite의 장기 OOS 성능은 아직 검증되지 않았다.
- V19/V20 이후 `CURRENT_SECTOR_ROTATION_COMPOSITE_V3` 출력은 완료된 SPY+11개 공통 총수익률 거래일마다
  최초 한 번 append-only 저장한다. UTC 신호 계산일과 가격 anchor 거래일을 분리하고, 21/63/126 공통 거래
  세션이 지난 뒤에만 가격 anchor부터 SPY/11개 동일가중 대비 outcome을 붙인다. 과거 backfill은 하지 않으므로
  초기 전체 composite 검증 상태는 `INSUFFICIENT_SAMPLE`이다.
