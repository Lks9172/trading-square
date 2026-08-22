# ADR-004: 날짜가 있는 구성종목 EPS revision breadth를 섹터 증거로 사용한다

- 문서 상태: **DECISION**
- 상태: **Accepted / production**
- 결정일: 2026-08-08
- 관련 ADR: [`ADR-003`](ADR-003-sector-rotation-evidence-integrity.md)
- 관련 PDR: [`../../docs/PDR-003-sector-eps-revision-breadth-disclosure.md`](../../docs/PDR-003-sector-eps-revision-breadth-disclosure.md)
- 금융 감사: [`../../docs/finance/SECTOR-ROTATION-AUDIT-2026-08-08.md`](../../docs/finance/SECTOR-ROTATION-AUDIT-2026-08-08.md)

## 맥락

기존 research catalog의 섹터 `earningsRevisionScore`에는 금융 기준일, 구성종목 수, coverage가 없었다.
따라서 현재 섹터 순환 점수나 확인 상태에 사용하면 미래정보·stale 자료·가짜 정밀도를 구분할 수 없었다.
회사 컨텍스트는 이미 Yahoo `earningsTrend.epsTrend`에서 계산한 forward EPS 7/30/90일 변화율을
매시간 수집하지만, 이전 analyst history projection에는 변화율을 영속하지 않았다.

## 결정

1. `company.analyst_snapshot`에 당시 수집된 `eps_revision_7d_pct`, `eps_revision_30d_pct`,
   `eps_revision_90d_pct`를 관측일과 함께 저장한다.
2. Research 컨텍스트는 회사 테이블을 직접 소유하지 않고 read-only anti-corruption port를 통해 표준
   11개 섹터의 구성종목 30일 EPS revision 방향 breadth를 읽는다.
3. 기준일 `D`에서 `D-3일~D` 안의 ticker별 최신 snapshot만 사용한다. 최신 행의 30일 revision이
   `null`이면 더 오래된 non-null 행으로 되돌아가지 않는다.
4. `revision > +0.10%`는 상향, `< -0.10%`는 하향, 그 사이는 보합으로 분류한다.
5. 구성종목 5개 이상과 coverage 50% 이상을 동시에 만족할 때만 점수를 만든다.
6. 점수는 다음과 같고 0~100으로 제한한다.

   ```text
   net breadth = (상향 종목 수 - 하향 종목 수) / 유효 종목 수
   EPS revision breadth score = round(50 + 50 × net breadth)
   ```

7. 유효한 현재 breadth가 없으면 섹터 점수 계산 내부에서는 의도적인 50 중립 prior를 사용하지만,
   API/UI에는 점수를 `null/자료 없음`으로 유지한다. 기준일 없는 catalog revision은 현재 점수와
   confirmation에 사용하지 않는다.
8. 이 점수는 상승 확률이나 애널리스트 정확도 점수가 아니다. 구성종목 forward EPS 추정 방향의
   단면 확산도다.

## 도메인 소유권과 경계

- Company domain/application: 개별 기업 analyst snapshot과 EPS revision 값의 정합성·영속 이력 소유
- Research domain: `SectorEarningsRevisionBreadth`, 최소 coverage와 breadth 점수 정책 소유
- Research application: 섹터 ticker universe, 기준일, 최대 허용 age를 port에 전달하고 현재 rotation에 결합
- Persistence adapter: Company SQL row를 Research 도메인 증거로 번역하는 read-only ACL
- REST/client: source date·coverage·상향/하향 비율을 표시하며 산식을 재계산하지 않음

Research domain은 JDBC, 테이블, Yahoo DTO, Spring, Controller DTO를 참조하지 않는다. Company domain도
섹터·화면·REST 타입을 참조하지 않는다.

## 데이터 모델과 일관성

- Flyway V17은 세 revision 열, finite check constraint, `(ticker, observed_on desc)` 조회 index를 추가한다.
- 기존 unique identity와 transaction 경계는 ticker+observed date analyst snapshot 계약을 유지한다.
- migration은 additive이며 기존 행은 `null`이다. 과거 값을 현재 공급자 응답으로 역채우지 않는다.
- 새 이력은 scheduler가 수집 시점에 실제로 확인한 revision을 저장한다. point-in-time 이력은 V17 배포
  이후부터 축적된다.
