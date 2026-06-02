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

  it('adds timing confirmation metadata to execution plans', async () => {
    const raw: Record<string, MarketDataPoint> = {
      NASDAQ: { code: 'NASDAQ', value: 20000, date: '2026-01-01', source: 'YAHOO' },
      VIXCLS: { code: 'VIXCLS', value: 24, date: '2026-01-01', source: 'FRED' },
      ICSA: { code: 'ICSA', value: 220000, date: '2026-01-01', source: 'FRED' },
    };
    const derived: Record<string, DerivedIndicator> = {
      OVERHEATED: { name: 'OVERHEATED', value: 1, date: '2026-01-01', formula: '' },
    };
    const signals: AssetSignal[] = [{
      asset: 'NASDAQ',
      signal: 'BUY',
      conditionsMet: 3,
      conditionsTotal: 5,
      weightedScore: 5,
      weightedMaxScore: 7,
      reasons: ['200DMA 회복 대기'],
      unmetReasons: ['과열 주의'],
      date: '2026-01-01',
      explanation: {
        baseSignal: 'BUY',
        finalSignal: 'BUY',
        overrides: [],
        macroReasons: ['유동성 개선'],
        sectorReasons: ['기술 섹터 우위'],
        flowReasons: ['기관 순매수'],
        timingNotes: ['W 반등 대기'],
      },
    }];
    const allocation: AllocationPlan = {
      regime: 'RISK_ON',
      score: 70,
      allocations: { nasdaq: 35, cash: 65 },
      leverageAllowed: false,
      buyStage: 1,
      date: '2026-01-01',
    };
    const regime: RegimeState = { regime: 'RISK_ON', score: 70, components: {}, date: '2026-01-01' };

    const plans = await computeExecutionPlans(raw, derived, signals, allocation, regime);
    const nasdaq = plans.find((plan) => plan.asset === 'NASDAQ');

    expect(nasdaq?.timing?.macroAligned).toBe(true);
    expect(nasdaq?.timing?.sectorAligned).toBe(true);
    expect(nasdaq?.timing?.flowConfirmed).toBe(true);
    expect(nasdaq?.timing?.chartConfirmed).toBe(true);
    expect(nasdaq?.timing?.overheatingRisk).toBe(true);
  });


  it('does not mark chartConfirmed from non-chart timing notes alone', async () => {
    const raw: Record<string, MarketDataPoint> = {
      GOLD: { code: 'GOLD', value: 3000, date: '2026-01-01', source: 'YAHOO' },
    };
    const derived: Record<string, DerivedIndicator> = {};
    const signals: AssetSignal[] = [{
      asset: 'GOLD',
      signal: 'BUY',
      conditionsMet: 2,
      conditionsTotal: 4,
      weightedScore: 4,
      weightedMaxScore: 8,
      reasons: ['실질금리 하락'],
      unmetReasons: [],
      date: '2026-01-01',
      explanation: {
        baseSignal: 'BUY',
        finalSignal: 'BUY',
        overrides: [],
        macroReasons: ['실질금리 부담 완화'],
        timingNotes: ['과열 주의'],
      },
    }];
    const allocation: AllocationPlan = {
      regime: 'CAUTION',
      score: 48,
      allocations: { gold: 20, cash: 80 },
      leverageAllowed: false,
      buyStage: 1,
      date: '2026-01-01',
    };
    const regime: RegimeState = { regime: 'CAUTION', score: 48, components: {}, date: '2026-01-01' };

    const plans = await computeExecutionPlans(raw, derived, signals, allocation, regime);
    const gold = plans.find((plan) => plan.asset === 'GOLD');

    expect(gold?.timing?.chartConfirmed).toBe(false);
    expect(gold?.timing?.overheatingRisk).toBe(true);
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
