# PDR-005: 섹터 순환 점수는 연속 거시 근거를 한 번만 사용한 관찰 우선순위로 표시한다

> 이 문서에서 PDR은 **Product Decision Record**를 뜻한다.

- 문서 상태: **DECISION**
- 상태: **Accepted / production**
- 결정일: 2026-08-08
- 관련 ADR: [`../server-spring/docs/ADR-006-sector-rotation-macro-decorrelation.md`](../server-spring/docs/ADR-006-sector-rotation-macro-decorrelation.md)

## User problem

같은 금리·유동성·신용 정보가 거시 label과 별도 금융여건 점수에 반복되면 숫자가 정교해 보여도 실제
독립 증거는 늘지 않는다. 사용자는 점수를 상승 확률로 오해할 수 있다.

## Decision and displayed meaning

- methodology에 “연속 거시 적합도·중복 제거”를 명시한다.
- rotation score는 현재/다음/다다음 **관찰 우선순위**이며 상승 확률, BUY 또는 예상 도달 시점이 아니다.
- upstream macro label은 설명용으로 보이지만 sector score를 0/100으로 재가중하지 않는다.
- current/next/secondary 상태와 1~3개월/3~6개월 문구는 조건부 관찰 horizon이며 보장 시점이 아니다.

## Labels, thresholds, colors, and actions

기존 `LEADING/IMPROVING/WEAKENING/LAGGING`과 색상은 유지한다. 높은 score 단독으로 매수하지 않고,
가격 추세·dated revision·공식 ETF flow·기업 선별을 함께 확인한다. 산식 변경 전후 score 차이는
성과 개선으로 해석하지 않는다.

## Null, stale, loading, and error states

- current macro 7개 중 5개 미만이면 순환 평가 자체를 제공하지 않는다.
- event 미수집은 false가 아니라 unknown이며 confidence를 낮춘다.
- dated revision/flow가 없으면 화면은 `자료 없음`; 내부 prior 50을 관측값처럼 표시하지 않는다.
- 기존 정상 snapshot 날짜를 수집 실패 시 새 날짜로 바꾸지 않는다.

## Evidence and confidence disclosure

regime confidence는 9개 macro evidence coverage와 국면 점수 분리도의 제한값이며 예측 적중률이 아니다.
현재 장기 walk-forward는 총수익률 상대 모멘텀 레이어만 검증한다. 전체 composite는 immutable
point-in-time ledger와 충분한 forward outcome이 생길 때까지 `검증 완료`로 표시하지 않는다. V19/V20부터
실제 운영 snapshot과 21/63/126 거래일 outcome을 누적하지만 초기 상태는 `INSUFFICIENT_SAMPLE`이다.

## Alternatives considered

- 점수만 바꾸고 방법론 문구 유지: 사용자가 구·신 산식을 혼동하므로 기각.
- 새 점수를 확률로 변환: calibration 표본이 없어 기각.
- 순위 변화를 숨김: 운영 투명성에 어긋나 기각.

## Accessibility and interaction behavior

방법론과 `자료 없음`은 색상 외 텍스트로도 제공한다. tooltip은 hover뿐 아니라 focus/touch로 접근
가능해야 하며 카드 이동 클릭을 가로채지 않는다.

## Analytics and success criteria

- 같은 연속 입력에서 macro label만 변경한 순위 변동 0건
- 중복 financial-conditions 축 0건
- API/UI methodology 일치
- 전체 composite 적중률은 V19 forward 표본·구간·benchmark·불확실성과 함께 별도 승인 전까지 비노출

## Rollout and rollback

서버와 methodology 문구를 함께 배포한다. 이상 시 애플리케이션 이미지만 롤백하며 원천/snapshot은
보존한다. 새 산식의 과거 성과를 현재 데이터로 역산해 제품 수치로 게시하지 않는다.

## Verification

domain invariance 회귀 테스트, API contract, UI build, 문서 검증, 홈서버 current snapshot 비교를 수행한다.