- 비율 단위는 `%`이고 점수 단위는 0~100 index다.

## 동시성과 실패 의미

- 기존 company analyst scheduler의 advisory lock/non-overlap/개별 ticker 실패 격리를 그대로 사용한다.
- 부분 수집은 coverage를 낮추며 50% 미만이면 전체 섹터 breadth가 unavailable이다.
- SQL 실패나 schema 미적용을 정적 catalog 값 또는 0점으로 대체하지 않는다.
- 같은 ticker/date 재실행은 기존 analyst history의 upsert/멱등 계약을 따른다.

## 검토한 대안

### 기준일 없는 catalog revision 사용 — 기각

출처 시점과 coverage를 검증할 수 없어 현재 증거가 아니다.

### 구성종목 revision 평균 — 기각

극단치와 EPS 0 부근 백분율에 민감하다. 우선 방향 breadth로 확산도를 측정하고 magnitude 모델은 별도
검증 후 도입한다.

### coverage 부족을 50점 관측으로 공개 — 기각

실제 중립 breadth와 자료 없음이 구분되지 않는다. 계산 prior와 공개 관측을 분리한다.

### 기존 행을 현재 provider 값으로 backfill — 기각

현재 응답을 과거 관측처럼 저장해 revision/look-ahead bias를 만든다.

## 결과와 트레이드오프

- 장점: 현재 revision 근거에 날짜·coverage가 붙고 구성종목 확산도를 재현할 수 있다.
- 장점: Company와 Research의 저장 소유권을 유지하면서 중복 수집을 피한다.
- 단점: 배포 직후 과거 행은 null이므로 coverage가 채워질 때까지 `자료 없음`이 정상이다.
- 단점: Yahoo forward EPS 추정은 공식 거래소/SEC 데이터가 아니며 provider coverage와 정의 변경에
  영향을 받는다.
- 단점: cap-weight 영향이나 revision magnitude를 반영하지 않는 equal-count breadth다.
- 한계: V17 이전 point-in-time history가 없어 전체 composite 장기 적중률은 아직 검증할 수 없다.

## 마이그레이션·호환성·롤백

1. V17 적용
2. 기존 애플리케이션과 호환되는 nullable 열 유지
3. scheduler의 post-deploy 관측부터 revision history 축적
4. coverage 충족 전에는 UI/API unavailable

애플리케이션은 직전 이미지로 롤백할 수 있다. nullable additive 열과 index는 유지하며 데이터 volume을
삭제하지 않는다. V17 행을 임의 backfill/삭제하지 않는다.

## 보안과 관측

- 쿼리는 bounded ticker list와 typed parameter를 사용한다.
- API에 provider credential이나 원문 응답을 노출하지 않는다.
- 홈 검증은 Flyway V17과 revision 열 존재를 확인한다.
- 운영 점검은 `eps_revision_30d_pct is not null`인 최신 ticker 수, 가장 오래된/최신 observed date,
  섹터 coverage를 구분해서 본다.

## 검증

- Domain: coverage 50%/5종목 경계, 상향·하향·보합, 점수 범위, 결측 fail-closed
- Application: 날짜와 coverage가 있는 breadth만 현재 rotation/confirmation에 사용
- Adapter/DB: V17 적용, finite constraint, ticker별 최신 행 조회
- API/UI: 기준일, coverage, 상향/하향 비율, unavailable 상태 보존
- 문서: ADR/PDR registry와 V17 계약 자동 검증

## 재검토 조건

- 최소 36개월 point-in-time revision breadth 축적
- 공식/유료 consensus point-in-time 데이터 계약 도입
- cap-weighted breadth 또는 revision magnitude 모델의 walk-forward 우위 확인
- 표준 11개 전체의 ETF flow·가격 breadth를 같은 시점 계약으로 확보
