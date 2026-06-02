jest.mock('../collectors', () => ({
  collectAll: jest.fn(async () => {
    await new Promise((resolve) => setTimeout(resolve, 25));
    return {
      NASDAQ: { code: '^IXIC', value: 100, date: '2026-01-01', source: 'YAHOO' },
      SP500: { code: '^GSPC', value: 100, date: '2026-01-01', source: 'YAHOO' },
    };
  }),
}));

jest.mock('../engines/derived', () => ({
  computeDerived: jest.fn(async () => ({
    SECTOR_XLP: { name: 'sector_xlp', value: 2.1, date: '2026-01-01', formula: '' },
    SECTOR_ITA: { name: 'sector_ita', value: 3.4, date: '2026-01-01', formula: '' },
    SECTOR_GRID: { name: 'sector_grid', value: 4.2, date: '2026-01-01', formula: '' },
    SECTOR_POLICY_SUPPORT_GRID: { name: 'sector_policy_support_grid', value: 78, date: '2026-01-01', formula: '' },
    SECTOR_STRUCTURAL_DEMAND_GRID: { name: 'sector_structural_demand_grid', value: 84, date: '2026-01-01', formula: '' },
    SECTOR_SUPPLY_TIGHTNESS_GRID: { name: 'sector_supply_tightness_grid', value: 72, date: '2026-01-01', formula: '' },
    SECTOR_QUALITY_TOTAL_GRID: { name: 'sector_quality_total_grid', value: 79, date: '2026-01-01', formula: '' },
  })),
}));

jest.mock('../engines/regime', () => ({
  classifyRegime: jest.fn(() => ({ regime: 'NEUTRAL', score: 60, components: {}, date: '2026-01-01' })),
}));

jest.mock('../engines/signals', () => ({
  computeSignals: jest.fn(() => ([{
    asset: 'NASDAQ',
    signal: 'BUY',
    conditionsMet: 3,
    conditionsTotal: 5,
    weightedScore: 3,
    weightedMaxScore: 5,
    reasons: ['기본 이유'],
    unmetReasons: [],
    date: '2026-01-01',
    explanation: {
      baseSignal: 'BUY',
      finalSignal: 'BUY',
      overrides: [],
      macroReasons: ['유동성 개선'],
      sectorReasons: ['전력망 CAPEX 우위'],
      flowReasons: ['기관 흐름 개선'],
      timingNotes: ['W 바닥 확인'],
    },
  }])),
}));

jest.mock('../engines/allocation', () => ({
  computeAllocation: jest.fn(() => ({
    regime: 'NEUTRAL',
    score: 60,
    allocations: { cash: 100 },
    leverageAllowed: false,
    buyStage: null,
    date: '2026-01-01',
  })),
}));

jest.mock('../state/history-store', () => ({
  HISTORY_GUARANTEE: { FRED_YEARS: 10, YAHOO_YEARS: 5 },
}));

jest.mock('../collectors/auto-manual', () => ({
  computeAutoManualInputs: jest.fn(async () => ({
    policyDirection: 0,
    geoRisk: 2,
    cbBuying: true,
    ismPmi: null,
  })),
}));

jest.mock('../collectors/smart-money', () => ({
  fetchInsiderSummary: jest.fn(async () => null),
}));

jest.mock('../collectors/calendar', () => ({
  fetchEconomicCalendar: jest.fn(async () => []),
}));

jest.mock('../engines/execution_plan', () => ({
  computeExecutionPlans: jest.fn(() => ([{
    asset: 'NASDAQ',
    action: 'BUY_NOW',
    actionLabel: '지금 1차 매수',
    currentPrice: 100,
    targetAllocationPct: 35,
    stages: [],
    stopLoss: { price: null, condition: '— ' },
    takeProfit: { price: null, condition: '— ' },
    validityDays: 45,
    primaryReason: '테스트',
    timing: {
      macroAligned: true,
      sectorAligned: true,
      flowConfirmed: true,
      chartConfirmed: true,
      overheatingRisk: false,
      notes: ['전력/인프라 CAPEX 프록시 정합'],
    },
  }])),
}));

jest.mock('../utils/market-hours', () => ({
  getUSPriceSource: jest.fn(() => 'spot'),
}));

jest.mock('../services/flagPersistence', () => ({
  hardenFlag: jest.fn(async (_key: string, value: number) => value),
}));

jest.mock('../observability/trace', () => ({
  withSpan: jest.fn((_name: string, fn: (span: { setAttribute: (k: string, v: string | number) => void }) => Promise<unknown>) =>
    fn({ setAttribute: () => undefined })),
}));

jest.mock('../services/logger', () => ({
  childLogger: jest.fn(() => ({ info: jest.fn(), warn: jest.fn(), error: jest.fn() })),
  serializeError: jest.fn((error: unknown) => String(error)),
}));

jest.mock('../services/policy-inputs', () => ({
  DEFAULT_MANUAL_INPUTS: { policyDirection: 0, geoRisk: 2, cbBuying: true, ismPmi: null },
  mergeEffectiveManualInputs: jest.fn((manualInputs: unknown) => manualInputs),
}));

import { collectAll } from '../collectors';
import { DEFAULT_PROFILE, buildSnapshot, getSnapshot, resetSnapshotStateForTests } from '../state/cache';

describe('getSnapshot', () => {
  beforeEach(() => {
    resetSnapshotStateForTests();
    jest.clearAllMocks();
  });

  it('dedupes concurrent snapshot builds for the same profile', async () => {
    const [first, second] = await Promise.all([
      getSnapshot(DEFAULT_PROFILE, true),
      getSnapshot(DEFAULT_PROFILE, true),
    ]);

    expect(collectAll).toHaveBeenCalledTimes(1);
    expect(first).toBe(second);
  });


  it('includes topdown sectors, signal explanations, and execution timing in snapshot shape', async () => {
    const snapshot = await buildSnapshot(DEFAULT_PROFILE);

    expect(snapshot.meta.topdown?.favoredSectors).toBeDefined();
    expect(snapshot.meta.topdown?.favoredSectors?.some((sector) => ['SECTOR_GRID', 'SECTOR_ITA', 'SECTOR_XLP'].includes(sector.key))).toBe(true);
    expect(snapshot.signals[0]?.explanation?.sectorReasons?.length).toBeGreaterThan(0);
    expect(snapshot.meta.executionPlans?.[0]?.timing?.sectorAligned).toBe(true);
  });

  it('shares the in-flight build between forced and non-forced calls for the same profile', async () => {
    const [first, second] = await Promise.all([
      getSnapshot(DEFAULT_PROFILE, true),
      getSnapshot(DEFAULT_PROFILE, false),
    ]);

    expect(collectAll).toHaveBeenCalledTimes(1);
    expect(first).toBe(second);
  });
});
