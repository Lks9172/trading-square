export interface BottleneckCandidateDefinition {
  ticker: string;
  company?: string;
  role: string;
  theme: string;
  peerGroup?: string;
  tags?: string[];
  priors?: {
    concentration?: number;
    supplyTightness?: number;
    capexLinkage?: number;
    switchingCost?: number;
  };
}

export interface BottleneckThemeDefinition {
  id: string;
  title: string;
  description: string;
  tickers: BottleneckCandidateDefinition[];
}

export interface BottleneckComponentScores {
  textSignal: number;
  quality: number;
  concentration: number;
  supplyTightness: number;
  capexLinkage: number;
  switchingCost: number;
}

export interface BottleneckTextMatch {
  label: string;
  count: number;
  score: number;
  reason: string;
  excerpts?: string[];
}

export interface BottleneckCandidateScore {
  ticker: string;
  company: string;
  role: string;
  theme: string;
  score: number;
  conviction: 'WATCH' | 'STRONG' | 'CORE';
  componentScores: BottleneckComponentScores;
  textMatches: BottleneckTextMatch[];
  reasons: string[];
  metrics: {
    revenueGrowthYoY: number | null;
    operatingMargin: number | null;
    evToSales: number | null;
    totalScore: number | null;
  };
}

export interface BottleneckThemeResponse {
  theme: BottleneckThemeDefinition;
  generatedAt: string;
  summary?: {
    averageScore: number;
    coreCount: number;
    strongCount: number;
    topTickers: string[];
  };
  items: BottleneckCandidateScore[];
}
