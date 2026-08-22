"use client";

import { InfoTooltip } from "./InfoTooltip";

interface DataPoint {
  code: string;
  value: number | null;
  date: string;
  source: string;
  eligibleForSignals?: boolean;
}

interface DerivedPoint {
  name: string;
  value: number | null;
  date: string;
  formula: string;
  eligibleForSignals?: boolean;
}

const FRED_LABELS: Record<string, { label: string; unit: string; desc: string; freq: string }> = {
  DGS10:        { label: "10Y 금리",       unit: "%",    desc: "미국 10년 국채 금리. 장기금리의 기준이며 성장주 부담/할인율 판단에 사용", freq: "일간" },
  T10YIE:       { label: "기대인플레(BEI)", unit: "%",    desc: "10년 기대인플레이션. 실질금리 계산의 핵심 입력값", freq: "일간" },
  T10Y2Y:       { label: "장단기 금리차",   unit: "%",    desc: "10Y-2Y 스프레드. 음수면 경기침체 선행신호", freq: "일간" },
  VIXCLS:       { label: "VIX",            unit: "pt",   desc: "S&P500 옵션 내재변동성. 20↓ 안정, 30↑ 경계, 35↑ 공포", freq: "일간" },
  BAMLH0A0HYM2: { label: "하이일드 스프레드", unit: "%",  desc: "투기등급 채권과 국채 금리 차이. 6%↑이면 신용시장 스트레스", freq: "일간" },
  STLFSI4:      { label: "금융스트레스",    unit: "σ",    desc: "세인트루이스 금융스트레스지수. 0 이상이면 평균 이상 스트레스", freq: "주간" },
  WALCL:        { label: "연준 총자산",     unit: "$M",   desc: "연준 대차대조표 규모. 증가=양적완화, 감소=양적긴축", freq: "주간" },
  WRESBAL:      { label: "지급준비금",      unit: "$M",   desc: "은행 준비금 잔액. 유동성 체력의 핵심 지표", freq: "주간" },
  RRPONTSYD:    { label: "역레포(RRP)",    unit: "$B",   desc: "연준의 익일물 역레포 잔액. 감소는 준비금 공급 방향이지만 위험자산 유입을 뜻하지 않으며, 잔액 소진 시 추가 공급 여력도 줄어듭니다.", freq: "일간" },
  WDTGAL:       { label: "TGA 수요일 잔액", unit: "$M",   desc: "WALCL과 같은 수요일 시점의 재무부 일반계정 잔액. 순유동성 계산에 사용합니다.", freq: "주간" },
  WTREGEN:      { label: "TGA 주간평균(참고)", unit: "$M", desc: "주간 평균 TGA 잔액. 감사용으로 보존하며 point-in-time 순유동성에는 사용하지 않습니다.", freq: "주간" },
  TREASURY_MARKETABLE_ISSUANCE: { label: "미 국채 순발행 압력", unit: "$M", desc: "연준 Z.1의 시장성 국채 분기 순거래. 양수가 크면 국채 공급이 시중 유동성을 흡수할 수 있습니다. 분기 자료이므로 단기 경매 일정의 대체값은 아닙니다.", freq: "분기" },
  WRMFNS:       { label: "MMF 잔액",       unit: "$B",   desc: "머니마켓펀드 총 잔액. 위험자산 대기 자금 규모", freq: "주간" },
  M2SL:         { label: "M2 통화량",      unit: "$B",   desc: "광의 통화량. YoY 방향이 유동성 추세를 결정", freq: "월간" },
  UNRATE:       { label: "실업률",         unit: "%",    desc: "미국 실업률. 4% 미만이면 완전고용 수준", freq: "월간" },
  ICSA:         { label: "신규실업수당",    unit: "건",   desc: "주간 신규 실업수당 청구건수. 30만↑이면 경기침체 신호", freq: "주간" },
  SOFR:         { label: "SOFR",           unit: "%",    desc: "담보부 단기금리. 자금시장 상태 판단", freq: "일간" },
  EFFR:         { label: "EFFR",           unit: "%",    desc: "무담보 단기금리. SOFR과의 스프레드로 유동성 긴장도 확인", freq: "일간" },
};

const YAHOO_LABELS: Record<string, { label: string; unit: string; desc: string }> = {
  SP500:    { label: "S&P 500",    unit: "pt",    desc: "미국 대형주 500개 지수. 시장 전체 체온계" },
  NASDAQ:   { label: "나스닥",     unit: "pt",    desc: "기술주 중심 지수. 성장주 민감도 높음" },
  KOSPI:    { label: "코스피",     unit: "pt",    desc: "한국 종합주가지수" },
  GOLD:     { label: "금",         unit: "$/oz",  desc: "안전자산. 실질금리·DXY·중앙은행 매수에 반응" },
  SILVER:   { label: "은",         unit: "$/oz",  desc: "산업+귀금속 이중 성격. 금은비로 상대가치 판단" },
  COPPER:   { label: "구리",       unit: "$/lb",  desc: "닥터 코퍼. 경기 선행 2~3개월. ISM과 함께 확인" },
  WTI:      { label: "WTI 원유",   unit: "$/bbl", desc: "에너지 가격. 인플레·지정학 영향" },
  SKEW:     { label: "SKEW",       unit: "pt",    desc: "CBOE 꼬리위험 지수. 100 기준. 145+ 구간이면 대형 급락 헤지 수요 급증 해석" },
  VVIX:     { label: "VVIX",       unit: "pt",    desc: "VIX 의 변동성. 80~100 정상, 110+ 변동성 레짐 변화 선행 신호" },
  OVX:      { label: "OVX",        unit: "pt",    desc: "CBOE Oil VIX. 30~40 정상, 60+ 지정학/에너지 쇼크 구간" },
  DXY:      { label: "달러인덱스", unit: "pt",    desc: "달러 강약. 약세→금/신흥국 우호, 강세→위험자산 부담" },
  USDKRW:   { label: "원/달러",    unit: "₩",     desc: "원화 환율. 외국인 수급·환위험 프록시" },
  USDJPY:   { label: "달러/엔",    unit: "¥",     desc: "엔캐리/위험선호 보조지표" },
};

