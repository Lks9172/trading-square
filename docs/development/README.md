# 개발·운영 문서 안내

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-17**

## 읽는 순서

1. [시스템 아키텍처](SYSTEM-ARCHITECTURE.md)
2. [데이터 계약·계보](DATA-CONTRACTS-AND-LINEAGE.md)
3. [스케줄러·동시성·멱등성](SCHEDULERS-CONCURRENCY-IDEMPOTENCY.md)
4. [테스트·품질 게이트](TESTING-AND-QUALITY-GATES.md)
5. [장애 재발 방지](INCIDENT-RECURRENCE-PREVENTION.md)
6. [변경·검증·관측 추적 매트릭스](CHANGE-TRACEABILITY-MATRIX.md)
7. [배포·롤백·복구](DEPLOYMENT-ROLLBACK-RECOVERY.md)
8. [공개 API 표면](API-SURFACE.md)
9. [관측 Runbook](../RUNBOOK-daily-observability-audit.md)
10. [문서 거버넌스](../DOCUMENT-GOVERNANCE.md)

## 변경 완료의 정의

코드가 동작하는 것만으로 완료가 아니다. 아래 항목이 모두 필요하다.

- owning bounded context가 명확함
- Domain/application/adapters 경계 유지
- 정상·결측·stale·부분실패·동시성 경로 테스트
- 과거 동일 장애의 영구 가드 또는 기존 가드 재사용
- source/as-of/version/freshness가 API에서 설명 가능
- DB migration과 persistence constraint 검토
- 로그·메트릭·트레이스·business integrity 탐지 경로 존재
- ADR/PDR와 CURRENT 문서 갱신
- 로컬 검증, 홈서버 preflight, smoke, 배포 후 실측
- 롤백 가능성과 last-valid 데이터 보존 확인

## 가장 중요한 원칙

1. 이전에 발생한 장애는 회귀 테스트·DB 제약·운영 탐지 중 최소 두 층으로 막는다.
2. 빈 값과 오래된 값을 정상값으로 보정해 BUY를 만들지 않는다.
3. 외부 I/O 실패가 기존 정상 projection을 지우지 않게 한다.
4. 단일 ticker 성공이 전체 batch 성공으로 보이지 않게 한다.
5. 문서 변경도 CI와 배포의 검증 대상이다.
