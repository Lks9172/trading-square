# ADR-012: 총량이 아닌 point-in-time 순유동성 방향·전환을 우선한다

- 상태: **Accepted**
- 결정일: **2026-08-16**
- 정정일: **2026-08-16** (`WTREGEN` 주간평균 혼용·순발행 퍼센트 변화·후행 flow 동등축 반영 제거)
- 관련 문서: [금융 의사결정 모델](../../docs/finance/FINANCIAL-DECISION-MODEL.md),
  [데이터 원천·신선도](../../docs/finance/DATA-SOURCES-AND-FRESHNESS.md),
  [PDR-009](../../docs/PDR-009-liquidity-impulse-product-interpretation.md),
  [Asset X2 영상 감사](../../docs/ASSET-X2-VIDEO-COVERAGE-2026-07-26.md)
- 대체/피대체: 없음

## 맥락

기존 `LIQUIDITY_DIRECTION`은 RRP·TGA·MMF·준비금 방향과 미국 M2 YoY를 같은 비중의 부호로 합쳤다.
이는 월간 후행 총량인 M2와 주간 대차대조표 배관을 섞고, TGA 감소와 ON RRP 감소를 조건 없이 위험자산
호재로 세는 문제가 있었다. 2026-08-10 자산제곱 영상은 총량보다 방향·속도·전환을 우선하고, TGA 재충전,
ON RRP 잔액 소진, 디레버리징에 의한 전달 차단을 별도로 보라고 제안했다.

영상의 프레임과 자막을 재검증했으나 `Fed assets - TGA - ON RRP`는 연준 공식 통계가 아니라 널리 쓰이는
**분석 프록시**다. 또한 TGA 감소의 원인을 잔액만으로 확정하거나 ON RRP 감소를 위험자산 직접 유입으로
해석할 수 없다. 따라서 영상 문구를 그대로 매수 산식으로 옮기지 않고 회계적 방향과 추론을 분리한다.

## 결정

1. 순유동성 프록시는 `WALCL - WDTGAL - RRPONTSYD × 1,000`으로 계산한다.
   - WALCL·WDTGAL: 같은 수요일 시점값, 백만 달러
   - RRPONTSYD: 십억 달러
   - 출력 잔액: 조 달러, 변화: 십억 달러
2. 세 원천의 최신 날짜를 섞지 않는다. 가장 이른 최신일을 공통 anchor로 잡고 각 원천을 해당 날짜
   이전 마지막 관측으로 as-of join한다. 주간 항목은 최대 10일, 일간 RRP는 최대 3일 간격만 허용한다.
3. `LIQUIDITY_DIRECTION`은 순유동성 4주 충격 상태를 우선한다. 계산 불가할 때만 기존 복합축을 fallback으로
   사용한다.
4. 4주 충격 상태는 ±250억 달러에서 ±1, ±1,000억 달러에서 ±2다. 이는 잡음 억제용 휴리스틱이며
   확률이나 검증된 기대수익률이 아니다.
5. 전환은 직전 4주 충격과 최근 4주 충격이 0선을 교차하고 최근 절대 충격이 250억 달러 이상일 때만
   +1/-1 이벤트로 기록한다. 지속 상태와 분리한다.
6. TGA 감소의 현재 준비금 공급과 분기 시장성 국채 순발행 압력은 별도 축으로 유지한다. 향후 발행압력이
   현재 공급을 이미 상쇄했다고 간주해 TGA 축을 중립 처리하지 않는다. 두 조건이 겹치면 재충전 여부를
   모니터링하는 context만 표시한다.
7. 분기 순발행은 부호가 바뀔 수 있는 거래 흐름이므로 퍼센트 변화를 사용하지 않는다. 최근 분기와 직전
   4분기 평균의 차이를 십억 달러로 계산하고 ±500억 달러에서 +1/-1 방향으로 분류한다.
8. ON RRP 감소는 현재 공급 방향으로 남기되 잔액 1,000억 달러 이하 또는 3년 고점의 10% 이하이면
   추가 runoff 여력 제한을 별도 경고한다. 이를 은행 준비금 부족이라고 부르지 않는다.
9. 신용 스트레스, VIX 30 이상, SOFR-IORB 10bp 이상을 유동성 전달 스트레스 3축으로 분리한다. 2축 이상이면
   NASDAQ 적극 매수 상한을 BUY, 레버리지를 HOLD로 제한한다. 가용 입력이 2/3 미만이어도 저스트레스로
   간주하지 않고 같은 상한을 적용한다.
10. 미국 M2는 YoY와 3개월 연율화 속도·가속을 따로 표시하되 순유동성 전환을 대체하지 않는다. M2SL의
    period date와 월간 H.6 발표 지연을 반영해 원천·파생 freshness는 95일로 둔다.
11. 현재 유동성 배관 정렬은 TGA·ON RRP·은행 준비금의 주간/일간 3개 확인축만 사용한다. 세 값은 동일한
    연준 대차대조표에 연결되어 상호 독립 팩터가 아니므로 합치도·가용률로만 표현한다. 후행 Z.1 분기
    순거래 flow는 이 정렬에서 제외하고 과거 발행 맥락으로만 노출한다.
12. 방향 파생값은 snapshot 계산일이 아니라 해당 원천의 마지막 관측일을 보존한다. 3축 합성값의 날짜는
    가용 3축 중 가장 오래된 최신 관측일로 두어 최신처럼 보이는 lineage 왜곡을 막는다.
13. 은행 준비금은 방향과 별도로 현재 잔액을 조 달러 단위로 노출한다. 3조 달러는 영상에서 제시한
    **모니터링 휴리스틱**일 뿐 연준의 공식 안전선, 시장 붕괴 임계값 또는 수익 신호로 부르지 않는다.
