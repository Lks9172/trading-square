"use client";

const SIGNAL_CONFIG: Record<string, { color: string; bg: string; label: string }> = {
  STRONG_BUY: { color: "text-green-400", bg: "bg-green-500/20", label: "적극 매수" },
  BUY:        { color: "text-blue-400",  bg: "bg-blue-500/20",  label: "매수" },
  HOLD:       { color: "text-neutral-400", bg: "bg-neutral-700", label: "관망" },
  REDUCE:     { color: "text-yellow-400", bg: "bg-yellow-500/20", label: "축소" },
  SELL:       { color: "text-red-400",   bg: "bg-red-500/20",   label: "매도" },
};

const ASSET_LABELS: Record<string, string> = {
  NASDAQ: "나스닥 ETF",
  GOLD: "금",
  SILVER: "은",
  COPPER: "구리",
  CASH: "현금",
  LEVERAGE: "레버리지",
};

interface Signal {
  asset: string;
  signal: string;
  conditionsMet: number;
  conditionsTotal: number;
  reasons: string[];
}

interface Props {
  signals: Signal[];
}

export function SignalPanel({ signals }: Props) {
  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-5">
      <h3 className="text-lg font-semibold mb-4">자산별 신호</h3>
      <div className="space-y-3">
        {signals.map((sig) => {
          const config = SIGNAL_CONFIG[sig.signal] || SIGNAL_CONFIG.HOLD;
          return (
            <div key={sig.asset} className="flex items-start justify-between gap-4 pb-3 border-b border-[var(--card-border)] last:border-0 last:pb-0">
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1">
                  <span className="font-medium">{ASSET_LABELS[sig.asset] || sig.asset}</span>
                  <span className={`px-2 py-0.5 rounded text-xs font-semibold ${config.bg} ${config.color}`}>
                    {config.label}
                  </span>
                </div>
                <div className="text-xs text-[var(--muted)] space-y-0.5">
                  {sig.reasons.map((r, i) => (
                    <p key={i}>{r}</p>
                  ))}
                </div>
              </div>
              {sig.conditionsTotal > 0 && (
                <div className="text-right shrink-0">
                  <div className="text-lg font-mono font-bold">
                    {sig.conditionsMet}/{sig.conditionsTotal}
                  </div>
                  <div className="text-[10px] text-[var(--muted)]">충족</div>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
