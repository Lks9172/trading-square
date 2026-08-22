# 섹터 순환 전수검사 — 2026-08-08

- 문서 상태: **SNAPSHOT**
- 검사 범위: source → 조정주가 이력 → 파생지표 → domain policy → application overlay → API/UI → backtest
- 관련 결정: [ADR-002](../../server-spring/docs/ADR-002-sector-rotation-total-return-momentum.md),
  [ADR-003](../../server-spring/docs/ADR-003-sector-rotation-evidence-integrity.md),
  [ADR-004](../../server-spring/docs/ADR-004-dated-sector-eps-revision-breadth.md),
  [ADR-005](../../server-spring/docs/ADR-005-official-sector-etf-flow-and-price-breadth.md),
  [PDR-002](../PDR-002-sector-rotation-evidence-disclosure.md),
  [PDR-003](../PDR-003-sector-eps-revision-breadth-disclosure.md),
  [PDR-004](../PDR-004-sector-flow-and-price-breadth-disclosure.md)

## 결론

위험조정 상대 모멘텀의 6·12개월/최근 1개월 제외/252일 변동성 조정 자체는 운영과 walk-forward가
일치했다. 그러나 **비교 유니버스 혼입, 결측의 가짜 중립 관측, 비독립 flow, 기간 버킷 불일치,
중첩 표본 신뢰구간, 부분 단면 저장** 문제가 있어 그대로는 표준 섹터 주도 화면을 신뢰하기 어려웠다.
확정 가능한 무결성 결함은 코드와 회귀 테스트로 수정했다.

## 수정한 결함

| 심각도 | 결함 | 영향 | 조치 |
|---|---|---|---|
| Critical | 표준 11개+전략 테마 5개를 한 percentile로 계산 | 테마 추가만으로 XLK 등 표준 점수 변경 | 두 유니버스 별도 percentile |
| Critical | 표준 summary에 SOXX/SMH 합류 | “표준 섹터”와 “전략 테마” 혼동 | 표준 summary/candidate는 11개만 |
| Critical | 표준 total-return 부분 배치 저장 | 날짜·조정기준이 다른 횡단면 가능 | 12/12·동일 최신일 원자적 저장 |
| High | 결측 curve/real-yield가 강한 국면 증거 | 데이터 부족 때 confidence 과대 | 중립 50 + coverage confidence cap |
| High | 절대 추세 null이 leader gate 통과 가능 | 미확인 추세의 주도 승격 | `TRUE`만 통과 |
| High | 유동성·credit·가격을 flow로 재사용 | 독립 수급처럼 보이고 입력 중복 | sector-specific flow 없으면 null |
| High | undated revision 중복 가중 | 현재 후보 점수의 false precision | reference만 보존, current score 중립 prior |
| High | 가격+거시만으로 BUILDING | 확인 배지 과장 | 독립 revision/flow 없으면 WATCH |
| High | score 68 기준으로 next/secondary 분리 | 카드 기간과 목록 이름 모순 | 1~3m/3~6m horizon으로 직접 분리 |
| Medium | crowding 68과 UI 70 기준 불일치 | 같은 값의 상태·색상 모순 | 70으로 통일 |
| Medium | `oil-supply` 오타 호환 유지 | 에너지 내러티브 보정 미적용 | `energy-supply` enum 사용 |
| Medium | 3·6개월 중첩 표본을 독립으로 간주 | 신뢰구간 과도하게 좁을 수 있음 | Newey-West/Bartlett 조정 구간 추가 |
| Medium | 결과 없는 월말도 rebalance에 포함 | 기간·회전율·표본 설명 왜곡 | forward 결과 없는 event 제외 |

## 2차 전수검사 보강

초기 수정본을 홈서버에 배포한 뒤 운영 API와 저장 이력을 다시 대조해 다음 경계 결함을 추가로 막았다.

| 심각도 | 추가 확인 사항 | 조치 |
|---|---|---|
| High | `OVERHEATED`/copper-gold event flag 미수집을 `false` 관측으로 간주 가능 | `Boolean` 3상태로 전환, null은 50 중립, 국면 분리도 coverage는 9개 축 기준 상한 |
| High | domain 직접 호출에서 단기·중기 RS null이 0으로 대체돼 상태 승격 가능 | 둘 중 하나라도 null이면 LEADING/IMPROVING fail-closed |
| High | 전략 테마의 중복 표준 key가 더 완전하다는 이유로 canonical sector reference를 교체 가능 | 표준 11개 reference는 표준 sector catalog가 소유, theme은 덮어쓰기 금지 |
| Medium | 선택된 거시 국면만 노출해 근접 후보를 확인하기 어려움 | 5개 거시 국면 점수 전체를 API/UI에 공개 |
| Medium | 운영은 일별, 백테스트는 월말인데 같은 cadence처럼 읽힐 수 있음 | 백테스트 경고에 월중 순위 성과 미검증을 명시 |
| Low | 현재 상위 첫 카드의 `Top1 검증축` 문구가 live leader gate와 pure momentum backtest를 혼동 | `현재 모멘텀 1위`로 변경 |

