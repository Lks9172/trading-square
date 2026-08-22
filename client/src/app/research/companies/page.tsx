import { ScoreLegend, ScoreBadge, ActionBadge } from "@/components/ScoreUI";
import { SmartLink } from "@/components/SmartLink";
import { fetchServerJson } from "@/lib/server-api";

export const revalidate = 300;
export const dynamic = "force-dynamic";

type SortKey = 'buy' | 'total' | 'growth' | 'margin' | 'value' | 'appeal';

interface ResearchCompanyItem {
  ticker: string;
  name: string;
  peerGroup: string | null;
  themeIds: string[];
  themeNames: string[];
  sectorIds: string[];
  sectorNames: string[];
  totalScore: number | null;
  buyScore: number | null;
  buyLabel: string | null;
  appealScore: number | null;
  crowdingScore: number | null;
  revenueGrowthYoY: number | null;
  operatingMargin: number | null;
  evToSales: number | null;
  narrativeStage: string | null;
  bottleneckConviction: string | null;
  bottomState: string | null;
  earningsBottomScore: number | null;
  priceBottomScore: number | null;
  volumeConfirmationScore: number | null;
  bottomFailureRiskScore: number | null;
  confirmedBottomScore: number | null;
  confirmedBottomState: '미충족' | '후보' | '확신' | null;
}

interface ResearchCompaniesResponse {
  items: ResearchCompanyItem[];
  sortKey: SortKey;
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
  themes: Array<{ id: string; theme: string }>;
  sectors: Array<{ id: string; label: string }>;
}

function fmtNum(value: number | null, digits = 1) {
  if (value === null || Number.isNaN(value)) return '—';
  return value.toLocaleString('en-US', { maximumFractionDigits: digits, minimumFractionDigits: digits });
}

function fmtPct(value: number | null) {
  if (value === null || Number.isNaN(value)) return '—';
  return `${value.toFixed(1)}%`;
}

async function fetchCompanies(sort: SortKey, q: string, themeId: string, sectorId: string, page: number): Promise<ResearchCompaniesResponse | null> {
  const search = new URLSearchParams();
  search.set('sort', sort);
  search.set('page', String(page));
  if (q) search.set('q', q);
  if (themeId) search.set('themeId', themeId);
  if (sectorId) search.set('sectorId', sectorId);
  return fetchServerJson<ResearchCompaniesResponse>(`/api/research/companies?${search.toString()}`, { revalidate: 300 });
}

