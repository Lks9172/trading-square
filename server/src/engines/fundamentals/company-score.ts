import { CompanyFinancialSnapshot, CompanyScore } from '../../types/fundamentals';
import { computeBalanceSheetScore } from './balance-sheet-score';
import { computeGrowthScore } from './growth-score';
import { computeQualityScore } from './quality-score';
import { averageScore } from './scoring-utils';
import { computeValuationScore } from './valuation-score';

export function computeCompanyScore(financials: CompanyFinancialSnapshot): CompanyScore {
  const growth = computeGrowthScore(financials);
  const quality = computeQualityScore(financials);
  const valuation = computeValuationScore(financials);
  const balanceSheet = computeBalanceSheetScore(financials);
  const totalScore = averageScore([growth.value, quality.value, valuation.value, balanceSheet.value]);

  return {
    ticker: financials.ticker,
    totalScore,
    growth,
    quality,
    valuation,
    balanceSheet,
    reasons: [
      ...growth.reasons.slice(0, 1),
      ...quality.reasons.slice(0, 1),
      ...valuation.reasons.slice(0, 1),
      ...balanceSheet.reasons.slice(0, 1),
    ].filter(Boolean),
  };
}

