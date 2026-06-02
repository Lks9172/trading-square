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

export interface CompanyIrMaterial {
  title: string;
  form: string;
  filingDate: string;
  url: string;
  type: 'presentation' | 'earnings-release' | 'annual-report' | 'quarterly-report' | 'other';
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
  segmentGeoMixNote?: string | null;
  estimateUpsidePct?: number | null;
  estimateRevision30d?: number | null;
  analystScore?: number | null;
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
}

export interface CompanyResearchResponse {
  profile: CompanyProfile;
  quote: CompanyQuote;
  financials: CompanyFinancialSnapshot;
  score: CompanyScore;
  filings: CompanyFilingEvent[];
  irMaterials: CompanyIrMaterial[];
  highlights: string[];
  peers: CompanyPeerSummary[];
}
