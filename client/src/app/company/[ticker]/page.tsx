import Link from "next/link";
import { CompanySearchBox } from "@/components/CompanySearchBox";
import { WatchlistToggle } from "@/components/WatchlistToggle";

export const dynamic = "force-dynamic";

const SSR_API_URL = process.env.SSR_API_URL || "http://localhost:5846";

interface CompanyResearchResponse {
  profile: {
    ticker: string;
    cik: string;
    name: string;
    exchange?: string | null;
    sic?: string | null;
  };
  quote: {
    symbol: string;
    price: number | null;
    date: string | null;
  };
  financials: {
    asOf: string;
    revenueTtm: number | null;
    operatingIncomeTtm: number | null;
    netIncomeTtm: number | null;
    freeCashFlowTtm: number | null;
    cash: number | null;
    debt: number | null;
    sharesOutstanding: number | null;
    marketCap: number | null;
    enterpriseValue: number | null;
    revenueGrowthYoY: number | null;
    operatingMargin: number | null;
    operatingMarginTrend: number | null;
    freeCashFlowMargin: number | null;
    netDebtToRevenue: number | null;
    evToSales: number | null;
    evToFcf: number | null;
    shareDilutionYoY: number | null;
    stockCompToRevenue: number | null;
    roe: number | null;
    estimateUpsidePct: number | null;
    estimateRevision30d?: number | null;
    analystScore?: number | null;
    segmentGeoMixNote?: string | null;
  };
  score: {
    totalScore: number;
    growth: { value: number; reasons: string[] };
    quality: { value: number; reasons: string[] };
    valuation: { value: number; reasons: string[] };
    balanceSheet: { value: number; reasons: string[] };
    reasons: string[];
  };
  filings: Array<{
    accessionNumber: string;
    form: string;
    filingDate: string;
    primaryDocument?: string | null;
    primaryDocDescription?: string | null;
    isEarningsRelated?: boolean;
    filingUrl?: string | null;
    summary?: string | null;
    guidanceSignals?: string[];
  }>;
  irMaterials: Array<{
    title: string;
    form: string;
    filingDate: string;
    url: string;
    type: 'presentation' | 'earnings-release' | 'annual-report' | 'quarterly-report' | 'other';
  }>
  highlights: string[];
  peers: Array<{
    ticker: string;
    name: string;
    relation: string;
    peerGroup?: string;
    totalScore: number | null;
    revenueGrowthYoY: number | null;
    operatingMargin: number | null;
    evToSales: number | null;
    rank?: number | null;
    percentile?: number | null;
  }>;
}

function fmtNum(value: number | null, digits = 1) {
  if (value === null || Number.isNaN(value)) return "—";
  return value.toLocaleString("en-US", { maximumFractionDigits: digits, minimumFractionDigits: digits });
}

function fmtPct(value: number | null) {
  if (value === null || Number.isNaN(value)) return "—";
  return `${value.toFixed(1)}%`;
}

function fmtSignedPctPoint(value: number | null | undefined) {
  if (value === null || value === undefined || Number.isNaN(value)) return "—";
  return `${value >= 0 ? '+' : ''}${value.toFixed(1)}%p`;
}

async function fetchCompany(ticker: string): Promise<CompanyResearchResponse | null> {
  try {
    const res = await fetch(`${SSR_API_URL}/api/company/${encodeURIComponent(ticker)}`, {
      cache: "no-store",
    });
    if (!res.ok) return null;
    return res.json();
  } catch {
    return null;
  }
}

function scoreTone(score: number) {
  if (score >= 75) return "text-green-300 border-green-500/30 bg-green-500/10";
  if (score >= 55) return "text-cyan-300 border-cyan-500/30 bg-cyan-500/10";
  if (score >= 40) return "text-yellow-300 border-yellow-500/30 bg-yellow-500/10";
  return "text-red-300 border-red-500/30 bg-red-500/10";
}

