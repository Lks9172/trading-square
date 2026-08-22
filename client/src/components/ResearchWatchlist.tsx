"use client";
import { SmartLink } from "@/components/SmartLink";

import { useSyncExternalStore } from "react";

const STORAGE_KEY = "macrosquare_research_watchlist";
const WATCHLIST_CHANGED_EVENT = "macrosquare:watchlist-changed";
const EMPTY_WATCHLIST: string[] = [];

let cachedRaw: string | null | undefined;
let cachedWatchlist: string[] = EMPTY_WATCHLIST;

function normalizeWatchlist(value: unknown): string[] {
  if (!Array.isArray(value)) return EMPTY_WATCHLIST;
  return [
    ...new Set(
      value
        .filter((item): item is string => typeof item === "string")
        .map((item) => item.trim().toUpperCase())
        .filter(Boolean),
    ),
  ].slice(0, 20);
}

function getWatchlistSnapshot(): string[] {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (raw === cachedRaw) return cachedWatchlist;
    cachedRaw = raw;
    cachedWatchlist = raw ? normalizeWatchlist(JSON.parse(raw)) : EMPTY_WATCHLIST;
    return cachedWatchlist;
  } catch {
    cachedRaw = null;
    cachedWatchlist = EMPTY_WATCHLIST;
    return cachedWatchlist;
  }
}

function subscribeToWatchlist(onStoreChange: () => void): () => void {
  const onStorage = (event: StorageEvent) => {
    if (event.key === STORAGE_KEY) {
      cachedRaw = undefined;
      onStoreChange();
    }
  };
  const onLocalChange = () => {
    cachedRaw = undefined;
    onStoreChange();
  };

  window.addEventListener("storage", onStorage);
  window.addEventListener(WATCHLIST_CHANGED_EVENT, onLocalChange);
  return () => {
    window.removeEventListener("storage", onStorage);
    window.removeEventListener(WATCHLIST_CHANGED_EVENT, onLocalChange);
  };
}

function saveWatchlist(next: string[]) {
  const normalized = normalizeWatchlist(next);
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(normalized));
  } catch {
    // Storage may be unavailable in private/restricted browsing.
  }
  cachedRaw = undefined;
  window.dispatchEvent(new Event(WATCHLIST_CHANGED_EVENT));
}

export function useResearchWatchlist() {
  const watchlist = useSyncExternalStore(
    subscribeToWatchlist,
    getWatchlistSnapshot,
    () => EMPTY_WATCHLIST,
  );

  function toggle(ticker: string) {
    const normalized = ticker.trim().toUpperCase();
    if (!normalized) return;
    if (watchlist.includes(normalized)) {
      saveWatchlist(watchlist.filter((item) => item !== normalized));
    } else {
      saveWatchlist([...watchlist, normalized]);
    }
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
          <SmartLink
            key={ticker}
            href={`/company/${ticker}`}
            className="rounded-full border border-amber-500/30 bg-amber-500/10 px-3 py-1.5 text-sm text-amber-200 cursor-pointer hover:bg-amber-500/20 active:scale-[0.99]"
          >
            ★ {ticker}
          </SmartLink>
        ))}
      </div>
    </section>
  );
}
