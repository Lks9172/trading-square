# MacroSquare 문서 허브

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-22**
- 운영 기준: Java 21 · Spring Boot 4.1.0 · PostgreSQL 18 · MinIO · Next.js 16.3.0

이 문서는 금융 판단과 개발·운영 문서를 찾는 단일 진입점이다. 같은 개념을 여러 문서가 다르게 설명해
장애가 재발하지 않도록 문서의 권위와 상태를 명시한다.

## 권위 순서

충돌할 때는 다음 순서로 판단한다.

1. **Domain 불변식과 PostgreSQL 제약**: 실제로 허용되는 값과 상태
2. **ADR/PDR**: 왜 해당 설계와 제품 의미를 선택했는지
3. **CURRENT 금융·개발 문서**: 운영 코드에서 추출한 설명과 절차
4. **Runbook**: 장애 대응 절차
5. **SNAPSHOT/ARCHIVED 문서**: 당시 조사·마이그레이션·영상 분석 이력

문서가 코드보다 강한 규칙을 주장해서는 안 된다. 반대로 코드 계약이 바뀌면 같은 변경에서 ADR/PDR,
관련 CURRENT 문서와 자동 문서 검증 계약을 함께 갱신한다.

## 금융 관점

| 문서 | 목적 |
|---|---|
| [금융 문서 안내](finance/README.md) | 투자 판단 문서의 읽는 순서와 용어 |
| [금융 의사결정 모델](finance/FINANCIAL-DECISION-MODEL.md) | 거시→자산→섹터→기업의 전체 판단 구조 |
| [기업 점수·액션·바닥/반전](finance/COMPANY-SCORECARD.md) | Company/B/최종 액션과 신호의 정확한 의미 |
| [MACD 교차·다이버전스](finance/MACD-TIMING-METHODOLOGY.md) | 일봉·주봉 MACD, 교차, histogram, point-in-time 다이버전스 |
| [데이터 원천·신선도](finance/DATA-SOURCES-AND-FRESHNESS.md) | 원천별 출처, 주기, stale/fallback 계약 |
| [백테스트·모델 거버넌스](finance/BACKTEST-AND-MODEL-GOVERNANCE.md) | 미래정보 차단, point-in-time, 성능 표기 기준 |
| [섹터 순환 플레이북](finance/SECTOR-ROTATION-PLAYBOOK.md) | 현재/다음/다다음 주도 섹터의 사용법 |
| [섹터 순환 2026-08-08 전수검사](finance/SECTOR-ROTATION-AUDIT-2026-08-08.md) | 유니버스·결측·수급·백테스트 감사 결과와 남은 한계 |
| [코인·타 자산 해석](finance/CRYPTO-AND-CROSS-ASSET.md) | 코인장 우선 판단과 자산별 신호 한계 |

## 개발·운영 관점

| 문서 | 목적 |
|---|---|
| [개발 문서 안내](development/README.md) | 아키텍처·운영 문서의 읽는 순서 |
| [시스템 아키텍처](development/SYSTEM-ARCHITECTURE.md) | DDD/클린 아키텍처, 컨텍스트, 런타임 구성 |
| [데이터 계약·계보](development/DATA-CONTRACTS-AND-LINEAGE.md) | source→정규화→계산→projection→UI 경로 |
| [스케줄러·동시성·멱등성](development/SCHEDULERS-CONCURRENCY-IDEMPOTENCY.md) | 수집 주기, 락, single-flight, outbox |
| [테스트·품질 게이트](development/TESTING-AND-QUALITY-GATES.md) | 변경 유형별 필수 테스트와 배포 차단 조건 |
| [장애 재발 방지](development/INCIDENT-RECURRENCE-PREVENTION.md) | 과거 장애 유형, 영구 가드, 탐지와 대응 |
| [배포·롤백·복구](development/DEPLOYMENT-ROLLBACK-RECOVERY.md) | 홈서버 배포, 검증, 자동 롤백, 백업 |
| [공개 API 표면](development/API-SURFACE.md) | 45개 공개 API의 소유 컨텍스트와 계약 |
| [일일/실시간 관측 Runbook](RUNBOOK-daily-observability-audit.md) | 로그·메트릭·트레이스·DB 기반 장애 조사 |

## 의사결정 기록

