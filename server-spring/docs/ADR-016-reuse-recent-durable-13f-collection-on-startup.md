# ADR-016: 재기동 시 최근 영속 13F 수집을 재사용한다

- 상태: **Accepted**
- 결정일: **2026-08-17**
- 관련: [ADR-014](ADR-014-stagger-provider-heavy-startup-work.md),
  [스케줄러·동시성·멱등성](../../docs/development/SCHEDULERS-CONCURRENCY-IDEMPOTENCY.md)

## 맥락

SEC 13F는 분기 공시이며 운영 수집 주기는 24시간이다. 그러나 서버가 재배포될 때마다 30초 뒤 같은
40개 filing과 약 9만 holding을 다시 파싱·정규화·삭제 후 삽입했다. 최근 성공 수집이 PostgreSQL에
남아 있어도 startup invocation이 이를 확인하지 않아, 배포 직후 약 2분의 단일 코어 CPU 사용과
불필요한 SEC/MinIO/PostgreSQL 부하가 발생했다.

## 결정

1. 설정된 모든 manager별 `institutional.filing.collected_at` 최댓값을 구하고 그중 최솟값을 최근
   공통 수집의 영속 근거로 사용한다. manager 하나라도 행이 없거나 오래됐으면 skip하지 않는다.
2. 프로세스의 **첫 13F invocation에만** 2시간 freshness window를 적용한다.
3. 최근 영속 수집이 cutoff 이상이면 startup provider 호출·holding 재기록·identity 재해석을 모두
   생략한다.
4. 이후 fixed-delay invocation은 freshness shortcut을 사용하지 않고 기존 24시간 전수 수집을
   실행한다. 따라서 잦은 재기동이 정규 수집을 계속 미루지 못한다.
5. freshness 판정 조회가 실패하면 성공으로 가장하지 않고 예외를 전파한다.

## 경계

- application use case가 영속 수집 시각을 기준으로 실행/생략을 결정한다.
- PostgreSQL adapter만 `collected_at` SQL을 소유한다.
- scheduler는 first-invocation과 cutoff 시각만 전달하며 SEC/JDBC 세부 타입을 알지 않는다.
- institutional domain의 flow 계산과 금융적 의미는 변경하지 않는다.

## 검토한 대안

- **startup delay만 늘림**: 부하 시점만 이동하고 중복 계산은 남으므로 기각했다.
- **13F 정규 주기를 늘림**: 데이터 적시성을 낮추므로 기각했다.
- **메모리 last-run 시각**: 재기동에서 사라져 문제를 해결하지 못하므로 기각했다.
- **모든 invocation에 2시간 TTL 적용**: fixed-delay와 결합해 정상 수집이 계속 밀릴 수 있어 기각했다.

## 결과

- 최근 2시간 안에 성공 수집한 뒤 재배포하면 약 9만 holding 재처리를 하지 않는다.
- 24시간 정규 수집 주기와 13F 점수·flow 산식, API 정보량은 변하지 않는다.
- 마지막 수집이 2시간보다 오래됐으면 startup 비용은 기존대로 발생한다.

## 운영·관측·롤백

- skip 로그: `SEC 13F startup collection skipped because durable evidence is current`
- 정규 성공 로그: `SEC 13F collection completed`
- 배포 검증은 `SEC_13F_STARTUP_FRESHNESS=2h`와 container restart count 0을 확인한다.
- 롤백은 env와 scheduler 분기를 제거하면 되며 DB schema 변경은 없다.

## 재검토 조건

- 13F 수집 주기가 24시간보다 짧아짐
- manager별 `collected_at`이 성공 수집이 아닌 부분실패에도 갱신되도록 저장 계약이 바뀜
- 다중 replica에서 first-invocation 의미가 달라짐
- 신규 filing 탐지 지연 SLO가 2시간보다 짧아짐
