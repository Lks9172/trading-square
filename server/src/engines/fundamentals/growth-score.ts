import { CompanyFinancialSnapshot, CompanyScoreBreakdown } from '../../types/fundamentals';
import { averageScore, clampScore } from './scoring-utils';

export function computeGrowthScore(financials: CompanyFinancialSnapshot): CompanyScoreBreakdown {
  const reasons: string[] = [];
  const scores: number[] = [];
  const growth = financials.revenueGrowthYoY;
  if (growth !== null) {
    if (growth >= 20) { scores.push(90); reasons.push(`매출 YoY ${growth.toFixed(1)}% 고성장`); }
    else if (growth >= 10) { scores.push(75); reasons.push(`매출 YoY ${growth.toFixed(1)}% 양호`); }
    else if (growth >= 0) { scores.push(55); reasons.push(`매출 YoY ${growth.toFixed(1)}% 완만 성장`); }
    else { scores.push(25); reasons.push(`매출 YoY ${growth.toFixed(1)}% 역성장`); }
  }
  return { value: clampScore(averageScore(scores)), reasons };
}

