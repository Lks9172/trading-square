import { CompanySearchBox } from "@/components/CompanySearchBox";
import { ResearchWatchlist } from "@/components/ResearchWatchlist";
import { WatchlistRankingPanel } from "@/components/WatchlistRankingPanel";
import { SmartLink } from "@/components/SmartLink";
import { DartDisclosurePanel } from "@/components/DartDisclosurePanel";
import { fetchServerJson } from "@/lib/server-api";

const SAMPLE_TICKERS = ["NVDA", "MSFT", "GOOGL", "META", "ASML", "AVGO", "TSM", "VRT", "LMT", "LLY"];
export const revalidate = 300;
export const dynamic = "force-dynamic";

async function fetchThemes(): Promise<Array<{ id: string; theme: string; description: string; tickers: string[]; sectorSummary?: { averageBuyScore?: number | null; averageBottomScore?: number | null; averageBottomFailureRiskScore?: number | null; averageAppealScore?: number | null; averageCrowdingScore?: number | null; averageQualityScore?: number | null; topSector?: { label?: string; buyScore?: number | null; buyLabel?: string | null; bottomState?: string | null; bottomScore?: number | null; bottomFailureRiskScore?: number | null; actionLabel?: string | null; failureSummary?: string | null } | null } }>> {
  const data = await fetchServerJson<{ themes?: Array<{ id: string; theme: string; description: string; tickers: string[]; sectorSummary?: { averageBuyScore?: number | null; averageBottomScore?: number | null; averageBottomFailureRiskScore?: number | null; averageAppealScore?: number | null; averageCrowdingScore?: number | null; averageQualityScore?: number | null; topSector?: { label?: string; buyScore?: number | null; buyLabel?: string | null; bottomState?: string | null; bottomScore?: number | null; bottomFailureRiskScore?: number | null; actionLabel?: string | null; failureSummary?: string | null } | null } }> }>('/api/research/themes', { revalidate: 300 });
  return Array.isArray(data?.themes) ? data.themes : [];
}

async function fetchStandardSectors(): Promise<Array<{ id: string; label: string; description: string; sectorSummary?: { averageBuyScore?: number | null; averageBottomScore?: number | null; averageBottomFailureRiskScore?: number | null; averageQualityScore?: number | null; averageCrowdingScore?: number | null } | null }>> {
  const data = await fetchServerJson<{ sectors?: Array<{ id: string; label: string; description: string; sectorSummary?: { averageBuyScore?: number | null; averageBottomScore?: number | null; averageBottomFailureRiskScore?: number | null; averageQualityScore?: number | null; averageCrowdingScore?: number | null } | null }> }>('/api/research/sectors', { revalidate: 300 });
  return Array.isArray(data?.sectors) ? data.sectors : [];
}


async function fetchBottleneckThemes(): Promise<Array<{ id: string; title: string; description: string }>> {
  const data = await fetchServerJson<{ themes?: Array<{ id: string; title: string; description: string }> }>('/api/bottleneck/themes', { revalidate: 900 });
  return Array.isArray(data?.themes) ? data.themes : [];
}

async function fetchNarrativeOverview(): Promise<Array<{ theme: { id: string; title: string; description: string }; stage: string; heatScore: number; sourceStatus?: string; sourceQualityScore?: number; sourceCoveragePct?: number; sourceRevisionCount?: number; sourceFailureCount?: number; sourceLastRefreshAt?: string | null }>> {
  const data = await fetchServerJson<{ themes?: Array<{ theme: { id: string; title: string; description: string }; stage: string; heatScore: number; sourceStatus?: string; sourceQualityScore?: number; sourceCoveragePct?: number; sourceRevisionCount?: number; sourceFailureCount?: number; sourceLastRefreshAt?: string | null }> }>('/api/narrative/overview', { revalidate: 300 });
  return Array.isArray(data?.themes) ? data.themes : [];
}

