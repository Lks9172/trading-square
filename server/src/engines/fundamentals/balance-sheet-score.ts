import { CompanyFinancialSnapshot, CompanyScoreBreakdown } from '../../types/fundamentals';
import { averageScore, clampScore } from './scoring-utils';

export function computeBalanceSheetScore(financials: CompanyFinancialSnapshot): CompanyScoreBreakdown {
  const scores: number[] = [];
  const reasons: string[] = [];

  if (financials.netDebtToRevenue !== null) {
    if (financials.netDebtToRevenue <= 0) { scores.push(90); reasons.push('순현금 또는 무차입 구조'); }
    else if (financials.netDebtToRevenue <= 0.5) { scores.push(75); reasons.push(`순부채/매출 ${financials.netDebtToRevenue.toFixed(2)}x 관리 가능`); }
    else if (financials.netDebtToRevenue <= 1) { scores.push(50); reasons.push(`순부채/매출 ${financials.netDebtToRevenue.toFixed(2)}x 중립`); }
    else { scores.push(25); reasons.push(`순부채/매출 ${financials.netDebtToRevenue.toFixed(2)}x 부담`); }
  }

  if (financials.cash !== null && financials.debt !== null) {
    if (financials.cash >= financials.debt) scores.push(85);
    else if (financials.cash >= financials.debt * 0.5) scores.push(60);
    else scores.push(30);
  }

  if (financials.shareDilutionYoY !== null) {
    if (financials.shareDilutionYoY <= 0) { scores.push(82); reasons.push(`주식수 YoY ${financials.shareDilutionYoY.toFixed(1)}%로 희석 제한적`); }
    else if (financials.shareDilutionYoY <= 2) { scores.push(65); reasons.push(`주식수 YoY +${financials.shareDilutionYoY.toFixed(1)}% 관리 가능`); }
    else { scores.push(30); reasons.push(`주식수 YoY +${financials.shareDilutionYoY.toFixed(1)}% 희석 부담`); }
  }

  if (financials.stockCompToRevenue !== null) {
    if (financials.stockCompToRevenue <= 3) scores.push(80);
    else if (financials.stockCompToRevenue <= 8) scores.push(60);
    else {
      scores.push(30);
      reasons.push(`주식보상/매출 ${financials.stockCompToRevenue.toFixed(1)}% 부담`);
    }
  }

  return { value: clampScore(averageScore(scores)), reasons };
}
