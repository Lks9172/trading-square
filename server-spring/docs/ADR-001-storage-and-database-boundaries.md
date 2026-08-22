# ADR-001: PostgreSQL·MinIO 저장 경계

- 문서 상태: **DECISION**
- 상태: **Accepted / production target**
- 기준일: 2026-07-21
- 대체 결정: Spring-owned mutable file store

## 결정

MacroSquare의 운영 저장소는 다음 두 기술로 분리한다.

1. **PostgreSQL 18**: 정형 상태, 시계열, 트랜잭션, 중복 방지 상태, 객체 메타데이터
2. **MinIO(S3 호환)**: SEC/IR PDF·HTML·XML·TXT, JSON projection, immutable cutover seed

파일 본문이나 BLOB을 PostgreSQL에 넣지 않으며, 정형 command/state를 MinIO JSON 한 덩어리로
저장하지 않는다. Java domain/application 계층은 JDBC, SQL, MinIO, bucket, ETag를 모른다.

| 데이터 | 기준 저장소 | 일관성/쓰기 방식 |
|---|---|---|
| 시장 관측·가격 이력 | PostgreSQL `market` | `(source, key, date)` 멱등 upsert |
| 애널리스트 revision history | PostgreSQL `company` | ticker aggregate 단위 transaction |
| 투자 계획·분할매수·거래 로그 | PostgreSQL `execution` | aggregate 직렬화, command 단위 ACID, audit append |
| Telegram dedupe/current candidates/outbox | PostgreSQL `notification` | 상태 전이+enqueue 단일 transaction, leased dispatch |
| 현재 시장/기업/research projection | MinIO + PostgreSQL pointer | versioned object, pointer commit 후 공개 |
| SEC/IR 원문·슬라이드 | MinIO + PostgreSQL metadata | content/logical key, bounded body |
| cutover seed/source cache | MinIO | read-only immutable prefix |

## 경계와 스키마

- Flyway만 DDL의 소유자다. 실제 migration은
  [`bootstrap/src/main/resources/db/migration`](../bootstrap/src/main/resources/db/migration)의 V1~V7이다.
- bounded context별 schema를 분리한다: `market`, `company`, `execution`, `notification`, `storage`.
- JDBC 구현은 adapter 계층에만 존재한다. JPA entity나 ORM proxy를 domain에 전달하지 않는다.
- SQL은 필요한 column만 명시하고, domain 생성자가 동일한 불변식을 다시 검증한다.
- 주요 조회 형태에 맞춘 B-tree/partial index를 migration에 함께 둔다.

### 객체 저장 protocol

MinIO와 PostgreSQL은 하나의 분산 transaction을 지원하지 않는다. 따라서 mutable object는
다음 순서로 공개한다.

1. MinIO private/versioned bucket에 object version을 기록한다.
2. SHA-256, ETag, size, content type, version ID를 `storage.object_artifact`에 기록한다.
3. 같은 PostgreSQL transaction에서 `storage.object_pointer`를 새 artifact로 교체한다.
4. reader는 pointer가 가리키는 **정확한 version ID**만 읽고 size/ETag/SHA-256을 검증한다.

2~3단계가 실패하면 새 body는 참조되지 않은 orphan version일 뿐이며 기존 pointer는 유지된다.
따라서 실패한 write가 부분적으로 공개되거나 last-valid projection을 지우지 않는다. immutable seed
prefix는 외부 initializer가 만든 읽기 전용 자료이므로 pointer 없이 직접 읽을 수 있다.

Prefix 계약은 다음과 같다.

```text
projections/       Java runtime mutable projection
seed-projections/  immutable cutover projection
source-cache/      immutable last-valid source evidence
legacy-history/    PostgreSQL 최초 seed
sec-filings/       SEC/IR 원문 object
```

Bucket은 public access를 금지하고 versioning을 켠다. 애플리케이션은 MinIO root credential이 아니라
해당 bucket의 list/get/put만 가능한 별도 service account를 사용하며 delete 권한은 갖지 않는다.

## 트랜잭션·동시성

- 여러 row를 교체하는 analyst/notification state는 `TransactionOperations`로 원자화한다.
- 투자계획 partial PATCH는 application의 원자 변경 port를 통해서만 수행한다. PostgreSQL adapter는
  transaction advisory lock으로 빈 aggregate의 첫 write까지 직렬화하고, 기존 singleton row에는
  `SELECT ... FOR UPDATE`를 적용한 뒤 같은 transaction에서 version을 증가시킨다.
- market collector는 batch upsert하며 재수집해도 row가 중복되지 않는다.
- 모든 부수효과 scheduler는 JVM non-overlap guard와 PostgreSQL session advisory lock을 조합한다.
  rolling deploy 중 구·신 인스턴스가 겹쳐도 동일 task key를 획득한 한 인스턴스만 실행하며,
  DB 조정 실패를 로컬 실행으로 우회하지 않는다.
