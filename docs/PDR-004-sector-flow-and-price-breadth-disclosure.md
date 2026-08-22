# PDR-004: ETF 생성·환매와 추적 종목 가격 breadth의 출처·날짜·한계를 함께 표시한다

> 이 문서에서 PDR은 **Product Decision Record**를 뜻한다.

- 문서 상태: **DECISION**
- 상태: **Accepted / production**
- 결정일: 2026-08-08
- 관련 ADR: [`../server-spring/docs/ADR-005-official-sector-etf-flow-and-price-breadth.md`](../server-spring/docs/ADR-005-official-sector-etf-flow-and-price-breadth.md)
- 기존 해석 원칙: [`PDR-002`](PDR-002-sector-rotation-evidence-disclosure.md)

## 사용자 문제

“독립 수급”이라는 점수만으로는 실제 ETF 생성·환매인지 가격/유동성 프록시인지 알 수 없고, 높은 섹터
수익률이 구성종목 전반의 상승인지 소수 종목 집중인지도 확인하기 어렵다.

## 결정

- `독립 수급`을 `ETF 생성·환매`로 바꾸고 State Street 공식 기준일, 1·5·20일 달러 흐름, 5·20일
  순자산 대비 비율을 표시한다.
- `가격 breadth`는 점수, 최신 가격일, 추적 종목 coverage, MA20/50/200 위 비율을 표시한다.
- issuer flow가 7일을 넘거나 breadth가 최소 10종목·70% coverage를 충족하지 못하면 `자료 없음`이다.
- breadth에는 “대표 추적 종목 기준이며 ETF 전체 보유종목이 아님”을 가까운 위치에 표시한다.
- 두 점수는 수익 확률·BUY 액션이 아니다. breadth는 현재 진단 표시만 하고 주도 confirmation을 승격하지 않는다.

## 표시·색상·행동

| 값 | 표시 | 사용자 행동 |
|---|---|---|
| flow 60 이상 | 생성 우세 색상 + 실제 금액/비율 | 다른 독립 근거와 함께 확인 |
| flow 41~59 | 중립 색상 | 단독 승격 없음 |
| flow 40 이하 | 환매 우세 경고 + 음수 금액/비율 | 후보 훼손 여부 점검 |
| breadth 60 이상 | 상승 참여 확산 | 소수 대형주 집중 위험 완화 근거 |
| breadth 41~59 | 혼재 | 추가 확인 |
| breadth 40 이하 | 참여 협소 경고 | 섹터 ETF 강세를 구성종목 전체 강세로 해석 금지 |
| 자료 없음 | 회색/명시 문구 | 50점으로 간주하거나 BUY 승격 금지 |

색상만으로 구분하지 않고 금액, 부호, 비율, 날짜, `자료 없음` 문구를 함께 쓴다.

## 오해 방지

- ETF 생성은 ETF 매수 주문이나 기관 투자자의 장중 순매수와 동일하지 않다.
- 발행좌수 변화는 지정참가자 creation/redemption 활동의 일별 근사다.
- 높은 flow/breadth는 저평가, 바닥, 향후 상승 확률을 뜻하지 않는다.
- breadth는 catalog의 현재 대표 종목 기준이므로 역사 전체 ETF 구성과 동일하지 않다.
- score는 비교 편의를 위한 휴리스틱 0~100 index이며 보정된 확률이 아니다.

## null·stale·오류

- 원천 오류는 해당 축만 unavailable로 두고 화면 전체나 마지막 정상 행을 새 기준일로 바꾸지 않는다.
- 저장된 마지막 정상 snapshot이 7일 이내면 원래 observed date와 함께 읽고, 넘으면 현재 판단에서 제외한다.
- flow와 breadth 중 하나만 성공하면 성공한 축만 표시한다.
- loading/tooltip/modal이 카드 이동 클릭을 가로채지 않으며 focus/touch에서도 같은 설명에 접근 가능해야 한다.

## 출시·측정·롤백

- V18과 서버/API/클라이언트를 함께 출시하고, 배포 직후 첫 scheduled refresh가 완료되기 전 `자료 없음`을
  정상으로 허용한다. 최초 refresh 실패는 마지막 정상값의 날짜를 바꾸지 않고 운영 로그·감시에서 오류로 남긴다.
- 성공 기준은 표준 11개별 기준일/금액/coverage/API/UI 일치, stale 차단, proxy의 독립 flow 오표기 0건이다.
- 문제 시 애플리케이션만 롤백하고 V18 snapshot은 보존한다.
- immutable point-in-time ledger와 장기 OOS 검증 전에는 제품 문구를 “예측 적중률 개선”으로 바꾸지 않는다.