## 운영 실측 및 독립 재계산

- 홈서버 총수익률 이력: **17/17**, standard SPY+11개 모두 최신일 `2026-08-07` 정렬
- 표준 섹터 카드: **11개**, global current/next/secondary/fading key는 모두 표준 집합의 부분집합
- 현재 leader: `XLK`, `XLE`, `XLI`; SOXX·SMH 등 전략 테마의 표준 summary 유입 **0건**
- 현재 독립 flow 부재는 50이 아니라 `null`, current leader confirmation은 모두 `WATCH`
- Python 독립 재계산으로 11개 전부의 6·12개월 ex-1M 상대수익률, 252일 상대변동성,
  횡단면 percentile을 재산출했고 운영 snapshot과 차이는 반올림 오차 범위(`RS < 5e-7`, percentile 0)
- 7년 월말 walk-forward: 83회, 월평균 Top3 교체율 24.39%
  - 1개월 Top1 SPY 상회 40.96%, 평균 초과수익 -0.09%p
  - 3개월 53.09%, +2.00%p, 중첩 보정 95% 구간 40.40~65.77%
  - 6개월 61.54%, +3.93%p, 중첩 보정 95% 구간 47.12~75.96%

이 결과는 **6개월 수치도 통계적으로 확정적인 우위라고 부르기 어렵다**는 뜻이다. 95% 구간 하단이
50% 아래이고, Top3의 SPY 상회율과 평균 초과수익은 약했다. 따라서 Top1 momentum은 관찰 우선순위이며
자동 매수·섹터 전체 매수 신호가 아니다.

## 3차 보강 — 날짜 있는 EPS revision breadth

후속 우선순위 1의 운영 경로를 구현했다.

- V17부터 회사 analyst snapshot에 당시 확인한 forward-EPS 7/30/90일 revision을 영속한다.
- 표준 섹터 구성종목의 최신 30일 revision을 equal-count 방향 breadth로 집계한다.
- 기준일 3일 이내, 최소 5종목, coverage 50% 이상일 때만 current evidence다.
- 상향 `>+0.10%`, 하향 `<-0.10%`, 나머지는 보합이다.
- 점수는 `round(50 + 50 × (상향-하향)/유효 종목 수)`이며 확률이 아니다.
- source date·coverage·상향/하향 비율을 API/UI에 같이 공개한다.
- 기준일 없는 catalog revision은 현재 점수와 confirmation에서 계속 제외한다.

이 변경은 **현재 계산의 출처·신선도 무결성을 높인 것**이지 장기 예측 성능을 입증한 것이 아니다.
V17 이전 analyst 행에는 revision이 없고 현재 공급자 값으로 과거를 역채우지 않으므로, 전체 composite의
point-in-time walk-forward에는 향후 누적 이력이 필요하다. 배포 직후 coverage 미충족 섹터가 `자료 없음`인
것도 정상적인 fail-closed 상태다.

## 4차 보강 — 공식 ETF 생성·환매와 추적 가격 breadth

후속 우선순위 2의 현재 운영 경로를 구현했다.

- 표준 11개 SPDR ETF 모두 State Street 공식 NAV history의 발행좌수 변화로 1·5·20일 creation/redemption
  흐름을 같은 정의로 계산한다.
- 21개 관측과 기준일 7일 gate를 통과한 flow만 rotation의 4% 축과 confirmation 독립 증거로 사용한다.
- 기존 유동성·신용·가격 재가공 `flowScore`는 독립 flow로 사용하지 않는다.
- catalog 대표 종목의 MA20/50/200 상단 비율을 20:30:50으로 결합하고 최소 10종목·coverage 70%를 요구한다.
- 가격 breadth는 전체 ETF holdings가 아니며 현재는 이유/API/UI 진단에만 사용하고 composite에 추가
  가중하지 않는다.
- V18은 두 증거를 `(sector_key, observed_on)` snapshot으로 영속하고 source date·원시 금액·coverage를
  UI에 같이 공개한다.
- 첫 운영 수집에서 issuer 구 URL의 301, workbook `-` 결측 행, Java `Instant`의 PostgreSQL 직접 binding을
  발견했다. canonical URL+redirect, 명시적 placeholder skip, 공용 temporal 변환과 실제 PostgreSQL
  round-trip/live source contract test로 재발을 막았다.

이 변경도 **독립성과 현재성의 개선**이지 전체 composite 적중률 개선의 증거가 아니다. issuer workbook의
과거 전체는 현재 다운로드된 파일이므로 과거 당시 가용값이라고 단정하지 않는다. V18 이후 immutable
snapshot과 향후 composite ledger만 point-in-time 검증에 사용할 수 있다.

## 5차 보강 — 거시 파생 label과 금융여건 중복 제거

