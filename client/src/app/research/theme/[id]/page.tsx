import { redirect } from 'next/navigation';
import { ThemeCompanyCompare } from '@/components/ThemeCompanyCompare';
import { ActionBadge, ScoreBadge, ScoreLegend, HelpDot } from '@/components/ScoreUI';
import { SmartLink } from "@/components/SmartLink";
import { fetchServerJson } from "@/lib/server-api";
export const revalidate = 300;
export const dynamic = "force-dynamic";


const CLASSIFICATION_LABEL: Record<string, string> = {
  cyclical: '사이클형',
  structural: '구조형',
  defensive: '방어형',
  neutral: '중립',
};

type ThemeResponse = {
  sortKey?: string;
  companySortKey?: string;
  theme?: { id?: string; theme: string; description: string; tickers: string[]; sectorKeys?: string[] };
  items?: Array<{
    rank: number;
    ticker: string;
    name: string;
    marketCap: number | null;
    totalScore: number | null;
    buyScore: number | null;
    buyLabel: string | null;
    appealScore: number | null;
    crowdingScore: number | null;
    revenueGrowthYoY: number | null;
    operatingMargin: number | null;
    evToSales: number | null;
    error?: string;
  }>;

  sectorScores?: Array<{
    key: string;
    label: string;
    classification: string;
    momentumScore: number | null;
    qualityScore: number | null;
    policySupport: number | null;
    structuralDemand: number | null;
    supplyTightness: number | null;
    marketConcentration: number | null;
    appealScore: number | null;
    crowdingScore: number | null;
    buyScore: number | null;
    buyLabel: string | null;
    stance: 'favored' | 'avoided' | 'neutral';
    bottomState?: string | null;
    bottomScore?: number | null;
    bottomFailureRiskScore?: number | null;
    actionLabel?: string | null;
    failureSummary?: string | null;
    buyScoreTrend?: Array<number | null>;
    buyScoreDelta7d?: number | null;
    buyScoreDelta30d?: number | null;
  }>;
  sectorSummary?: {
    averageBuyScore: number | null;
    averageBottomScore?: number | null;
    averageBottomFailureRiskScore?: number | null;
    averageAppealScore: number | null;
    averageCrowdingScore: number | null;
    averageQualityScore: number | null;
    topSector?: { label?: string; buyScore?: number | null; buyLabel?: string | null; bottomState?: string | null; bottomScore?: number | null; bottomFailureRiskScore?: number | null } | null;
  } | null;
  error?: string;
};

function normalizeThemeId(value: string): string {
  let decoded = value;
  try {
    decoded = decodeURIComponent(value);
  } catch {
    decoded = value;
  }
  return decoded.toLowerCase().replace(/[^a-z0-9가-힣]+/g, '-').replace(/^-+|-+$/g, '');
}

async function fetchTheme(id: string, sort?: string, companySort?: string): Promise<ThemeResponse> {
  try {
    const targetId = normalizeThemeId(id);
    const params = new URLSearchParams();
    if (sort) params.set('sort', sort);
    if (companySort) params.set('companySort', companySort);
    const query = params.size ? `?${params.toString()}` : '';
    const data = await fetchServerJson<ThemeResponse>(`/api/research/themes/${encodeURIComponent(targetId)}${query}`, { revalidate: 300 });
    if (data?.theme) return data;

    const themesData = await fetchServerJson<{ themes?: Array<{ id: string; theme: string; description: string; tickers: string[] }> }>('/api/research/themes', { revalidate: 300 });
    const matched = (themesData?.themes ?? []).find((item) => normalizeThemeId(item.id) === targetId || normalizeThemeId(item.theme) === targetId);
    if (matched) {
      const retry = await fetchServerJson<ThemeResponse>(`/api/research/themes/${encodeURIComponent(matched.id)}${query}`, { revalidate: 300 });
      if (retry) return retry;
    }

    return data ?? { error: 'failed' };
  } catch (error) {
    return { error: error instanceof Error ? error.message : 'failed' };
  }
}

function sparkline(values?: Array<number | null>) {
  const blocks = ['▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'];
  const valid = (values ?? []).filter((value): value is number => typeof value === 'number');
  if (!valid.length) return '—';
  const min = Math.min(...valid);
  const max = Math.max(...valid);
  const span = Math.max(max - min, 1);
  return (values ?? []).map((value) => {
    if (typeof value !== 'number') return '·';
    const idx = Math.max(0, Math.min(blocks.length - 1, Math.round(((value - min) / span) * (blocks.length - 1))));
    return blocks[idx];
  }).join('');
}

