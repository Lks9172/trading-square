# 의사결정 기록 목록

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-21**

Architecture Decision Record(ADR)는 기술·아키텍처 불변식과 트레이드오프를, Product Decision
Record(PDR)는 사용자에게 노출되는 의미·행동·안전 기준을 기록한다.

결정이 바뀌면 기존 문서를 삭제하거나 조용히 수정하지 않는다. 기존 상태를 `Superseded`로 바꾸고 새
문서에서 대체 이유와 migration/rollback 경로를 기록한다.

## ADR

| 문서 | 상태 | 결정일 | 결정 |
|---|---|---|---|
| [ADR-001](../server-spring/docs/ADR-001-storage-and-database-boundaries.md) | Accepted | 2026-07-21 | PostgreSQL과 MinIO의 저장 경계 |
| [ADR-002](../server-spring/docs/ADR-002-sector-rotation-total-return-momentum.md) | Accepted | 2026-08-08 | 섹터 순환 상대강도에 총수익률 위험조정 모멘텀 사용 |
| [ADR-003](../server-spring/docs/ADR-003-sector-rotation-evidence-integrity.md) | Accepted | 2026-08-08 | 표준/테마 유니버스·결측·독립 증거·원자적 단면 계약 |
| [ADR-004](../server-spring/docs/ADR-004-dated-sector-eps-revision-breadth.md) | Accepted | 2026-08-08 | 날짜·coverage가 있는 구성종목 EPS revision breadth 계약 |
| [ADR-005](../server-spring/docs/ADR-005-official-sector-etf-flow-and-price-breadth.md) | Accepted | 2026-08-08 | 공식 ETF 생성·환매와 추적 구성종목 가격 breadth 계약 |
| [ADR-006](../server-spring/docs/ADR-006-sector-rotation-macro-decorrelation.md) | Accepted | 2026-08-08 | 거시 파생 label 재입력과 중복 financial-conditions 축 제거 |
| [ADR-007](../server-spring/docs/ADR-007-immutable-sector-rotation-validation-ledger.md) | Accepted | 2026-08-08 | 완료된 공통 거래일 기준 immutable composite/OOS outcome 원장 |
| [ADR-008](../server-spring/docs/ADR-008-decision-grade-transient-source-gap-policy.md) | Accepted | 2026-08-09 | fresh 직전값이 있는 일시적 FX 공백의 bounded retry·hard alert 유예 |
| [ADR-009](../server-spring/docs/ADR-009-bounded-relational-backup-pause.md) | Accepted | 2026-08-09 | 관계형 복구 지점만 bounded pause하고 MinIO copy 전에 backend 재개 |
| [ADR-010](../server-spring/docs/ADR-010-change-scoped-build-test-and-deployment.md) | Accepted | 2026-08-09 | 변경 범위 기반 테스트·단일 서비스 배포와 금융 안전 게이트 승격 |
| [ADR-011](../server-spring/docs/ADR-011-corporate-action-cache-quarantine-and-incident-reminders.md) | Accepted | 2026-08-11 | 기업 가격 basis-break cache 격리·복구 우선순위·지속 장애 reminder 제한 |
| [ADR-012](../server-spring/docs/ADR-012-direction-first-net-liquidity-impulse.md) | Accepted | 2026-08-16 | point-in-time 순유동성 방향·전환·전달 스트레스 우선 계약 |
| [ADR-013](../server-spring/docs/ADR-013-isolate-universe-membership-from-current-sector-assessment.md) | Accepted | 2026-08-17 | 수집 membership과 현재 섹터 순환 평가의 장애 경계 분리 |
| [ADR-014](../server-spring/docs/ADR-014-stagger-provider-heavy-startup-work.md) | Accepted | 2026-08-17 | 영속 snapshot 뒤 공급자 집약형 startup 작업 순차 실행 |
| [ADR-015](../server-spring/docs/ADR-015-reuse-current-company-notification-evidence.md) | Accepted | 2026-08-17 | 현재 기업 알림 근거 영속 재사용으로 중복 차트 계산 제거 |
| [ADR-016](../server-spring/docs/ADR-016-reuse-recent-durable-13f-collection-on-startup.md) | Accepted | 2026-08-17 | 재기동 시 최근 영속 13F 수집 재사용으로 중복 holding 처리 제거 |
| [ADR-017](../server-spring/docs/ADR-017-shared-point-in-time-macd-technical-kernel.md) | Accepted | 2026-08-21 | 기업·시장 공통 point-in-time MACD 교차·다이버전스 커널 |
| [ADR-018](../server-spring/docs/ADR-018-persist-compact-macd-notification-evidence.md) | Accepted | 2026-08-21 | 현재 MACD 알림 근거를 영속 요약과 bounded-context ACL로 재사용 |

