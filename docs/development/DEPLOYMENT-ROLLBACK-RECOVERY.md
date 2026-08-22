# 홈서버 배포·롤백·복구 절차

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-16**
- 운영 호스트 경로: `/home/lks/trading-square`
- 공개 UI: `http://192.168.0.200:5847`

## 1. 배포 원칙

- 소스 수정 후 테스트와 문서 검증을 통과한 동일 tree를 배포한다.
- PostgreSQL/MinIO volume을 삭제하거나 재생성하지 않는다.
- 직전 compose, observability config, server/client image를 rollback unit으로 보존한다.
- 새 server를 readiness 확인한 뒤 client를 교체한다.
- 실패하면 자동으로 직전 compose/image를 강제 재생성한다.
- 성공 후 임시 rollback tag만 제거하고 PostgreSQL·MinIO 데이터는 보존한다.

## 2. 표준 명령

```bash
./scripts/deploy-home.sh --plan   # 원격 변경 없이 선택 범위 확인
./scripts/deploy-home.sh          # 기본 --auto
./scripts/deploy-home.sh --server # Spring만 build/cutover
./scripts/deploy-home.sh --client # Next.js만 build/cutover
./scripts/deploy-home.sh --scripts
./scripts/deploy-home.sh --docs
./scripts/deploy-home.sh --full   # 공통 계약/compose/동시 변경
```

`--auto`는 Git 상태가 아니라 실제 로컬 배포 입력과 홈서버 tree의 content/delete 차이를 비교한다. compose 또는
observability 변경, server/client 동시 변경은 full로 승격한다. POM/Flyway/persistence 변경은 server release
gate로 승격해 최근 48시간 검증 backup과 실제 PostgreSQL 통합 테스트를 요구한다.

full 배포는 다음을 순서대로 수행한다.

1. 원격 변경 전 로컬 128MiB·홈서버 4GiB 최소 디스크 여유 확인
2. 문서·아키텍처·cutover invariant 검증
3. server/client/scripts/observability/docs/README/compose 동기화
4. 실제 PostgreSQL 18 multi-instance integration test
5. server/client production build
6. MinIO private/versioned bucket과 app policy 확인
7. preflight server로 production storage profile 확인
8. server rolling replace + readiness
9. client replace + health
10. 1분/일일 monitor cron idempotent 설치
11. `verify-home.sh` 전체 검증

디스크 preflight 실패는 원격 backup/sync/cutover 전에 종료한다. 한도는 긴급 상황에서 환경변수로 변경할
수 있지만, 기준을 낮춰 배포하기보다 생성된 `target`/`.next` 같은 재생성 가능한 로컬 artifact나 홈서버의
검증된 image/object retention을 먼저 정리한다.

full build처럼 홈서버 CPU·I/O 포화가 길어지는 원격 작업은 SSH/rsync 모두 15초 keepalive와 최대 40회
응답 유예를 사용한다. 이는 일시적인 scheduler/build 포화가 `Broken pipe`로 배포 세션을 끊는 것을 막되,
실제 연결 단절은 최대 10분 안에 실패로 확정한다. auto dry-run rsync도 동일한 `RSYNC_RSH` 계약을 사용한다.

## 3. 배포 전 확인

```bash
python3 scripts/verify-documentation.py
./scripts/check-cutover-invariants.sh
cd server-spring && ./mvnw -B -ntp clean verify
cd ../client && npm run test:server-api && npm run lint && npm run build
```

secret은 로컬 출력이나 rsync 대상에 포함하지 않는다. 홈서버 `.env`는 mode 0600이며 없을 때만 생성한다.

## 4. 배포 성공 기준

