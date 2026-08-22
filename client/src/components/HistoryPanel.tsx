"use client";

import { useEffect, useMemo, useRef, useState } from "react";

type RangeKey = "1D" | "1W" | "1M" | "1Y" | "5Y";
type IntervalKey = "1D" | "1W" | "1M";
type HistoryPoint = { date: string; value: number };
type SeriesDef = {
  queryKey: string;
  label: string;
  color: string;
  group: "macro" | "asset" | "signal";
  mode: "indexed" | "signal";
};

const SERIES: SeriesDef[] = [
  { queryKey: "FRED:VIXCLS", label: "VIX", color: "#ef4444", group: "macro", mode: "indexed" },
  { queryKey: "FRED:DGS10", label: "10Y 금리", color: "#3b82f6", group: "macro", mode: "indexed" },
  { queryKey: "FRED:T10Y2Y", label: "장단기 금리차", color: "#eab308", group: "macro", mode: "indexed" },
  { queryKey: "FRED:ICSA", label: "신규실업수당", color: "#8b5cf6", group: "macro", mode: "indexed" },
  { queryKey: "FRED:M2SL", label: "M2", color: "#06b6d4", group: "macro", mode: "indexed" },
  { queryKey: "FRED:WALCL", label: "연준 총자산", color: "#22c55e", group: "macro", mode: "indexed" },
  { queryKey: "YAHOO:DXY", label: "DXY", color: "#f97316", group: "macro", mode: "indexed" },
  { queryKey: "YAHOO:NASDAQ", label: "나스닥", color: "#3b82f6", group: "asset", mode: "indexed" },
  { queryKey: "YAHOO:SP500", label: "S&P500", color: "#22c55e", group: "asset", mode: "indexed" },
  { queryKey: "YAHOO:GOLD", label: "금", color: "#eab308", group: "asset", mode: "indexed" },
  { queryKey: "YAHOO:SILVER", label: "은", color: "#a1a1aa", group: "asset", mode: "indexed" },
  { queryKey: "YAHOO:COPPER", label: "구리", color: "#b45309", group: "asset", mode: "indexed" },
  { queryKey: "YAHOO:WTI", label: "WTI", color: "#f97316", group: "asset", mode: "indexed" },
  { queryKey: "YAHOO:USDKRW", label: "원/달러", color: "#14b8a6", group: "asset", mode: "indexed" },
  { queryKey: "SIGNAL:NASDAQ", label: "나스닥 신호", color: "#60a5fa", group: "signal", mode: "signal" },
  { queryKey: "SIGNAL:GOLD", label: "금 신호", color: "#facc15", group: "signal", mode: "signal" },
  { queryKey: "SIGNAL:SILVER", label: "은 신호", color: "#d4d4d8", group: "signal", mode: "signal" },
  { queryKey: "SIGNAL:COPPER", label: "구리 신호", color: "#fb923c", group: "signal", mode: "signal" },
  { queryKey: "SIGNAL:REGIME", label: "국면 점수", color: "#f43f5e", group: "signal", mode: "signal" },
  { queryKey: "SIGNAL:PORTFOLIO", label: "종합 자산 신호", color: "#10b981", group: "signal", mode: "signal" },
];

const RANGE_OPTIONS: RangeKey[] = ["1D", "1W", "1M", "1Y", "5Y"];
const INTERVAL_OPTIONS: IntervalKey[] = ["1D", "1W", "1M"];

const CHART_W = 1000;
const CHART_H = 320;
const LEFT_PAD = 50;
const RIGHT_PAD = 50;
const DRAW_W = CHART_W - LEFT_PAD - RIGHT_PAD;

function effectiveRange(range: RangeKey): RangeKey {
  if (range === "5Y") return "5Y";
  if (range === "1Y") return "5Y";
  return "1Y";
}

function displayWindowFactor(range: RangeKey, effective: RangeKey) {
  if (range === effective) return 1;
  if (range === "1Y" && effective === "5Y") return 0.2;
  if (["1D", "1W", "1M"].includes(range) && effective === "1Y") return 0.2;
  return 1;
}

function indexToBase100(points: HistoryPoint[]) {
  if (!points.length) return [];
  const base = points[0].value || 1;
  return points.map((p) => ({ date: p.date, value: (p.value / base) * 100 }));
}