const DERIVED_LABELS: Record<string, { label: string; unit: string; desc: string; formula: string }> = {
  REAL_YIELD:         { label: "실질금리",     unit: "%",   desc: "금의 핵심 드라이버. 하락하면 금 강세", formula: "10Y 금리 - 기대인플레(BEI)" },
  GOLD_SILVER_RATIO:  { label: "금은비",       unit: "",    desc: "80 이상이면 은 저평가 가능성. 경기회복 동시 확인 필요", formula: "금 ÷ 은" },
  COPPER_GOLD_RATIO:  { label: "구리금비",     unit: "",    desc: "경기 vs 안전선호 비율. 상승전환이면 경기회복 신호", formula: "구리 ÷ 금" },
  NASDAQ_SMA200:      { label: "나스닥 200DMA", unit: "pt", desc: "200일 이동평균. 장기 추세의 핵심 기준선", formula: "최근 200거래일 종가 평균" },
  NASDAQ_DISPARITY:   { label: "나스닥 이격도", unit: "%",  desc: "200DMA 대비 괴리. -25% 이하면 과매도 구간", formula: "(현재가 - 200DMA) ÷ 200DMA × 100" },
  NASDAQ_DISPARITY_20:  { label: "나스닥 이격도 20", unit: "%", desc: "20일 이동평균 대비 괴리. 단기 추세", formula: "(PRICE - SMA20)/SMA20*100" },
  NASDAQ_DISPARITY_60:  { label: "나스닥 이격도 60", unit: "%", desc: "60일 이동평균 대비", formula: "(PRICE - SMA60)/SMA60*100" },
  NASDAQ_DISPARITY_120: { label: "나스닥 이격도 120", unit: "%", desc: "120일 이동평균 대비. 중기 추세", formula: "(PRICE - SMA120)/SMA120*100" },
  KOSPI_DISPARITY_20:   { label: "코스피 이격도 20", unit: "%", desc: "20일 이동평균 대비", formula: "(KOSPI - SMA20)/SMA20*100" },
  KOSPI_DISPARITY_60:   { label: "코스피 이격도 60", unit: "%", desc: "60일 이동평균 대비", formula: "(KOSPI - SMA60)/SMA60*100" },
  KOSPI_DISPARITY_120:  { label: "코스피 이격도 120", unit: "%", desc: "120일 이동평균 대비", formula: "(KOSPI - SMA120)/SMA120*100" },
  NASDAQ_WEEKLY_20MA:          { label: "나스닥 주봉 20MA", unit: "pt", desc: "최근 20주 종가 평균. 장기투자자 진입 기준 (영상2·3)", formula: "SMA(weekly close, 20)" },
  NASDAQ_WEEKLY_20MA_RECOVERY: { label: "나스닥 주봉 20MA 회복", unit: "", desc: "1=이번 주 상향 돌파, 0.5=상회 유지, 0=하회", formula: "이번 주 close vs 20MA" },
  KOSPI_WEEKLY_20MA:           { label: "코스피 주봉 20MA", unit: "pt", desc: "최근 20주 종가 평균", formula: "SMA(weekly close, 20)" },
  KOSPI_WEEKLY_20MA_RECOVERY:  { label: "코스피 주봉 20MA 회복", unit: "", desc: "1=이번 주 상향 돌파, 0.5=상회 유지, 0=하회", formula: "이번 주 close vs 20MA" },
  ICSA_REGIME_LABEL:           { label: "ICSA 매트릭스", unit: "", desc: "200DMA × ICSA 4구획. +2=안정확장 / +1=조정매수기회 / -1=모멘텀둔화 / -2=구조적위험 (영상3 §174)", formula: "200DMA 상/하회 × ICSA 20만대/30만+" },
  WTI_60D_CHANGE:              { label: "유가 60일 변화",  unit: "%", desc: "WTI 60일 변화율. 2~3개월 뒤 CPI 지연 반영 방향 (영상5 §7m7s)", formula: "(cur - 60일전)/60일전 × 100" },
  CPI_OIL_LAG_PRESSURE:        { label: "유가→CPI 압력",   unit: "", desc: "-2=강한 완화 / -1=완화 / 0=중립 / +1=상승 / +2=강한 상승 (2~3개월 뒤 CPI 전망)", formula: "WTI 60D 변화율 기반 구간 점수" },
  SILVER_OUTPERFORM_SETUP:     { label: "은 아웃퍼폼 셋업", unit: "", desc: "1=금은비≥70 AND ISM≥50 동시 충족 (영상2 '60~80 이상 + 경기회복 동반')", formula: "gsr>=70 AND ism>=50" },
  COPPER_STRONG_SETUP:         { label: "구리 강매수 복합", unit: "", desc: "1=ISM≥50 + 금구리비>0.00125 + ICSA<250K 3조건 전부 충족 (영상2)", formula: "ism>=50 AND cgr>0.00125 AND icsa<250K" },
  COPPER_SETUP_COUNT:          { label: "구리 조건 충족",  unit: "/3", desc: "구리 강매수 3조건 중 충족 개수", formula: "count of 3 conditions met" },
  LIQUIDITY_DIRECTION:         { label: "미국 유동성 방향", unit: "", desc: "미국 순유동성 4주 충격을 우선한 -2~+2 환경 상태. 계산 불가 시에만 RRP·TGA·MMF·준비금·M2 복합축으로 대체합니다.", formula: "미국 순유동성 4주 상태 우선, 결측 시 기존 방향 복합" },
  TREASURY_NET_ISSUANCE_CHANGE_BN: { label: "미 국채 분기 순발행 변화", unit: "$B", desc: "최근 분기 시장성 국채 순거래와 직전 4개 분기 평균의 차이. 부호 전환 가능한 흐름이므로 퍼센트 변화로 계산하지 않습니다.", formula: "(최근 분기 - 직전 4분기 평균) ÷ 10억달러" },
  TREASURY_ISSUANCE_DIRECTION: { label: "미 국채 분기 순거래 방향", unit: "", desc: "+1은 최신 공표 분기 거래 flow가 직전 4분기 평균보다 500억달러 이상 높고, -1은 500억달러 이상 낮으며, 0은 잡음 구간입니다. 후행값이며 경매·재충전 예측이 아닙니다.", formula: "최신 공표 분기 순거래 변화 ±500억달러 상태" },
  WRESBAL_DIRECTION:           { label: "은행 준비금 방향", unit: "%", desc: "양수면 은행의 대출·투자 여력이 개선되는 방향, 음수면 유동성 체력이 약해지는 방향입니다.", formula: "최근 2주 평균 ÷ 직전 2주 평균 - 1" },
  LIQUIDITY_PLUMBING_SCORE:    { label: "현재 유동성 3축 점수", unit: "/100", desc: "TGA↓·RRP↓·은행 준비금↑의 현재 방향 합치도입니다. 세 항목은 같은 연준 대차대조표에 연결된 확인값으로 독립 요인이 아니며 수익 확률도 아닙니다.", formula: "현재 우호 축-비우호 축을 0~100으로 정규화" },
  LIQUIDITY_PLUMBING_SIGNAL:   { label: "현재 유동성 3축 정렬", unit: "", desc: "+2=3축 모두 공급, +1=2축 이상 공급·흡수 없음, 0=혼조, -1=2축 이상 흡수·공급 없음, -2=3축 모두 흡수. 후행 분기 국채 flow는 제외합니다.", formula: "TGA·RRP·준비금 현재 방향 정렬" },
  LIQUIDITY_PLUMBING_BULLISH_AXES: { label: "유동성 공급 축", unit: "/3", desc: "현재 시장 유동성 공급 방향을 가리키는 확인축 개수입니다.", formula: "TGA↓, RRP↓, 준비금↑ 충족 개수" },
  LIQUIDITY_PLUMBING_BEARISH_AXES: { label: "유동성 흡수 축", unit: "/3", desc: "현재 3개 확인축 중 비우호 방향 개수입니다.", formula: "3개 축 중 비우호 방향 개수" },
  LIQUIDITY_PLUMBING_NEUTRAL_AXES: { label: "유동성 중립 축", unit: "/3", desc: "변화폭이 잡음 구간 안에 있어 공급·흡수 어느 쪽으로도 분류하지 않은 축입니다.", formula: "3개 축 중 중립 임계값 안에 있는 개수" },
  LIQUIDITY_PLUMBING_CONFIDENCE: { label: "유동성 데이터 충족", unit: "%", desc: "공식 현재 입력 3개 중 계산 가능한 비율. 통계적 신뢰도나 수익 확률이 아닙니다.", formula: "가용 축 ÷ 3 × 100" },
  NET_LIQUIDITY_LEVEL_TN: { label: "미국 순유동성 잔액", unit: "$T", desc: "같은 수요일의 연준 총자산에서 TGA 수요일 잔액과 ON RRP를 차감한 미국 분석 프록시. 잔액 자체보다 변화 방향을 우선합니다.", formula: "(WALCL - WDTGAL - ON RRP) ÷ 1조달러" },
  NET_LIQUIDITY_IMPULSE_4W_BN: { label: "순유동성 4주 충격", unit: "$B", desc: "양수는 공급, 음수는 흡수 방향입니다. 단독 수익 예측이 아닙니다.", formula: "현재 순유동성 - 4주 전 순유동성" },
  NET_LIQUIDITY_ACCELERATION_4W_BN: { label: "순유동성 가속도", unit: "$B", desc: "양수면 최근 공급 속도가 직전 4주보다 개선된 방향입니다.", formula: "최근 4주 충격 - 직전 4주 충격" },
  NET_LIQUIDITY_IMPULSE_STATE: { label: "순유동성 방향 상태", unit: "", desc: "+2/+1 확장, 0 혼조, -1/-2 흡수. 임계값 기반 환경 분류이며 확률이 아닙니다.", formula: "4주 충격 ±250억/±1,000억달러 구간" },
  NET_LIQUIDITY_TURN_SIGNAL: { label: "순유동성 4주 구간 전환", unit: "", desc: "+1 확장 전환, -1 흡수 전환, 0 신규 전환 없음. 최근 비중첩 4주와 직전 4주의 부호 변화이며 일별 교차시점이 아닙니다.", formula: "비중첩 4주 충격 부호 전환 + 최소 ±250억달러" },
  TGA_LIQUIDITY_CONTRIBUTION_4W_BN: { label: "TGA 4주 기여", unit: "$B", desc: "양수는 TGA 감소에 따른 현재 준비금 공급, 음수는 TGA 재충전에 따른 흡수입니다.", formula: "-(현재 TGA - 4주 전 TGA)" },
  TGA_LAGGED_ISSUANCE_CONTEXT: { label: "TGA 감소·후행 분기 거래 맥락", unit: "", desc: "1이면 현재 TGA 감소 공급과 최신 공표 분기 국채 순거래 확대가 겹칩니다. 현재 공급 상쇄나 향후 재충전·경매를 뜻하지 않습니다.", formula: "TGA 공급 ≥250억달러 AND 최신 공표 분기 순거래 방향 +1" },
  TGA_ISSUANCE_OFFSET_RISK: { label: "TGA 감소·후행 분기 거래 맥락(호환)", unit: "", desc: "이전 API 호환 별칭입니다. 현재 공급 상쇄가 아니며 신규 화면은 TGA_LAGGED_ISSUANCE_CONTEXT를 사용합니다.", formula: "TGA_LAGGED_ISSUANCE_CONTEXT 호환 별칭" },
  RRP_LIQUIDITY_CONTRIBUTION_4W_BN: { label: "ON RRP 4주 기여", unit: "$B", desc: "양수는 ON RRP 감소에 따른 준비금 공급 방향입니다. 위험자산 직접 유입과 동일하지 않습니다.", formula: "-(현재 RRP - 4주 전 RRP)" },
  RRP_BUFFER_PCT_OF_3Y_PEAK: { label: "ON RRP 잔액 비율", unit: "%", desc: "3년 고점 대비 현재 잔액. 낮을수록 잔액 기준 추가 감소 여지가 제한되지만 미래 유동성을 확정하지 않습니다.", formula: "현재 ON RRP ÷ 최근 3년 고점" },
  RRP_BUFFER_LOW: { label: "ON RRP 저잔액 경고", unit: "", desc: "1이면 잔액이 1,000억달러 이하 또는 3년 고점의 10% 이하입니다. 은행 준비금 부족 신호와는 다릅니다.", formula: "RRP≤$100B OR 3년 고점 대비≤10%" },
  US_M2_3M_ANNUALIZED: { label: "미국 M2 최근 속도", unit: "%", desc: "최근 3개월 연율화 성장률. 월간 후행 지표로서 순유동성 전환을 대체하지 않습니다.", formula: "(M2/M2[-3개월])^4-1" },
  US_M2_GROWTH_ACCELERATION: { label: "미국 M2 성장 가속", unit: "%p", desc: "최근 속도와 YoY의 차이. 양수면 최근 성장 속도가 장기 속도보다 빠릅니다.", formula: "M2 3개월 연율화 - M2 YoY" },
  LIQUIDITY_TRANSMISSION_STRESS_SCORE: { label: "유동성 전달 스트레스", unit: "/3", desc: "신용·VIX·단기자금 스트레스 합계. 2개 이상이면 공급이 위험자산으로 전달되지 않을 가능성을 경계합니다.", formula: "크레딧 경고 + VIX≥30 + SOFR-IORB≥0.10%p" },
  LIQUIDITY_TRANSMISSION_COVERAGE: { label: "전달 스트레스 데이터", unit: "%", desc: "전달 스트레스 3축 중 현재 계산 가능한 입력 비율입니다. 67% 미만은 낮은 스트레스로 해석하지 않고 적극 액션을 제한합니다.", formula: "가용 축 ÷ 3 × 100" },
  DOLLAR_LIQUIDITY_SPILLOVER_SCORE: { label: "달러→신흥국 전이", unit: "/100", desc: "미국 유동성·달러 방향·원달러·코스피 외국인 수급이 실제 한국/신흥국으로 이어지는지 확인합니다.", formula: "유동성 배관 + DXY 역방향 + USDKRW + 외국인 추세" },
  DOLLAR_LIQUIDITY_SPILLOVER_SIGNAL: { label: "신흥국 전이 신호", unit: "", desc: "+2/+1은 달러 유동성의 신흥국 전이 우호, -1/-2는 자금 회수 압력, 0은 혼조입니다.", formula: "4개 전이 축의 부호 합" },
  DOLLAR_LIQUIDITY_SPILLOVER_CONFIDENCE: { label: "전이 데이터 충족", unit: "%", desc: "전이 판단 4축 중 계산 가능한 입력 비율입니다.", formula: "가용 축 ÷ 4 × 100" },
  KOSPI_FX_FOREIGN_DIVERGENCE: { label: "외국인-환율 괴리", unit: "x", desc: "실제 외국인 20D 순매수 / 기대매도(환율상승×-3조). 2+ = 과매도 ATM화 반발 후보", formula: "actual / expected" },
  KOSPI_ATM_WARNING:           { label: "코스피 ATM 경고", unit: "", desc: "1=환율 상승 대비 외국인 매도 2배 이상 과잉 (영상5 §112 반발 조기신호)", formula: "divergence ≥ 2 AND fx↑" },
  NASDAQ_DRAWDOWN:    { label: "나스닥 낙폭",  unit: "%",   desc: "전고점 대비 하락률. -20% 이하면 약세장", formula: "(현재가 - 전고점) ÷ 전고점 × 100" },
  SOFR_EFFR_SPREAD:   { label: "SOFR-EFFR",   unit: "%",   desc: "양수면 담보금리>무담보. 자금시장 긴장 신호", formula: "SOFR - EFFR" },
  SOFR_IORB_SPREAD:   { label: "SOFR-IORB",   unit: "%",   desc: "양수=지급준비금 부족/repo 긴장. 은행간 유동성 스트레스 판별", formula: "SOFR - IORB" },
  KOSPI_SMA200:       { label: "코스피 200DMA", unit: "pt",  desc: "코스피 200일 이동평균. 장기 추세 기준선", formula: "SMA(KOSPI, 200)" },
  KOSPI_DISPARITY:    { label: "코스피 이격도", unit: "%",   desc: "코스피 200DMA 대비 괴리", formula: "(KOSPI - SMA200) / SMA200 × 100" },
  KOSPI_DRAWDOWN:     { label: "코스피 낙폭",  unit: "%",    desc: "전고점 대비 하락률", formula: "(KOSPI - ATH) / ATH × 100" },
  KRW_FX_LEVEL:       { label: "환율 레벨",    unit: "",     desc: "≤1400:+2(우호), ≤1480:+1, ≤1500:0, ≤1550:-1, >1550:-2(위험). 외국인 수급 핵심", formula: "원달러 환율 구간 점수" },
  REAL_YIELD_TREND:   { label: "실질금리 추세", unit: "",   desc: "음수=하락추세(금 우호). 최근5일 vs 15~20일전 실질금리 차이", formula: "최근5일 실질금리평균 - 15~20일전 평균" },
  DXY_TREND:          { label: "DXY 단기추세",  unit: "",    desc: "음수=달러 단기 약세(금/신흥국 우호)", formula: "DXY 최근5일평균 - 15~20일전 평균" },
  DXY_TREND_LONG:     { label: "DXY 장기추세",  unit: "",    desc: "음수=달러 구조적 약세. -2 이하면 금 장기 우호", formula: "DXY 최근10일평균 - 50~60일전 평균" },
  CHASE_NASDAQ:       { label: "나스닥 추격경고", unit: "%",  desc: "20일 수익률. +15% 이상이면 추격매수 주의", formula: "20일 수익률" },
  CHASE_GOLD:         { label: "금 추격경고",    unit: "%",   desc: "20일 수익률. +15% 이상이면 추격매수 주의", formula: "20일 수익률" },
  CHASE_KOSPI:        { label: "코스피 추격경고", unit: "%",  desc: "20일 수익률. +15% 이상이면 추격매수 주의", formula: "20일 수익률" },
  CHASE_COPPER:       { label: "구리 추격경고",   unit: "%",  desc: "20일 수익률. +15% 이상이면 추격매수 주의", formula: "20일 수익률" },
  RRP_DIRECTION:      { label: "RRP 방향",     unit: "%",  desc: "음수는 준비금 공급 방향이지만 위험자산 직접 유입을 뜻하지 않습니다.", formula: "최근 5개 평균 ÷ 직전 5개 평균 - 1" },
  TGA_DIRECTION:      { label: "TGA 수요일 잔액 방향", unit: "%", desc: "음수는 현재 준비금 공급 방향입니다. 최신 공표 분기 국채 순거래는 후행 맥락으로만 별도 표시합니다.", formula: "최근 수요일 2개 평균 ÷ 직전 수요일 2개 평균 - 1" },
  MMF_DIRECTION:      { label: "MMF 방향",     unit: "%",  desc: "MMF 자산의 최근 방향입니다. 감소분이 위험자산으로 직접 이동했다고 확정할 수 없으며 현재 3축 정렬에는 포함하지 않습니다.", formula: "최근 2개 평균 ÷ 직전 2개 평균 - 1" },
  GOLD_SMA200:        { label: "금 200DMA",     unit: "$/oz", desc: "금 200일 이동평균. 대세 상승장에서 핵심 바닥", formula: "SMA(GOLD, 200)" },
  GOLD_DISPARITY:     { label: "금 이격도",     unit: "%",   desc: "금 200DMA 대비 괴리", formula: "(GOLD - SMA200) / SMA200 × 100" },
  GOLD_FIB_382:       { label: "금 피보 0.382", unit: "$/oz", desc: "최근 60일 고저 기준 피보나치 38.2% 되돌림", formula: "HIGH-(HIGH-LOW)*0.382" },
  GOLD_FIB_500:       { label: "금 피보 0.5",   unit: "$/oz", desc: "최근 60일 고저 기준 피보나치 50% 되돌림", formula: "HIGH-(HIGH-LOW)*0.5" },
  GOLD_FIB_618:       { label: "금 피보 0.618", unit: "$/oz", desc: "최근 60일 고저 기준 피보나치 61.8% 되돌림", formula: "HIGH-(HIGH-LOW)*0.618" },
  NASDAQ_FIB_382:     { label: "나스닥 피보 0.382", unit: "pt", desc: "최근 60일 고저 기준 피보나치 38.2%", formula: "HIGH-(HIGH-LOW)*0.382" },
  NASDAQ_FIB_500:     { label: "나스닥 피보 0.5",   unit: "pt", desc: "최근 60일 고저 기준 피보나치 50%", formula: "HIGH-(HIGH-LOW)*0.5" },
  NASDAQ_FIB_618:     { label: "나스닥 피보 0.618", unit: "pt", desc: "최근 60일 고저 기준 피보나치 61.8%", formula: "HIGH-(HIGH-LOW)*0.618" },
  NASDAQ_CROSS:       { label: "골든/데드크로스", unit: "", desc: "1=골든크로스 발생, -1=데드크로스 발생, 0.5=정배열 유지, -0.5=역배열 유지", formula: "SMA50 vs SMA200 교차 감지" },
  NASDAQ_SMA50:       { label: "나스닥 50DMA",  unit: "pt",  desc: "50일 이동평균", formula: "SMA(NASDAQ, 50)" },
  OVERHEATED:         { label: "과열 감지",     unit: "",    desc: "1=과열. 이격도 +20% AND F&G 75+ 또는 이격도 +15% AND VIX <15", formula: "과열 조건 충족 여부" },
  SECTOR_XLK:         { label: "기술 섹터",     unit: "%",   desc: "XLK 20일 수익률", formula: "최근 20거래일 수익률" },
  SECTOR_XLF:         { label: "금융 섹터",     unit: "%",   desc: "XLF 20일 수익률", formula: "최근 20거래일 수익률" },
  SECTOR_XLE:         { label: "에너지 섹터",   unit: "%",   desc: "XLE 20일 수익률", formula: "최근 20거래일 수익률" },
  SECTOR_XLV:         { label: "헬스케어 섹터", unit: "%",   desc: "XLV 20일 수익률", formula: "최근 20거래일 수익률" },
  SECTOR_XLI:         { label: "산업재 섹터",   unit: "%",   desc: "XLI 20일 수익률", formula: "최근 20거래일 수익률" },
  SECTOR_XLY:         { label: "임의소비재 섹터", unit: "%", desc: "XLY 20일 수익률", formula: "최근 20거래일 수익률" },
  SECTOR_SOXX:        { label: "반도체 광역",    unit: "%",   desc: "SOXX 20일 수익률. AI·HBM·헬륨 사이클 주도 섹터 (영상2·5)", formula: "최근 20거래일 수익률" },
  SECTOR_SMH:         { label: "반도체 대형",    unit: "%",   desc: "SMH 20일 수익률. NVDA/TSM/AVGO 집중 비중, 반도체 리더십 지표", formula: "최근 20거래일 수익률" },
  SECTOR_STRONGEST:   { label: "최강 섹터",     unit: "%",   desc: "20일 수익률 기준 가장 강한 섹터", formula: "섹터별 20일 수익률 최대값" },
  KOSPI_TREND_RECOVERY: { label: "코스피 추세회복", unit: "", desc: "1=추세선 회복 (현재가>50DMA>200DMA)", formula: "현재가>50DMA>200DMA" },
  KOSPI_YEAR_RETURN:  { label: "코스피 연수익률", unit: "%", desc: "1년 수익률. 75%+ 시 역사적 조정 패턴 경고", formula: "1년 수익률" },
  KOSPI_OVERHEATED:   { label: "코스피 과열",    unit: "",   desc: "1=연간 75%+ 상승 후 조정 패턴 경고", formula: "연간 수익률 75%+" },
  KOSPI_FOREIGN_NET_1D:  { label: "외국인 당일",    unit: "억",  desc: "당일 외국인 순매수 (억원)", formula: "네이버 금융 투자자별 매매동향 당일" },
  KOSPI_FOREIGN_NET_5D:  { label: "외국인 5일",     unit: "억",  desc: "최근 5영업일 외국인 순매수 합. 양수=매수 우위", formula: "5영업일 합계" },
  KOSPI_FOREIGN_NET_20D: { label: "외국인 20일",    unit: "억",  desc: "최근 20영업일 외국인 순매수 합. 코스피 방향성 판단 핵심 축", formula: "20영업일 합계" },
  KOSPI_FOREIGN_TREND:   { label: "외국인 추세",   unit: "억",  desc: "5일평균 - 6~20일평균. 양수=매수 가속, 음수=매수 둔화/매도 전환", formula: "최근5일평균 - 그 이전 15일평균" },
  KOSPI_INSTITUTION_NET_5D: { label: "기관 5일",     unit: "억",  desc: "최근 5영업일 기관계 순매수 합", formula: "5영업일 기관계 합계" },
  KOSPI_INDIVIDUAL_NET_1D: { label: "개인 당일", unit: "억", desc: "당일 개인 순매수. 외국인 수급의 반대편 흡수 여부를 확인", formula: "네이버 금융 투자자별 매매동향 당일" },
  KOSPI_INDIVIDUAL_NET_5D: { label: "개인 5일", unit: "억", desc: "최근 5영업일 개인 순매수 합", formula: "5영업일 합계" },
  KOSPI_INDIVIDUAL_NET_20D: { label: "개인 20일", unit: "억", desc: "최근 20영업일 개인 순매수 합", formula: "20영업일 합계" },
  KOSPI_PENSION_NET_5D: { label: "연기금 5일", unit: "억", desc: "최근 5영업일 연기금등 순매수 합", formula: "5영업일 합계" },
  KRX_PENSION_FUND_FLOW: { label: "연기금 방향", unit: "", desc: "+1=5일 +1조 이상 / -1=5일 -1조 이하 / 0=중립", formula: "연기금 5D ±1조 임계" },
  KOSPI_FOREIGN_BUY_STREAK:  { label: "외국인 매수 연속",  unit: "일", desc: "최신일부터 연속 순매수 일수. 5+ 면 추세 확정", formula: "reverse count of positive days" },
  KOSPI_FOREIGN_SELL_STREAK: { label: "외국인 매도 연속",  unit: "일", desc: "연속 순매도 일수. 5+ 면 구조적 이탈 경고", formula: "reverse count of negative days" },
  KOSPI_FOREIGN_STREAK_DAYS: { label: "외국인 연속 방향", unit: "일", desc: "양수=연속 순매수, 음수=연속 순매도", formula: "매수 연속일 또는 -매도 연속일" },
  KOSPI_FOREIGN_EXTREME:     { label: "외국인 20D 극단",  unit: "",   desc: "+1=과열(+3조↑) / -1=과매도(-3조↓) / 0=중립. 영상5 45~60조 매도 맥락 선제 경고", formula: "20D 합 임계 ±3조" },
  KOSPI_FOREIGN_HISTORIC_EXTREME: { label: "외국인 역사적 극단", unit: "", desc: "+1=20일 +20조 이상 / -1=20일 -20조 이하 / 0=중립", formula: "20D 합 임계 ±20조" },
  KOSPI_FOREIGN_INDIVIDUAL_DIVERGENCE: { label: "외인-개인 괴리", unit: "", desc: "+1=개인이 외국인 3조 이상 매도를 흡수하는 경고 / -1=반대", formula: "외국인·개인 5D ±3조 교차" },
  KOSPI_FOREIGN_OVERSELL_30T_FLAG: { label: "외국인 60D 공황매도", unit: "", desc: "1=최근 60영업일 누적 -30조 이하. 반등 보장이 아닌 극단 매도 환경", formula: "60D 합 ≤ -30조" },
  FX_FOREIGN_COMBO_ALERT: { label: "환율·외인 이중게이트", unit: "", desc: "2=환율 1500+·외인 5일 매도, 1=1480+·3일 매도, -1=1480 이하·매도 연속 없음", formula: "USDKRW × 외국인 매도 연속일" },
  KRW_FX_REVERSAL_TRIGGER: { label: "환율·외인 복귀", unit: "", desc: "1=환율 1480 이하 5회 연속과 외국인 5일 연속 순매수 동시 충족", formula: "FX 안정 5회 AND 외인 매수 5일" },
  KRW_FX_GREEN:              { label: "환율 그린",       unit: "",   desc: "1=USDKRW ≤1480 (외국인 복귀 우호). 영상5 §3-1 이중 게이트", formula: "usdkrw ≤ 1480" },
  KRW_FX_RED:                { label: "환율 레드",       unit: "",   desc: "1=USDKRW ≥1500 (외국인 매도 압력 임계)", formula: "usdkrw ≥ 1500" },
  DGS30_20D_CHANGE:          { label: "30Y 20일 변화",   unit: "%p", desc: "30년 국채 20일 변화폭. +0.2p↑ + 레벨 4.8%+ 시 재정 리스크", formula: "현재 DGS30 - 20일 전" },
  FISCAL_STRESS:             { label: "재정 리스크",     unit: "",   desc: "1=채권 자경단 경고 (30년 금리 급등 + 레벨 높음). 위험자산 축소·금/현금 방어", formula: "DGS30 Δ20D + 레벨 조건" },
  FISCAL_STRESS_HARD:        { label: "재정 리스크 강",  unit: "",   desc: "1=재정 리스크 + 수익률곡선 스티프닝 동시 → 가장 강한 경고", formula: "FISCAL_STRESS + T10Y2Y > 0.1" },
  BOND_VIGILANTE_SCORE:      { label: "채권 자경단",    unit: "/4", desc: "30Y-10Y 스티프닝 + 30Y 고금리 + DXY 약세 + HY 스트레스 4축 합산", formula: "Σ 4 conditions" },
  BOND_VIGILANTE_WARNING:    { label: "자경단 경보",    unit: "",   desc: "1=4축 중 3축+ 충족. 정책 신뢰 이탈 프리커서", formula: "score ≥ 3" },
  STAGFLATION_SCORE:         { label: "스태그플레이션", unit: "/2", desc: "인플레↑ + 성장둔화 동시 = 2 (영상4 §145)", formula: "cpi_pressure ≥ 1 AND (ism < 50 OR icsa label ≤ -1)" },
  STAGFLATION_WARNING:       { label: "스태그플레 경보", unit: "",  desc: "1=스태그플레이션 2축 동시 충족", formula: "stagflation_score === 2" },
  NASDAQ_MONTHLY_EXHAUSTION:  { label: "나스닥 월봉 소진", unit: "",   desc: "1=최근 3개월 연속 장대양봉 + 아래꼬리 없음 → 과열 소진 경고 (영상5 패턴)", formula: "consecutive large bullish + no lower wick" },
  NASDAQ_WEEKLY_REVERSAL:     { label: "나스닥 주봉 반전", unit: "",   desc: "1=이전 4주 상승 추세 + 최근 장대음봉 → 추세 전환 경고", formula: "previous uptrend AND latest large bearish" },
  NASDAQ_MONTH_POS:           { label: "나스닥 월봉 위치", unit: "%",  desc: "최근 12개월 고-저 사이 종가 위치. 95+=고점근처, 5-=저점", formula: "(close - 12M low) / (12M high - 12M low)" },
  NASDAQ_MONTHLY_BODY_PCT:    { label: "나스닥 월 몸통", unit: "%",   desc: "최근 월봉 몸통 비율. 90+=마루보주, <10=도지", formula: "|close-open| / range" },
  NASDAQ_MONTHLY_LOWER_WICK_PCT: { label: "나스닥 월 아래꼬리", unit: "%", desc: "최근 월봉 아래꼬리 비율. 낮을수록 매수 검증 약함", formula: "(min(open,close)-low) / range" },
  NASDAQ_WEEKLY_BULLISH:      { label: "나스닥 주봉 양봉", unit: "",   desc: "1=최근 주봉 양봉, 0=음봉", formula: "close > open" },
  NASDAQ_AREA_INDEX:          { label: "나스닥 Area Index", unit: "%", desc: "아래꼬리/전체 비율. <10% = 매수 소화도 경고 (영상5 §106)", formula: "lowerWick / range" },
  NASDAQ_MONTHLY_PIN_BULLISH: { label: "나스닥 월 하방 핀바", unit: "", desc: "1=매수 반전 후보 (아래꼬리 길고 몸통 작음)", formula: "body<30 + 아래꼬리≥60 + 윗꼬리<10" },
  NASDAQ_MONTHLY_PIN_BEARISH: { label: "나스닥 월 상방 핀바", unit: "", desc: "1=매도 반전 후보 (윗꼬리 길고 몸통 작음)", formula: "body<30 + 윗꼬리≥60 + 아래꼬리<10" },
  NASDAQ_MONTHLY_OUTSIDE_BAR: { label: "나스닥 월 아웃사이드", unit: "", desc: "+1=상승 포섭/-1=하락 포섭/0=없음. 이전 봉 완전 감싸는 강관성 신호", formula: "cur.high>prev.high AND cur.low<prev.low" },
  NASDAQ_W_BOTTOM:            { label: "나스닥 W 반등",  unit: "",   desc: "1=W 바닥 패턴 확정 (두 저점 근접 + 5% 반등 + neckline 돌파). 영상3·5 '3차 매수' 트리거", formula: "2 local mins + peak break + rebound" },
  NASDAQ_CHANNEL_POSITION:    { label: "나스닥 채널 위치",  unit: "%", desc: "장기(15년) 월봉 회귀채널 내 위치. 0=하단(매수강도↑) / 50=중단 / 100=상단(저항)", formula: "(price - lower) / (upper - lower) × 100" },
  NASDAQ_CHANNEL_UPPER:       { label: "나스닥 채널 상단", unit: "pt", desc: "회귀선 + 1σ", formula: "mid + 1 sigma" },
  NASDAQ_CHANNEL_MID:         { label: "나스닥 채널 중간", unit: "pt", desc: "월봉 OLS 회귀선", formula: "slope×t + intercept" },
  NASDAQ_CHANNEL_LOWER:       { label: "나스닥 채널 하단", unit: "pt", desc: "회귀선 - 1σ. 영상3 §155 '하단 지지선에서 매수 강도 강해지는 경향'", formula: "mid - 1 sigma" },
  KOSPI_MONTHLY_EXHAUSTION:   { label: "코스피 월봉 소진", unit: "",   desc: "1=최근 3개월 연속 장대양봉 + 아래꼬리 없음. 영상5 코스피 과열 경고 핵심", formula: "consecutive large bullish + no lower wick" },
  KOSPI_WEEKLY_REVERSAL:      { label: "코스피 주봉 반전", unit: "",   desc: "1=이전 4주 상승 + 최근 장대음봉 → 추세 전환 경고", formula: "previous uptrend AND latest large bearish" },
  KOSPI_MONTH_POS:            { label: "코스피 월봉 위치", unit: "%",  desc: "최근 12개월 고-저 사이 종가 위치", formula: "(close - 12M low) / (12M high - 12M low)" },
  KOSPI_MONTHLY_BODY_PCT:     { label: "코스피 월 몸통",  unit: "%",   desc: "최근 월봉 몸통 비율", formula: "|close-open| / range" },
  KOSPI_MONTHLY_LOWER_WICK_PCT: { label: "코스피 월 아래꼬리", unit: "%", desc: "낮을수록 매수 검증 약함 (영상5 경고)", formula: "(min(open,close)-low) / range" },
  KOSPI_WEEKLY_BULLISH:       { label: "코스피 주봉 양봉", unit: "",   desc: "1=최근 주봉 양봉", formula: "close > open" },
  KOSPI_AREA_INDEX:           { label: "코스피 Area Index", unit: "%", desc: "아래꼬리/전체 비율. <10% = 매수 소화도 경고 (영상5 §106)", formula: "lowerWick / range" },
  KOSPI_MONTHLY_PIN_BULLISH:  { label: "코스피 월 하방 핀바", unit: "", desc: "1=매수 반전 후보", formula: "body<30 + 아래꼬리≥60 + 윗꼬리<10" },
  KOSPI_MONTHLY_PIN_BEARISH:  { label: "코스피 월 상방 핀바", unit: "", desc: "1=매도 반전 후보", formula: "body<30 + 윗꼬리≥60 + 아래꼬리<10" },
  KOSPI_MONTHLY_OUTSIDE_BAR:  { label: "코스피 월 아웃사이드", unit: "", desc: "+1=상승 포섭/-1=하락 포섭/0=없음", formula: "cur.high>prev.high AND cur.low<prev.low" },
  KOSPI_W_BOTTOM:             { label: "코스피 W 반등",  unit: "",   desc: "1=W 바닥 패턴 확정 (영상5 §101 '3차 W자 반등 저점 매수')", formula: "2 local mins + peak break + rebound" },
  GLOBAL_M2_PROXY:    { label: "미국 M2 유동성 프록시", unit: "%", desc: "현재는 미국 M2SL YoY만 사용합니다. 유로·일본 M3는 갱신 신뢰성 문제로 제외되어 글로벌 유동성 전체를 뜻하지 않습니다.", formula: "미국 M2SL 최신 / 12개월 전 - 1; -20~30% 범위 검증" },
  US_M2_YOY:          { label: "미국 M2 YoY",     unit: "%", desc: "미국 M2SL 12개월 대비 증가율", formula: "최신 / 12개월 전 - 1" },
  EURO_M3_YOY:        { label: "유로 M3 YoY",     unit: "%", desc: "유로지역 M3 12개월 대비 증가율", formula: "최신 / 12개월 전 - 1" },
  JAPAN_M3_YOY:       { label: "일본 M3 YoY",     unit: "%", desc: "일본 M3 12개월 대비 증가율", formula: "최신 / 12개월 전 - 1" },
  GOLD_FIB_ZONE:      { label: "금 피보나치 구간", unit: "", desc: "3=0.618이하(강한조정), 2=0.5~0.618, 1=0.382~0.5, 0=고점근처", formula: "현재 금 가격의 피보나치 위치" },
};


