import { SecCompanyFactsResponse, SecFactPoint } from '../../collectors/sec/companyfacts';
import { CompanyFinancialSnapshot } from '../../types/fundamentals';

function factSeries(
  facts: SecCompanyFactsResponse,
  taxonomy: 'us-gaap' | 'dei',
  keys: string[],
): SecFactPoint[] {
  const bucket = facts.facts?.[taxonomy];
  for (const key of keys) {
    const units = bucket?.[key]?.units;
    if (!units) continue;
    for (const unit of Object.values(units)) {
      if (Array.isArray(unit) && unit.length > 0) return unit.filter((item) => typeof item.val === 'number');
    }
  }
  return [];
}

function latestInstant(points: SecFactPoint[]): number | null {
  const filtered = points
    .filter((item) => item.val !== undefined && item.end)
    .sort((a, b) => String(b.end).localeCompare(String(a.end)));
  return filtered[0]?.val ?? null;
}

function latestAnnual(points: SecFactPoint[]): { val: number; previousVal: number | null; end: string } | null {
  const annual = points
    .filter((item) => item.val !== undefined && item.form === '10-K' && item.fp === 'FY' && item.end)
    .sort((a, b) => String(b.end).localeCompare(String(a.end)));
  if (!annual[0]?.val || !annual[0]?.end) return null;
  return {
    val: annual[0].val,
    previousVal: annual[1]?.val ?? null,
    end: annual[0].end,
  };
}

function latestAnnualSeries(points: SecFactPoint[]): SecFactPoint[] {
  return points
    .filter((item) => item.val !== undefined && item.form === '10-K' && item.fp === 'FY' && item.end)
    .sort((a, b) => String(b.end).localeCompare(String(a.end)))
    .slice(0, 2);
}

function latestQuarterlies(points: SecFactPoint[]): SecFactPoint[] {
  return points
    .filter((item) => item.val !== undefined && item.form === '10-Q' && item.end)
    .sort((a, b) => String(b.end).localeCompare(String(a.end)))
    .slice(0, 4);
}

function sumTtm(points: SecFactPoint[]): number | null {
  const q = latestQuarterlies(points);
  if (q.length === 4) return q.reduce((sum, item) => sum + (item.val ?? 0), 0);
  const annual = latestAnnual(points);
  return annual?.val ?? null;
}

function yoyFromAnnual(points: SecFactPoint[]): number | null {
  const annual = latestAnnual(points);
  if (!annual || annual.previousVal === null || annual.previousVal === 0) return null;
  return ((annual.val - annual.previousVal) / Math.abs(annual.previousVal)) * 100;
}

function trendFromAnnualRatio(numeratorPoints: SecFactPoint[], denominatorPoints: SecFactPoint[]): number | null {
  const num = latestAnnualSeries(numeratorPoints);
  const den = latestAnnualSeries(denominatorPoints);
  if (num.length < 2 || den.length < 2 || !num[0].val || !num[1].val || !den[0].val || !den[1].val) return null;
  const latestRatio = (num[0].val / den[0].val) * 100;
  const prevRatio = (num[1].val / den[1].val) * 100;
  return latestRatio - prevRatio;
}

function ratio(numerator: number | null, denominator: number | null): number | null {
  if (numerator === null || denominator === null || denominator === 0) return null;
  return (numerator / denominator) * 100;
}

function multiple(numerator: number | null, denominator: number | null): number | null {
  if (numerator === null || denominator === null || denominator === 0) return null;
  return numerator / denominator;
}

function dateOfFacts(...series: SecFactPoint[][]): string {
  const all = series.flat().filter((item) => item.end).map((item) => String(item.end));
  return all.sort().reverse()[0] ?? new Date().toISOString().slice(0, 10);
}

export function normalizeCompanyFinancials(
  ticker: string,
  cik: string,
  facts: SecCompanyFactsResponse,
  currentPrice: number | null = null,
): CompanyFinancialSnapshot {
  const revenueSeries = factSeries(facts, 'us-gaap', ['RevenueFromContractWithCustomerExcludingAssessedTax', 'SalesRevenueNet']);
  const operatingIncomeSeries = factSeries(facts, 'us-gaap', ['OperatingIncomeLoss']);
  const netIncomeSeries = factSeries(facts, 'us-gaap', ['NetIncomeLoss']);
  const ocfSeries = factSeries(facts, 'us-gaap', ['NetCashProvidedByUsedInOperatingActivities']);
  const capexSeries = factSeries(facts, 'us-gaap', ['PaymentsToAcquirePropertyPlantAndEquipment', 'CapitalExpendituresIncurredButNotYetPaid']);
  const cashSeries = factSeries(facts, 'us-gaap', ['CashAndCashEquivalentsAtCarryingValue', 'CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents']);
  const debtSeries = factSeries(facts, 'us-gaap', ['LongTermDebtAndCapitalLeaseObligations', 'LongTermDebt', 'DebtInstrumentFaceAmount']);
  const sharesSeries = factSeries(facts, 'dei', ['EntityCommonStockSharesOutstanding']);
  const stockCompSeries = factSeries(facts, 'us-gaap', ['ShareBasedCompensation', 'StockBasedCompensation']);
  const equitySeries = factSeries(facts, 'us-gaap', ['StockholdersEquity', 'StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest']);

  const revenueTtm = sumTtm(revenueSeries);
  const operatingIncomeTtm = sumTtm(operatingIncomeSeries);
  const netIncomeTtm = sumTtm(netIncomeSeries);
  const operatingCashFlowTtm = sumTtm(ocfSeries);
  const capexTtm = sumTtm(capexSeries);
  const freeCashFlowTtm = operatingCashFlowTtm !== null && capexTtm !== null ? operatingCashFlowTtm - Math.abs(capexTtm) : null;
  const cash = latestInstant(cashSeries);
  const debt = latestInstant(debtSeries);
  const sharesOutstanding = latestInstant(sharesSeries);
  const marketCap = currentPrice !== null && sharesOutstanding !== null ? currentPrice * sharesOutstanding : null;
  const enterpriseValue = marketCap !== null ? marketCap + (debt ?? 0) - (cash ?? 0) : null;
  const stockCompTtm = sumTtm(stockCompSeries);
  const equity = latestInstant(equitySeries);

  return {
    ticker,
    cik,
    asOf: dateOfFacts(revenueSeries, cashSeries, debtSeries, sharesSeries),
    revenueTtm,
    operatingIncomeTtm,
    netIncomeTtm,
    freeCashFlowTtm,
    cash,
    debt,
    capexTtm,
    operatingCashFlowTtm,
    sharesOutstanding,
    marketCap,
    enterpriseValue,
    revenueGrowthYoY: yoyFromAnnual(revenueSeries),
    operatingMargin: ratio(operatingIncomeTtm, revenueTtm),
    operatingMarginTrend: trendFromAnnualRatio(operatingIncomeSeries, revenueSeries),
    freeCashFlowMargin: ratio(freeCashFlowTtm, revenueTtm),
    netDebtToRevenue: multiple((debt ?? 0) - (cash ?? 0), revenueTtm),
    evToSales: multiple(enterpriseValue, revenueTtm),
    evToFcf: multiple(enterpriseValue, freeCashFlowTtm),
    shareDilutionYoY: yoyFromAnnual(sharesSeries),
    stockCompToRevenue: ratio(stockCompTtm, revenueTtm),
    roe: ratio(netIncomeTtm, equity),
    segmentGeoMixNote: null,
    estimateUpsidePct: null,
  };
}
