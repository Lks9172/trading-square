# PDR-007: 유료화·공개 지연 원천은 장애가 아닌 `LIMITED`로 구분한다

- 문서 상태: **DECISION**
- 상태: **Accepted**
- 결정일: 2026-08-09
- 관련 ADR: [ADR-008](../server-spring/docs/ADR-008-decision-grade-transient-source-gap-policy.md)

## 사용자 문제와 목표

NAAIM public table이 정책적으로 3개월 지연되는데 매 수집을 일반 `DEGRADED`로만 표시하면 파서 장애와
계약상 미제공을 구분할 수 없다. 반대로 지연값을 현재값으로 사용하면 심리 점수가 오염된다.

## 결정과 사용자 의미

- `LIMITED`: 공급자 정책 때문에 특정 현재값을 합법적·신선하게 받을 수 없지만 나머지 원천은 정상 수집된 상태
- `DEGRADED`: 받아야 할 원천의 일시적/비정상 공백
- `FAILED`: usable observation이 없거나 저장에 실패한 상태
- NAAIM public 지연은 `PROVIDER_POLICY_UNAVAILABLE`과 `NAAIM_EXPOSURE`를 함께 노출한다.
- NAAIM은 심리 composite에서 제외하며 coverage는 최대 75%다. 중립값, 3개월 전 값, 새 timestamp를 합성하지
  않는다.
- licensed current endpoint가 연결되고 14일 freshness를 통과하면 자동으로 일반 `SUCCESS`로 돌아간다.

이 상태는 매수/매도 신호가 아니며 다른 심리 component의 평균을 NAAIM 값으로 가장하지 않는다.

## 표시·알림

Telegram과 collection health에는 `심리: 공급자 정책 제한 / 결측 NAAIM_EXPOSURE`로 표시한다. `LIMITED`만
존재할 때 전체 수집 상태를 `DEGRADED`로 과장하지 않지만 coverage와 결측은 숨기지 않는다.

## 채택하지 않은 안

- 공개 지연값 사용: stale positioning 오염 때문에 기각
- 임의의 다른 지표를 NAAIM 이름으로 대체: 의미와 단위가 달라 기각
- 결측 자체를 숨기고 SUCCESS: 데이터 계보를 거짓 표시하므로 기각

## 성공 기준과 재검토

- 정책 제한과 파서/네트워크 장애가 별도 failure type으로 저장됨
- NAAIM 결측 시 심리 coverage 75%, synthetic value 0건
- licensed source 계약 또는 신뢰 가능한 동등 원천을 확보하면 source ADR과 함께 재검토
