import { getBottleneckThemeById, getBottleneckThemes } from '../domain/bottleneck/candidate-map';
import { computeBottleneckCandidateScore } from '../domain/bottleneck/bottleneck-score';
import { BottleneckThemeResponse } from '../types/bottleneck';
import { buildCompanyResearchLite } from './company-research';
import { childLogger, serializeError } from './logger';

const log = childLogger({ module: 'service.bottleneck-research' });

export async function buildBottleneckTheme(themeId: string): Promise<BottleneckThemeResponse> {
  const theme = getBottleneckThemeById(themeId);
  if (!theme) {
    throw new Error('bottleneck theme not found');
  }

  const items = await Promise.all(theme.tickers.map(async (candidate) => {
    try {
      const research = await buildCompanyResearchLite(candidate.ticker);
      return computeBottleneckCandidateScore(candidate, research);
    } catch (error) {
      log.warn({ ticker: candidate.ticker, error: serializeError(error) }, 'bottleneck candidate scoring failed');
      return {
        ticker: candidate.ticker,
        company: candidate.company ?? candidate.ticker,
        role: candidate.role,
        theme: candidate.theme,
        score: 0,
        conviction: 'WATCH' as const,
        componentScores: {
          textSignal: 0,
          quality: 0,
          concentration: candidate.priors?.concentration ?? 5,
          supplyTightness: candidate.priors?.supplyTightness ?? 5,
          capexLinkage: candidate.priors?.capexLinkage ?? 5,
          switchingCost: candidate.priors?.switchingCost ?? 5,
        },
        textMatches: [],
        reasons: ['데이터 로드 실패'],
        metrics: {
          revenueGrowthYoY: null,
          operatingMargin: null,
          evToSales: null,
          totalScore: null,
        },
      };
    }
  }));

  const sorted = items.sort((a, b) => b.score - a.score);
  const coreCount = sorted.filter((item) => item.conviction === 'CORE').length;
  const strongCount = sorted.filter((item) => item.conviction === 'STRONG').length;
  const averageScore = sorted.length ? Math.round((sorted.reduce((sum, item) => sum + item.score, 0) / sorted.length) * 10) / 10 : 0;

  return {
    theme,
    generatedAt: new Date().toISOString(),
    summary: {
      averageScore,
      coreCount,
      strongCount,
      topTickers: sorted.slice(0, 3).map((item) => item.ticker),
    },
    items: sorted,
  };
}

export function listBottleneckThemes() {
  return getBottleneckThemes();
}
