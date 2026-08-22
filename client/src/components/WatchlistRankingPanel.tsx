"use client";
import { SmartLink } from "@/components/SmartLink";

import { useEffect, useMemo, useState } from 'react';
import { useResearchWatchlist } from './ResearchWatchlist';
import { ActionBadge, ScoreBadge } from './ScoreUI';

type SortKey = 'buy' | 'total' | 'growth';

type RankedItem = {
  ticker: string;
  name: string;
  totalScore: number | null;
  buyScore: number | null;
  buyLabel: string | null;
  revenueGrowthYoY: number | null;
  operatingMargin: number | null;
  evToSales: number | null;
  crowdingScore: number | null;
  appealScore: number | null;
  bottomState: string | null;
  earningsBottomScore: number | null;
  priceBottomScore: number | null;
  volumeConfirmationScore: number | null;
  failureRiskScore: number | null;
};

function formatPct(value: number | null) {
  return value === null ? '—' : `${value.toFixed(1)}%`;
}

export function WatchlistRankingPanel() {
  const { watchlist } = useResearchWatchlist();
  const [items, setItems] = useState<RankedItem[]>([]);
  const [sortKey, setSortKey] = useState<SortKey>('buy');
  const [selected, setSelected] = useState<string[]>([]);
  const [compareOpen, setCompareOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    if (!watchlist.length) return;

    const tickers = [...new Set(watchlist.map((item) => item.trim().toUpperCase()).filter(Boolean))];
    fetch(`/api/company-summaries?tickers=${encodeURIComponent(tickers.join(','))}`)
      .then(async (res) => {
        if (!res.ok) return { items: [] };
        return res.json() as Promise<{
          items?: Array<{
            ticker: string;
            name: string;
            totalScore: number | null;
            buyScore: number | null;
            buyLabel: string | null;
            revenueGrowthYoY: number | null;
            operatingMargin: number | null;
            evToSales: number | null;
            crowdingScore: number | null;
            appealScore: number | null;
            bottomState: string | null;
            earningsBottomScore: number | null;
            priceBottomScore: number | null;
            volumeConfirmationScore: number | null;
            failureRiskScore: number | null;
          }>;
        }>;
      })
      .then((data) => {
        if (cancelled) return;
        setItems(Array.isArray(data.items) ? data.items : []);
      })
      .catch(() => {
        if (cancelled) return;
        setItems([]);
      });

    return () => {
      cancelled = true;
    };
  }, [watchlist]);

  const best = useMemo(() => {
    if (!watchlist.length) return [];
    const sorted = [...items].sort((a, b) => {
      if (sortKey === 'total') return (b.totalScore ?? -1) - (a.totalScore ?? -1);
      if (sortKey === 'growth') return (b.revenueGrowthYoY ?? -999) - (a.revenueGrowthYoY ?? -999);
      return (b.buyScore ?? -1) - (a.buyScore ?? -1);
    });
    return sorted.slice(0, 5);
  }, [items, watchlist.length, sortKey]);


  useEffect(() => {
    if (!compareOpen) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, [compareOpen]);

  function toggleSelected(ticker: string) {
    setSelected((prev) => prev.includes(ticker) ? prev.filter((item) => item !== ticker) : [...prev, ticker].slice(0, 4));
  }

  function removeSelected(ticker: string) {
    setSelected((prev) => prev.filter((item) => item !== ticker));
  }

  function moveSelected(ticker: string, direction: -1 | 1) {
    setSelected((prev) => {
      const index = prev.indexOf(ticker);
      if (index < 0) return prev;
      const nextIndex = index + direction;
      if (nextIndex < 0 || nextIndex >= prev.length) return prev;
      const next = [...prev];
      [next[index], next[nextIndex]] = [next[nextIndex], next[index]];
      return next;
    });
  }

  const orderedCompareItems = selected.map((ticker) => items.find((item) => item.ticker === ticker)).filter((item): item is RankedItem => Boolean(item)).slice(0, 4);

  if (!watchlist.length || !best.length) return null;

  return (
    <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
      <div className="mb-3 flex items-center justify-between gap-3">
        <div className="font-semibold text-white">Watchlist 랭킹</div>
        <div className="flex flex-wrap gap-2 text-xs">
          {[['buy','B'],['total','총점'],['growth','성장']].map(([key,label]) => (
            <button
              key={key}
              type="button"
              onClick={() => setSortKey(key as SortKey)}
              className={`rounded-full border px-3 py-1 ${sortKey === key ? 'border-cyan-500/40 bg-cyan-500/10 text-cyan-100' : 'border-white/10 text-white/70 cursor-pointer hover:bg-white/5 active:scale-[0.99]'}`}
            >
              {label}
            </button>
          ))}
        </div>
      </div>
      <div className="space-y-2">
        {best.map((item, index) => (
          <div key={item.ticker} className="flex items-center justify-between gap-3 rounded-lg bg-black/15 px-3 py-2">
            <div>
              <div className="text-xs text-cyan-200">#{index + 1}</div>
              <div className="font-medium text-white">{item.ticker}</div>
              <div className="text-[11px] text-[var(--muted)] break-words">{item.name}</div>
            </div>
            <div className="text-right text-xs flex flex-col items-end gap-1">
              <ScoreBadge label="B" value={item.buyScore} title="B는 지금 사도 되는지 보는 실행 점수입니다." kind="buy" />
              <ActionBadge value={item.buyScore} />
              <div className="mt-1 text-[var(--muted)]">총점 {item.totalScore ?? '—'} · 성장 {formatPct(item.revenueGrowthYoY)}</div>
              <div className="text-[11px] text-white/75">{item.bottomState ?? '바닥 정보 없음'} · 거래량 {item.volumeConfirmationScore ?? '—'} · 가격 {item.priceBottomScore ?? '—'}</div>
              <div className="mt-2 flex gap-2">
                <SmartLink href={`/company/${item.ticker}`} className="rounded-full border border-cyan-500/30 bg-cyan-500/10 px-3 py-1 text-[11px] text-cyan-100 cursor-pointer hover:bg-cyan-500/20 active:scale-[0.99]">상세</SmartLink>
                <button type="button" onClick={() => toggleSelected(item.ticker)} className={`rounded-full border px-3 py-1 text-[11px] ${selected.includes(item.ticker) ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-100' : 'border-white/10 text-white/70 cursor-pointer hover:bg-white/5 active:scale-[0.99]'}`}>비교 {selected.includes(item.ticker) ? '해제' : '추가'}</button>
              </div>
            </div>
          </div>
        ))}
      </div>
      {orderedCompareItems.length >= 2 && (
        <div className="mt-4 flex items-center justify-between gap-3 rounded-xl border border-white/10 bg-black/15 p-4">
          <div>
            <div className="text-sm font-semibold text-white">Watchlist 비교 준비됨</div>
            <div className="text-xs text-[var(--muted)]">{orderedCompareItems.length}개 종목 선택 · 최대 4개 비교</div>
          </div>
          <button
            type="button"
            onClick={() => setCompareOpen(true)}
            className="rounded-full border border-cyan-500/30 bg-cyan-500/10 px-4 py-2 text-xs font-medium text-cyan-100 cursor-pointer hover:bg-cyan-500/20 active:scale-[0.99]"
          >
            비교 열기
          </button>
        </div>
      )}
      {compareOpen && orderedCompareItems.length >= 2 && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4">
          <div className="w-full max-w-5xl rounded-2xl border border-white/10 bg-[var(--card)] p-4 shadow-2xl">
            <div className="mb-3 flex items-center justify-between gap-3">
              <div>
                <div className="text-base font-semibold text-white">Watchlist 비교</div>
                <div className="text-xs text-[var(--muted)]">좋은 회사인지, 비싼지, 과열인지 한 번에 비교합니다.</div>
              </div>
              <button
                type="button"
                onClick={() => setCompareOpen(false)}
                className="rounded-full border border-white/10 px-3 py-1 text-xs text-white/70 cursor-pointer hover:bg-white/5 active:scale-[0.99]"
              >
                닫기
              </button>
            </div>
            <div className="grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-4">
              {orderedCompareItems.map((item, index) => (
                <div key={item.ticker} className="rounded-lg border border-white/10 bg-white/5 p-3 text-xs">
                  <div className="flex items-center justify-between gap-2">
                    <div className="font-medium text-white">#{index + 1} {item.ticker}</div>
                    <ActionBadge value={item.buyScore} compact />
                  </div>
                  <div className="mt-2 flex gap-2">
                    <button type="button" onClick={() => moveSelected(item.ticker, -1)} className="rounded-full border border-white/10 px-2 py-1 text-[10px] text-white/70 cursor-pointer hover:bg-white/5 active:scale-[0.99]">←</button>
                    <button type="button" onClick={() => moveSelected(item.ticker, 1)} className="rounded-full border border-white/10 px-2 py-1 text-[10px] text-white/70 cursor-pointer hover:bg-white/5 active:scale-[0.99]">→</button>
                    <button type="button" onClick={() => removeSelected(item.ticker)} className="rounded-full border border-red-500/20 bg-red-500/10 px-2 py-1 text-[10px] text-red-200 hover:bg-red-500/20">제거</button>
                  </div>
                  <div className="mt-2 space-y-1 text-[var(--muted)]">
                    <div>B {item.buyScore ?? '—'}</div>
                    <div>총점 {item.totalScore ?? '—'}</div>
                    <div>성장 {formatPct(item.revenueGrowthYoY)}</div>
                    <div>마진 {item.operatingMargin === null ? '—' : `${item.operatingMargin.toFixed(1)}%`}</div>
                    <div>EV/Sales {item.evToSales === null ? '—' : `${item.evToSales.toFixed(1)}x`}</div>
                    <div>매력 {item.appealScore ?? '—'} · 과열 {item.crowdingScore ?? '—'}</div>
                    <div>바닥 {item.bottomState ?? '—'}</div>
                    <div>거래량확인 {item.volumeConfirmationScore ?? '—'} · 가격바닥 {item.priceBottomScore ?? '—'}</div>
                    <div>실패위험 {item.failureRiskScore ?? '—'}</div>
                  </div>
                  <div className="mt-3">
                    <SmartLink href={`/company/${item.ticker}`} className="text-[11px] text-cyan-300 cursor-pointer hover:text-cyan-200">상세 보기 →</SmartLink>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
    </section>
  );
}
