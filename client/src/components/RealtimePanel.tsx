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

const REALTIME_ITEMS: Array<{
  key: string;
  type: "raw" | "derived";
  label: string;
  unit: string;
  desc: string;
  format?: (v: number) => string;
}> = [
  { key: "VIXCLS", type: "raw", label: "VIX", unit: "pt", desc: "S&P500 변동성 공포지수. 20↓ 안정, 30↑ 경계, 35↑ 공포" },
  { key: "FEAR_GREED", type: "raw", label: "Fear & Greed", unit: "pt", desc: "CNN 공포탐욕지수. 0=극단공포, 100=극단탐욕" },

  { key: "KOSPI", type: "raw", label: "코스피", unit: "pt", desc: "한국 종합주가지수", format: (v) => v.toLocaleString("ko-KR", { maximumFractionDigits: 0 }) },
  { key: "GOLD", type: "raw", label: "금", unit: "$/oz", desc: "금 선물 가격" },
  { key: "SILVER", type: "raw", label: "은", unit: "$/oz", desc: "은 선물 가격" },
  { key: "COPPER", type: "raw", label: "구리", unit: "$/lb", desc: "구리 선물 가격" },
  { key: "WTI", type: "raw", label: "WTI 유가", unit: "$/bbl", desc: "WTI 원유 선물" },
  { key: "DXY", type: "raw", label: "달러 인덱스", unit: "pt", desc: "달러 강약 지수" },
  { key: "USDKRW", type: "raw", label: "원/달러", unit: "₩", desc: "원달러 환율", format: (v) => v.toFixed(1) },
  { key: "DGS10", type: "raw", label: "10Y 금리", unit: "%", desc: "미국 10년물 국채 금리" },
  { key: "T10Y2Y", type: "raw", label: "장단기 금리차", unit: "%", desc: "10Y-2Y 스프레드" },
  { key: "REAL_YIELD", type: "derived", label: "실질금리", unit: "%", desc: "10Y 금리 - 기대인플레" },
  { key: "GOLD_SILVER_RATIO", type: "derived", label: "금은비", unit: "", desc: "금÷은. 80 이상이면 은 저평가" },
  { key: "NASDAQ_DISPARITY", type: "derived", label: "나스닥 이격도", unit: "%", desc: "200DMA 대비 괴리율" },
  { key: "KOSPI_DISPARITY", type: "derived", label: "코스피 이격도", unit: "%", desc: "200DMA 대비 괴리율" },
  { key: "KRW_FX_LEVEL", type: "derived", label: "환율 레벨", unit: "", desc: "≤1400:+2, ≤1480:+1, ≤1500:0, ≤1550:-1, >1550:-2" },
];

function defaultFormat(v: number, unit: string): string {
  if (Math.abs(v) >= 10000) return v.toLocaleString("ko-KR", { maximumFractionDigits: 0 });
  if (Math.abs(v) >= 100) return v.toFixed(1);
  if (Math.abs(v) >= 1) return v.toFixed(2);
  return v.toFixed(4);
}

interface Props {
  raw: Record<string, DataPoint>;
  derived: Record<string, DerivedPoint>;
  timestamp: string;
  usPriceSource?: "spot" | "futures";
}

const SPOT_FUTURES_PAIRS = [
  { spotKey: "NASDAQ_SPOT", futuresKey: "NQ_FUTURES", activeKey: "NASDAQ", label: "나스닥", spotLabel: "현물 (^IXIC)", futuresLabel: "선물 (NQ=F)" },
  { spotKey: "SP500_SPOT", futuresKey: "ES_FUTURES", activeKey: "SP500", label: "S&P 500", spotLabel: "현물 (^GSPC)", futuresLabel: "선물 (ES=F)" },
];

function FuturesSpotBlock({ raw, usPriceSource }: { raw: Record<string, DataPoint>; usPriceSource?: string }) {
  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 mb-2">
      {SPOT_FUTURES_PAIRS.map(({ spotKey, futuresKey, label, spotLabel, futuresLabel }) => {
        const spot = raw[spotKey];
        const futures = raw[futuresKey];
        const isSpotActive = usPriceSource === "spot";
        const isFuturesActive = usPriceSource === "futures";

        return (
          <div key={label} className="rounded-lg bg-[var(--background)] border border-[var(--card-border)] p-3">
            <div className="text-xs sm:text-sm font-semibold mb-2">{label}</div>
            <div className="grid grid-cols-2 gap-2">
              <div className={`rounded p-2 ${isSpotActive ? "bg-blue-500/10 border border-blue-500/30" : "bg-neutral-800"}`}>
                <div className="flex items-center gap-1 mb-1">
                  <span className="text-[9px] sm:text-[10px] text-[var(--muted)]">{spotLabel}</span>
                  {isSpotActive && <span className="text-[8px] px-1 py-0.5 rounded bg-blue-500/20 text-blue-400">신호 기준</span>}
                </div>
                <div className="text-sm sm:text-base font-mono font-bold">
                  {typeof spot?.value === "number" ? spot.value.toLocaleString("ko-KR", { maximumFractionDigits: 0 }) : "-"}
                </div>
              </div>
              <div className={`rounded p-2 ${isFuturesActive ? "bg-blue-500/10 border border-blue-500/30" : "bg-neutral-800"}`}>
                <div className="flex items-center gap-1 mb-1">
                  <span className="text-[9px] sm:text-[10px] text-[var(--muted)]">{futuresLabel}</span>
                  {isFuturesActive && <span className="text-[8px] px-1 py-0.5 rounded bg-blue-500/20 text-blue-400">신호 기준</span>}
                </div>
                <div className="text-sm sm:text-base font-mono font-bold">
                  {typeof futures?.value === "number" ? futures.value.toLocaleString("ko-KR", { maximumFractionDigits: 0 }) : "-"}
                </div>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

export function RealtimePanel({ raw, derived, timestamp, usPriceSource }: Props) {
  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <div className="flex items-center justify-between mb-3">
        <div>
          <h3 className="text-base sm:text-lg font-semibold">실시간 수집 지표</h3>
          <p className="text-[11px] sm:text-xs text-[var(--muted)]">5분마다 자동 갱신. 시장 상태를 즉시 반영.</p>
        </div>
        <div className="flex items-center gap-1.5 shrink-0">
          <span className="w-2 h-2 rounded-full bg-green-500 animate-pulse" />
          <span className="text-[10px] sm:text-xs text-[var(--muted)]">LIVE</span>
        </div>
      </div>

      <FuturesSpotBlock raw={raw} usPriceSource={usPriceSource} />

      <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-2">
        {REALTIME_ITEMS.map((item) => {
          const dp = item.type === "raw" ? raw[item.key] : null;
          const ddp = item.type === "derived" ? derived[item.key] : null;
          const value = dp?.value ?? ddp?.value ?? null;
          if (value === null) return null;

          const formatted = item.format ? item.format(value) : defaultFormat(value, item.unit);

          return (
            <div key={item.key} className="rounded-lg bg-[var(--background)] border border-[var(--card-border)] p-2.5">
              <div className="flex items-center text-[10px] sm:text-xs text-[var(--muted)] mb-1">
                <span className="truncate">{item.label}</span>
                <InfoTooltip title={item.label} description={item.desc} frequency="5분" source={item.type === "raw" ? (dp?.source || "") : "자체 계산"} />
              </div>
              <div className="text-base sm:text-lg font-mono font-bold leading-tight">{formatted}</div>
              <div className="text-[9px] text-[var(--muted)] mt-0.5">{item.unit}</div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
