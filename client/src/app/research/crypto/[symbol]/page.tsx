import { ActionBadge, ScoreBadge, ScoreLegend } from "@/components/ScoreUI";
import { SmartLink } from "@/components/SmartLink";
import { BottomConfirmationChart } from "@/components/BottomConfirmationChart";
import { fetchServerJson } from "@/lib/server-api";

export const revalidate = 300;
export const dynamic = "force-dynamic";

type CryptoResponse = {
  profile: {
    symbol: string;
    name: string;
    category: string;
    narrativeTheme: string;
    linkedAsset: string;
    strengths: string[];
    risks: string[];
  };
  market: {
    asOf: string | null;
    price: number | null;
    return7d: number | null;
    return30d: number | null;
    return90d: number | null;
    volumeTrend30d: number | null;
    volatility30d: number | null;
    distanceFrom52wHigh: number | null;
    distanceFrom52wLow: number | null;
  };
  macro: {
    stance: '우호' | '중립' | '주의';
    summary: string;
    drivers: string[];
    liquidityScore: number | null;
    dollarScore: number | null;
    riskOnScore: number | null;
  };
  narrative: {
    theme: string;
    stage: 'EARLY' | 'MID' | 'OVERHEATED';
    heatScore: number;
    summary: string;
  };
  bottomUp: {
    networkScore: number;
    tokenomicsScore: number;
    adoptionScore: number;
    summary: string;
    strengths: string[];
    risks: string[];
  };
  moat: {
    moatType: string;
    moatScore: number;
    summary: string;
    reasons: string[];
  };
  supplyPressure: {
    unlockRisk: '낮음' | '보통' | '높음';
    dilutionRisk: '낮음' | '보통' | '높음';
    floatScore: number;
    fdvPremiumPct: number | null;
    circulatingRatioPct: number | null;
    summary: string;
    reasons: string[];
  };
  onchain: {
    tvlUsd: number | null;
    tvlTrend30dPct: number | null;
    fees30dAvgUsd: number | null;
    feesTrend30dPct: number | null;
    developerScore: number | null;
    communityScore: number | null;
    activityScore: number;
    summary: string;
    reasons: string[];
  };
  flows: {
    stablecoinDemandScore: number | null;
    stablecoinDemandLabel: '확장' | '중립' | '둔화' | '판단불가';
    stablecoinDominancePct: number | null;
    altSeasonScore: number;
    altSeasonLabel: 'BTC 시즌' | '중립' | '알트 시즌';
    altSeasonInsight: string;
    btcDominanceScore: number;
    btcDominanceLabel: 'BTC 주도' | '알트 확산' | '균형';
    btcDominancePct: number | null;
    etfFlowProxy: '강함' | '보통' | '약함';
    etfDailyNetFlowUsd: number | null;
    etfWeeklyNetFlowUsd: number | null;
    exchangeNetflowProxy: '유입 우세' | '중립' | '유출 우세';
    exchangeNetflowInsight: string;
    exchangeFlowRisk: '낮음' | '보통' | '높음';
    derivativesHeat: '낮음' | '보통' | '높음';
    volumeToMarketCapPct: number | null;
    summary: string;
    reasons: string[];
  };
  trendCharts: {
    btcDominanceProxy30d: Array<{ date: string; value: number }>;
    stablecoinMcap30d: Array<{ date: string; value: number }>;
    etfNetFlow30d: Array<{ date: string; value: number }>;
    altSeasonProxy30d: Array<{ date: string; value: number }>;
    exchangeNetflowProxy30d: Array<{ date: string; value: number }>;
  };
  freshness: {
    marketObservedOn: string | null;
    supportingEvidenceObservedOn: string | null;
    marketAgeDays: number | null;
    supportingEvidenceAgeDays: number | null;
    maximumMarketAgeDays: number;
    maximumSupportingEvidenceAgeDays: number;
    eligibleForDecisions: boolean;
    status: 'CURRENT' | 'STALE' | 'UNKNOWN';
    explanation: string;
  };
  buyScore: {
    appealScore: number;
    crowdingScore: number;
    buyScore: number;
    action: string;
    actionLabel: string;
    reasons: string[];
  };
  bottomSignal: {
    score: number;
    state: '바닥 아님' | '바닥 시도' | '재시험 구간' | '1차 확인' | '구조적 바닥 가능';
    actionBias: '대기' | '관찰 매수' | '분할 매수' | '확인 우선';
    summary: string;
    volumeConfirmationScore?: number;
    failureRiskScore?: number;
    metrics: Array<{ key: string; label: string; score: number | null; status: 'positive' | 'neutral' | 'negative'; detail: string }>;
    chart: {
      points: Array<{ date: string; value: number }>;
      markers: Array<{ kind: 'peak' | 'candidate' | 'retest' | 'confirm' | 'current'; date: string; label: string; value: number }>;
    };
    confirmedBottom?: {
      score: number;
      state: '미충족' | '후보' | '확신';
      actionBias: '대기' | '관찰 매수' | '분할 매수';
      signalDate: string | null;
      daysSinceSignal: number | null;
      summary: string;
      recentVolumeRatio: number | null;
      contractionRatio: number | null;
      drawdown120dPct: number | null;
      ma20GapPct: number | null;
      recentDrop3dPct: number | null;
      reasons: string[];
      cautions: string[];
    };
    reasons: string[];
    cautions: string[];
    failureSignals?: string[];
  };
  positionSizing: {
    targetPositionPct: number;
    initialEntryPctOfTarget: number;
    reservePctOfTarget: number;
    summary: string;
  };
  verdicts: {
    quality: string;
    timing: string;
    valuationProxy: string;
    finalAction: string;
    oneLiners: {
      quality: string;
      timing: string;
      action: string;
    };
  };
  scenarios: {
    bullCase: string;
    baseCase: string;
    bearCase: string;
  };
  executionBridge: {
    asset: string;
    action: string;
    actionLabel: string;
    targetAllocationPct: number;
    alignment: 'aligned' | 'mixed' | 'conflicted';
    entryMode: '현물 코어' | '분할 현물' | '관찰 대기' | '축소/익절';
    riskBox: string;
    summary: string;
    timingNotes: string[];
  } | null;
};

