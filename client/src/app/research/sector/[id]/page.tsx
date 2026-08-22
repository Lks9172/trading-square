import { ScoreBadge, ActionBadge, ScoreLegend } from "@/components/ScoreUI";
import { SmartLink } from "@/components/SmartLink";
import { fetchServerJson } from "@/lib/server-api";

export const revalidate = 300;
export const dynamic = "force-dynamic";

type RotationItem = {
  key: string;
  label: string;
  classification: string;
  rotationScore: number;
  macroFitScore: number;
  relativeStrengthScore: number;
  fundamentalScore: number;
  valuationScore?: number | null;
  earningsRevisionScore?: number | null;
  earningsRevisionObservedOn?: string | null;
  earningsRevisionCoveragePct?: number | null;
  earningsRevisionUpPct?: number | null;
  earningsRevisionDownPct?: number | null;
  flowScore?: number | null;
  fundFlowObservedOn?: string | null;
  fundFlow1dUsd?: number | null;
  fundFlow5dUsd?: number | null;
  fundFlow20dUsd?: number | null;
  fundFlow5dPct?: number | null;
  fundFlow20dPct?: number | null;
  priceBreadthScore?: number | null;
  priceBreadthObservedOn?: string | null;
  priceBreadthCoveragePct?: number | null;
  aboveMa20Pct?: number | null;
  aboveMa50Pct?: number | null;
  aboveMa200Pct?: number | null;
  crowdingReliefScore: number;
  state: 'LEADING' | 'IMPROVING' | 'WEAKENING' | 'LAGGING';
  rotationLabel: 'Rotation In' | 'Leader' | 'Late Leader' | 'Rotation Out' | 'Defensive Hold';
  reasons: string[];
};

type RotationCandidateView = {
  label: string;
  sectorKey: string;
  rotationScore: number;
  expectedLeadershipWindow: string;
  expectedLeadershipMessage: string;
  note: string;
  confirmationState?: 'CONFIRMED' | 'BUILDING' | 'WATCH' | 'INVALIDATED' | null;
  confirmationScore?: number | null;
  confirmationCoveragePct?: number | null;
  confirmationLabel?: string | null;
  confirmationReasons?: string[];
  invalidationSignals?: string[];
};

type SectorDetailResponse = {
  sector?: { id: string; label: string; description: string; sectorKey: string; tickers: string[] };
  sortKey?: string;
  relatedThemes?: Array<{ id: string; theme: string }>;
  rotation?: RotationItem | null;
  rotationSummary?: {
    regime: string;
    confidence?: number;
    regimeScores?: Record<string, number>;
    summary: string;
    favoredNext: string[];
    fadingNext: string[];
    currentLeaders?: RotationCandidateView[];
    nextCandidates?: RotationCandidateView[];
    secondaryCandidates?: RotationCandidateView[];
    calculatedAt?: string | null;
    currentMarketOverlay?: boolean;
    methodology?: string;
  } | null;
  sectorScores?: Array<{
    key: string;
    label: string;
    classification: string;
    momentumScore: number | null;
    qualityScore: number | null;
    appealScore: number | null;
    crowdingScore: number | null;
    buyScore: number | null;
    buyLabel: string | null;
    rotationScore?: number | null;
    rotationState?: string | null;
    rotationLabel?: string | null;
    rotationReasons?: string[];
    bottomState?: string | null;
    bottomScore?: number | null;
    bottomFailureRiskScore?: number | null;
    avgVolumeConfirmationScore?: number | null;
    actionLabel?: string | null;
    failureSummary?: string | null;
  }>;
  sectorSummary?: {
    averageBuyScore: number | null;
    averageBottomScore?: number | null;
    averageBottomFailureRiskScore?: number | null;
    averageVolumeConfirmationScore?: number | null;
    averageAppealScore: number | null;
    averageCrowdingScore: number | null;
    averageQualityScore: number | null;
    averageRotationScore?: number | null;
  } | null;
  densitySummary?: {
    peer: number;
    peerPct: number;
    narrative: number;
    narrativePct: number;
    fallback: number;
    fallbackPct: number;
    bottleneck: number;
    bottleneckPct: number;
    capitalFlow: number;
    capitalFlowPct: number;
  } | null;
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
    bottomScore: number | null;
    priceBottomScore: number | null;
    volumeConfirmationScore: number | null;
    failureRiskScore: number | null;
    bottomState: string | null;
    confirmedBottomScore?: number | null;
    confirmedBottomState?: '미충족' | '후보' | '확신' | null;
  }>;
};