const SECTOR_QUALITY_PREFIX_LABELS: Record<string, string> = {
  SECTOR_POLICY_SUPPORT_: '정책',
  SECTOR_STRUCTURAL_DEMAND_: '구조수요',
  SECTOR_SUPPLY_TIGHTNESS_: '공급제약',
  SECTOR_QUALITY_TOTAL_: '종합품질',
};

const MARKET_STRUCTURE_SUFFIXES = [
  'STRUCTURE_SCORE',
  'DOW_TREND_STATE',
  'BEARISH_REVERSAL_STAGE',
  'STRUCTURE_CHANNEL_POSITION',
  'STRUCTURE_CHANNEL_SLOPE',
  'STRUCTURE_CHANNEL_LOWER',
  'STRUCTURE_CHANNEL_MID',
  'STRUCTURE_CHANNEL_UPPER',
  'DMA_CONVERGENCE_PCT',
  'DMA_CONVERGENCE_LEVEL',
  'SUPPORT_ZONE_LOW',
  'SUPPORT_ZONE_HIGH',
  'RESISTANCE_ZONE_LOW',
  'RESISTANCE_ZONE_HIGH',
  'RANGE_DURATION',
  'RSI_SUPPORT_CONFLUENCE',
  'PRICE_LOCATION_STATE',
  'FIB_236',
  'FIB_382',
  'FIB_500',
  'FIB_618',
  'FIB_786',
  'FIB_SWING_DIRECTION',
  'FIB_CURRENT_RETRACEMENT',
  'FIB_NEAREST_RATIO',
  'FIB_WEEKLY_CONFLUENCE',
  'FIB_SUPPORT_CONFLUENCE',
  'FIB_CONFLUENCE_SCORE',
  'FIB_LAST_DEFENSE_BROKEN',
] as const;

