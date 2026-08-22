import { computeBottleneckCandidateScore } from '../domain/bottleneck/bottleneck-score';
import { BottleneckCandidateDefinition } from '../types/bottleneck';
import { CompanyResearchResponse } from '../types/fundamentals';

function makeResearch(summary: string): CompanyResearchResponse {
  return {
    profile: { ticker: 'TEST', cik: '1', name: 'Test Co' },
    quote: { symbol: 'TEST', price: 100, date: '2026-06-03' },
    financials: {
      ticker: 'TEST', cik: '1', asOf: '2026-06-03', revenueTtm: 100, operatingIncomeTtm: 20, netIncomeTtm: 15, freeCashFlowTtm: 18,
      cash: 10, debt: 5, currentAssets: 40, currentLiabilities: 20, receivables: 12, inventory: 6, capexTtm: 6, operatingCashFlowTtm: 24, sharesOutstanding: 10, marketCap: 1000, enterpriseValue: 995,
      revenueGrowthYoY: 24, operatingMargin: 22, operatingMarginTrend: 2, freeCashFlowMargin: 18, netDebtToRevenue: -5,
      evToSales: 5, evToFcf: 20, shareDilutionYoY: 1, stockCompToRevenue: 2, roe: 18, currentRatio: 2, receivablesToRevenue: 0.12, inventoryToRevenue: 0.06,
    },
    score: {
      ticker: 'TEST', totalScore: 78,
      growth: { value: 80, reasons: ['성장'] },
      quality: { value: 76, reasons: ['quality'] },
      valuation: { value: 60, reasons: ['valuation'] },
      balanceSheet: { value: 80, reasons: ['balance'] },
      reasons: ['overall'],
    },
    buyScore: { appealScore: 76, crowdingScore: 32, buyScore: 70, label: '매수 우호', reasons: ['기초체력 우수'] },
    filings: [{ accessionNumber: '1', form: '8-K', filingDate: '2026-06-03', summary, guidanceSignals: [], guidanceSummary: null }],
    irMaterials: [{ title: 'deck', form: '8-K', filingDate: '2026-06-03', url: 'u', type: 'presentation', summary }],
    highlights: ['매출 성장 24.0%', '영업이익률 22.0%'],
    peerGroup: 'AI_SEMIS',
    peers: [],
  };
}

describe('bottleneck score', () => {
  it('captures repeated keyword matches and conviction', () => {
    const candidate: BottleneckCandidateDefinition = {
      ticker: 'TEST',
      role: 'EDA 소프트웨어',
      theme: 'EDA',
      tags: ['eda', 'qualification'],
      priors: { concentration: 9, supplyTightness: 7, capexLinkage: 8, switchingCost: 9 },
    };
    const research = makeResearch('Supply constrained backlog improved with design win and installed base expansion. Long lead times and pricing power remain strong.');
    const result = computeBottleneckCandidateScore(candidate, research);
    expect(result.textMatches.length).toBeGreaterThan(0);
    expect(result.componentScores.switchingCost).toBeGreaterThanOrEqual(9);
    expect(['WATCH', 'STRONG', 'CORE']).toContain(result.conviction);
    expect(result.score).toBeGreaterThan(50);
  });
});
