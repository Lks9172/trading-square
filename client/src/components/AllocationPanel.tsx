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
    buyStage: number;
  };
}

export function AllocationPanel({ allocation }: Props) {
  const entries = Object.entries(allocation.allocations)
    .filter(([, pct]) => pct > 0)
    .sort((a, b) => b[1] - a[1]);

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-5">
      <h3 className="text-lg font-semibold mb-4">포트폴리오 비중 제안</h3>

      <div className="w-full h-6 rounded-full overflow-hidden flex mb-4">
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

      <div className="space-y-2">
        {entries.map(([asset, pct]) => (
          <div key={asset} className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <div
                className="w-3 h-3 rounded-sm"
                style={{ backgroundColor: ASSET_COLORS[asset] || "#525252" }}
              />
              <span className="text-sm">{ASSET_LABELS[asset] || asset}</span>
            </div>
            <span className="text-sm font-mono font-semibold">{pct}%</span>
          </div>
        ))}
      </div>

      <div className="mt-4 pt-4 border-t border-[var(--card-border)] space-y-2">
        <div className="flex items-center justify-between text-sm">
          <span className="text-[var(--muted)]">레버리지</span>
          <span className={allocation.leverageAllowed ? "text-green-400 font-semibold" : "text-[var(--muted)]"}>
            {allocation.leverageAllowed ? "허용 (최대 15%)" : "불허"}
          </span>
        </div>
        <div className="flex items-center justify-between text-sm">
          <span className="text-[var(--muted)]">분할매수</span>
          <span className={allocation.buyStage > 0 ? "text-blue-400 font-semibold" : "text-[var(--muted)]"}>
            {BUY_STAGE_LABELS[allocation.buyStage] || ""}
          </span>
        </div>
      </div>
    </div>
  );
}
