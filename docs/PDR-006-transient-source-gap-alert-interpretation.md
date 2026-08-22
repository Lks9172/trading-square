# PDR-006: 일시적 원천 공백과 데이터 무결성 경보의 제품 해석

- 상태: **Accepted**
- 결정일: **2026-08-09**
- 관련 ADR: [ADR-008](../server-spring/docs/ADR-008-decision-grade-transient-source-gap-policy.md)

## 사용자 문제

가격·점수 데이터가 실제로 오염되지 않았는데도 Yahoo FX 한 key의 순간 공백마다 장애/회복 Telegram이
반복되면 중요한 무결성 경보를 무시하게 된다.

## 결정과 표시 의미

- `DEGRADED`: 이번 수집에 일부 공백이 있었다는 원천 상태다.
- 데이터 무결성 hard alert: 현재 의사결정에 쓸 검증된 값이 없거나 허용 신선도를 넘었다는 상태다.
- Yahoo의 `USDKRW`/`USDJPY`만 실패하고 30분 이내 실제 직전 관측이 있으면 `DEGRADED`는 유지하되
  hard alert는 유예한다.
- Yahoo가 `JPY=X` 대신 `USDJPY=X`처럼 같은 USD-base alias를 반환하면 정상 quote로 수용한다.
- NAAIM public table의 3개월 지연은 [PDR-007](PDR-007-provider-policy-limited-data-state.md)의
  `LIMITED`로 표시하며 심리점수에 포함하지 않는다. licensed current source가 연결되기 전 coverage는
  최대 75%일 수 있다.
- 30분 안에 회복하지 않거나 다른 key도 실패하면 hard alert와 BUY fail-closed가 적용된다.

이 정책은 매수/매도 신호나 confidence 점수가 아니다.

## 임계값·색상·액션

새 점수, 색상 또는 투자 액션은 추가하지 않는다. 30분은 5분 Yahoo 수집 최대 여섯 번을 허용하고 기존
Yahoo stale 기준과 일치시키는 운영 경계다. hard alert가 켜지면 기존대로 관련 신규 BUY 후보를 차단한다.

## null·stale·loading·error

- fresh prior: 실제 직전 값과 원래 `collected_at`을 유지
- 30분 초과: stale/hard failure
- 이전 값 없음: 즉시 unavailable/hard failure
- 전체 batch 실패 또는 저장 불일치: 즉시 hard failure
- NAAIM public 지연: DB DEGRADED·제품 LIMITED/현재값 제외; 과거값을 새 timestamp로 복제하지 않음
- 선택적 NAAIM 공백과 Yahoo FX 유예는 서로 다른 명시 정책으로 관리

## 근거와 한계 고지

운영 로그에서 FX 단일 key가 다음 수집에 회복하는 반복 패턴을 확인했다. 이 정책은 Yahoo의 장기
가용성을 보장하지 않고, stale 값을 최신 값으로 간주하지 않는다.

## 검토한 대안

모든 provider WARN을 Telegram으로 보내는 안은 alert fatigue 때문에, FX 공백을 영구 무시하는 안은
실제 거시 입력 손실을 숨기므로 채택하지 않았다.

## 접근성과 상호작용

현재 UI 계약은 바뀌지 않는다. 향후 수집 상태를 UI에 노출할 때 `부분 수집`과 `의사결정 불가`를 같은
색/문구로 합치지 않는다.

## 성공 기준

- fresh FX fallback 동안 반복 alert/recovery 0
- 30분 초과 또는 값 부재 시 hard alert 1
- `DEGRADED` DB 증거와 일일 audit 보존
- 점수·액션·관측 timestamp 변조 0

## 출시와 롤백

server image만 교체한다. 문제가 있으면 직전 image로 롤백하며 데이터 repair는 하지 않는다.

## 검증

adapter 단위 테스트, 실제 PostgreSQL freshness 경계 테스트, 배포 후 1시간 Loki/outbox 관측으로
확인한다.
