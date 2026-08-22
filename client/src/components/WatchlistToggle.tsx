"use client";

import { useMemo } from "react";
import { useResearchWatchlist } from "./ResearchWatchlist";

export function WatchlistToggle({ ticker }: { ticker: string }) {
  const { watchlist, toggle } = useResearchWatchlist();
  const active = useMemo(() => watchlist.includes(ticker.toUpperCase()), [watchlist, ticker]);

  return (
    <button
      type="button"
      onClick={() => toggle(ticker)}
      className={`touch-manipulation rounded-full border px-3 py-1 text-xs ${active ? "border-amber-500/40 bg-amber-500/15 text-amber-200" : "border-white/10 bg-white/5 text-slate-300"}`}
    >
      {active ? "★ Watchlist" : "☆ Watchlist"}
    </button>
  );
}
