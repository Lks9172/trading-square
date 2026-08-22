import { ActionBadge, ScoreBadge, ScoreLegend } from "@/components/ScoreUI";
import { SmartLink } from "@/components/SmartLink";
import { fetchServerJson } from "@/lib/server-api";

export const revalidate = 300;
export const dynamic = "force-dynamic";

type CryptoItem = {
  profile: {
    symbol: string;
    name: string;
    category: string;
    narrativeTheme: string;
    linkedAsset: string;
  };
  market: {
    asOf?: string | null;
    price: number | null;
    return7d: number | null;
    return30d: number | null;
    return90d: number | null;
    distanceFrom52wHigh: number | null;
  };
  freshness: CryptoDecisionFreshness;
  macro: {
    stance: '우호' | '중립' | '주의';
    summary: string;
  };
  narrative: {
    stage: 'EARLY' | 'MID' | 'OVERHEATED';
    heatScore: number;
    summary: string;
  };
  bottomUp: {
    networkScore: number;
    tokenomicsScore: number;
    adoptionScore: number;
    summary: string;
  };
  flows: {
    stablecoinDemandLabel: string;
    stablecoinDominancePct: number | null;
    altSeasonLabel: string;
    altSeasonInsight: string;
    btcDominanceLabel: string;
    btcDominancePct: number | null;
    etfFlowProxy: string;
    exchangeFlowRisk: string;
    exchangeNetflowProxy: string;
    exchangeNetflowInsight: string;
    derivativesHeat: string;
    volumeToMarketCapPct: number | null;
    summary: string;
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
    state: string;
    score: number;
    volumeConfirmationScore?: number | null;
    failureRiskScore?: number | null;
    confirmedBottom?: {
      score: number;
      state: '미충족' | '후보' | '확신';
    } | null;
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
  executionBridge: {
    asset: string;
    actionLabel: string;
    alignment: 'aligned' | 'mixed' | 'conflicted';
    entryMode: string;
    riskBox: string;
    summary: string;
  } | null;
};

type CryptoDecisionFreshness = {
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

type CryptoMarketRegime = {
  regime: 'RISK_ON' | 'SELECTIVE' | 'DEFENSIVE' | 'STAY_OUT';
  action: '공격 가능' | '선별 접근' | '현금 우선' | '관망' | '관찰 대기';
  altRegime: 'BTC 중심장' | '혼조장' | '알트 확산장';
  targetTotalExposurePct: number;
  summary: string;
  reasons: string[];
};

async function fetchCrypto(): Promise<{ items: CryptoItem[]; marketRegime: CryptoMarketRegime | null; freshness: CryptoDecisionFreshness | null }> {
  const data = await fetchServerJson<{ items?: CryptoItem[]; marketRegime?: CryptoMarketRegime; freshness?: CryptoDecisionFreshness }>('/api/research/crypto', { revalidate: 300 });
  return {
    items: Array.isArray(data?.items) ? data.items : [],
    marketRegime: data?.marketRegime ?? null,
    freshness: data?.freshness ?? null,
  };
}

function fmtPrice(value: number | null) {
  if (value === null || Number.isNaN(value)) return "—";
  return value >= 1000 ? value.toLocaleString("en-US", { maximumFractionDigits: 0 }) : value.toLocaleString("en-US", { maximumFractionDigits: 2 });
}

function fmtPct(value: number | null) {
  if (value === null || Number.isNaN(value)) return "—";
  return `${value >= 0 ? "+" : ""}${value.toFixed(1)}%`;
}

export default async function CryptoResearchPage() {
  const { items, marketRegime, freshness } = await fetchCrypto();

  return (
    <main className="flex-1 p-4 md:p-6 max-w-6xl mx-auto w-full">
      <div className="space-y-6">
        <header className="space-y-2">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
            <div>
              <h1 className="text-2xl font-bold tracking-tight">코인 리서치</h1>
              <p className="text-sm text-[var(--muted)]">
                메이저 코인 5개를 거시경제 → 탑다운 → 코인별 바텀업 → 실행 계획 순서로 보는 MVP 탭입니다.
              </p>
            </div>
            <div className="flex flex-wrap gap-2">
              <SmartLink href="/research" className="inline-flex rounded-full border border-white/10 bg-white/5 px-4 py-2 text-sm text-white/80 hover:bg-white/10">기업 리서치로 돌아가기</SmartLink>
            </div>
          </div>
        </header>

        <ScoreLegend compact defaultOpen />

        {freshness && !freshness.eligibleForDecisions ? (
          <section className="rounded-2xl border border-amber-500/30 bg-amber-500/10 p-4 text-sm text-amber-50">
            <div className="font-semibold">코인 실행 신호 일시 중지 · {freshness.status === 'UNKNOWN' ? '보조근거 확인 불가' : '보조근거 오래됨'}</div>
            <div className="mt-1 text-amber-100/85">
              가격 {freshness.marketObservedOn ?? '확인 불가'} · 수급/온체인 {freshness.supportingEvidenceObservedOn ?? '확인 불가'}
              {freshness.supportingEvidenceAgeDays !== null ? ` (${freshness.supportingEvidenceAgeDays}일 경과)` : ''}
            </div>
            <div className="mt-1 text-xs text-amber-100/70">{freshness.explanation}</div>
          </section>
        ) : null}

        {marketRegime ? (
          <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
              <div>
                <div className="text-lg font-semibold text-white">코인장 상태</div>
                <div className="mt-1 text-sm text-[var(--muted)]">{marketRegime.summary}</div>
              </div>
              <div className="flex flex-wrap gap-2 text-xs">
                <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-3 py-1 text-cyan-100">{marketRegime.action}</span>
                <span className="rounded-full border border-white/10 bg-white/5 px-3 py-1 text-white/80">{marketRegime.altRegime}</span>
                <span className="rounded-full border border-white/10 bg-white/5 px-3 py-1 text-white/80">총 익스포저 {marketRegime.targetTotalExposurePct}%</span>
              </div>
            </div>
            <div className="mt-3 grid grid-cols-1 md:grid-cols-2 gap-2 text-sm text-[var(--muted)]">
              {marketRegime.reasons.map((reason) => <div key={reason}>• {reason}</div>)}
            </div>
          </section>
        ) : null}

        <section className="grid grid-cols-1 xl:grid-cols-2 gap-4">
          {items.map((item) => (
            <SmartLink
              key={item.profile.symbol}
              href={`/research/crypto/${item.profile.symbol.toLowerCase()}`}
              className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5 cursor-pointer hover:bg-white/[0.03] active:scale-[0.99]"
            >
              <div className="flex items-start justify-between gap-4">
                <div>
                  <div className="text-lg font-semibold text-white">{item.profile.symbol} · {item.profile.name}</div>
                  <div className="mt-1 text-xs text-[var(--muted)]">{item.profile.category}</div>
                  <div className="mt-2 flex flex-wrap gap-2 text-[10px]">
                    <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-0.5 text-cyan-100">{item.profile.narrativeTheme}</span>
                    <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-white/75">연결 자산 {item.profile.linkedAsset}</span>
                    <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-white/75">내러티브 {item.narrative.stage}</span>
                  </div>
                </div>
                <div className="text-right">
                  <div className="text-xs text-[var(--muted)]">가격</div>
                  <div className="text-xl font-semibold text-white">${fmtPrice(item.market.price)}</div>
                </div>
              </div>

              <div className="mt-4 flex flex-wrap gap-2">
                <ScoreBadge label="B" value={item.buyScore.buyScore} title="코인 실행 점수입니다." kind="buy" interactive={false} />
                <ScoreBadge label="매력" value={item.buyScore.appealScore} title="거시+기초체력 매력도입니다." kind="appeal" interactive={false} />
                <ScoreBadge label="과열" value={item.buyScore.crowdingScore} title="단기 군중화/과열도입니다." kind="crowding" interactive={false} />
                {item.freshness.eligibleForDecisions ? (
                  <ActionBadge value={item.buyScore.buyScore} interactive={false} />
                ) : (
                  <span className="inline-flex items-center rounded-full border border-amber-500/30 bg-amber-500/10 px-2.5 py-1 text-xs font-semibold text-amber-100">데이터 갱신 대기</span>
                )}
              </div>
              {!item.freshness.eligibleForDecisions ? (
                <div className="mt-2 text-[11px] text-amber-200/80">
                  B점수는 과거 보조근거 참고값 · 수급/온체인 {item.freshness.supportingEvidenceObservedOn ?? '확인 불가'}
                </div>
              ) : null}
              <div className="mt-3 flex flex-wrap gap-2 text-[11px]">
                <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-1 text-cyan-100">바닥 {item.bottomSignal.state}</span>
                {item.bottomSignal.confirmedBottom && item.bottomSignal.confirmedBottom.state !== '미충족' ? <span className="rounded-full border border-fuchsia-500/20 bg-fuchsia-500/10 px-2 py-1 text-fuchsia-100">확신형 {item.bottomSignal.confirmedBottom.state} {item.bottomSignal.confirmedBottom.score}</span> : null}
                <span className="rounded-full border border-emerald-500/20 bg-emerald-500/10 px-2 py-1 text-emerald-100">바닥점수 {item.bottomSignal.score}</span>
                <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1 text-white/80">거래량확인 {item.bottomSignal.volumeConfirmationScore ?? '—'}</span>
                <span className="rounded-full border border-rose-500/20 bg-rose-500/10 px-2 py-1 text-rose-100">실패위험 {item.bottomSignal.failureRiskScore ?? '—'}</span>
              </div>

              <div className="mt-4 grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">7D</div>
                  <div className="mt-1 font-semibold">{fmtPct(item.market.return7d)}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">30D</div>
                  <div className="mt-1 font-semibold">{fmtPct(item.market.return30d)}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">90D</div>
                  <div className="mt-1 font-semibold">{fmtPct(item.market.return90d)}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">52주 고점대비</div>
                  <div className="mt-1 font-semibold">{fmtPct(item.market.distanceFrom52wHigh)}</div>
                </div>
              </div>

              <div className="mt-4 grid grid-cols-1 md:grid-cols-2 gap-3 text-xs text-[var(--muted)]">
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="mb-1 font-medium text-white">거시 해석 · {item.macro.stance}</div>
                  <div>{item.macro.summary}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="mb-1 font-medium text-white">바텀업 해석</div>
                  <div>{item.bottomUp.summary}</div>
                </div>
              </div>

              <div className="mt-3 flex flex-wrap gap-2 text-[10px]">
                <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-white/75">스테이블 {item.flows.stablecoinDemandLabel}</span>
                <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-white/75">알트 시즌 {item.flows.altSeasonLabel}</span>
                <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-white/75">스테이블 점유 {fmtPct(item.flows.stablecoinDominancePct)}</span>
                <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-white/75">BTC/알트 {item.flows.btcDominanceLabel}</span>
                <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-white/75">BTC 점유 {fmtPct(item.flows.btcDominancePct)}</span>
                <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-white/75">ETF {item.flows.etfFlowProxy}</span>
                <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-white/75">거래소 순유입 {item.flows.exchangeNetflowProxy}</span>
                <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-white/75">거래소 위험 {item.flows.exchangeFlowRisk}</span>
                <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-white/75">파생 과열 {item.flows.derivativesHeat}</span>
              </div>

              <div className="mt-3 text-xs text-white/80">{item.verdicts.oneLiners.action}</div>
              <div className="mt-1 text-[11px] text-[var(--muted)]">{item.flows.altSeasonInsight}</div>
              <div className="mt-1 text-[11px] text-[var(--muted)]">{item.executionBridge?.summary ?? item.flows.exchangeNetflowInsight}</div>
              {item.executionBridge ? (
                <div className="mt-2 text-[11px] text-[var(--muted)]">
                  {item.executionBridge.entryMode} · {item.executionBridge.alignment === 'aligned' ? '실행 정합' : item.executionBridge.alignment === 'mixed' ? '부분 정합' : '실행 충돌'}
                </div>
              ) : null}
            </SmartLink>
          ))}
        </section>
      </div>
    </main>
  );
}