- 동일 연속 거시 입력에서 상위 `macroRegime` label만 바뀌어도 섹터 국면·점수는 바뀌지 않는다.
- 거시 적합도에 이미 포함된 실질금리·유동성·HY OAS를 별도 8% financial-conditions로 재가중하지 않는다.
- 제거한 8%는 상대 모멘텀 4%p, fundamental 4%p로 옮겼다.
- domain invariance 테스트는 통과했지만, 전체 composite의 point-in-time 장기 성능은 아직 검증되지 않았다.
  따라서 이 수정은 공선성·불연속 결함 제거이지 적중률 개선 주장도 아니다.

## 6차 보강 — immutable live composite/OOS outcome 원장

- 운영 전체 composite에 `CURRENT_SECTOR_ROTATION_COMPOSITE_V3` 산식 버전을 부여했다.
- 완료된 SPY+표준 11개 total-return 공통 거래일마다 실제 운영 출력, 11개 component/rank/state,
  macro/momentum/revision/flow/breadth source date·coverage와 신호일/가격 anchor를 V19/V20에 최초 한 번만 저장한다.
- 같은 UTC 날짜의 장중 값을 종가로 오인하지 않도록 22:00 UTC 전에는 직전 공통 거래일만 anchor로 쓴다.
- 21/63/126 공통 거래 세션이 실제로 존재한 뒤에만 섹터·SPY·11개 동일가중 수익률과 초과수익을 붙인다.
- V19 이전 composite를 현재 revision/flow로 역채우지 않는다. 따라서 원장 구축은 검증 **시작**이지 성능
  입증이 아니며, 현재 전체 composite 상태는 `INSUFFICIENT_SAMPLE`이다.

Yahoo adjusted close의 과거 재조정 가능성은 남는다. outcome은 평가 시 같은 데이터 vintage의 시작/종료를
함께 계산해 고정하지만 원 공급자의 과거 revision 이력을 완전히 복원하는 것은 아니다.

## 현재 검증된 것과 검증되지 않은 것

### 검증됨

- 표준 11개 ETF/SPY adjusted-close 상대 모멘텀 계산
- 최근 1개월 제외 6·12개월 50:50 결합
- 252일 상대 변동성 조정
- 완료 월말 no-lookahead walk-forward
- SPY/섹터 동일가중/절대수익 결과 분리
- 표준·테마 유니버스 불변성, 결측 fail-closed, 원자적 refresh 회귀 테스트

### 아직 검증 완료 아님

- 거시·품질·밸류·revision·flow를 합친 전체 rotation score의 과거 적중률
- 고정 DXY·WTI·실질금리 level threshold의 장기 국면 안정성
- categorical jump와 명시적 financial-conditions 중복은 제거했지만 새 전체 가중의 장기 성능
- 날짜 있는 bottom-up earnings revision breadth의 장기 point-in-time 성능
- 표준 11개 전체의 공식 ETF flow는 구현됐지만 장기 point-in-time 성능은 미검증
- 전체 ETF point-in-time holdings 기반 breadth와 cap-weight/equal-weight 비교
- 거래비용·세금·ETF 구성 변경을 포함한 실현 가능 수익률
- 전략 테마 5개 상호 간 독립성(SOXX/SMH 중복 포함)

따라서 현재 제품의 신뢰 가능한 핵심은 **3~6개월 상대 주도 관찰용 모멘텀 레이어**다. 전체 composite를
미래 상승 확률 또는 섹터 BUY 신호로 부르면 안 된다.

## 금융 방법론 교차검증

운영의 6·12개월 결합, 최근 1개월 제외, 변동성 조정은
[MSCI Momentum Indexes Methodology](https://www.msci.com/indexes/documents/methodology/2_MSCI_Momentum_Indexes_Methodology_20250725.pdf)와
[S&P Momentum Indices Methodology](https://www.spglobal.com/spdji/en/documents/methodologies/methodology-sp-momentum-indices.pdf)의
공통 원칙을 섹터/SPY 상대 총수익률에 적용한 내부 프록시다. 공급자 지수를 복제하지 않는다.

거시 순환표는 경제적 설명 변수이지 검증된 확률모형이 아니다. 특히 absolute level은 통화·인플레이션
레짐에 따라 의미가 이동하므로 향후 rolling z-score/변화율 후보와 기존 산식을 **point-in-time
walk-forward**로 비교한 뒤에만 교체한다.

## 후속 우선순위

1. V17 이후 날짜 있는 30일 EPS revision breadth 이력 누적; 90일 breadth는 충분한 이력 후 별도 승인
2. V18 flow·breadth 이력 누적과 공식 holdings 기반 equal-weight/cap-weight 확산도 비교
3. macro component를 level과 trend/z-score 후보로 나눠 walk-forward 비교
4. V19/V20 live composite/OOS ledger 표본 누적 후 최소 표본·subperiod·중첩구간 기준을 별도 승인
5. 거래비용/회전율 민감도, subperiod·recession regime·multiple-testing 보고서 추가

6. 운영 일별 순위와 월말 모델을 분리 검증하고, 전체 composite가 실제로 LEADING/IMPROVING을
   선택하거나 abstain한 이력까지 평가
