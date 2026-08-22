import type { BottomSignalChartMarker, BottomSignalChartPoint, BottomSignalMetric, BottomSignalState, DeepBottomSignal } from './indicators';

export interface CryptoAssetDefinition {
  symbol: string;
  yahooSymbol: string;
  coingeckoId: string;
  llamaChainSlug: string;
  name: string;
  category: string;
  narrativeTheme: string;
  linkedAsset: 'NASDAQ' | 'GOLD' | 'CASH' | 'EMERGING' | 'COPPER';
  foundationalScore: number;
  networkScore: number;
  tokenomicsScore: number;
  adoptionScore: number;
  macroSensitivity: string[];
  strengths: string[];
  risks: string[];
}

export interface CryptoMacroView {
  liquidityScore: number | null;
  dollarScore: number | null;
  riskOnScore: number | null;
  stance: '우호' | '중립' | '주의';
  summary: string;
  drivers: string[];
}

export interface CryptoNarrativeView {
  theme: string;
  stage: 'EARLY' | 'MID' | 'OVERHEATED';
  heatScore: number;
  summary: string;
}

export interface CryptoBottomUpView {
  networkScore: number;
  tokenomicsScore: number;
  adoptionScore: number;
  summary: string;
  strengths: string[];
  risks: string[];
}

export interface CryptoFlowView {
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
}

export interface CryptoTrendPoint {
  date: string;
  value: number;
}

export interface CryptoMarketStats {
  asOf: string | null;
  price: number | null;
  return7d: number | null;
  return30d: number | null;
  return90d: number | null;
  volumeTrend30d: number | null;
  volatility30d: number | null;
  distanceFrom52wHigh: number | null;
  distanceFrom52wLow: number | null;
}

export interface CryptoBuyScore {
  appealScore: number;
  crowdingScore: number;
  buyScore: number;
  action: 'STRONG BUY' | 'BUY' | 'HOLD' | 'REDUCE' | 'SELL';
  actionLabel: string;
  reasons: string[];
}

export interface CryptoExecutionBridge {
  asset: string;
  action: string;
  actionLabel: string;
  targetAllocationPct: number;
  alignment: 'aligned' | 'mixed' | 'conflicted';
  entryMode: '현물 코어' | '분할 현물' | '관찰 대기' | '축소/익절';
  riskBox: string;
  summary: string;
  timingNotes: string[];
}

export interface CryptoPositionSizingPlan {
  targetPositionPct: number;
  initialEntryPctOfTarget: number;
  reservePctOfTarget: number;
  summary: string;
}

export interface CryptoMoatView {
  moatType: string;
  moatScore: number;
  summary: string;
  reasons: string[];
}

export interface CryptoSupplyPressureView {
  unlockRisk: '낮음' | '보통' | '높음';
  dilutionRisk: '낮음' | '보통' | '높음';
  floatScore: number;
  fdvPremiumPct: number | null;
  circulatingRatioPct: number | null;
  summary: string;
  reasons: string[];
}

export interface CryptoOnchainView {
  tvlUsd: number | null;
  tvlTrend30dPct: number | null;
  fees30dAvgUsd: number | null;
  feesTrend30dPct: number | null;
  developerScore: number | null;
  communityScore: number | null;
  activityScore: number;
  summary: string;
  reasons: string[];
}

export interface CryptoVerdictView {
  quality: '강함' | '양호' | '보통' | '약함';
  timing: '우호' | '중립' | '주의';
  valuationProxy: '부담 낮음' | '중립' | '과열 부담';
  finalAction: 'STRONG BUY' | 'BUY' | 'HOLD' | 'REDUCE' | 'SELL';
  oneLiners: {
    quality: string;
    timing: string;
    action: string;
  };
}

export interface CryptoScenarioView {
  bullCase: string;
  baseCase: string;
  bearCase: string;
}

export interface CryptoBottomSignal {
  score: number;
  state: BottomSignalState;
  actionBias: '대기' | '관찰 매수' | '분할 매수' | '확인 우선';
  summary: string;
  volumeConfirmationScore?: number;
  failureRiskScore?: number;
  metrics: BottomSignalMetric[];
  chart: {
    points: BottomSignalChartPoint[];
    markers: BottomSignalChartMarker[];
  };
  confirmedBottom?: DeepBottomSignal;
  reasons: string[];
  cautions: string[];
  failureSignals?: string[];
}

export interface CryptoMarketRegimeView {
  regime: 'RISK_ON' | 'SELECTIVE' | 'DEFENSIVE' | 'STAY_OUT';
  action: '공격 가능' | '선별 접근' | '현금 우선' | '관망';
  altRegime: 'BTC 중심장' | '혼조장' | '알트 확산장';
  targetTotalExposurePct: number;
  summary: string;
  reasons: string[];
}

export interface CryptoResearchResponse {
  profile: CryptoAssetDefinition;
  market: CryptoMarketStats;
  macro: CryptoMacroView;
  narrative: CryptoNarrativeView;
  bottomUp: CryptoBottomUpView;
  moat: CryptoMoatView;
  supplyPressure: CryptoSupplyPressureView;
  onchain: CryptoOnchainView;
  flows: CryptoFlowView;
  trendCharts: {
    btcDominanceProxy30d: CryptoTrendPoint[];
    stablecoinMcap30d: CryptoTrendPoint[];
    etfNetFlow30d: CryptoTrendPoint[];
    altSeasonProxy30d: CryptoTrendPoint[];
    exchangeNetflowProxy30d: CryptoTrendPoint[];
  };
  buyScore: CryptoBuyScore;
  positionSizing: CryptoPositionSizingPlan;
  verdicts: CryptoVerdictView;
  bottomSignal: CryptoBottomSignal;
  scenarios: CryptoScenarioView;
  executionBridge: CryptoExecutionBridge | null;
}
