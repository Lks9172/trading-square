"use client";

const REGIME_CONFIG: Record<string, { emoji: string; color: string; label: string; action: string }> = {
  RISK_ON:        { emoji: "🟢", color: "border-green-500/50 bg-green-500/10", label: "위험선호", action: "공격적 비중 유지" },
  NEUTRAL:        { emoji: "🔵", color: "border-blue-500/50 bg-blue-500/10",   label: "중립",     action: "현 비중 유지, 관망" },
  CAUTION:        { emoji: "🟡", color: "border-yellow-500/50 bg-yellow-500/10", label: "경계",   action: "신규 매수 자제" },
  CORRECTION:     { emoji: "🟠", color: "border-orange-500/50 bg-orange-500/10", label: "조정",   action: "분할매수 준비/시작" },
  PANIC_BUT_OK:   { emoji: "🔴", color: "border-red-500/50 bg-red-500/10",     label: "공포(체력 유지)", action: "적극 분할매수" },
  RECESSION_RISK: { emoji: "⚫", color: "border-neutral-500/50 bg-neutral-500/10", label: "침체 위험", action: "현금 비중 극대화" },
};

interface Props {
  regime: {
    regime: string;
    score: number;
    components: Record<string, number>;
  };
}

export function RegimeHeader({ regime }: Props) {
  const config = REGIME_CONFIG[regime.regime] || REGIME_CONFIG.NEUTRAL;

  return (
    <div className={`rounded-xl border ${config.color} p-6`}>
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-3">
          <span className="text-3xl">{config.emoji}</span>
          <div>
            <h2 className="text-xl font-bold">{config.label}</h2>
            <p className="text-sm text-[var(--muted)]">{config.action}</p>
          </div>
        </div>
        <div className="text-right">
          <div className="text-3xl font-bold font-mono">{regime.score}</div>
          <div className="text-xs text-[var(--muted)]">/ 100</div>
        </div>
      </div>

      <div className="w-full bg-neutral-800 rounded-full h-2 mt-2">
        <div
          className="h-2 rounded-full transition-all duration-500"
          style={{
            width: `${regime.score}%`,
            background: regime.score >= 75 ? "#22c55e"
              : regime.score >= 55 ? "#3b82f6"
              : regime.score >= 40 ? "#eab308"
              : regime.score >= 25 ? "#f97316"
              : "#ef4444",
          }}
        />
      </div>

      <div className="mt-4 flex flex-wrap gap-2">
        {Object.entries(regime.components).map(([key, val]) => (
          <span
            key={key}
            className={`px-2 py-1 rounded text-xs font-mono ${
              val > 0 ? "bg-green-500/20 text-green-400"
              : val < 0 ? "bg-red-500/20 text-red-400"
              : "bg-neutral-700 text-neutral-400"
            }`}
          >
            {key}: {val > 0 ? "+" : ""}{val}
          </span>
        ))}
      </div>
    </div>
  );
}