async function fetchCrypto(symbol: string): Promise<CryptoResponse | null> {
  return fetchServerJson<CryptoResponse>(`/api/research/crypto/${encodeURIComponent(symbol.toUpperCase())}`, { revalidate: 300 });
}

function fmtPrice(value: number | null) {
  if (value === null || Number.isNaN(value)) return "—";
  return value >= 1000 ? value.toLocaleString("en-US", { maximumFractionDigits: 0 }) : value.toLocaleString("en-US", { maximumFractionDigits: 2 });
}

function fmtPct(value: number | null) {
  if (value === null || Number.isNaN(value)) return "—";
  return `${value >= 0 ? "+" : ""}${value.toFixed(1)}%`;
}

function fmtUsdMillions(value: number | null) {
  if (value === null || Number.isNaN(value)) return "—";
  return `${value >= 0 ? "+" : ""}$${(value / 1_000_000).toFixed(1)}M`;
}

function fmtUsdBillions(value: number | null) {
  if (value === null || Number.isNaN(value)) return "—";
  return `$${(value / 1_000_000_000).toFixed(2)}B`;
}

function fmtCryptoEtf(value: number | null, symbol: string) {
  if (symbol !== "BTC" && symbol !== "ETH") return "해당 없음";
  return fmtUsdMillions(value);
}

function fmtNullableScore(value: number | null, fallback = "보강 예정") {
  if (value === null || Number.isNaN(value)) return fallback;
  return value.toFixed(1);
}

function bottomStateTone(state: CryptoResponse['bottomSignal']['state']) {
  switch (state) {
    case '구조적 바닥 가능':
      return 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100';
    case '1차 확인':
      return 'border-cyan-500/20 bg-cyan-500/10 text-cyan-100';
    case '바닥 시도':
    case '재시험 구간':
      return 'border-amber-500/20 bg-amber-500/10 text-amber-100';
    default:
      return 'border-rose-500/20 bg-rose-500/10 text-rose-100';
  }
}

function confirmedBottomTone(state: '미충족' | '후보' | '확신') {
  switch (state) {
    case '확신':
      return 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100';
    case '후보':
      return 'border-amber-500/20 bg-amber-500/10 text-amber-100';
    default:
      return 'border-white/10 bg-white/5 text-white/75';
  }
}