function toPath(points: HistoryPoint[], min: number, max: number) {
  if (!points.length) return "";
  const safeRange = max - min || 1;
  return points
    .map((p, i) => {
      const x = LEFT_PAD + (i / Math.max(points.length - 1, 1)) * DRAW_W;
      const y = CHART_H - ((p.value - min) / safeRange) * CHART_H;
      return `${i === 0 ? "M" : "L"}${x.toFixed(2)},${y.toFixed(2)}`;
    })
    .join(" ");
}

function formatDateShort(date: string) {
  return date.slice(2).replace(/-/g, ".");
}

function fmtY(value: number) {
  if (Math.abs(value) >= 1000) return value.toFixed(0);
  if (Math.abs(value) >= 100) return value.toFixed(1);
  return value.toFixed(2);
}

function labelCountForRange(range: RangeKey) {
  if (range === "1D") return 6;
  if (range === "1W") return 6;
  if (range === "1M") return 6;
  if (range === "1Y") return 7;
  return 8;
}

function yTicks(min: number, max: number, count = 5) {
  return Array.from({ length: count }, (_, i) => min + ((max - min) * i) / (count - 1));
}

export function HistoryPanel() {
  const [range, setRange] = useState<RangeKey>("1Y");
  const [interval, setInterval] = useState<IntervalKey>("1W");
  const [selected, setSelected] = useState<string[]>(["YAHOO:NASDAQ", "YAHOO:GOLD", "SIGNAL:REGIME"]);
  const [seriesMap, setSeriesMap] = useState<Record<string, HistoryPoint[]>>({});
  const [windowStart, setWindowStart] = useState(Number.MAX_SAFE_INTEGER);
  const dragState = useRef<{ dragging: boolean; startX: number; startWindow: number }>({ dragging: false, startX: 0, startWindow: 0 });

  useEffect(() => {
    const fetchRange = effectiveRange(range);
    const params = new URLSearchParams({ keys: selected.join(","), range: fetchRange, interval });
    fetch(`/api/history/series?${params.toString()}`)
      .then((r) => r.json())
      .then((json) => setSeriesMap(json.series || {}))
      .catch(() => setSeriesMap({}));
  }, [selected, range, interval]);

  const activeSeries = useMemo(() => {
    return selected
      .map((queryKey) => {
        const meta = SERIES.find((s) => s.queryKey === queryKey);
        const raw = seriesMap[queryKey] || [];
        if (!meta || !raw.length) return null;
        const points = meta.mode === "indexed" ? indexToBase100(raw) : raw;
        return { ...meta, points };
      })
      .filter(Boolean) as Array<SeriesDef & { points: HistoryPoint[] }>;
  }, [selected, seriesMap]);

  const effective = effectiveRange(range);
  const longestLength = Math.max(0, ...activeSeries.map((s) => s.points.length));
  const visibleCount = Math.max(10, Math.round(longestLength * displayWindowFactor(range, effective)));
  const maxWindowStart = Math.max(0, longestLength - visibleCount);
  const safeWindowStart = Math.min(windowStart, maxWindowStart);

  const windowedSeries = useMemo(() => {
    return activeSeries.map((series) => ({
      ...series,
      points: series.points.slice(safeWindowStart, safeWindowStart + visibleCount),
    }));
  }, [activeSeries, safeWindowStart, visibleCount]);

  const indexedSeries = windowedSeries.filter((s) => s.mode === "indexed");
  const signalSeries = windowedSeries.filter((s) => s.mode === "signal");

  const hasIndexed = indexedSeries.length > 0;
  const hasSignal = signalSeries.length > 0;

  const indexedVals = indexedSeries.flatMap((s) => s.points.map((p) => p.value));
  const iMin = indexedVals.length ? Math.min(...indexedVals) : 0;
  const iMax = indexedVals.length ? Math.max(...indexedVals) : 100;

  const signalVals = signalSeries.flatMap((s) => s.points.map((p) => p.value));
  const sMin = signalVals.length ? Math.min(...signalVals) : 0;
  const sMax = signalVals.length ? Math.max(...signalVals) : 100;

  const xLabels = useMemo(() => {
    const base = windowedSeries[0]?.points || [];
    if (!base.length) return [] as Array<{ x: number; label: string }>;
    const labelCount = labelCountForRange(range);
    const maxIndex = Math.max(base.length - 1, 1);
    const seen = new Set<number>();
    const marks = Array.from({ length: labelCount }, (_, i) => Math.round((i / (labelCount - 1)) * maxIndex))
      .filter((idx) => { if (seen.has(idx)) return false; seen.add(idx); return true; });
    return marks.map((idx) => ({ x: LEFT_PAD + (idx / maxIndex) * DRAW_W, label: formatDateShort(base[idx].date) }));
  }, [windowedSeries, range]);

  function onPointerDown(clientX: number) {
    dragState.current = { dragging: true, startX: clientX, startWindow: safeWindowStart };
  }
  function onPointerMove(clientX: number) {
    if (!dragState.current.dragging) return;
    const pointsPerPx = longestLength / DRAW_W;
    const deltaPoints = Math.round((clientX - dragState.current.startX) * pointsPerPx);
    const next = Math.max(0, Math.min(maxWindowStart, dragState.current.startWindow - deltaPoints));
    setWindowStart(next);
  }
  function onPointerUp() { dragState.current.dragging = false; }

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5 space-y-4">
      <div className="flex flex-col gap-2 md:flex-row md:items-end md:justify-between">
        <div>
          <h3 className="text-base sm:text-lg font-semibold">히스토리 시각화</h3>
          <p className="text-[11px] sm:text-xs text-[var(--muted)]">
            좌축: 가격/거시 (Index 100) · 우축: 신호 (0~100 점수)
          </p>
          <p className="text-[10px] sm:text-xs text-[var(--muted)] mt-1">
            선택 기준 <span className="font-semibold text-white">{range}</span> → 로드 범위 <span className="font-semibold text-white">{effective}</span>
          </p>
        </div>
        <div className="text-[10px] sm:text-xs text-[var(--muted)]">공통 보장: 최근 5년</div>
      </div>

      <div className="flex flex-wrap gap-2">
        {RANGE_OPTIONS.map((item) => (
          <button key={item} type="button" onClick={() => { setRange(item); setWindowStart(Number.MAX_SAFE_INTEGER); }} className={`rounded px-2.5 py-1.5 text-xs sm:text-sm border ${range === item ? "border-blue-500 bg-blue-500/10 text-blue-300" : "border-[var(--card-border)] text-[var(--muted)]"}`}>{item}</button>
        ))}
      </div>
      <div className="flex flex-wrap gap-2">
        {INTERVAL_OPTIONS.map((item) => (
          <button key={item} type="button" onClick={() => { setInterval(item); setWindowStart(Number.MAX_SAFE_INTEGER); }} className={`rounded px-2.5 py-1.5 text-xs sm:text-sm border ${interval === item ? "border-green-500 bg-green-500/10 text-green-300" : "border-[var(--card-border)] text-[var(--muted)]"}`}>간격 {item}</button>
        ))}
      </div>

      <SelectorGroup title="주요 데이터" items={SERIES.filter((s) => s.group === "macro")} selected={selected} setSelected={setSelected} resetWindow={() => setWindowStart(Number.MAX_SAFE_INTEGER)} />
      <SelectorGroup title="주요 자산 가격" items={SERIES.filter((s) => s.group === "asset")} selected={selected} setSelected={setSelected} resetWindow={() => setWindowStart(Number.MAX_SAFE_INTEGER)} />
      <SelectorGroup title="자산별/종합 신호" items={SERIES.filter((s) => s.group === "signal")} selected={selected} setSelected={setSelected} resetWindow={() => setWindowStart(Number.MAX_SAFE_INTEGER)} />

      <div className="rounded-lg border border-[var(--card-border)] bg-[var(--background)] p-3">
        <div className="flex items-center justify-between mb-2 text-[10px] sm:text-xs text-[var(--muted)]">
          <span>드래그해서 과거/최근 구간 이동</span>
          <span>{safeWindowStart + 1} ~ {Math.min(safeWindowStart + visibleCount, longestLength)} / {longestLength}</span>
        </div>

        <svg
          viewBox={`0 0 ${CHART_W} 380`}
          className="w-full h-[300px] sm:h-[380px] overflow-visible touch-none cursor-grab active:cursor-grabbing select-none"
          onMouseDown={(e) => onPointerDown(e.clientX)}
          onMouseMove={(e) => onPointerMove(e.clientX)}
          onMouseUp={onPointerUp}
          onMouseLeave={onPointerUp}
          onTouchStart={(e) => onPointerDown(e.touches[0].clientX)}
          onTouchMove={(e) => onPointerMove(e.touches[0].clientX)}
          onTouchEnd={onPointerUp}
        >
          {/* 좌측 y축 (indexed) */}
          {hasIndexed && yTicks(iMin, iMax).map((tick, idx) => {
            const y = CHART_H - ((tick - iMin) / Math.max(iMax - iMin, 1)) * CHART_H;
            return (
              <g key={`l-${idx}`}>
                <line x1={LEFT_PAD} x2={CHART_W - RIGHT_PAD} y1={y} y2={y} stroke="#262626" strokeWidth="1" />
                <text x={LEFT_PAD - 4} y={y + 3} textAnchor="end" fill="#3b82f6" fontSize="10">{fmtY(tick)}</text>
              </g>
            );
          })}

          {/* 우측 y축 (signal) */}
          {hasSignal && yTicks(sMin, sMax).map((tick, idx) => {
            const y = CHART_H - ((tick - sMin) / Math.max(sMax - sMin, 1)) * CHART_H;
            return (
              <g key={`r-${idx}`}>
                {!hasIndexed && <line x1={LEFT_PAD} x2={CHART_W - RIGHT_PAD} y1={y} y2={y} stroke="#262626" strokeWidth="1" />}
                <text x={CHART_W - RIGHT_PAD + 4} y={y + 3} textAnchor="start" fill="#f43f5e" fontSize="10">{fmtY(tick)}</text>
              </g>
            );
          })}

          {/* 축 라벨 */}
          {hasIndexed && <text x={LEFT_PAD - 4} y={-6} textAnchor="end" fill="#3b82f6" fontSize="9" fontWeight="bold">Index 100</text>}
          {hasSignal && <text x={CHART_W - RIGHT_PAD + 4} y={-6} textAnchor="start" fill="#f43f5e" fontSize="9" fontWeight="bold">Signal 0~100</text>}

          {/* x축 날짜 라벨 */}
          {xLabels.map((label) => (
            <g key={`${label.x}-${label.label}`}>
              <line x1={label.x} x2={label.x} y1="0" y2={CHART_H} stroke="#1f1f1f" strokeWidth="1" />
              <text x={label.x} y={CHART_H + 25} textAnchor="middle" fill="#737373" fontSize="10">{label.label}</text>
            </g>
          ))}

          {/* indexed 라인 (좌축 스케일) */}
          {indexedSeries.map((series) => (
            <path key={series.queryKey} d={toPath(series.points, iMin, iMax)} fill="none" stroke={series.color} strokeWidth="2.5" />
          ))}

          {/* signal 라인 (우축 스케일) */}
          {signalSeries.map((series) => (
            <path key={series.queryKey} d={toPath(series.points, sMin, sMax)} fill="none" stroke={series.color} strokeWidth="2" strokeDasharray="6 3" />
          ))}
        </svg>

        {/* 범례 */}
        <div className="mt-3 flex flex-wrap gap-2">
          {windowedSeries.map((series) => (
            <div key={series.queryKey} className="inline-flex items-center gap-2 rounded bg-neutral-800 px-2 py-1 text-[11px] sm:text-xs">
              <span className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: series.color }} />
              <span>{series.label}</span>
              <span className={`text-[9px] ${series.mode === "indexed" ? "text-blue-400" : "text-rose-400"}`}>
                {series.mode === "indexed" ? "Index" : "Signal"}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function SelectorGroup({
  title,
  items,
  selected,
  setSelected,
  resetWindow,
}: {
  title: string;
  items: SeriesDef[];
  selected: string[];
  setSelected: React.Dispatch<React.SetStateAction<string[]>>;
  resetWindow: () => void;
}) {
  return (
    <div>
      <div className="text-sm font-medium mb-2">{title}</div>
      <div className="flex flex-wrap gap-2">
        {items.map((item) => {
          const active = selected.includes(item.queryKey);
          return (
            <button
              key={item.queryKey}
              type="button"
              onClick={() => {
                resetWindow();
                setSelected((prev) => active ? prev.filter((v) => v !== item.queryKey) : [...prev, item.queryKey]);
              }}
              className={`rounded-lg border px-2.5 py-2 text-left transition-colors ${active ? "border-blue-500 bg-blue-500/10 text-blue-300" : "border-[var(--card-border)] bg-[var(--background)] text-[var(--muted)] hover:text-white"}`}
            >
              <div className="text-xs sm:text-sm font-medium">{item.label}</div>
              <div className={`text-[10px] sm:text-[11px] ${item.mode === "indexed" ? "text-blue-400/70" : "text-rose-400/70"}`}>
                {item.mode === "indexed" ? "좌축 · Index 100" : "우축 · Signal 0~100"}
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
}
