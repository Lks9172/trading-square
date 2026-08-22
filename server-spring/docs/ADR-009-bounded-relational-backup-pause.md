# ADR-009: 백엔드 pause는 관계형 복구 지점 캡처에만 제한한다

- 문서 상태: **DECISION**
- 상태: **Accepted / production**
- 결정일: 2026-08-09
- 관련 문서: [백업·복구](BACKUP-RESTORE.md),
  [장애 재발 방지](../../docs/development/INCIDENT-RECURRENCE-PREVENTION.md)

## Context

2026-08-09 03:25~03:35 KST 백업이 `macrosquare-server`를 pause한 채 PostgreSQL 캡처뿐 아니라
MinIO 전체 mirror와 checksum까지 수행했다. Docker journal은 매분 `Container ... is paused`를 남겼고,
pause 해제 직후 Hikari housekeeper가 `10m13s` 지연을 보고했다. 같은 시각 Prometheus scrape의 broken
pipe가 발생했다. DB timeout/deadlock/OOM은 없었으며 원인은 JVM 또는 DB가 아니라 운영 백업의 명시적
`docker pause`였다.

## Decision

1. pause 구간에는 PostgreSQL custom dump, active object version/checksum pointer, 복구 row count만 캡처한다.
2. 캡처 직후 backend를 unpause하고 readiness를 확인한 다음 MinIO mirror와 SHA-256 검증을 수행한다.
3. pause 구간은 기본 20초 hard deadline으로 제한한다. 초과하면 backup을 실패시키고 EXIT trap이 backend를
   반드시 unpause한다.
4. MinIO body는 PostgreSQL이 캡처한 immutable version ID와 SHA-256으로 다시 선택하므로, 장시간 application
   정지는 일관성 조건이 아니다.
5. host recurrence monitor는 Docker health가 과거의 healthy로 남아 있어도 `Paused=true`를 독립 incident로
   탐지하며 paused container에 `docker exec`를 시도하지 않는다.
6. off-host 용량 preflight와 불완전 partial 정리는 pause보다 먼저 실행한다. 복제 공간 부족은 홈서버
   runtime을 변경하지 않고 fail-fast한다.

## Boundaries and consistency

백업 조정은 운영 shell adapter 책임이며 domain/application 코드를 정지 상태에 결합하지 않는다. PostgreSQL은
정형 상태와 object pointer의 권위 원장이고 MinIO version body는 immutable recovery member다. pause deadline
초과 backup은 완료본/LATEST로 승격하지 않는다.

## Alternatives considered

- 전체 object copy 동안 pause 유지: 10분 API/scheduler 정지 때문에 기각.
- pause 없이 서로 다른 시점의 DB query 사용: pointer와 dump의 복구 시점이 어긋날 수 있어 이번 변경에서는
  채택하지 않음.
- PostgreSQL exported snapshot 기반 무정지 백업: 장기적으로 가장 좋지만 운영 shell 복잡도가 증가한다.
  pause 실측이 지속적으로 5초를 넘으면 재검토한다.

## Consequences, rollback, observability

object copy 시간은 그대로지만 backend 정지는 작은 DB 캡처 구간으로 줄어든다. 롤백은 backup script만 직전
버전으로 되돌릴 수 있으나 10분 pause가 재발하므로 권장하지 않는다. backup 로그에 pause 초와 deadline을
남기며 Hikari warning, `RUNTIME_PAUSED`, readiness를 교차검증한다.

## Verification

- shell syntax 및 cutover invariant: deadline 존재, unpause가 MinIO mirror보다 앞섬
- paused-but-healthy runtime monitor 단위 테스트
- 실제 홈서버 backup에서 pause 시간, readiness, Hikari/5xx 부재 확인
