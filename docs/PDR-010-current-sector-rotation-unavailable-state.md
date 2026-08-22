# PDR-010: 현재 섹터 순환 증거 부족을 후보 없음과 구분한다

> 이 문서에서 PDR은 **Product Decision Record**를 뜻한다.

- 문서 상태: **DECISION**
- 상태: **Accepted / production**
- 결정일: 2026-08-17
- 관련 ADR: [`ADR-013`](../server-spring/docs/ADR-013-isolate-universe-membership-from-current-sector-assessment.md)
- 관련 PDR: [`PDR-008`](PDR-008-empty-sector-candidate-watchlist.md)

## 사용자 문제

`nextCandidates=[]`는 현재 데이터로 조건을 통과한 후보가 없다는 정상 결과다. 반면 표준 11개 중 70% 미만의
단기·중기 momentum 또는 핵심 거시 입력이 없는 상태는 계산 자체가 불가능하다. 둘을 같은 빈 화면이나 500으로
표현하면 사용자가 정상 미충족과 수집·계산 대기를 구분할 수 없다.

## 제품 결정

1. 증거가 충분하고 후보만 없으면 기존처럼 빈 후보와 `확정 후보 아님` 관찰 순위를 표시한다.
2. 현재 순환 평가가 불가능하면 API는 HTTP 503과
   `Current sector rotation data is temporarily unavailable`을 반환한다.
3. 입력 부족 시 captured 점수, 0점 또는 중립값으로 현재/다음/다다음 후보를 합성하지 않는다.
4. 기업·analyst·earnings 수집은 정적 membership을 사용하므로 이 unavailable 상태와 독립적으로 계속된다.
5. 503은 매도·축소·후보 없음 신호가 아니라 **현재 판단 보류**다.

## 성공 기준과 한계

- 현재 증거 부족이 HTTP 500이나 빈 정상 후보로 위장되지 않는다.
- 수집 배치가 순환 API unavailable 때문에 중단되지 않는다.
- 503이 지속되면 실시간 로그/HTTP 탐지와 데이터 무결성 경보가 발생한다.
- 이 계약은 순환 예측 정확도를 높이는 변경이 아니라 거짓 현재 신호와 연쇄 장애를 차단하는 변경이다.
