# MACD 교차·다이버전스 방법론

- 문서 상태: **CURRENT**
- 최종 코드 대조일: **2026-08-21**
- 구현: `io.macrosquare.technical.domain.MacdSignalPolicy`

## 목적과 한계

이 지표는 기업·지수·자산의 **진입/축소 타이밍을 보조 확인**한다. 기업가치, 바닥 확률, 기대수익률,
자동 매수·매도 판단이 아니다. 현재 Company Score, B Score, 찐바닥, 반전확인, 섹터 순환 점수에는
가산·감산하지 않는다.

참고 영상은 [Money Comics, 「여기가 고점인지 어떻게 알아요?」](https://www.youtube.com/watch?v=8L6Cm4xDkuQ)이며,
표준 산식은 [StockCharts ChartSchool MACD](https://chartschool.stockcharts.com/table-of-contents/technical-indicators-and-overlays/technical-indicators/macd-moving-average-convergence-divergence-oscillator)와
교차 확인했다. 영상의 핵심은 일봉 우선·주봉 보조, MACD 시그널 교차, 히스토그램의 힘 변화,
가격과 히스토그램의 일반 다이버전스이며 단독 매매를 경고한다.

## 표준 산식

| 항목 | 산식/의미 |
|---|---|
| MACD line | `EMA12(close) - EMA26(close)` |
| Signal line | `EMA9(MACD line)` |
| Histogram | `MACD line - Signal line` |
| 상방 골든크로스 | 직전 histogram `<= 0`, 현재 `> 0` |
| 하방 데드크로스 | 직전 histogram `>= 0`, 현재 `< 0` |
| 0선 국면 | MACD line의 0선 상·하 위치 |

EMA 최초값은 해당 기간 단순평균으로 시드한다. 최소 34개 종가가 없으면 `UNAVAILABLE`로 닫힌다.
기존 `NASDAQ_CROSS`는 **50일선과 200일선의 이동평균 교차**이며 MACD 교차와 다른 지표다.

## 히스토그램 상태

- `EXPANDING_POSITIVE`: 0 위에서 증가, 상승 모멘텀 확대
- `CONTRACTING_POSITIVE`: 0 위에서 감소, 상승 모멘텀 둔화
- `EXPANDING_NEGATIVE`: 0 아래에서 더 감소, 하락 모멘텀 확대
- `CONTRACTING_NEGATIVE`: 0 아래에서 0으로 접근, 하락 모멘텀 둔화
- `FLAT`: 실질 변화 없음

상승 모멘텀 둔화는 곧바로 하락 확정이 아니며, 하락 모멘텀 둔화도 바닥 확정이 아니다.

## 일반 다이버전스

미래 데이터 누수를 막기 위해 종가 피벗은 우측 관측치가 모두 생긴 뒤에만 확정한다.

| 프레임 | 피벗 좌·우 | 피벗 간격 | 활성 기간 |
|---|---:|---:|---:|
| 일봉 | 각 3거래일 | 5~60거래일 | 확인 후 20거래일 |
| 주봉 | 각 2주 | 3~26주 | 확인 후 8주 |

- 상승 다이버전스: 두 histogram이 음수이고 가격 저점은 0.5% 이상 낮아졌지만 두 번째 음수
  histogram 절댓값은 첫 번째보다 10% 이상 축소
- 하락 다이버전스: 두 histogram이 양수이고 가격 고점은 0.5% 이상 높아졌지만 두 번째 양수
  histogram은 첫 번째보다 10% 이상 축소
- 신호일은 두 번째 가격 피벗 날짜가 아니라 **우측 피벗 확인이 끝난 날짜**다.
- 활성 기간을 넘으면 과거 관측으로 표시하고 `ON`으로 표시하지 않는다.

이는 close-only 휴리스틱이다. 고가·저가 또는 거래량 다이버전스를 가장하지 않는다.

## 일봉·주봉 계약

- 일봉: 최근 타이밍과 교차 경과 거래일
- 주봉: 같은 일봉 원천을 주별 마지막 종가로 별도 집계한 중기 확인
- 최신 일봉이 금요일 전이면 현재 주봉을 `진행 중 주봉`으로 표시한다. 휴장일이 있는 주에는 보수적인
  UI 힌트일 뿐 완전한 거래소 캘린더 판정이 아니다.

## 적용 범위

- 회사 상세: 조회되는 모든 기업의 직접 가격 이력
- 시장: S&P 500, NASDAQ, KOSPI/KOSDAQ, 금·은·구리·WTI, DXY·주요 환율, 5개 코인
- 섹터/테마: GICS 11개 ETF와 SOXX·SMH·ITA·GRID·IGF
- Telegram 회사 알림: startup·신규 편입·신호 강화의 각 회사에 일봉·주봉 compact 근거
- Telegram 시장 알림: startup·자산 신호 변경에 S&P500/NASDAQ 일봉·주봉 compact 근거

Telegram은 수치 전체가 아니라 기준일, 최근 교차와 경과 관측 수, signal-line 위치, histogram 상태,
확인된 다이버전스 활성/과거 여부만 표시한다. 기업 근거는 2시간 이내의 version 6 영속 요약을 사용하며
누락·stale이면 직접 현재 평가로 fallback한다. 시장 근거는 현재 snapshot의 signal-eligible derived 값만
사용한다. 어느 경우에도 MACD가 알림 편입 조건이나 실행 액션을 바꾸지 않는다.

## 검증 상태

- 합성 시계열로 상·하향 교차, 부족 데이터 fail-closed, 일/주 별도 집계 검증
- 다이버전스는 우측 피벗 확인 전 `ON`이 되지 않는 회귀 테스트 적용
- 회사와 시장은 같은 framework-free 도메인 정책을 사용
- **실제 수익률 적중률은 아직 운영 점수로 보정하지 않았다.** 향후 point-in-time 워크포워드 검증 전에는
  승률·확률·매수 등급으로 표시하지 않는다.
