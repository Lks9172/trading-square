"use client";

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
  1: "1차 분할매수 구간 (200DMA 터치)",
  2: "2차 분할매수 구간 (강한 조정)",
  3: "3차 분할매수 구간 (공포 극대화)",
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
  void overheated; // 향후 과열 배너용 예약 (현재 UI 렌더에는 영향 없음)
  const entries = Object.entries(allocation.allocations)
    .filter(([, pct]) => pct > 0)
    .sort((a, b) => b[1] - a[1]);

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <h3 className="text-base sm:text-lg font-semibold mb-3 sm:mb-4">포트폴리오 비중 제안</h3>

      <div className="w-full h-5 sm:h-6 rounded-full overflow-hidden flex mb-3 sm:mb-4">
        {entries.map(([asset, pct]) => (
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

      <div className="space-y-1.5 sm:space-y-2">
        {entries.map(([asset, pct]) => (
          <div key={asset} className="flex items-center justify-between">
            <div className="flex items-center gap-1.5 sm:gap-2 min-w-0">
              <div
                className="w-2.5 h-2.5 sm:w-3 sm:h-3 rounded-sm shrink-0"
                style={{ backgroundColor: ASSET_COLORS[asset] || "#525252" }}
              />
              <span className="text-xs sm:text-sm truncate">{ASSET_LABELS[asset] || asset}</span>
            </div>
            <span className="text-xs sm:text-sm font-mono font-semibold shrink-0 ml-2">{pct}%</span>
          </div>
        ))}
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
          <span className={`${(allocation.buyStage ?? 0) > 0 ? "text-blue-400 font-semibold" : "text-[var(--muted)]"} text-right`}>
            {allocation.buyStage === null
              ? "데이터 없음 (NASDAQ 200DMA 수집 실패)"
              : BUY_STAGE_LABELS[allocation.buyStage] || ""}
          </span>
        </div>
      </div>
    </div>
  );
}