const MARKET_STRUCTURE_ASSET_LABELS: Record<string, string> = {
  NASDAQ: '나스닥',
  KOSPI: '코스피',
  GOLD: '금',
  BTC: '비트코인',
};

const MACD_ASSET_LABELS: Record<string, string> = {
  SP500: 'S&P 500', NASDAQ: '나스닥', KOSPI: '코스피', KOSDAQ: '코스닥',
  GOLD: '금', SILVER: '은', COPPER: '구리', WTI: 'WTI',
  DXY: '달러인덱스', USDKRW: '원/달러', USDJPY: '달러/엔',
  BTC: '비트코인', ETH: '이더리움', SOL: '솔라나', XRP: '리플', BNB: 'BNB',
  XLK: '기술', XLF: '금융', XLE: '에너지', XLV: '헬스케어', XLI: '산업재',
  XLY: '임의소비재', XLC: '커뮤니케이션', XLB: '소재', XLRE: '부동산', XLU: '유틸리티', XLP: '필수소비재',
  SOXX: '반도체', SMH: '반도체 대형', ITA: '방산·항공', GRID: '전력망', IGF: '인프라',
};

function macdAssets(derived: Record<string, DerivedPoint>) {
  return Object.keys(MACD_ASSET_LABELS).filter((asset) => derived[`${asset}_MACD_POSITION`]);
}

