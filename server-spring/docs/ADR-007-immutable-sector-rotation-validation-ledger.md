# ADR-007: 현재 섹터 composite를 완료된 공통 거래일 기준 append-only 원장에 저장한다

- 문서 상태: **DECISION**
- 상태: **Accepted / production**
- 결정일: 2026-08-08
- 관련 ADR: [`ADR-002`](ADR-002-sector-rotation-total-return-momentum.md),
  [`ADR-003`](ADR-003-sector-rotation-evidence-integrity.md),
  [`ADR-006`](ADR-006-sector-rotation-macro-decorrelation.md)
- 관련 PDR: [`../../docs/PDR-005-sector-rotation-macro-score-disclosure.md`](../../docs/PDR-005-sector-rotation-macro-score-disclosure.md)

## Context

총수익률 모멘텀 레이어는 과거 워크포워드 검증이 있지만, 거시·fundamental·dated revision·공식 ETF
flow를 합친 현재 composite는 과거 당시 사용한 값을 보존하지 않았다. 현재 내려받은 수정 가능 이력을
과거 날짜에 재적용하면 look-ahead와 revision bias가 생긴다. 따라서 기존 5년 모멘텀 결과를 전체
composite 적중률로 표시할 수 없다.

## Decision

1. 운영 산식 버전은 `CURRENT_SECTOR_ROTATION_COMPOSITE_V3`로 고정한다.
2. SPY와 표준 11개 섹터 ETF의 총수익률이 모두 존재하는 **완료된 공통 거래일**마다 최초 한 번만 현재
   composite 출력과 구성 점수·상태·근거 날짜를 append-only 저장한다.
3. 동일 산식/공통 거래일 재실행은 결정적 run ID와 unique key로 no-op 처리하며 기존 값을 수정하지 않는다.
4. 과거 backfill은 하지 않는다. V19/V20 이후 실제 운영 시점의 snapshot만 forward validation 표본이다.
5. 21/63/126 공통 거래 세션이 실제로 지난 뒤에만 sector, SPY, 11개 동일가중 수익률을 같은 종료일로
   계산해 outcome을 append한다.
6. 미국 장중 current-day 관측을 확정 종가로 오인하지 않도록 같은 UTC 날짜는 22:00 UTC 이후에만 완료
   후보로 인정한다. 그 전에는 직전 공통 거래일을 사용한다.

## Domain ownership and boundaries

- Research domain의 `SectorRotationCompositeSnapshot`, `SectorRotationOutcome`이 불변식과 11개 유니버스를
  소유한다.
- Application의 `CaptureSectorRotationSnapshotService`와 `EvaluateSectorRotationOutcomesService`가 캡처와
  성숙 outcome을 조정한다.
- `LoadSectorRotationPriceWindowPort`는 Research가 요구하는 공통 거래일/forward-window 계약이다.
- `MarketSectorRotationPriceWindowAdapter`만 Market observation을 읽는 ACL이며 Research domain은 Market,
  JDBC, Spring, Yahoo 타입을 참조하지 않는다.
- `JdbcSectorRotationValidationRepository`만 V19/V20 관계형 원장을 소유한다. Controller/API GET은 원장을
  쓰지 않고, Spring market snapshot 생성 경로만 캡처 use case를 호출한다.

## Data model and consistency

- `research.sector_rotation_run`: 산식 버전+가격 anchor 거래일 unique, UTC 신호일(`as_of_date`), 완료된
  공통 총수익률 거래일(`price_anchor_on`), 계산시각, regime/confidence, coverage, 실제 macro raw input 날짜 범위
- `research.sector_rotation_item_snapshot`: run+sector PK, run+rank unique, composite component, 상태/horizon,
  momentum/revision/flow/breadth 날짜와 coverage
- `research.sector_rotation_outcome`: run+sector+21/63/126 PK, 시작/종료일, 섹터/SPY/동일가중 수익률과 초과수익
- 한 run과 11개 item은 하나의 PostgreSQL transaction으로 저장한다. item 일부만 남는 상태는 허용하지 않는다.
- derived 계산일은 원천 관측일로 기록하지 않는다. momentum은 완료된 공통 총수익률 거래일을, real yield와
  liquidity는 이를 구성한 dated raw series를 기록한다.

Yahoo adjusted close는 분배금·분할에 따라 과거 값이 재조정될 수 있다. outcome은 평가 시점의 동일 vintage로
시작/종료 값을 함께 계산하고 결과를 고정하지만, 공급자 revision 자체를 원본 tick 단위로 복원하지는 않는다.

