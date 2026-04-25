"use client";

import { useEffect, useState } from "react";

interface PeriodResult {
  label: string;
  return_pct: number;
  max_drawdown: number;
  regime_changes: number;
  data_points: number;
  // 20차 노션 A2: PortfolioVisualizer 정합 메트릭
  sharpe?: number;
  cagr_pct?: number;
  calmar?: number;
  annual_volatility_pct?: number;
}

interface BacktestData {
  periods: PeriodResult[];
  signal_stats: { total_days: number; buy_signal_days: number; buy_signal_ratio: number };
}

interface PortfolioResult {
  years: number;
  portfolio: { final_value: number; return_pct: number; max_drawdown: number };
  buyhold: { final_value: number; return_pct: number; max_drawdown: number };
  alpha: number;
  series: Array<{ date: string; portfolio: number; buyhold: number }>;
}

// 년 버튼 체감 지연 해결: mount 시 1/3/5Y 동시 prefetch 후 map 에 캐시.
// 버튼 클릭은 캐시에서 즉시 swap (server TTL 캐시와 이중 방어).
const YEAR_OPTIONS = [1, 3, 5] as const;

export function BacktestPanel() {
  const [data, setData] = useState<BacktestData | null>(null);
  const [portfolios, setPortfolios] = useState<Record<number, PortfolioResult>>({});
  const [pfYears, setPfYears] = useState<number>(3);
  const [loadingYear, setLoadingYear] = useState<number | null>(null);

  useEffect(() => {
    fetch("/api/backtest/summary").then((r) => r.json()).then(setData).catch(() => {});
  }, []);

  // mount 시 1/3/5Y 전부 동시 prefetch. server TTL 캐시가 있어 같은 값 여러번 호출해도
  // 1번만 실제 연산. 네트워크 병렬이라 3개 합쳐도 가장 느린 것만큼만 걸림.
  useEffect(() => {
    let cancelled = false;
    Promise.all(
      YEAR_OPTIONS.map((y) =>
        fetch(`/api/backtest/portfolio?years=${y}`)
          .then((r) => r.json())
          .then((j) => ({ y, j }))
          .catch(() => ({ y, j: null }))
      )
    ).then((results) => {
      if (cancelled) return;
      const next: Record<number, PortfolioResult> = {};
      for (const { y, j } of results) {
        if (j && j.series) next[y] = j as PortfolioResult;
      }
      setPortfolios(next);
    });
    return () => { cancelled = true; };
  }, []);

  // 클릭 후 캐시에 없는 년은 on-demand fetch (보통 prefetch 된 상태).
  useEffect(() => {
    if (portfolios[pfYears]) return;
    let cancelled = false;
    setLoadingYear(pfYears);
    fetch(`/api/backtest/portfolio?years=${pfYears}`)
      .then((r) => r.json())
      .then((j) => {
        if (cancelled) return;
        if (j && j.series) setPortfolios((p) => ({ ...p, [pfYears]: j as PortfolioResult }));
      })
      .catch(() => {})
      .finally(() => { if (!cancelled) setLoadingYear(null); });
    return () => { cancelled = true; };
  }, [pfYears, portfolios]);

  const portfolio = portfolios[pfYears] ?? null;

  if (!data?.periods?.length) return null;

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5 space-y-5">
      <div>
        <h3 className="text-base sm:text-lg font-semibold mb-1">백테스트</h3>
        <p className="text-[11px] sm:text-xs text-[var(--muted)]">영속 히스토리 기반. 시스템 비중 포트폴리오 vs 나스닥 단순보유.</p>
      </div>

      <div>
        <div className="text-sm font-medium mb-2">나스닥 벤치마크</div>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          {data.periods.map((p) => (
            <div key={p.label} className="rounded-lg border border-[var(--card-border)] bg-[var(--background)] p-3">
              <div className="text-xs text-[var(--muted)] mb-1">{p.label}</div>
              <div className={`text-lg font-mono font-bold ${p.return_pct >= 0 ? "text-green-400" : "text-red-400"}`}>
                {p.return_pct >= 0 ? "+" : ""}{p.return_pct}%
              </div>
              <div className="mt-2 space-y-1 text-[10px] text-[var(--muted)]">
                <div className="flex justify-between"><span>최대 낙폭</span><span className="text-red-400 font-mono">{p.max_drawdown}%</span></div>
                <div className="flex justify-between"><span>국면 변동</span><span className="font-mono">{p.regime_changes}회</span></div>
                {/* 20차 노션 A2: PortfolioVisualizer 정합 — Sharpe/CAGR/Calmar/Vol */}
                {typeof p.sharpe === 'number' && (
                  <>
                    <div className="flex justify-between"><span>Sharpe</span><span className="font-mono">{p.sharpe}</span></div>
                    <div className="flex justify-between"><span>CAGR</span><span className="font-mono">{p.cagr_pct}%</span></div>
                    <div className="flex justify-between"><span>Calmar</span><span className="font-mono">{p.calmar}</span></div>
                    <div className="flex justify-between"><span>연환산 변동성</span><span className="font-mono">{p.annual_volatility_pct}%</span></div>
                  </>
                )}
              </div>
            </div>
          ))}
        </div>
      </div>

      <div>
        <div className="flex items-center justify-between mb-2">
          <div className="text-sm font-medium">시스템 포트폴리오 vs 나스닥</div>
          <div className="flex gap-1">
            {YEAR_OPTIONS.map((y) => {
              const isActive = pfYears === y;
              const isLoading = loadingYear === y && !portfolios[y];
              const isCached = !!portfolios[y];
              return (
                <button
                  key={y}
                  type="button"
                  onClick={() => setPfYears(y)}
                  className={`px-2 py-1 rounded text-xs border transition-colors ${
                    isActive
                      ? "border-blue-500 bg-blue-500/10 text-blue-300"
                      : isCached
                        ? "border-[var(--card-border)] text-[var(--muted)] hover:border-blue-500/50"
                        : "border-[var(--card-border)] text-[var(--muted)] opacity-60"
                  }`}
                  title={isCached ? "캐시됨 (즉시 전환)" : isLoading ? "로딩 중..." : "prefetch 중..."}
                >
                  {y}Y{isLoading ? "…" : ""}
                </button>
              );
            })}
          </div>
        </div>

        {portfolio?.series ? (
          <>
            <div className="grid grid-cols-2 gap-3 mb-3">
              <div className="rounded-lg border border-blue-500/30 bg-blue-500/5 p-3">
                <div className="text-[10px] text-blue-400 mb-1">시스템 포트폴리오</div>
                <div className={`text-xl font-mono font-bold ${portfolio.portfolio.return_pct >= 0 ? "text-green-400" : "text-red-400"}`}>
                  {portfolio.portfolio.return_pct >= 0 ? "+" : ""}{portfolio.portfolio.return_pct}%
                </div>
                <div className="text-[10px] text-[var(--muted)] mt-1">최대낙폭 {portfolio.portfolio.max_drawdown}%</div>
              </div>
              <div className="rounded-lg border border-[var(--card-border)] bg-[var(--background)] p-3">
                <div className="text-[10px] text-[var(--muted)] mb-1">나스닥 단순보유</div>
                <div className={`text-xl font-mono font-bold ${portfolio.buyhold.return_pct >= 0 ? "text-green-400" : "text-red-400"}`}>
                  {portfolio.buyhold.return_pct >= 0 ? "+" : ""}{portfolio.buyhold.return_pct}%
                </div>
                <div className="text-[10px] text-[var(--muted)] mt-1">최대낙폭 {portfolio.buyhold.max_drawdown}%</div>
              </div>
            </div>

            <div className="rounded-lg border border-[var(--card-border)] bg-[var(--background)] p-3 mb-3">
              <div className="flex items-center justify-between">
                <span className="text-xs text-[var(--muted)]">알파 (초과수익)</span>
                <span className={`text-sm font-mono font-bold ${portfolio.alpha >= 0 ? "text-green-400" : "text-red-400"}`}>
                  {portfolio.alpha >= 0 ? "+" : ""}{portfolio.alpha}%p
                </span>
              </div>
            </div>

            <div className="rounded-lg border border-[var(--card-border)] bg-[var(--background)] p-3">
              <svg viewBox="0 0 1000 200" className="w-full h-[160px] sm:h-[200px]">
                {portfolio.series.length > 1 && (() => {
                  const allVals = portfolio.series.flatMap((p) => [p.portfolio, p.buyhold]);
                  const min = Math.min(...allVals);
                  const max = Math.max(...allVals);
                  const range = max - min || 1;
                  const toPath = (key: "portfolio" | "buyhold") =>
                    portfolio.series.map((p, i) => {
                      const x = (i / (portfolio.series.length - 1)) * 1000;
                      const y = 200 - ((p[key] - min) / range) * 200;
                      return `${i === 0 ? "M" : "L"}${x.toFixed(1)},${y.toFixed(1)}`;
                    }).join(" ");
                  return (
                    <>
                      <path d={toPath("buyhold")} fill="none" stroke="#737373" strokeWidth="2" />
                      <path d={toPath("portfolio")} fill="none" stroke="#3b82f6" strokeWidth="2.5" />
                    </>
                  );
                })()}
              </svg>
              <div className="flex gap-4 mt-2 text-[10px] sm:text-xs">
                <div className="flex items-center gap-1"><span className="w-3 h-0.5 bg-blue-500 inline-block" /> 시스템 포트폴리오</div>
                <div className="flex items-center gap-1"><span className="w-3 h-0.5 bg-neutral-500 inline-block" /> 나스닥 단순보유</div>
              </div>
            </div>
          </>
        ) : (
          <div className="text-xs text-[var(--muted)]">로딩 중...</div>
        )}
      </div>

      <div className="rounded-lg border border-[var(--card-border)] bg-[var(--background)] p-3">
        <div className="text-xs text-[var(--muted)] mb-2">매수 신호 통계</div>
        <div className="flex items-center gap-4">
          <div>
            <div className="text-sm font-mono font-bold text-blue-400">{data.signal_stats.buy_signal_ratio}%</div>
            <div className="text-[10px] text-[var(--muted)]">매수 신호 비율</div>
          </div>
          <div className="flex-1">
            <div className="w-full h-2 rounded-full bg-neutral-800 overflow-hidden">
              <div className="h-2 rounded-full bg-blue-500" style={{ width: `${data.signal_stats.buy_signal_ratio}%` }} />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
