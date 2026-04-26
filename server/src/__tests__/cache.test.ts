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
  computeDerived: jest.fn(async () => ({})),
}));

jest.mock('../engines/regime', () => ({
  classifyRegime: jest.fn(() => ({ regime: 'NEUTRAL', score: 60, components: {}, date: '2026-01-01' })),
}));

jest.mock('../engines/signals', () => ({
  computeSignals: jest.fn(() => []),
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
  computeExecutionPlans: jest.fn(() => []),
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
import { DEFAULT_PROFILE, getSnapshot, resetSnapshotStateForTests } from '../state/cache';

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

  it('shares the in-flight build between forced and non-forced calls for the same profile', async () => {
    const [first, second] = await Promise.all([
      getSnapshot(DEFAULT_PROFILE, true),
      getSnapshot(DEFAULT_PROFILE, false),
    ]);

    expect(collectAll).toHaveBeenCalledTimes(1);
    expect(first).toBe(second);
  });
});
