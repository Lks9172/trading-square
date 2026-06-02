import { CompanyFinancialSnapshot, CompanyScoreBreakdown } from '../../types/fundamentals';
import { averageScore, clampScore } from './scoring-utils';

export function computeQualityScore(financials: CompanyFinancialSnapshot): CompanyScoreBreakdown {
  const scores: number[] = [];
  const reasons: string[] = [];

  if (financials.operatingMargin !== null) {
    if (financials.operatingMargin >= 25) { scores.push(90); reasons.push(`영업이익률 ${financials.operatingMargin.toFixed(1)}% 우수`); }
    else if (financials.operatingMargin >= 15) { scores.push(75); reasons.push(`영업이익률 ${financials.operatingMargin.toFixed(1)}% 양호`); }
    else if (financials.operatingMargin >= 5) { scores.push(55); reasons.push(`영업이익률 ${financials.operatingMargin.toFixed(1)}% 보통`); }
    else { scores.push(25); reasons.push(`영업이익률 ${financials.operatingMargin.toFixed(1)}% 낮음`); }
  }

  if (financials.freeCashFlowMargin !== null) {
    if (financials.freeCashFlowMargin >= 20) { scores.push(88); reasons.push(`FCF 마진 ${financials.freeCashFlowMargin.toFixed(1)}% 우수`); }
    else if (financials.freeCashFlowMargin >= 10) { scores.push(72); reasons.push(`FCF 마진 ${financials.freeCashFlowMargin.toFixed(1)}% 양호`); }
    else if (financials.freeCashFlowMargin >= 0) { scores.push(55); reasons.push(`FCF 마진 ${financials.freeCashFlowMargin.toFixed(1)}% 보통`); }
    else { scores.push(20); reasons.push(`FCF 마진 ${financials.freeCashFlowMargin.toFixed(1)}% 음수`); }
  }

  if (financials.roe !== null) {
    if (financials.roe >= 20) { scores.push(88); reasons.push(`ROE ${financials.roe.toFixed(1)}% 우수`); }
    else if (financials.roe >= 12) { scores.push(72); reasons.push(`ROE ${financials.roe.toFixed(1)}% 양호`); }
    else if (financials.roe >= 5) { scores.push(52); reasons.push(`ROE ${financials.roe.toFixed(1)}% 보통`); }
    else { scores.push(25); reasons.push(`ROE ${financials.roe.toFixed(1)}% 낮음`); }
  }

  if (financials.operatingMarginTrend !== null) {
    if (financials.operatingMarginTrend >= 3) { scores.push(82); reasons.push(`마진 추세 +${financials.operatingMarginTrend.toFixed(1)}%p 개선`); }
    else if (financials.operatingMarginTrend <= -3) { scores.push(28); reasons.push(`마진 추세 ${financials.operatingMarginTrend.toFixed(1)}%p 악화`); }
  }

  return { value: clampScore(averageScore(scores)), reasons };
}