export default async function ResearchPage() {
  const [themes, standardSectors, bottleneckThemes, narrativeThemes] = await Promise.all([fetchThemes(), fetchStandardSectors(), fetchBottleneckThemes(), fetchNarrativeOverview()]);
  return (
    <main className="flex-1 p-4 md:p-6 max-w-6xl mx-auto w-full">
      <div className="space-y-6">
        <header className="space-y-2">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
            <div>
              <h1 className="text-2xl font-bold tracking-tight">기업 리서치</h1>
              <p className="text-sm text-[var(--muted)]">
                SEC 공시 기반 바텀업 팩트 레이어의 1차 MVP입니다. 재무/밸류/최근 공시를 빠르게 확인할 수 있습니다.
              </p>
            </div>
            <div className="relative z-10 flex flex-wrap gap-2">
              <SmartLink href="/research/companies" className="inline-flex cursor-pointer rounded-full border border-cyan-500/30 bg-cyan-500/10 px-4 py-2 text-sm text-cyan-200 cursor-pointer hover:bg-cyan-500/20 active:scale-[0.99]">전체 기업 리스트 보기</SmartLink>
              <SmartLink href="/research/sectors" className="inline-flex cursor-pointer rounded-full border border-white/10 bg-white/5 px-4 py-2 text-sm text-white/80 hover:bg-white/10">표준 11개 섹터 보기</SmartLink>
              <SmartLink href="/research/crypto" className="inline-flex cursor-pointer rounded-full border border-amber-500/30 bg-amber-500/10 px-4 py-2 text-sm text-amber-200 cursor-pointer hover:bg-amber-500/20 active:scale-[0.99]">코인 탭 보기</SmartLink>
            </div>
          </div>
        </header>

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="mb-3 text-sm font-semibold text-white">티커 검색</div>
          <CompanySearchBox />
        </section>

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="mb-3 text-sm font-semibold text-white">빠른 이동</div>
          <div className="flex flex-wrap gap-2">
            {SAMPLE_TICKERS.map((ticker) => (
              <SmartLink
                key={ticker}
                href={`/company/${ticker}`}
                className="rounded-full border border-cyan-500/30 bg-cyan-500/10 px-3 py-1.5 text-sm text-cyan-200 cursor-pointer hover:bg-cyan-500/20 active:scale-[0.99]"
              >
                {ticker}
              </SmartLink>
            ))}
          </div>
        </section>

        <ResearchWatchlist />
        <WatchlistRankingPanel />
        <DartDisclosurePanel />

        {standardSectors.length > 0 && (
          <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="mb-3 flex items-center justify-between">
              <div className="text-sm font-semibold text-white">표준 11개 섹터</div>
              <SmartLink href="/research/sectors" className="text-xs text-cyan-200 hover:text-cyan-100">전체 보기 →</SmartLink>
            </div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {standardSectors.map((sector) => (
                <SmartLink key={sector.id} href={`/research/sector/${sector.id}`} className="rounded-xl border border-white/10 bg-black/15 p-4 cursor-pointer hover:bg-white/5 active:scale-[0.99]">
                  <div className="font-medium text-white">{sector.label}</div>
                  <div className="mt-1 text-xs text-[var(--muted)]">{sector.description}</div>
                  <div className="mt-2 flex flex-wrap gap-2 text-[10px]">
                    {typeof sector.sectorSummary?.averageBuyScore === 'number' && <span className="rounded-full border border-emerald-500/20 bg-emerald-500/10 px-2 py-0.5 text-emerald-100">B {sector.sectorSummary.averageBuyScore}</span>}
                    {typeof sector.sectorSummary?.averageBottomScore === 'number' && <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-0.5 text-cyan-100">바닥 {sector.sectorSummary.averageBottomScore}</span>}
                    {typeof sector.sectorSummary?.averageBottomFailureRiskScore === 'number' && <span className="rounded-full border border-rose-500/20 bg-rose-500/10 px-2 py-0.5 text-rose-100">실패위험 {sector.sectorSummary.averageBottomFailureRiskScore}</span>}
                    {typeof sector.sectorSummary?.averageQualityScore === 'number' && <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-0.5 text-cyan-100">Q {sector.sectorSummary.averageQualityScore}</span>}
                    {typeof sector.sectorSummary?.averageCrowdingScore === 'number' && <span className="rounded-full border border-amber-500/20 bg-amber-500/10 px-2 py-0.5 text-amber-100">과열 {sector.sectorSummary.averageCrowdingScore}</span>}
                  </div>
                </SmartLink>
              ))}
            </div>
          </section>
        )}

        {themes.length > 0 && (
          <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="mb-3 text-sm font-semibold text-white">전략 테마 대표 기업</div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {themes.map((theme) => (
                <div key={theme.theme} className="rounded-xl border border-white/10 bg-black/15 p-4">
                  <div className="font-medium text-white">{theme.theme}</div>
                  <div className="mt-1 text-xs text-[var(--muted)]">{theme.description}</div>
                  <div className="mt-2 flex flex-wrap gap-2 text-[10px]">
                    <span className="text-cyan-200">대표 {theme.tickers.length}종목</span>
                    {typeof theme.sectorSummary?.averageBuyScore === 'number' && <span className="rounded-full border border-emerald-500/20 bg-emerald-500/10 px-2 py-0.5 text-emerald-100">섹터 B {theme.sectorSummary.averageBuyScore}</span>}
                    {typeof theme.sectorSummary?.averageBottomScore === 'number' && <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-0.5 text-cyan-100">바닥 {theme.sectorSummary.averageBottomScore}</span>}
                    {typeof theme.sectorSummary?.averageBottomFailureRiskScore === 'number' && <span className="rounded-full border border-rose-500/20 bg-rose-500/10 px-2 py-0.5 text-rose-100">실패위험 {theme.sectorSummary.averageBottomFailureRiskScore}</span>}
                    {typeof theme.sectorSummary?.averageQualityScore === 'number' && <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-0.5 text-cyan-100">Q {theme.sectorSummary.averageQualityScore}</span>}
                    {typeof theme.sectorSummary?.averageCrowdingScore === 'number' && <span className="rounded-full border border-amber-500/20 bg-amber-500/10 px-2 py-0.5 text-amber-100">과열 {theme.sectorSummary.averageCrowdingScore}</span>}
                  </div>
                  {theme.sectorSummary?.topSector?.actionLabel ? (
                    <div className="mt-2 text-[11px] text-white/75">
                      액션: {theme.sectorSummary.topSector.actionLabel}
                      {theme.sectorSummary.topSector.failureSummary ? ` · ${theme.sectorSummary.topSector.failureSummary}` : ''}
                    </div>
                  ) : null}
                  <div className="mt-3 flex flex-wrap gap-2">
                    {theme.tickers.slice(0, 10).map((ticker) => (
                      <SmartLink
                        key={ticker}
                        href={`/company/${ticker}`}
                        className="rounded-full border border-cyan-500/30 bg-cyan-500/10 px-2.5 py-1 text-xs text-cyan-200 cursor-pointer hover:bg-cyan-500/20 active:scale-[0.99]"
                      >
                        {ticker}
                      </SmartLink>
                    ))}
                  </div>
                  {theme.sectorSummary?.topSector?.label && (
                    <div className="mt-2 text-[11px] text-[var(--muted)]">
                      대표 섹터: <span className="text-white">{theme.sectorSummary.topSector.label}</span>
                      {typeof theme.sectorSummary.topSector.buyScore === 'number' ? ` · B ${theme.sectorSummary.topSector.buyScore}` : ''}
                      {typeof theme.sectorSummary.topSector.bottomScore === 'number' ? ` · 바닥 ${theme.sectorSummary.topSector.bottomScore}` : ''}
                      {theme.sectorSummary.topSector.bottomState ? ` · ${theme.sectorSummary.topSector.bottomState}` : ''}
                      {theme.sectorSummary.topSector.buyLabel ? ` · ${theme.sectorSummary.topSector.buyLabel}` : ''}
                    </div>
                  )}
                  <div className="mt-4">
                    <SmartLink
                      href={`/research/theme/${theme.id}`}
                      className="inline-flex rounded-full border border-white/10 px-3 py-1 text-xs text-white/80 hover:bg-white/10"
                    >
                      랭킹 보기
                    </SmartLink>
                  </div>
                </div>
              ))}
            </div>
          </section>
        )}

        {bottleneckThemes.length > 0 && (
          <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="mb-3 text-sm font-semibold text-white">병목 후보 테마</div>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
              {bottleneckThemes.map((theme) => (
                <SmartLink key={theme.id} href={`/research/bottleneck/${theme.id}`} className="rounded-xl border border-white/10 bg-black/15 p-4 cursor-pointer hover:bg-white/5 active:scale-[0.99]">
                  <div className="font-medium text-white">{theme.title}</div>
                  <div className="mt-1 text-xs text-[var(--muted)] break-words">{theme.description}</div>
                  <div className="mt-3 text-xs text-cyan-200">랭킹 보기 →</div>
                </SmartLink>
              ))}
            </div>
          </section>
        )}

        {narrativeThemes.length > 0 && (
          <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="mb-3 text-sm font-semibold text-white">내러티브 열기</div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {narrativeThemes.map((item) => (
                <SmartLink key={item.theme.id} href={`/research/narrative/${item.theme.id}`} className="rounded-xl border border-white/10 bg-black/15 p-4 cursor-pointer hover:bg-white/5 active:scale-[0.99]">
                  <div className="flex items-center justify-between gap-3">
                    <div>
                      <div className="font-medium text-white">{item.theme.title}</div>
                      <div className="mt-1 text-xs text-[var(--muted)] break-words">{item.theme.description}</div>
                    </div>
                    <div className="rounded-full border border-cyan-500/30 bg-cyan-500/10 px-3 py-1 text-xs text-cyan-100">{item.heatScore}점</div>
                  </div>
                  <div className="mt-3 flex flex-wrap gap-2 text-xs">
                    <span className="text-cyan-200">{item.stage}</span>
                    <span className={`rounded-full border px-2 py-0.5 text-[10px] ${item.sourceStatus === 'HEALTHY' ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100' : item.sourceStatus === 'DEGRADED' ? 'border-amber-500/20 bg-amber-500/10 text-amber-100' : 'border-red-500/20 bg-red-500/10 text-red-100'}`}>
                      소스 {item.sourceQualityScore ?? 0} · {item.sourceCoveragePct ?? 0}%
                    </span>
                    {(item.sourceRevisionCount ?? 0) > 0 ? (
                      <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-0.5 text-[10px] text-cyan-100">
                        45일 리비전 {item.sourceRevisionCount}
                      </span>
                    ) : null}
                    {(item.sourceFailureCount ?? 0) > 0 ? (
                      <span className="rounded-full border border-amber-500/20 bg-amber-500/10 px-2 py-0.5 text-[10px] text-amber-100">
                        45일 실패 {item.sourceFailureCount}
                      </span>
                    ) : null}
                    {item.sourceLastRefreshAt ? (
                      <span className="text-[10px] text-white/45">갱신 {formatNarrativeRefresh(item.sourceLastRefreshAt)}</span>
                    ) : null}
                  </div>
                </SmartLink>
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

function formatNarrativeRefresh(value: string) {
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(parsed);
}
