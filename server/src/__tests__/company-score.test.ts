import { computeCompanyScore } from '../engines/fundamentals/company-score';
import { normalizeCompanyFinancials } from '../engines/fundamentals/normalize-financials';
import { SecCompanyFactsResponse } from '../collectors/sec/companyfacts';

function sampleFacts(): SecCompanyFactsResponse {
  return {
    cik: '0000000001',
    entityName: 'Test Co',
    facts: {
      'us-gaap': {
        RevenueFromContractWithCustomerExcludingAssessedTax: {
          units: {
            USD: [
              { form: '10-Q', fp: 'Q1', end: '2025-03-31', val: 110 },
              { form: '10-Q', fp: 'Q2', end: '2025-06-30', val: 120 },
              { form: '10-Q', fp: 'Q3', end: '2025-09-30', val: 130 },
              { form: '10-Q', fp: 'Q4', end: '2025-12-31', val: 140 },
              { form: '10-K', fp: 'FY', end: '2025-12-31', val: 500 },
              { form: '10-K', fp: 'FY', end: '2024-12-31', val: 400 },
            ],
          },
        },
        OperatingIncomeLoss: {
          units: { USD: [
            { form: '10-Q', fp: 'Q1', end: '2025-03-31', val: 28 },
            { form: '10-Q', fp: 'Q2', end: '2025-06-30', val: 30 },
            { form: '10-Q', fp: 'Q3', end: '2025-09-30', val: 32 },
            { form: '10-Q', fp: 'Q4', end: '2025-12-31', val: 35 },
          ] },
        },
        NetIncomeLoss: {
          units: { USD: [
            { form: '10-Q', fp: 'Q1', end: '2025-03-31', val: 20 },
            { form: '10-Q', fp: 'Q2', end: '2025-06-30', val: 22 },
            { form: '10-Q', fp: 'Q3', end: '2025-09-30', val: 24 },
            { form: '10-Q', fp: 'Q4', end: '2025-12-31', val: 26 },
          ] },
        },
        NetCashProvidedByUsedInOperatingActivities: {
          units: { USD: [
            { form: '10-Q', fp: 'Q1', end: '2025-03-31', val: 30 },
            { form: '10-Q', fp: 'Q2', end: '2025-06-30', val: 31 },
            { form: '10-Q', fp: 'Q3', end: '2025-09-30', val: 33 },
            { form: '10-Q', fp: 'Q4', end: '2025-12-31', val: 35 },
          ] },
        },
        PaymentsToAcquirePropertyPlantAndEquipment: {
          units: { USD: [
            { form: '10-Q', fp: 'Q1', end: '2025-03-31', val: 8 },
            { form: '10-Q', fp: 'Q2', end: '2025-06-30', val: 8 },
            { form: '10-Q', fp: 'Q3', end: '2025-09-30', val: 9 },
            { form: '10-Q', fp: 'Q4', end: '2025-12-31', val: 10 },
          ] },
        },
        CashAndCashEquivalentsAtCarryingValue: {
          units: { USD: [{ end: '2025-12-31', val: 150 }] },
        },
        LongTermDebt: {
          units: { USD: [{ end: '2025-12-31', val: 90 }] },
        },
      },
      dei: {
        EntityCommonStockSharesOutstanding: {
          units: { shares: [{ end: '2025-12-31', val: 10 }] },
        },
      },
    },
  };
}

describe('company fundamentals', () => {
  it('normalizes SEC facts into financial snapshot', () => {
    const snapshot = normalizeCompanyFinancials('TEST', '0000000001', sampleFacts(), 50);
    expect(snapshot.revenueTtm).toBe(500);
    expect(snapshot.revenueGrowthYoY).toBeCloseTo(25);
    expect(snapshot.freeCashFlowTtm).toBe(94);
    expect(snapshot.marketCap).toBe(500);
    expect(snapshot.enterpriseValue).toBe(440);
    expect(snapshot.evToSales).toBeCloseTo(0.88, 2);
  });

  it('computes aggregate company score', () => {
    const snapshot = normalizeCompanyFinancials('TEST', '0000000001', sampleFacts(), 50);
    const score = computeCompanyScore(snapshot);
    expect(score.totalScore).toBeGreaterThanOrEqual(70);
    expect(score.growth.value).toBeGreaterThan(70);
    expect(score.balanceSheet.value).toBeGreaterThan(70);
    expect(score.reasons.length).toBeGreaterThan(0);
  });
});

