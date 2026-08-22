# ADR-003: 섹터 순환의 비교 유니버스와 증거 무결성을 분리한다

- 문서 상태: **DECISION**
- 상태: **Accepted / production**
- 결정일: 2026-08-08
- 관련 ADR: [`ADR-002`](ADR-002-sector-rotation-total-return-momentum.md)
- 관련 PDR: [`../../docs/PDR-002-sector-rotation-evidence-disclosure.md`](../../docs/PDR-002-sector-rotation-evidence-disclosure.md)
- 감사 보고서: [`../../docs/finance/SECTOR-ROTATION-AUDIT-2026-08-08.md`](../../docs/finance/SECTOR-ROTATION-AUDIT-2026-08-08.md)

## 맥락

ADR-002는 표준 11개 섹터의 총수익률 위험조정 모멘텀을 채택했다. 후속 전수검사에서 산식 자체와
별개로 다음 무결성 문제가 확인됐다.

1. 표준 11개 섹터와 SOXX·SMH·ITA·GRID·IGF 전략 테마가 같은 횡단면 percentile에 섞였다.
2. 표준 섹터 화면의 현재 주도 목록에 중복 테마 ETF가 합류했다.
3. 누락 거시값 일부가 중립이 아니라 강한 국면 증거처럼 계산됐다.
4. 독립 수급이 없는 섹터에 유동성·신용·가격을 다시 섞은 값을 `flow`로 표시해 입력을 중복 계산했다.
5. 기준일 없는 catalog earnings revision이 현재 확인 증거와 현재 순환 점수에 영향을 줬다.
6. 3·6개월 월별 forward window가 중첩되는데 독립 Bernoulli 표본용 Wilson 구간만 노출했다.
7. 부분 수집한 표준 ETF를 저장하면 서로 다른 최신일·조정기준의 단면을 만들 수 있었다.

## 결정

### 1. 비교 유니버스 분리

- 표준 섹터: XLK, XLF, XLE, XLV, XLI, XLY, XLC, XLB, XLRE, XLU, XLP
- 전략 테마: SOXX, SMH, ITA, GRID, IGF
- 각 그룹의 위험조정 모멘텀 percentile은 그룹 안에서만 계산한다.
- 표준 섹터 `rotation summary/current/next/fading`은 표준 11개만 소유한다.
- 전략 테마는 테마 상세 profile에는 남지만 표준 섹터 주도 목록을 바꾸지 못한다.

### 2. 결측은 중립 또는 사용 불가로 보존

- 거시값이 없을 때 flat curve, moderate yield, defensive curve 항목은 50 중립으로 둔다.
- 거시 국면 confidence는 7개 연속 입력과 2개 event flag, 총 9개 현재 입력의 coverage를 상한으로 둔다.
- `OVERHEATED`와 copper/gold upturn은 true/false/null 3상태이며 null은 false 관측이 아니라 50 중립이다.
- 절대 추세가 `null`이면 `LEADING` 승격을 허용하지 않는다.
- 단기·중기 상대강도 중 하나라도 `null`이면 `LEADING`/`IMPROVING` 승격을 허용하지 않는다.
- valuation·earnings revision·flow의 부재는 API에서 `null`로 유지한다. UI가 50이라는 실제 관측처럼
  표시하지 않는다.

### 3. 독립 증거만 confirmation에 사용

- 독립 flow는 현재 공급 가능한 기술·금융·에너지 그룹에만 허용한다.
- 유동성·HY credit·단기 가격을 다시 조합한 값은 flow로 부르지 않는다.
- 가격+거시만 존재하면 confirmation은 `WATCH`다.
- `BUILDING`에는 날짜가 있는 revision 또는 독립 flow 중 하나가 필요하고, `CONFIRMED`에는 둘 다와
  단기·중기 상대강도 확인이 필요하다.
- 기준일 없는 catalog revision은 화면의 reference로만 남고 현재 rotation score에는 50 중립 prior를
  사용한다.

### 4. 상태와 시간 버킷 일치