export default async function ResearchCompaniesPage({
  searchParams,
}: {
  searchParams: Promise<{ sort?: string; q?: string; themeId?: string; sectorId?: string; page?: string }>;
}) {
  const params = await searchParams;
  const sort = (['buy', 'total', 'growth', 'margin', 'value', 'appeal'].includes(String(params.sort)) ? params.sort : 'buy') as SortKey;
  const q = String(params.q || '').trim();
  const themeId = String(params.themeId || '').trim();
  const sectorId = String(params.sectorId || '').trim();
  const page = Math.max(1, parseInt(String(params.page || '1'), 10) || 1);
  const data = await fetchCompanies(sort, q, themeId, sectorId, page);

  if (!data) {
    return (
      <main className="flex-1 p-4 md:p-6 max-w-7xl mx-auto w-full">
        <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-6">
          <div className="text-lg font-semibold mb-2">기업 리스트를 불러오지 못했습니다</div>
          <SmartLink href="/research" className="text-cyan-300 underline">← 리서치 홈으로</SmartLink>
        </div>
      </main>
    );
  }

  const sortOptions: Array<{ key: SortKey; label: string }> = [
    { key: 'buy', label: 'B 점수' },
    { key: 'total', label: '총점' },
    { key: 'growth', label: '성장' },
    { key: 'margin', label: '마진' },
    { key: 'value', label: '밸류' },
    { key: 'appeal', label: '매력' },
  ];

  const makeHref = (nextPage: number, nextSort = sort) => {
    const search = new URLSearchParams();
    search.set('sort', nextSort);
    search.set('page', String(nextPage));
    if (q) search.set('q', q);
    if (themeId) search.set('themeId', themeId);
    if (sectorId) search.set('sectorId', sectorId);
    return `/research/companies?${search.toString()}`;
  };

  return (
    <main className="flex-1 p-4 md:p-6 max-w-7xl mx-auto w-full">
      <div className="space-y-6">
        <header className="space-y-2">
          <div className="text-xs text-[var(--muted)]"><SmartLink href="/research" className="cursor-pointer hover:text-white">리서치</SmartLink> / 전체 기업 리스트</div>
          <h1 className="text-2xl font-bold tracking-tight">전체 기업 리스트</h1>
          <p className="text-sm text-[var(--muted)]">전략 테마 + 표준 섹터에 포함된 전체 기업을 한 번에 보고, B 점수/총점/성장/마진/밸류 기준으로 정렬할 수 있습니다.</p>
        </header>

        <ScoreLegend defaultOpen />

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
            <form className="flex flex-col gap-3 md:flex-row md:items-end">
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-[var(--muted)]">검색</span>
                <input name="q" defaultValue={q} placeholder="티커 또는 테마명" className="rounded-xl border border-white/10 bg-black/20 px-3 py-2 text-sm outline-none focus:border-cyan-400/50" />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-[var(--muted)]">전략 테마</span>
                <select name="themeId" defaultValue={themeId} className="rounded-xl border border-white/10 bg-black/20 px-3 py-2 text-sm outline-none focus:border-cyan-400/50">
                  <option value="">전체</option>
                  {data.themes.map((theme) => <option key={theme.id} value={theme.id}>{theme.theme}</option>)}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-[var(--muted)]">표준 섹터</span>
                <select name="sectorId" defaultValue={sectorId} className="rounded-xl border border-white/10 bg-black/20 px-3 py-2 text-sm outline-none focus:border-cyan-400/50">
                  <option value="">전체</option>
                  {data.sectors.map((sector) => <option key={sector.id} value={sector.id}>{sector.label}</option>)}
                </select>
              </label>
              <input type="hidden" name="sort" value={sort} />
              <input type="hidden" name="page" value="1" />
              <button className="rounded-xl border border-cyan-500/30 bg-cyan-500/10 px-4 py-2 text-sm text-cyan-200 cursor-pointer hover:bg-cyan-500/20 active:scale-[0.99]">적용</button>
            </form>
            <div className="text-sm text-[var(--muted)]">총 {data.total}개 기업 · {data.page}/{data.totalPages} 페이지</div>
          </div>

          <div className="mt-4 flex flex-wrap gap-2">
            {sortOptions.map((option) => {
              const active = option.key === sort;
              const params = new URLSearchParams();
              params.set('sort', option.key);
              params.set('page', '1');
              if (q) params.set('q', q);
              if (themeId) params.set('themeId', themeId);
              if (sectorId) params.set('sectorId', sectorId);
              return (
                <SmartLink
                  key={option.key}
                  href={`/research/companies?${params.toString()}`}
                  prefetch={false}
                  className={`rounded-full border px-3 py-1 text-xs ${active ? 'border-cyan-500/30 bg-cyan-500/10 text-cyan-200' : 'border-white/10 bg-white/5 text-white/75 hover:bg-white/10'}`}
                >
                  {option.label}
                </SmartLink>
              );
            })}
          </div>
        </section>

        <section className="grid grid-cols-1 xl:grid-cols-2 gap-4">
          {data.items.map((item) => (
            <SmartLink key={item.ticker} href={`/company/${item.ticker}`} prefetch={false} className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5 cursor-pointer hover:bg-white/[0.03] active:scale-[0.99]">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                <div>
                  <div className="text-lg font-semibold text-white">{item.ticker}</div>
                  <div className="mt-1 text-sm text-[var(--muted)] break-words">{item.name}</div>
                  <div className="mt-2 flex flex-wrap gap-2 text-[11px] text-white/75">
                    {item.themeNames.map((theme) => (
                      <span key={theme} className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5">{theme}</span>
                    ))}
                    {item.sectorNames.map((sector) => (
                      <span key={sector} className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-0.5 text-cyan-100">{sector}</span>
                    ))}
                    {item.narrativeStage ? <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-0.5 text-cyan-100">{item.narrativeStage}</span> : null}
                    {item.bottleneckConviction ? <span className="rounded-full border border-amber-500/20 bg-amber-500/10 px-2 py-0.5 text-amber-100">병목 {item.bottleneckConviction}</span> : null}
                  </div>
                </div>
                <div className="flex flex-wrap items-center gap-2 sm:justify-end">
                  <ScoreBadge label="B" value={item.buyScore} title="지금 사도 되는지 보는 실행 점수입니다." kind="buy" className="text-xs" interactive={false} />
                  <ScoreBadge label="총점" value={item.totalScore} title="기업의 기초체력을 종합한 점수입니다." kind="total" className="text-xs" interactive={false} />
                  <ActionBadge value={item.buyScore} compact interactive={false} />
                </div>
              </div>

              <div className="mt-3 flex flex-wrap gap-2 text-[11px]">
                {item.bottomState ? <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-1 text-cyan-100">바닥 {item.bottomState}</span> : null}
                {item.confirmedBottomState && item.confirmedBottomState !== '미충족' ? <span className="rounded-full border border-fuchsia-500/20 bg-fuchsia-500/10 px-2 py-1 text-fuchsia-100">찐바닥 {item.confirmedBottomState} {item.confirmedBottomScore ?? ''}</span> : null}
                {typeof item.volumeConfirmationScore === 'number' ? <span className="rounded-full border border-amber-500/20 bg-amber-500/10 px-2 py-1 text-amber-100">거래량확인 {item.volumeConfirmationScore}</span> : null}
                {typeof item.priceBottomScore === 'number' ? <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1 text-white/80">가격바닥 {item.priceBottomScore}</span> : null}
                {typeof item.bottomFailureRiskScore === 'number' ? <span className="rounded-full border border-rose-500/20 bg-rose-500/10 px-2 py-1 text-rose-100">실패위험 {item.bottomFailureRiskScore}</span> : null}
              </div>

              <div className="mt-4 grid grid-cols-2 md:grid-cols-5 gap-3 text-sm">
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">매력</div>
                  <div className="mt-1 font-semibold">{item.appealScore ?? '—'}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">과열</div>
                  <div className="mt-1 font-semibold">{item.crowdingScore ?? '—'}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">매출 YoY</div>
                  <div className="mt-1 font-semibold">{fmtPct(item.revenueGrowthYoY)}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">영업이익률</div>
                  <div className="mt-1 font-semibold">{fmtPct(item.operatingMargin)}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">EV/Sales</div>
                  <div className="mt-1 font-semibold">{fmtNum(item.evToSales)}x</div>
                </div>
              </div>
            </SmartLink>
          ))}
        </section>

        <section className="flex items-center justify-between rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-4">
          <div className="text-sm text-[var(--muted)]">
            {(data.page - 1) * data.pageSize + 1}–{Math.min(data.page * data.pageSize, data.total)} / {data.total}
          </div>
          <div className="flex items-center gap-2 text-sm">
            <SmartLink
              href={makeHref(Math.max(1, data.page - 1))}
              prefetch={false}
              aria-disabled={data.page <= 1}
              className={`rounded-lg border px-3 py-2 ${data.page <= 1 ? 'pointer-events-none border-white/5 text-white/30' : 'border-white/10 text-white/80 cursor-pointer hover:bg-white/5 active:scale-[0.99]'}`}
            >
              이전
            </SmartLink>
            <div className="rounded-lg border border-white/10 px-3 py-2 text-white/80">
              {data.page} / {data.totalPages}
            </div>
            <SmartLink
              href={makeHref(Math.min(data.totalPages, data.page + 1))}
              prefetch={false}
              aria-disabled={data.page >= data.totalPages}
              className={`rounded-lg border px-3 py-2 ${data.page >= data.totalPages ? 'pointer-events-none border-white/5 text-white/30' : 'border-white/10 text-white/80 cursor-pointer hover:bg-white/5 active:scale-[0.99]'}`}
            >
              다음
            </SmartLink>
          </div>
        </section>
      </div>
    </main>
  );
}