- [문서 거버넌스와 변경 규칙](DOCUMENT-GOVERNANCE.md)
- [변경·검증·관측 추적 매트릭스](development/CHANGE-TRACEABILITY-MATRIX.md)
- [ADR/PDR 목록과 작성 규칙](DECISION-RECORDS.md)
- [ADR-001: PostgreSQL·MinIO 저장 경계](../server-spring/docs/ADR-001-storage-and-database-boundaries.md)
- [ADR-002: 섹터 총수익률 위험조정 모멘텀](../server-spring/docs/ADR-002-sector-rotation-total-return-momentum.md)
- [ADR-003: 섹터 순환 증거 무결성](../server-spring/docs/ADR-003-sector-rotation-evidence-integrity.md)
- [ADR-004: 날짜가 있는 섹터 EPS revision breadth](../server-spring/docs/ADR-004-dated-sector-eps-revision-breadth.md)
- [ADR-005: 공식 섹터 ETF 생성·환매와 가격 breadth](../server-spring/docs/ADR-005-official-sector-etf-flow-and-price-breadth.md)
- [ADR-007: immutable 섹터 composite/OOS 원장](../server-spring/docs/ADR-007-immutable-sector-rotation-validation-ledger.md)
- [ADR-006: 섹터 순환 거시 입력 중복 제거](../server-spring/docs/ADR-006-sector-rotation-macro-decorrelation.md)
- [ADR-012: point-in-time 순유동성 방향·전환](../server-spring/docs/ADR-012-direction-first-net-liquidity-impulse.md)
- [ADR-013: 수집 membership과 현재 섹터 평가 분리](../server-spring/docs/ADR-013-isolate-universe-membership-from-current-sector-assessment.md)
- [ADR-014: provider 집약형 startup 작업 순차 실행](../server-spring/docs/ADR-014-stagger-provider-heavy-startup-work.md)
- [ADR-015: 현재 기업 알림 근거 영속 재사용](../server-spring/docs/ADR-015-reuse-current-company-notification-evidence.md)
- [ADR-016: 재기동 시 최근 영속 13F 수집 재사용](../server-spring/docs/ADR-016-reuse-recent-durable-13f-collection-on-startup.md)
- [ADR-017: 공통 point-in-time MACD 기술분석 커널](../server-spring/docs/ADR-017-shared-point-in-time-macd-technical-kernel.md)
- [ADR-018: 영속 MACD 알림 근거와 bounded-context ACL](../server-spring/docs/ADR-018-persist-compact-macd-notification-evidence.md)
- [PDR-001: 섹터 순환 제품 해석](PDR-001-sector-rotation-product-interpretation.md)
- [PDR-002: 섹터 순환 증거 노출](PDR-002-sector-rotation-evidence-disclosure.md)
- [PDR-003: 섹터 EPS revision breadth 노출](PDR-003-sector-eps-revision-breadth-disclosure.md)
- [PDR-004: 섹터 ETF flow·가격 breadth 노출](PDR-004-sector-flow-and-price-breadth-disclosure.md)
- [PDR-005: 섹터 순환 거시 점수 해석](PDR-005-sector-rotation-macro-score-disclosure.md)
- [PDR-009: 유동성 방향·전환 제품 해석](PDR-009-liquidity-impulse-product-interpretation.md)
- [PDR-010: 현재 섹터 순환 unavailable 상태](PDR-010-current-sector-rotation-unavailable-state.md)
- [PDR-011: 즉시 startup snapshot과 지연된 후보 재계산](PDR-011-startup-snapshot-and-delayed-candidate-recalculation.md)
- [PDR-012: MACD 타이밍 신호 노출](PDR-012-macd-timing-signal-disclosure.md)
- [PDR-013: Telegram MACD 타이밍 노출](PDR-013-telegram-macd-timing-disclosure.md)
- [PDR-014: 기업 Telegram 찐바닥 후보 완화](PDR-014-relax-company-telegram-bottom-threshold.md)

## 상태가 다른 문서

| 상태 | 의미 | 변경 원칙 |
|---|---|---|
| `CURRENT` | 현재 운영 코드와 자동 대조되는 기준 | 코드 변경과 같은 PR에서 갱신 |
| `DECISION` | 채택된 ADR/PDR | 대체 시 Superseded 처리 후 새 기록 작성 |
| `SNAPSHOT` | 특정 날짜의 감사·성과·TODO 상태 | 당시 증거 보존, 현재 계약으로 사용 금지 |
| `ARCHIVED` | Node/마이그레이션/과거 설계 이력 | 운영 구현 판단에 사용 금지 |

`scripts/verify-documentation.py`가 CURRENT 문서의 존재, 내부 링크, 버전·유니버스·스케줄 핵심 계약,
ADR/PDR 등록 여부를 CI와 배포 전에 검사한다.
