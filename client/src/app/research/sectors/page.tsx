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
  expectedLeadershipWindow?: string | null;
  expectedLeadershipMessage?: string | null;
  reasons: string[];
};

type SectorListItem = {
  id: string;
  label: string;
  description: string;
  sectorKey: string;
  tickers: string[];
  sectorSummary?: {
    averageBuyScore?: number | null;
    averageBottomScore?: number | null;
    averageBottomFailureRiskScore?: number | null;
    averageVolumeConfirmationScore?: number | null;
    averageAppealScore?: number | null;
    averageCrowdingScore?: number | null;
    averageQualityScore?: number | null;
    averageRotationScore?: number | null;
  } | null;
  rotation?: RotationItem | null;
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
  relatedThemes?: Array<{ id: string; theme: string }>;
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

type SectorListResponse = {
  sectors?: SectorListItem[];
  rotation?: {
    regime: string;
    confidence?: number;
    regimeScores?: Record<string, number>;
    summary: string;
    favoredNext: string[];
    fadingNext: string[];
    currentLeaders?: RotationCandidateView[];
    nextCandidates?: RotationCandidateView[];
    secondaryCandidates?: RotationCandidateView[];
    fadingCandidates?: RotationCandidateView[];
    calculatedAt?: string | null;
    currentMarketOverlay?: boolean;
    methodology?: string;
  } | null;
};

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

type BacktestMetric = {
  sampleCount?: number | null;
  top1HitRate?: number | null;
  top3HitRate?: number | null;
  top1AvgExcessPct?: number | null;
  top3AvgExcessPct?: number | null;
  top1PositiveReturnRatePct?: number | null;
  top3PositiveReturnRatePct?: number | null;
  top1UniverseHitRatePct?: number | null;
  top3UniverseHitRatePct?: number | null;
  top1AvgUniverseExcessPct?: number | null;
  top3AvgUniverseExcessPct?: number | null;
  top1HitRate95LowerPct?: number | null;
  top1HitRate95UpperPct?: number | null;
  overlapAdjustmentLagMonths?: number | null;
  top1HitRateOverlapAdjusted95LowerPct?: number | null;
  top1HitRateOverlapAdjusted95UpperPct?: number | null;
};

type SectorBacktestResponse = {
  methodology?: {
    version?: string;
    dataBasis?: string;
    benchmark?: string;
    scoreFormula?: string;
    methodologyOrigin?: string;
    references?: string[];
    liveRelativeStrengthLayerMatched?: boolean;
    fullRotationForecastValidated?: boolean;
    validatedScope?: string;
    compatibility?: string;
    liveMethodologyMatched?: boolean;
    warning?: string;
  };
  dateRange?: { from: string | null; to: string | null };
  rebalanceCount?: number;
  averageMonthlyTurnoverPct?: number;
  summary?: {
    oneMonth?: BacktestMetric;
    threeMonth?: BacktestMetric;
    sixMonth?: BacktestMetric;
  };
  comparisonBaseline?: {
    version?: string;
    compatibility?: string;
    summary?: SectorBacktestResponse['summary'];
    assessment?: {
      status?: 'IMPROVED' | 'MIXED' | 'INSUFFICIENT';
      threeMonthHitDeltaPct?: number;
      sixMonthHitDeltaPct?: number;
      threeMonthAverageExcessDeltaPct?: number;
      sixMonthAverageExcessDeltaPct?: number;
    };
  };
  warnings?: string[];
};

async function fetchData(): Promise<{
  sectors: SectorListResponse;
  backtest: SectorBacktestResponse | null;
  legacyBacktest: SectorBacktestResponse | null;
}> {
  const [sectors, backtest, legacyBacktest] = await Promise.all([
    fetchServerJson<SectorListResponse>('/api/research/sectors', { revalidate: 300 }),
    fetchServerJson<SectorBacktestResponse>('/api/research/sectors/backtest/current?years=7', { revalidate: 21600 })
      .catch(() => null),
    fetchServerJson<SectorBacktestResponse>('/api/research/sectors/backtest?years=5', { revalidate: 86400 })
      .catch(() => null),
  ]);
  return { sectors: sectors ?? { sectors: [] }, backtest, legacyBacktest };
}

function rotationTone(label?: RotationItem['rotationLabel'] | null) {
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

function scoreValue(value?: number | null) {
  return typeof value === 'number' && Number.isFinite(value) ? value : '—';
}

function compactUsd(value?: number | null) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—';
  const sign = value > 0 ? '+' : '';
  return `${sign}${new Intl.NumberFormat('ko-KR', {
    style: 'currency', currency: 'USD', notation: 'compact', maximumFractionDigits: 1,
  }).format(value)}`;
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

export default async function ResearchSectorsPage() {
  const { sectors: data, backtest, legacyBacktest } = await fetchData();
  const sectors = data.sectors ?? [];
  const rankedSectors = [...sectors]
    .filter((sector) => typeof sector.rotation?.rotationScore === 'number')
    .sort((left, right) =>
      (right.rotation?.rotationScore ?? -1) - (left.rotation?.rotationScore ?? -1)
      || left.sectorKey.localeCompare(right.sectorKey));
  const rotationRankBySectorKey = new Map(
    rankedSectors.map((sector, index) => [sector.sectorKey, index + 1])
  );
  const nearCandidateSectors = rankedSectors
    .filter((sector) => sector.rotation?.state === 'LAGGING')
    .slice(0, 3);
  const hasForwardCandidate = (data.rotation?.nextCandidates?.length ?? 0) > 0
    || (data.rotation?.secondaryCandidates?.length ?? 0) > 0;

  return (
    <main className="flex-1 p-4 md:p-6 max-w-6xl mx-auto w-full">
      <div className="space-y-6">
        <header className="space-y-2">
          <div className="text-xs text-[var(--muted)]"><SmartLink href="/research" className="cursor-pointer hover:text-white">리서치</SmartLink> / 표준 11개 섹터</div>
          <h1 className="text-2xl font-bold tracking-tight">표준 11개 섹터</h1>
          <p className="text-sm text-[var(--muted)]">영상 기반 전략 테마와 별도로, 시장 전체를 보는 표준 섹터 레이어입니다.</p>
        </header>

        <ScoreLegend />

        {!!data.rotation && (
          <>
            <section className="rounded-2xl border border-cyan-500/20 bg-cyan-500/5 p-5">
              <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
                <div>
                  <div className="text-sm font-semibold text-cyan-100">섹터 순환</div>
                  <div className="mt-1 text-xs text-cyan-50/80">{data.rotation.regime}{typeof data.rotation.confidence === 'number' ? ` · 후보 간 분리도 ${data.rotation.confidence}/100` : ''}</div>
                  {data.rotation.regimeScores && Object.keys(data.rotation.regimeScores).length > 0 ? (
                    <div className="mt-2 flex flex-wrap gap-1.5 text-[10px] text-cyan-50/75">
                      {Object.entries(data.rotation.regimeScores)
                        .sort(([, left], [, right]) => right - left)
                        .map(([key, value]) => (
                          <span key={key} className="rounded-full border border-cyan-400/15 bg-black/10 px-2 py-1">
                            {regimeLabel(key)} {value}
                          </span>
                        ))}
                    </div>
                  ) : null}
                  <p className="mt-2 text-sm text-cyan-50/90">{data.rotation.summary}</p>
                  {data.rotation.currentMarketOverlay && (
                    <div className="mt-2 inline-flex flex-wrap items-center gap-2 rounded-full border border-emerald-400/20 bg-emerald-400/10 px-3 py-1 text-[10px] text-emerald-100">
                      <span>현재 시장 재계산</span>
                      {data.rotation.calculatedAt ? <span>{data.rotation.calculatedAt.replace('T', ' ').replace('Z', ' UTC')}</span> : null}
                      {data.rotation.methodology ? <span className="text-emerald-100/70">{data.rotation.methodology}</span> : null}
                    </div>
                  )}
                </div>
                <div className="grid gap-2 text-xs text-cyan-50/90">
                  <div>
                    <span className="text-cyan-200">모델 우선 관찰</span>
                    <div className="mt-1 flex flex-wrap gap-2">
                      {data.rotation.favoredNext.map((label) => <span key={label} className="rounded-full border border-cyan-400/20 bg-cyan-400/10 px-2 py-1">{label}</span>)}
                    </div>
                  </div>
                  <div>
                    <span className="text-cyan-200">약화 후보</span>
                    <div className="mt-1 flex flex-wrap gap-2">
                      {data.rotation.fadingNext.map((label) => <span key={label} className="rounded-full border border-rose-400/20 bg-rose-400/10 px-2 py-1 text-rose-100">{label}</span>)}
                    </div>
                  </div>
                </div>
              </div>
              <div className="mt-4 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                <div className="rounded-xl border border-sky-400/20 bg-sky-400/5 p-3">
                  <div className="text-xs font-semibold text-sky-200">현재 순환 상위</div>
                  <div className="mt-2 space-y-2">
                    {(data.rotation.currentLeaders ?? []).map((item, index) => (
                      <div key={item.sectorKey} className="rounded-lg border border-white/10 bg-black/10 p-2">
                        <div className="flex items-center justify-between gap-2">
                          <div className="flex items-center gap-1.5 text-sm font-medium text-white">
                            <span>{item.label}</span>
                            <span className={`rounded-full border px-1.5 py-0.5 text-[9px] ${index === 0 ? 'border-emerald-400/25 bg-emerald-400/10 text-emerald-100' : 'border-white/10 bg-white/5 text-white/55'}`}>
                              {index === 0 ? '현재 모멘텀 1위' : '분산 관찰'}
                            </span>
                          </div>
                          <div className="text-[11px] text-sky-100">{horizonLabel(item.expectedLeadershipWindow)}</div>
                        </div>
                        <div className="mt-1 text-[11px] text-white/70">{item.expectedLeadershipMessage}</div>
                        <ConfirmationStatus item={item} />
                      </div>
                    ))}
                  </div>
                </div>
                <div className="rounded-xl border border-emerald-400/20 bg-emerald-400/5 p-3">
                  <div className="text-xs font-semibold text-emerald-200">다음 주도 후보</div>
                  <div className="mt-2 space-y-2">
                    {(data.rotation.nextCandidates ?? []).map((item) => (
                      <div key={item.sectorKey} className="rounded-lg border border-white/10 bg-black/10 p-2">
                        <div className="flex items-center justify-between gap-2">
                          <div className="text-sm font-medium text-white">{item.label}</div>
                          <div className="text-[11px] text-emerald-100">{horizonLabel(item.expectedLeadershipWindow)}</div>
                        </div>
                        <div className="mt-1 text-[11px] text-white/70">{item.expectedLeadershipMessage}</div>
                        <ConfirmationStatus item={item} />
                      </div>
                    ))}
                    {(data.rotation.nextCandidates ?? []).length === 0 ? (
                      <div className="rounded-lg border border-dashed border-white/10 bg-black/10 p-3 text-[11px] leading-relaxed text-white/55">
                        현재는 1~3개월 전환 조건을 동시에 충족한 섹터가 없습니다.
                      </div>
                    ) : null}
                  </div>
                </div>
                <div className="rounded-xl border border-amber-400/20 bg-amber-400/5 p-3">
                  <div className="text-xs font-semibold text-amber-200">그다음 후보</div>
                  <div className="mt-2 space-y-2">
                    {(data.rotation.secondaryCandidates ?? []).map((item) => (
                      <div key={item.sectorKey} className="rounded-lg border border-white/10 bg-black/10 p-2">
                        <div className="flex items-center justify-between gap-2">
                          <div className="text-sm font-medium text-white">{item.label}</div>
                          <div className="text-[11px] text-amber-100">{horizonLabel(item.expectedLeadershipWindow)}</div>
                        </div>
                        <div className="mt-1 text-[11px] text-white/70">{item.expectedLeadershipMessage}</div>
                        <ConfirmationStatus item={item} />
                      </div>
                    ))}
                    {(data.rotation.secondaryCandidates ?? []).length === 0 ? (
                      <div className="rounded-lg border border-dashed border-white/10 bg-black/10 p-3 text-[11px] leading-relaxed text-white/55">
                        현재는 3~6개월 선행 조건을 동시에 충족한 섹터가 없습니다.
                      </div>
                    ) : null}
                  </div>
                </div>
                <div className="rounded-xl border border-rose-400/20 bg-rose-400/5 p-3">
                  <div className="text-xs font-semibold text-rose-200">약화/후순위</div>
                  <div className="mt-2 space-y-2">
                    {(data.rotation.fadingCandidates ?? []).map((item) => (
                      <div key={item.sectorKey} className="rounded-lg border border-white/10 bg-black/10 p-2">
                        <div className="flex items-center justify-between gap-2">
                          <div className="text-sm font-medium text-white">{item.label}</div>
                          <div className="text-[11px] text-rose-100">{horizonLabel(item.expectedLeadershipWindow)}</div>
                        </div>
                        <div className="mt-1 text-[11px] text-white/70">{item.expectedLeadershipMessage}</div>
                        <ConfirmationStatus item={item} />
                      </div>
                    ))}
                  </div>
                </div>
              </div>
              {!hasForwardCandidate && nearCandidateSectors.length > 0 ? (
                <div className="mt-3 rounded-xl border border-violet-400/20 bg-violet-400/5 p-3">
                  <div className="text-xs font-semibold text-violet-100">승격 전 관찰 순위 · 확정 후보 아님</div>
                  <p className="mt-1 text-[11px] leading-relaxed text-white/60">
                    현재 1~3개월·3~6개월 조건을 동시에 통과한 섹터가 없습니다. 임계값을 억지로 낮추지 않고,
                    아직 LAGGING인 섹터 중 현 순환 점수가 높은 순서만 진단용으로 표시합니다.
                  </p>
                  <div className="mt-2 grid gap-2 md:grid-cols-3">
                    {nearCandidateSectors.map((sector) => (
                      <SmartLink
                        key={sector.sectorKey}
                        href={`/research/sector/${sector.id}`}
                        className="rounded-lg border border-white/10 bg-black/10 p-2 transition-colors hover:border-violet-300/30 hover:bg-violet-300/5"
                      >
                        <div className="flex items-center justify-between gap-2">
                          <span className="text-sm font-medium text-white">{sector.label}</span>
                          <span className="text-[10px] text-violet-100">순환 {sector.rotation?.rotationScore ?? '—'}</span>
                        </div>
                        <div className="mt-1 text-[10px] text-white/55">
                          거시 {sector.rotation?.macroFitScore ?? '—'} · 상대강도 {sector.rotation?.relativeStrengthScore ?? '—'}
                        </div>
                        <div className="mt-1 text-[10px] leading-relaxed text-white/60">
                          {sector.rotation?.expectedLeadershipMessage ?? '주도 전환 조건의 동시 충족을 기다리는 단계'}
                        </div>
                      </SmartLink>
                    ))}
                  </div>
                </div>
              ) : null}
            </section>

            <section className="rounded-2xl border border-white/10 bg-[var(--card)] p-5">
              <div className="text-sm font-semibold text-white">순환 예측을 읽는 법</div>
              <p className="mt-2 text-xs leading-relaxed text-[var(--muted)]">
                순환 점수는 수익률 확률이 아니라 11개 섹터의 상대 순위입니다. 경기 국면만으로 후보를 정하지 않고,
                상대강도와 거시 순위가 먼저 후보를 만들고, 기준일이 확인된 이익추정 변화와 공식 ETF 생성·환매까지
                충족돼야 실제 주도 전환 확인으로 올립니다.
              </p>
              <div className="mt-4 grid grid-cols-2 gap-2 text-xs md:grid-cols-4 xl:grid-cols-8">
                {[
                  ['거시 적합', '성장·물가·금리·유동성 국면'],
                  ['상대강도', '최근 1개월 제외 6·12개월 SPY 대비 총수익률·변동성'],
                  ['EPS 리비전 breadth', '구성종목 30일 EPS 추정 상·하향 비율; 날짜·50% 이상 커버리지 필수'],
                  ['펀더멘털', '성장·마진·현금흐름'],
                  ['ETF 생성·환매', 'State Street 공식 NAV·발행좌수의 5·20일 변화'],
                  ['가격 breadth', '추적 구성종목 중 20·50·200일선 위 비율; 전체 ETF 보유종목 아님'],
                  ['밸류', '자체 역사·동종 섹터 대비'],
                  ['과열완화', '쏠림·고평가 역풍 차감'],
                ].map(([label, detail]) => (
                  <div key={label} className="rounded-xl border border-white/10 bg-black/15 p-3">
                    <div className="font-medium text-white">{label}</div>
                    <div className="mt-1 text-[10px] leading-relaxed text-[var(--muted)]">{detail}</div>
                  </div>
                ))}
              </div>
              <div className="mt-3 rounded-xl border border-amber-500/15 bg-amber-500/5 px-3 py-2 text-[11px] leading-relaxed text-amber-50/80">
                ‘1~3개월’과 ‘3~6개월’은 예상 관찰 창이지 도달 보장이 아닙니다. 순환 상위는 주도 확정이나 추격 매수
                신호가 아니며, 현재 이익추정·공식 ETF 생성·환매가 없으면 확인 진행 중 이하로만 표시합니다.
              </div>
            </section>
          </>
        )}

        {!!backtest?.summary && (
          <section className="rounded-2xl border border-emerald-500/20 bg-emerald-500/5 p-5">
            <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
              <div>
                <div className="text-sm font-semibold text-emerald-100">현 산식 장기 워크포워드 검증</div>
                <div className="mt-1 text-xs text-emerald-50/80">월말 무누수 리밸런스 · {backtest.dateRange?.from ?? '—'} ~ {backtest.dateRange?.to ?? '—'} · {backtest.rebalanceCount ?? 0}회</div>
                <p className="mt-2 text-sm text-emerald-50/90">배당·분배금 재투자를 반영한 조정주가로 최근 1개월을 제외한 6·12개월 상대 모멘텀을 변동성 조정하고, 이후 SPY 및 섹터 동일가중 수익률을 이겼는지 검증합니다.</p>
                {backtest.methodology?.scoreFormula ? <div className="mt-2 text-[11px] text-emerald-100/70">산식 · {backtest.methodology.scoreFormula}</div> : null}
                <div className="mt-2 flex flex-wrap gap-2 text-[10px]">
                  <span className="rounded-full border border-emerald-400/20 bg-emerald-400/10 px-2 py-1 text-emerald-100">총수익률 기준</span>
                  <span className="rounded-full border border-emerald-400/20 bg-emerald-400/10 px-2 py-1 text-emerald-100">기관식 모멘텀 프록시</span>
                  <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1 text-white/70">월평균 교체 {backtest.averageMonthlyTurnoverPct ?? '—'}%</span>
                  <span className="rounded-full border border-amber-400/20 bg-amber-400/10 px-2 py-1 text-amber-100">전체 순환 예측 검증 아님</span>
                </div>
              </div>
              <div className="grid grid-cols-1 gap-2 text-xs text-emerald-50/90 md:min-w-[440px]">
                {([
                  ['1M', backtest.summary.oneMonth],
                  ['3M', backtest.summary.threeMonth],
                  ['6M', backtest.summary.sixMonth],
                ] as const).map(([label, metric]) => (
                  <div key={label} className="grid grid-cols-4 gap-2 rounded-xl border border-white/10 bg-black/10 p-3">
                    <div><div className="text-[10px] text-emerald-200">{label} Top1 Hit</div><div className="mt-1 font-semibold text-white">{metric?.top1HitRate ?? '—'}%</div></div>
                    <div><div className="text-[10px] text-emerald-200">{label} Top3 Hit</div><div className="mt-1 font-semibold text-white">{metric?.top3HitRate ?? '—'}%</div></div>
                    <div><div className="text-[10px] text-emerald-200">Top1 초과</div><div className="mt-1 font-semibold text-white">{typeof metric?.top1AvgExcessPct === 'number' ? `${metric.top1AvgExcessPct >= 0 ? '+' : ''}${metric.top1AvgExcessPct.toFixed(2)}%p` : '—'}</div></div>
                    <div><div className="text-[10px] text-emerald-200">Top3 초과</div><div className="mt-1 font-semibold text-white">{typeof metric?.top3AvgExcessPct === 'number' ? `${metric.top3AvgExcessPct >= 0 ? '+' : ''}${metric.top3AvgExcessPct.toFixed(2)}%p` : '—'}</div></div>
                    <div className="col-span-4 mt-1 grid grid-cols-4 gap-2 border-t border-white/10 pt-2 text-[10px] text-white/60">
                      <div>표본 {metric?.sampleCount ?? '—'}회</div>
                      <div>상승 {metric?.top1PositiveReturnRatePct ?? '—'}%</div>
                      <div>동일가중 상회 {metric?.top1UniverseHitRatePct ?? '—'}%</div>
                      <div>
                        보정 95% 구간 {metric?.top1HitRateOverlapAdjusted95LowerPct ?? metric?.top1HitRate95LowerPct ?? '—'}~{metric?.top1HitRateOverlapAdjusted95UpperPct ?? metric?.top1HitRate95UpperPct ?? '—'}%
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
            <div className="mt-3 text-[11px] leading-relaxed text-emerald-50/70">
              Hit는 해당 월말 상위 후보가 이후 SPY 총수익률을 이긴 비율입니다. 3·6개월 결과의 월별 관측은 서로 겹치므로,
              상승률·섹터 동일가중 대비 결과와 중첩 시계열 보정 95% 구간을 함께 표시합니다.
            </div>
            {backtest.comparisonBaseline?.compatibility === 'COMPARISON_ONLY_NOT_LIVE' ? (
              <div className="mt-3 rounded-xl border border-sky-400/15 bg-sky-400/5 p-3 text-[11px] text-sky-50/75">
                <div className="font-semibold text-sky-100">V1 구형 상대강도와 동일 구간 비교</div>
                <div className="mt-1 text-[10px] text-sky-100/65">
                  판정 {backtest.comparisonBaseline.assessment?.status ?? '—'}
                  {' · '}3개월 초과수익 개선 {backtest.comparisonBaseline.assessment?.threeMonthAverageExcessDeltaPct ?? '—'}%p
                  {' · '}6개월 Hit 개선 {backtest.comparisonBaseline.assessment?.sixMonthHitDeltaPct ?? '—'}%p
                  {' · '}6개월 초과수익 개선 {backtest.comparisonBaseline.assessment?.sixMonthAverageExcessDeltaPct ?? '—'}%p
                </div>
                <div className="mt-1 grid gap-2 sm:grid-cols-3">
                  {([
                    ['1개월', backtest.summary.oneMonth, backtest.comparisonBaseline.summary?.oneMonth],
                    ['3개월', backtest.summary.threeMonth, backtest.comparisonBaseline.summary?.threeMonth],
                    ['6개월', backtest.summary.sixMonth, backtest.comparisonBaseline.summary?.sixMonth],
                  ] as const).map(([label, current, baseline]) => (
                    <div key={label} className="rounded-lg border border-white/10 bg-black/10 px-3 py-2">
                      <div>{label} Top1 Hit</div>
                      <div className="mt-1 text-white">V2 {current?.top1HitRate ?? '—'}% · V1 {baseline?.top1HitRate ?? '—'}%</div>
                      <div className="mt-0.5 text-[10px] text-white/55">평균 초과 V2 {current?.top1AvgExcessPct ?? '—'}%p · V1 {baseline?.top1AvgExcessPct ?? '—'}%p</div>
                    </div>
                  ))}
                </div>
              </div>
            ) : null}
            {(backtest.warnings ?? []).length > 0 ? (
              <ul className="mt-3 list-disc space-y-1 pl-4 text-[10px] leading-relaxed text-amber-100/75">
                {backtest.warnings?.map((warning) => <li key={warning}>{warning}</li>)}
              </ul>
            ) : null}
          </section>
        )}

        {legacyBacktest?.methodology?.compatibility === 'LEGACY_REFERENCE_ONLY' ? (
          <section className="rounded-2xl border border-white/10 bg-white/[0.02] p-4 text-xs text-white/55">
            <div className="font-semibold text-white/75">구형 5년 백테스트 · 참고 전용</div>
            <p className="mt-1 leading-relaxed">
              {legacyBacktest.methodology.warning ?? '현재 산식과 일치하지 않는 과거 연구 결과이므로 현 모델 적중률로 사용하지 않습니다.'}
            </p>
          </section>
        ) : null}

        <section className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {sectors.map((sector) => (
            <SmartLink key={sector.id} href={`/research/sector/${sector.id}`} className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5 cursor-pointer hover:bg-white/[0.03] active:scale-[0.99]">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <div className="text-lg font-semibold text-white">{sector.label}</div>
                  <div className="mt-1 text-sm text-[var(--muted)]">{sector.description}</div>
                </div>
                <div className="text-right">
                  <ScoreBadge label="B" value={sector.sectorSummary?.averageBuyScore ?? null} title="표준 섹터 기준 평균 B 점수" kind="buy" interactive={false} />
                  <div className="mt-2"><ActionBadge value={sector.sectorSummary?.averageBuyScore ?? null} compact interactive={false} /></div>
                </div>
              </div>
              <div className="mt-3 flex flex-wrap gap-2 text-xs">
                <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-white/80">대표 {sector.tickers.length}종목</span>
                {sector.rotation ? <span className={`rounded-full border px-2 py-0.5 ${rotationTone(sector.rotation.rotationLabel)}`}>{sector.rotation.rotationLabel}</span> : null}
                {rotationRankBySectorKey.has(sector.sectorKey) ? (
                  <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-white/75">
                    순환 #{rotationRankBySectorKey.get(sector.sectorKey)}/{rankedSectors.length}
                  </span>
                ) : null}
                <ScoreBadge label="순환" value={sector.rotation?.rotationScore ?? sector.sectorSummary?.averageRotationScore ?? null} title="섹터 순환 우선순위 점수" kind="buy" interactive={false} />
                <ScoreBadge label="바닥" value={sector.sectorSummary?.averageBottomScore ?? null} title="표준 섹터 평균 바닥 점수" kind="appeal" interactive={false} />
                <ScoreBadge label="거래량" value={sector.sectorSummary?.averageVolumeConfirmationScore ?? null} title="대표 종목 평균 거래량 확인 점수" kind="appeal" interactive={false} />
                <ScoreBadge label="실패위험" value={sector.sectorSummary?.averageBottomFailureRiskScore ?? null} title="표준 섹터 평균 바닥 실패 위험" kind="crowding" interactive={false} />
                <ScoreBadge label="Q" value={sector.sectorSummary?.averageQualityScore ?? null} title="표준 섹터 구조 점수" kind="quality" interactive={false} />
                <ScoreBadge label="매력" value={sector.sectorSummary?.averageAppealScore ?? null} title="표준 섹터 매력도" kind="appeal" interactive={false} />
                <ScoreBadge label="과열" value={sector.sectorSummary?.averageCrowdingScore ?? null} title="표준 섹터 과열도" kind="crowding" interactive={false} />
              </div>
              {!!sector.rotation?.reasons?.length && (
                <div className="mt-3 rounded-xl border border-white/10 bg-black/10 p-3 text-[11px] text-white/75">
                  {sector.rotation.reasons[0]}
                </div>
              )}
              {!!sector.rotation && (
                <div className="mt-3">
                  {sector.rotation.expectedLeadershipMessage ? (
                    <div className="mb-2 rounded-xl border border-cyan-500/15 bg-cyan-500/5 px-3 py-2 text-[11px] text-cyan-50/85">
                      예상 관찰 창 {horizonLabel(sector.rotation.expectedLeadershipWindow ?? undefined)} · {sector.rotation.expectedLeadershipMessage}
                    </div>
                  ) : null}
                  <div className="grid grid-cols-4 gap-1.5 text-center text-[10px] sm:grid-cols-8">
                    {[
                      ['거시', sector.rotation.macroFitScore],
                      ['상대강도', sector.rotation.relativeStrengthScore],
                      ['펀더멘털 참고', sector.rotation.fundamentalScore],
                      ['EPS 리비전', sector.rotation.earningsRevisionScore],
                      ['ETF 생성·환매', sector.rotation.flowScore],
                      ['가격 breadth', sector.rotation.priceBreadthScore],
                      ['밸류', sector.rotation.valuationScore],
                      ['과열완화', sector.rotation.crowdingReliefScore],
                    ].map(([label, value]) => (
                      <div key={label} className="rounded-lg border border-white/10 bg-black/10 px-1.5 py-2">
                        <div className="text-white/55">{label}</div>
                        <div className="mt-1 font-semibold text-white">{scoreValue(value as number | null | undefined)}</div>
                      </div>
                    ))}
                  </div>
                  {sector.rotation.earningsRevisionObservedOn ? (
                    <div className="mt-2 text-[10px] text-white/55">
                      EPS 리비전 {sector.rotation.earningsRevisionObservedOn}
                      {typeof sector.rotation.earningsRevisionCoveragePct === 'number' ? ` · 커버리지 ${sector.rotation.earningsRevisionCoveragePct}%` : ''}
                      {typeof sector.rotation.earningsRevisionUpPct === 'number' ? ` · 상향 ${sector.rotation.earningsRevisionUpPct}%` : ''}
                      {typeof sector.rotation.earningsRevisionDownPct === 'number' ? ` · 하향 ${sector.rotation.earningsRevisionDownPct}%` : ''}
                    </div>
                  ) : (
                    <div className="mt-2 text-[10px] text-white/45">EPS 리비전: 날짜·커버리지 충족 자료 없음</div>
                  )}
                  {sector.rotation.fundFlowObservedOn ? (
                    <div className="mt-1 text-[10px] text-white/55">
                      ETF 생성·환매 {sector.rotation.fundFlowObservedOn}
                      {' · '}5일 {compactUsd(sector.rotation.fundFlow5dUsd)} ({sector.rotation.fundFlow5dPct?.toFixed(2) ?? '—'}%)
                      {' · '}20일 {compactUsd(sector.rotation.fundFlow20dUsd)} ({sector.rotation.fundFlow20dPct?.toFixed(2) ?? '—'}%)
                    </div>
                  ) : (
                    <div className="mt-1 text-[10px] text-white/45">ETF 생성·환매: 7일 이내 공식 자료 없음</div>
                  )}
                  {sector.rotation.priceBreadthObservedOn ? (
                    <div className="mt-1 text-[10px] text-white/55">
                      추적 종목 breadth {sector.rotation.priceBreadthObservedOn}
                      {' · '}커버리지 {sector.rotation.priceBreadthCoveragePct ?? '—'}%
                      {' · '}MA20 {sector.rotation.aboveMa20Pct ?? '—'}%
                      {' · '}MA50 {sector.rotation.aboveMa50Pct ?? '—'}%
                      {' · '}MA200 {sector.rotation.aboveMa200Pct ?? '—'}%
                    </div>
                  ) : (
                    <div className="mt-1 text-[10px] text-white/45">가격 breadth: 날짜·70% 이상 커버리지 충족 자료 없음</div>
                  )}
                </div>
              )}
              {!!sector.densitySummary && (
                <div className="mt-3 grid grid-cols-2 md:grid-cols-5 gap-2 text-[11px] text-white/75">
                  <div className="rounded-lg border border-white/10 bg-black/10 px-2 py-1">Peer {sector.densitySummary.peerPct}%</div>
                  <div className="rounded-lg border border-white/10 bg-black/10 px-2 py-1">Narr {sector.densitySummary.narrativePct}%</div>
                  <div className="rounded-lg border border-white/10 bg-black/10 px-2 py-1">Flow {sector.densitySummary.capitalFlowPct}%</div>
                  <div className="rounded-lg border border-white/10 bg-black/10 px-2 py-1">Fallback {sector.densitySummary.fallbackPct}%</div>
                  <div className="rounded-lg border border-white/10 bg-black/10 px-2 py-1">Bottle {sector.densitySummary.bottleneckPct}%</div>
                </div>
              )}
              {!!sector.relatedThemes?.length && (
                <div className="mt-3 flex flex-wrap gap-2 text-[11px] text-white/75">
                  {sector.relatedThemes.slice(0, 4).map((theme) => (
                    <span key={theme.id} className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5">{theme.theme}</span>
                  ))}
                </div>
              )}
            </SmartLink>
          ))}
        </section>
      </div>
    </main>
  );
}
