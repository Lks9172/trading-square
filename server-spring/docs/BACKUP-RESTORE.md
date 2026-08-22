# PostgreSQL·MinIO 백업·복구·GC

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-08**

## 저장 경계

- 정형 상태/시계열/notification outbox/object pointer: PostgreSQL 18
- JSON projection과 SEC/IR 본문: private·versioned MinIO
- legacy 원본: 홈서버에 보존하지만 운영 컨테이너에는 mount하지 않음

애플리케이션 MinIO service account에는 delete 권한이 없다. 삭제는 운영자 maintenance script만 수행한다.

## 일관 백업과 오프호스트 복제

```bash
./scripts/backup-home-storage.sh
```

백업은 `macrosquare-server`를 기본 최대 20초만 Docker `pause`하고 PostgreSQL custom dump와 active object
pointer/version을 캡처한다. 즉시 `unpause`하고 readiness를 확인한 뒤 MinIO current object를 복사하고 DB
pointer가 선택한 exact version을 다시 덮어쓴다. 장시간 object mirror 동안 backend를 멈추지 않으며 pause
deadline 초과 backup은 실패한다. 프로세스를 재시작하지 않으므로 startup Telegram도 재발송되지 않는다.
off-host 여유 공간은 홈서버 pause 전에 최근 backup 크기 기준으로 검사한다. 실패한 `.partial` 디렉터리는
복구 지점이 아니므로 다음 실행 전에 제거하고, 공간 부족 시 홈서버 runtime을 건드리지 않고 fail-fast한다.
홈서버 staging도 checksum 완료 전에는 `.partial` 이름을 사용하며 실패 시 EXIT trap으로 제거한다.

산출물:

- `postgres.dump`
- `objects/`
- `active-objects.tsv`
- `row-counts.tsv`
- `MANIFEST`, `SHA256SUMS`

1차 staging은 홈서버 `/home/lks/macrosquare-backups/<UTC timestamp>`에 3개 보관한다. 완료된 backup은
별도 물리 호스트인 Mac의 `~/MacroSquareBackups/trading-square/<UTC timestamp>`로 rsync한 후 모든 파일을
다시 SHA-256 검증하고 기본 2개 보관한다. `LATEST` symlink는 검증 완료본만 가리킨다. 새 copy 공간이
부족하면 가장 최신의 검증 완료본 하나는 반드시 남기고 그보다 오래된 off-host 완료본부터 정리한다.
홈서버에는 별도로 최근 3개를 유지하므로 단일 세대만 남은 상태에서 추가 삭제하지 않는다.

설정 변수:

```text
BACKUP_ROOT
OFFHOST_BACKUP_ROOT
REMOTE_BACKUP_RETENTION
OFFHOST_BACKUP_RETENTION
BACKUP_MAX_SERVER_PAUSE_SECONDS
```

## 격리 복구 리허설

```bash
./scripts/restore-drill-home-storage.sh
# 또는 BACKUP_PATH=/absolute/backup ./scripts/restore-drill-home-storage.sh
```

리허설은 off-host copy를 홈서버 임시 경로로 다시 전송한 뒤 다음 disposable resource를 만든다.

1. 전용 Docker network
2. 빈 PostgreSQL 18 volume/container
3. 빈 MinIO volume/container와 versioned private bucket
4. 복구 Spring container(운영 포트 미노출, 알림/수집 scheduler 비활성)

검증 항목:

- local/remote backup SHA-256
- PostgreSQL table별 row count
- dangling object pointer 0
- 모든 active object의 복구 후 SHA-256
- Flyway validation/migration
- Spring readiness와 `/api/snapshot`

성공/실패 후 임시 container/network/volume/복구 파일은 삭제하며, 로컬
`~/MacroSquareBackups/trading-square/restore-drills/*.log`만 감사 증빙으로 남긴다. 운영 volume과 운영 포트는
읽거나 수정하지 않는다.

## MinIO orphan GC

```bash
# 기본: 조회만
./scripts/gc-home-object-storage.sh --dry-run --retention-days 30

# 검토/백업 후 실제 삭제
./scripts/gc-home-object-storage.sh --apply --retention-days 30
```

삭제 가능 조건은 모두 충족해야 한다.

1. Spring managed prefix(`projections/`, `sec-filings/`)일 것
2. `storage.object_artifact`에 `(bucket,key,version)`이 없을 것
3. `storage.object_pointer`가 선택하지 않을 것
4. 최소 30일 이상 지났을 것

apply 직전에 DB를 다시 조회하고, exact version ID만 삭제한 뒤 MinIO inventory에서 사라졌는지 재검증한다.
seed/source/history prefix와 DB에 기록된 과거 artifact version은 삭제하지 않는다.

## 자동화

```bash
./scripts/install-storage-maintenance-launchagent.sh
```

macOS user LaunchAgent가 매일 로컬 시각 03:20에 다음 순서로 실행한다.

1. 일관 backup + off-host checksum 검증
2. 30일 초과 orphan GC
3. 7일마다 격리 restore drill

설치기는 macOS TCC가 Desktop의 background 실행을 차단해도 동작하도록 필요한 secret-free 스크립트만
`~/Library/Application Support/MacroSquare/maintenance-runtime`에 복사한다. 유지보수 스크립트를 변경한
뒤에는 설치기를 다시 실행해 이 runtime copy를 갱신한다.

로그:

```text
~/Library/Logs/MacroSquare/storage-maintenance.log
~/Library/Logs/MacroSquare/storage-maintenance-error.log
```

제거:

```bash
./scripts/install-storage-maintenance-launchagent.sh --uninstall
```

## 실제 재해 복구

리허설 PASS backup만 사용한다. 운영 복구는 파괴적이므로 자동 수행하지 않는다.

1. `SHA256SUMS` 검증
2. 현재 PostgreSQL/MinIO volume 이름 변경·보존
3. 빈 volume에 MinIO object와 PostgreSQL dump 복구
4. pointer integrity/active object SHA-256 확인
5. Spring 1 replica readiness/API/UI smoke
6. 운영 포트 전환

MinIO version ID는 store-local이다. 복구 store의 version ID가 달라도 reader는 current body의 SHA-256을
검증한 후 새 artifact/pointer로 remap한다. checksum이 다르면 fail-closed 한다.

## 한계

단일 홈서버는 HA cluster가 아니다. 현재 off-host Mac copy는 홈서버 디스크 장애를 보호하지만 두 장소 동시
장애까지 보호하지 않는다. 중요도가 높아지면 외장 디스크 또는 원격 S3 Object Lock/WORM copy를 추가한다.

## 최신 검증 기록

2026-07-21 운영 V2 상태로 다음을 실제 수행했다.

- off-host backup: `20260721T044611Z`, 약 1.0GiB, checksum 파일 2,212개
- restore drill: `20260721T044836Z-31674`, relational row count·active object 12개 SHA-256·Spring readiness PASS
- drill 종료 후 임시 container/network/volume 잔존 0
- MinIO GC dry-run/apply: managed version 61개, 30일 초과 orphan 0개
- LaunchAgent `io.macrosquare.storage-maintenance`: TCC-safe runtime에서 수동 실기동 `exit 0`
- LaunchAgent 실기동 backup `20260721T045551Z`: checksum 2,212개, GC 완료, restore drill not-due 판단
