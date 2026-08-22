# PDR-014: 기업 Telegram 후보는 찐바닥 후보와 독립 반전 확인의 결합을 허용한다

> 이 문서에서 PDR은 **Product Decision Record**를 뜻한다.

- 문서 상태: **DECISION**
- 상태: **Accepted / production pending deployment verification**
- 결정일: **2026-08-22**
- 관련 문서: [기업 점수·액션·바닥/반전](finance/COMPANY-SCORECARD.md),
  [PDR-013](PDR-013-telegram-macd-timing-disclosure.md)

## 사용자 문제와 목표

기존 기업 Telegram 편입은 `찐바닥 CONVICTION`을 필수로 요구했다. 이 기준은 투매성 급락을 포착하는
데 유용하지만, 급락 강도가 CONVICTION까지 이르지 않아도 독립 가격 구조와 OBV/VWAP 거래량이 실제
반전을 확인한 종목을 모두 제외했다. 알림은 자동 주문이 아니라 추가 조사 후보이므로, 바닥 강도와
반전 확인을 분리해 보되 단순 과매도나 가격 반등만으로 편입해서는 안 된다.

## 제품 결정

1. 기업 Telegram 신규 편입과 startup 표시 조건을 다음으로 통일한다.
   - 기업 총점 70 이상
   - B점수 70 이상
   - 찐바닥 `CANDIDATE` 또는 `CONVICTION`
   - 반전확인 `ON` 또는 `STRONG`
2. `ON`은 찐바닥 후보 이상이면서 저점 이후 1차/구조 확인 marker가 있고, 독립 OBV/VWAP 62 이상,
   독립 가격 구조 60 이상, 합성 반전 점수 68 이상일 때만 허용한다.
3. `STRONG`은 기존대로 `CONVICTION`, 구조적 바닥, marker, OBV/VWAP 72 이상, 가격 구조 68 이상,
   합성 반전 점수 78 이상을 모두 요구한다.
4. 실행 액션, 섹터 주도 여부, MACD는 편입 필터가 아니다. 메시지에 별도 참고 근거로 표시한다.
5. 가격·거래량 현재 검증이 실패하면 과거 후보를 되살리지 않고 `UNMET/OFF`로 닫는다.

## 해석과 제한

- `CANDIDATE + ON`은 **관찰할 가치가 생긴 반전 확인 후보**이지 확정 수익이나 즉시 전액 매수 신호가
  아니다.
- 반전 점수와 Company/B점수는 확률이 아니며, 이 임계값 변경 자체의 미래 수익률은 아직 전진 검증되지
  않았다.
- MACD 상방·주도 섹터·실행 액션이 불리할 수 있으므로 Telegram 본문에서 각 근거를 함께 확인한다.
- 현재 원천 기준일과 결측 정책은 기존 company summary 계약을 그대로 따른다.

## 채택하지 않은 안

- 찐바닥 조건만 CANDIDATE로 바꾸고 반전 `ON` 생성은 CONVICTION에 묶어두기: 실질적으로 후보가 새로
  편입될 수 없는 무효 완화라 기각.
- 모든 `EARLY` 허용: 독립 가격·거래량 확인이 부족한 기술적 반등까지 대량 편입하므로 기각.
- 반전 점수 60 이상만 허용: 높은 바닥 점수가 낮은 OBV/VWAP·가격 구조를 가릴 수 있어 기각.
- 실행 액션 BUY 필수: 알림은 증거 강화 탐지이고 실행 정책과 목적이 달라 유지하지 않음.

## 검증·관측·롤백

- Domain test가 `CANDIDATE + 독립 가격/수급 충족 → ON`과 미충족 `EARLY`를 함께 고정한다.
- Notification policy test가 CANDIDATE/CONVICTION 허용, UNMET 차단, 총점/B 70 경계를 검증한다.
- Application test가 후보 편입 메시지의 상태·조건 문구와 transactional outbox 생성을 검증한다.
- 배포 후 후보 snapshot과 실제 Telegram payload를 확인한다.
- 과도한 후보·오탐이 관찰되면 notification 조건을 CONVICTION으로 롤백할 수 있으며 DB migration은
  필요하지 않다.

## 재검토 조건

- CANDIDATE+ON의 워크포워드 표본이 충분히 쌓여 CONVICTION+ON 대비 손실·낙폭이 유의하게 악화됨
- 메시지 후보 수가 반복적으로 운영 한도를 초과함
- 실행 액션 또는 섹터 레짐을 알림 필수 gate로 바꾸라는 명시적 제품 결정이 내려짐
