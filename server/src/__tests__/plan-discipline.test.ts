/**
 * computePlanDiscipline (21차 P1#5 + 22차 확장) 단위테스트.
 * 실제 trade-log.jsonl 파일 IO 를 우회하기 위해 readRecentTradeLog 를 mock 한다.
 */

import { TradeLogEntry } from '../services/investment-plan';

jest.mock('../services/investment-plan', () => ({
  readRecentTradeLog: jest.fn<Promise<TradeLogEntry[]>, [number?]>(),
  appendTradeLog: jest.fn(),
  readInvestmentPlan: jest.fn().mockResolvedValue({
    horizon: 'medium',
    targetReturnAnnualPct: 12,
    maxDrawdownTolerancePct: 25,
    rebalanceIntervalDays: 90,
    leverageMaxPct: 15,
    profitTakeTargetPct: 25,
    stopLossPct: 15,
    monthlyDCA_KRW: 1_000_000,
    updatedAt: '2026-01-01T00:00:00Z',
  }),
}));

import { readRecentTradeLog } from '../services/investment-plan';
import { computePlanDiscipline } from '../services/weekly-report';

const NOW = Date.now();

function entry(overrides: Partial<TradeLogEntry> & { ageMs: number }): TradeLogEntry {
  return {
    ts: new Date(NOW - overrides.ageMs).toISOString(),
    kind: overrides.kind ?? 'observation',
    asset: overrides.asset,
    from: overrides.from,
    to: overrides.to,
    notes: overrides.notes,
    againstSystemRecommendation: overrides.againstSystemRecommendation,
    context: overrides.context,
  };
}

describe('computePlanDiscipline', () => {
  beforeEach(() => jest.clearAllMocks());

  it('flags reviewWarning when 0 reviews in last 7d', async () => {
    (readRecentTradeLog as jest.Mock).mockResolvedValue([]);
    const d = await computePlanDiscipline();
    expect(d.reviewsLast7d).toBe(0);
    expect(d.reviewWarning).toMatch(/복기 0회/);
  });

  it('flags impulsive when same asset has 3+ user_action in 24h', async () => {
    const items = [
      entry({ ageMs: 1 * 3600000, kind: 'user_action', asset: 'NASDAQ', to: 'BUY' }),
      entry({ ageMs: 5 * 3600000, kind: 'user_action', asset: 'NASDAQ', to: 'SELL' }),
      entry({ ageMs: 10 * 3600000, kind: 'user_action', asset: 'NASDAQ', to: 'BUY' }),
    ];
    (readRecentTradeLog as jest.Mock).mockResolvedValue(items);
    const d = await computePlanDiscipline();
    expect(d.impulsiveTrades24h).toBeGreaterThanOrEqual(1);
    expect(d.impulsiveWarning).toMatch(/빈번한 변경/);
  });

  it('flags patternWarning when ≥3 against_system in 4w', async () => {
    const items = Array.from({ length: 4 }).map((_, i) => entry({
      ageMs: (i + 1) * 86400000,
      kind: 'user_action',
      asset: 'NASDAQ',
      to: 'SELL',
      againstSystemRecommendation: true,
    }));
    (readRecentTradeLog as jest.Mock).mockResolvedValue(items);
    const d = await computePlanDiscipline();
    expect(d.againstSystemCount4w).toBe(4);
    expect(d.patternWarning).toMatch(/시스템 권고 반대/);
  });

  it('counts horizon changes in last 30d', async () => {
    const items = [
      entry({ ageMs: 5 * 86400000, kind: 'observation', notes: 'horizon change: medium → long' }),
      entry({ ageMs: 20 * 86400000, kind: 'observation', notes: 'horizon change: long → short' }),
      entry({ ageMs: 50 * 86400000, kind: 'observation', notes: 'horizon change: short → medium' }), // outside 30d
    ];
    (readRecentTradeLog as jest.Mock).mockResolvedValue(items);
    const d = await computePlanDiscipline();
    expect(d.horizonChangeCount30d).toBe(2);
  });
});
