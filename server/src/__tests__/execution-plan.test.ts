import { computeExecutionPlans, DEFAULT_TRANCHE_WEIGHTS } from '../engines/execution_plan';
import { AllocationPlan, AssetSignal, DerivedIndicator, MarketDataPoint, RegimeState } from '../types/indicators';

describe('computeExecutionPlans', () => {
  it('applies the 30/30/40 default tranche standard to 3-stage plans', async () => {
    const raw: Record<string, MarketDataPoint> = {
      NASDAQ: { code: 'NASDAQ', value: 20000, date: '2026-01-01', source: 'YAHOO' },
      VIXCLS: { code: 'VIXCLS', value: 32, date: '2026-01-01', source: 'FRED' },
      ICSA: { code: 'ICSA', value: 220000, date: '2026-01-01', source: 'FRED' },
    };
    const derived: Record<string, DerivedIndicator> = {
      NASDAQ_SMA200: { name: 'NASDAQ_SMA200', value: 18000, date: '2026-01-01', formula: '' },
      NASDAQ_DISPARITY: { name: 'NASDAQ_DISPARITY', value: -18, date: '2026-01-01', formula: '' },
      NASDAQ_ABOVE_200DMA: { name: 'NASDAQ_ABOVE_200DMA', value: 0, date: '2026-01-01', formula: '' },
      NASDAQ_W_BOTTOM: { name: 'NASDAQ_W_BOTTOM', value: 0, date: '2026-01-01', formula: '' },
    };
    const signals: AssetSignal[] = [
      {
        asset: 'NASDAQ',
        signal: 'STRONG_BUY',
        conditionsMet: 5,
        conditionsTotal: 7,
        weightedScore: 5,
        weightedMaxScore: 7,
        reasons: [],
        unmetReasons: [],
        date: '2026-01-01',
      },
    ];
    const allocation: AllocationPlan = {
      regime: 'CORRECTION',
      score: 42,
      allocations: { nasdaq: 35, cash: 65 },
      leverageAllowed: false,
      buyStage: 2,
      date: '2026-01-01',
    };
    const regime: RegimeState = { regime: 'CORRECTION', score: 42, components: {}, date: '2026-01-01' };

    const plans = await computeExecutionPlans(raw, derived, signals, allocation, regime);
    const nasdaq = plans.find((plan) => plan.asset === 'NASDAQ');

    expect(nasdaq?.stages.map((stage) => stage.weightPct)).toEqual([...DEFAULT_TRANCHE_WEIGHTS]);
  });

  it('keeps BUY playbooks aligned to the 30/30/40 tranche standard', async () => {
    const raw: Record<string, MarketDataPoint> = {
      GOLD: { code: 'GOLD', value: 3000, date: '2026-01-01', source: 'YAHOO' },
      EWZ: { code: 'EWZ', value: 35, date: '2026-01-01', source: 'YAHOO' },
      SILVER: { code: 'SILVER', value: 32, date: '2026-01-01', source: 'YAHOO' },
      DXY: { code: 'DXY', value: 101, date: '2026-01-01', source: 'YAHOO' },
    };
    const derived: Record<string, DerivedIndicator> = {
      GOLD_SMA200: { name: 'GOLD_SMA200', value: 2800, date: '2026-01-01', formula: '' },
      GOLD_FIB_382: { name: 'GOLD_FIB_382', value: 2950, date: '2026-01-01', formula: '' },
      GOLD_FIB_500: { name: 'GOLD_FIB_500', value: 2900, date: '2026-01-01', formula: '' },
      GOLD_FIB_618: { name: 'GOLD_FIB_618', value: 2850, date: '2026-01-01', formula: '' },
      DXY_TREND: { name: 'DXY_TREND', value: -1, date: '2026-01-01', formula: '' },
      GLOBAL_M2_PROXY: { name: 'GLOBAL_M2_PROXY', value: 2, date: '2026-01-01', formula: '' },
    };
    const signals: AssetSignal[] = [
      {
        asset: 'GOLD',
        signal: 'BUY',
        conditionsMet: 2,
        conditionsTotal: 4,
        weightedScore: 4.5,
        weightedMaxScore: 8,
        reasons: [],
        unmetReasons: [],
        date: '2026-01-01',
      },
      {
        asset: 'EMERGING',
        signal: 'BUY',
        conditionsMet: 2,
        conditionsTotal: 3,
        weightedScore: 2,
        weightedMaxScore: 3,
        reasons: [],
        unmetReasons: [],
        date: '2026-01-01',
      },
      {
        asset: 'SILVER',
        signal: 'BUY',
        conditionsMet: 1,
        conditionsTotal: 2,
        weightedScore: 1,
        weightedMaxScore: 2,
        reasons: [],
        unmetReasons: [],
        date: '2026-01-01',
      },
    ];
    const allocation: AllocationPlan = {
      regime: 'NEUTRAL',
      score: 60,
      allocations: { gold: 20, emerging: 10, silver: 8, cash: 62 },
      leverageAllowed: false,
      buyStage: 1,
      date: '2026-01-01',
    };
    const regime: RegimeState = { regime: 'NEUTRAL', score: 60, components: {}, date: '2026-01-01' };

    const plans = await computeExecutionPlans(raw, derived, signals, allocation, regime);
    expect(plans.find((plan) => plan.asset === 'GOLD')?.stages.map((stage) => stage.weightPct)).toEqual([...DEFAULT_TRANCHE_WEIGHTS]);
    expect(plans.find((plan) => plan.asset === 'EMERGING')?.stages.map((stage) => stage.weightPct)).toEqual([...DEFAULT_TRANCHE_WEIGHTS]);
    expect(plans.find((plan) => plan.asset === 'SILVER')?.stages.map((stage) => stage.weightPct)).toEqual([...DEFAULT_TRANCHE_WEIGHTS]);
  });
});