- server/client/postgres/minio/jaeger healthy, restart 0
- running image ID가 방금 build한 ID와 일치
- Flyway V1~V22
- legacy runtime mount 없음
- 실행 컨테이너의 `DATA_INTEGRITY_EXPECTED_COMPANY_UNIVERSE=277`과 company total/current version 6가 각각 정확히 일치
- 표준 섹터·전략 테마·전체기업 API와 V6 기업 점수/바닥/MACD 알림 근거/정렬 E2E drift 0
- replacement 상세 RBLX/EPD가 HTTP 200뿐 아니라 공식 exchange/SIC, filing 1개 이상, 유효 투자판정을 반환
- recurrence guard constraint 11/11 + collection outcome V16 + dated EPS revision V17 + sector evidence V18
  + immutable sector validation ledger V19/V20(run별 정확히 11 items, 신호일/가격 anchor 분리)
- outbox retry/dead/stuck 0
- object pointer dangling 0
- total-return series 17/17, min points 2,000+, latest aligned
- API smoke 43 통과
- Prometheus target up, RUM Loki visible, test trace Jaeger visible
- 주요 frontend route HTTP 200
- 최근 fatal/error 없음
- 전체 배포·검증 SSH transport에 15초/40회 keepalive와 TCP keepalive 적용

## 5. 자동 롤백

ERR trap은 다음을 복구한다.

- 직전 compose와 observability config
- 직전 server/client image tag
- cutover 시작 후에는 두 container를 `--force-recreate`
- 관측 stack의 이전 활성 상태

volume과 source evidence는 삭제하지 않는다. schema migration이 forward-only여도 이전 image가 새 schema를
읽을 수 있는 additive migration 원칙을 유지한다. destructive migration은 별도 ADR과 2단계 배포가 필요하다.

## 6. 수동 상태 검증

```bash
./scripts/verify-home.sh

ssh lks@192.168.0.200 \
  'docker inspect -f "{{.Name}} {{.State.Status}} {{.RestartCount}}" \
   macrosquare-server macrosquare-client'
```

문서만 변경해도 홈서버 source 문서가 로컬과 같은지 checksum을 확인한다. `deploy-home.sh`는 `docs/`를
`--delete` mirror해 삭제·이름변경도 반영한다.

server/client scoped 배포도 해당 image rollback, production profile preflight, running image ID, 43 API smoke와
`verify-home.sh`를 생략하지 않는다. 차이는 관계없는 image를 build하거나 container를 재시작하지 않는 것이다.
scripts/docs scope는 application restart 없이 동기화와 검증만 수행한다.

`verify-home.sh`의 원격 검증 본문은 SSH 표준입력으로 전달된다. 본문 안에서 별도 Python/CLI를 실행할 때는
반드시 heredoc 또는 `</dev/null`로 자식 프로세스의 표준입력을 격리한다. 그렇지 않으면 자식이 남은 검증
본문을 소비하여 후속 검사가 실행되지 않았는데도 원격 shell이 정상 종료할 수 있다. cutover invariant가
회사 선별 E2E checker의 표준입력 격리를 정적으로 검사한다.

## 7. 백업·복구

- PostgreSQL: custom-format `pg_dump`
- MinIO: active object version과 manifest/checksum mirror
- 두 저장소는 같은 timestamp manifest로 묶음
- backend pause는 관계형 dump/pointer 캡처에만 사용하고 기본 20초를 넘기지 않으며 MinIO mirror 전에 해제
- 별도 Mac host에 자동 보관
- disposable PostgreSQL/MinIO volume에서 restore drill
- restore 후 Flyway, row count, pointer SHA-256, Spring readiness/API 확인

```bash
./scripts/backup-home-storage.sh
./scripts/restore-drill-home-storage.sh
```

상세 절차는 [BACKUP-RESTORE](../../server-spring/docs/BACKUP-RESTORE.md)를 따른다.

## 8. 운영 장애 중 배포 금지 조건

- 원인/오염 범위 미확정
- backup 미확인
- DB constraint 위반을 임시 삭제해야만 기동 가능
- stale/partial 값을 강제로 current로 바꾸는 repair
- provider 장애를 가짜 값으로 채우는 수정
- observability가 죽어 배포 결과를 검증할 수 없음

긴급 수정도 read-only 증거 확보 → 회귀 테스트 → rollback unit 확보 순서를 생략하지 않는다.
