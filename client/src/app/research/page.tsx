import Link from "next/link";
import { CompanySearchBox } from "@/components/CompanySearchBox";
import { ResearchWatchlist } from "@/components/ResearchWatchlist";

const SAMPLE_TICKERS = ["NVDA", "MSFT", "GOOGL", "META", "ASML", "AVGO", "TSM"];
const SSR_API_URL = process.env.SSR_API_URL || "http://localhost:5846";

export const dynamic = "force-dynamic";

async function fetchThemes(): Promise<Array<{ theme: string; description: string; tickers: string[] }>> {
  try {
    const res = await fetch(`${SSR_API_URL}/api/research/themes`, { cache: "no-store" });
    if (!res.ok) return [];
    const data = await res.json() as { themes?: Array<{ theme: string; description: string; tickers: string[] }> };
    return Array.isArray(data.themes) ? data.themes : [];
  } catch {
    return [];
  }
}

export default async function ResearchPage() {
  const themes = await fetchThemes();
  return (
    <main className="flex-1 p-4 md:p-6 max-w-6xl mx-auto w-full">
      <div className="space-y-6">
        <header className="space-y-2">
          <h1 className="text-2xl font-bold tracking-tight">기업 리서치</h1>
          <p className="text-sm text-[var(--muted)]">
            SEC 공시 기반 바텀업 팩트 레이어의 1차 MVP입니다. 재무/밸류/최근 공시를 빠르게 확인할 수 있습니다.
          </p>
        </header>

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="mb-3 text-sm font-semibold text-white">티커 검색</div>
          <CompanySearchBox />
        </section>

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="mb-3 text-sm font-semibold text-white">빠른 이동</div>
          <div className="flex flex-wrap gap-2">
            {SAMPLE_TICKERS.map((ticker) => (
              <Link
                key={ticker}
                href={`/company/${ticker}`}
                className="rounded-full border border-cyan-500/30 bg-cyan-500/10 px-3 py-1.5 text-sm text-cyan-200 hover:bg-cyan-500/20"
              >
                {ticker}
              </Link>
            ))}
          </div>
        </section>

        <ResearchWatchlist />

        {themes.length > 0 && (
          <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="mb-3 text-sm font-semibold text-white">테마/섹터 대표 기업</div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {themes.map((theme) => (
                <div key={theme.theme} className="rounded-xl border border-white/10 bg-black/15 p-4">
                  <div className="font-medium text-white">{theme.theme}</div>
                  <div className="mt-1 text-xs text-[var(--muted)]">{theme.description}</div>
                  <div className="mt-3 flex flex-wrap gap-2">
                    {theme.tickers.map((ticker) => (
                      <Link
                        key={ticker}
                        href={`/company/${ticker}`}
                        className="rounded-full border border-cyan-500/30 bg-cyan-500/10 px-2.5 py-1 text-xs text-cyan-200 hover:bg-cyan-500/20"
                      >
                        {ticker}
                      </Link>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </section>
        )}

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5 text-sm text-[var(--muted)]">
          <div className="mb-2 font-semibold text-white">현재 포함 범위</div>
          <ul className="list-disc pl-5 space-y-1">
            <li>SEC company facts 기반 TTM/마진/성장률 계산</li>
            <li>EV/Sales, EV/FCF, 순현금/순부채 점수화</li>
            <li>최근 10-K / 10-Q / 8-K filing 목록 제공</li>
          </ul>
        </section>
      </div>
    </main>
  );
}
