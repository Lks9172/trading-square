# ADR-010: 변경 범위에 맞춰 테스트·빌드·배포하되 금융 안전 게이트는 승격한다

- 문서 상태: **DECISION**
- 상태: **Accepted / production**
- 결정일: 2026-08-09
- 관련 문서: [배포·롤백·복구](../../docs/development/DEPLOYMENT-ROLLBACK-RECOVERY.md),
  [테스트·품질 게이트](../../docs/development/TESTING-AND-QUALITY-GATES.md)

## Context

기존 `deploy-home.sh`는 문서나 단일 backend 수정에도 server/client/observability 전체 동기화, 실제
PostgreSQL 통합 테스트, 두 production image build와 두 container 교체를 매번 실행했다. 43-route smoke와
무결성 검증은 빠르지만, 관계없는 image build·startup 수집·대용량 backup을 반복하면 한 작업이 50분 이상
걸리고 재시작 알림과 외부 공급자 호출도 불필요하게 증가한다.

검증을 줄이는 것이 아니라 **변경의 영향 경계와 검증 강도를 일치**시켜야 한다. 금융 산식·DB·공통 runtime
계약은 계속 fail-closed여야 하고, 문서·운영 script 변경은 healthy application을 재시작해서는 안 된다.

## Decision

1. `deploy-home.sh`는 기본 `--auto` dispatcher다. 로컬과 홈서버의 rsync itemized content/delete 차이와
   compose checksum을 비교해 `verify`, `docs`, `scripts`, `server`, `client`, `full` 중 최소 안전 범위를 고른다.
2. compose/observability 변경 또는 server와 client 동시 변경은 무조건 `full`로 승격한다.
3. server의 Maven POM, Flyway migration, persistence adapter, PostgreSQL integration 변경은 release 범위로
   승격해 48시간 이내 검증 backup과 실제 PostgreSQL multi-instance test를 요구한다.
4. server/client scoped cutover도 직전 image rollback tag, production-profile preflight/readiness, running image
   ID, API/UI smoke, 전체 `verify-home.sh`를 유지한다. 관계없는 container만 재시작하지 않는다.
5. scripts/docs 배포는 checksum mirror와 정적·운영 검증만 수행하고 application container를 재시작하지 않는다.
6. 테스트는 `fast`, `standard`, `release`로 분리한다. standard는 backend/frontend/ops를 병렬 실행하고,
   release만 clean build와 실제 PostgreSQL 검증을 추가한다.
7. 4.7GiB MinIO 전체 backup은 DB/storage/backup 계약 변경 또는 야간 maintenance에서만 실행한다. 일반 코드
   배포는 최근 검증 recovery point의 존재·나이만 확인한다.

## Boundaries

이 결정은 build/deploy adapter와 운영 절차만 바꾼다. domain/application의 금융 산식, PostgreSQL/MinIO 소유권,
API 계약 및 사용자에게 보이는 정보량은 변경하지 않는다. 변경 감지는 Git 상태가 아니라 실제 배포 입력과
원격 tree를 비교하므로 오래된 worktree나 untracked migration history에도 의존하지 않는다.

## Alternatives considered

- 모든 변경에 full deploy 유지: 가장 단순하지만 관계없는 rebuild/restart 비용과 외부 호출이 과도해 기각.
- Git diff만으로 범위 선택: 현재 운영 tree와 실제 원격 상태를 증명하지 못해 기각.
- smoke와 `verify-home` 축소: 금융 데이터 오염과 running-image drift를 놓칠 수 있어 기각.
- DB 변경마다 동기식 full object backup: 복구 안전성은 높지만 immutable object 4.7GiB 재복제가 배포 critical
  path를 15분 이상 늘린다. 최근 checksum 완료본을 release gate로 사용하고 야간 backup을 유지한다.

## Consequences and rollback

- 단일 server/client 변경은 관계없는 image build와 container restart를 제거한다.
- docs/scripts 변경은 무중단으로 반영된다.
- auto 판정이 넓은 범위로 오탐하면 느려질 뿐 안전성은 낮아지지 않는다. 불확실하거나 공통 계약이면 full로
  승격한다.
- scoped cutover 실패 시 해당 직전 image를 다시 production tag로 지정하고 `--force-recreate`한다. full
  cutover는 기존 compose+observability+두 image rollback transaction을 그대로 사용한다.

## Verification and review trigger

- dispatcher plan에서 scripts-only 변경이 server/client/full로 승격되지 않는지 fixture·홈서버 dry-run 확인
- shell syntax, cutover invariant, Python ops tests, Maven architecture/full verify
- scoped 배포 후 restart count, image ID, 43 API smoke, frontend route와 observability 확인
- 변경 누락, 잘못된 축소 판정 또는 scoped rollback 실패가 한 번이라도 발생하면 auto classifier를 fail-full로
  재설계한다.
