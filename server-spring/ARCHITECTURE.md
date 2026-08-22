# MacroSquare Java 설계 원칙

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-08**
- 상세 운영 아키텍처: [`../docs/development/SYSTEM-ARCHITECTURE.md`](../docs/development/SYSTEM-ARCHITECTURE.md)

## 1. 의존성과 바운디드 컨텍스트

```text
bootstrap -> adapters -> application -> domain
```

- `domain`: 불변 value object, entity/aggregate 규칙, 순수 policy만 둔다.
- `application`: use case와 input/output port를 조합하고 트랜잭션의 의미를 정한다.
- `adapters`: HTTP/Jackson/JDBC/MinIO/외부 API/Telegram/Spring Scheduler를 변환한다.
- `bootstrap`: Bean과 런타임 설정만 조립한다.
- Company, Market, Research, Crypto, Execution, Notification, Institutional, Policy, Disclosure는
  서로의 inner-layer 타입을 공유하지 않는다.
  필요한 결합은 application port 또는 outer adapter의 anti-corruption mapping으로만 만든다.
- 예를 들어 analyst consensus→13F divergence는 institutional outbound port와 company outer adapter가
  숫자 스냅샷만 교환하며 company domain 모델을 institutional domain으로 전달하지 않는다.
- 재사용을 이유로 특정 컨텍스트의 domain package를 사실상의 `shared kernel`로 사용하지 않는다.
  이 규칙은 `CleanArchitectureTest`가 빌드에서 강제한다.

## 2. 객체지향

- 상태와 불변식은 record compact constructor 또는 aggregate method 안에서 함께 관리한다.
- 외부에서 setter로 객체를 조립하지 않는다. 변경은 새 불변 값 생성으로 표현한다.
- domain policy는 한 가지 의사결정 축을 소유한다. 숫자 guidance 파싱과 문맥 판정,
  섹터 거시국면 판정과 섹터 랭킹처럼 변경 이유가 다른 책임은 합성 객체로 분리한다.
- 구현체가 하나뿐인 인터페이스를 기계적으로 늘리지 않는다. 교체 가능성이 실제로 존재하는
  시스템 경계에만 port를 둔다.
- 상속보다 합성을 기본으로 하고, enum/string switch는 외부 계약 mapping에 국한한다.

## 3. 함수형 스타일

- 계산 policy는 동일 입력에 동일 출력을 내며 Clock, 네트워크, 파일, 캐시를 직접 읽지 않는다.
- collection은 생성 시 방어 복사하고 외부로 mutable reference를 노출하지 않는다.
- enrichment는 `baseline -> evidence transform -> enriched` 파이프라인으로 구성한다.
  소스별 실패는 `OperationalEventSink`로 관측한 뒤 마지막 정상값을 보존한다.
- stream은 변환/필터/집계가 더 명확할 때만 사용하고, 상태 전이와 오류 처리는 명시적 제어 흐름을 쓴다.
- `parallelStream`과 global common pool은 사용하지 않는다.

## 4. 동시성 및 멀티스레딩

- Java 21 virtual thread는 I/O 대기 작업에 사용하되 Semaphore/고정 worker 수로 외부 공급자별 동시성을 제한한다.
- Executor와 TaskScheduler는 Spring composition root가 생성하고 shutdown lifecycle을 소유한다.
- startup 지연은 worker에서 `Thread.sleep`하지 않고 `TaskScheduler`에 예약한다.
- 동일 ticker/source 갱신은 single-flight를 사용한다. 스케줄 작업은 프로세스 내부 non-overlap guard와
  PostgreSQL session advisory lock을 함께 사용해 rolling deploy나 replica 중첩에서도 한 인스턴스만
  부수효과를 실행한다. 락 획득용 connection은 작업 종료까지 유지하고 DB 조정 실패 시 fail-closed 한다.
- PostgreSQL transaction, aggregate lock, row lock과 unique key가 멀티스레드 write의
  원자성·직렬화·멱등성을 담당한다. 투자계획의 partial PATCH는 repository 원자 변경 계약을 거쳐
  transaction advisory lock과 `SELECT ... FOR UPDATE` 안에서 현재값을 읽고 갱신하므로 서로 다른
  필드의 동시 요청도 변경을 유실하지 않는다.
- legacy file adapter를 선택한 개발 모드에서는 동일 원자 변경 계약을 JVM lock으로 구현하고,
  `SingleWriterFileLease`가 두 번째 프로세스 writer를 거부한다.
- 알림 상태 전이와 outbound message enqueue는 PostgreSQL transactional outbox에 함께 commit한다.
  dispatcher는 `FOR UPDATE SKIP LOCKED` lease로 여러 인스턴스에서 한 worker만 메시지를 가져가며,
  실패는 지수 backoff 후 재시도하고 한도 초과는 dead 상태로 격리한다. Telegram은 provider idempotency-key를
  지원하지 않으므로 provider 수락 직후 ack commit 전 crash의 극소 중복 창은 남는 at-least-once 의미다.
- Java 21의 `StructuredTaskScope`는 preview API이므로 운영 빌드에 사용하지 않는다.

## 5. 실패 의미

- 명령의 핵심 결과가 무효이면 예외를 전파한다.
- last-valid fallback이 제품 계약인 경우에만 계속 진행하며 로그와
  `macrosquare.degraded.operations` metric을 반드시 남긴다.
- 스케줄러 오류를 catch하고 성공처럼 반환하지 않는다. 로그 후 재전파해 Micrometer의 scheduled-task
  관측 결과가 실제 실패를 나타내게 한다.
- 손상된 mutable state는 빈 상태로 초기화하지 않고 fail-closed 한다.

## 6. 영속성

- 운영 정형 상태와 시계열은 PostgreSQL 18이 소유하고 Flyway가 schema version을 관리한다.
- SEC/IR 문서와 JSON projection 본문은 private/versioned MinIO bucket에 둔다. PostgreSQL에는
  object version, ETag, SHA-256, size와 active pointer만 저장한다.
- MinIO body를 먼저 쓴 뒤 PostgreSQL pointer를 transaction으로 활성화한다. pointer commit 전 object는
  reader에게 보이지 않으므로 DB 실패가 last-valid projection을 오염시키지 않는다.
- JDBC와 MinIO SDK는 adapter 내부에 머문다. domain/application port에는 bucket, SQL, DTO, ORM type이 없다.
- JPA lazy-loading 대신 명시적 SQL과 bounded query를 사용해 aggregate 경계와 성능 비용을 드러낸다.
- scheduler의 PostgreSQL advisory lock은 adapter 구현 세부사항이다. application은
  `ExclusiveTaskExecution`의 실행권 계약만 알며 DataSource, SQL, lock key를 알지 못한다.
- session advisory lock connection은 transactional Hikari pool과 분리된 unpooled coordination source에서
  열고 fair semaphore로 동시 수를 제한한다. 외부 수집 지연이 API/transaction connection을 고갈시키지 않는다.
- legacy file adapter는 로컬 호환/일회성 import 용도이며 production source of truth가 아니다.
- 상세 schema, migration, rollback, backup 계약은
  [`docs/ADR-001-storage-and-database-boundaries.md`](docs/ADR-001-storage-and-database-boundaries.md)에 고정한다.