export default async function ResearchThemeDetailPage({ params, searchParams }: { params: Promise<{ id: string }>; searchParams: Promise<{ sort?: string; companySort?: string }> }) {
  const { id } = await params;
  const { sort, companySort } = await searchParams;
  const data = await fetchTheme(id, sort, companySort);
  const normalizedId = normalizeThemeId(id);

  if (data.theme?.id && data.theme.id !== normalizedId) {
    redirect(`/research/theme/${data.theme.id}`);
  }

  if (!data.theme) {
    return (
      <main className="flex-1 p-4 md:p-6 max-w-5xl mx-auto w-full">
        <div className="rounded-2xl border border-red-500/20 bg-red-500/10 p-5 text-sm text-red-100">
          테마를 찾을 수 없습니다. 링크가 오래됐을 수 있어 새 테마 ID로 재시도 중입니다.
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 p-4 md:p-6 max-w-6xl mx-auto w-full">
      <div className="space-y-6">
        <header className="space-y-2">
          <SmartLink href="/research" className="text-xs text-cyan-300 cursor-pointer hover:text-cyan-200">← Research로 돌아가기</SmartLink>
          <h1 className="text-2xl font-bold tracking-tight">{data.theme.theme} 랭킹</h1>
          <p className="text-sm text-[var(--muted)]">{data.theme.description}</p>
        </header>

        {data.sectorSummary && (
          <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="mb-3 flex flex-col gap-2 md:flex-row md:items-center md:justify-between">
              <div>
                <div className="text-sm font-semibold text-white">섹터 점수 요약</div>
                <div className="text-xs text-[var(--muted)]">기업 점수와 별개로 이 테마가 기대는 섹터 프록시 점수입니다.</div>
                <div className="mt-2"><ScoreLegend defaultOpen /></div>
              </div>
              <div className="flex flex-wrap gap-2 text-xs">
                {typeof data.sectorSummary.averageBuyScore === 'number' && <ScoreBadge label="평균 B" value={data.sectorSummary.averageBuyScore} title="평균 B입니다. 70+면 테마 전반이 매수 우호, 55~69면 선별 접근, 그 이하면 보수 접근으로 해석합니다." kind="buy" />}
                {typeof data.sectorSummary.averageBottomScore === 'number' && <ScoreBadge label="평균 바닥" value={data.sectorSummary.averageBottomScore} title="섹터들의 바닥 확인 평균입니다." kind="appeal" />}
                {typeof data.sectorSummary.averageBottomFailureRiskScore === 'number' && <ScoreBadge label="평균 실패위험" value={data.sectorSummary.averageBottomFailureRiskScore} title="섹터 바닥 실패 위험 평균입니다." kind="crowding" />}
                {typeof data.sectorSummary.averageQualityScore === 'number' && <ScoreBadge label="평균 Q" value={data.sectorSummary.averageQualityScore} title="평균 Q입니다. 70+면 구조 건강도가 좋고, 55~69면 양호, 그 이하면 구조 우위가 약한 편입니다." kind="quality" />}
                {typeof data.sectorSummary.averageCrowdingScore === 'number' && <ScoreBadge label="평균 과열" value={data.sectorSummary.averageCrowdingScore} title="평균 과열입니다. 70+면 이미 많이 오른 crowded 구간일 수 있어 추격 주의입니다." kind="crowding" />}
              </div>
            </div>
            {!!data.sectorScores?.length && (
              <>
                <div className="mb-3 flex flex-wrap gap-2 text-xs">
                  {['buy','quality','momentum','delta7','delta30','crowding'].map((key) => (
                    <SmartLink
                      key={key}
                      href={`/research/theme/${data.theme?.id}?sort=${key}`}
                      prefetch={false}
                      className={`rounded-full border px-3 py-1 ${data.sortKey === key ? 'border-cyan-500/40 bg-cyan-500/10 text-cyan-100' : 'border-white/10 text-white/70 cursor-pointer hover:bg-white/5 active:scale-[0.99]'}`}
                    >
                      {key === 'buy' ? 'B 정렬' : key === 'quality' ? 'Q 정렬' : key === 'momentum' ? '모멘텀' : key === 'delta7' ? '7D 변화' : key === 'delta30' ? '30D 변화' : '과열'}
                    </SmartLink>
                  ))}
                </div>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {data.sectorScores.map((sector) => (
                  <div key={sector.key} className="rounded-xl border border-white/10 bg-black/15 p-4">
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <div className="font-medium text-white">{sector.label}</div>
                        <div className="mt-1 text-xs text-[var(--muted)]">{CLASSIFICATION_LABEL[sector.classification] || sector.classification}</div>
                      </div>
                      <div className="text-right">
                        {typeof sector.buyScore === 'number' && <ScoreBadge label="B" value={sector.buyScore} title="B입니다. 70+면 매수 우호, 55~69면 선별 접근, 54 이하면 보수적으로 해석합니다." kind="buy" />}
                        <div className="mt-2"><ActionBadge value={sector.buyScore} /></div>
                      </div>
                    </div>
                    <div className="mt-3 flex flex-wrap gap-2 text-[10px]">
                      {typeof sector.qualityScore === 'number' && <ScoreBadge label="Q" value={sector.qualityScore} title="Q입니다. 섹터의 구조적 건강도이며 높을수록 가격 대비 기반이 좋다고 볼 수 있습니다." kind="quality" />}
                      {typeof sector.momentumScore === 'number' && <span title="최근 20일 상대강도입니다. 단기적으로 자금이 들어오고 있는지 보는 값입니다." className="rounded-full border border-white/10 px-2 py-1 text-white/80">모멘텀 {sector.momentumScore.toFixed(1)}%<HelpDot title="최근 20일 상대강도입니다. 단기적으로 자금이 들어오고 있는지 보는 값입니다." /></span>}
                      {typeof sector.appealScore === 'number' && <ScoreBadge label="매력" value={sector.appealScore} title="매력도는 가격보다 펀더멘털/구조/흐름이 얼마나 좋은지에 가깝습니다." kind="appeal" />}
                      {typeof sector.crowdingScore === 'number' && <ScoreBadge label="과열" value={sector.crowdingScore} title="과열은 추격 위험입니다. 높을수록 기업/섹터는 좋아도 진입 타이밍은 신중해야 합니다." kind="crowding" />}
                      {typeof sector.bottomScore === 'number' && <ScoreBadge label="바닥" value={sector.bottomScore} title="섹터 바닥 확인 점수입니다." kind="appeal" />}
                      {typeof sector.bottomFailureRiskScore === 'number' && <ScoreBadge label="실패위험" value={sector.bottomFailureRiskScore} title="섹터 바닥 실패 위험입니다." kind="crowding" />}
                      {sector.bottomState ? <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-1 text-cyan-100">{sector.bottomState}</span> : null}
                      {sector.actionLabel ? <span className="rounded-full border border-white/10 px-2 py-1 text-white/80">{sector.actionLabel}</span> : null}
                      {typeof sector.buyScoreDelta7d === 'number' && <span className="rounded-full border border-white/10 px-2 py-1 text-white/80">7D {sector.buyScoreDelta7d >= 0 ? '+' : ''}{sector.buyScoreDelta7d}</span>}
                      {typeof sector.buyScoreDelta30d === 'number' && <span className="rounded-full border border-white/10 px-2 py-1 text-white/80">30D {sector.buyScoreDelta30d >= 0 ? '+' : ''}{sector.buyScoreDelta30d}</span>}
                      {sector.buyScoreTrend?.length ? <span className="rounded-full border border-white/10 px-2 py-1 text-white/80">추이 {sparkline(sector.buyScoreTrend)}</span> : null}
                    </div>
                    <div className="mt-3 grid grid-cols-2 gap-2 text-xs">
                      <div className="rounded-lg bg-white/5 p-2"><div className="text-[var(--muted)]">정책 <HelpDot title="정책 수혜 강도" /></div><div className="mt-1 text-white">{sector.policySupport ?? '—'}</div></div>
                      <div className="rounded-lg bg-white/5 p-2"><div className="text-[var(--muted)]">구조 수요 <HelpDot title="장기 구조 수요 강도" /></div><div className="mt-1 text-white">{sector.structuralDemand ?? '—'}</div></div>
                      <div className="rounded-lg bg-white/5 p-2"><div className="text-[var(--muted)]">공급 제약 <HelpDot title="공급 제약/병목 강도" /></div><div className="mt-1 text-white">{sector.supplyTightness ?? '—'}</div></div>
                      <div className="rounded-lg bg-white/5 p-2"><div className="text-[var(--muted)]">과점도 <HelpDot title="소수 플레이어 집중도" /></div><div className="mt-1 text-white">{sector.marketConcentration ?? '—'}</div></div>
                    </div>
                    {sector.failureSummary ? (
                      <div className="mt-3 rounded-lg border border-rose-500/15 bg-rose-500/5 p-2 text-[11px] text-rose-100">
                        {sector.failureSummary}
                      </div>
                    ) : null}
                  </div>
                ))}
              </div>
              </>
            )}
          </section>
        )}

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="mb-3 flex items-center justify-between">
            <div className="text-sm font-semibold text-white">대표 종목 {data.theme.tickers.length}개</div>
            <div className="text-xs text-[var(--muted)]">기본 순서: 시총 + 품질 + B 점수 합성</div>
          </div>

          <div className="mb-3 flex flex-wrap gap-2 text-xs">
            {['priority','marketcap','buy','total','growth','margin','valuation'].map((key) => (
              <SmartLink
                key={key}
                href={`/research/theme/${data.theme?.id}?sort=${data.sortKey ?? 'buy'}&companySort=${key}`}
                prefetch={false}
                className={`rounded-full border px-3 py-1 ${data.companySortKey === key ? 'border-cyan-500/40 bg-cyan-500/10 text-cyan-100' : 'border-white/10 text-white/70 cursor-pointer hover:bg-white/5 active:scale-[0.99]'}`}
              >
                {key === 'priority' ? '추천순' : key === 'marketcap' ? '시총' : key === 'total' ? '총점' : key === 'buy' ? 'B 정렬' : key === 'growth' ? '성장' : key === 'margin' ? '마진' : '밸류'}
              </SmartLink>
            ))}
          </div>

          <ThemeCompanyCompare items={data.items ?? []} />
        </section>
      </div>
    </main>
  );
}