14. 현재 주간 TGA context를 만들 때 후행 분기 순거래의 원천 분기일이 270일 freshness 안인지 다시
    검증한다. 분기값이 만료되면 현재 anchor 날짜를 가진 context를 만들지 않아 오래된 Z.1 값이 최신
    파생값으로 세탁되는 것을 막는다.

## 감사 정정 근거

- `WTREGEN`은 TGA **week average**, `WDTGAL`은 WALCL과 동일한 **Wednesday level**이다. point-in-time
  차감에는 WDTGAL을 사용하고 WTREGEN은 감사용 원천으로만 보존한다.
- `BOGZ1FU313161105Q`는 잔액이 아니라 marketable Treasury liability **transactions flow**다. 0 또는 음수를
  지날 수 있어 `(latest/prior)-1`은 경제적 방향을 뒤집을 수 있으므로 금액 차이로 교체했다.
- 이 flow의 FRED 관측일은 공표일이 아니라 대표 분기의 시작일이다. 200일 단순 달력 게이트는 다음 Z.1
  공표 전 최신값까지 stale 처리할 수 있어 270일로 보정한다. 다음 분기 미수집 시에는 유예 종료 후
  fail-closed 한다.
- TGA 감소는 현재 reserve injection이고 최신 공표 분기 순거래 확대는 후행 역사 맥락이다. 미래 TGA
  재충전이나 경매 일정이 관측되기 전 두 사실을 기계적으로 상계하거나 미래 drain으로 단정하지 않는다.
- 추가 감사에서 후행 분기 순거래를 현재 주간 3축과 동등한 네 번째 축으로 세면 현재성·독립성 모두를
  과장한다는 문제를 확인했다. 따라서 `LIQUIDITY_PLUMBING_*`의 API 키는 호환 유지하되 의미를 현재 3축
  합치도로 정정하고, 미래 압력을 암시하지 않는 `TGA_LAGGED_ISSUANCE_CONTEXT`를 정식 맥락 키로 사용한다.

## 도메인 소유권과 경계

- 산식·단위·as-of join·임계값·fallback은 framework-free Market domain policy가 소유한다.
- FRED JSON, HTTP 재시도와 source key mapping은 infrastructure adapter가 소유한다.
- snapshot 조합과 stale gate는 Market application service가 소유한다.
- UI와 Telegram은 domain 결과를 설명할 뿐 재계산하지 않는다.
- controller DTO, JDBC, cache, React type은 domain에 들어가지 않는다.

## 원천과 사실성

- Federal Reserve H.4.1: https://www.federalreserve.gov/releases/h41/
- New York Fed ON RRP operations: https://www.newyorkfed.org/markets/desk-operations/reverse-repo
- Treasury operating cash balance: https://fiscaldata.treasury.gov/datasets/daily-treasury-statement/operating-cash-balance
- FRED M2SL: https://fred.stlouisfed.org/series/M2SL

검색·재확인일은 2026-08-16이다. 위 기관은 각 원천 잔액의 권위자지만, 세 잔액을 차감한 값의 자산가격
예측력을 보증하지 않는다.

## 검토한 대안

- M2 YoY 단독: 월간 후행 총량이라 전환 감지에 늦어 기각.
- 현재 TGA 감소와 향후 순발행 압력을 하나의 축에서 기계적으로 상계: 관측 사실과 미래 가능성을 섞어 기각.
- 영상의 자산 반응 순서를 고정 선행기간으로 점수화: 시기별 규제·금리·수급 변화로 비정상적이라 기각.
- 중앙은행 5곳 합산 지표를 결측 원천으로 합성: 통화·주기·환산시점이 다른 값을 가짜 글로벌 지표로 만들 수
  있어 공식 시계열과 환율 point-in-time 계약이 준비될 때까지 기각.
- 0DTE·margin debt·레버리지 ETF 통계를 synthetic neutral로 채움: 공식 최신 원천 부재 시 거짓 안정으로
  보일 수 있어 기각.

## 결과·호환성과 롤백

API schema는 자유형 derived map에 키가 추가되는 하위 호환 변경이다. `LIQUIDITY_DIRECTION` 의미는
순유동성 우선으로 바뀌므로 거시·섹터·자산이 같은 방향 근거를 공유한다. 이전 동작으로 롤백하려면 신규
순유동성 계산과 우선 분기를 제거하면 되며 DB migration이나 데이터 복원은 필요 없다.

## 관측과 검증

- 순유동성 anchor date, 단위 변환, 4주/8주 전 as-of 계산 unit test
- 후행 발행압력의 방향이 현재 3축 정렬을 바꾸지 않는 unit test
- WDTGAL/WALCL 수요일 시점 정렬, 순발행 flow의 0선 통과 unit test
- M2 YoY와 최근 속도 분리 unit test
- 전달 스트레스 2/3 미만을 저위험으로 오인하지 않는 action gate test
- Telegram에 4주 충격·전환·전달/TGA/RRP 경고를 싣는 adapter test
- 만료된 분기 순거래가 현재 TGA context로 재날짜화되지 않는 lineage test
- 전체 domain/application/adapters/bootstrap/architecture test와 클라이언트 build

## 남은 한계와 재검토 조건

임계값은 아직 walk-forward 성과로 확률 보정되지 않았다. 분기 순발행 ±500억 달러와 순유동성
±250억/±1,000억 달러는 휴리스틱이며 향후 경매 일정이 아니다.
글로벌 중앙은행 5곳·0DTE·margin debt는 현재 결정 산식에 넣지 않았다. 공식 point-in-time 원천과 최소 5년
OOS 검증이 확보되면 별도 ADR로 재검토한다.