## PDR

| 문서 | 상태 | 결정일 | 결정 |
|---|---|---|---|
| [PDR-001](PDR-001-sector-rotation-product-interpretation.md) | Accepted | 2026-08-08 | 섹터 순환 신호의 제품 해석과 노출 원칙 |
| [PDR-002](PDR-002-sector-rotation-evidence-disclosure.md) | Accepted | 2026-08-08 | 섹터 유니버스·자료 없음·확인 근거 노출 원칙 |
| [PDR-003](PDR-003-sector-eps-revision-breadth-disclosure.md) | Accepted | 2026-08-08 | 섹터 EPS revision의 날짜·coverage·결측 노출 원칙 |
| [PDR-004](PDR-004-sector-flow-and-price-breadth-disclosure.md) | Accepted | 2026-08-08 | ETF 생성·환매와 추적 가격 breadth의 출처·한계 노출 원칙 |
| [PDR-005](PDR-005-sector-rotation-macro-score-disclosure.md) | Accepted | 2026-08-08 | 연속 거시 근거를 한 번만 사용한 관찰 우선순위 해석 |
| [PDR-006](PDR-006-transient-source-gap-alert-interpretation.md) | Accepted | 2026-08-09 | 부분 수집과 의사결정 불가 hard alert를 구분 |
| [PDR-007](PDR-007-provider-policy-limited-data-state.md) | Accepted | 2026-08-09 | 공급자 정책상 현재 데이터 미제공을 LIMITED로 명시 |
| [PDR-008](PDR-008-empty-sector-candidate-watchlist.md) | Accepted | 2026-08-09 | 확정 후보 공백과 승격 전 관찰 순위를 구분 노출 |
| [PDR-009](PDR-009-liquidity-impulse-product-interpretation.md) | Accepted | 2026-08-16 | 유동성 총량·방향·전환·전달을 분리 노출 |
| [PDR-010](PDR-010-current-sector-rotation-unavailable-state.md) | Accepted | 2026-08-17 | 현재 증거 부족과 정상 후보 공백을 구분 |
| [PDR-011](PDR-011-startup-snapshot-and-delayed-candidate-recalculation.md) | Accepted | 2026-08-17 | 즉시 startup snapshot과 안정화 뒤 후보 전수 재계산 분리 |
| [PDR-012](PDR-012-macd-timing-signal-disclosure.md) | Accepted | 2026-08-21 | MACD 교차·다이버전스를 점수와 분리한 타이밍 보조 근거로 노출 |
| [PDR-013](PDR-013-telegram-macd-timing-disclosure.md) | Accepted | 2026-08-21 | Telegram 후보·시장 메시지에 compact MACD 타이밍 근거 노출 |
| [PDR-014](PDR-014-relax-company-telegram-bottom-threshold.md) | Accepted | 2026-08-22 | 찐바닥 후보와 독립 반전 ON을 결합한 기업 Telegram 편입 허용 |

## 작성 규칙

### ADR 필수 항목

- 상태, 결정일, 관련 문서
- 맥락과 해결할 문제
- 채택한 결정과 아키텍처 경계
- 검토한 대안과 기각 이유
- 긍정적·부정적 결과
- 운영 불변식, 관측, 배포와 롤백
- 재검토 조건

### PDR 필수 항목

- 사용자 문제와 제품 목표
- 사용자에게 보이는 의미와 행동
- 채택하지 않은 제품안
- 기능·데이터·사용자 안전 성공 기준
- 출시 결과와 후속 측정
- 재검토 조건

## 변경 완료 규칙

- 운영 산식·저장 경계·실패 의미가 달라지면 코드와 같은 변경에서 ADR을 추가하거나 갱신한다.
- 사용자에게 보이는 점수 의미·액션·경고·실행 기준이 달라지면 PDR을 추가하거나 갱신한다.
- Accepted 기록을 조용히 덮어쓰지 않는다. 의미가 바뀌면 기존 기록은 `Superseded`로 전환하고 대체
  기록을 연결한다.
- 모든 `ADR-*.md`, `PDR-*.md`는 이 목록에 등록되어야 하며
  `scripts/verify-documentation.py`가 누락을 차단한다.
