"use client";

import { ActionBadge, InfoBadge, ScoreLegend, ScoreRow } from "./ScoreUI";

interface DerivedPoint {
  name: string;
  value: number | null;
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
    narrativeSummary?: string[];
    bottleneckSummary?: string[];
    favoredSectors?: Array<{
      key: string;
      label: string;
      classification: string;
      score: number | null;
      quality?: { totalScore: number };
      appealScore?: number;
      crowdingScore?: number;
      buyScore?: number;
      buyLabel?: string;
      reasons: string[];
    }>;
    avoidedSectors?: Array<{
      key: string;
      label: string;
      classification: string;
      score: number | null;
      quality?: { totalScore: number };
      appealScore?: number;
      crowdingScore?: number;
      buyScore?: number;
      buyLabel?: string;
      reasons: string[];
    }>;
    rotation?: {
      regime?: string;
      summary?: string;
      favoredNext?: string[];
      fadingNext?: string[];
      sectors?: Array<{
        key: string;
        label: string;
        rotationScore: number;
        state: string;
        rotationLabel: string;
      }>;
    };
  };
  topdownFreshness?: {
    source: string;
    eligibleForCurrentRanking: boolean;
    reason: string;
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

export function SectorPanel({ derived, topdown, topdownFreshness }: Props) {
  const sectors = SECTOR_KEYS
    .map(({ key, label, color }) => {
      const d = derived[key];
      if (!d) return null;
      const value = typeof d.value === "number" && Number.isFinite(d.value) ? d.value : null;
      return { label, color, value };
    })
    .filter(Boolean) as Array<{ label: string; color: string; value: number | null }>;

  if (!sectors.length) return null;

  const sorted = [...sectors].sort((a, b) => (b.value ?? Number.NEGATIVE_INFINITY) - (a.value ?? Number.NEGATIVE_INFINITY));
  const maxAbs = Math.max(...sectors.map((s) => Math.abs(s.value ?? 0)), 1);
  const strongest = derived.SECTOR_STRONGEST;

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <div className="flex items-center justify-between mb-3">
        <div>
          <h3 className="text-base sm:text-lg font-semibold">섹터 모멘텀</h3>
          <p className="text-[11px] sm:text-xs text-[var(--muted)]">막대: 20일 가격수익률 · 순환 점수: 최근 1개월 제외 6·12개월 배당 반영 상대강도·변동성 조정</p>
          <div className="mt-2"><ScoreLegend compact defaultOpen /></div>
        </div>
        {strongest && typeof strongest.formula === "string" && (
          <div className="text-right">
            <div className="text-[10px] text-[var(--muted)]">최강 섹터</div>
            <div className="text-sm font-semibold text-green-400">{strongest.formula.split(': ')[1] || ''}</div>
          </div>
        )}
      </div>

      {topdown && topdownFreshness?.eligibleForCurrentRanking !== true && (
        <div className="mb-3 rounded-lg border border-amber-500/25 bg-amber-500/10 px-3 py-2 text-[11px] text-amber-100">
          <span className="font-semibold">과거 탑다운 참고값</span>
          <span className="ml-2 text-amber-100/75">{topdownFreshness?.reason ?? '현재 산출시점을 확인할 수 없습니다.'}</span>
        </div>
      )}
      {topdown && topdownFreshness?.eligibleForCurrentRanking === true && (
        <div className="mb-3 rounded-lg border border-emerald-500/20 bg-emerald-500/5 px-3 py-2 text-[11px] text-emerald-100/80">
          현재 시장 입력 재계산 · {topdownFreshness.reason}
        </div>
      )}

      {topdown?.summary && (
        <div className="mb-3 rounded-lg border border-cyan-500/20 bg-cyan-500/5 p-3 text-[11px] sm:text-xs leading-relaxed break-words text-cyan-100">
          <div>{topdown.summary}</div>
          {topdown?.rotation?.summary && (
            <div className="mt-2 rounded-lg border border-white/10 bg-black/10 p-2 text-cyan-50">
              <div className="font-medium">섹터 순환 · {topdown.rotation.regime}</div>
              <div className="mt-1">{topdown.rotation.summary}</div>
              <div className="mt-2 flex flex-wrap gap-2">
                {(topdown.rotation.favoredNext ?? []).slice(0, 3).map((label) => (
                  <span key={label} className="rounded-full border border-emerald-500/20 bg-emerald-500/10 px-2 py-1 text-[10px] text-emerald-100">IN {label}</span>
                ))}
                {(topdown.rotation.fadingNext ?? []).slice(0, 2).map((label) => (
                  <span key={label} className="rounded-full border border-rose-500/20 bg-rose-500/10 px-2 py-1 text-[10px] text-rose-100">OUT {label}</span>
                ))}
              </div>
            </div>
          )}
          {((topdown?.favoredSectors?.length ?? 0) > 0 || (topdown?.avoidedSectors?.length ?? 0) > 0) && (
            <div className="mt-2 flex flex-wrap gap-2">
              {[...(topdown?.favoredSectors ?? []), ...(topdown?.avoidedSectors ?? [])]
                .filter((sector) => typeof sector.buyScore === 'number')
                .sort((a, b) => (b.buyScore ?? -1) - (a.buyScore ?? -1))
                .slice(0, 3)
                .map((sector) => (
                  <span key={sector.key} className="rounded-full border border-emerald-500/20 bg-emerald-500/10 px-2 py-1 text-[10px] text-emerald-100">
                    {sector.label} B{sector.buyScore}
                  </span>
                ))}
            </div>
          )}
          {(topdown?.narrativeSummary?.length ?? 0) > 0 && (
            <div className="mt-2 flex flex-wrap gap-2">
              {topdown?.narrativeSummary?.slice(0, 3).map((item) => (
                <span key={item} className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-1 text-[10px] text-cyan-50">
                  {item}
                </span>
              ))}
            </div>
          )}
          {(topdown?.bottleneckSummary?.length ?? 0) > 0 && (
            <div className="mt-2 flex flex-wrap gap-2">
              {topdown?.bottleneckSummary?.slice(0, 3).map((item) => (
                <span key={item} className="rounded-full border border-fuchsia-500/20 bg-fuchsia-500/10 px-2 py-1 text-[10px] text-fuchsia-50">
                  {item}
                </span>
              ))}
            </div>
          )}
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
                  width: `${Math.abs(s.value ?? 0) / maxAbs * 50}%`,
                  marginLeft: (s.value ?? 0) >= 0 ? "50%" : `${50 - Math.abs(s.value ?? 0) / maxAbs * 50}%`,
                  backgroundColor: s.color,
                  opacity: s.value === null ? 0.2 : 0.7,
                }}
              />
              <div className="absolute inset-0 flex items-center justify-center">
                <div className="w-px h-full bg-neutral-600" style={{ marginLeft: "50%" }} />
              </div>
            </div>
            <div className={`w-14 text-right text-xs font-mono ${s.value === null ? "text-[var(--muted)]" : s.value >= 0 ? "text-green-400" : "text-red-400"}`}>
              {s.value === null ? "—" : `${s.value >= 0 ? "+" : ""}${s.value.toFixed(1)}%`}
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
                        </span>
                      </div>
                      <ScoreRow>
                        <InfoBadge label="B" value={sector.buyScore} title="B 70+면 매수 우호 구간으로 볼 수 있고, 55~69면 선별 접근, 54 이하면 보수적으로 보는 편이 좋습니다. 좋은 섹터라도 단독 매수 신호는 아닙니다." kind="buy" />
                        <InfoBadge label="Q" value={sector.quality?.totalScore} title="Q는 섹터의 구조적 건강도입니다. 70+면 구조가 좋고, 55~69면 양호, 그 이하면 구조 우위가 약한 편입니다." kind="quality" />
                        <InfoBadge label="과열" value={sector.crowdingScore} title="과열 70+면 기업/섹터가 좋아도 추격은 주의 구간입니다. 55~69는 주의, 그 이하는 비교적 안정적입니다." kind="crowding" />
                        <InfoBadge label="매력" value={sector.appealScore} title="매력도는 가격보다 기업/섹터 상태가 얼마나 좋아 보이는지에 가깝습니다. 높을수록 구조와 흐름이 받쳐주는 편입니다." kind="appeal" />
                        <ActionBadge value={sector.buyScore} />
                        {typeof metrics.policy === "number" && <span title="정책 수혜 강도" className="rounded-full border border-white/10 px-1.5 py-0.5 text-cyan-200">정책 {Math.round(metrics.policy)}</span>}
                        {typeof metrics.demand === "number" && <span title="장기 구조 수요 강도" className="rounded-full border border-white/10 px-1.5 py-0.5 text-blue-200">수요 {Math.round(metrics.demand)}</span>}
                        {typeof metrics.supply === "number" && <span title="공급 제약/병목 강도" className="rounded-full border border-white/10 px-1.5 py-0.5 text-amber-200">공급 {Math.round(metrics.supply)}</span>}
                      </ScoreRow>
                      <ul className="mt-1 text-[var(--muted)] list-disc pl-4 break-words">
                        {sector.reasons.slice(0, 2).map((r, i) => <li key={i}>{r}</li>)}
                      </ul>
                    </div>
                  );
                })}
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
                        </span>
                      </div>
                      <ScoreRow>
                        <InfoBadge label="B" value={sector.buyScore} title="B 70+면 매수 우호 구간으로 볼 수 있고, 55~69면 선별 접근, 54 이하면 보수적으로 보는 편이 좋습니다. 좋은 섹터라도 단독 매수 신호는 아닙니다." kind="buy" />
                        <InfoBadge label="Q" value={sector.quality?.totalScore} title="Q는 섹터의 구조적 건강도입니다. 70+면 구조가 좋고, 55~69면 양호, 그 이하면 구조 우위가 약한 편입니다." kind="quality" />
                        <InfoBadge label="과열" value={sector.crowdingScore} title="과열 70+면 기업/섹터가 좋아도 추격은 주의 구간입니다. 55~69는 주의, 그 이하는 비교적 안정적입니다." kind="crowding" />
                        <InfoBadge label="매력" value={sector.appealScore} title="매력도는 가격보다 기업/섹터 상태가 얼마나 좋아 보이는지에 가깝습니다. 높을수록 구조와 흐름이 받쳐주는 편입니다." kind="appeal" />
                        <ActionBadge value={sector.buyScore} />
                        {typeof metrics.policy === "number" && <span title="정책 수혜 강도" className="rounded-full border border-white/10 px-1.5 py-0.5 text-cyan-200">정책 {Math.round(metrics.policy)}</span>}
                        {typeof metrics.demand === "number" && <span title="장기 구조 수요 강도" className="rounded-full border border-white/10 px-1.5 py-0.5 text-blue-200">수요 {Math.round(metrics.demand)}</span>}
                        {typeof metrics.supply === "number" && <span title="공급 제약/병목 강도" className="rounded-full border border-white/10 px-1.5 py-0.5 text-amber-200">공급 {Math.round(metrics.supply)}</span>}
                      </ScoreRow>
                      <ul className="mt-1 text-[var(--muted)] list-disc pl-4 break-words">
                        {sector.reasons.slice(0, 2).map((r, i) => <li key={i}>{r}</li>)}
                      </ul>
                    </div>
                  );
                })}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
