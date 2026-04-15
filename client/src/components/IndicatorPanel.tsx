"use client";

import { InfoTooltip } from "./InfoTooltip";

interface DataPoint {
  code: string;
  value: number;
  date: string;
  source: string;
}

interface DerivedPoint {
  name: string;
  value: number;
  date: string;
  formula: string;
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
  RRPONTSYD:    { label: "역레포(RRP)",    unit: "$B",   desc: "연준의 초과유동성 흡수 창구. 감소하면 시장으로 유동성 유입", freq: "일간" },
  WTREGEN:      { label: "TGA 잔액",       unit: "$M",   desc: "재무부 일반계정. 감소하면 시중에 유동성 공급", freq: "주간" },
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
  LIQUIDITY_DIRECTION:         { label: "유동성 방향", unit: "", desc: "RRP/TGA/MMF 감소 + M2 YoY 양수 합산. -5~+5. 영상4 §120 '총량 아닌 방향'", formula: "각 지표 방향 부호 합" },
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
  RRP_DIRECTION:      { label: "RRP 방향",     unit: "$B",  desc: "음수=RRP 감소→시장 유동성 유입", formula: "최근 RRP - 10일전 RRP" },
  TGA_DIRECTION:      { label: "TGA 방향",     unit: "$M",  desc: "음수=TGA 감소→시중 유동성 공급", formula: "최근 TGA - 이전주 TGA" },
  MMF_DIRECTION:      { label: "MMF 방향",     unit: "$B",  desc: "음수=MMF 감소→위험자산으로 자금 이동", formula: "최근 MMF - 이전주 MMF" },
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
  KOSPI_FOREIGN_BUY_STREAK:  { label: "외국인 매수 연속",  unit: "일", desc: "최신일부터 연속 순매수 일수. 5+ 면 추세 확정", formula: "reverse count of positive days" },
  KOSPI_FOREIGN_SELL_STREAK: { label: "외국인 매도 연속",  unit: "일", desc: "연속 순매도 일수. 5+ 면 구조적 이탈 경고", formula: "reverse count of negative days" },
  KOSPI_FOREIGN_EXTREME:     { label: "외국인 20D 극단",  unit: "",   desc: "+1=과열(+3조↑) / -1=과매도(-3조↓) / 0=중립. 영상5 45~60조 매도 맥락 선제 경고", formula: "20D 합 임계 ±3조" },
  KRW_FX_GREEN:              { label: "환율 그린",       unit: "",   desc: "1=USDKRW ≤1480 (외국인 복귀 우호). 영상5 §3-1 이중 게이트", formula: "usdkrw ≤ 1480" },
  KRW_FX_RED:                { label: "환율 레드",       unit: "",   desc: "1=USDKRW ≥1500 (외국인 매도 압력 임계)", formula: "usdkrw ≥ 1500" },
  DGS30_20D_CHANGE:          { label: "30Y 20일 변화",   unit: "%p", desc: "30년 국채 20일 변화폭. +0.2p↑ + 레벨 4.5%+ 시 재정 리스크", formula: "현재 DGS30 - 20일 전" },
  FISCAL_STRESS:             { label: "재정 리스크",     unit: "",   desc: "1=채권 자경단 경고 (30년 금리 급등 + 레벨 높음). 위험자산 축소·금/현금 방어", formula: "DGS30 Δ20D + 레벨 조건" },
  FISCAL_STRESS_HARD:        { label: "재정 리스크 강",  unit: "",   desc: "1=재정 리스크 + 수익률곡선 스티프닝 동시 → 가장 강한 경고", formula: "FISCAL_STRESS + T10Y2Y > 0.1" },
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
  GLOBAL_M2_PROXY:    { label: "글로벌 M2 프록시", unit: "%", desc: "미국 M2 + 유로 M3 + 일본 M3 YoY% 평균. 양수=글로벌 유동성 확장. -20~30% 밖 이상치는 제외", formula: "3개 시리즈 각 (최신 / 12개월전 - 1), 범위 밖 제외 후 평균" },
  US_M2_YOY:          { label: "미국 M2 YoY",     unit: "%", desc: "미국 M2SL 12개월 대비 증가율", formula: "최신 / 12개월 전 - 1" },
  EURO_M3_YOY:        { label: "유로 M3 YoY",     unit: "%", desc: "유로지역 M3 12개월 대비 증가율", formula: "최신 / 12개월 전 - 1" },
  JAPAN_M3_YOY:       { label: "일본 M3 YoY",     unit: "%", desc: "일본 M3 12개월 대비 증가율", formula: "최신 / 12개월 전 - 1" },
  GOLD_FIB_ZONE:      { label: "금 피보나치 구간", unit: "", desc: "3=0.618이하(강한조정), 2=0.5~0.618, 1=0.382~0.5, 0=고점근처", formula: "현재 금 가격의 피보나치 위치" },
};

function formatValue(val: number, unit: string): string {
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
  const derivedKeys = Object.keys(DERIVED_LABELS).filter((k) => derived[k]);

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
  label, value, date, desc, freq, source,
}: {
  label: string; value: string; date: string;
  desc: string; freq: string; source: string;
}) {
  return (
    <div className="rounded-lg bg-[var(--background)] border border-[var(--card-border)] p-2.5 sm:p-3">
      <div className="flex items-center text-[10px] sm:text-xs text-[var(--muted)] mb-1">
        <span className="truncate">{label}</span>
        <InfoTooltip title={label} description={desc} frequency={freq} source={source} />
      </div>
      <div className="text-base sm:text-lg font-mono font-bold leading-tight">{value}</div>
      <div className="text-[9px] sm:text-[10px] text-[var(--muted)] mt-1">{date}</div>
    </div>
  );
}
