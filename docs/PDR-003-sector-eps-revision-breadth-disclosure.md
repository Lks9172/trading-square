# PDR-003: 섹터 EPS revision breadth의 날짜·coverage·결측을 함께 표시한다

> 이 문서에서 PDR은 **Product Decision Record**를 뜻한다.

- 문서 상태: **DECISION**
- 상태: **Accepted / production**
- 결정일: 2026-08-08
- 관련 ADR: [`../server-spring/docs/ADR-004-dated-sector-eps-revision-breadth.md`](../server-spring/docs/ADR-004-dated-sector-eps-revision-breadth.md)
- 기존 해석 원칙: [`PDR-002`](PDR-002-sector-rotation-evidence-disclosure.md)

## 사용자 문제

섹터의 “실적 개선” 점수만 보면 어떤 날짜의 몇 개 기업을 사용했는지, 실제 추정 상향이 넓은지,
자료가 없는지를 알 수 없다. 이 값이 상승 확률이나 즉시 BUY로 오인될 위험도 있다.

## 결정과 표시 의미

- `EPS 리비전`은 표준 섹터 구성종목별 30일 forward EPS 추정 변화의 **방향 breadth**다.
- 점수 옆에 최신 관측일, coverage, 상향 비율, 하향 비율을 함께 표시한다.
- 최소 5종목 및 50% coverage를 충족하지 못하거나 최신 관측이 기준일보다 3일 넘게 오래되면
  `자료 없음`으로 표시한다.
- 기준일 없는 catalog revision을 현재 점수처럼 표시하지 않는다.
- 이 값은 섹터 조사 우선순위와 confirmation의 한 근거일 뿐, 매수·매도 액션이나 상승 확률이 아니다.

## 라벨·임계값·색상·액션

| 점수 | 짧은 해석 | 색상 원칙 | 액션 의미 |
|---:|---|---|---|
| 55~100 | 상향 종목이 더 넓음 | 긍정색 | 독립 확인축 1개로 사용 가능 |
| 46~54 | 방향 대체로 중립 | 중립색 | 단독 승격 없음 |
| 0~45 | 하향 종목이 더 넓음 | 경고색 | 후보의 실적 확인 약화 |
| 자료 없음 | coverage/date gate 미충족 | 회색/점선 | WATCH에서 임의 승격 금지 |

점수 자체에는 `BUY`, `STRONG BUY`, `SELL` 라벨을 붙이지 않는다. 최종 기업 액션과 섹터 confirmation은
별도 정책 결과다.

## null·stale·loading·error

- `null`: `-`와 “현재 독립 자료 없음”을 표시한다. 50점 막대를 만들지 않는다.
- stale: 최신 구성종목 관측이 3일 gate를 넘으면 현재 증거에서 제외한다.
- loading: 마지막 정상 API 응답이 있으면 stale 경고와 함께 유지하고, 없으면 skeleton/갱신 중을 표시한다.
- error: 화면 전체를 빈 정상 상태로 바꾸지 않고 해당 증거만 unavailable로 표시한다.
- 일부 coverage: 50% 이상이면 비율과 coverage를 동시에 표시해 대표성 한계를 숨기지 않는다.

## 증거와 confidence 고지

- 원천은 Yahoo `earningsTrend` 기반 forward EPS 추정 변화이며 SEC 공식 수치가 아니다.
- 관측일은 provider 값을 시스템이 수집해 analyst snapshot으로 저장한 날짜다.
- 0.10% 초과 상향, -0.10% 미만 하향, 사이는 보합으로 계산한다.
- equal-count breadth이므로 시가총액 영향과 revision magnitude를 반영하지 않는다.
- 0~100 점수는 보정된 확률이 아니다. V17 이후 이력만 point-in-time 검증에 사용할 수 있다.

## 검토한 대안

- 점수만 표시: 대표성·신선도를 알 수 없어 기각
- 자료 없음을 중립 50로 표시: 실제 중립과 혼동돼 기각
- 상향/하향 기업명을 모두 카드에 표시: 정보 과밀과 provider coverage 노출 문제로 상세 drill-down 과제로 보류
- score에 BUY 액션 연결: 전체 composite OOS 검증 전 과장이라 기각

## 접근성과 상호작용

- 색상만으로 방향을 구분하지 않고 숫자·상향/하향·자료 없음 문구를 함께 사용한다.
- tooltip은 hover뿐 아니라 focus/touch에서도 열려야 하며 클릭을 가로채지 않는다.
- 목록과 상세 화면은 같은 용어·threshold를 사용한다.

## 측정과 성공 기준

- API와 UI의 observed date/coverage/up/down/score가 동일하다.
- coverage 49% 또는 유효 4종목은 항상 unavailable이다.
- stale·null이 confirmation을 BUILDING/CONFIRMED로 승격시키지 않는다.
- 기준일 없는 catalog reference가 현재 카드의 EPS 점수로 노출되지 않는다.
- 사용자 문구에 “확률”, “확정 상승”, “즉시 매수”가 없다.

## 출시·롤백

V17과 애플리케이션을 함께 배포한다. 배포 직후 기존 행의 revision이 null이면 `자료 없음`이 정상이며,
새 analyst 수집으로 coverage가 채워진 뒤 자동 노출된다. 문제가 있으면 직전 애플리케이션으로 롤백하되
additive V17 열과 수집 이력은 보존한다.

## 검증

- server domain/application/adapter 테스트
- client test/lint/build와 목록·상세 route smoke
- 홈서버 Flyway V17/API nullable field/DB coverage 실측
- 문서 registry·링크·버전 자동 검증
