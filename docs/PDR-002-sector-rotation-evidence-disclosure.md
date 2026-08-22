# PDR-002: 섹터 순환의 유니버스·결측·확인 근거를 사용자에게 구분한다

> 이 문서에서 PDR은 **Product Decision Record**를 뜻한다.

- 문서 상태: **DECISION**
- 상태: **Accepted / production**
- 결정일: 2026-08-08
- 관련 ADR: [`../server-spring/docs/ADR-003-sector-rotation-evidence-integrity.md`](../server-spring/docs/ADR-003-sector-rotation-evidence-integrity.md)
- 기존 해석 원칙: [`PDR-001`](PDR-001-sector-rotation-product-interpretation.md)

## 사용자 문제

같은 화면에서 표준 섹터와 반도체·전력망 같은 전략 테마가 섞이면 “미국 시장의 현재 주도 섹터”와
“겹치는 투자 테마”를 구분할 수 없다. 또한 자료 없음이 50점으로 보이거나 가격·거시만으로 “확인 진행
중”이 뜨면 사용자는 실제 수급·이익 확인이 존재한다고 오해할 수 있다.

## 제품 결정

1. 표준 11개 섹터 주도 목록에는 표준 섹터만 표시한다.
2. 전략 테마는 별도 테마 화면과 profile에서 비교하며 표준 percentile을 바꾸지 않는다.
3. valuation·revision·flow가 없으면 `-`/`자료 없음`으로 표시하고 50점 관측처럼 칠하지 않는다.
4. 가격+거시만 존재하면 confirmation은 `관찰 단계`다.
5. `확인 진행 중`은 독립 flow 또는 날짜가 있는 revision이 하나 이상 있을 때만 사용한다.
6. 다음 후보는 1~3개월, 다다음 후보는 3~6개월 전망과 반드시 일치한다.
7. 과열 기준은 70 이상으로 통일한다.
8. 3·6개월 적중률에는 중첩 월별 결과를 감안한 조정 95% 구간을 우선 표시한다.
9. 선택된 거시 국면뿐 아니라 5개 후보 점수를 함께 보여 근소한 판정을 확인할 수 있게 한다.
10. 운영은 일별 재계산, 공개 검증은 월말 리밸런스라는 cadence 차이를 명시한다.

## 사용자에게 보이는 문구

- 현재 주도: “표준 11개 섹터 총수익률 상대 모멘텀과 절대 추세 통과”
- 전략 테마: “표준 섹터와 중복될 수 있는 별도 관찰 ETF”
- 자료 없음: “중립 50”이 아니라 “현재 독립 자료 없음”
- WATCH: “가격·거시는 보이지만 이익/수급 독립 확인 전”
- BUILDING: “독립 확인축 1개 이상, 아직 완성 아님”
- CONFIRMED: “가격·거시·revision·flow 체크리스트 충족; 상승 확률을 뜻하지 않음”

## 채택하지 않은 제품안

- 표준·테마를 한 Top3로 노출: 비교 유니버스가 겹쳐 기각
- 자료 없음을 50점 막대로 노출: 실제 관측과 구분되지 않아 기각
- 후보 수를 채우기 위해 기간·점수 임계값 완화: false precision 때문에 기각
- Wilson 구간만 노출: 3·6개월 중첩 표본의 불확실성을 과소표시할 수 있어 기각

## 성공 기준

- 표준 섹터 summary에 SOXX·SMH·ITA·GRID·IGF가 들어가지 않는다.
- 동일 표준 데이터에서 전략 테마를 추가해도 표준 percentile이 변하지 않는다.
- 독립 증거가 없는 확인 배지는 WATCH다.
- 카드의 next/secondary 목록과 `expectedLeadershipWindow`가 모순되지 않는다.
- nullable 점수는 API·UI 전 구간에서 nullable로 유지된다.
- 백테스트는 overlap-adjusted interval, 표본 수, 평균 초과수익을 함께 표시한다.

## 남은 제품 과제

- 구성종목 breadth와 ETF flow를 표준 11개 전체에 같은 정의로 수집
- 날짜가 있는 섹터 revision breadth는 운영 반영 완료; V17 이후 point-in-time snapshot 장기 누적
- 각 component의 source date·coverage·검증 등급을 한 카드에서 확인
- 전체 composite의 out-of-sample ledger가 쌓이기 전까지 “예측 확률” 문구 금지