- crowding 고위험 기준은 사용자 계약과 같은 70 이상으로 통일한다.
- `nextCandidates`는 `ONE_TO_THREE_MONTHS`, `secondaryCandidates`는 `THREE_TO_SIX_MONTHS` 전망만 받는다.
- 임의의 68점 절단으로 카드의 기간 문구와 목록 이름이 어긋나는 경로를 제거한다.
- 에너지 내러티브는 실제 catalog 식별자 `energy-supply`를 사용한다.

### 5. 수집과 통계 무결성

- SPY와 표준 11개 ETF 총수익률은 하나의 원자적 횡단면 그룹으로 저장한다.
- full backfill은 시계열당 최소 2,000개, recent refresh는 최소 5개와 동일 최신일을 요구한다.
- 표준 그룹 하나라도 빠지거나 최신일이 다르면 새 그룹을 저장하지 않고 last-valid를 유지한다.
- 중첩 forward window의 Top1 hit-rate에는 horizon-1 lag의 Bartlett/Newey-West 조정 95% 구간을 함께
  제공하며, 기존 Wilson 구간보다 좁아지지 않게 한다.
- 결과가 하나도 없는 월말은 rebalance count·turnover·기간에서 제외한다.

## 경계

- `domain`: 유니버스별 percentile, 결측 처리, 상태/기간, confirmation, robust interval
- `application`: 표준/테마 orchestration, 원자적 수집 그룹 검증, coverage 계약
- `adapters`: Yahoo/JDBC/JSON/HTTP 변환과 nullable 직렬화
- `client`: `null=자료 없음`, 시간 버킷, 중첩 조정 구간 표시

Domain에는 Yahoo, Jackson, JDBC, Spring, Controller DTO가 들어가지 않는다.

## 검토한 대안

### 16개 ETF를 한 순위로 유지 — 기각

SOXX·SMH는 XLK와 구성 노출이 겹치며 표준 GICS 섹터가 아니다. 비교 대상의 상호배타성이 깨지고 테마
추가·삭제만으로 표준 섹터 점수가 변한다.

### 결측을 모두 50으로 직렬화 — 기각

계산 내부의 중립 prior와 외부의 실제 50점 관측을 구분할 수 없다. 사용자와 후속 정책이 가짜 증거를
소비하게 된다.

### 부분 표준 단면 저장 — 기각

가용성은 높지만 횡단면 순위의 비교 기준이 조용히 달라진다. 섹터 선택 신호는 last-valid complete group이
부분 current group보다 안전하다.

## 결과와 한계

표준 섹터 점수는 전략 테마 구성 변경에 불변이고, 결측·stale 증거가 확인 상태를 승격시키지 못한다.
다만 거시·revision·flow를 모두 포함한 전체 rotation score는 아직 point-in-time walk-forward 검증이
완료되지 않았다. 전략 테마 5개는 중복 노출이 있어 서로 독립적인 경제 섹터로 해석하지 않는다.

## 운영 불변식

- 표준 summary의 sector key는 표준 11개 집합의 부분집합
- 표준 percentile은 전략 테마 추가·삭제에 불변
- 전략 테마 projection은 canonical 표준 sector reference를 덮어쓰지 못함
- 표준 total-return refresh는 12/12 series·동일 최신일·full 2,000개 이상일 때만 저장
- 절대 추세 `null`인 sector의 `LEADING` 수는 0
- 독립 revision/flow가 모두 없는 confirmation은 `WATCH`
- API의 관측 부재는 `null`, 내부 중립 prior와 구분
- 3개월 overlap lag=2, 6개월 overlap lag=5

## 롤백

애플리케이션 이미지만 직전 버전으로 롤백할 수 있다. 총수익률 DB 행은 append/upsert이고 스키마 변경이
없으므로 삭제하지 않는다. 부분 배치 차단으로 새 값이 저장되지 않아도 last-valid complete group은 남는다.

## 재검토 조건

- 날짜가 있는 섹터 bottom-up revision breadth를 안정적으로 수집
- ETF flow·구성종목 breadth를 표준 11개 전체에 같은 정의로 확보
- 공식 point-in-time total-return sector index 계약 체결
- 최소 36개월의 전체 composite immutable snapshot 축적
