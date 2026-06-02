import { CompanyFinancialSnapshot, CompanyScoreBreakdown } from '../../types/fundamentals';
import { averageScore, clampScore } from './scoring-utils';

export function computeValuationScore(financials: CompanyFinancialSnapshot): CompanyScoreBreakdown {
  const scores: number[] = [];
  const reasons: string[] = [];

  if (financials.evToSales !== null) {
    if (financials.evToSales <= 3) { scores.push(85); reasons.push(`EV/Sales ${financials.evToSales.toFixed(1)}x 저평가 구간`); }
    else if (financials.evToSales <= 6) { scores.push(70); reasons.push(`EV/Sales ${financials.evToSales.toFixed(1)}x 수용 가능`); }
    else if (financials.evToSales <= 10) { scores.push(45); reasons.push(`EV/Sales ${financials.evToSales.toFixed(1)}x 고평가 부담`); }
    else { scores.push(20); reasons.push(`EV/Sales ${financials.evToSales.toFixed(1)}x 과열 가능성`); }
  }

  if (financials.evToFcf !== null) {
    if (financials.evToFcf <= 20) { scores.push(85); reasons.push(`EV/FCF ${financials.evToFcf.toFixed(1)}x 매력적`); }
    else if (financials.evToFcf <= 35) { scores.push(65); reasons.push(`EV/FCF ${financials.evToFcf.toFixed(1)}x 보통`); }
    else if (financials.evToFcf <= 50) { scores.push(40); reasons.push(`EV/FCF ${financials.evToFcf.toFixed(1)}x 부담`); }
    else { scores.push(20); reasons.push(`EV/FCF ${financials.evToFcf.toFixed(1)}x 고평가`); }
  }

  return { value: clampScore(averageScore(scores)), reasons };
}

