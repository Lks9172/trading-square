import type { BottomSignalChartMarker, BottomSignalChartPoint, BottomSignalMetric, BottomSignalState, DeepBottomSignal } from './indicators';

export interface CompanyProfile {
  ticker: string;
  cik: string;
  name: string;
  exchange?: string | null;
  sic?: string | null;
}

export interface CompanyQuote {
  symbol: string;
  price: number | null;
  date: string | null;
}

export interface CompanyHistoryPoint {
  date: string;
  value: number | null;
}


export interface CompanyGuidanceMetricValue {
  raw: string;
  min: number | null;
  max: number | null;
  unit: 'usd' | 'percent' | 'bps' | 'other' | null;
}

export interface CompanyGuidanceSummary {
  stance: 'raised' | 'lowered' | 'affirmed' | 'mixed' | 'unclear';
  revenue?: 'raised' | 'lowered' | 'affirmed' | 'mentioned' | null;
  margin?: 'raised' | 'lowered' | 'affirmed' | 'mentioned' | null;
  capex?: 'raised' | 'lowered' | 'affirmed' | 'mentioned' | null;
  fcf?: 'raised' | 'lowered' | 'affirmed' | 'mentioned' | null;
  revenueText?: string | null;
  marginText?: string | null;
  capexText?: string | null;
  fcfText?: string | null;
  revenueValue?: CompanyGuidanceMetricValue | null;
  marginValue?: CompanyGuidanceMetricValue | null;
  capexValue?: CompanyGuidanceMetricValue | null;
  fcfValue?: CompanyGuidanceMetricValue | null;
  evidence: string[];
}

export interface CompanySegmentMixEntry {
  label: string;
  value: number | null;
  unit?: string | null;
  percentOfTotal?: number | null;
}

export interface CompanyIrMaterial {
  title: string;
  form: string;
  filingDate: string;
  url: string;
  type: 'presentation' | 'earnings-release' | 'annual-report' | 'quarterly-report' | 'other';
  source?: 'primary' | 'exhibit' | 'index';
  contentType?: 'pdf' | 'html' | 'txt' | 'other';
  summary?: string | null;
}

export interface CompanyFilingEvent {
  accessionNumber: string;
  form: string;
  filingDate: string;
  primaryDocument?: string | null;
  primaryDocDescription?: string | null;
  isEarningsRelated?: boolean;
  filingUrl?: string | null;
  summary?: string | null;
  guidanceSignals?: string[];
  guidanceSummary?: CompanyGuidanceSummary | null;
}

export interface CompanyFinancialSnapshot {
  ticker: string;
  cik: string;
  asOf: string;
  revenueTtm: number | null;
  operatingIncomeTtm: number | null;
  netIncomeTtm: number | null;
  freeCashFlowTtm: number | null;
  cash: number | null;
  debt: number | null;
  currentAssets: number | null;
  currentLiabilities: number | null;
  receivables: number | null;
  inventory: number | null;
  capexTtm: number | null;
  operatingCashFlowTtm: number | null;
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
  currentRatio: number | null;
  receivablesToRevenue: number | null;
  inventoryToRevenue: number | null;
  segmentGeoMixNote?: string | null;
  segmentMix?: CompanySegmentMixEntry[];
  geoMix?: CompanySegmentMixEntry[];
  estimateUpsidePct?: number | null;
  estimateRevision7d?: number | null;
  estimateRevision30d?: number | null;
  estimateRevision90d?: number | null;
  analystScore?: number | null;
  analystScoreRevision7d?: number | null;
  analystScoreRevision30d?: number | null;
  analystScoreRevision90d?: number | null;
  estimateUpsideHistory?: CompanyHistoryPoint[];
  analystScoreHistory?: CompanyHistoryPoint[];
}

export interface CompanyScoreBreakdown {
  value: number;
  reasons: string[];
}

export interface CompanyScore {
  ticker: string;
  totalScore: number;
  growth: CompanyScoreBreakdown;
  quality: CompanyScoreBreakdown;
  valuation: CompanyScoreBreakdown;
  balanceSheet: CompanyScoreBreakdown;
  reasons: string[];
}

export interface CompanyBuyScore {
  appealScore: number;
  crowdingScore: number;
  buyScore: number;
  label: '매수 우호' | '선별 접근' | '추격 주의';
  reasons: string[];
}

export interface CompanyPeerSummary {
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
}


export interface CompanyBottleneckInsight {
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
}

export interface CompanyNarrativeInsight {
  themeId: string;
  title: string;
  stage: 'EARLY' | 'MID' | 'OVERHEATED';
  heatScore: number;
  trend?: 'HEATING' | 'COOLING' | 'STABLE';
  riskNote: string;
  drivers: string[];
}

export interface CompanyCapitalFlowInsight {
  etfInclusion: string | null;
  policyTailwinds: string[];
  capexLinkage: string | null;
  fundingDrivers: string[];
}

export interface CompanyVerdictBlock {
  label: '우호' | '양호' | '중립' | '주의';
  score: number;
  summary: string;
}

export interface CompanyVerdicts {
  businessQuality: CompanyVerdictBlock;
  valuation: CompanyVerdictBlock;
  timing: CompanyVerdictBlock;
  finalAction: CompanyVerdictBlock;
  oneLiners?: {
    business: string;
    valuation: string;
    timing: string;
    action: string;
  };
}


