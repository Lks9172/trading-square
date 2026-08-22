# 문서 거버넌스와 변경 규칙

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-08**

## 1. 목적

문서는 구현 이후 작성하는 설명문이 아니라 재발 방지 계약이다. 금융 산식, 데이터 의미, 운영 절차가
여러 문서에서 서로 달라지면 오래된 설명을 따라 같은 장애를 다시 만들게 된다. 따라서 모든 문서는
상태와 권위를 명시하고, 코드 계약 변경과 같은 변경 단위에서 검증한다.

## 2. 상태

| 상태 | 의미 | 사용 가능 범위 |
|---|---|---|
| `CURRENT` | 현재 운영 코드와 대조된 규범 문서 | 구현·운영·장애 대응의 현재 기준 |
| `DECISION` | Accepted/Superseded 상태를 가진 ADR/PDR | 결정 이유와 대안·트레이드오프 |
| `SNAPSHOT` | 특정 날짜의 감사·TODO·조사 결과 | 당시 증거만 재현, 현재 계약으로 사용 금지 |
| `ARCHIVED` | Node/마이그레이션/과거 설계 | 역사 참조만 가능 |

CURRENT 문서는 `최종 코드 대조일`을 적는다. 날짜가 최신이라는 이유만으로 자동 신뢰하지 않고 CI가
코드의 버전·유니버스·migration·API·주기 계약과 대조한다.

## 3. 권위와 단일 소유자

1. Domain policy와 DB constraint: 허용 상태와 금융 불변식
2. ADR/PDR: 설계와 제품 의미의 결정 이유
3. CURRENT 금융·개발 문서: 산식, 데이터 계보, 절차
4. Runbook: 관측과 복구 순서
5. SNAPSHOT/ARCHIVED: 당시 참고자료

금융 임계값은 Controller, UI, 문서에서 재계산하지 않는다. Domain의 상수와 정책이 소유하고 문서는 그
의미를 설명한다. 수집 주기는 scheduler/config가 소유하며 문서는 운영 override를 기록한다.

## 4. 변경 유형별 문서 동시 갱신

| 변경 | 반드시 갱신할 문서/기록 |
|---|---|
| Company/B/액션/바닥/반전 산식 | Company Scorecard, 금융 모델, backtest, 필요 시 PDR |
| 데이터 source·TTL·fallback | 데이터 원천·신선도, lineage, scheduler |
| DB schema/constraint | lineage, recurrence catalog, migration/ADR |
| scheduler/concurrency | scheduler 계약, runbook, 운영 검증 |
| API 필드/route | API surface, smoke, frontend consumer |
| 배포/rollback/backup | deployment 문서, runbook, ADR |
| 사용자 액션 문구·위험 의미 | PDR, 금융 문서, UI tooltip 계약 |

## 5. 한 번 난 장애의 문서화 완료 조건

장애를 단순히 “수정 완료”로 기록하지 않는다. 다음을 남긴다.

- 최초 증거와 사용자·금융 영향
- root cause와 오염 가능 범위
- 코드/DB/test/monitor 중 최소 두 층, 금융 오류는 가능하면 세 층의 영구 가드
- 기존 데이터의 읽기 전용 감사와 repair 여부
- 배포·rollback·운영 실측
- 실패 카탈로그 ID와 관련 ADR/PDR

재발 방지 맵은 [변경 추적 매트릭스](development/CHANGE-TRACEABILITY-MATRIX.md), 실제 장애 유형은
[장애 재발 방지](development/INCIDENT-RECURRENCE-PREVENTION.md)를 따른다.

## 6. 자동 검증

```bash
python3 scripts/verify-documentation.py
```

검증기는 다음을 차단한다.

- 필수 CURRENT/DECISION/SNAPSHOT/ARCHIVED 상태 누락
- 깨진 내부 Markdown 링크
- Java/Spring/Next, 정책 version, 유니버스, 계산 version, Flyway, API/smoke 수의 문서 drift
- 핵심 운영 schedule drift
- ADR/PDR registry 누락

검증 계약을 우회하기보다 코드 변경과 함께 기대값과 설명을 의도적으로 갱신한다.