export default async function CompanyPage({
  params,
}: {
  params: Promise<{ ticker: string }>;
}) {
  const { ticker } = await params;
  const data = await fetchCompany(ticker.toUpperCase());

  if (!data) {
    return (
      <main className="flex-1 p-4 md:p-6 max-w-6xl mx-auto w-full">
        <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-6">
          <div className="text-lg font-semibold mb-2">기업 데이터를 불러오지 못했습니다</div>
          <p className="text-sm text-[var(--muted)] mb-4">
            티커가 SEC 매핑에 없거나, 현재 공시 데이터를 불러오지 못한 상태입니다.
          </p>
          <Link href="/research" className="text-cyan-300 hover:text-cyan-200 underline">
            ← 리서치 홈으로
          </Link>
        </div>
      </main>
    );
  }

  const scoreCards = [
    ["성장", data.score.growth.value, data.score.growth.reasons[0]],
    ["수익성", data.score.quality.value, data.score.quality.reasons[0]],
    ["밸류", data.score.valuation.value, data.score.valuation.reasons[0]],
    ["재무", data.score.balanceSheet.value, data.score.balanceSheet.reasons[0]],
  ] as const;

  const metrics = [
    ["매출 TTM", fmtNum(data.financials.revenueTtm), ""],
    ["영업이익 TTM", fmtNum(data.financials.operatingIncomeTtm), ""],
    ["순이익 TTM", fmtNum(data.financials.netIncomeTtm), ""],
    ["FCF TTM", fmtNum(data.financials.freeCashFlowTtm), ""],
    ["매출 YoY", fmtPct(data.financials.revenueGrowthYoY), ""],
    ["영업이익률", fmtPct(data.financials.operatingMargin), ""],
    ["FCF 마진", fmtPct(data.financials.freeCashFlowMargin), ""],
    ["EV/Sales", fmtNum(data.financials.evToSales), "x"],
    ["EV/FCF", fmtNum(data.financials.evToFcf), "x"],
    ["주식수 희석 YoY", fmtPct(data.financials.shareDilutionYoY), ""],
    ["주식보상/매출", fmtPct(data.financials.stockCompToRevenue), ""],
    ["ROE", fmtPct(data.financials.roe), ""],
    ["마진 추세", data.financials.operatingMarginTrend !== null ? `${data.financials.operatingMarginTrend.toFixed(1)}%p` : "—", ""],
    ["애널리스트 업사이드", fmtPct(data.financials.estimateUpsidePct), ""],
    ["30일 컨센서스 변화", fmtSignedPctPoint(data.financials.estimateRevision30d), ""],
    ["애널리스트 점수", data.financials.analystScore !== null && data.financials.analystScore !== undefined ? data.financials.analystScore.toFixed(2) : "—", ""],
    ["시가총액", fmtNum(data.financials.marketCap), ""],
    ["현금", fmtNum(data.financials.cash), ""],
    ["부채", fmtNum(data.financials.debt), ""],
  ] as const;

  return (
    <main className="flex-1 p-4 md:p-6 max-w-6xl mx-auto w-full">
      <div className="space-y-6">
        <header className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <div className="text-xs text-[var(--muted)] mb-1">
              <Link href="/research" className="hover:text-white">리서치</Link> / {data.profile.ticker}
            </div>
            <h1 className="text-2xl sm:text-3xl font-bold tracking-tight">{data.profile.name}</h1>
            <div className="mt-1 text-sm text-[var(--muted)]">
              {data.profile.ticker} · CIK {data.profile.cik}
              {data.profile.exchange ? ` · ${data.profile.exchange}` : ""}
              {data.profile.sic ? ` · SIC ${data.profile.sic}` : ""}
            </div>
          </div>
          <div className="text-right space-y-2">
            <div className="text-xs text-[var(--muted)]">현재가</div>
            <div className="text-2xl font-semibold">{data.quote.price !== null ? fmtNum(data.quote.price, 2) : "—"}</div>
            <div className="text-xs text-[var(--muted)]">{data.quote.date ?? data.financials.asOf}</div>
            <div className="flex justify-end">
              <WatchlistToggle ticker={data.profile.ticker} />
            </div>
          </div>
        </header>

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="mb-3 text-sm font-semibold text-white">다른 티커 보기</div>
          <CompanySearchBox initialTicker={data.profile.ticker} />
        </section>

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="flex items-center justify-between gap-3 mb-3">
            <div>
              <div className="text-sm text-[var(--muted)]">종합 점수</div>
              <div className="text-3xl font-bold">{data.score.totalScore}/100</div>
            </div>
            <div className={`rounded-full border px-3 py-1 text-sm ${scoreTone(data.score.totalScore)}`}>
              {data.score.totalScore >= 75 ? "상대 우수" : data.score.totalScore >= 55 ? "보통 이상" : data.score.totalScore >= 40 ? "중립" : "주의"}
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            {data.highlights.map((item) => (
              <span key={item} className="rounded-full border border-white/10 bg-white/5 px-3 py-1 text-xs text-slate-200">
                {item}
              </span>
            ))}
          </div>
        </section>

        <section className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-3">
          {scoreCards.map(([label, score, reason]) => (
            <div key={label} className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-4">
              <div className="text-xs text-[var(--muted)] mb-1">{label}</div>
              <div className="text-2xl font-semibold mb-2">{score}</div>
              <div className="text-xs leading-relaxed text-[var(--muted)] break-words">{reason || "—"}</div>
            </div>
          ))}
        </section>

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="text-lg font-semibold mb-4">핵심 재무 지표</div>
          <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-3 gap-3">
            {metrics.map(([label, value, suffix]) => (
              <div key={label} className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">{label}</div>
                <div className="mt-1 text-lg font-semibold">
                  {value}{suffix}
                </div>
              </div>
            ))}
          </div>
        </section>

        <section className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="text-lg font-semibold mb-3">Segment / Geo mix</div>
            <div className="text-sm leading-relaxed text-[var(--muted)] break-words">
              {data.financials.segmentGeoMixNote || "최근 10-K/10-Q에서 자동 추출된 세그먼트/지역 믹스 요약이 아직 없습니다."}
            </div>
          </div>

          <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="text-lg font-semibold mb-3">IR / 발표자료</div>
            {data.irMaterials.length === 0 ? (
              <div className="text-sm text-[var(--muted)]">표시할 IR/발표자료 후보가 없습니다.</div>
            ) : (
              <div className="space-y-2">
                {data.irMaterials.map((item) => (
                  <a
                    key={`${item.url}-${item.filingDate}`}
                    href={item.url}
                    target="_blank"
                    rel="noreferrer"
                    className="block rounded-xl border border-white/10 bg-black/15 p-3 hover:bg-black/25"
                  >
                    <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between">
                      <div className="font-medium break-words">{item.title}</div>
                      <div className="text-xs text-[var(--muted)]">{item.filingDate}</div>
                    </div>
                    <div className="mt-1 flex flex-wrap gap-1">
                      <span className="rounded-full border border-cyan-500/30 bg-cyan-500/10 px-2 py-0.5 text-[10px] text-cyan-200">
                        {item.type}
                      </span>
                      <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-[10px] text-slate-300">
                        {item.form}
                      </span>
                    </div>
                  </a>
                ))}
              </div>
            )}
          </div>
        </section>

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="text-lg font-semibold mb-3">최근 공시</div>
          <div className="space-y-2">
            {data.filings.slice(0, 10).map((filing) => (
              <div key={filing.accessionNumber} className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between">
                  <div className="font-medium">
                    {filing.form}
                    {filing.isEarningsRelated ? (
                      <span className="ml-2 rounded-full border border-cyan-500/30 bg-cyan-500/10 px-2 py-0.5 text-[10px] text-cyan-200">
                        earnings
                      </span>
                    ) : null}
                  </div>
                  <div className="text-xs text-[var(--muted)]">{filing.filingDate}</div>
                </div>
                <div className="mt-1 text-sm text-[var(--muted)] break-words">
                  {filing.summary || filing.primaryDocDescription || filing.primaryDocument || filing.accessionNumber}
                </div>
                {filing.guidanceSignals && filing.guidanceSignals.length > 0 && (
                  <div className="mt-2 flex flex-wrap gap-1">
                    {filing.guidanceSignals.map((signal) => (
                      <span key={signal} className="rounded-full border border-amber-500/30 bg-amber-500/10 px-2 py-0.5 text-[10px] text-amber-200">
                        {signal}
                      </span>
                    ))}
                  </div>
                )}
                {filing.filingUrl && (
                  <div className="mt-2">
                    <a href={filing.filingUrl} target="_blank" rel="noreferrer" className="text-xs text-cyan-300 hover:text-cyan-200 underline">
                      SEC 원문 보기
                    </a>
                  </div>
                )}
              </div>
            ))}
          </div>
        </section>

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="flex items-center justify-between gap-3 mb-3">
            <div className="text-lg font-semibold">Peer comparison</div>
            <div className="text-xs text-[var(--muted)]">산업군/테마 기반 peer universe</div>
          </div>
          {data.peers.length === 0 ? (
            <div className="text-sm text-[var(--muted)]">현재 등록된 peer가 없습니다.</div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
              {data.peers.map((peer) => (
                <Link
                  key={peer.ticker}
                  href={`/company/${peer.ticker}`}
                  className="rounded-xl border border-white/10 bg-black/15 p-4 hover:bg-black/25"
                >
                  <div className="flex items-start justify-between gap-2 mb-2">
                    <div>
                      <div className="font-semibold">{peer.ticker}</div>
                      <div className="text-xs text-[var(--muted)] line-clamp-2">{peer.name}</div>
                    </div>
                    <div className={`rounded-full border px-2 py-0.5 text-xs ${scoreTone(peer.totalScore ?? 0)}`}>
                      {peer.totalScore ?? "—"}
                    </div>
                  </div>
                  <div className="text-xs text-cyan-200 mb-2">{peer.relation}</div>
                  <div className="mb-2 text-[10px] text-[var(--muted)]">
                    {peer.rank ? `Peer rank #${peer.rank}` : "Rank N/A"}
                    {peer.percentile ? ` · 상위 ${peer.percentile}%` : ""}
                  </div>
                  <div className="space-y-1 text-xs text-[var(--muted)]">
                    <div>매출 YoY: {fmtPct(peer.revenueGrowthYoY)}</div>
                    <div>영업이익률: {fmtPct(peer.operatingMargin)}</div>
                    <div>EV/Sales: {fmtNum(peer.evToSales)}x</div>
                  </div>
                </Link>
              ))}
            </div>
          )}
        </section>
      </div>
    </main>
  );
}
