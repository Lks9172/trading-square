# ADR-014: 공급자 집약형 startup 작업을 영속 snapshot 뒤에서 순차 시작한다

- 상태: **Accepted**
- 결정일: **2026-08-17**
- 관련 문서: [스케줄러·동시성·멱등성](../../docs/development/SCHEDULERS-CONCURRENCY-IDEMPOTENCY.md),
  [장애 재발 방지](../../docs/development/INCIDENT-RECURRENCE-PREVENTION.md),
  [PDR-011](../../docs/PDR-011-startup-snapshot-and-delayed-candidate-recalculation.md)

## 맥락

서버 재기동 후 company summary 277개, analyst history 277개, Telegram 후보 전수 재계산이 각각 15초,
30초, 약 3분에 시작했다. 각 작업의 로컬 guard와 PostgreSQL advisory lock은 같은 작업의 중복만 막으므로,
서로 다른 세 작업은 Yahoo throttle·CPU·executor를 동시에 점유했다. 2026-08-17 배포 직후 세 작업이 완료
로그 없이 겹쳤고 server CPU가 약 179%, company cold request가 17.2초까지 상승했다. DB 오류나 5xx는 없었지만
readiness 직후 사용자 요청과 수집 작업이 불필요하게 경쟁했다.

## 결정

1. startup Telegram은 영속 snapshot/outbox만 사용해 기존 5초 지연을 유지한다.
2. market·sector 초기 수집 이후 company summary를 3분에 시작한다.
3. analyst history startup seed는 company summary와 겹치지 않도록 15분에 시작한다.
4. 후보 전수 재계산은 설정 가능한 `post-startup-recalculation-delay`로 분리하고 기본 20분에 시작한다.
5. 세 작업은 서로 다른 scheduler이지만 동일한 cluster-wide `company:provider-heavy` advisory slot을 사용한다.
   delay 예상보다 앞 작업이 길어져도 실제 provider 작업은 겹치지 않는다.
6. startup 후보 재계산이 slot을 얻지 못하면 5분 간격으로 최대 6회 bounded 재시도한다. 정규 cron 작업은
   다음 주기에 다시 실행되므로 lock 대기 thread를 만들지 않는다.
7. 정규 fixed-delay/cron 주기는 변경하지 않는다.
8. 세 provider 집약 작업은 lock 획득 뒤 start/terminal/duration lifecycle 로그를 남기고 일일 감사가 교차 작업 중첩을
   `provider-heavy-scheduler-overlap`으로 탐지한다.

## 경계와 결과

- 투자 산식, 점수, 후보 필터, 수집 주기는 바뀌지 않는다.
- PostgreSQL/MinIO의 영속 최신 snapshot이 startup 조회를 담당하므로 정보량이나 즉시 startup 메시지를 줄이지 않는다.
- 외부 공급자 호출과 thread scheduling은 adapter/bootstrap 책임이며 domain에 시간·Spring 타입을 유입하지 않는다.
- 재배포 직후 완전 재계산은 20분에 첫 시도하고 slot 점유 시 bounded 재시도한다. 그 전에는 마지막 검증
  snapshot을 명시적으로 사용한다.

## 검토한 대안

- 모든 작업에 같은 advisory key만 사용하고 재시도하지 않음: startup 후보 계산이 건너뛸 수 있어 기각.
- executor/concurrency 확대: Yahoo IP rate limit과 홈서버 CPU 경쟁을 악화하므로 기각.
- startup 전수 재계산 제거: 장기 실행 전에 현재 후보를 재확인할 기회를 잃어 기각.

## 검증·관측·롤백

- notification scheduling instant, shared slot, bounded retry와 잘못된 0초 delay 회귀 테스트
- scheduler start/completion/failure duration 로그
- audit overlap parser의 겹침/순차 fixture
- 배포 후 3분 이내 startup Telegram, 3분 summary start, 15분 analyst start, 20분 후보 scan 순서를 확인한다.
- 롤백은 세 delay 값을 환경변수로 이전 값에 가깝게 줄일 수 있으나, 중첩 경고가 재발하면 허용하지 않는다.