function MiniLine({ points, color = "#67e8f9" }: { points: Array<{ date: string; value: number }>; color?: string }) {
  if (!points.length) {
    return <div className="h-16 rounded-xl border border-white/10 bg-black/15 p-3 text-xs text-[var(--muted)]">차트 데이터 부족</div>;
  }
  const width = 320;
  const height = 64;
  const values = points.map((p) => p.value);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  const d = points
    .map((p, idx) => {
      const x = (idx / Math.max(points.length - 1, 1)) * width;
      const y = height - ((p.value - min) / range) * (height - 8) - 4;
      return `${idx === 0 ? "M" : "L"} ${x.toFixed(1)} ${y.toFixed(1)}`;
    })
    .join(" ");
  return (
    <div className="rounded-xl border border-white/10 bg-black/15 p-3">
      <svg viewBox={`0 0 ${width} ${height}`} className="h-16 w-full">
        <path d={d} fill="none" stroke={color} strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      <div className="mt-2 flex items-center justify-between text-[11px] text-[var(--muted)]">
        <span>{points[0]?.date}</span>
        <span>{points.at(-1)?.date}</span>
      </div>
    </div>
  );
}

export default async function CryptoDetailPage({ params }: { params: Promise<{ symbol: string }> }) {
  const { symbol } = await params;
  const data = await fetchCrypto(symbol);

  if (!data) {
    return (
      <main className="flex-1 p-4 md:p-6 max-w-6xl mx-auto w-full">
        <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-6">
          <div className="text-lg font-semibold text-white">코인 데이터를 불러올 수 없습니다.</div>
          <div className="mt-2 text-sm text-[var(--muted)]">지원 심볼은 BTC, ETH, SOL, XRP, BNB 입니다.</div>
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 p-4 md:p-6 max-w-6xl mx-auto w-full">
      <div className="space-y-6">
        <header className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="text-xs text-[var(--muted)]">
            <SmartLink href="/research" className="cursor-pointer hover:text-white">리서치</SmartLink> / <SmartLink href="/research/crypto" className="cursor-pointer hover:text-white">코인</SmartLink> / {data.profile.symbol}
          </div>
          <div className="mt-3 flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
            <div>
              <h1 className="text-2xl font-bold tracking-tight">{data.profile.symbol} · {data.profile.name}</h1>
              <div className="mt-1 text-sm text-[var(--muted)]">{data.profile.category}</div>
              <div className="mt-2 flex flex-wrap gap-2 text-[11px]">
                <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-1 text-cyan-100">{data.profile.narrativeTheme}</span>
                <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1 text-white/80">연결 자산 {data.profile.linkedAsset}</span>
                <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1 text-white/80">기준일 {data.market.asOf ?? '—'}</span>
              </div>
            </div>
            <div className="text-right">
              <div className="text-xs text-[var(--muted)]">현재가</div>
              <div className="text-3xl font-semibold text-white">${fmtPrice(data.market.price)}</div>
            </div>
          </div>
        </header>

        <ScoreLegend compact defaultOpen />

        {!data.freshness.eligibleForDecisions ? (
          <section className="rounded-2xl border border-amber-500/30 bg-amber-500/10 p-4 text-sm text-amber-50">
            <div className="font-semibold">실행 판단 중지 · {data.freshness.status === 'UNKNOWN' ? '필수 보조근거 확인 불가' : '필수 보조근거 최신성 미충족'}</div>
            <div className="mt-1 text-amber-100/85">
              가격 {data.freshness.marketObservedOn ?? '확인 불가'}
              {data.freshness.marketAgeDays !== null ? ` (${data.freshness.marketAgeDays}일 경과)` : ''}
              {' · '}수급/온체인 {data.freshness.supportingEvidenceObservedOn ?? '확인 불가'}
              {data.freshness.supportingEvidenceAgeDays !== null ? ` (${data.freshness.supportingEvidenceAgeDays}일 경과)` : ''}
            </div>
            <div className="mt-1 text-xs text-amber-100/70">{data.freshness.explanation} 아래 B점수와 차트는 과거 근거 참고값입니다.</div>
          </section>
        ) : null}

        <section className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="mb-3 flex flex-wrap gap-2">
              <ScoreBadge label="B" value={data.buyScore.buyScore} title="코인 실행 점수입니다." kind="buy" />
              <ScoreBadge label="매력" value={data.buyScore.appealScore} title="거시·기초체력 기반 매력도입니다." kind="appeal" />
              <ScoreBadge label="과열" value={data.buyScore.crowdingScore} title="단기 군중화·과열도입니다." kind="crowding" />
              {data.freshness.eligibleForDecisions ? (
                <ActionBadge value={data.buyScore.buyScore} />
              ) : (
                <span className="inline-flex items-center rounded-full border border-amber-500/30 bg-amber-500/10 px-2.5 py-1 text-xs font-semibold text-amber-100">데이터 갱신 대기</span>
              )}
            </div>
            <div className="rounded-xl border border-white/10 bg-black/15 p-3 text-sm text-[var(--muted)]">{data.positionSizing.summary}</div>
            <div className="mt-3 grid grid-cols-3 gap-3 text-sm">
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">목표 비중</div>
                <div className="mt-1 text-xl font-semibold">{data.positionSizing.targetPositionPct}%</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">즉시 1차</div>
                <div className="mt-1 text-xl font-semibold">{data.positionSizing.initialEntryPctOfTarget}%</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">남길 현금</div>
                <div className="mt-1 text-xl font-semibold">{data.positionSizing.reservePctOfTarget}%</div>
              </div>
            </div>
            <div className="mt-3 space-y-1 text-xs text-[var(--muted)]">
              {data.buyScore.reasons.map((reason) => <div key={reason}>• {reason}</div>)}
            </div>
          </div>

          <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="text-lg font-semibold mb-3">거시 / 탑다운</div>
            <div className="flex flex-wrap gap-2 text-xs">
              <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1 text-white/80">거시 {data.macro.stance}</span>
              <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-1 text-cyan-100">{data.narrative.stage} · {data.narrative.heatScore}</span>
            </div>
            <div className="mt-3 rounded-xl border border-white/10 bg-black/15 p-3 text-sm text-[var(--muted)]">{data.macro.summary}</div>
            <div className="mt-3 grid grid-cols-3 gap-3 text-sm">
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">유동성</div>
                <div className="mt-1 text-xl font-semibold">{data.macro.liquidityScore ?? '—'}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">달러</div>
                <div className="mt-1 text-xl font-semibold">{data.macro.dollarScore ?? '—'}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">위험선호</div>
                <div className="mt-1 text-xl font-semibold">{data.macro.riskOnScore ?? '—'}</div>
              </div>
            </div>
            <div className="mt-3 space-y-1 text-xs text-[var(--muted)]">
              {data.macro.drivers.map((item) => <div key={item}>• {item}</div>)}
            </div>
          </div>
        </section>

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
            <div>
              <div className="text-sm text-[var(--muted)]">거래량 동반 코인 바닥 확인</div>
              <div className="mt-1 flex flex-wrap items-center gap-3">
                <div className="text-3xl font-bold">{data.bottomSignal.score}/100</div>
                <span className={`rounded-full border px-3 py-1 text-xs ${bottomStateTone(data.bottomSignal.state)}`}>{data.bottomSignal.state}</span>
                <span className="rounded-full border border-white/10 bg-white/5 px-3 py-1 text-xs text-white/85">{data.bottomSignal.actionBias}</span>
              </div>
              <div className="mt-2 rounded-xl border border-white/10 bg-black/15 px-3 py-2 text-xs text-[var(--muted)]">
                {data.bottomSignal.summary}
              </div>
            </div>
            <div className="grid min-w-[260px] grid-cols-2 gap-2 text-xs sm:grid-cols-3">
              {data.bottomSignal.metrics.slice(0, 6).map((metric) => (
                <div key={metric.key} className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-[var(--muted)]">{metric.label}</div>
                  <div className={`mt-1 text-lg font-semibold ${metric.status === 'positive' ? 'text-emerald-200' : metric.status === 'negative' ? 'text-rose-200' : 'text-cyan-100'}`}>{metric.score ?? '—'}</div>
                </div>
              ))}
            </div>
          </div>
          <div className="mt-4">
            <BottomConfirmationChart points={data.bottomSignal.chart.points} markers={data.bottomSignal.chart.markers} />
          </div>
          <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-3 text-xs">
            <div className="rounded-xl border border-cyan-500/15 bg-cyan-500/5 p-3">
              <div className="text-cyan-100">거시/유동성</div>
              <div className="mt-1 text-2xl font-semibold text-white">{data.bottomSignal.metrics.find((item) => item.key === 'macro')?.score ?? '—'}</div>
              <div className="mt-1 text-[var(--muted)]">유동성·달러·위험선호</div>
            </div>
            <div className="rounded-xl border border-emerald-500/15 bg-emerald-500/5 p-3">
              <div className="text-emerald-100">주가 패턴</div>
              <div className="mt-1 text-2xl font-semibold text-white">{data.bottomSignal.metrics.find((item) => item.key === 'pattern')?.score ?? '—'}</div>
              <div className="mt-1 text-[var(--muted)]">저점 후보·재시험·확인 돌파</div>
            </div>
            <div className="rounded-xl border border-amber-500/15 bg-amber-500/5 p-3">
              <div className="text-amber-100">거래량 동반</div>
              <div className="mt-1 text-2xl font-semibold text-white">{data.bottomSignal.volumeConfirmationScore ?? '—'}</div>
              <div className="mt-1 text-[var(--muted)]">진짜 바닥인지 보는 핵심 수급 기준</div>
            </div>
            <div className="rounded-xl border border-rose-500/15 bg-rose-500/5 p-3">
              <div className="text-rose-100">실패 위험</div>
              <div className="mt-1 text-2xl font-semibold text-white">{data.bottomSignal.failureRiskScore ?? '—'}</div>
              <div className="mt-1 text-[var(--muted)]">재시험 실패·가짜 반등·거래소/파생 과열</div>
            </div>
            {data.bottomSignal.confirmedBottom ? (
              <div className="rounded-xl border border-fuchsia-500/15 bg-fuchsia-500/5 p-3">
                <div className="text-fuchsia-100">확신형 바닥</div>
                <div className="mt-1 text-2xl font-semibold text-white">{data.bottomSignal.confirmedBottom.score ?? '—'}</div>
                <div className="mt-1 text-[var(--muted)]">미래 반등 제외 · 당시 데이터만으로 계산</div>
              </div>
            ) : null}
          </div>
          {data.bottomSignal.confirmedBottom ? (
            <div className="mt-4 rounded-2xl border border-fuchsia-500/20 bg-fuchsia-500/5 p-4">
              <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                <div>
                  <div className="text-sm text-fuchsia-100">찐 바닥 확신 신호</div>
                  <div className="mt-1 flex flex-wrap items-center gap-2">
                    <div className="text-2xl font-semibold text-white">{data.bottomSignal.confirmedBottom.score}/100</div>
                    <span className={`rounded-full border px-2.5 py-1 text-[11px] ${confirmedBottomTone(data.bottomSignal.confirmedBottom.state)}`}>{data.bottomSignal.confirmedBottom.state}</span>
                    <span className="rounded-full border border-white/10 bg-white/5 px-2.5 py-1 text-[11px] text-white/80">{data.bottomSignal.confirmedBottom.actionBias}</span>
                  </div>
                  <div className="mt-2 text-xs text-[var(--muted)]">{data.bottomSignal.confirmedBottom.summary}</div>
                </div>
                <div className="grid grid-cols-2 gap-2 text-xs sm:grid-cols-4">
                  <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">신호일</div><div className="mt-1 text-white">{data.bottomSignal.confirmedBottom.signalDate ?? '—'}</div></div>
                  <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">경과일</div><div className="mt-1 text-white">{data.bottomSignal.confirmedBottom.daysSinceSignal ?? '—'}</div></div>
                  <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">거래량</div><div className="mt-1 text-white">{data.bottomSignal.confirmedBottom.recentVolumeRatio !== null ? `${data.bottomSignal.confirmedBottom.recentVolumeRatio}x` : '—'}</div></div>
                  <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">낙폭축소</div><div className="mt-1 text-white">{data.bottomSignal.confirmedBottom.contractionRatio !== null ? `${Math.round(data.bottomSignal.confirmedBottom.contractionRatio * 100)}%` : '—'}</div></div>
                </div>
              </div>
              <div className="mt-3 grid grid-cols-1 gap-3 lg:grid-cols-2 text-xs">
                <div className="rounded-xl border border-emerald-500/15 bg-emerald-500/5 p-3">
                  <div className="mb-2 font-medium text-emerald-100">확신 근거</div>
                  <div className="space-y-1 text-emerald-50/90">{data.bottomSignal.confirmedBottom.reasons.map((item) => <div key={item}>• {item}</div>)}</div>
                </div>
                <div className="rounded-xl border border-amber-500/15 bg-amber-500/5 p-3">
                  <div className="mb-2 font-medium text-amber-100">주의 포인트</div>
                  <div className="space-y-1 text-amber-50/90">{data.bottomSignal.confirmedBottom.cautions.map((item) => <div key={item}>• {item}</div>)}</div>
                </div>
              </div>
            </div>
          ) : null}
          <div className="mt-4 grid grid-cols-1 gap-3 lg:grid-cols-3 text-xs">
            <div className="rounded-xl border border-emerald-500/15 bg-emerald-500/5 p-3">
              <div className="mb-2 font-medium text-emerald-100">거래량이 받쳐주는 근거</div>
              <div className="space-y-1 text-emerald-50/90">{data.bottomSignal.reasons.map((item) => <div key={item}>• {item}</div>)}</div>
            </div>
            <div className="rounded-xl border border-amber-500/15 bg-amber-500/5 p-3">
              <div className="mb-2 font-medium text-amber-100">남은 확인 포인트</div>
              <div className="space-y-1 text-amber-50/90">{data.bottomSignal.cautions.map((item) => <div key={item}>• {item}</div>)}</div>
            </div>
            <div className="rounded-xl border border-rose-500/15 bg-rose-500/5 p-3">
              <div className="mb-2 font-medium text-rose-100">바닥 실패 시그널</div>
              <div className="space-y-1 text-rose-50/90">{(data.bottomSignal.failureSignals ?? []).map((item) => <div key={item}>• {item}</div>)}</div>
            </div>
          </div>
        </section>

        <section className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="text-lg font-semibold mb-3">좋은 코인 vs 좋은 투자</div>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">기초체력</div>
                <div className="mt-1 font-semibold">{data.verdicts.quality}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">타이밍</div>
                <div className="mt-1 font-semibold">{data.verdicts.timing}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">가격 부담</div>
                <div className="mt-1 font-semibold">{data.verdicts.valuationProxy}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">최종 액션</div>
                <div className="mt-1 font-semibold">{data.buyScore.actionLabel}</div>
              </div>
            </div>
            <div className="mt-3 space-y-2 text-sm text-[var(--muted)]">
              <div>• {data.verdicts.oneLiners.quality}</div>
              <div>• {data.verdicts.oneLiners.timing}</div>
              <div>• {data.verdicts.oneLiners.action}</div>
            </div>
          </div>

          <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="text-lg font-semibold mb-3">시나리오</div>
            <div className="space-y-3 text-sm">
              <div className="rounded-xl border border-emerald-500/15 bg-emerald-500/5 p-3">
                <div className="mb-1 font-medium text-emerald-100">Bull case</div>
                <div className="text-emerald-50/90">{data.scenarios.bullCase}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="mb-1 font-medium text-white">Base case</div>
                <div className="text-[var(--muted)]">{data.scenarios.baseCase}</div>
              </div>
              <div className="rounded-xl border border-rose-500/15 bg-rose-500/5 p-3">
                <div className="mb-1 font-medium text-rose-100">Bear case</div>
                <div className="text-rose-50/90">{data.scenarios.bearCase}</div>
              </div>
            </div>
          </div>
        </section>

        <section className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="text-lg font-semibold mb-3">코인 바텀업</div>
            <div className="grid grid-cols-3 gap-3 text-sm">
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">네트워크</div>
                <div className="mt-1 text-xl font-semibold">{data.bottomUp.networkScore}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">토크노믹스</div>
                <div className="mt-1 text-xl font-semibold">{data.bottomUp.tokenomicsScore}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">채택/활동</div>
                <div className="mt-1 text-xl font-semibold">{data.bottomUp.adoptionScore}</div>
              </div>
            </div>
            <div className="mt-3 rounded-xl border border-white/10 bg-black/15 p-3 text-sm text-[var(--muted)]">{data.bottomUp.summary}</div>
            <div className="mt-3 grid grid-cols-1 md:grid-cols-2 gap-3 text-xs">
              <div className="rounded-xl border border-emerald-500/15 bg-emerald-500/5 p-3">
                <div className="mb-2 font-medium text-emerald-100">강점</div>
                <div className="space-y-1 text-emerald-50/90">{data.bottomUp.strengths.map((item) => <div key={item}>• {item}</div>)}</div>
              </div>
              <div className="rounded-xl border border-amber-500/15 bg-amber-500/5 p-3">
                <div className="mb-2 font-medium text-amber-100">리스크</div>
                <div className="space-y-1 text-amber-50/90">{data.bottomUp.risks.map((item) => <div key={item}>• {item}</div>)}</div>
              </div>
            </div>
          </div>

          <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="text-lg font-semibold mb-3">해자 / 공급 압력</div>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">해자 점수</div>
                <div className="mt-1 font-semibold">{data.moat.moatScore}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">유통 비율</div>
                <div className="mt-1 font-semibold">{fmtPct(data.supplyPressure.circulatingRatioPct)}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">FDV 프리미엄</div>
                <div className="mt-1 font-semibold">{fmtPct(data.supplyPressure.fdvPremiumPct)}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">공급압력 점수</div>
                <div className="mt-1 font-semibold">{data.supplyPressure.floatScore}</div>
              </div>
            </div>
            <div className="mt-3 rounded-xl border border-white/10 bg-black/15 p-3 text-sm text-[var(--muted)]">{data.moat.moatType} · {data.moat.summary}</div>
            <div className="mt-3 grid grid-cols-1 md:grid-cols-2 gap-3 text-xs">
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="mb-2 font-medium text-white">해자 근거</div>
                <div className="space-y-1 text-[var(--muted)]">{data.moat.reasons.map((item) => <div key={item}>• {item}</div>)}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="mb-2 font-medium text-white">공급 압력</div>
                <div className="space-y-1 text-[var(--muted)]">
                  <div>• 언락 리스크: {data.supplyPressure.unlockRisk}</div>
                  <div>• 희석 리스크: {data.supplyPressure.dilutionRisk}</div>
                  {data.supplyPressure.reasons.map((item) => <div key={item}>• {item}</div>)}
                </div>
              </div>
            </div>
          </div>

          <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="text-lg font-semibold mb-3">시장 상태</div>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">7D</div>
                <div className="mt-1 font-semibold">{fmtPct(data.market.return7d)}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">30D</div>
                <div className="mt-1 font-semibold">{fmtPct(data.market.return30d)}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">90D</div>
                <div className="mt-1 font-semibold">{fmtPct(data.market.return90d)}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">변동성 30D</div>
                <div className="mt-1 font-semibold">{fmtPct(data.market.volatility30d)}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">거래량 30D</div>
                <div className="mt-1 font-semibold">{fmtPct(data.market.volumeTrend30d)}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">52주 고점대비</div>
                <div className="mt-1 font-semibold">{fmtPct(data.market.distanceFrom52wHigh)}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">52주 저점대비</div>
                <div className="mt-1 font-semibold">{fmtPct(data.market.distanceFrom52wLow)}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">내러티브</div>
                <div className="mt-1 font-semibold">{data.narrative.theme}</div>
              </div>
            </div>
            <div className="mt-3 rounded-xl border border-white/10 bg-black/15 p-3 text-sm text-[var(--muted)]">{data.narrative.summary}</div>
          </div>
        </section>

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="text-lg font-semibold mb-3">온체인 / 실사용 데이터</div>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
            <div className="rounded-xl border border-white/10 bg-black/15 p-3">
              <div className="text-xs text-[var(--muted)]">활동 점수</div>
              <div className="mt-1 font-semibold">{data.onchain.activityScore}</div>
            </div>
            <div className="rounded-xl border border-white/10 bg-black/15 p-3">
              <div className="text-xs text-[var(--muted)]">TVL</div>
              <div className="mt-1 font-semibold">{fmtUsdBillions(data.onchain.tvlUsd)}</div>
            </div>
            <div className="rounded-xl border border-white/10 bg-black/15 p-3">
              <div className="text-xs text-[var(--muted)]">TVL 30D</div>
              <div className="mt-1 font-semibold">{fmtPct(data.onchain.tvlTrend30dPct)}</div>
            </div>
            <div className="rounded-xl border border-white/10 bg-black/15 p-3">
              <div className="text-xs text-[var(--muted)]">수수료 30D 평균</div>
              <div className="mt-1 font-semibold">{fmtUsdMillions(data.onchain.fees30dAvgUsd)}</div>
            </div>
            <div className="rounded-xl border border-white/10 bg-black/15 p-3">
              <div className="text-xs text-[var(--muted)]">수수료 추세</div>
              <div className="mt-1 font-semibold">{fmtPct(data.onchain.feesTrend30dPct)}</div>
            </div>
            <div className="rounded-xl border border-white/10 bg-black/15 p-3">
              <div className="text-xs text-[var(--muted)]">개발자 점수</div>
              <div className="mt-1 font-semibold">{fmtNullableScore(data.onchain.developerScore)}</div>
            </div>
            <div className="rounded-xl border border-white/10 bg-black/15 p-3">
              <div className="text-xs text-[var(--muted)]">커뮤니티 점수</div>
              <div className="mt-1 font-semibold">{fmtNullableScore(data.onchain.communityScore)}</div>
            </div>
          </div>
          <div className="mt-3 rounded-xl border border-white/10 bg-black/15 p-3 text-sm text-[var(--muted)]">{data.onchain.summary}</div>
          <div className="mt-3 space-y-1 text-xs text-[var(--muted)]">
            {data.onchain.reasons.map((item) => <div key={item}>• {item}</div>)}
          </div>
        </section>

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="text-lg font-semibold mb-3">수급 / 자금 흐름</div>
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">스테이블 수요</div>
                <div className="mt-1 font-semibold">{data.flows.stablecoinDemandLabel}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">스테이블 점유</div>
                <div className="mt-1 font-semibold">{fmtPct(data.flows.stablecoinDominancePct)}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">알트 시즌 프록시</div>
                <div className="mt-1 font-semibold">{data.flows.altSeasonLabel}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">BTC dominance</div>
                <div className="mt-1 font-semibold">{data.flows.btcDominanceLabel}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">BTC 점유율</div>
                <div className="mt-1 font-semibold">{fmtPct(data.flows.btcDominancePct)}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">ETF 프록시</div>
                <div className="mt-1 font-semibold">{data.flows.etfFlowProxy}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">ETF 일간 순유입</div>
                <div className="mt-1 font-semibold">{fmtCryptoEtf(data.flows.etfDailyNetFlowUsd, data.profile.symbol)}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">ETF 5일 합계</div>
                <div className="mt-1 font-semibold">{fmtCryptoEtf(data.flows.etfWeeklyNetFlowUsd, data.profile.symbol)}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">거래소 순유입/유출</div>
                <div className="mt-1 font-semibold">{data.flows.exchangeNetflowProxy}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">거래소 과열</div>
                <div className="mt-1 font-semibold">{data.flows.exchangeFlowRisk}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">파생 과열</div>
                <div className="mt-1 font-semibold">{data.flows.derivativesHeat}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">거래대금/시총</div>
                <div className="mt-1 font-semibold">{fmtPct(data.flows.volumeToMarketCapPct)}</div>
              </div>
            </div>
          <div className="mt-3 rounded-xl border border-white/10 bg-black/15 p-3 text-sm text-[var(--muted)]">{data.flows.summary}</div>
          <div className="mt-3 grid grid-cols-1 md:grid-cols-2 gap-3 text-sm">
            <div className="rounded-xl border border-violet-500/20 bg-violet-500/5 p-3 text-violet-50/90">{data.flows.altSeasonInsight}</div>
            <div className="rounded-xl border border-sky-500/20 bg-sky-500/5 p-3 text-sky-50/90">{data.flows.exchangeNetflowInsight}</div>
          </div>
          <div className="mt-3 space-y-1 text-xs text-[var(--muted)]">
            {data.flows.reasons.map((item) => <div key={item}>• {item}</div>)}
          </div>
        </section>

        <section className="grid grid-cols-1 xl:grid-cols-3 gap-4">
          <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="mb-3 text-lg font-semibold">BTC dominance proxy 30D</div>
            <MiniLine points={data.trendCharts.btcDominanceProxy30d} color="#67e8f9" />
          </div>
          <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="mb-3 text-lg font-semibold">알트 시즌 프록시 30D</div>
            <MiniLine points={data.trendCharts.altSeasonProxy30d} color="#c084fc" />
          </div>
          <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="mb-3 text-lg font-semibold">스테이블 시총 30D</div>
            <MiniLine points={data.trendCharts.stablecoinMcap30d} color="#34d399" />
          </div>
          <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="mb-3 text-lg font-semibold">{data.profile.symbol === 'ETH' ? 'ETH ETF 순유입 30D' : 'BTC ETF 순유입 30D'}</div>
            <MiniLine points={data.trendCharts.etfNetFlow30d} color="#fbbf24" />
          </div>
          <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="mb-3 text-lg font-semibold">거래소 순유입 프록시 30D</div>
            <MiniLine points={data.trendCharts.exchangeNetflowProxy30d} color="#fb7185" />
          </div>
        </section>

        {data.executionBridge ? (
          <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="text-lg font-semibold mb-3">연결 자산 실행 계획</div>
            <div className="flex flex-wrap items-center gap-2 text-xs">
              <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-1 text-cyan-100">{data.executionBridge.asset}</span>
              <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1 text-white/80">{data.executionBridge.actionLabel}</span>
              <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1 text-white/80">목표 {data.executionBridge.targetAllocationPct}%</span>
              <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1 text-white/80">{data.executionBridge.entryMode}</span>
              <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1 text-white/80">{data.executionBridge.alignment === 'aligned' ? '실행 정합' : data.executionBridge.alignment === 'mixed' ? '부분 정합' : '실행 충돌'}</span>
            </div>
            <div className="mt-3 rounded-xl border border-white/10 bg-black/15 p-3 text-sm text-[var(--muted)]">{data.executionBridge.summary}</div>
            <div className="mt-3 rounded-xl border border-amber-500/20 bg-amber-500/5 p-3 text-sm text-amber-50/90">{data.executionBridge.riskBox}</div>
            {data.executionBridge.timingNotes?.length ? (
              <div className="mt-3 space-y-1 text-xs text-[var(--muted)]">
                {data.executionBridge.timingNotes.map((item) => <div key={item}>• {item}</div>)}
              </div>
            ) : null}
          </section>
        ) : null}
      </div>
    </main>
  );
}