function macdCross(value: number | null | undefined) {
  if (value === 1) return '상방 골든';
  if (value === -1) return '하방 데드';
  return '교차 없음';
}

function macdPosition(value: number | null | undefined) {
  if (value === 1) return '시그널 위';
  if (value === -1) return '시그널 아래';
  if (value === 0) return '시그널 접점';
  return '계산 대기';
}

function macdHistogram(value: number | null | undefined) {
  if (value === 2) return '상승힘 확대';
  if (value === 1) return '하락힘 둔화';
  if (value === -1) return '상승힘 둔화';
  if (value === -2) return '하락힘 확대';
  if (value === 0) return '정체';
  return '계산 대기';
}

function macdDivergence(value: number | null | undefined, active: number | null | undefined) {
  if (active !== 1) return value === 1 ? '과거 상승 다이버전스' : value === -1 ? '과거 하락 다이버전스' : '없음';
  if (value === 1) return '상승 다이버전스 ON';
  if (value === -1) return '하락 다이버전스 ON';
  return '없음';
}

function marketStructureDerivedItems(derived: Record<string, DerivedPoint>) {
  return Object.keys(derived)
    .filter((key) => Object.keys(MARKET_STRUCTURE_ASSET_LABELS).some((asset) =>
      MARKET_STRUCTURE_SUFFIXES.some((suffix) => key === `${asset}_${suffix}`)))
    .sort();
}

