"use client";

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

const FRED_LABELS: Record<string, { label: string; unit: string; desc: string }> = {
  DGS10:        { label: "10Y 금리",       unit: "%",    desc: "미국 장기금리 기준" },
  T10YIE:       { label: "기대인플레(BEI)", unit: "%",    desc: "10년 기대인플레이션" },
  T10Y2Y:       { label: "장단기 금리차",   unit: "%",    desc: "10Y-2Y 스프레드" },
  VIXCLS:       { label: "VIX",            unit: "pt",   desc: "변동성 공포지수" },
  BAMLH0A0HYM2: { label: "하이일드 스프레드", unit: "%",  desc: "신용시장 스트레스" },
  STLFSI4:      { label: "금융스트레스",    unit: "σ",    desc: "세인트루이스 STLFSI" },
  WALCL:        { label: "연준 총자산",     unit: "$M",   desc: "연준 대차대조표" },
  WRESBAL:      { label: "지급준비금",      unit: "$M",   desc: "은행 준비금 잔액" },
  RRPONTSYD:    { label: "역레포(RRP)",    unit: "$B",   desc: "연준 초과유동성 흡수" },
  WTREGEN:      { label: "TGA 잔액",       unit: "$M",   desc: "재무부 일반계정" },
  WRMFNS:       { label: "MMF 잔액",       unit: "$B",   desc: "머니마켓펀드" },
  M2SL:         { label: "M2 통화량",      unit: "$B",   desc: "광의 통화량" },
  UNRATE:       { label: "실업률",         unit: "%",    desc: "미국 실업률" },
  ICSA:         { label: "신규실업수당",    unit: "건",   desc: "주간 청구건수" },
  SOFR:         { label: "SOFR",           unit: "%",    desc: "담보부 금리" },
  EFFR:         { label: "EFFR",           unit: "%",    desc: "무담보 금리" },
};

const YAHOO_LABELS: Record<string, { label: string; unit: string }> = {
  SP500:    { label: "S&P 500",    unit: "pt" },
  NASDAQ:   { label: "나스닥",     unit: "pt" },
  KOSPI:    { label: "코스피",     unit: "pt" },
  GOLD:     { label: "금",         unit: "$/oz" },
  SILVER:   { label: "은",         unit: "$/oz" },
  COPPER:   { label: "구리",       unit: "$/lb" },
  WTI:      { label: "WTI 원유",   unit: "$/bbl" },
  BTC:      { label: "비트코인",   unit: "$" },
  DXY:      { label: "달러인덱스", unit: "pt" },
  USDKRW:   { label: "원/달러",    unit: "₩" },
  USDJPY:   { label: "달러/엔",    unit: "¥" },
};

const DERIVED_LABELS: Record<string, { label: string; unit: string; desc: string }> = {
  REAL_YIELD:         { label: "실질금리",     unit: "%",    desc: "DGS10 - BEI" },
  GOLD_SILVER_RATIO:  { label: "금은비",       unit: "",     desc: "금 ÷ 은" },
  COPPER_GOLD_RATIO:  { label: "구리금비",     unit: "",     desc: "구리 ÷ 금" },
  NASDAQ_SMA200:      { label: "나스닥 200DMA", unit: "pt",  desc: "200일 이동평균" },
  NASDAQ_DISPARITY:   { label: "나스닥 이격도", unit: "%",   desc: "200DMA 대비" },
  NASDAQ_DRAWDOWN:    { label: "나스닥 낙폭",  unit: "%",    desc: "전고점 대비" },
  SOFR_EFFR_SPREAD:   { label: "SOFR-EFFR",   unit: "%",    desc: "자금시장 스프레드" },
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
    <div className="space-y-6">
      {yahooKeys.length > 0 && (
        <Section title="시장 가격">
          <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3">
            {yahooKeys.map((key) => {
              const info = YAHOO_LABELS[key];
              const dp = raw[key];
              return (
                <Card key={key} label={info.label} value={formatValue(dp.value, info.unit)} unit={info.unit} date={dp.date} />
              );
            })}
          </div>
        </Section>
      )}

      {fredKeys.length > 0 && (
        <Section title="거시·유동성·고용">
          <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3">
            {fredKeys.map((key) => {
              const info = FRED_LABELS[key];
              const dp = raw[key];
              return (
                <Card key={key} label={info.label} value={formatValue(dp.value, info.unit)} unit={info.unit} date={dp.date} desc={info.desc} />
              );
            })}
          </div>
        </Section>
      )}

      {derivedKeys.length > 0 && (
        <Section title="파생 지표">
          <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-3">
            {derivedKeys.map((key) => {
              const info = DERIVED_LABELS[key];
              const dp = derived[key];
              return (
                <Card key={key} label={info.label} value={formatValue(dp.value, info.unit)} unit={info.unit} date={dp.date} desc={info.desc} />
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
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-5">
      <h3 className="text-lg font-semibold mb-4">{title}</h3>
      {children}
    </div>
  );
}

function Card({ label, value, unit, date, desc }: { label: string; value: string; unit: string; date: string; desc?: string }) {
  return (
    <div className="rounded-lg bg-[var(--background)] border border-[var(--card-border)] p-3">
      <div className="text-xs text-[var(--muted)] mb-1 truncate" title={desc}>{label}</div>
      <div className="text-lg font-mono font-bold leading-tight">{value}</div>
      <div className="text-[10px] text-[var(--muted)] mt-1">{date}</div>
    </div>
  );
}
