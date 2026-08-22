# ADR-006: 섹터 순환 거시 점수에서 파생 label 재입력과 중복 financial-conditions 축을 제거한다

- 문서 상태: **DECISION**
- 상태: **Accepted / production**
- 결정일: 2026-08-08
- 관련 ADR: [`ADR-002`](ADR-002-sector-rotation-total-return-momentum.md),
  [`ADR-003`](ADR-003-sector-rotation-evidence-integrity.md)
- 관련 PDR: [`../../docs/PDR-005-sector-rotation-macro-score-disclosure.md`](../../docs/PDR-005-sector-rotation-macro-score-disclosure.md)

## 맥락

상위 시장 `macroRegime` label은 유동성·실질금리·금리곡선·유가·달러·신용/스트레스 등 원천을 이미
요약한다. 이를 `riskOff`/`stagflation` 0 또는 100으로 다시 거시 국면 점수에 넣으면 동일 정보가
중복되고 label 경계 한 칸에서 섹터 점수가 불연속적으로 바뀐다. 별도 8% `financialConditionsScore`도
실질금리·유동성·HY OAS를 거시 적합도와 다시 사용했다.

## 결정

1. 거시 국면 점수는 날짜 있는 7개 연속 입력과 2개 3상태 event만 사용한다.
2. `macroRegime`는 화면/호환 입력으로 보존하지만 섹터 순환 점수에는 재입력하지 않는다.
3. 별도 8% financial-conditions 축을 제거한다.
4. 제거한 8%는 독립성이 더 높은 상대 모멘텀 `24→28%`, 기업/섹터 기초체력 `18→22%`로 이동한다.
5. 최종 현재 composite 가중은 macro fit 30%, momentum 28%, fundamental 22%, dated revision 12%,
   crowding relief 4%, dated official ETF flow 4%다. 결측 revision/flow는 현재 50 prior로만 계산하고
   API/UI에서는 `자료 없음`으로 유지한다.

이는 calibrated probability가 아니라 관찰 우선순위용 휴리스틱이다.

## Domain ownership and boundaries

- Research domain의 `SectorRotationRegimePolicy`가 연속 거시 국면과 sector fit을 소유한다.
- `SectorRotationPolicy`가 독립 축 가중과 state/horizon을 소유한다.
- Application은 raw/derived current evidence를 domain 값으로 번역할 뿐 점수를 재계산하지 않는다.
- REST/UI는 methodology와 결측을 표시하며 macro label로 점수를 덮어쓰지 않는다.

Domain은 Spring, JDBC, API DTO 또는 시장 공급자 DTO를 참조하지 않는다.

## Data model and consistency

이번 결정 자체는 additive schema 변경이 없다. 전체 composite 검증용 immutable ledger는 후속
[`ADR-007`](ADR-007-immutable-sector-rotation-validation-ledger.md)/V19+V20에서 구축했으며 현재 데이터를 과거로
역채우지 않는다.

## Concurrency and failure semantics

수집·스케줄러 계약은 바뀌지 않는다. 7개 연속 입력 중 5개 미만이면 application이 평가를 거부하고,
9개 전체 evidence coverage는 regime confidence 상한으로 사용한다. 결측 event는 false가 아니라 50
중립이다.

## Alternatives considered

- 상위 label을 낮은 가중으로 유지: 불연속성과 동일 원천 재사용이 남아 기각.
- financial conditions만 유지: macro fit과의 공선성이 높아 기각.
- 거시 축 전체 제거: 경제적 조건부 설명력을 잃으므로 기각.
- 회귀/ML로 즉시 교체: immutable point-in-time 학습 표본이 없어 보류.

## Consequences and trade-offs

- 장점: 같은 연속 입력에서 upstream label만 바뀌어도 섹터 순위가 점프하지 않는다.
- 장점: 실질금리·유동성·신용의 명시적 이중 가중을 제거한다.
- 단점: 현재 순위와 이전 운영 순위가 달라질 수 있다.
- 단점: 전체 composite OOS 우위를 아직 입증하지 못했다.

## Migration, compatibility, and rollback

API 필드와 DB schema는 호환된다. 롤백은 직전 애플리케이션 이미지로 가능하며 V17/V18 증거 snapshot과
V19/V20 validation ledger는 보존한다. 이전 composite 성과를 새 산식 성과처럼 재표기하지 않는다.

## Security and observability

외부 입력·권한 변화는 없다. 운영 methodology 문자열에 “연속 거시·중복 제거”를 노출하고, 향후
V19/V20 immutable ledger에는 산식 버전·component·source date·coverage와 별도 가격 anchor를 저장한다.

## Verification

- 동일 연속 입력에서 `RISK_ON`과 `PANIC_BUT_OK` label을 바꿔도 regime/sector 결과가 같음
- 중복 alias인 HY OAS basis-point 필드만 바꿔도 sector 결과가 같음
- macro 결측 coverage cap, 3상태 event, 표준/테마 분리 기존 회귀 테스트 유지
- 장기 전체 composite walk-forward는 **미완료**이며 immutable point-in-time ledger 이후 별도 승인
