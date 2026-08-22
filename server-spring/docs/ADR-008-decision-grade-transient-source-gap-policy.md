# ADR-008: 의사결정 가능한 값이 남은 일시적 수집 공백의 경보 정책

- 상태: **Accepted**
- 결정일: **2026-08-09**
- 관련 문서: [PDR-006](../../docs/PDR-006-transient-source-gap-alert-interpretation.md),
  [데이터 원천·신선도](../../docs/finance/DATA-SOURCES-AND-FRESHNESS.md),
  [장애 재발 방지](../../docs/development/INCIDENT-RECURRENCE-PREVENTION.md)
- 대체/피대체: 없음

## 맥락

2026-08-08 Loki와 notification outbox에서 Yahoo의 `USDKRW` 또는 `USDJPY` 한 건이 5분 수집 중
간헐적으로 malformed metadata를 반환한 뒤 다음 수집에서 회복하는 패턴이 반복됐다. 원 응답 교차검증에서
Yahoo가 같은 USD 기준 환율을 요청 심볼 `JPY=X`/`KRW=X`와 명시 심볼
`USDJPY=X`/`USDKRW=X` 사이에서 번갈아 반환한다는 원인을 확인했다. 수집 상태를
`DEGRADED`로 보존한 것은 맞지만, 직전 수집에서 검증·저장된 값이 남아 있는데도 1분 integrity
monitor가 즉시 hard failure와 회복을 번갈아 발송했다. 가격·점수 오염이나 HTTP 5xx는 없었고,
경보 피로만 증가했다.

## 결정

1. Yahoo market adapter는 전체 provider host 집합이 실패한 key만 같은 bounded host 집합으로 한 번 더
   시도한다. 공유 Yahoo throttle은 모든 재시도에도 적용한다.
2. 재시도 후 공백은 계속 `DEGRADED`와 실패 key로 영속한다. `SUCCESS`로 숨기지 않는다.
3. integrity evidence에서 다음 조건을 **모두** 만족하는 경우에만 hard failure 경보를 최대 30분
   유예한다.
   - source가 `YAHOO`
   - 실패 key가 `USDKRW`, `USDJPY` 또는 둘의 조합뿐
   - batch의 collected/persisted count가 모두 양수이고 서로 일치
   - 각 실패 key에 30분 이내 실제 `market.observation.collected_at`이 존재
4. 다른 key, 값 부재, 30분 초과, 전체 실패, 저장 건수 불일치, 거짓 SUCCESS는 즉시 hard failure다.
5. 유예 중에도 새 값을 합성하거나 timestamp를 갱신하지 않는다. 소비자는 실제 직전 관측만 사용한다.
6. FX 응답 심볼은 요청한 `XXX=X`와 동일하거나 정확한 `USDXXX=X`일 때만 같은 quote로 인정한다.
   다른 FX 방향, ETF, 지수, 주식의 심볼 불일치는 계속 거부한다.
7. 2026-08-01부터 NAAIM 공식 public table은 3개월 지연이다. 최신 행을 파싱하더라도 14일 freshness를
   넘으면 현재 `NAAIM_EXPOSURE`로 저장하지 않고 `Provider data is delayed beyond decision freshness`로
   제외한다. DB 원장은 `DEGRADED`와 `PROVIDER_POLICY_UNAVAILABLE`을 보존하고 사용자 collection health는
   일반 장애와 구분한 `LIMITED`로 표시한다. licensed current table은 `NAAIM_EXPOSURE_URL`로 주입할 수 있다.

## 도메인 소유권과 경계

- 결측을 중립값으로 바꾸지 않는 금융 불변식은 domain `DataIntegrityPolicy`가 유지한다.
- 수집 재시도와 Yahoo payload 판별은 infrastructure adapter의 책임이다.
- PostgreSQL에서 현재 사용 가능한 근거의 신선도를 읽는 일은 integrity persistence adapter의
  read-only projection 책임이다.
- Controller, DTO, JDBC type 또는 provider payload는 domain으로 들어가지 않는다.

## 데이터 모델과 일관성

schema 변경과 data repair는 없다. 권위 데이터는 기존 `market.observation`과
`market.collection_status`다. `observed_on`은 금융 기준일, `collected_at`은 실제 마지막 성공 수집
시각이며 둘을 대체하지 않는다.

## 동시성과 실패 의미

재시도는 key별 최대 두 pass로 bounded되고 process-wide Yahoo throttle 및 기존 scheduler
advisory lock 안에서 실행된다. 한 pass 공백은 `DEGRADED`, 전체 usable observation 부재는 기존처럼
scheduler 실패다. 30분 안에 정상 수집되지 않으면 다음 1분 integrity check가 hard failure를 만든다.

## 검토한 대안

- 모든 `DEGRADED` 즉시 Telegram: 실제 오염보다 provider 순간 공백에 경보가 반복되어 기각.
- FX 공백을 항상 optional 처리: 장기 공백을 숨기므로 기각.
- 직전 값을 새 값처럼 재저장: 금융 기준일과 수집 증거를 왜곡하므로 기각.
- 즉시 타 provider 값으로 혼합: source/단위/시장시간 계약과 교차검증 없이 도입할 수 없어 기각.
- 3개월 지연 NAAIM을 최신값으로 사용: 심리 점수를 stale positioning으로 오염하므로 기각.

## 결과와 트레이드오프

단일 FX provider blip은 최대 30분 hard alert가 늦어진다. 대신 `DEGRADED` 증거와 일일 audit WARN은
남고, 의사결정 가능한 직전 값이 사라지거나 오래되면 자동으로 hard alert로 승격된다. 이 1시간은
5분 수집 최대 여섯 번이자 기존 Yahoo stale 계약과 같은 운영 상한이지 금융 신호나 확률이 아니다.

## 배포·호환성과 롤백

additive schema 변경이 없어 직전 server image로 즉시 롤백할 수 있다. 롤백하면 모든 Yahoo
`DEGRADED`가 다시 즉시 hard failure가 된다. PostgreSQL/MinIO 데이터는 변경하거나 복원하지 않는다.

## 보안과 관측

integrity ERROR 로그에 실패 source 목록을 함께 남기되 URL, credential, response body는 남기지 않는다.
Loki에서 key별 공백, outbox에서 alert/recovery 전이, DB에서 현재 status와 마지막 collected time을
교차한다.

## 검증

- Yahoo `JPY=X` 요청에 `USDJPY=X`가 반환되는 실응답 alias 회귀 테스트
- 비-FX 심볼 불일치 거부 테스트
- NAAIM 지연 table을 malformed가 아닌 stale source gap으로 분류하는 테스트
- 정렬되지 않은 NAAIM table에서 최신 날짜를 선택하는 테스트
- Yahoo 첫 pass malformed → 두 번째 pass 성공 회귀 테스트
- 두 pass 모두 invalid → `DEGRADED` 유지 테스트
- 실제 PostgreSQL에서 fresh FX fallback은 hard 0, 30분 초과 fallback은 hard 1 테스트
- 전체 Maven, 43 API smoke, 배포 후 Loki/DB/outbox 검증
