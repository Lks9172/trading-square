"use client";

import { useEffect, useState } from "react";

interface InsiderData {
  insiderBuyRatio: number;
  recentInsiderBuys: number;
  recentInsiderSells: number;
  score?: number;
  dataromaBuyCount?: number;
  dataromaSellCount?: number;
  dataromaScore?: number;
  dataromaAvgAddPct?: number;
  dataromaAvgReducePct?: number;
  dataromaNetPortfolioFlow?: number;
  lastUpdated: string;
}

interface EarningsEvent {
  ticker: string;
  date: string;
}

export function SmartMoneyPanel() {
  const [insider, setInsider] = useState<InsiderData | null>(null);
  const [earnings, setEarnings] = useState<EarningsEvent[]>([]);

  useEffect(() => {
    fetch("/api/smart-money").then((r) => r.json()).then((d) => setInsider(d.insider)).catch(() => {});
    fetch("/api/earnings").then((r) => r.json()).then((d) => setEarnings(d.earnings || [])).catch(() => {});
  }, []);

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 sm:gap-6">
      <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
        <h3 className="text-base sm:text-lg font-semibold mb-1">스마트머니 추적</h3>
        <p className="text-[11px] sm:text-xs text-[var(--muted)] mb-3">최근 7일 미국 내부자거래 요약 (OpenInsider)</p>

        {insider ? (
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-sm">내부자 매수 비율</span>
              <span className={`text-lg font-mono font-bold ${insider.insiderBuyRatio > 55 ? "text-green-400" : insider.insiderBuyRatio < 45 ? "text-red-400" : "text-neutral-400"}`}>
                {insider.insiderBuyRatio}%
              </span>
            </div>
            <div className="w-full h-3 rounded-full bg-neutral-800 overflow-hidden">
              <div className="h-3 rounded-full bg-green-500" style={{ width: `${insider.insiderBuyRatio}%` }} />
            </div>
            <div className="flex justify-between text-[10px] sm:text-xs text-[var(--muted)]">
              <span>매수 {insider.recentInsiderBuys}건</span>
              <span>매도 {insider.recentInsiderSells}건</span>
            </div>
            <div className="flex justify-between text-[10px] sm:text-xs text-[var(--muted)]">
              <span>스마트머니 점수</span>
              <span className={`font-mono ${
                (insider.score ?? 0) > 0 ? 'text-green-400' : (insider.score ?? 0) < 0 ? 'text-red-400' : 'text-neutral-400'
              }`}>
                {insider.score ?? 0}
              </span>
            </div>
            <div className="pt-2 border-t border-[var(--card-border)] text-[10px] sm:text-xs text-[var(--muted)] space-y-1">
              <div className="flex justify-between">
                <span>Dataroma 매수/추가</span>
                <span>{insider.dataromaBuyCount ?? 0}건</span>
              </div>
              <div className="flex justify-between">
                <span>Dataroma 매도/축소</span>
                <span>{insider.dataromaSellCount ?? 0}건</span>
              </div>
              <div className="flex justify-between">
                <span>평균 Add %</span>
                <span className="font-mono text-green-400">+{(insider.dataromaAvgAddPct ?? 0).toFixed(1)}%</span>
              </div>
              <div className="flex justify-between">
                <span>평균 Reduce %</span>
                <span className="font-mono text-red-400">-{(insider.dataromaAvgReducePct ?? 0).toFixed(1)}%</span>
              </div>
              <div className="flex justify-between">
                <span>포트폴리오 net flow</span>
                <span className={`font-mono ${
                  (insider.dataromaNetPortfolioFlow ?? 0) > 0 ? 'text-green-400' : (insider.dataromaNetPortfolioFlow ?? 0) < 0 ? 'text-red-400' : 'text-neutral-400'
                }`}>
                  {(insider.dataromaNetPortfolioFlow ?? 0) > 0 ? '+' : ''}{(insider.dataromaNetPortfolioFlow ?? 0).toFixed(1)}%
                </span>
              </div>
              <div className="flex justify-between">
                <span>Dataroma 점수</span>
                <span className={`font-mono ${
                  (insider.dataromaScore ?? 0) > 0 ? 'text-green-400' : (insider.dataromaScore ?? 0) < 0 ? 'text-red-400' : 'text-neutral-400'
                }`}>{insider.dataromaScore ?? 0}</span>
              </div>
            </div>
            <div className="text-[10px] text-[var(--muted)]">갱신: {insider.lastUpdated}</div>
          </div>
        ) : (
          <div className="text-xs text-[var(--muted)]">로딩 중...</div>
        )}
      </div>

      <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
        <h3 className="text-base sm:text-lg font-semibold mb-1">실적 발표 일정</h3>
        <p className="text-[11px] sm:text-xs text-[var(--muted)] mb-3">향후 5일간 주요 기업 실적 발표</p>

        {earnings.length > 0 ? (
          <div className="space-y-1.5">
            {[...new Map(earnings.map((e) => [`${e.ticker}-${e.date}`, e])).values()].slice(0, 10).map((e, i) => (
              <div key={i} className="flex items-center justify-between rounded-lg bg-[var(--background)] border border-[var(--card-border)] px-3 py-2">
                <span className="text-sm font-mono font-semibold">{e.ticker}</span>
                <span className="text-xs text-[var(--muted)]">{e.date}</span>
              </div>
            ))}
          </div>
        ) : (
          <div className="text-xs text-[var(--muted)]">주요 실적 발표 없음 또는 로딩 중...</div>
        )}
      </div>
    </div>
  );
}
