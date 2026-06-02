"use client";

import { FormEvent, useEffect, useState } from "react";
import { useRouter } from "next/navigation";

interface Props {
  initialTicker?: string;
}

interface Suggestion {
  ticker: string;
  cik: string;
  title: string;
}

export function CompanySearchBox({ initialTicker = "" }: Props) {
  const router = useRouter();
  const [ticker, setTicker] = useState(initialTicker);
  const [suggestions, setSuggestions] = useState<Suggestion[]>([]);

  useEffect(() => {
    const q = ticker.trim();
    if (q.length < 1) {
      return;
    }
    const timer = setTimeout(async () => {
      try {
        const res = await fetch(`/api/company-search?q=${encodeURIComponent(q)}&limit=6`, { cache: "no-store" });
        if (!res.ok) return;
        const data = await res.json() as { items?: Suggestion[] };
        setSuggestions(Array.isArray(data.items) ? data.items : []);
      } catch {
        setSuggestions([]);
      }
    }, 180);
    return () => clearTimeout(timer);
  }, [ticker]);

  function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const normalized = ticker.trim().toUpperCase();
    if (!normalized) return;
    router.push(`/company/${encodeURIComponent(normalized)}`);
    setSuggestions([]);
  }

  return (
    <div className="space-y-2">
      <form onSubmit={onSubmit} className="flex flex-col sm:flex-row gap-2">
        <input
          value={ticker}
          onChange={(e) => {
            const next = e.target.value.toUpperCase();
            setTicker(next);
            if (next.trim().length < 1) setSuggestions([]);
          }}
          placeholder="예: NVDA, MSFT, ASML"
          className="flex-1 rounded-xl border border-[var(--card-border)] bg-black/20 px-3 py-2 text-sm outline-none focus:border-cyan-500/50"
        />
        <button
          type="submit"
          className="rounded-xl border border-cyan-500/40 bg-cyan-500/15 px-4 py-2 text-sm font-medium text-cyan-200 hover:bg-cyan-500/25"
        >
          기업 보기
        </button>
      </form>

      {suggestions.length > 0 && (
        <div className="rounded-xl border border-[var(--card-border)] bg-black/20 overflow-hidden">
          {suggestions.map((item) => (
            <button
              key={`${item.ticker}-${item.cik}`}
              type="button"
              onClick={() => {
                setTicker(item.ticker);
                setSuggestions([]);
                router.push(`/company/${encodeURIComponent(item.ticker)}`);
              }}
              className="w-full px-3 py-2 text-left hover:bg-white/5 border-b border-white/5 last:border-b-0"
            >
              <div className="text-sm font-medium text-white">{item.ticker}</div>
              <div className="text-xs text-[var(--muted)]">{item.title}</div>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
