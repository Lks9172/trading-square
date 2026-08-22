import { CompanySearchBox } from "@/components/CompanySearchBox";
import { WatchlistToggle } from "@/components/WatchlistToggle";
import { ActionBadge, ScoreBadge, ScoreLegend, HelpDot, scoreTone } from "@/components/ScoreUI";
import { SmartLink } from "@/components/SmartLink";
import { BottomConfirmationChart } from "@/components/BottomConfirmationChart";
import { DynamicPeerPanel } from "@/components/DynamicPeerPanel";
import { InvestmentDecisionPanel, type InvestmentDecisionView } from "@/components/InvestmentDecisionPanel";
import { MacdMomentumPanel, type MacdMultiTimeframeView } from "@/components/MacdMomentumPanel";
import { fetchServerJson } from "@/lib/server-api";

// The backend returns a cheap persisted seed immediately and refreshes the
// network-heavy evidence in the background. A long ISR window would freeze
// that startup seed even after the complete projection is ready.
export const revalidate = 30;
export const dynamic = "force-dynamic";

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
    roic?: number | null;
    effectiveTaxRate?: number | null;
    roicEstimated?: boolean;
    shareDilution3yCagr?: number | null;
    accrualRatio?: number | null;
    estimateUpsidePct: number | null;
    estimateRevision7d?: number | null;
    estimateRevision30d?: number | null;
    estimateRevision90d?: number | null;
    targetUpsideChange30d?: number | null;
    analystScore?: number | null;
    analystScoreRevision7d?: number | null;
    analystScoreRevision30d?: number | null;
    analystScoreRevision90d?: number | null;
    estimateUpsideHistory?: Array<{ date: string; value: number | null }>;
    analystScoreHistory?: Array<{ date: string; value: number | null }>;
    segmentGeoMixNote?: string | null;
    fundamentalsStatus?: 'CURRENT' | 'LAGGING' | 'INCOMPLETE' | 'UNKNOWN' | 'PENDING';
    latestPeriodicReportDate?: string | null;
    latestPeriodicFilingDate?: string | null;
    latestPeriodicForm?: string | null;
    fundamentalsLagDays?: number | null;
    scoreComparable?: boolean;
    scoreWarnings?: string[];
    segmentMix?: Array<{ label: string; value: number | null; unit?: string | null; percentOfTotal?: number | null }>;
    geoMix?: Array<{ label: string; value: number | null; unit?: string | null; percentOfTotal?: number | null }>;
  };
  score: {
    totalScore: number | null;
    growth: { value: number | null; reasons: string[] };
    quality: { value: number | null; reasons: string[] };
    valuation: { value: number | null; reasons: string[] };
    balanceSheet: { value: number | null; reasons: string[] };
    reasons: string[];
  };
  buyScore: {
    appealScore: number | null;
    crowdingScore: number | null;
    buyScore: number | null;
    label: '매수 우호' | '선별 접근' | '추격 주의' | null;
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
    guidanceSummary?: {
      stance: 'raised' | 'lowered' | 'affirmed' | 'mixed' | 'unclear';
      revenue?: 'raised' | 'lowered' | 'affirmed' | 'mentioned' | null;
      margin?: 'raised' | 'lowered' | 'affirmed' | 'mentioned' | null;
      capex?: 'raised' | 'lowered' | 'affirmed' | 'mentioned' | null;
      fcf?: 'raised' | 'lowered' | 'affirmed' | 'mentioned' | null;
      revenueText?: string | null;
      marginText?: string | null;
      capexText?: string | null;
      fcfText?: string | null;
      revenueValue?: { raw: string; min: number | null; max: number | null; unit: 'usd' | 'percent' | 'bps' | 'other' | null } | null;
      marginValue?: { raw: string; min: number | null; max: number | null; unit: 'usd' | 'percent' | 'bps' | 'other' | null } | null;
      capexValue?: { raw: string; min: number | null; max: number | null; unit: 'usd' | 'percent' | 'bps' | 'other' | null } | null;
      fcfValue?: { raw: string; min: number | null; max: number | null; unit: 'usd' | 'percent' | 'bps' | 'other' | null } | null;
      evidence: string[];
    } | null;
  }>;
  irMaterials: Array<{
    title: string;
    form: string;
    filingDate: string;
    url: string;
    type: 'presentation' | 'earnings-release' | 'annual-report' | 'quarterly-report' | 'other';
    source?: 'primary' | 'exhibit' | 'index';
    contentType?: 'pdf' | 'html' | 'txt' | 'other';
    summary?: string | null;
  }>
  highlights: string[];
  peerGroup?: string | null;

  bottleneck?: {
    themeId: string;
    title: string;
    role: string;
    score: number;
    conviction: 'WATCH' | 'STRONG' | 'CORE';
    switchingCost: number;
    pricingPower: '낮음' | '보통' | '높음';
    leadTimeSignal: '약함' | '보통' | '강함';
    backlogSignal: '약함' | '보통' | '강함';
    reasons: string[];
  } | null;
  narrative?: {
    themeId: string;
    title: string;
    stage: 'EARLY' | 'MID' | 'OVERHEATED';
    heatScore: number;
    trend?: 'HEATING' | 'COOLING' | 'STABLE';
    riskNote: string;
    drivers: string[];
  } | null;
  capitalFlow?: {
    etfInclusion: string | null;
    policyTailwinds: string[];
    capexLinkage: string | null;
    fundingDrivers: string[];
    flowEvidenceType?: 'STRUCTURAL_PROXY';
    evidenceNotice?: string;
  } | null;
  cashFlowQuality?: {
    cashConversionScore: number;
    earningsQualityScore: number;
    accrualRisk: '낮음' | '보통' | '높음' | '자료부족';
    ocfToNetIncome: number | null;
    receivablesRisk: '낮음' | '보통' | '높음' | '자료부족';
    inventoryRisk: '낮음' | '보통' | '높음' | '자료부족';
    liquidityLabel: '양호' | '보통' | '주의' | '자료부족';
    summary: string;
    reasons: string[];
    evidenceBasis?: string;
  } | null;
  multipleInsight?: {
    rateSensitivity: '낮음' | '보통' | '높음';
    narrativePremium: '낮음' | '보통' | '높음';
    valuationVsPeer: '할인' | '중립' | '프리미엄' | '판단불가';
    multipleCompressionRisk: '낮음' | '보통' | '높음';
    valuationVsInternalRange: '저평가권' | '중립권' | '고평가권' | '판단불가';
    peerAverageEvToSales: number | null;
    peerMedianEvToSales: number | null;
    premiumPctVsPeer: number | null;
    premiumPctVsPeerMedian: number | null;
    currentEvToSales?: number | null;
    currentEvToFcf?: number | null;
    summary: string;
    reasons: string[];
    evidenceBasis?: string;
  } | null;
  guidanceInsight?: {
    stance: 'raised' | 'lowered' | 'affirmed' | 'mixed' | 'unclear';
    actionBias: '공격 가능' | '선별 접근' | '보수 접근';
    summary: string;
    revenue?: string | null;
    margin?: string | null;
    capex?: string | null;
    fcf?: string | null;
    revenueValue?: { raw: string; min: number | null; max: number | null; unit: 'usd' | 'percent' | 'bps' | 'other' | null } | null;
    marginValue?: { raw: string; min: number | null; max: number | null; unit: 'usd' | 'percent' | 'bps' | 'other' | null } | null;
    capexValue?: { raw: string; min: number | null; max: number | null; unit: 'usd' | 'percent' | 'bps' | 'other' | null } | null;
    fcfValue?: { raw: string; min: number | null; max: number | null; unit: 'usd' | 'percent' | 'bps' | 'other' | null } | null;
    evidence: string[];
  } | null;
  timeframeView?: {
    shortTerm: { stance: 'STRONG BUY' | 'BUY' | 'HOLD' | 'REDUCE' | 'SELL'; score?: number; confidence?: number; summary: string; reasons?: string[] };
    swingTerm: { stance: 'STRONG BUY' | 'BUY' | 'HOLD' | 'REDUCE' | 'SELL'; score?: number; confidence?: number; summary: string; reasons?: string[] };
    longTerm: { stance: 'STRONG BUY' | 'BUY' | 'HOLD' | 'REDUCE' | 'SELL'; score?: number; confidence?: number; summary: string; reasons?: string[] };
    validation?: {
      firstDate: string | null;
      lastDate: string | null;
      historyPointCount: number;
      methodology: string;
      horizons: Array<{
        horizon: 'SHORT_TERM' | 'SWING_TERM' | 'LONG_TERM';
        forwardTradingDays: number;
        targetReturnPct: number;
        signalThreshold: number;
        signalCount: number;
        positiveHitRatePct: number | null;
        targetHitRatePct: number | null;
        averageReturnPct: number | null;
        medianReturnPct: number | null;
        averageDaysToTarget: number | null;
        averageMaxDrawdownPct: number | null;
      }>;
    };
  } | null;
  correctionAssessment?: {
    correctionScore: number;
    trendBreakRiskScore: number;
    verdict: '조정 우세' | '혼합' | '추세전환 경계';
    actionBias: '눌림 매수 가능' | '눌림 매수 검토' | '확인 후 접근' | '방어 우선';
    summary: string;
    reasons: string[];
    risks: string[];
    methodology?: string;
  } | null;
  thesisMonitor?: {
    status: '유지' | '일부 약화' | '훼손 경계';
    summary: string;
    reasons: string[];
    breakConditions: string[];
  } | null;
  reversalConfirmation?: {
    status: 'OFF' | 'EARLY' | 'ON' | 'STRONG';
    score: number;
    signalDate: string | null;
    summary: string;
    reasons: string[];
    cautions: string[];
  } | null;
  sectorContext?: {
    sectorId: string;
    label: string;
    sectorKey: string;
    classification: string;
    buyScore: number | null;
    qualityScore: number | null;
    appealScore: number | null;
    crowdingScore: number | null;
    valuationScore?: number | null;
    earningsRevisionScore?: number | null;
    earningsRevisionReferenceScore?: number | null;
    rotationScore?: number | null;
    rotationRank?: number | null;
    rotationUniverseSize?: number | null;
    rotationPercentile?: number | null;
    macroFitScore?: number | null;
    relativeStrengthScore?: number | null;
    fundamentalScore?: number | null;
    flowScore?: number | null;
    flowProxyScore?: number | null;
    sectorEvidenceSummary?: string | null;
    stance: 'favored' | 'avoided' | 'neutral';
    rotationState?: 'CURRENT_LEADER' | 'NEXT_CANDIDATE' | 'SECONDARY_CANDIDATE' | 'FADING' | 'NEUTRAL' | null;
    rotationLabel?: string | null;
    expectedLeadershipWindow?: string | null;
    expectedLeadershipMessage?: string | null;
    rotationReasons?: string[];
    thesis: string;
    relatedThemes: Array<{ id: string; theme: string }>;
  } | null;
  verdicts?: {
    businessQuality: { label: '우호' | '양호' | '중립' | '주의'; score: number; summary: string };
    valuation: { label: '우호' | '양호' | '중립' | '주의'; score: number; summary: string };
    timing: { label: '우호' | '양호' | '중립' | '주의'; score: number; summary: string };
    finalAction: { label: '우호' | '양호' | '중립' | '주의'; score: number; summary: string };
    oneLiners?: { business: string; valuation: string; timing: string; action: string };
    investmentDecision?: InvestmentDecisionView;
  } | null;
  bottomSignal?: {
    score: number | null;
    state: '바닥 아님' | '바닥 시도' | '재시험 구간' | '1차 확인' | '구조적 바닥 가능' | '데이터 부족';
    actionBias: '대기' | '관찰 매수' | '분할 매수' | '확인 우선';
    summary: string;
    earningsBottomScore?: number;
    priceBottomScore?: number;
    volumeConfirmationScore?: number;
    failureRiskScore?: number;
    expectationEvidenceNotice?: string;
    metrics: Array<{ key: string; label: string; score: number | null; status: 'positive' | 'neutral' | 'negative'; detail: string }>;
    chart: {
      points: Array<{
        date: string;
        value: number;
        vwap20?: number | null;
        obvPressure20Pct?: number | null;
        sma20?: number | null;
        sma50?: number | null;
        sma100?: number | null;
        sma200?: number | null;
        channelLower?: number | null;
        channelMid?: number | null;
        channelUpper?: number | null;
      }>;
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
    technicalConfirmation?: {
      score: number;
      state: '매집 우위' | '중립' | '분산 우위' | '데이터 부족';
      vwap20: number | null;
      closeVsVwap20Pct: number | null;
      vwapSlope5dPct: number | null;
      obvPressure20Pct: number | null;
      reasons: string[];
      cautions: string[];
      methodology: string;
    };
    macdMomentum?: MacdMultiTimeframeView;
    priceStructure?: {
      score: number;
      trendState: 'UPTREND' | 'RANGE' | 'DOWNTREND' | 'TRANSITION' | 'UNAVAILABLE';
      trendLabel: string;
      bearishReversalStage: 'INTACT' | 'MOMENTUM_WEAKENING' | 'STRUCTURAL_CRACK' | 'PRIOR_LOW_BROKEN' | 'UNAVAILABLE';
      bearishReversalLabel: string;
      recoveryStage: 'NONE' | 'BASE_BUILDING' | 'REBOUND' | 'STRUCTURE_BREAK' | 'RETEST_HELD' | 'UNAVAILABLE';
      recoveryLabel: string;
      priceLocation: 'BREAKOUT' | 'LOWER_CHANNEL' | 'SUPPORT_ZONE' | 'MID_CHANNEL' | 'RESISTANCE_ZONE' | 'UPPER_CHANNEL' | 'BREAKDOWN' | 'UNAVAILABLE';
      priceLocationLabel: string;
      movingAverageState: 'BULLISH_ALIGNED' | 'CONVERGED' | 'TRANSITION' | 'BEARISH_ALIGNED' | 'UNAVAILABLE';
      movingAverageLabel: string;
      rsi14: number | null;
      sma20: number | null;
      sma50: number | null;
      sma100: number | null;
      sma200: number | null;
      movingAverageConvergencePct: number | null;
      channelLower: number | null;
      channelMid: number | null;
      channelUpper: number | null;
      channelPositionPct: number | null;
      channelAnnualizedSlopePct: number | null;
      supportZone: { lower: number; upper: number; touches: number; strength: number; roleFlip: boolean } | null;
      resistanceZone: { lower: number; upper: number; touches: number; strength: number; roleFlip: boolean } | null;
      consolidationDays: number;
      consolidationRangePct: number | null;
      volumeBreakout: boolean;
      stopHuntReclaim: boolean;
      oversoldConfluence: boolean;
      fibonacci: {
        swingDirection: 'UP_SWING' | 'DOWN_SWING' | 'UNAVAILABLE';
        swingDirectionLabel: string;
        swingStartDate: string | null;
        swingEndDate: string | null;
        swingStartPrice: number | null;
        swingEndPrice: number | null;
        swingAmplitudePct: number | null;
        currentPrice: number | null;
        currentRetracementRatio: number | null;
        levels: Array<{ ratio: number; price: number; label: string }>;
        nearestRatio: number | null;
        nearestPrice: number | null;
        nearestGapPct: number | null;
        timeframeReliability: 'WEEKLY_CONFIRMED' | 'DAILY_ONLY' | 'UNAVAILABLE';
        timeframeLabel: string;
        weeklyConfluence: boolean;
        supportResistanceConfluence: boolean;
        channelConfluence: boolean;
        confluenceScore: number;
        zoneState:
          | 'EXTENSION'
          | 'SHALLOW_RETRACEMENT'
          | 'MODERATE_RETRACEMENT'
          | 'DEEP_RETRACEMENT'
          | 'LAST_DEFENSE'
          | 'LAST_DEFENSE_BROKEN'
          | 'UNAVAILABLE';
        zoneLabel: string;
        summary: string;
        cautions: string[];
        methodology: string;
      };
      reasons: string[];
      cautions: string[];
      methodology: string;
    };
    reasons: string[];
    cautions: string[];
    failureSignals?: string[];
  } | null;
  positionSizing?: {
    action: 'STRONG BUY' | 'BUY' | 'HOLD' | 'REDUCE' | 'SELL';
    targetPositionPct: number;
    initialEntryPctOfTarget: number;
    reservePctOfTarget: number;
    addOnPlan: string[];
    reducePlan?: string[];
    zoneLabel?: string;
    reduceTrigger: string;
    stopScenario: string;
    summary: string;
    reasons: string[];
  } | null;
  executionBridge?: {
    asset: string;
    action: string;
    actionLabel: string;
    companyAction: 'STRONG BUY' | 'BUY' | 'HOLD' | 'REDUCE' | 'SELL';
    companyActionLabel: string;
    targetAllocationPct: number;
    alignment: 'aligned' | 'mixed' | 'conflicted';
    primaryReason: string;
    summary: string;
    timingNotes: string[];
  } | null;
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
    vsPeerAvgRevenueGrowth?: number | null;
    vsPeerAvgOperatingMargin?: number | null;
    vsPeerAvgEvToSales?: number | null;
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

function bottomStateTone(state: CompanyResearchResponse['bottomSignal'] extends { state: infer S } ? S : string) {
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

function correctionVerdictTone(verdict: '조정 우세' | '혼합' | '추세전환 경계') {
  switch (verdict) {
    case '조정 우세':
      return 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100';
    case '추세전환 경계':
      return 'border-rose-500/20 bg-rose-500/10 text-rose-100';
    default:
      return 'border-amber-500/20 bg-amber-500/10 text-amber-100';
  }
}

function thesisStatusTone(status: '유지' | '일부 약화' | '훼손 경계') {
  switch (status) {
    case '유지':
      return 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100';
    case '훼손 경계':
      return 'border-rose-500/20 bg-rose-500/10 text-rose-100';
    default:
      return 'border-amber-500/20 bg-amber-500/10 text-amber-100';
  }
}

function reversalTone(status: 'OFF' | 'EARLY' | 'ON' | 'STRONG') {
  switch (status) {
    case 'STRONG':
      return 'border-fuchsia-500/20 bg-fuchsia-500/10 text-fuchsia-100';
    case 'ON':
      return 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100';
    case 'EARLY':
      return 'border-cyan-500/20 bg-cyan-500/10 text-cyan-100';
    default:
      return 'border-white/10 bg-white/5 text-white/75';
  }
}

function priceStructureStageTone(stage: NonNullable<NonNullable<CompanyResearchResponse['bottomSignal']>['priceStructure']>['bearishReversalStage']) {
  switch (stage) {
    case 'INTACT':
      return 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100';
    case 'MOMENTUM_WEAKENING':
      return 'border-amber-500/20 bg-amber-500/10 text-amber-100';
    case 'STRUCTURAL_CRACK':
      return 'border-orange-500/20 bg-orange-500/10 text-orange-100';
    case 'PRIOR_LOW_BROKEN':
      return 'border-rose-500/25 bg-rose-500/10 text-rose-100';
    default:
      return 'border-white/10 bg-white/5 text-white/70';
  }
}

function fmtSignedPctPoint(value: number | null | undefined) {
  if (value === null || value === undefined || Number.isNaN(value)) return "—";
  return `${value >= 0 ? '+' : ''}${value.toFixed(1)}%p`;
}

function toSparkline(values: Array<number | null | undefined>, fallback = '—') {
  const valid = values.filter((value): value is number => typeof value === 'number' && Number.isFinite(value));
  if (!valid.length) return fallback;
  const bars = '▁▂▃▄▅▆▇█';
  const min = Math.min(...valid);
  const max = Math.max(...valid);
  if (min === max) return bars[Math.floor((bars.length - 1) / 2)].repeat(valid.length);
  return valid
    .map((value) => bars[Math.max(0, Math.min(bars.length - 1, Math.round(((value - min) / (max - min)) * (bars.length - 1))))])
    .join('');
}

function fmtSigned(value: number | null | undefined, digits = 2, suffix = '') {
  if (value === null || value === undefined || Number.isNaN(value)) return '—';
  return `${value >= 0 ? '+' : ''}${value.toFixed(digits)}${suffix}`;
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

function verdictTone(label: '우호' | '양호' | '중립' | '주의') {
  return label === '우호' ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-200' : label === '양호' ? 'border-cyan-500/30 bg-cyan-500/10 text-cyan-200' : label === '중립' ? 'border-amber-500/30 bg-amber-500/10 text-amber-200' : 'border-red-500/30 bg-red-500/10 text-red-200';
}


function fmtGuidanceValue(value: { raw: string; min: number | null; max: number | null; unit: 'usd' | 'percent' | 'bps' | 'other' | null } | null | undefined) {
  if (!value) return null;
  if (value.min === null && value.max === null) return value.raw;
  const formatUsd = (amount: number) => {
    if (Math.abs(amount) >= 1_000_000_000) return `$${(amount / 1_000_000_000).toFixed(1)}B`;
    if (Math.abs(amount) >= 1_000_000) return `$${(amount / 1_000_000).toFixed(0)}M`;
    return `$${amount}`;
  };
  const format = (amount: number) => {
    if (value.unit === 'usd') return formatUsd(amount);
    if (value.unit === 'percent') return `${amount}%`;
    if (value.unit === 'bps') return `${amount}bps`;
    return `${amount}`;
  };
  if (value.min !== null && value.max !== null && value.min !== value.max) {
    return `${format(value.min)} ~ ${format(value.max)}`;
  }
  if (value.min !== null) return value.max === null ? `${format(value.min)}+` : format(value.min);
  if (value.max !== null) return value.min === null ? `≤ ${format(value.max)}` : format(value.max);
  return value.raw;
}


async function fetchCompany(ticker: string): Promise<CompanyResearchResponse | null> {
  // Company projections already have a cheap persisted/backend cache and can be
  // refreshed asynchronously. A second Next data-cache layer can retain the
  // startup seed after the backend projection has gained new price/fundamental
  // evidence, so the detail page must always ask the owning service for its
  // latest projection.
  return fetchServerJson<CompanyResearchResponse>(`/api/company/${encodeURIComponent(ticker)}`, {
    cache: "no-store",
  });
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
          <SmartLink href="/research" className="text-cyan-300 cursor-pointer hover:text-cyan-200 underline">
            ← 리서치 홈으로
          </SmartLink>
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

  const upsideSpark = toSparkline((data.financials.estimateUpsideHistory ?? []).map((item) => item.value));
  const analystSpark = toSparkline((data.financials.analystScoreHistory ?? []).map((item) => item.value));

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
    ["주식수 희석 3년 CAGR", fmtPct(data.financials.shareDilution3yCagr ?? null), ""],
    ["주식보상/매출", fmtPct(data.financials.stockCompToRevenue), ""],
    ["ROE", fmtPct(data.financials.roe), ""],
    [data.financials.roicEstimated ? "ROIC (추정)" : "ROIC", fmtPct(data.financials.roic ?? null), ""],
    ["발생액/평균자산", fmtPct(data.financials.accrualRatio ?? null), ""],
    ["마진 추세", data.financials.operatingMarginTrend !== null ? `${data.financials.operatingMarginTrend.toFixed(1)}%p` : "—", ""],
    ["애널리스트 업사이드", fmtPct(data.financials.estimateUpsidePct), ""],
    ["EPS 추정치 7일 변화", fmtSigned(data.financials.estimateRevision7d, 1, "%"), ""],
    ["EPS 추정치 30일 변화", fmtSigned(data.financials.estimateRevision30d, 1, "%"), ""],
    ["EPS 추정치 90일 변화", fmtSigned(data.financials.estimateRevision90d, 1, "%"), ""],
    ["목표가 상승여력 30일 변화", fmtSignedPctPoint(data.financials.targetUpsideChange30d), ""],
    ["애널리스트 점수", data.financials.analystScore !== null && data.financials.analystScore !== undefined ? data.financials.analystScore.toFixed(2) : "—", ""],
    ["애널리스트 점수 7일 변화", data.financials.analystScoreRevision7d !== null && data.financials.analystScoreRevision7d !== undefined ? `${data.financials.analystScoreRevision7d >= 0 ? '+' : ''}${data.financials.analystScoreRevision7d.toFixed(2)}` : "—", ""],
    ["애널리스트 점수 30일 변화", data.financials.analystScoreRevision30d !== null && data.financials.analystScoreRevision30d !== undefined ? `${data.financials.analystScoreRevision30d >= 0 ? '+' : ''}${data.financials.analystScoreRevision30d.toFixed(2)}` : "—", ""],
    ["애널리스트 점수 90일 변화", data.financials.analystScoreRevision90d !== null && data.financials.analystScoreRevision90d !== undefined ? `${data.financials.analystScoreRevision90d >= 0 ? '+' : ''}${data.financials.analystScoreRevision90d.toFixed(2)}` : "—", ""],
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
              <SmartLink href="/research" className="cursor-pointer hover:text-white">리서치</SmartLink> / {data.profile.ticker}
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

        {data.verdicts?.investmentDecision ? (
          <InvestmentDecisionPanel decision={data.verdicts.investmentDecision} />
        ) : null}

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="flex items-center justify-between gap-3 mb-3">
            <div>
              <div className="text-sm text-[var(--muted)]">종합 점수</div>
              <div className="text-3xl font-bold">{data.score.totalScore !== null ? `${data.score.totalScore}/100` : "검수 대기"}</div>
            </div>
            <ScoreBadge label="총점" value={data.score.totalScore} title="총점입니다. 기업의 기초체력을 종합한 점수로 70+면 건강한 편, 55~69면 보통 이상, 그 이하면 약한 편입니다." kind="total" className="text-sm px-3" />
          </div>
          <div className="flex flex-wrap gap-2">
            {data.highlights.map((item) => (
              <span key={item} className="rounded-full border border-white/10 bg-white/5 px-3 py-1 text-xs text-slate-200">
                {item}
              </span>
            ))}
          </div>
        </section>

        {data.financials.scoreComparable === false ? (
          <section className="rounded-2xl border border-amber-500/25 bg-amber-500/5 p-5">
            <div className="flex flex-wrap items-center gap-2">
              <div className="font-semibold text-amber-100">기업 점수 보류</div>
              <span className="rounded-full border border-amber-500/30 bg-amber-500/10 px-2 py-1 text-[10px] text-amber-100">
                {data.financials.fundamentalsStatus ?? "UNKNOWN"}
              </span>
            </div>
            <div className="mt-2 text-xs leading-relaxed text-amber-50/80">
              최신 정기보고서와 계산 기준일 또는 점수 축의 근거가 완전히 일치할 때까지 총점·B점수를 표시하지 않습니다.
              {data.financials.latestPeriodicForm && data.financials.latestPeriodicReportDate
                ? ` 최신 ${data.financials.latestPeriodicForm} 보고기간: ${data.financials.latestPeriodicReportDate}.`
                : ""}
            </div>
            {(data.financials.scoreWarnings ?? []).length > 0 ? (
              <ul className="mt-3 space-y-1 text-xs text-amber-50/75">
                {(data.financials.scoreWarnings ?? []).map((warning) => <li key={warning}>• {warning}</li>)}
              </ul>
            ) : null}
          </section>
        ) : null}

        <ScoreLegend defaultOpen />

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
            <div>
              <div className="text-sm text-[var(--muted)]">기존 B 실행 참고 점수</div>
              <div className="mt-1 flex flex-wrap items-center gap-3">
                <div className="text-3xl font-bold">{data.buyScore.buyScore !== null ? `${data.buyScore.buyScore}/100` : "검수 대기"}</div>
                <ScoreBadge label="B" value={data.buyScore.buyScore} title="기존 B 참고 점수입니다. 최종 매수·보유·축소 판단은 위 투자 판단 스택이 품질·가격·촉매·섹터·진입·위험을 함께 검증해 결정합니다." kind="buy" className="text-sm px-3" />
                <ActionBadge value={data.buyScore.buyScore} />
              </div>
              <div className="mt-2 rounded-xl border border-cyan-500/15 bg-cyan-500/5 px-3 py-2 text-xs text-[var(--muted)] break-words">
                <strong className="text-cyan-100">해석법:</strong> 매력도는 펀더멘털/성장/밸류 종합, 과열도는 밸류 부담·컨센서스 쏠림·군중화를 뜻합니다. B는 기존 화면·알림 호환용 실행 참고값이며, 단독 매수 신호가 아닙니다. 최종 액션은 위 6축 판단의 위험 게이트를 우선합니다.
              </div>
            </div>
            <div className="grid grid-cols-3 gap-2 text-center text-xs min-w-[240px]">
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-[var(--muted)]">매력도 <HelpDot title="매력도는 가격보다 기업 상태가 얼마나 좋은지에 가깝습니다. 높을수록 기업 체력/성장/밸류 조합이 좋습니다." /></div>
                <div className={`mt-1 text-lg font-semibold ${scoreTone(data.buyScore.appealScore, 'appeal').split(' ')[0]}`}>{data.buyScore.appealScore ?? "—"}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-[var(--muted)]">과열도 <HelpDot title="과열도는 좋은 기업이라도 이미 많이 올라 추격 위험이 큰지 보는 값입니다. 70+면 추격 주의입니다." /></div>
                <div className={`mt-1 text-lg font-semibold ${scoreTone(data.buyScore.crowdingScore, 'crowding').split(' ')[0]}`}>{data.buyScore.crowdingScore ?? "—"}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-[var(--muted)]">실행점수 <HelpDot title="실행점수는 매력도와 과열도를 합친 최종 참고값입니다. 기업이 좋고 과열이 낮을수록 높아집니다." /></div>
                <div className={`mt-1 text-lg font-semibold ${scoreTone(data.buyScore.buyScore, 'buy').split(' ')[0]}`}>{data.buyScore.buyScore ?? "—"}</div>
              </div>
            </div>
          </div>
          <ul className="mt-4 grid grid-cols-1 md:grid-cols-2 gap-2 text-sm text-[var(--muted)]">
            {data.buyScore.reasons.map((item) => (
              <li key={item} className="rounded-xl border border-white/10 bg-black/15 px-3 py-2 break-words">• {item}</li>
            ))}
          </ul>
        </section>

        {(data.correctionAssessment || data.thesisMonitor) ? (
          <section className="grid grid-cols-1 gap-4 xl:grid-cols-2">
            {data.correctionAssessment ? (
              <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
                <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                  <div>
                    <div className="text-lg font-semibold">조정 vs 추세전환 판별</div>
                    <div className="mt-1 text-xs text-[var(--muted)]">이번 하락이 눌림인지, 사업가설 훼손인지 먼저 구분합니다.</div>
                  </div>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className={`rounded-full border px-2.5 py-1 text-xs ${correctionVerdictTone(data.correctionAssessment.verdict)}`}>{data.correctionAssessment.verdict}</span>
                    <span className="rounded-full border border-white/10 bg-white/5 px-2.5 py-1 text-xs text-white/80">{data.correctionAssessment.actionBias}</span>
                  </div>
                </div>
                <div className="mt-3 grid grid-cols-2 gap-3 text-sm">
                  <div className="rounded-xl border border-emerald-500/15 bg-emerald-500/5 p-3">
                    <div className="text-xs text-emerald-100">조정 점수</div>
                    <div className="mt-1 text-2xl font-semibold text-white">{data.correctionAssessment.correctionScore}</div>
                    <div className="mt-1 text-[11px] text-[var(--muted)]">높을수록 단순 조정 근거 우세 · 확률 아님</div>
                  </div>
                  <div className="rounded-xl border border-rose-500/15 bg-rose-500/5 p-3">
                    <div className="text-xs text-rose-100">추세훼손 위험</div>
                    <div className="mt-1 text-2xl font-semibold text-white">{data.correctionAssessment.trendBreakRiskScore}</div>
                    <div className="mt-1 text-[11px] text-[var(--muted)]">높을수록 내러티브/실적 훼손 위험</div>
                  </div>
                </div>
                <div className="mt-3 rounded-xl border border-white/10 bg-black/15 p-3 text-xs text-[var(--muted)] break-words">{data.correctionAssessment.summary}</div>
                {data.correctionAssessment.methodology ? <div className="mt-2 text-[11px] text-white/45">{data.correctionAssessment.methodology}</div> : null}
                {data.reversalConfirmation ? (
                  <div className="mt-3 rounded-xl border border-fuchsia-500/15 bg-fuchsia-500/5 p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <div className="text-sm font-medium text-white">반전확인 신호</div>
                      <span className={`rounded-full border px-2 py-0.5 text-[10px] ${reversalTone(data.reversalConfirmation.status)}`}>{data.reversalConfirmation.status}</span>
                      <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-[10px] text-white/80">{data.reversalConfirmation.score}/100</span>
                      {data.reversalConfirmation.signalDate ? <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-[10px] text-white/70">신호일 {data.reversalConfirmation.signalDate}</span> : null}
                    </div>
                    <div className="mt-2 text-xs text-[var(--muted)] break-words">{data.reversalConfirmation.summary}</div>
                  </div>
                ) : null}
                <div className="mt-3 grid grid-cols-1 gap-3 lg:grid-cols-2 text-xs">
                  <div className="rounded-xl border border-emerald-500/15 bg-emerald-500/5 p-3">
                    <div className="mb-2 font-medium text-emerald-100">조정으로 보는 근거</div>
                    <div className="space-y-1 text-emerald-50/90">{data.correctionAssessment.reasons.map((item) => <div key={item}>• {item}</div>)}</div>
                  </div>
                  <div className="rounded-xl border border-rose-500/15 bg-rose-500/5 p-3">
                    <div className="mb-2 font-medium text-rose-100">추세훼손 리스크</div>
                    <div className="space-y-1 text-rose-50/90">{data.correctionAssessment.risks.map((item) => <div key={item}>• {item}</div>)}</div>
                  </div>
                </div>
              </div>
            ) : null}

            {data.thesisMonitor ? (
              <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
                <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                  <div>
                    <div className="text-lg font-semibold">매수 이유 / 훼손 조건</div>
                    <div className="mt-1 text-xs text-[var(--muted)]">왜 사는지와 무엇이 깨지면 틀린 건지를 분리해 보여줍니다.</div>
                  </div>
                  <span className={`rounded-full border px-2.5 py-1 text-xs ${thesisStatusTone(data.thesisMonitor.status)}`}>{data.thesisMonitor.status}</span>
                </div>
                <div className="mt-3 rounded-xl border border-white/10 bg-black/15 p-3 text-xs text-[var(--muted)] break-words">{data.thesisMonitor.summary}</div>
                <div className="mt-3 grid grid-cols-1 gap-3 lg:grid-cols-2 text-xs">
                  <div className="rounded-xl border border-cyan-500/15 bg-cyan-500/5 p-3">
                    <div className="mb-2 font-medium text-cyan-100">현재 매수 이유</div>
                    <div className="space-y-1 text-cyan-50/90">{data.thesisMonitor.reasons.map((item) => <div key={item}>• {item}</div>)}</div>
                  </div>
                  <div className="rounded-xl border border-amber-500/15 bg-amber-500/5 p-3">
                    <div className="mb-2 font-medium text-amber-100">이 조건이 나오면 재검토</div>
                    <div className="space-y-1 text-amber-50/90">{data.thesisMonitor.breakConditions.map((item) => <div key={item}>• {item}</div>)}</div>
                  </div>
                </div>
              </div>
            ) : null}
          </section>
        ) : null}

        {data.bottomSignal ? (
          <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
              <div>
                <div className="text-sm text-[var(--muted)]">반전 거래량 동반 바닥 확인</div>
                <div className="mt-1 flex flex-wrap items-center gap-3">
                  <div className="text-3xl font-bold">{data.bottomSignal.score ?? '—'}{data.bottomSignal.score !== null ? '/100' : ''}</div>
                  <span className={`rounded-full border px-3 py-1 text-xs ${bottomStateTone(data.bottomSignal.state)}`}>{data.bottomSignal.state}</span>
                  <span className="rounded-full border border-white/10 bg-white/5 px-3 py-1 text-xs text-white/85">{data.bottomSignal.actionBias}</span>
                </div>
              <div className="mt-2 rounded-xl border border-white/10 bg-black/15 px-3 py-2 text-xs text-[var(--muted)] break-words">
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
            <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-3">
              <div className="rounded-xl border border-cyan-500/15 bg-cyan-500/5 p-3 text-xs">
                <div className="text-cyan-100">실적 바닥</div>
                <div className="mt-1 text-2xl font-semibold text-white">{data.bottomSignal.earningsBottomScore ?? '—'}</div>
                <div className="mt-1 text-[var(--muted)]">가이던스·EPS 추정치·사업체력 기준</div>
              </div>
              <div className="rounded-xl border border-emerald-500/15 bg-emerald-500/5 p-3 text-xs">
                <div className="text-emerald-100">가격 바닥</div>
                <div className="mt-1 text-2xl font-semibold text-white">{data.bottomSignal.priceBottomScore ?? '—'}</div>
                <div className="mt-1 text-[var(--muted)]">가격 패턴·재시험·과열 완화 기준</div>
              </div>
              <div className="rounded-xl border border-amber-500/15 bg-amber-500/5 p-3 text-xs">
                <div className="text-amber-100">거래량 동반</div>
                <div className="mt-1 text-2xl font-semibold text-white">{data.bottomSignal.volumeConfirmationScore ?? '—'}</div>
                <div className="mt-1 text-[var(--muted)]">진짜 바닥인지 보는 핵심 수급 기준</div>
              </div>
              <div className="rounded-xl border border-rose-500/15 bg-rose-500/5 p-3 text-xs">
                <div className="text-rose-100">실패 위험</div>
                <div className="mt-1 text-2xl font-semibold text-white">{data.bottomSignal.failureRiskScore ?? '—'}</div>
              <div className="mt-1 text-[var(--muted)]">재시험 실패·가짜 돌파·실적 재하향 위험</div>
            </div>
              {data.bottomSignal.confirmedBottom ? (
                <div className="rounded-xl border border-fuchsia-500/15 bg-fuchsia-500/5 p-3 text-xs">
                  <div className="text-fuchsia-100">찐바닥 신호</div>
                  <div className="mt-1 text-2xl font-semibold text-white">{data.bottomSignal.confirmedBottom.score}</div>
                  <div className="mt-1 text-[var(--muted)]">미래 반등 제외 · 당시 데이터만으로 계산</div>
                </div>
              ) : null}
            </div>
            {data.bottomSignal.expectationEvidenceNotice ? <div className="mt-3 rounded-xl border border-cyan-500/10 bg-cyan-500/5 px-3 py-2 text-[11px] text-cyan-50/70">{data.bottomSignal.expectationEvidenceNotice}</div> : null}
            <div className="mt-4">
              <BottomConfirmationChart
                points={data.bottomSignal.chart.points}
                markers={data.bottomSignal.chart.markers}
                structure={data.bottomSignal.priceStructure}
              />
            </div>
            {data.bottomSignal.priceStructure ? (
              <div data-testid="company-price-structure" className="mt-4 rounded-2xl border border-cyan-500/20 bg-cyan-500/5 p-4">
                <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                  <div>
                    <div className="text-sm font-medium text-cyan-100">가격 구조 · 시장 심리</div>
                    <div className="mt-1 max-w-3xl text-xs leading-relaxed text-[var(--muted)]">
                      지지·저항은 한 줄이 아닌 구간이며, RSI 과매도는 다우 구조·채널 위치·거래량과 함께 맞을 때만 의미를 부여합니다.
                    </div>
                    <div className="mt-2 flex flex-wrap gap-2 text-[11px]">
                      <span className="rounded-full border border-white/10 bg-black/15 px-2.5 py-1 text-white/85">{data.bottomSignal.priceStructure.trendLabel}</span>
                      <span className={`rounded-full border px-2.5 py-1 ${priceStructureStageTone(data.bottomSignal.priceStructure.bearishReversalStage)}`}>
                        {data.bottomSignal.priceStructure.bearishReversalLabel}
                      </span>
                      <span className="rounded-full border border-fuchsia-500/20 bg-fuchsia-500/10 px-2.5 py-1 text-fuchsia-100">{data.bottomSignal.priceStructure.recoveryLabel}</span>
                      <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2.5 py-1 text-cyan-100">{data.bottomSignal.priceStructure.priceLocationLabel}</span>
                      <span className="rounded-full border border-blue-500/20 bg-blue-500/10 px-2.5 py-1 text-blue-100">{data.bottomSignal.priceStructure.movingAverageLabel}</span>
                    </div>
                  </div>
                  <div className={`shrink-0 rounded-xl border px-4 py-3 text-center ${scoreTone(data.bottomSignal.priceStructure.score, 'quality')}`}>
                    <div className="text-[10px] opacity-75">구조 합치도</div>
                    <div className="mt-1 text-2xl font-semibold">{data.bottomSignal.priceStructure.score}/100</div>
                    <div className="mt-1 text-[10px] opacity-70">수익 확률 아님</div>
                  </div>
                </div>

                <div className="mt-3 grid grid-cols-2 gap-2 text-xs sm:grid-cols-4 lg:grid-cols-6">
                  <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">RSI 14</div><div className="mt-1 text-lg font-semibold text-white">{fmtNum(data.bottomSignal.priceStructure.rsi14, 1)}</div></div>
                  <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">채널 위치</div><div className="mt-1 text-lg font-semibold text-white">{fmtPct(data.bottomSignal.priceStructure.channelPositionPct)}</div></div>
                  <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">채널 연환산 기울기</div><div className="mt-1 text-lg font-semibold text-white">{fmtPct(data.bottomSignal.priceStructure.channelAnnualizedSlopePct)}</div></div>
                  <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">이평선 수렴폭</div><div className="mt-1 text-lg font-semibold text-white">{fmtPct(data.bottomSignal.priceStructure.movingAverageConvergencePct)}</div></div>
                  <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">횡보 기간</div><div className="mt-1 text-lg font-semibold text-white">{data.bottomSignal.priceStructure.consolidationDays || '—'}일</div></div>
                  <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">횡보 범위</div><div className="mt-1 text-lg font-semibold text-white">{fmtPct(data.bottomSignal.priceStructure.consolidationRangePct)}</div></div>
                </div>

                <div className="mt-3 grid grid-cols-1 gap-2 text-xs md:grid-cols-2">
                  <div className="rounded-xl border border-emerald-500/15 bg-emerald-500/5 p-3">
                    <div className="text-emerald-100">가까운 지지 구간</div>
                    {data.bottomSignal.priceStructure.supportZone ? (
                      <div className="mt-1 text-white">
                        {fmtNum(data.bottomSignal.priceStructure.supportZone.lower, 2)} ~ {fmtNum(data.bottomSignal.priceStructure.supportZone.upper, 2)}
                        <span className="ml-2 text-[11px] text-[var(--muted)]">
                          {data.bottomSignal.priceStructure.supportZone.touches}회 · 강도 {data.bottomSignal.priceStructure.supportZone.strength}
                          {data.bottomSignal.priceStructure.supportZone.roleFlip ? ' · 저항→지지 전환' : ''}
                        </span>
                      </div>
                    ) : <div className="mt-1 text-[var(--muted)]">반복 확인 구간 부족</div>}
                  </div>
                  <div className="rounded-xl border border-rose-500/15 bg-rose-500/5 p-3">
                    <div className="text-rose-100">가까운 저항 구간</div>
                    {data.bottomSignal.priceStructure.resistanceZone ? (
                      <div className="mt-1 text-white">
                        {fmtNum(data.bottomSignal.priceStructure.resistanceZone.lower, 2)} ~ {fmtNum(data.bottomSignal.priceStructure.resistanceZone.upper, 2)}
                        <span className="ml-2 text-[11px] text-[var(--muted)]">
                          {data.bottomSignal.priceStructure.resistanceZone.touches}회 · 강도 {data.bottomSignal.priceStructure.resistanceZone.strength}
                          {data.bottomSignal.priceStructure.resistanceZone.roleFlip ? ' · 지지→저항 전환' : ''}
                        </span>
                      </div>
                    ) : <div className="mt-1 text-[var(--muted)]">반복 확인 구간 부족</div>}
                  </div>
                </div>

                {data.bottomSignal.priceStructure.fibonacci?.levels?.length ? (
                  <div data-testid="company-fibonacci-structure" className="mt-3 rounded-xl border border-yellow-500/20 bg-yellow-500/5 p-3">
                    <div className="flex flex-col gap-3 lg:flex-row lg:items-start lg:justify-between">
                      <div>
                        <div className="text-sm font-medium text-yellow-100">주요 파동 피보나치 되돌림</div>
                        <div className="mt-1 text-xs leading-relaxed text-white/70">
                          {data.bottomSignal.priceStructure.fibonacci.summary}
                        </div>
                        <div className="mt-2 flex flex-wrap gap-2 text-[11px]">
                          <span className="rounded-full border border-yellow-500/20 bg-black/15 px-2.5 py-1 text-yellow-50">
                            {data.bottomSignal.priceStructure.fibonacci.swingDirectionLabel}
                            {data.bottomSignal.priceStructure.fibonacci.swingStartDate && data.bottomSignal.priceStructure.fibonacci.swingEndDate
                              ? ` · ${data.bottomSignal.priceStructure.fibonacci.swingStartDate} → ${data.bottomSignal.priceStructure.fibonacci.swingEndDate}`
                              : ''}
                          </span>
                          <span className="rounded-full border border-white/10 bg-black/15 px-2.5 py-1 text-white/80">
                            {data.bottomSignal.priceStructure.fibonacci.timeframeLabel}
                          </span>
                          <span className={`rounded-full border px-2.5 py-1 ${
                            data.bottomSignal.priceStructure.fibonacci.zoneState === 'LAST_DEFENSE_BROKEN'
                              ? 'border-rose-500/25 bg-rose-500/10 text-rose-100'
                              : 'border-amber-500/20 bg-amber-500/10 text-amber-100'
                          }`}>
                            {data.bottomSignal.priceStructure.fibonacci.zoneLabel}
                          </span>
                        </div>
                      </div>
                      <div className="grid shrink-0 grid-cols-3 gap-2 text-center text-xs">
                        <div className="rounded-lg border border-yellow-500/20 bg-black/15 px-3 py-2">
                          <div className="text-white/55">현재 되돌림</div>
                          <div className="mt-1 font-semibold text-white">
                            {typeof data.bottomSignal.priceStructure.fibonacci.currentRetracementRatio === 'number'
                              ? `${(data.bottomSignal.priceStructure.fibonacci.currentRetracementRatio * 100).toFixed(1)}%`
                              : '—'}
                          </div>
                        </div>
                        <div className="rounded-lg border border-yellow-500/20 bg-black/15 px-3 py-2">
                          <div className="text-white/55">가까운 비율</div>
                          <div className="mt-1 font-semibold text-white">
                            {typeof data.bottomSignal.priceStructure.fibonacci.nearestRatio === 'number'
                              ? data.bottomSignal.priceStructure.fibonacci.nearestRatio.toFixed(3)
                              : '—'}
                          </div>
                        </div>
                        <div className="rounded-lg border border-yellow-500/20 bg-black/15 px-3 py-2">
                          <div className="text-white/55">교차 합치도</div>
                          <div className="mt-1 font-semibold text-white">{data.bottomSignal.priceStructure.fibonacci.confluenceScore}/100</div>
                        </div>
                      </div>
                    </div>
                    <div className="mt-3 grid grid-cols-2 gap-2 text-[11px] sm:grid-cols-5">
                      {data.bottomSignal.priceStructure.fibonacci.levels.map((level) => (
                        <div
                          key={level.ratio}
                          className={`rounded-lg border p-2 ${
                            data.bottomSignal?.priceStructure?.fibonacci?.nearestRatio === level.ratio
                              ? 'border-yellow-400/35 bg-yellow-400/10'
                              : 'border-white/10 bg-black/15'
                          }`}
                        >
                          <div className="text-white/55">{level.ratio.toFixed(3)}</div>
                          <div className="mt-1 font-semibold text-white">{fmtNum(level.price, 2)}</div>
                        </div>
                      ))}
                    </div>
                    <div className="mt-3 flex flex-wrap gap-2 text-[11px]">
                      <span className={`rounded-full border px-2.5 py-1 ${data.bottomSignal.priceStructure.fibonacci.weeklyConfluence ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100' : 'border-white/10 bg-black/15 text-white/55'}`}>주봉 합치 {data.bottomSignal.priceStructure.fibonacci.weeklyConfluence ? 'ON' : 'OFF'}</span>
                      <span className={`rounded-full border px-2.5 py-1 ${data.bottomSignal.priceStructure.fibonacci.supportResistanceConfluence ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100' : 'border-white/10 bg-black/15 text-white/55'}`}>지지·저항 합치 {data.bottomSignal.priceStructure.fibonacci.supportResistanceConfluence ? 'ON' : 'OFF'}</span>
                      <span className={`rounded-full border px-2.5 py-1 ${data.bottomSignal.priceStructure.fibonacci.channelConfluence ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100' : 'border-white/10 bg-black/15 text-white/55'}`}>채널 합치 {data.bottomSignal.priceStructure.fibonacci.channelConfluence ? 'ON' : 'OFF'}</span>
                    </div>
                    {data.bottomSignal.priceStructure.fibonacci.cautions.length ? (
                      <div className="mt-3 space-y-1 rounded-lg border border-amber-500/15 bg-amber-500/5 p-3 text-[11px] leading-relaxed text-amber-50/80">
                        {data.bottomSignal.priceStructure.fibonacci.cautions.map((item) => <div key={item}>• {item}</div>)}
                      </div>
                    ) : null}
                  </div>
                ) : null}

                <div className="mt-3 flex flex-wrap gap-2 text-[11px]">
                  <span className={`rounded-full border px-2.5 py-1 ${data.bottomSignal.priceStructure.volumeBreakout ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100' : 'border-white/10 bg-black/15 text-white/55'}`}>거래량 돌파 {data.bottomSignal.priceStructure.volumeBreakout ? 'ON' : 'OFF'}</span>
                  <span className={`rounded-full border px-2.5 py-1 ${data.bottomSignal.priceStructure.stopHuntReclaim ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100' : 'border-white/10 bg-black/15 text-white/55'}`}>스톱헌트 회복 {data.bottomSignal.priceStructure.stopHuntReclaim ? 'ON' : 'OFF'}</span>
                  <span className={`rounded-full border px-2.5 py-1 ${data.bottomSignal.priceStructure.oversoldConfluence ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100' : 'border-white/10 bg-black/15 text-white/55'}`}>RSI 다중확인 {data.bottomSignal.priceStructure.oversoldConfluence ? 'ON' : 'OFF'}</span>
                </div>

                <div className="mt-3 grid grid-cols-1 gap-3 text-xs lg:grid-cols-2">
                  <div className="rounded-xl border border-emerald-500/15 bg-emerald-500/5 p-3">
                    <div className="mb-2 font-medium text-emerald-100">구조상 우호 근거</div>
                    <div className="space-y-1 text-emerald-50/90">{data.bottomSignal.priceStructure.reasons.map((item) => <div key={item}>• {item}</div>)}</div>
                  </div>
                  <div className="rounded-xl border border-amber-500/15 bg-amber-500/5 p-3">
                    <div className="mb-2 font-medium text-amber-100">추격·훼손 경계</div>
                    <div className="space-y-1 text-amber-50/90">{data.bottomSignal.priceStructure.cautions.map((item) => <div key={item}>• {item}</div>)}</div>
                  </div>
                </div>
                <details className="mt-3 rounded-xl border border-white/10 bg-black/15 p-3 text-[11px] text-[var(--muted)]">
                  <summary className="cursor-pointer text-white/75">계산 기준 보기</summary>
                  <div className="mt-2 leading-relaxed">{data.bottomSignal.priceStructure.methodology}</div>
                </details>
              </div>
            ) : null}
            {data.bottomSignal.macdMomentum ? <MacdMomentumPanel value={data.bottomSignal.macdMomentum} /> : null}
            {data.bottomSignal.technicalConfirmation ? (
              <div className="mt-4 rounded-2xl border border-sky-500/20 bg-sky-500/5 p-4">
                <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                  <div>
                    <div className="text-sm font-medium text-sky-100">OBV / 20일 VWAP 보조 확인</div>
                    <div className="mt-1 text-xs text-[var(--muted)]">{data.bottomSignal.technicalConfirmation.methodology}</div>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-xl font-semibold">{data.bottomSignal.technicalConfirmation.score}/100</span>
                    <span className="rounded-full border border-sky-500/20 bg-sky-500/10 px-2.5 py-1 text-[11px] text-sky-100">{data.bottomSignal.technicalConfirmation.state}</span>
                  </div>
                </div>
                <div className="mt-3 grid grid-cols-2 gap-2 text-xs sm:grid-cols-4">
                  <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">VWAP20 proxy</div><div className="mt-1 text-white">{fmtNum(data.bottomSignal.technicalConfirmation.vwap20, 2)}</div></div>
                  <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">종가-VWAP</div><div className="mt-1 text-white">{fmtPct(data.bottomSignal.technicalConfirmation.closeVsVwap20Pct)}</div></div>
                  <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">VWAP 5일 기울기</div><div className="mt-1 text-white">{fmtPct(data.bottomSignal.technicalConfirmation.vwapSlope5dPct)}</div></div>
                  <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">OBV 20일 압력</div><div className="mt-1 text-white">{fmtPct(data.bottomSignal.technicalConfirmation.obvPressure20Pct)}</div></div>
                </div>
              </div>
            ) : null}
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
                    <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">반전 확인일</div><div className="mt-1 text-white">{data.bottomSignal.confirmedBottom.signalDate ?? '—'}</div></div>
                    <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">경과일</div><div className="mt-1 text-white">{data.bottomSignal.confirmedBottom.daysSinceSignal ?? '—'}</div></div>
                    <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">반전 거래량</div><div className="mt-1 text-white">{data.bottomSignal.confirmedBottom.recentVolumeRatio !== null ? `${data.bottomSignal.confirmedBottom.recentVolumeRatio}x` : '—'}</div></div>
                    <div className="rounded-xl border border-white/10 bg-black/15 p-3"><div className="text-[var(--muted)]">낙폭 둔화</div><div className="mt-1 text-white">{data.bottomSignal.confirmedBottom.contractionRatio !== null ? `${Math.round(data.bottomSignal.confirmedBottom.contractionRatio * 100)}%` : '—'}</div></div>
                  </div>
                </div>
                <div className="mt-3 grid grid-cols-1 gap-3 lg:grid-cols-2 text-xs">
                  <div className="rounded-xl border border-emerald-500/15 bg-emerald-500/5 p-3">
                    <div className="mb-2 font-medium text-emerald-100">반전 확인 신호</div>
                    <div className="space-y-1 text-emerald-50/90">{data.bottomSignal.confirmedBottom.reasons.map((item) => <div key={item}>• {item}</div>)}</div>
                  </div>
                  <div className="rounded-xl border border-amber-500/15 bg-amber-500/5 p-3">
                    <div className="mb-2 font-medium text-amber-100">리스크 / 미충족</div>
                    <div className="space-y-1 text-amber-50/90">{data.bottomSignal.confirmedBottom.cautions.map((item) => <div key={item}>• {item}</div>)}</div>
                  </div>
                </div>
              </div>
            ) : null}
            <div className="mt-4 grid grid-cols-1 gap-3 lg:grid-cols-3">
              <div className="rounded-xl border border-emerald-500/15 bg-emerald-500/5 p-3 text-xs">
                <div className="mb-2 font-medium text-emerald-100">거래량이 받쳐주는 근거</div>
                <div className="space-y-1 text-emerald-50/90">{data.bottomSignal.reasons.map((item) => <div key={item}>• {item}</div>)}</div>
              </div>
              <div className="rounded-xl border border-amber-500/15 bg-amber-500/5 p-3 text-xs">
                <div className="mb-2 font-medium text-amber-100">남은 확인 포인트</div>
                <div className="space-y-1 text-amber-50/90">{data.bottomSignal.cautions.map((item) => <div key={item}>• {item}</div>)}</div>
              </div>
              <div className="rounded-xl border border-rose-500/15 bg-rose-500/5 p-3 text-xs">
                <div className="mb-2 font-medium text-rose-100">바닥 실패 시그널</div>
                <div className="space-y-1 text-rose-50/90">{(data.bottomSignal.failureSignals ?? []).map((item) => <div key={item}>• {item}</div>)}</div>
              </div>
            </div>
          </section>
        ) : null}

        <section className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-3">
          {scoreCards.map(([label, score, reason]) => (
            <div key={label} className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-4">
              <div className="text-xs text-[var(--muted)] mb-1">{label}</div>
              <div className="text-2xl font-semibold mb-2">{score ?? "—"}</div>
              <div className="text-xs leading-relaxed text-[var(--muted)] break-words">{reason || "—"}</div>
            </div>
          ))}
        </section>

        <section className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {data.sectorContext ? (
            <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <div className="text-lg font-semibold">섹터 연결</div>
                  <div className="mt-1 text-xs text-[var(--muted)]">거시 → 섹터 → 기업 흐름에서 이 회사가 놓인 위치입니다.</div>
                </div>
                <SmartLink href={`/research/sector/${data.sectorContext.sectorId}`} prefetch={false} className="text-xs text-cyan-300 cursor-pointer hover:text-cyan-200">섹터 보기 →</SmartLink>
              </div>
              <div className="mt-3 flex flex-wrap gap-2">
                <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1 text-xs text-white/85">{data.sectorContext.label}</span>
                <ScoreBadge label="B" value={data.sectorContext.buyScore} title="이 회사가 속한 표준 섹터의 B 점수입니다." kind="buy" />
                <ScoreBadge label="Q" value={data.sectorContext.qualityScore} title="이 회사가 속한 표준 섹터의 Q 점수입니다." kind="quality" />
                <ScoreBadge label="과열" value={data.sectorContext.crowdingScore} title="이 회사가 속한 표준 섹터의 과열도입니다." kind="crowding" />
                {data.sectorContext.rotationLabel ? (
                  <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-1 text-xs text-cyan-100">
                    {data.sectorContext.rotationLabel}
                  </span>
                ) : null}
                {typeof data.sectorContext.rotationRank === 'number' ? (
                  <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1 text-xs text-white/80">
                    순환 #{data.sectorContext.rotationRank}/{data.sectorContext.rotationUniverseSize ?? 11}
                    {typeof data.sectorContext.rotationPercentile === 'number' ? ` · 상위 ${Math.max(1, 100 - data.sectorContext.rotationPercentile)}%` : ''}
                  </span>
                ) : null}
              </div>
              <div className="mt-3 rounded-xl border border-white/10 bg-black/15 p-3 text-xs text-[var(--muted)] break-words">{data.sectorContext.thesis}</div>
              {data.sectorContext.expectedLeadershipMessage ? (
                <div className="mt-3 rounded-xl border border-cyan-500/15 bg-cyan-500/5 px-3 py-2 text-xs text-cyan-50/85">
                  예상 관찰 창 {horizonLabel(data.sectorContext.expectedLeadershipWindow ?? undefined)} · {data.sectorContext.expectedLeadershipMessage}
                </div>
              ) : null}
              {typeof data.sectorContext.rotationScore === 'number' ? (
                <div className="mt-3 grid grid-cols-4 gap-1.5 text-center text-[10px] sm:grid-cols-7">
                  {[
                    ['순환', data.sectorContext.rotationScore],
                    ['거시', data.sectorContext.macroFitScore],
                    ['상대강도', data.sectorContext.relativeStrengthScore],
                    ['펀더멘털', data.sectorContext.fundamentalScore],
                    [typeof data.sectorContext.earningsRevisionScore === 'number' ? '이익추정 현재' : '이익추정 참고',
                      data.sectorContext.earningsRevisionScore ?? data.sectorContext.earningsRevisionReferenceScore],
                    [typeof data.sectorContext.flowScore === 'number' ? '독립수급' : '수급 프록시',
                      data.sectorContext.flowScore ?? data.sectorContext.flowProxyScore],
                    ['밸류', data.sectorContext.valuationScore],
                  ].map(([label, value]) => (
                    <div key={String(label)} className="rounded-lg border border-white/10 bg-black/15 px-1.5 py-2">
                      <div className="text-white/55">{label}</div>
                      <div className="mt-1 font-semibold text-white">{typeof value === 'number' ? value : '—'}</div>
                    </div>
                  ))}
                </div>
              ) : null}
              {data.sectorContext.sectorEvidenceSummary ? (
                <div className="mt-3 rounded-xl border border-amber-500/15 bg-amber-500/5 px-3 py-2 text-[11px] leading-relaxed text-amber-50/80">
                  {data.sectorContext.sectorEvidenceSummary}
                </div>
              ) : null}
              {!!data.sectorContext.rotationReasons?.length ? (
                <div className="mt-3 space-y-1 text-[11px] leading-relaxed text-[var(--muted)]">
                  {data.sectorContext.rotationReasons.slice(0, 3).map((reason) => <div key={reason}>• {reason}</div>)}
                </div>
              ) : null}
              {data.sectorContext.relatedThemes.length ? (
                <div className="mt-3 flex flex-wrap gap-2 text-[11px]">
                  {data.sectorContext.relatedThemes.map((theme) => (
                    <SmartLink key={theme.id} href={`/research/theme/${theme.id}`} prefetch={false} className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-1 text-cyan-100 cursor-pointer hover:bg-cyan-500/20 active:scale-[0.99]">{theme.theme}</SmartLink>
                  ))}
                </div>
              ) : null}
            </div>
          ) : null}

          {data.timeframeView ? (
            <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
              <div className="text-lg font-semibold">시간 프레임 해석</div>
              <div className="mt-1 text-xs text-[var(--muted)]">좋은 회사여도 단기·스윙·장기 해석은 다를 수 있습니다.</div>
              <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-3">
                {([
                  ['단기', data.timeframeView.shortTerm],
                  ['스윙', data.timeframeView.swingTerm],
                  ['장기', data.timeframeView.longTerm],
                ] as const).map(([label, item]) => (
                  <div key={label} className="rounded-xl border border-white/10 bg-black/15 p-3">
                    <div className="flex items-center justify-between gap-2">
                      <div className="text-xs text-[var(--muted)]">{label}</div>
                      <ActionBadge value={item.stance === 'SELL' ? 20 : item.stance === 'REDUCE' ? 45 : item.stance === 'HOLD' ? 60 : item.stance === 'BUY' ? 75 : 88} compact />
                    </div>
                    {typeof item.score === 'number' ? <div className="mt-2 text-lg font-semibold">{item.score}/100 <span className="text-[10px] font-normal text-[var(--muted)]">충족 {item.confidence ?? '—'}%</span></div> : null}
                    <div className="mt-2 text-xs text-[var(--muted)] break-words">{item.summary}</div>
                  </div>
                ))}
              </div>
              {data.timeframeView.validation ? (
                <details className="mt-4 rounded-xl border border-white/10 bg-black/15 p-3">
                  <summary className="cursor-pointer text-xs font-medium text-white">워크포워드 검증 보기 · {data.timeframeView.validation.historyPointCount}거래일</summary>
                  <div className="mt-2 text-[11px] text-[var(--muted)]">{data.timeframeView.validation.methodology}</div>
                  <div className="mt-3 grid grid-cols-1 gap-2 text-xs sm:grid-cols-3">
                    {data.timeframeView.validation.horizons.map((metric) => (
                      <div key={metric.horizon} className="rounded-lg border border-white/10 bg-black/20 p-3">
                        <div className="font-medium text-white">{metric.forwardTradingDays}거래일 · 신호 {metric.signalCount}회</div>
                        <div className="mt-2 text-[var(--muted)]">양(+) 수익 {metric.positiveHitRatePct !== null ? `${metric.positiveHitRatePct}%` : '—'}</div>
                        <div className="text-[var(--muted)]">목표 +{metric.targetReturnPct}% 도달 {metric.targetHitRatePct !== null ? `${metric.targetHitRatePct}%` : '—'}</div>
                        <div className="text-[var(--muted)]">평균 {metric.averageReturnPct !== null ? `${metric.averageReturnPct}%` : '—'} · 평균 MDD {metric.averageMaxDrawdownPct !== null ? `${metric.averageMaxDrawdownPct}%` : '—'}</div>
                      </div>
                    ))}
                  </div>
                </details>
              ) : null}
            </div>
          ) : null}

          {data.verdicts ? (
            <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
              <div className="text-lg font-semibold mb-3">좋은 회사 vs 좋은 투자</div>
              {data.verdicts.oneLiners ? (
                <div className="mb-3 grid grid-cols-1 gap-2 text-xs sm:grid-cols-2">
                  <div className="rounded-xl border border-cyan-500/15 bg-cyan-500/5 px-3 py-2 text-cyan-100">사업: {data.verdicts.oneLiners.business}</div>
                  <div className="rounded-xl border border-amber-500/15 bg-amber-500/5 px-3 py-2 text-amber-100">가격: {data.verdicts.oneLiners.valuation}</div>
                  <div className="rounded-xl border border-white/10 bg-black/15 px-3 py-2 text-white/85">타이밍: {data.verdicts.oneLiners.timing}</div>
                  <div className="rounded-xl border border-emerald-500/15 bg-emerald-500/5 px-3 py-2 text-emerald-100">최종: {data.verdicts.oneLiners.action}</div>
                </div>
              ) : null}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-sm">
                {[
                  { label: '사업 품질', item: data.verdicts.businessQuality },
                  { label: '밸류', item: data.verdicts.valuation },
                  { label: '타이밍', item: data.verdicts.timing },
                  { label: '최종 판단', item: data.verdicts.finalAction },
                ].map(({ label, item }) => (
                  <div key={label} className="rounded-xl border border-white/10 bg-black/15 p-3">
                    <div className="flex items-center justify-between gap-2">
                      <div className="text-xs text-[var(--muted)]">{label}</div>
                      <span className={`rounded-full border px-2 py-0.5 text-[10px] ${verdictTone(item.label)}`}>{item.label} · {item.score}</span>
                    </div>
                    <div className="mt-2 text-xs text-[var(--muted)] break-words">{item.summary}</div>
                  </div>
                ))}
              </div>
            </div>
          ) : null}

          {data.positionSizing ? (
            <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
              <div className="flex items-center justify-between gap-3 mb-3">
                <div>
                  <div className="text-lg font-semibold">비중 / 실행 플레이북</div>
                  <div className="mt-1 text-xs text-[var(--muted)]">company 판단을 실제 1차 진입·남길 현금·축소 조건으로 번역한 카드입니다.</div>
                </div>
                <span className={`rounded-full border px-2.5 py-1 text-xs ${scoreTone(data.positionSizing.action === 'SELL' ? 20 : data.positionSizing.action === 'REDUCE' ? 45 : data.positionSizing.action === 'HOLD' ? 60 : data.positionSizing.action === 'BUY' ? 75 : 88, 'buy')}`}>{data.positionSizing.action}</span>
              </div>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
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
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">현재 가격 위치</div>
                  <div className="mt-1 text-xl font-semibold">{data.positionSizing.zoneLabel ?? data.positionSizing.action}</div>
                </div>
              </div>
              <div className="mt-3 rounded-xl border border-white/10 bg-black/15 p-3 text-xs text-[var(--muted)] break-words">{data.positionSizing.summary}</div>
              {data.executionBridge ? (
                <div className="mt-3 rounded-2xl border border-cyan-500/20 bg-cyan-500/5 p-4 text-xs text-[var(--muted)]">
                  <div className="mb-3 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                    <div>
                      <div className="font-medium text-white">연결된 자산 실행계획</div>
                      <div className="mt-1 text-[11px] text-white/70">이 회사 판단을 실제 자산 실행계획에 연결한 브리지입니다.</div>
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                      <ActionBadge value={data.positionSizing?.action === 'SELL' ? 20 : data.positionSizing?.action === 'REDUCE' ? 45 : data.positionSizing?.action === 'HOLD' ? 60 : data.positionSizing?.action === 'BUY' ? 75 : 88} compact />
                      <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-[10px] text-white/80">{data.executionBridge.asset} · 목표 {data.executionBridge.targetAllocationPct}%</span>
                    </div>
                  </div>
                  <div className="rounded-xl border border-white/10 bg-black/20 p-3">
                    <div className="text-white/90">회사 판단: <span className="font-medium">{data.executionBridge.companyAction} · {data.executionBridge.companyActionLabel}</span></div>
                    <div className="mt-1 text-white/90">자산 실행: <span className="font-medium">{data.executionBridge.action}</span></div>
                    <div className="mt-1 text-white/90">정합성: <span className="font-medium">{data.executionBridge.alignment === 'aligned' ? '판단 일치' : data.executionBridge.alignment === 'mixed' ? '부분 일치' : '판단 충돌'}</span></div>
                    <div className="mt-2">{data.executionBridge.primaryReason}</div>
                    <div className="mt-2 text-white/80">{data.executionBridge.summary}</div>
                    {data.executionBridge.timingNotes?.length ? <div className="mt-2 text-white/70">{data.executionBridge.timingNotes.join(' · ')}</div> : null}
                  </div>
                </div>
              ) : null}

              <div className="mt-3 grid grid-cols-1 md:grid-cols-2 gap-3 text-xs text-[var(--muted)]">
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="mb-2 font-medium text-white">추가 매수 계획</div>
                  <div className="space-y-1">{data.positionSizing.addOnPlan.map((item) => <div key={item}>• {item}</div>)}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="mb-2 font-medium text-white">축소 / 중단 조건</div>
                  {data.positionSizing.reducePlan?.length ? (
                    <div className="space-y-1">{data.positionSizing.reducePlan.map((item) => <div key={item}>• {item}</div>)}</div>
                  ) : (
                    <>
                      <div>• {data.positionSizing.reduceTrigger}</div>
                      <div className="mt-1">• {data.positionSizing.stopScenario}</div>
                    </>
                  )}
                </div>
              </div>
              <div className="mt-3 flex flex-wrap gap-2 text-[11px] text-white/80">{data.positionSizing.reasons.map((item) => <span key={item} className="rounded-full border border-white/10 bg-white/5 px-2 py-1">{item}</span>)}</div>
            </div>
          ) : null}

          <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="text-lg font-semibold mb-3">내러티브 / 병목 / 자금 흐름</div>
            <div className="space-y-3 text-sm">
              {data.narrative ? (
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="font-medium text-white">내러티브</span>
                    <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-[10px] text-white/80">{data.narrative.title}</span>
                    <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-0.5 text-[10px] text-cyan-100">{data.narrative.stage} · {data.narrative.heatScore}</span>
                  </div>
                  <div className="mt-2 text-xs text-[var(--muted)] break-words">{data.narrative.riskNote}</div>
                  {data.narrative.drivers?.length ? <div className="mt-2 text-[11px] text-white/80">핵심 동인: {data.narrative.drivers.join(' · ')}</div> : null}
                </div>
              ) : null}
              {data.bottleneck ? (
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="font-medium text-white">병목 포지션</span>
                    <span className="rounded-full border border-amber-500/20 bg-amber-500/10 px-2 py-0.5 text-[10px] text-amber-100">{data.bottleneck.conviction} · {data.bottleneck.score}</span>
                  </div>
                  <div className="mt-2 text-xs text-[var(--muted)] break-words">{data.bottleneck.title} · {data.bottleneck.role}</div>
                  <div className="mt-2 flex flex-wrap gap-2 text-[11px] text-white/80">
                    <span>전환비용 {data.bottleneck.switchingCost}/10</span>
                    <span>가격결정력 {data.bottleneck.pricingPower}</span>
                    <span>리드타임 {data.bottleneck.leadTimeSignal}</span>
                    <span>백로그 {data.bottleneck.backlogSignal}</span>
                  </div>
                </div>
              ) : null}
              {data.capitalFlow ? (
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <div className="font-medium text-white">구조 자금 유입 프록시</div>
                    <span className="rounded-full border border-amber-500/20 bg-amber-500/10 px-2 py-0.5 text-[10px] text-amber-100">실제 순유입 아님</span>
                  </div>
                  <div className="mt-2 space-y-1 text-xs text-[var(--muted)] break-words">
                    {data.capitalFlow.etfInclusion ? <div>• {data.capitalFlow.etfInclusion}</div> : null}
                    {data.capitalFlow.capexLinkage ? <div>• {data.capitalFlow.capexLinkage}</div> : null}
                    {(data.capitalFlow.policyTailwinds || []).slice(0,2).map((item) => <div key={item}>• {item}</div>)}
                    {(data.capitalFlow.fundingDrivers || []).slice(0,3).map((item) => <div key={item}>• {item}</div>)}
                  </div>
                  {data.capitalFlow.evidenceNotice ? <div className="mt-2 rounded-lg border border-amber-500/10 bg-amber-500/5 p-2 text-[10px] text-amber-50/70">{data.capitalFlow.evidenceNotice}</div> : null}
                </div>
              ) : null}
            </div>
          </div>
        </section>

        <section className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {data.guidanceInsight ? (
            <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
              <div className="text-lg font-semibold mb-3">가이던스 해석</div>
              <div className="flex flex-wrap gap-2 text-xs">
                <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-1 text-cyan-100">stance {data.guidanceInsight.stance}</span>
                <span className="rounded-full border border-white/10 bg-white/5 px-2 py-1 text-white/80">{data.guidanceInsight.actionBias}</span>
              </div>
              <div className="mt-3 rounded-xl border border-white/10 bg-black/15 p-3 text-xs text-[var(--muted)] break-words">{data.guidanceInsight.summary}</div>
              <div className="mt-3 space-y-1 text-xs text-[var(--muted)]">
                {data.guidanceInsight.revenue ? <div>• Revenue: {data.guidanceInsight.revenue}{data.guidanceInsight.revenueValue ? ` → ${fmtGuidanceValue(data.guidanceInsight.revenueValue)}` : ''}</div> : null}
                {data.guidanceInsight.margin ? <div>• Margin: {data.guidanceInsight.margin}{data.guidanceInsight.marginValue ? ` → ${fmtGuidanceValue(data.guidanceInsight.marginValue)}` : ''}</div> : null}
                {data.guidanceInsight.capex ? <div>• CAPEX: {data.guidanceInsight.capex}{data.guidanceInsight.capexValue ? ` → ${fmtGuidanceValue(data.guidanceInsight.capexValue)}` : ''}</div> : null}
                {data.guidanceInsight.fcf ? <div>• FCF: {data.guidanceInsight.fcf}{data.guidanceInsight.fcfValue ? ` → ${fmtGuidanceValue(data.guidanceInsight.fcfValue)}` : ''}</div> : null}
                {data.guidanceInsight.evidence.map((item) => <div key={item}>• {item}</div>)}
              </div>
            </div>
          ) : null}

          {data.cashFlowQuality ? (
            <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
              <div className="text-lg font-semibold mb-3">현금흐름 질 / 회계 질</div>
              <div className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-5">
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">현금전환 점수</div>
                  <div className="mt-1 text-xl font-semibold">{data.cashFlowQuality.cashConversionScore}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">이익 질 점수</div>
                  <div className="mt-1 text-xl font-semibold">{data.cashFlowQuality.earningsQualityScore}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">채권 리스크</div>
                  <div className="mt-1 text-lg font-semibold">{data.cashFlowQuality.receivablesRisk}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">재고 리스크</div>
                  <div className="mt-1 text-lg font-semibold">{data.cashFlowQuality.inventoryRisk}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">유동성</div>
                  <div className="mt-1 text-lg font-semibold">{data.cashFlowQuality.liquidityLabel}</div>
                </div>
              </div>
              <div className="mt-3 rounded-xl border border-white/10 bg-black/15 p-3 text-xs text-[var(--muted)] break-words">
                {data.cashFlowQuality.summary}
                <div className="mt-2 text-white/80">Accrual risk: {data.cashFlowQuality.accrualRisk} · OCF/순이익 {data.cashFlowQuality.ocfToNetIncome === null ? '—' : `${data.cashFlowQuality.ocfToNetIncome.toFixed(2)}x`}</div>
              </div>
              <div className="mt-3 space-y-1 text-xs text-[var(--muted)]">
                {data.cashFlowQuality.reasons.map((item) => <div key={item}>• {item}</div>)}
              </div>
              {data.cashFlowQuality.evidenceBasis ? <div className="mt-2 text-[10px] text-white/40">근거: {data.cashFlowQuality.evidenceBasis}</div> : null}
            </div>
          ) : null}

          {data.multipleInsight ? (
            <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
              <div className="text-lg font-semibold mb-3">멀티플 해석</div>
              <div className="grid grid-cols-1 sm:grid-cols-5 gap-3 text-sm">
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">금리 민감도</div>
                  <div className="mt-1 font-semibold">{data.multipleInsight.rateSensitivity}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">내러티브 프리미엄</div>
                  <div className="mt-1 font-semibold">{data.multipleInsight.narrativePremium}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">peer 대비</div>
                  <div className="mt-1 font-semibold">{data.multipleInsight.valuationVsPeer}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">자체 밴드</div>
                  <div className="mt-1 font-semibold">{data.multipleInsight.valuationVsInternalRange}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">peer 평균 대비</div>
                  <div className="mt-1 font-semibold">{data.multipleInsight.premiumPctVsPeer === null ? '—' : `${data.multipleInsight.premiumPctVsPeer >= 0 ? '+' : ''}${data.multipleInsight.premiumPctVsPeer}%`}</div>
                  <div className="mt-1 text-[10px] text-[var(--muted)]">평균 {data.multipleInsight.peerAverageEvToSales === null ? '—' : `${data.multipleInsight.peerAverageEvToSales}x`}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">peer 중앙값 대비</div>
                  <div className="mt-1 font-semibold">{data.multipleInsight.premiumPctVsPeerMedian === null ? '—' : `${data.multipleInsight.premiumPctVsPeerMedian >= 0 ? '+' : ''}${data.multipleInsight.premiumPctVsPeerMedian}%`}</div>
                  <div className="mt-1 text-[10px] text-[var(--muted)]">중앙값 {data.multipleInsight.peerMedianEvToSales === null ? '—' : `${data.multipleInsight.peerMedianEvToSales}x`}</div>
                </div>
                <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                  <div className="text-xs text-[var(--muted)]">압축 위험</div>
                  <div className="mt-1 font-semibold">{data.multipleInsight.multipleCompressionRisk}</div>
                </div>
              </div>
              <div className="mt-3 rounded-xl border border-white/10 bg-black/15 p-3 text-xs text-[var(--muted)] break-words">{data.multipleInsight.summary}</div>
              <div className="mt-3 space-y-1 text-xs text-[var(--muted)]">
                {data.multipleInsight.reasons.map((item) => <div key={item}>• {item}</div>)}
              </div>
              {data.multipleInsight.evidenceBasis ? <div className="mt-2 text-[10px] text-white/40">근거: {data.multipleInsight.evidenceBasis}</div> : null}
            </div>
          ) : null}
        </section>

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
            <div>
              <div className="text-lg font-semibold">EPS 추정치 / 목표가 / 애널리스트 히스토리</div>
              <div className="mt-1 text-xs text-[var(--muted)]">EPS 추정치 변화율과 목표가 상승여력 변화를 분리해 봅니다. 둘은 같은 지표가 아닙니다.</div>
            </div>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 md:min-w-[560px]">
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">EPS 추정치 변화율</div>
                <div className="mt-3 text-[11px] leading-5 text-cyan-100">7D {fmtSigned(data.financials.estimateRevision7d, 1, "%")}<br />30D {fmtSigned(data.financials.estimateRevision30d, 1, "%")}<br />90D {fmtSigned(data.financials.estimateRevision90d, 1, "%")}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">목표가 상승여력 추이</div>
                <div className="mt-2 font-mono text-lg tracking-[0.2em] text-cyan-200">{upsideSpark}</div>
                <div className="mt-2 text-[11px] text-[var(--muted)]">30D 변화 {fmtSignedPctPoint(data.financials.targetUpsideChange30d)}</div>
              </div>
              <div className="rounded-xl border border-white/10 bg-black/15 p-3">
                <div className="text-xs text-[var(--muted)]">애널리스트 점수 추이</div>
                <div className="mt-2 font-mono text-lg tracking-[0.2em] text-emerald-200">{analystSpark}</div>
                <div className="mt-2 text-[11px] text-[var(--muted)]">30D {fmtSigned(data.financials.analystScoreRevision30d)}</div>
              </div>
            </div>
          </div>
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
              {data.financials.segmentGeoMixNote || "최근 10-K/10-Q/XBRL에서 자동 추출된 세그먼트/지역 믹스 요약이 아직 없습니다."}
            </div>
            {(data.financials.segmentMix?.length || data.financials.geoMix?.length) ? (
              <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-2">
                <div>
                  <div className="mb-2 text-xs font-semibold text-white/80">세그먼트 후보</div>
                  <div className="space-y-1">
                    {(data.financials.segmentMix || []).slice(0, 4).map((item) => (
                      <div key={item.label} className="text-xs text-[var(--muted)] break-words">• {item.label}{item.percentOfTotal !== null && item.percentOfTotal !== undefined ? ` : ${item.percentOfTotal}%` : item.value !== null ? ` : ${item.value}` : ''}</div>
                    ))}
                  </div>
                </div>
                <div>
                  <div className="mb-2 text-xs font-semibold text-white/80">지역 후보</div>
                  <div className="space-y-1">
                    {(data.financials.geoMix || []).slice(0, 4).map((item) => (
                      <div key={item.label} className="text-xs text-[var(--muted)] break-words">• {item.label}{item.percentOfTotal !== null && item.percentOfTotal !== undefined ? ` : ${item.percentOfTotal}%` : item.value !== null ? ` : ${item.value}` : ''}</div>
                    ))}
                  </div>
                </div>
              </div>
            ) : null}
          </div>

          <div className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
            <div className="text-lg font-semibold mb-3">IR / 발표자료</div>
            {data.irMaterials.length === 0 ? (
              <div className="text-sm text-[var(--muted)]">표시할 IR/발표자료 후보가 없습니다.</div>
            ) : (
              <div className="space-y-2">
                {data.irMaterials.map((item) => (
                  <SmartLink
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
                      {item.source ? <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-[10px] text-slate-300">{item.source}</span> : null}
                      {item.contentType ? <span className="rounded-full border border-white/10 bg-white/5 px-2 py-0.5 text-[10px] text-slate-300">{item.contentType}</span> : null}
                    </div>
                    {item.summary ? <div className="mt-2 text-xs text-[var(--muted)] break-words">{item.summary}</div> : null}
                  </SmartLink>
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
                {filing.guidanceSummary ? (
                  <div className="mt-2 space-y-1 text-xs text-[var(--muted)] break-words">
                    <div>가이던스: {filing.guidanceSummary.stance}
                      {filing.guidanceSummary.revenue ? ` · rev ${filing.guidanceSummary.revenue}` : ''}
                      {filing.guidanceSummary.margin ? ` · margin ${filing.guidanceSummary.margin}` : ''}
                      {filing.guidanceSummary.capex ? ` · capex ${filing.guidanceSummary.capex}` : ''}
                      {filing.guidanceSummary.fcf ? ` · fcf ${filing.guidanceSummary.fcf}` : ''}
                    </div>
                    {filing.guidanceSummary.revenueText ? <div>Revenue: {filing.guidanceSummary.revenueText}{filing.guidanceSummary.revenueValue ? ` → ${fmtGuidanceValue(filing.guidanceSummary.revenueValue)}` : ''}</div> : null}
                    {filing.guidanceSummary.marginText ? <div>Margin: {filing.guidanceSummary.marginText}{filing.guidanceSummary.marginValue ? ` → ${fmtGuidanceValue(filing.guidanceSummary.marginValue)}` : ''}</div> : null}
                    {filing.guidanceSummary.capexText ? <div>CAPEX: {filing.guidanceSummary.capexText}{filing.guidanceSummary.capexValue ? ` → ${fmtGuidanceValue(filing.guidanceSummary.capexValue)}` : ''}</div> : null}
                    {filing.guidanceSummary.fcfText ? <div>FCF: {filing.guidanceSummary.fcfText}{filing.guidanceSummary.fcfValue ? ` → ${fmtGuidanceValue(filing.guidanceSummary.fcfValue)}` : ''}</div> : null}
                  </div>
                ) : null}
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
                    <SmartLink href={filing.filingUrl} target="_blank" rel="noreferrer" className="text-xs text-cyan-300 cursor-pointer hover:text-cyan-200 underline">
                      SEC 원문 보기
                    </SmartLink>
                  </div>
                )}
              </div>
            ))}
          </div>
        </section>

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="flex items-center justify-between gap-3 mb-3">
            <div className="text-lg font-semibold">Peer comparison</div>
            <div className="text-xs text-[var(--muted)]">{data.peerGroup ? `${data.peerGroup} 기반 peer universe` : '산업군/테마 기반 peer universe'}</div>
          </div>
          {data.peers.length === 0 ? (
            <div className="text-sm text-[var(--muted)]">현재 등록된 peer가 없습니다.</div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
              {data.peers.map((peer) => (
                <SmartLink
                  key={peer.ticker}
                  href={`/company/${peer.ticker}`}
                  prefetch={false}
                  className="rounded-xl border border-white/10 bg-black/15 p-4 hover:bg-black/25"
                >
                  <div className="flex items-start justify-between gap-2 mb-2">
                    <div>
                      <div className="font-semibold">{peer.ticker}</div>
                      <div className="text-xs text-[var(--muted)] line-clamp-2">{peer.name}</div>
                    </div>
                    <div className={`rounded-full border px-2 py-0.5 text-xs ${scoreTone(peer.totalScore ?? 0, 'total')}`}>
                      {peer.totalScore ?? "—"}
                    </div>
                  </div>
                  <div className="text-xs text-cyan-200 mb-2">{peer.relation}</div>
                  <div className="mb-2 text-[10px] text-[var(--muted)]">
                    {peer.rank ? `Peer rank #${peer.rank}` : "Rank N/A"}
                    {peer.percentile ? ` · 상위 ${peer.percentile}%` : ""}
                  </div>
                  <div className="space-y-1 text-xs text-[var(--muted)]">
                    <div>매출 YoY: {fmtPct(peer.revenueGrowthYoY)}{peer.vsPeerAvgRevenueGrowth !== undefined && peer.vsPeerAvgRevenueGrowth !== null ? ` (${peer.vsPeerAvgRevenueGrowth >= 0 ? '+' : ''}${peer.vsPeerAvgRevenueGrowth.toFixed(1)} vs avg)` : ''}</div>
                    <div>영업이익률: {fmtPct(peer.operatingMargin)}{peer.vsPeerAvgOperatingMargin !== undefined && peer.vsPeerAvgOperatingMargin !== null ? ` (${peer.vsPeerAvgOperatingMargin >= 0 ? '+' : ''}${peer.vsPeerAvgOperatingMargin.toFixed(1)}%p vs avg)` : ''}</div>
                    <div>EV/Sales: {fmtNum(peer.evToSales)}x{peer.vsPeerAvgEvToSales !== undefined && peer.vsPeerAvgEvToSales !== null ? ` (${peer.vsPeerAvgEvToSales >= 0 ? '+' : ''}${peer.vsPeerAvgEvToSales.toFixed(2)}x vs avg)` : ''}</div>
                  </div>
                </SmartLink>
              ))}
            </div>
          )}
        </section>

        <DynamicPeerPanel ticker={ticker} />
      </div>
    </main>
  );
}