async function fetchSector(id: string): Promise<SectorDetailResponse | null> {
  return fetchServerJson<SectorDetailResponse>(`/api/research/sectors/${encodeURIComponent(id)}`, { revalidate: 300 });
}

function fmtPct(value: number | null) {
  if (value === null || Number.isNaN(value)) return "—";
  return `${value.toFixed(1)}%`;
}

function fmtNum(value: number | null, digits = 1) {
  if (value === null || Number.isNaN(value)) return "—";
  return value.toLocaleString("en-US", { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

function compactUsd(value?: number | null) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—';
  const sign = value > 0 ? '+' : '';
  return `${sign}${new Intl.NumberFormat('ko-KR', {
    style: 'currency', currency: 'USD', notation: 'compact', maximumFractionDigits: 1,
  }).format(value)}`;
}

function rotationTone(label?: string | null) {
  switch (label) {
    case 'Rotation In':
      return 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100';
    case 'Leader':
      return 'border-sky-500/20 bg-sky-500/10 text-sky-100';
    case 'Late Leader':
      return 'border-amber-500/20 bg-amber-500/10 text-amber-100';
    case 'Defensive Hold':
      return 'border-violet-500/20 bg-violet-500/10 text-violet-100';
    default:
      return 'border-rose-500/20 bg-rose-500/10 text-rose-100';
  }
}

function horizonLabel(value?: string) {
  switch (value) {
    case 'now': return '지금~3개월';
    case '1_3m': return '1~3개월';
    case '3_6m': return '3~6개월';
    case '6m_plus': return '6개월+';
    default: return '가시성 낮음';
  }
}

function regimeLabel(value: string) {
  switch (value) {
    case 'EARLY_CYCLICAL': return '초기 경기민감';
    case 'MID_GROWTH': return '중기 성장';
    case 'LATE_INFLATION': return '후기 인플레';
    case 'DEFENSIVE': return '방어';
    case 'RE_ACCELERATION': return '재가속';
    default: return value;
  }
}

function confirmationTone(state?: RotationCandidateView['confirmationState']) {
  switch (state) {
    case 'CONFIRMED': return 'border-emerald-400/30 bg-emerald-400/10 text-emerald-100';
    case 'BUILDING': return 'border-sky-400/30 bg-sky-400/10 text-sky-100';
    case 'INVALIDATED': return 'border-rose-400/30 bg-rose-400/10 text-rose-100';
    default: return 'border-white/10 bg-white/5 text-white/65';
  }
}

function ConfirmationStatus({ item }: { item: RotationCandidateView }) {
  if (!item.confirmationState) return null;
  return (
    <div className="mt-2 space-y-1">
      <div className={`inline-flex rounded-full border px-2 py-0.5 text-[10px] ${confirmationTone(item.confirmationState)}`}>
        {item.confirmationLabel ?? item.confirmationState}
        {typeof item.confirmationScore === 'number' ? ` ${item.confirmationScore}` : ''}
        {typeof item.confirmationCoveragePct === 'number' ? ` · 근거 ${item.confirmationCoveragePct}%` : ''}
      </div>
      {item.confirmationReasons?.[0] ? <div className="text-[10px] text-white/55">{item.confirmationReasons[0]}</div> : null}
      {item.invalidationSignals?.[0] ? <div className="text-[10px] text-rose-100/65">훼손: {item.invalidationSignals[0]}</div> : null}
    </div>
  );
}

export default async function ResearchSectorDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const data = await fetchSector(id);

  if (!data?.sector) {
    return (
      <main className="flex-1 p-4 md:p-6 max-w-6xl mx-auto w-full">
        <div className="rounded-2xl border border-red-500/20 bg-red-500/10 p-5 text-sm text-red-100">섹터를 찾을 수 없습니다.</div>
      </main>
    );
  }

  return (
    <main className="flex-1 p-4 md:p-6 max-w-6xl mx-auto w-full">
      <div className="space-y-6">
        <header className="space-y-2">
          <div className="text-xs text-[var(--muted)]"><SmartLink href="/research" className="cursor-pointer hover:text-white">리서치</SmartLink> / <SmartLink href="/research/sectors" className="cursor-pointer hover:text-white">표준 11개 섹터</SmartLink> / {data.sector.label}</div>
          <h1 className="text-2xl font-bold tracking-tight">{data.sector.label}</h1>
          <p className="text-sm text-[var(--muted)]">{data.sector.description}</p>
          <div className="text-xs text-[var(--muted)]">대표 기업 {data.sector.tickers.length}개</div>
        </header>

        <ScoreLegend />

        {!!data.rotationSummary && (
          <section className="rounded-2xl border border-cyan-500/20 bg-cyan-500/5 p-5">
            <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
              <div>
                <div className="text-sm font-semibold text-cyan-100">섹터 순환 해석</div>
                <div className="mt-1 text-xs text-cyan-50/80">{data.rotationSummary.regime}{typeof data.rotationSummary.confidence === 'number' ? ` · 분리도 ${data.rotationSummary.confidence}` : ''}</div>
                {data.rotationSummary.regimeScores && Object.keys(data.rotationSummary.regimeScores).length > 0 ? (
                  <div className="mt-2 flex flex-wrap gap-1.5 text-[10px] text-cyan-50/75">
                    {Object.entries(data.rotationSummary.regimeScores)
                      .sort(([, left], [, right]) => right - left)
                      .map(([key, value]) => (
                        <span key={key} className="rounded-full border border-cyan-400/15 bg-black/10 px-2 py-1">
                          {regimeLabel(key)} {value}
                        </span>
                      ))}
                  </div>
                ) : null}
                <p className="mt-2 text-sm text-cyan-50/90">{data.rotationSummary.summary}</p>
                {data.rotationSummary.currentMarketOverlay && (
                  <div className="mt-2 text-[10px] text-emerald-100/80">
                    현재 시장 재계산 · {data.rotationSummary.calculatedAt?.replace('T', ' ').replace('Z', ' UTC') ?? '산출시각 확인 중'}
                    {data.rotationSummary.methodology ? ` · ${data.rotationSummary.methodology}` : ''}
                  </div>
                )}
                {!!data.rotation && (
                  <>
                  <div className="mt-3 flex flex-wrap gap-2">
                    <span className={`rounded-full border px-2 py-1 text-xs ${rotationTone(data.rotation.rotationLabel)}`}>{data.rotation.rotationLabel}</span>
                    <ScoreBadge label="순환" value={data.rotation.rotationScore ?? null} title="현재 섹터 순환 점수" kind="buy" interactive={false} />
                    <ScoreBadge label="거시정합" value={data.rotation.macroFitScore ?? null} title="현재 거시 국면과의 정합도" kind="quality" interactive={false} />
                    <ScoreBadge label="상대강도" value={data.rotation.relativeStrengthScore ?? null} title="1M보다 3M/6M/12M 비중이 더 큰 중기 상대강도 점수" kind="appeal" interactive={false} />
                    <ScoreBadge label="밸류" value={data.rotation.valuationScore ?? null} title="대표주 forward PE 기준 섹터 밸류 프록시 점수" kind="quality" interactive={false} />
                    <ScoreBadge label="EPS 리비전" value={data.rotation.earningsRevisionScore ?? null} title={data.rotation.earningsRevisionObservedOn ? `구성종목 30일 EPS 추정 방향 breadth · ${data.rotation.earningsRevisionObservedOn} · 커버리지 ${data.rotation.earningsRevisionCoveragePct ?? '—'}% · 상향 ${data.rotation.earningsRevisionUpPct ?? '—'}% · 하향 ${data.rotation.earningsRevisionDownPct ?? '—'}%` : "날짜와 50% 이상 구성종목 커버리지가 확인되지 않아 현재 점수와 확인축에서 제외합니다."} kind="quality" interactive={false} />
                    <ScoreBadge label="ETF 생성·환매" value={data.rotation.flowScore ?? null} title={data.rotation.fundFlowObservedOn ? `State Street 공식 NAV·발행좌수 변화 · ${data.rotation.fundFlowObservedOn} · 5일 ${compactUsd(data.rotation.fundFlow5dUsd)} (${data.rotation.fundFlow5dPct?.toFixed(2) ?? '—'}%) · 20일 ${compactUsd(data.rotation.fundFlow20dUsd)} (${data.rotation.fundFlow20dPct?.toFixed(2) ?? '—'}%)` : "7일 이내 공식 State Street 자료가 없어 현재 점수와 확인축에서 제외합니다."} kind="appeal" interactive={false} />
                    <ScoreBadge label="가격 breadth" value={data.rotation.priceBreadthScore ?? null} title={data.rotation.priceBreadthObservedOn ? `추적 구성종목 이동평균 참여도 · ${data.rotation.priceBreadthObservedOn} · 커버리지 ${data.rotation.priceBreadthCoveragePct ?? '—'}% · MA20 ${data.rotation.aboveMa20Pct ?? '—'}% · MA50 ${data.rotation.aboveMa50Pct ?? '—'}% · MA200 ${data.rotation.aboveMa200Pct ?? '—'}%` : "날짜와 70% 이상 추적 종목 커버리지가 확인되지 않아 표시하지 않습니다."} kind="quality" interactive={false} />
                    <ScoreBadge label="혼잡완화" value={data.rotation.crowdingReliefScore ?? null} title="과열이 낮을수록 높은 점수" kind="crowding" interactive={false} />
                  </div>
                  <div className="mt-3 grid gap-2 text-[11px] sm:grid-cols-2">
                    <div className="rounded-xl border border-emerald-400/15 bg-emerald-400/5 p-3 text-emerald-50/80">
                      <div className="font-semibold text-emerald-100">공식 ETF 생성·환매</div>
                      {data.rotation.fundFlowObservedOn ? (
                        <div className="mt-1 leading-relaxed">
                          기준 {data.rotation.fundFlowObservedOn} · 1일 {compactUsd(data.rotation.fundFlow1dUsd)} ·
                          5일 {compactUsd(data.rotation.fundFlow5dUsd)} ({data.rotation.fundFlow5dPct?.toFixed(2) ?? '—'}%) ·
                          20일 {compactUsd(data.rotation.fundFlow20dUsd)} ({data.rotation.fundFlow20dPct?.toFixed(2) ?? '—'}%)
                        </div>
                      ) : <div className="mt-1 text-white/50">7일 이내 공식 자료 없음</div>}
                    </div>
                    <div className="rounded-xl border border-sky-400/15 bg-sky-400/5 p-3 text-sky-50/80">
                      <div className="font-semibold text-sky-100">추적 구성종목 가격 breadth</div>
                      {data.rotation.priceBreadthObservedOn ? (
                        <div className="mt-1 leading-relaxed">
                          기준 {data.rotation.priceBreadthObservedOn} · 커버리지 {data.rotation.priceBreadthCoveragePct ?? '—'}% ·
                          MA20 {data.rotation.aboveMa20Pct ?? '—'}% · MA50 {data.rotation.aboveMa50Pct ?? '—'}% ·
                          MA200 {data.rotation.aboveMa200Pct ?? '—'}%
                        </div>
                      ) : <div className="mt-1 text-white/50">날짜·70% 커버리지 충족 자료 없음</div>}
                      <div className="mt-1 text-[10px] text-sky-100/55">대표 추적 종목 기준이며 ETF 전체 보유종목 breadth는 아닙니다.</div>
                    </div>
                  </div>
                  </>
                )}
              </div>
              <div className="grid gap-2 text-xs text-cyan-50/90">
                <div>
                  <span className="text-cyan-200">다음 후보</span>
                  <div className="mt-1 flex flex-wrap gap-2">
                    {data.rotationSummary.favoredNext.map((label) => <span key={label} className="rounded-full border border-cyan-400/20 bg-cyan-400/10 px-2 py-1">{label}</span>)}
                  </div>
                </div>
                <div>
                  <span className="text-cyan-200">약화 후보</span>
                  <div className="mt-1 flex flex-wrap gap-2">
                    {data.rotationSummary.fadingNext.map((label) => <span key={label} className="rounded-full border border-rose-400/20 bg-rose-400/10 px-2 py-1 text-rose-100">{label}</span>)}
                  </div>
                </div>
              </div>
            </div>
            <div className="mt-4 grid gap-3 md:grid-cols-3">
              <div className="rounded-xl border border-sky-400/20 bg-sky-400/5 p-3">
                <div className="text-xs font-semibold text-sky-200">현재 순환 상위</div>
                <div className="mt-2 space-y-2">
                  {(data.rotationSummary.currentLeaders ?? []).map((item) => (
                    <div key={item.sectorKey} className="rounded-lg border border-white/10 bg-black/10 p-2">
                      <div className="flex items-center justify-between gap-2">
                        <div className="text-sm font-medium text-white">{item.label}</div>
                        <div className="text-[11px] text-sky-100">{horizonLabel(item.expectedLeadershipWindow)}</div>
                      </div>
                      <ConfirmationStatus item={item} />
                    </div>
                  ))}
                </div>
              </div>
              <div className="rounded-xl border border-emerald-400/20 bg-emerald-400/5 p-3">
                <div className="text-xs font-semibold text-emerald-200">다음 주도 후보</div>
                <div className="mt-2 space-y-2">
                  {(data.rotationSummary.nextCandidates ?? []).map((item) => (
                    <div key={item.sectorKey} className="rounded-lg border border-white/10 bg-black/10 p-2">
                      <div className="flex items-center justify-between gap-2">
                        <div className="text-sm font-medium text-white">{item.label}</div>
                        <div className="text-[11px] text-emerald-100">{horizonLabel(item.expectedLeadershipWindow)}</div>
                      </div>
                      <div className="mt-1 text-[11px] text-white/70">{item.expectedLeadershipMessage}</div>
                      <ConfirmationStatus item={item} />
                    </div>
                  ))}
                </div>
              </div>
              <div className="rounded-xl border border-amber-400/20 bg-amber-400/5 p-3">
                <div className="text-xs font-semibold text-amber-200">그다음 후보</div>
                <div className="mt-2 space-y-2">
                  {(data.rotationSummary.secondaryCandidates ?? []).map((item) => (
                    <div key={item.sectorKey} className="rounded-lg border border-white/10 bg-black/10 p-2">
                      <div className="flex items-center justify-between gap-2">
                        <div className="text-sm font-medium text-white">{item.label}</div>
                        <div className="text-[11px] text-amber-100">{horizonLabel(item.expectedLeadershipWindow)}</div>
                      </div>
                      <div className="mt-1 text-[11px] text-white/70">{item.expectedLeadershipMessage}</div>
                      <ConfirmationStatus item={item} />
                    </div>
                  ))}
                </div>
              </div>
            </div>
            {!!data.rotation?.reasons?.length && (
              <div className="mt-3 rounded-xl border border-white/10 bg-black/10 p-3 text-[12px] text-white/80">
                {data.rotation.reasons.join(' · ')}
              </div>
            )}
          </section>
        )}

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="mb-3 text-sm font-semibold text-white">섹터 점수</div>
          <div className="flex flex-wrap gap-2">
            <ScoreBadge label="B" value={data.sectorSummary?.averageBuyScore ?? null} title="표준 섹터 B 점수" kind="buy" />
            <ScoreBadge label="순환" value={data.rotation?.rotationScore ?? data.sectorSummary?.averageRotationScore ?? null} title="표준 섹터 순환 우선순위 점수" kind="buy" />
            <ScoreBadge label="바닥" value={data.sectorSummary?.averageBottomScore ?? null} title="표준 섹터 바닥 확인 점수" kind="appeal" />
            <ScoreBadge label="거래량" value={data.sectorSummary?.averageVolumeConfirmationScore ?? null} title="대표 종목 평균 거래량 바닥 확인 점수" kind="appeal" />
            <ScoreBadge label="실패위험" value={data.sectorSummary?.averageBottomFailureRiskScore ?? null} title="표준 섹터 바닥 실패 위험" kind="crowding" />
            <ScoreBadge label="Q" value={data.sectorSummary?.averageQualityScore ?? null} title="표준 섹터 Q 점수" kind="quality" />
            <ScoreBadge label="매력" value={data.sectorSummary?.averageAppealScore ?? null} title="표준 섹터 매력도" kind="appeal" />
            <ScoreBadge label="과열" value={data.sectorSummary?.averageCrowdingScore ?? null} title="표준 섹터 과열도" kind="crowding" />
            <ActionBadge value={data.sectorSummary?.averageBuyScore ?? null} />
          </div>
          {!!data.relatedThemes?.length && (
            <div className="mt-4">
              <div className="mb-2 text-xs text-[var(--muted)]">연결된 전략 테마</div>
              <div className="flex flex-wrap gap-2">
                {data.relatedThemes.map((theme) => (
                  <SmartLink key={theme.id} href={`/research/theme/${theme.id}`} prefetch={false} className="rounded-full border border-white/10 bg-white/5 px-3 py-1 text-xs text-white/80 hover:bg-white/10">
                    {theme.theme}
                  </SmartLink>
                ))}
              </div>
            </div>
          )}
        </section>

        {!!data.densitySummary && (
          <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="mb-2 text-sm font-semibold text-white">분석 밀도</div>
            <p className="mb-3 text-xs text-[var(--muted)]">표준 섹터 20개 기업 중 얼마나 많은 종목에 고급 해석이 붙는지 보여줍니다.</p>
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm">
                <thead className="text-[11px] uppercase tracking-wide text-[var(--muted)]">
                  <tr>
                    <th className="text-left py-2">항목</th>
                    <th className="text-right py-2">커버 수</th>
                    <th className="text-right py-2">비율</th>
                    <th className="text-left py-2 pl-4">의미</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-white/10">
                  {[
                    ['Peer Group', data.densitySummary.peer, data.densitySummary.peerPct, '동종 그룹/비교군이 정의된 종목'],
                    ['Narrative', data.densitySummary.narrative, data.densitySummary.narrativePct, '내러티브 단계/열기 해석이 붙는 종목'],
                    ['Capital Flow', data.densitySummary.capitalFlow, data.densitySummary.capitalFlowPct, 'ETF/정책/자금유입 논리가 붙는 종목'],
                    ['Fallback', data.densitySummary.fallback, data.densitySummary.fallbackPct, 'segment/geo fallback이 준비된 대표 종목'],
                    ['Bottleneck', data.densitySummary.bottleneck, data.densitySummary.bottleneckPct, '병목/전환비용 해석이 붙는 종목'],
                  ].map(([label, count, pct, desc]) => (
                    <tr key={String(label)} className="text-white/90">
                      <td className="py-2">{label}</td>
                      <td className="py-2 text-right">{count}/20</td>
                      <td className="py-2 text-right">{pct}%</td>
                      <td className="py-2 pl-4 text-[var(--muted)]">{desc}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        )}

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="mb-3 text-sm font-semibold text-white">연관 대표 기업</div>
          <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
            {(data.items ?? []).map((item) => (
              <SmartLink key={item.ticker} href={`/company/${item.ticker}`} prefetch={false} className="rounded-2xl border border-white/10 bg-black/15 p-4 cursor-pointer hover:bg-white/[0.03] active:scale-[0.99]">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <div className="text-base font-semibold text-white">{item.rank}. {item.ticker}</div>
                    <div className="mt-1 text-sm text-[var(--muted)]">{item.name}</div>
                  </div>
                  <div className="text-right">
                    <ScoreBadge label="B" value={item.buyScore} title="기업 B 점수" kind="buy" className="text-xs" interactive={false} />
                    <div className="mt-2"><ActionBadge value={item.buyScore} compact interactive={false} /></div>
                  </div>
                </div>
                <div className="mt-3 grid grid-cols-2 md:grid-cols-5 gap-2 text-xs">
                  <div className="rounded-xl border border-white/10 bg-black/10 p-2"><div className="text-[var(--muted)]">총점</div><div className="mt-1 text-white">{item.totalScore ?? '—'}</div></div>
                  <div className="rounded-xl border border-white/10 bg-black/10 p-2"><div className="text-[var(--muted)]">매력</div><div className="mt-1 text-white">{item.appealScore ?? '—'}</div></div>
                  <div className="rounded-xl border border-white/10 bg-black/10 p-2"><div className="text-[var(--muted)]">과열</div><div className="mt-1 text-white">{item.crowdingScore ?? '—'}</div></div>
                  <div className="rounded-xl border border-white/10 bg-black/10 p-2"><div className="text-[var(--muted)]">거래량확인</div><div className="mt-1 text-white">{item.volumeConfirmationScore ?? '—'}</div></div>
                  <div className="rounded-xl border border-white/10 bg-black/10 p-2"><div className="text-[var(--muted)]">실패위험</div><div className="mt-1 text-white">{item.failureRiskScore ?? '—'}</div></div>
                </div>
                <div className="mt-3 flex flex-wrap gap-2 text-[11px] text-white/80">
                  {item.bottomState ? <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1">{item.bottomState}</span> : null}
                  {item.confirmedBottomState && item.confirmedBottomState !== '미충족' ? <span className="rounded-full border border-fuchsia-500/20 bg-fuchsia-500/10 px-2 py-1 text-fuchsia-100">확신형 {item.confirmedBottomState}{typeof item.confirmedBottomScore === 'number' ? ` ${item.confirmedBottomScore}` : ''}</span> : null}
                  {typeof item.priceBottomScore === 'number' ? <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1">가격 {item.priceBottomScore}</span> : null}
                  {typeof item.revenueGrowthYoY === 'number' ? <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1">매출 YoY {fmtPct(item.revenueGrowthYoY)}</span> : null}
                  {typeof item.evToSales === 'number' ? <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1">EV/Sales {fmtNum(item.evToSales)}x</span> : null}
                </div>
              </SmartLink>
            ))}
          </div>
        </section>

        {!!data.sectorScores?.length && (
          <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="mb-3 text-sm font-semibold text-white">섹터 바닥 상태</div>
            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
              {data.sectorScores.map((sector) => (
                <div key={sector.key} className="rounded-xl border border-white/10 bg-black/15 p-4">
                  <div className="flex items-center justify-between gap-3">
                    <div>
                      <div className="font-medium text-white">{sector.label}</div>
                      <div className="mt-1 text-xs text-[var(--muted)]">{sector.bottomState ?? '바닥 정보 없음'}{sector.actionLabel ? ` · ${sector.actionLabel}` : ''}</div>
                    </div>
                    <div className="flex flex-wrap gap-2 text-xs">
                      {sector.rotationLabel ? <span className={`rounded-full border px-2 py-1 ${rotationTone(sector.rotationLabel)}`}>{sector.rotationLabel}</span> : null}
                      <ScoreBadge label="순환" value={sector.rotationScore ?? null} title="섹터 순환 점수" kind="buy" interactive={false} />
                      <ScoreBadge label="바닥" value={sector.bottomScore ?? null} title="섹터 바닥 점수" kind="appeal" interactive={false} />
                      <ScoreBadge label="거래량" value={sector.avgVolumeConfirmationScore ?? null} title="대표 종목 평균 거래량 확인 점수" kind="appeal" interactive={false} />
                      <ScoreBadge label="실패위험" value={sector.bottomFailureRiskScore ?? null} title="섹터 바닥 실패 위험" kind="crowding" interactive={false} />
                    </div>
                  </div>
                  {!!sector.rotationReasons?.length && (
                    <div className="mt-3 rounded-lg border border-cyan-500/15 bg-cyan-500/5 p-2 text-[11px] text-cyan-50">{sector.rotationReasons[0]}</div>
                  )}
                  {sector.failureSummary ? <div className="mt-3 rounded-lg border border-rose-500/15 bg-rose-500/5 p-2 text-[11px] text-rose-100">{sector.failureSummary}</div> : null}
                </div>
              ))}
            </div>
          </section>
        )}
      </div>
    </main>
  );
}
