# ADR-011: Corporate-action 가격 basis cache 격리와 지속 incident reminder 제한

- 상태: **Accepted**
- 결정일: **2026-08-11**, 보강 **2026-08-16**
- 관련 문서: [금융 데이터 원천·신선도](../../docs/finance/DATA-SOURCES-AND-FRESHNESS.md),
  [장애 재발 방지](../../docs/development/INCIDENT-RECURRENCE-PREVENTION.md),
  [관측 Runbook](../../docs/RUNBOOK-daily-observability-audit.md)
- 대체/피대체: 없음

## 맥락

2026-08-10~11 Yahoo MNST 일봉이 약 2배의 인접 불연속을 포함했다. domain 품질 정책은 이를 거짓 급락·바닥
신호로 사용하지 않고 올바르게 거부했지만, Yahoo adapter는 application 검증 전에 응답을 정상 cache로
저장했다. 그 결과 `COMPANY_PRICE_SIGNAL_ROWS=274/275`가 두 차례 발생했고 같은 fingerprint가 매분 WARN으로
기록됐다. 점수 오염·잘못된 BUY·HTTP 5xx·DB 실패는 없었으나 한 기업의 가격 bundle 회복과 로그 판독성이
불필요하게 나빠졌다.

## 결정

1. Yahoo OHLCV adapter는 `CompanyPriceHistoryQualityPolicy`를 cache 저장 전에 적용한다.
2. basis-break payload는 두 provider host 모두에서 거부하고 cache에 넣지 않는다. application 경계도 같은
   domain 정책을 다시 적용해 defense in depth를 유지한다.
3. Yahoo chart 요청에 `events=div,splits`를 포함한다. 명시된 split의 분자/분모·시각이 유효하고, 행사 직전과
   직후의 실제 종가 비율이 공표 비율의 ±15% 안에 있어 **아직 미조정인 basis**로 식별될 때만 행사 전
   OHLC를 비율로 나누고 거래량을 같은 비율로 곱한다. 이미 수정된 이력은 직전/직후 종가 비율이 1배에
   가까우므로 다시 보정하지 않는다.
4. 명시 split event가 없거나, event와 가격 불연속이 일치하지 않거나, JSON이 모호하면 비율만 보고 임의
   보정하지 않고 기존 domain 검증으로 fail-closed한다.
5. 허용된 stale window 안의 마지막 정상 cache는 기존 stale-while-revalidate 계약대로 유지한다. window가
   끝날 때까지 정상 basis가 오지 않으면 신호를 비우고 액션을 HOLD로 제한한다.
6. 영속 summary에서 가격 bundle이 없는 ticker는 다음 전체 refresh의 제출 순서 맨 앞으로 이동한다. 정상
   ticker 사이의 기존 순서는 stable sort로 유지한다.
7. 동일 active integrity fingerprint의 WARN은 첫 지속 확인과 매 30회에만 기록한다. 1분 검사, 신규 ERROR,
   recovery INFO, transactional notification 전이는 그대로 유지한다.
8. 가격 수집이 성공하면 application projection은 가격 metric만 교체하지 않고 8축 바닥 탐색 합성점수,
   현재 구조/확신형 요약, reasons/cautions/failureSignals를 같은 immutable `Research` 재구성에서 갱신한다.
   `계산 대기` 문구와 현재 차트가 공존하는 부분 성공 상태를 허용하지 않는다.

## 도메인 소유권과 경계

- corporate-action형 가격 불연속의 금융 안전성은 domain policy가 소유한다.
- cache·provider fallback·재시도는 Yahoo infrastructure adapter가 소유한다.
- universe 갱신 우선순위는 company application service가 소유한다.
- scheduler log cadence는 inbound scheduling adapter가 소유한다.
- provider JSON, Spring logger, repository/JDBC type은 domain으로 들어가지 않는다.

## 데이터 모델과 일관성

schema 변경과 데이터 repair는 없다. 현재 `company.research_summary`의 가격 bundle 원자성 제약과
calculation version 5를 유지한다. basis 검증 실패 시 다섯 가격 필드는 모두 null이고 액션은 HOLD다.

## 동시성과 실패 의미

275개 작업은 기존 bounded fixed executor를 사용한다. stable priority sort는 결측 작업을 먼저 submit할 뿐
동시성 8, Yahoo fair semaphore, scheduler advisory lock을 바꾸지 않는다. provider basis가 계속 불안전하면
반복 시도하되 잘못된 cache나 합성값을 만들지 않는다.

## 검토한 대안

- event 없이 2배 불연속만 자동 역산: 실제 가격 급변과 분할 방향을 구분할 근거가 없어 기각.
- split event가 있으면 항상 재조정: Yahoo가 이미 과거 이력을 최신 basis로 수정한 일반 경우를 이중 보정하므로 기각.
- issuer/거래소 corporate-action 원천을 모든 ticker에 동기 결합: 가장 강한 근거지만 coverage·가용성·지연이
  현재 계약에 없으므로 후속 보강으로 남기고, 지금은 provider event와 가격비율의 이중 확인을 적용.
- invalid payload도 15분 cache: 회복 후에도 재시도를 지연하므로 기각.
- 모든 active check를 WARN: 감지는 늘지 않고 Loki noise만 증가해 기각.
- 한 ticker 전용 예외: 동일 현상이 모든 기업에 발생할 수 있어 기각.

## 결과와 트레이드오프

거짓 바닥 신호 방지는 유지하면서 신규 split의 provider event와 미조정 가격이 동시에 도착한 과도기에도
현재 basis의 OHLCV를 복원할 수 있다. 가격 bundle 결측은 universe 순서와 무관하게 먼저 처리된다. 반면
event가 누락·오염됐거나 ±15% 일치 조건을 통과하지 못하면 최대 2시간 이전 정상 일봉만 사용하고, 그
이후에는 해당 ticker가 HOLD/자료 없음으로 보인다. 이는 모호한 자동 보정보다 보수적인 선택이다.

## 배포·호환성과 롤백

DB/API schema 변경이 없어 server image만 직전 버전으로 롤백할 수 있다. 롤백 시 기존 domain fail-closed는
남지만 invalid history cache와 매분 WARN 동작이 복원된다. PostgreSQL/MinIO 복원은 필요 없다.

## 보안과 관측

로그에는 ticker, 예외 class, 정규화 fingerprint와 violation count만 남기고 provider body·credential은
남기지 않는다. `COMPANY_PRICE_SIGNAL_ROWS`, operational degraded ticker, summary refresh 결과, outbox 전이를
함께 본다.

## 검증

- corporate-action형 payload가 cache되지 않고 다음 정상 payload를 재요청하는 adapter 테스트
- 신규 2:1 split의 미조정 OHLCV 정규화, 이미 수정된 이력의 이중 보정 방지, event만 일치하고 가격비율이
  일치하지 않는 경우의 미보정 테스트
- 성공한 가격 projection이 pending 요약을 제거하고 현재 가격 축까지 포함한 합성점수를 다시 만드는 테스트
- 결측/미존재 bundle이 정상 bundle보다 먼저 오며 동률 순서가 유지되는 application 테스트
- active WARN이 1·30·60회에만 허용되는 scheduler 테스트
- 전체 Maven test, 문서 검증, cutover invariant, 홈서버 server-only 배포 후 275/275 DB·health·log 검증
