"use client";

interface DerivedPoint {
  name: string;
  value: number;
  date: string;
  formula: string;
}

const SECTOR_KEYS: Array<{ key: string; label: string; color: string }> = [
  { key: "SECTOR_XLK", label: "기술", color: "#3b82f6" },
  { key: "SECTOR_XLC", label: "커뮤니케이션", color: "#0ea5e9" },
  { key: "SECTOR_SOXX", label: "반도체", color: "#2563eb" },
  { key: "SECTOR_SMH", label: "반도체(대형)", color: "#1d4ed8" },
  { key: "SECTOR_XLF", label: "금융", color: "#22c55e" },
  { key: "SECTOR_XLE", label: "에너지", color: "#f97316" },
  { key: "SECTOR_XLV", label: "헬스케어", color: "#8b5cf6" },
  { key: "SECTOR_XLI", label: "산업재", color: "#eab308" },
  { key: "SECTOR_XLY", label: "임의소비재", color: "#14b8a6" },
  { key: "SECTOR_XLB", label: "소재", color: "#f59e0b" },
  { key: "SECTOR_XLU", label: "유틸리티", color: "#a855f7" },
  { key: "SECTOR_XLP", label: "필수소비재", color: "#c084fc" },
  { key: "SECTOR_XLRE", label: "리츠", color: "#64748b" },
  { key: "SECTOR_ITA", label: "방산", color: "#fb7185" },
  { key: "SECTOR_GRID", label: "전력망", color: "#06b6d4" },
  { key: "SECTOR_IGF", label: "인프라", color: "#84cc16" },
];

interface Props {
  derived: Record<string, DerivedPoint>;
  topdown?: {
    summary?: string;
    favoredSectors?: Array<{
      key: string;
      label: string;
      classification: string;
      score: number | null;
      quality?: { totalScore: number };
      reasons: string[];
    }>;
    avoidedSectors?: Array<{
      key: string;
      label: string;
      classification: string;
      score: number | null;
      quality?: { totalScore: number };
      reasons: string[];
    }>;
  };
}

const CLASSIFICATION_LABEL: Record<string, string> = {
  cyclical: '사이클형',
  structural: '구조형',
  defensive: '방어형',
};

function sectorQualityMetrics(derived: Record<string, DerivedPoint>, sectorKey: string) {
  const suffix = sectorKey.replace('SECTOR_', '');
  return {
    policy: derived[`SECTOR_POLICY_SUPPORT_${suffix}`]?.value,
    demand: derived[`SECTOR_STRUCTURAL_DEMAND_${suffix}`]?.value,
    supply: derived[`SECTOR_SUPPLY_TIGHTNESS_${suffix}`]?.value,
    total: derived[`SECTOR_QUALITY_TOTAL_${suffix}`]?.value,
  };
}

export function SectorPanel({ derived, topdown }: Props) {
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
          <p className="text-[11px] sm:text-xs text-[var(--muted)]">20일 수익률 기준 상대강도 + 탑다운 우호 섹터</p>
        </div>
        {strongest && (
          <div className="text-right">
            <div className="text-[10px] text-[var(--muted)]">최강 섹터</div>
            <div className="text-sm font-semibold text-green-400">{strongest.formula.split(': ')[1] || ''}</div>
          </div>
        )}
      </div>

      {topdown?.summary && (
        <div className="mb-3 rounded-lg border border-cyan-500/20 bg-cyan-500/5 p-3 text-[11px] sm:text-xs leading-relaxed break-words text-cyan-100">
          {topdown.summary}
        </div>
      )}

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

      {((topdown?.favoredSectors?.length ?? 0) > 0 || (topdown?.avoidedSectors?.length ?? 0) > 0) && (
        <div className="mt-4 grid grid-cols-1 lg:grid-cols-2 gap-3">
          {(topdown?.favoredSectors?.length ?? 0) > 0 && (
            <div className="rounded-lg border border-green-500/20 bg-green-500/5 p-3">
              <div className="text-xs font-semibold text-green-300 mb-2">탑다운 우호 섹터</div>
              <div className="space-y-2">
                {topdown?.favoredSectors?.map((sector) => {
                  const metrics = sectorQualityMetrics(derived, sector.key);
                  return (
                  <div key={sector.key} className="text-[11px] sm:text-xs break-words">
                    <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-1 sm:gap-2">
                      <span className="font-medium text-white">{sector.label}</span>
                      <span className="text-[10px] text-green-200">
                        {CLASSIFICATION_LABEL[sector.classification] || sector.classification}
                        {typeof sector.score === "number" ? ` · ${sector.score.toFixed(1)}%` : ""}
                        {typeof sector.quality?.totalScore === "number" ? ` · Q${sector.quality.totalScore}` : ""}
                      </span>
                    </div>
                    <div className="mt-1 flex flex-wrap gap-1 text-[10px]">
                      {typeof metrics.policy === "number" && <span className="rounded-full border border-white/10 px-1.5 py-0.5 text-cyan-200">정책 {Math.round(metrics.policy)}</span>}
                      {typeof metrics.demand === "number" && <span className="rounded-full border border-white/10 px-1.5 py-0.5 text-blue-200">수요 {Math.round(metrics.demand)}</span>}
                      {typeof metrics.supply === "number" && <span className="rounded-full border border-white/10 px-1.5 py-0.5 text-amber-200">공급 {Math.round(metrics.supply)}</span>}
                    </div>
                    <ul className="mt-1 text-[var(--muted)] list-disc pl-4 break-words">
                      {sector.reasons.slice(0, 2).map((r, i) => <li key={i}>{r}</li>)}
                    </ul>
                  </div>
                );})}
              </div>
            </div>
          )}

          {(topdown?.avoidedSectors?.length ?? 0) > 0 && (
            <div className="rounded-lg border border-red-500/20 bg-red-500/5 p-3">
              <div className="text-xs font-semibold text-red-300 mb-2">주의 섹터</div>
              <div className="space-y-2">
                {topdown?.avoidedSectors?.map((sector) => {
                  const metrics = sectorQualityMetrics(derived, sector.key);
                  return (
                  <div key={sector.key} className="text-[11px] sm:text-xs break-words">
                    <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-1 sm:gap-2">
                      <span className="font-medium text-white">{sector.label}</span>
                      <span className="text-[10px] text-red-200">
                        {CLASSIFICATION_LABEL[sector.classification] || sector.classification}
                        {typeof sector.score === "number" ? ` · ${sector.score.toFixed(1)}%` : ""}
                        {typeof sector.quality?.totalScore === "number" ? ` · Q${sector.quality.totalScore}` : ""}
                      </span>
                    </div>
                    <div className="mt-1 flex flex-wrap gap-1 text-[10px]">
                      {typeof metrics.policy === "number" && <span className="rounded-full border border-white/10 px-1.5 py-0.5 text-cyan-200">정책 {Math.round(metrics.policy)}</span>}
                      {typeof metrics.demand === "number" && <span className="rounded-full border border-white/10 px-1.5 py-0.5 text-blue-200">수요 {Math.round(metrics.demand)}</span>}
                      {typeof metrics.supply === "number" && <span className="rounded-full border border-white/10 px-1.5 py-0.5 text-amber-200">공급 {Math.round(metrics.supply)}</span>}
                    </div>
                    <ul className="mt-1 text-[var(--muted)] list-disc pl-4 break-words">
                      {sector.reasons.slice(0, 2).map((r, i) => <li key={i}>{r}</li>)}
                    </ul>
                  </div>
                );})}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