function marketStructureInfo(key: string) {
  const asset = Object.keys(MARKET_STRUCTURE_ASSET_LABELS).find((item) => key.startsWith(`${item}_`)) ?? '';
  const suffix = key.slice(asset.length + 1);
  const label = MARKET_STRUCTURE_ASSET_LABELS[asset] ?? asset;
  const entries: Record<string, { label: string; unit: string; desc: string }> = {
    STRUCTURE_SCORE: { label: '가격구조 합치도', unit: '/100', desc: '다우 구조·채널 위치·이평선·지지 구간 합치도. 수익 확률이나 단독 액션이 아닙니다.' },
    DOW_TREND_STATE: { label: '다우 추세', unit: '', desc: '+1=고점·저점 상승 / 0=횡보·전환 / -1=고점·저점 하락' },
    BEARISH_REVERSAL_STAGE: { label: '추세 훼손 단계', unit: '', desc: '0=정상 / 1=모멘텀 약화 / 2=낮아진 고점·균열 / 3=이전 저점 이탈' },
    STRUCTURE_CHANNEL_POSITION: { label: '252일 채널 위치', unit: '%', desc: '0=하단 / 50=중단 / 100=상단. 상단은 추격 경계, 하단은 구조 확인 후 분할 접근 후보입니다.' },
    STRUCTURE_CHANNEL_SLOPE: { label: '채널 기울기', unit: '%', desc: '252일 로그 회귀 채널의 연환산 기울기입니다.' },
    STRUCTURE_CHANNEL_LOWER: { label: '채널 하단', unit: '', desc: '252일 로그 회귀선 - 2표준편차입니다.' },
    STRUCTURE_CHANNEL_MID: { label: '채널 중단', unit: '', desc: '252일 로그 회귀 기대값입니다.' },
    STRUCTURE_CHANNEL_UPPER: { label: '채널 상단', unit: '', desc: '252일 로그 회귀선 + 2표준편차입니다.' },
    DMA_CONVERGENCE_PCT: { label: '이평선 수렴폭', unit: '%', desc: '20·50·100·200일선의 최대-최소 간격입니다. 작을수록 방향 선택 직전일 수 있습니다.' },
    DMA_CONVERGENCE_LEVEL: { label: '이평선 수렴', unit: '', desc: '1=주요 이동평균선 간격 4% 이하. 방향 예측이 아니라 변동성 압축 신호입니다.' },
    SUPPORT_ZONE_LOW: { label: '지지구간 하단', unit: '', desc: '반복 종가 피벗을 군집화한 가까운 지지 구간 하단입니다.' },
    SUPPORT_ZONE_HIGH: { label: '지지구간 상단', unit: '', desc: '반복 종가 피벗을 군집화한 가까운 지지 구간 상단입니다.' },
    RESISTANCE_ZONE_LOW: { label: '저항구간 하단', unit: '', desc: '반복 종가 피벗을 군집화한 가까운 저항 구간 하단입니다.' },
    RESISTANCE_ZONE_HIGH: { label: '저항구간 상단', unit: '', desc: '반복 종가 피벗을 군집화한 가까운 저항 구간 상단입니다.' },
    RANGE_DURATION: { label: '시간 조정', unit: '일', desc: '20~120거래일 중 현재 범위 안에 머문 최장 기간입니다.' },
    RSI_SUPPORT_CONFLUENCE: { label: 'RSI·지지 합치', unit: '', desc: '1=RSI≤35와 지지/채널 하단이 동시 충족. 거래량 확인 전에는 매수 신호가 아닙니다.' },
    PRICE_LOCATION_STATE: { label: '현재 가격 위치', unit: '', desc: '-2=지지 이탈 / -1=지지·채널 하단 / 0=중단 / +1=채널 상단' },
    FIB_236: { label: '피보나치 0.236', unit: '', desc: '최근 명확한 주요 파동의 얕은 되돌림 구간입니다. 단독 매수선이 아닙니다.' },
    FIB_382: { label: '피보나치 0.382', unit: '', desc: '최근 명확한 주요 파동의 38.2% 되돌림 구간입니다.' },
    FIB_500: { label: '피보나치 0.500', unit: '', desc: '최근 명확한 주요 파동의 절반 되돌림 구간입니다.' },
    FIB_618: { label: '피보나치 0.618', unit: '', desc: '깊은 조정에서 자주 관찰하는 61.8% 되돌림 구간입니다.' },
    FIB_786: { label: '피보나치 0.786', unit: '', desc: '선택한 주요 파동의 추세 훼손 경계 후보입니다. 통계적으로 보장된 방어선은 아닙니다.' },
    FIB_SWING_DIRECTION: { label: '피보 기준 파동', unit: '', desc: '+1=주요 저점→고점 상승 파동, -1=주요 고점→저점 하락 파동입니다.' },
    FIB_CURRENT_RETRACEMENT: { label: '현재 되돌림', unit: '%', desc: '선택된 주요 파동 대비 현재 가격의 되돌림 비율입니다. 100%를 넘으면 원 파동이 완전히 훼손된 상태입니다.' },
    FIB_NEAREST_RATIO: { label: '가장 가까운 피보', unit: '', desc: '현재 가격과 가장 가까운 표준 비율(0.236/0.382/0.5/0.618/0.786)입니다.' },
    FIB_WEEKLY_CONFLUENCE: { label: '주봉 피보 합치', unit: '', desc: '같은 원시 시세를 주봉으로 별도 집계해 선택한 주요 파동의 피보 구간이 2.5% 안에서 겹치는지 표시합니다.' },
    FIB_SUPPORT_CONFLUENCE: { label: '지지·저항 합치', unit: '', desc: '피보 구간과 반복 가격 피벗으로 계산한 지지·저항 구간이 2.5% 안에서 겹치는지 표시합니다.' },
    FIB_CONFLUENCE_SCORE: { label: '피보 교차 합치도', unit: '/100', desc: '주봉 별도 집계·독립 피벗 지지/저항·회귀채널이 겹칠 때만 점수를 부여합니다. 단독 피보는 0점이며 수익 확률이 아닙니다.' },
    FIB_LAST_DEFENSE_BROKEN: { label: '0.786 기준 이탈', unit: '', desc: '선택 파동의 0.786 구간을 2% 완충폭까지 넘었는지 표시합니다. 이것만으로 추세 훼손을 확정하지 않습니다.' },
  };
  const info = entries[suffix] ?? { label: suffix, unit: '', desc: 'Spring 네이티브 가격 구조 지표' };
  return { ...info, label: `${label} ${info.label}` };
}