export interface CompanyCashFlowQualityInsight {
  cashConversionScore: number;
  earningsQualityScore: number;
  accrualRisk: '낮음' | '보통' | '높음';
  ocfToNetIncome: number | null;
  receivablesRisk: '낮음' | '보통' | '높음';
  inventoryRisk: '낮음' | '보통' | '높음';
  liquidityLabel: '양호' | '보통' | '주의';
  summary: string;
  reasons: string[];
}

export interface CompanyMultipleInsight {
  rateSensitivity: '낮음' | '보통' | '높음';
  narrativePremium: '낮음' | '보통' | '높음';
  valuationVsPeer: '할인' | '중립' | '프리미엄' | '판단불가';
  multipleCompressionRisk: '낮음' | '보통' | '높음';
  valuationVsInternalRange: '저평가권' | '중립권' | '고평가권' | '판단불가';
  peerAverageEvToSales: number | null;
  peerMedianEvToSales: number | null;
  premiumPctVsPeer: number | null;
  premiumPctVsPeerMedian: number | null;
  summary: string;
  reasons: string[];
}

export interface CompanyGuidanceInsight {
  stance: 'raised' | 'lowered' | 'affirmed' | 'mixed' | 'unclear';
  actionBias: '공격 가능' | '선별 접근' | '보수 접근';
  summary: string;
  revenue?: string | null;
  margin?: string | null;
  capex?: string | null;
  fcf?: string | null;
  revenueValue?: CompanyGuidanceMetricValue | null;
  marginValue?: CompanyGuidanceMetricValue | null;
  capexValue?: CompanyGuidanceMetricValue | null;
  fcfValue?: CompanyGuidanceMetricValue | null;
  evidence: string[];
}

export interface CompanyTimeframeViewBlock {
  stance: 'STRONG BUY' | 'BUY' | 'HOLD' | 'REDUCE' | 'SELL';
  summary: string;
}

export interface CompanyTimeframeView {
  shortTerm: CompanyTimeframeViewBlock;
  swingTerm: CompanyTimeframeViewBlock;
  longTerm: CompanyTimeframeViewBlock;
}

export interface CompanyCorrectionAssessment {
  correctionScore: number;
  trendBreakRiskScore: number;
  verdict: '조정 우세' | '혼합' | '추세전환 경계';
  actionBias: '눌림 매수 가능' | '확인 후 접근' | '방어 우선';
  summary: string;
  reasons: string[];
  risks: string[];
}

export interface CompanyThesisMonitor {
  status: '유지' | '일부 약화' | '훼손 경계';
  summary: string;
  reasons: string[];
  breakConditions: string[];
}

export interface CompanyReversalConfirmation {
  status: 'OFF' | 'EARLY' | 'ON' | 'STRONG';
  score: number;
  signalDate: string | null;
  summary: string;
  reasons: string[];
  cautions: string[];
}

export interface CompanySectorContext {
  sectorId: string;
  label: string;
  sectorKey: string;
  classification: string;
  buyScore: number | null;
  qualityScore: number | null;
  appealScore: number | null;
  crowdingScore: number | null;
  stance: 'favored' | 'avoided' | 'neutral';
  thesis: string;
  relatedThemes: Array<{ id: string; theme: string }>;
}

export interface CompanyExecutionBridge {
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
}

export interface CompanyPositionSizingPlan {
  action: 'STRONG BUY' | 'BUY' | 'HOLD' | 'REDUCE' | 'SELL';
  targetPositionPct: number;
  initialEntryPctOfTarget: number;
  reservePctOfTarget: number;
  addOnPlan: string[];
  reduceTrigger: string;
  stopScenario: string;
  summary: string;
  reasons: string[];
}

export interface CompanyBottomSignal {
  score: number;
  state: BottomSignalState;
  actionBias: '대기' | '관찰 매수' | '분할 매수' | '확인 우선';
  summary: string;
  earningsBottomScore?: number;
  priceBottomScore?: number;
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

export interface CompanyResearchResponse {
  profile: CompanyProfile;
  quote: CompanyQuote;
  financials: CompanyFinancialSnapshot;
  score: CompanyScore;
  buyScore: CompanyBuyScore;
  filings: CompanyFilingEvent[];
  irMaterials: CompanyIrMaterial[];
  highlights: string[];
  peerGroup?: string | null;
  bottleneck?: CompanyBottleneckInsight | null;
  narrative?: CompanyNarrativeInsight | null;
  capitalFlow?: CompanyCapitalFlowInsight | null;
  cashFlowQuality?: CompanyCashFlowQualityInsight | null;
  multipleInsight?: CompanyMultipleInsight | null;
  guidanceInsight?: CompanyGuidanceInsight | null;
  timeframeView?: CompanyTimeframeView | null;
  correctionAssessment?: CompanyCorrectionAssessment | null;
  thesisMonitor?: CompanyThesisMonitor | null;
  reversalConfirmation?: CompanyReversalConfirmation | null;
  sectorContext?: CompanySectorContext | null;
  verdicts?: CompanyVerdicts | null;
  bottomSignal?: CompanyBottomSignal | null;
  positionSizing?: CompanyPositionSizingPlan | null;
  executionBridge?: CompanyExecutionBridge | null;
  peers: CompanyPeerSummary[];
}
