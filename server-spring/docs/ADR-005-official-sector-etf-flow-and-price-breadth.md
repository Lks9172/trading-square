# ADR-005: 공식 섹터 ETF 생성·환매와 추적 구성종목 가격 breadth를 분리해 사용한다

- 문서 상태: **DECISION**
- 상태: **Accepted / production**
- 결정일: 2026-08-08
- 관련 ADR: [`ADR-003`](ADR-003-sector-rotation-evidence-integrity.md), [`ADR-004`](ADR-004-dated-sector-eps-revision-breadth.md)
- 관련 PDR: [`../../docs/PDR-004-sector-flow-and-price-breadth-disclosure.md`](../../docs/PDR-004-sector-flow-and-price-breadth-disclosure.md)

## 맥락

기존 `flowScore`는 일부 섹터만 유동성·신용·가격 입력을 재가공한 값이었다. 같은 입력이 거시 적합도와
financial conditions에도 들어가므로 독립 수급처럼 사용하면 중복 가중과 출처 오표기가 발생한다. 또한 ETF
가격이 강해도 소수 대형주만 오른 것인지 구성종목 전반으로 확산됐는지 구분할 현재 breadth가 없었다.

## 결정

### 공식 ETF 생성·환매

표준 11개 SPDR 섹터 ETF의 State Street 공식 NAV history workbook에서 거래일별 `NAV`, `Shares
Outstanding`, `Total Net Assets`를 읽는다.

```text
daily flow_t = (shares_t - shares_t-1) × NAV_t
flow_5d_pct = 100 × Σ(last 5 daily flows) / total_net_assets_t
flow_20d_pct = 100 × Σ(last 20 daily flows) / total_net_assets_t
flow score = clamp(round(50 + 6 × flow_5d_pct + 4 × flow_20d_pct), 0, 100)
```

- 최소 21개 유효 관측이 있어야 한다.
- 기준일 7일 이내 snapshot만 현재 증거로 읽는다.
- score는 내부 휴리스틱 지수이며 자금유입 확률이나 수익 확률이 아니다.
- 현재 rotation score에는 4% 가중으로 사용하며, 날짜가 있는 값만 leadership confirmation의 독립 flow
  축으로 인정한다.

### 추적 구성종목 가격 breadth

표준 섹터 catalog의 대표 추적 종목 가격 이력을 Research ACL이 Company price port에서 읽어 20·50·200일
단순이동평균 위 종목 비율을 equal-count로 계산한다.

```text
breadth score = round(0.20 × above_MA20_pct
                    + 0.30 × above_MA50_pct
                    + 0.50 × above_MA200_pct)
```

- 200개 관측, 기준일 7일 이내, 최소 10종목, catalog coverage 70% 이상을 모두 요구한다.
- 현재는 rotation 이유와 API/UI 진단 근거로만 사용한다. 장기 OOS 검증 전에는 rotation score나
  confirmation을 추가 가중하지 않는다.
- 이는 **catalog 대표 추적 종목 breadth**이며 ETF 전체 공식 보유종목 breadth가 아니다.

## 경계와 소유권

- Research domain: flow/breadth 계산, 최소 이력·coverage·점수 범위 소유
- Research application: 표준 섹터 universe, 현재 기준일, 부분실패와 last-valid 저장 조정
- Official adapter: bounded HTTPS/OOXML parsing과 State Street workbook shape 검증
- Company ACL adapter: Company price type을 Research 가격 시계열로 번역
- JDBC adapter: `research` schema current snapshot 저장·조회
- REST/UI: 날짜·coverage·원시 비율을 전달/표시하며 산식을 재계산하지 않음

Domain/application은 Spring, JDBC, Yahoo DTO, workbook, Controller DTO를 참조하지 않는다.

## 저장·동시성·실패 계약

- Flyway V18의 `research.sector_fund_flow_snapshot`, `research.sector_price_breadth_snapshot`에
  `(sector_key, observed_on)`으로 멱등 upsert한다.