- 트랜잭션/API DB 작업은 bounded Hikari pool을 사용한다. 외부 I/O 동안 유지되어야 하는 session advisory
  lock은 별도의 unpooled connection source와 공정한 semaphore를 사용하며 기본 동시 물리 connection을
  4개로 제한한다. 따라서 장시간 collector가 Hikari slot을 점유하거나 leak detection 오탐을 만들지 않는다.
  scheduler 수를 늘릴 때는 coordination 상한과 PostgreSQL `max_connections`를 함께 검토한다.
- 각 PostgreSQL connection은 30초 statement timeout, 5초 lock timeout, 60초 idle transaction timeout을
  시작 시 설정한다. 60초 leak detection은 transaction 경계 누락을 운영 로그에서 탐지한다.
- 현재 홈서버는 backend 1 replica를 유지하되 rolling overlap은 위 advisory lock으로 보호한다.
- Telegram dedupe state와 message enqueue는 한 PostgreSQL transaction이다. dispatcher는 bounded lease,
  `SKIP LOCKED`, retry/dead-letter를 사용한다. Telegram API가 idempotency key를 받지 않으므로 provider가
  수락한 직후 delivered ack 전에 process가 죽는 구간까지 exactly-once로 만들 수는 없다.
- 전달 완료/영구 실패 outbox row는 감사 목적으로 30일 보존한 뒤, advisory lock을 획득한 한 인스턴스가
  매일 삭제한다. PENDING/RETRY/IN_FLIGHT row는 생성 시각과 무관하게 retention 대상이 아니다.
- object body와 DB pointer 사이에 XA/2PC를 도입하지 않는다. 위의 publish-after-pointer protocol로
  더 단순하고 복구 가능한 일관성을 얻는다.

## 마이그레이션

1. PostgreSQL/MinIO를 기존 서버와 독립적으로 시작하고 Flyway를 적용한다.
2. 초기 컷오버 때만 explicit `legacy-seed` profile이 seed/source/history를 private versioned bucket으로 mirror했다.
3. startup importer가 기존 read-only volume의 execution/notification/snapshot을 **빈 target에만**
   멱등 import했다. 운영 전환 후 importer와 legacy runtime mount는 비활성화했다.
4. 기존 파일은 checksum inventory로, named volume은 checksum이 검증된 압축 archive로 보존하되 running
   service에는 mount하지 않는다. 컷오버 검증과 복구 리허설이 끝난 legacy named volume 자체는 중복
   디스크 점유를 피하려고 제거한다.
5. readiness는 DB, MinIO bucket, snapshot 가용성을 모두 확인한다.
6. API 41-route smoke, row/object integrity, frontend health/route smoke가 끝난 뒤 배포를 확정한다.

배포 실패 시 이전 compose와 Java/client image로 되돌린다. PostgreSQL/MinIO volume은 삭제하지 않아
재시도와 forensic evidence를 보존한다. legacy 파일 원본과 named-volume archive도 삭제하지 않지만 운영
graph와는 분리한다. 검증된 archive와 동일한 legacy named volume 자체는 운영 전환 승인 후 제거할 수 있다.

## 백업·복구

- PostgreSQL: `pg_dump --format=custom` 일관 스냅샷, restore 후 Flyway validation
- MinIO: 전체 current object를 mirror한 뒤 DB pointer가 선택한 exact version을 다시 복사
- 홈서버 staging과 별도 Mac 호스트 backup은 같은 timestamp manifest와 SHA-256을 갖는다.
- 복구 리허설은 disposable PostgreSQL/MinIO volume과 격리 network에서 row count, active object SHA-256,
  Spring readiness/API를 확인한 뒤 모든 drill resource를 삭제한다.
- backup script는 secret을 출력하지 않고 실패 시 불완전 manifest를 성공으로 승격하지 않는다.
- MinIO orphan GC는 app 계정이 아니라 admin maintenance 경계에서만 실행한다. DB artifact/pointer에 없는
  managed version 중 30일 이상 된 버전만 재확인 후 version ID 단위로 삭제하며 dry-run이 기본이다.

현재 단일 홈서버 구성은 고가용성 cluster가 아니다. 디스크 장애 대비 copy는 별도 Mac 호스트에 자동
복제된다. 두 장소 동시 장애까지 대비하려면 추후 외장 디스크/원격 object-lock copy를 하나 더 둔다.

## 의도적으로 하지 않는 것

- PostgreSQL BYTEA/large object에 PDF·HTML 저장
- domain/application에서 Spring Data/JDBC/MinIO 타입 사용
- JPA lazy loading과 aggregate 외부 cascade
- public bucket 또는 MinIO root credential의 애플리케이션 사용
- DB와 MinIO 사이 XA transaction
- migration 성공·archive checksum·복구 리허설 확인 전 legacy volume 삭제