## Concurrency and failure semantics

- 결정적 `(methodology, price_anchor_on)` run ID와 DB unique key가 다중 instance 중복을 막는다.
- 캡처와 outcome 저장은 transaction + `ON CONFLICT DO NOTHING`으로 멱등이다.
- 공통 12개 총수익률 중 하나라도 없거나 anchor가 7일보다 오래되면 새 snapshot을 쓰지 않는다.
- 미래 21/63/126 세션이 부족하면 outcome은 `pending`으로 남고 합성 중립값을 만들지 않는다.
- validation 저장 실패는 error log로 남기되 현재 사용자 snapshot을 중단하지 않는다. 다음 market refresh가
  같은 공통 거래일을 재시도한다.
- application capture는 부분 coverage assessment를 UI에는 허용하되 원장 저장 전 정확히 11개 unique
  standard sector가 아니면 오류 없이 skip한다. 1분 integrity evidence는 최근 7일 내 V3 11-item run 존재와
  모든 V3 run의 item/sector/rank 원자성을 독립 SQL로 검사한다.

## Alternatives considered

- 현재 데이터로 5년 composite backfill: look-ahead/revision bias 때문에 기각.
- GET 요청마다 snapshot 저장: 사용자 트래픽에 따라 표본이 중복·왜곡되어 기각.
- 달력 1/3/6개월 종료일: 섹터별 휴장/누락 시점이 달라져 21/63/126 공통 거래 세션을 채택.
- 기존 projection JSON만 보존: component/date/constraint 질의와 원자성이 부족해 기각.

## Consequences and trade-offs

- 장점: 이후 전체 composite 성과는 실제 당시 출력으로만 검증할 수 있다.
- 장점: 산식 변경 전후 표본을 methodology version으로 분리할 수 있다.
- 단점: 126-session 결과와 통계적 표본 축적에는 최소 수개월~수년이 필요하다.
- 단점: 최초 운영 snapshot 전 과거 성과는 여전히 모멘텀 레이어 결과만 존재한다.

## Migration, compatibility, and rollback

V19는 additive schema이며 V20은 신호 이용 가능일과 가격 anchor를 분리하는 additive correction이다.
애플리케이션 롤백 시 V19/V20 원장과 이미 쌓인 snapshot/outcome은 삭제하지 않는다. 산식을 바꾸면 기존 행을
update하지 않고 새 methodology version/ADR로 시작한다. V19 적용 전 앱은 새 테이블을 사용하지 않으므로 읽기
호환된다.

### 2026-08-08 production amendment

최초 V19 배포에서는 `as_of_date`를 완료된 가격 거래일로 사용하면서, 주말에 정상 계산된 revision/breadth
근거일도 그 날짜 이하여야 한다고 검사했다. 그 결과 금요일 가격 anchor와 토요일 신호 계산이 결합된 정상
snapshot이 거부되었다. 원장 행은 쓰이지 않았으므로 오염된 표본은 없었다. V20부터 다음 의미를 분리한다.

- `as_of_date`: 모든 입력이 이용 가능했던 UTC 신호 계산일
- `price_anchor_on`: SPY+표준 11개 총수익률이 모두 완료된 forward-return 시작 거래일

component 근거일은 신호일 이하여야 하고, 가격 anchor는 신호일보다 미래일 수 없다. outcome은 반드시
`price_anchor_on`에서 시작한다. V19 checksum은 변경하지 않고 V20으로만 교정한다.

## Security and observability

외부 쓰기 API는 없다. PostgreSQL 제약이 sector key, 11개 rank, score 범위, 날짜 pair, horizon, 유한 수익률을
강제한다. 캡처 성공/실패 로그와 홈 검증의 `runs`, `immutableItems=11×runs`가 원자성·누락을 감시한다.

## Verification

- 완료 거래일 22:00 UTC gate와 정확한 21-session unit test
- 미래 window 부족 시 outcome 0건, 성숙 후 11건 저장 unit test
- 동일 공통 거래일 2회 캡처 시 최초 1회만 저장하는 unit/integration test
- disposable/운영 PostgreSQL V19+V20 migration, 11 item transaction, outcome idempotency, 신호일/가격 anchor
  분리와 constraint 위반 test
- 전체 composite 성능 상태는 snapshot/outcome 표본이 충분해질 때까지 **INSUFFICIENT_SAMPLE**