- PostgreSQL은 양수·finite·점수 범위·날짜·coverage 묶음을 check constraint로 재검증한다.
- scheduler는 전용 단일 thread, JVM non-overlap, PostgreSQL advisory task lock을 사용하고 90초 후 시작해
  6시간 fixed delay로 갱신한다.
- 섹터별 flow와 breadth 실패를 독립적으로 기록한다. 새 수집 실패는 기존 정상 snapshot을 삭제하지 않는다.
- HTTP body는 2MiB, 필요한 OOXML uncompressed entry는 8MiB로 제한하고 DTD/external entity를 비활성화한다.
- issuer의 canonical HTTPS workbook 경로를 기본값으로 사용하고 HTTPS redirect를 허용한다. 2026-08-08
  최초 운영 점검에서 구 intermediary 경로의 301을 비정상 응답으로 처리한 회귀를 확인해 이 계약을 추가했다.
- workbook의 과거 결측 행은 issuer가 `-`로 표시한다. 이런 명시적 결측 행만 제외하고, 예상하지 못한
  비숫자 값·열 변경은 계속 fail-closed한다. opt-in live source contract test로 실제 XLK 파일을 검증한다.
- PostgreSQL `timestamptz`에는 공용 `PostgresTemporal` 변환만 사용한다. 같은 점검에서 Java `Instant` 직접
  binding이 실제 PostgreSQL에서 실패한 문제를 실 DB round-trip 테스트로 고정했다.
- 결측·stale·coverage 부족은 공개 `null`이며 0이나 관측된 50으로 저장하지 않는다.

## 원천과 point-in-time 한계

- 공식 원천: [State Street XLK NAV history workbook](https://www.ssga.com/library-content/products/fund-data/etfs/us/navhist-us-en-xlk.xlsx)
- 상품 설명: [State Street XLK](https://www.ssga.com/us/en/intermediary/etfs/state-street-technology-select-sector-spdr-etf-xlk)
- 가격 원천: Yahoo chart history를 사용하는 기존 Company price adapter
- workbook은 과거 전체를 현재 시점에 제공하며 공급자가 과거 값을 수정할 가능성을 배제할 수 없다. V18
  snapshot부터 시스템이 실제 수집한 결과는 보존하지만, immutable source artifact와 월말 composite ledger가
  완성되기 전에는 이를 완전한 point-in-time 과거 flow backtest로 주장하지 않는다.

## 검토한 대안

- 가격·유동성 blend를 flow로 유지: 입력 중복과 오표기 때문에 기각
- 가격변화로 ETF flow 추정: creation/redemption과 가격 수익률을 분리할 수 없어 기각
- coverage 부족을 breadth 50으로 공개: 실제 중립과 자료 없음을 혼동해 기각
- breadth를 즉시 composite에 가중: 장기 OOS 검증이 없어 보류
- 전체 ETF holdings를 매번 수집: point-in-time 구성 변경과 공급자 계약을 먼저 설계해야 하므로 후속 과제

## 결과·롤백·검증

- 장점: 11개 표준 섹터에 동일 정의의 issuer 기반 flow가 생기고 소수 대형주 주도를 별도 진단한다.
- 단점: shares outstanding 변화는 creation/redemption의 근사이며 장중 주문 방향이나 최종 투자자 유형은 모른다.
- 단점: catalog breadth는 생존편향과 구성 변경 영향을 받으므로 역사 성능 검증용으로 바로 쓰지 않는다.
- 롤백: 직전 애플리케이션으로 되돌리되 additive V18 테이블과 누적 snapshot은 보존한다.
- 검증: domain 공식, workbook parser shape/보안, application 부분실패, JDBC migration, 실제 PostgreSQL
  temporal round-trip, canonical URL/redirect, API/UI null/date/coverage, 전용 scheduler lock과 홈서버 V18/row
  count를 확인한다.

## 재검토 조건

- immutable issuer workbook artifact와 월말 point-in-time composite ledger 확보
- 최소 36개월 flow/breadth snapshot 누적
- 공식 ETF holdings의 point-in-time 구성과 cap/equal-weight breadth 비교
- 거래비용·subperiod를 포함한 walk-forward에서 breadth 추가 가중의 안정적 개선 확인