function formatMarketStructureValue(key: string, value: number | null | undefined, unit: string) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—';
  if (key.endsWith('_DOW_TREND_STATE')) return value > 0 ? '상승 구조' : value < 0 ? '하락 구조' : '횡보·전환';
  if (key.endsWith('_BEARISH_REVERSAL_STAGE')) return ['훼손 없음', '1단계 약화', '2단계 균열', '3단계 저점 이탈'][Math.max(0, Math.min(3, Math.round(value)))] ?? '—';
  if (key.endsWith('_PRICE_LOCATION_STATE')) return value <= -2 ? '지지 이탈' : value < 0 ? '지지·하단' : value > 0 ? '채널 상단' : '채널 중단';
  if (key.endsWith('_FIB_SWING_DIRECTION')) return value > 0 ? '저점→고점' : '고점→저점';
  if (key.endsWith('_FIB_NEAREST_RATIO')) return value.toFixed(3);
  if (
    key.endsWith('_DMA_CONVERGENCE_LEVEL')
    || key.endsWith('_RSI_SUPPORT_CONFLUENCE')
    || key.endsWith('_FIB_WEEKLY_CONFLUENCE')
    || key.endsWith('_FIB_SUPPORT_CONFLUENCE')
    || key.endsWith('_FIB_LAST_DEFENSE_BROKEN')
  ) return value >= 1 ? 'ON' : 'OFF';
  return formatValue(value, unit);
}

function sectorQualityDerivedItems(derived: Record<string, DerivedPoint>) {
  return Object.keys(derived)
    .filter((key) => Object.keys(SECTOR_QUALITY_PREFIX_LABELS).some((prefix) => key.startsWith(prefix)))
    .sort();
}

function sectorQualityLabel(key: string): string {
  const prefix = Object.keys(SECTOR_QUALITY_PREFIX_LABELS).find((item) => key.startsWith(item));
  if (!prefix) return key;
  const suffix = key.replace(prefix, '');
  return `${suffix} ${SECTOR_QUALITY_PREFIX_LABELS[prefix]}`;
}

function formatValue(val: number | null | undefined, unit: string): string {
  if (typeof val !== "number" || !Number.isFinite(val)) return "—";
  if (unit === "$M" || unit === "$B") {
    if (val >= 1_000_000) return `${(val / 1_000_000).toFixed(2)}T`;
    if (val >= 1_000) return `${(val / 1_000).toFixed(1)}B`;
    return `${val.toFixed(1)}${unit === "$M" ? "M" : "B"}`;
  }
  if (unit === "건") return val.toLocaleString("ko-KR");
  if (Math.abs(val) >= 10000) return val.toLocaleString("ko-KR", { maximumFractionDigits: 0 });
  if (Math.abs(val) >= 100) return val.toFixed(1);
  if (Math.abs(val) >= 1) return val.toFixed(2);
  return val.toFixed(4);
}

interface Props {
  raw: Record<string, DataPoint>;
  derived: Record<string, DerivedPoint>;
}

