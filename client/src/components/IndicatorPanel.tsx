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
  NASDAQ_DRAWDOWN:    { label: "나스닥 낙폭",  unit: "%",   desc: "전고점 대비 하락률. -20% 이하면 약세장", formula: "(현재가 - 전고점) ÷ 전고점 × 100" },
  SOFR_EFFR_SPREAD:   { label: "SOFR-EFFR",   unit: "%",   desc: "양수면 담보금리>무담보. 자금시장 긴장 신호", formula: "SOFR - EFFR" },
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
