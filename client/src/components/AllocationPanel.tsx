"use client";

import { useState } from "react";

function formatKRW(value: number) {
  if (value >= 100_000_000) return `${(value / 100_000_000).toFixed(1)}억`;
  if (value >= 10_000) return `${(value / 10_000).toFixed(0)}만`;
  return value.toLocaleString("ko-KR");
}

const ASSET_LABELS: Record<string, string> = {
  cash: "현금",
  nasdaq: "나스닥 ETF",
  leverage: "레버리지 ETF",
  gold: "금",
  silver: "은",
  copper: "구리/원자재",
  korea: "한국 주식",
  emerging: "신흥국 ETF",
};

const ASSET_COLORS: Record<string, string> = {
  cash: "#737373",
  nasdaq: "#3b82f6",
  leverage: "#f97316",
  gold: "#eab308",
  silver: "#a1a1aa",
  copper: "#b45309",
  korea: "#8b5cf6",
  emerging: "#06b6d4",
};

const BUY_STAGE_LABELS: Record<number, string> = {
  0: "매수 구간 아님",
  1: "1차 분할매수 구간 (200DMA 터치) — 목표 비중의 33% 투입",
  2: "2차 분할매수 구간 (강한 조정) — 목표 비중의 33% 추가 투입",
  3: "3차 분할매수 구간 (공포 극대화) — 나머지 34% 투입",
};

interface Props {
  allocation: {
    regime: string;
    score: number;
    allocations: Record<string, number>;
    leverageAllowed: boolean;
    // Fix #6: NASDAQ_ABOVE_200DMA 결측 시 null. UI 는 "데이터 없음" 표시.
    buyStage: number | null;
  };
  overheated?: boolean;
}

export function AllocationPanel({ allocation, overheated }: Props) {
  const [totalAsset, setTotalAsset] = useState<string>("");
  const totalNum = parseInt(totalAsset.replace(/[^0-9]/g, ""), 10) || 0;

  // 전체 자산을 항상 나열 — 0% 도 "0%" 로 명시해야 사용자가
  // "빠진 건지 / 의도적 0 인지" 판단 가능. 막대는 > 0 만, 리스트는 전체.
  const allEntries = Object.entries(allocation.allocations)
    .sort((a, b) => b[1] - a[1]);
  const barEntries = allEntries.filter(([, pct]) => pct > 0);

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <h3 className="text-base sm:text-lg font-semibold mb-3 sm:mb-4">포트폴리오 비중 제안</h3>

      <div className="mb-3 sm:mb-4">
        <label className="text-xs sm:text-sm text-[var(--muted)] block mb-1.5">총 자산 (원)</label>
        <div className="flex items-center gap-2">
          <input
            type="text"
            inputMode="numeric"
            placeholder="예: 100000000"
            value={totalAsset}
            onChange={(e) => setTotalAsset(e.target.value.replace(/[^0-9]/g, ""))}
            className="flex-1 rounded-lg border border-[var(--card-border)] bg-[var(--background)] px-3 py-2 text-sm sm:text-base font-mono text-white placeholder:text-neutral-600 focus:border-blue-500 focus:outline-none"
          />
          {totalNum > 0 && (
            <span className="text-xs sm:text-sm font-semibold text-blue-400 shrink-0">
              {formatKRW(totalNum)}원
            </span>
          )}
        </div>
      </div>

      <div className="w-full h-5 sm:h-6 rounded-full overflow-hidden flex mb-3 sm:mb-4">
        {barEntries.map(([asset, pct]) => (
          <div
            key={asset}
            style={{
              width: `${pct}%`,
              backgroundColor: ASSET_COLORS[asset] || "#525252",
            }}
            title={`${ASSET_LABELS[asset] || asset}: ${pct}%`}
          />
        ))}
      </div>

      <div className="flex justify-between text-[9px] sm:text-[10px] text-[var(--muted)] mb-3 sm:mb-4 -mt-2">
        <span>0%</span>
        <span>25%</span>
        <span>50%</span>
        <span>75%</span>
        <span>100%</span>
      </div>

      <div className="space-y-1.5 sm:space-y-2">
        {allEntries.map(([asset, pct]) => {
          const isZero = pct === 0;
          const amount = totalNum > 0 && pct > 0 ? Math.round(totalNum * pct / 100) : 0;
          return (
            <div
              key={asset}
              className={`flex items-center justify-between ${isZero ? "opacity-50" : ""}`}
            >
              <div className="flex items-center gap-1.5 sm:gap-2 min-w-0">
                <div
                  className="w-2.5 h-2.5 sm:w-3 sm:h-3 rounded-sm shrink-0"
                  style={{
                    backgroundColor: ASSET_COLORS[asset] || "#525252",
                    opacity: isZero ? 0.3 : 1,
                  }}
                />
                <span className="text-xs sm:text-sm truncate">{ASSET_LABELS[asset] || asset}</span>
              </div>
              <div className="flex items-center gap-2 shrink-0 ml-2">
                <span
                  className={`text-xs sm:text-sm font-mono font-semibold ${
                    isZero ? "text-[var(--muted)]" : ""
                  }`}
                >
                  {pct}%
                </span>
                {amount > 0 && (
                  <span className="text-[10px] sm:text-xs font-mono text-blue-400">{formatKRW(amount)}원</span>
                )}
              </div>
            </div>
          );
        })}
      </div>

      <div className="mt-3 sm:mt-4 pt-3 sm:pt-4 border-t border-[var(--card-border)] space-y-2">
        <div className="flex items-center justify-between text-xs sm:text-sm">
          <span className="text-[var(--muted)]">레버리지</span>
          <span className={allocation.leverageAllowed ? "text-green-400 font-semibold" : "text-[var(--muted)]"}>
            {allocation.leverageAllowed ? "허용 (최대 15%)" : "불허"}
          </span>
        </div>
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-0.5 sm:gap-0 text-xs sm:text-sm">
          <span className="text-[var(--muted)]">분할매수</span>
          <span
            className={`${
              allocation.buyStage === null
                ? "text-[var(--muted)] italic"
                : allocation.buyStage > 0
                ? "text-blue-400 font-semibold"
                : "text-[var(--muted)]"
            } text-right`}
          >
            {allocation.buyStage === null
              ? "데이터 없음 (200DMA 결측)"
              : BUY_STAGE_LABELS[allocation.buyStage] || ""}
          </span>
        </div>
        {(allocation.allocations.cash || 0) > 10 && (
          <div className="mt-2 text-[10px] sm:text-xs text-[var(--muted)] bg-[var(--background)] border border-[var(--card-border)] rounded-lg p-2.5">
            💡 현금 비중 운용 가이드: 파킹 통장, 단기채 ETF (SHY, TIGER 단기국채), CMA를 활용하면 대기 중에도 이자 수익 확보 가능
          </div>
        )}
        {overheated && (
          <div className="mt-2 text-[10px] sm:text-xs text-yellow-300 bg-yellow-500/10 border border-yellow-500/20 rounded-lg p-2.5">
            ⚠️ 과열 구간 감지: 현금 +20%, 금 +5%로 방어 강화. 나스닥/한국/신흥국/구리 비중이 자동 축소됨.
          </div>
        )}
      </div>
    </div>
  );
}