export function IndicatorPanel({ raw, derived }: Props) {
  const fredKeys = Object.keys(FRED_LABELS).filter((k) => raw[k]);
  const yahooKeys = Object.keys(YAHOO_LABELS).filter((k) => raw[k]);
  const sectorQualityKeys = sectorQualityDerivedItems(derived);
  const marketStructureKeys = marketStructureDerivedItems(derived);
  const marketStructureKeySet = new Set(marketStructureKeys);
  const macdAssetKeys = macdAssets(derived);
  const derivedKeys = Object.keys(DERIVED_LABELS)
    .filter((k) => derived[k] && !marketStructureKeySet.has(k));

  return (
    <div className="space-y-4 sm:space-y-6">
      {yahooKeys.length > 0 && (
        <Section title="시장 가격">
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-2 sm:gap-3">
            {yahooKeys.map((key) => {
              const info = YAHOO_LABELS[key];
              const dp = raw[key];
              return (
                <Card
                  key={key}
                  label={info.label}
                  value={formatValue(dp.value, info.unit)}
                  date={dp.date}
                  desc={info.desc}
                  freq="일간"
                  source="Yahoo"
                  excluded={dp.eligibleForSignals === false}
                />
              );
            })}
          </div>
        </Section>
      )}

      {fredKeys.length > 0 && (
        <Section title="거시·유동성·고용">
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-2 sm:gap-3">
            {fredKeys.map((key) => {
              const info = FRED_LABELS[key];
              const dp = raw[key];
              return (
                <Card
                  key={key}
                  label={info.label}
                  value={formatValue(dp.value, info.unit)}
                  date={dp.date}
                  desc={info.desc}
                  freq={info.freq}
                  source="FRED"
                  excluded={dp.eligibleForSignals === false}
                />
              );
            })}
          </div>
        </Section>
      )}

      {derivedKeys.length > 0 && (
        <Section title="파생 지표">
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-2 sm:gap-3">
            {derivedKeys.map((key) => {
              const info = DERIVED_LABELS[key];
              const dp = derived[key];
              return (
                <Card
                  key={key}
                  label={info.label}
                  value={formatValue(dp.value, info.unit)}
                  date={dp.date}
                  desc={`${info.desc}\n\n산식: ${info.formula}`}
                  freq="계산"
                  source="자체"
                  excluded={dp.eligibleForSignals === false}
                />
              );
            })}
          </div>
        </Section>
      )}

      {sectorQualityKeys.length > 0 && (
        <Section title="섹터 품질 파생">
          <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-2 sm:gap-3">
            {sectorQualityKeys.map((key) => {
              const dp = derived[key];
              return (
                <Card
                  key={key}
                  label={sectorQualityLabel(key)}
                  value={formatValue(dp.value, '')}
                  date={dp.date}
                  desc={dp.formula}
                  freq="계산"
                  source="자체"
                  excluded={dp.eligibleForSignals === false}
                />
              );
            })}
          </div>
        </Section>
      )}

      {macdAssetKeys.length > 0 && (
        <Section title="MACD 교차·다이버전스">
          <div className="mb-3 rounded-xl border border-violet-500/15 bg-violet-500/5 px-3 py-2 text-[11px] leading-relaxed text-violet-50/80">
            MACD(12·26·9) 일봉과 주봉을 함께 표시합니다. ‘상방 골든/하방 데드’는 MACD-시그널선 교차이며 50·200일선 교차와 다릅니다. 피벗은 우측 확인 후에만 다이버전스로 확정하며 단독 매매 신호가 아닙니다.
          </div>
          <div className="space-y-2">
            {macdAssetKeys.map((asset) => {
              const daily = `${asset}_MACD_`;
              const weekly = `${asset}_WEEKLY_MACD_`;
              const date = derived[`${daily}POSITION`]?.date ?? '';
              const dailyPosition = derived[`${daily}POSITION`]?.value;
              const weeklyPosition = derived[`${weekly}POSITION`]?.value;
              const dailyCross = derived[`${daily}CROSS`]?.value;
              const weeklyCross = derived[`${weekly}CROSS`]?.value;
              const dailyDivergence = derived[`${daily}DIVERGENCE`]?.value;
              const dailyDivergenceActive = derived[`${daily}DIVERGENCE_ACTIVE`]?.value;
              const positive = dailyPosition === 1 && weeklyPosition === 1;
              const negative = dailyPosition === -1 && weeklyPosition === -1;
              return (
                <div key={asset} className={`grid grid-cols-1 gap-2 rounded-xl border p-3 text-xs md:grid-cols-[120px_1fr_1fr] ${positive ? 'border-emerald-500/20 bg-emerald-500/5' : negative ? 'border-rose-500/20 bg-rose-500/5' : 'border-white/10 bg-black/15'}`}>
                  <div>
                    <div className="font-semibold text-white">{MACD_ASSET_LABELS[asset]}</div>
                    <div className="mt-1 text-[10px] text-[var(--muted)]">{date}</div>
                  </div>
                  <div className="rounded-lg border border-white/10 bg-black/15 p-2">
                    <div className="text-[10px] text-white/50">일봉</div>
                    <div className="mt-1 flex flex-wrap gap-1.5">
                      <span>{macdPosition(dailyPosition)}</span><span>· {macdCross(dailyCross)}</span>
                      {derived[`${daily}CROSS_AGE`]?.value !== null && derived[`${daily}CROSS_AGE`]?.value !== undefined ? <span>({derived[`${daily}CROSS_AGE`].value}일 전)</span> : null}
                      <span>· {macdHistogram(derived[`${daily}HISTOGRAM_STATE`]?.value)}</span>
                      <span>· {macdDivergence(dailyDivergence, dailyDivergenceActive)}</span>
                    </div>
                  </div>
                  <div className="rounded-lg border border-white/10 bg-black/15 p-2">
                    <div className="text-[10px] text-white/50">주봉 {derived[`${asset}_MACD_CURRENT_WEEK_PROVISIONAL`]?.value === 1 ? '· 진행 중' : ''}</div>
                    <div className="mt-1 flex flex-wrap gap-1.5">
                      <span>{macdPosition(weeklyPosition)}</span><span>· {macdCross(weeklyCross)}</span>
                      {derived[`${weekly}CROSS_AGE`]?.value !== null && derived[`${weekly}CROSS_AGE`]?.value !== undefined ? <span>({derived[`${weekly}CROSS_AGE`].value}주 전)</span> : null}
                      <span>· {macdHistogram(derived[`${weekly}HISTOGRAM_STATE`]?.value)}</span>
                      <span>· {macdDivergence(derived[`${weekly}DIVERGENCE`]?.value, derived[`${weekly}DIVERGENCE_ACTIVE`]?.value)}</span>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        </Section>
      )}

      {marketStructureKeys.length > 0 && (
        <Section title="가격 구조·시장 심리">
          <div className="mb-3 rounded-xl border border-cyan-500/15 bg-cyan-500/5 px-3 py-2 text-[11px] leading-relaxed text-cyan-50/80">
            지지·저항과 피보나치는 한 줄이 아닌 반응 후보 구간입니다. 주봉·반복 가격대·회귀채널이 겹칠수록 신뢰를 높이되, 지수/자산 지표에는 거래량 축이 없으므로 단독 매수 신호로 사용하지 않습니다.
          </div>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 sm:gap-3 lg:grid-cols-4 xl:grid-cols-5">
            {marketStructureKeys.map((key) => {
              const dp = derived[key];
              const info = marketStructureInfo(key);
              return (
                <Card
                  key={key}
                  label={info.label}
                  value={formatMarketStructureValue(key, dp.value, info.unit)}
                  date={dp.date}
                  desc={`${info.desc}\n\n산식: ${dp.formula}`}
                  freq="일간 계산"
                  source="Spring"
                  excluded={dp.eligibleForSignals === false}
                />
              );
            })}
          </div>
        </Section>
      )}
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-3 sm:p-5">
      <h3 className="text-base sm:text-lg font-semibold mb-3 sm:mb-4">{title}</h3>
      {children}
    </div>
  );
}

function Card({
  label, value, date, desc, freq, source, excluded,
}: {
  label: string; value: string; date: string;
  desc: string; freq: string; source: string; excluded?: boolean;
}) {
  return (
    <div className="rounded-lg bg-[var(--background)] border border-[var(--card-border)] p-2.5 sm:p-3">
      <div className="flex items-center text-[10px] sm:text-xs text-[var(--muted)] mb-1">
        <span className="truncate">{label}</span>
        <InfoTooltip title={label} description={desc} frequency={freq} source={source} />
      </div>
      <div className="text-base sm:text-lg font-mono font-bold leading-tight">{value}</div>
      <div className="flex items-center gap-1 text-[9px] sm:text-[10px] text-[var(--muted)] mt-1">
        <span>{date}</span>
        {excluded && <span className="rounded bg-red-500/15 px-1 py-0.5 text-red-300">신선도 만료 · 산식 제외</span>}
      </div>
    </div>
  );
}
