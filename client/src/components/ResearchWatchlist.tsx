"use client";

import Link from "next/link";
import { useState } from "react";

const STORAGE_KEY = "macrosquare_research_watchlist";

export function useResearchWatchlist() {
  const [watchlist, setWatchlist] = useState<string[]>(() => {
    if (typeof window === "undefined") return [];
    try {
      const raw = window.localStorage.getItem(STORAGE_KEY);
      if (!raw) return [];
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed.filter((item) => typeof item === "string") : [];
    } catch {
      return [];
    }
  });

  function save(next: string[]) {
    setWatchlist(next);
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } catch {
      // noop
    }
  }

  function toggle(ticker: string) {
    const normalized = ticker.toUpperCase();
    if (watchlist.includes(normalized)) save(watchlist.filter((item) => item !== normalized));
    else save([...watchlist, normalized].slice(0, 20));
  }

  return { watchlist, toggle };
}

export function ResearchWatchlist() {
  const { watchlist } = useResearchWatchlist();

  if (watchlist.length === 0) {
    return (
      <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5 text-sm text-[var(--muted)]">
        <div className="font-semibold text-white mb-2">Watchlist</div>
        아직 저장한 기업이 없습니다. 회사 상세 페이지에서 별표 버튼으로 추가할 수 있습니다.
      </section>
    );
  }

  return (
    <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
      <div className="font-semibold text-white mb-3">Watchlist</div>
      <div className="flex flex-wrap gap-2">
        {watchlist.map((ticker) => (
          <Link
            key={ticker}
            href={`/company/${ticker}`}
            className="rounded-full border border-amber-500/30 bg-amber-500/10 px-3 py-1.5 text-sm text-amber-200 hover:bg-amber-500/20"
          >
            ★ {ticker}
          </Link>
        ))}
      </div>
    </section>
  );
}
