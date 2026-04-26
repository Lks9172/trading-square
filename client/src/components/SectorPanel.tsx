"use client";

interface DerivedPoint {
  name: string;
  value: number;
  date: string;
  formula: string;
}

const SECTOR_KEYS: Array<{ key: string; label: string; color: string }> = [
  { key: "SECTOR_XLK", label: "기술", color: "#3b82f6" },
  { key: "SECTOR_XLF", label: "금융", color: "#22c55e" },
  { key: "SECTOR_XLE", label: "에너지", color: "#f97316" },
  { key: "SECTOR_XLV", label: "헬스케어", color: "#8b5cf6" },
  { key: "SECTOR_XLI", label: "산업재", color: "#eab308" },
  { key: "SECTOR_XLY", label: "임의소비재", color: "#14b8a6" },
];

interface Props {
  derived: Record<string, DerivedPoint>;
}

export function SectorPanel({ derived }: Props) {
  const sectors = SECTOR_KEYS
    .map(({ key, label, color }) => {
      const d = derived[key];
      return d ? { label, color, value: d.value } : null;
    })
    .filter(Boolean) as Array<{ label: string; color: string; value: number }>;

  if (!sectors.length) return null;

  const sorted = [...sectors].sort((a, b) => b.value - a.value);
  const maxAbs = Math.max(...sectors.map((s) => Math.abs(s.value)), 1);
  const strongest = derived.SECTOR_STRONGEST;

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <div className="flex items-center justify-between mb-3">
        <div>
          <h3 className="text-base sm:text-lg font-semibold">섹터 모멘텀</h3>
          <p className="text-[11px] sm:text-xs text-[var(--muted)]">20일 수익률 기준 섹터별 상대강도</p>
        </div>
        {strongest && (
          <div className="text-right">
            <div className="text-[10px] text-[var(--muted)]">최강 섹터</div>
            <div className="text-sm font-semibold text-green-400">{strongest.formula.split(': ')[1] || ''}</div>
          </div>
        )}
      </div>

      <div className="space-y-2">
        {sorted.map((s) => (
          <div key={s.label} className="flex items-center gap-2">
            <div className="w-16 sm:w-20 text-xs sm:text-sm shrink-0">{s.label}</div>
            <div className="flex-1 h-5 bg-neutral-800 rounded overflow-hidden relative">
              <div
                className="h-5 rounded transition-all duration-300"
                style={{
                  width: `${Math.abs(s.value) / maxAbs * 50}%`,
                  marginLeft: s.value >= 0 ? "50%" : `${50 - Math.abs(s.value) / maxAbs * 50}%`,
                  backgroundColor: s.color,
                  opacity: 0.7,
                }}
              />
              <div className="absolute inset-0 flex items-center justify-center">
                <div className="w-px h-full bg-neutral-600" style={{ marginLeft: "50%" }} />
              </div>
            </div>
            <div className={`w-14 text-right text-xs font-mono ${s.value >= 0 ? "text-green-400" : "text-red-400"}`}>
              {s.value >= 0 ? "+" : ""}{s.value.toFixed(1)}%
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
