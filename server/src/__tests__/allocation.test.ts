import { computeAllocation } from '../engines/allocation';
import { AssetSignal, DerivedIndicator, MarketDataPoint } from '../types/indicators';

function makeSignals(overrides: Record<string, string>, leverageTier?: 'HARD' | 'MEDIUM' | 'SOFT' | null): AssetSignal[] {
  const defaults: Record<string, string> = {
    NASDAQ: 'HOLD', KOSPI: 'HOLD', GOLD: 'HOLD', SILVER: 'HOLD',
    COPPER: 'HOLD', CASH: 'HOLD', LEVERAGE: 'HOLD',
  };
  const merged = { ...defaults, ...overrides };
  return Object.entries(merged).map(([asset, signal]) => ({
    asset, signal: signal as any, conditionsMet: 0, conditionsTotal: 0,
    weightedScore: 0, weightedMaxScore: 0, reasons: [], unmetReasons: [], date: '2026-01-01',
    ...(asset === 'LEVERAGE' ? { tier: leverageTier ?? null } : {}),
  }));
}

function makeDerived(overrides: Record<string, number>): Record<string, DerivedIndicator> {
  const d: Record<string, DerivedIndicator> = {};
  for (const [k, v] of Object.entries(overrides)) {
    d[k] = { name: k, value: v, date: '2026-01-01', formula: '' };
  }
  return d;
}

function makeRaw(overrides: Record<string, number>): Record<string, MarketDataPoint> {
  const raw: Record<string, MarketDataPoint> = {};
  for (const [k, v] of Object.entries(overrides)) {
    raw[k] = { code: k, value: v, date: '2026-01-01', source: 'FRED' };
  }
  return raw;
}

describe('computeAllocation', () => {
  it('allocations sum to 100%', () => {
    const signals = makeSignals({});
    const result = computeAllocation('NEUTRAL', 60, signals, makeDerived({}), makeRaw({}), 'long');
    const sum = Object.values(result.allocations).reduce((a, b) => a + b, 0);
    expect(sum).toBe(100);
  });

  it('STRONG_BUY increases allocation vs HOLD', () => {
    const holdSignals = makeSignals({ NASDAQ: 'HOLD' });
    const buySignals = makeSignals({ NASDAQ: 'STRONG_BUY' });
    const holdResult = computeAllocation('NEUTRAL', 60, holdSignals, makeDerived({}), makeRaw({}), 'long');
    const buyResult = computeAllocation('NEUTRAL', 60, buySignals, makeDerived({}), makeRaw({}), 'long');
    expect(buyResult.allocations.nasdaq).toBeGreaterThan(holdResult.allocations.nasdaq);
  });

  it('REDUCE decreases allocation vs HOLD', () => {
    const holdSignals = makeSignals({ GOLD: 'HOLD' });
    const reduceSignals = makeSignals({ GOLD: 'REDUCE' });
    const holdResult = computeAllocation('NEUTRAL', 60, holdSignals, makeDerived({}), makeRaw({}), 'long');
    const reduceResult = computeAllocation('NEUTRAL', 60, reduceSignals, makeDerived({}), makeRaw({}), 'long');
    expect(reduceResult.allocations.gold).toBeLessThan(holdResult.allocations.gold);
  });

  it('RECESSION_RISK has highest cash allocation', () => {
    const signals = makeSignals({});
    const result = computeAllocation('RECESSION_RISK', 10, signals, makeDerived({}), makeRaw({}), 'long');
    expect(result.allocations.cash).toBeGreaterThanOrEqual(40);
  });

  it('leverage not allowed when conditions not met', () => {
    const signals = makeSignals({ LEVERAGE: 'HOLD' });
    const result = computeAllocation('NEUTRAL', 60, signals, makeDerived({}), makeRaw({}), 'long');
    expect(result.leverageAllowed).toBe(false);
    expect(result.allocations.leverage).toBe(0);
  });

  it('overheated increases cash by ~20%', () => {
    const signals = makeSignals({});
    const normalResult = computeAllocation('RISK_ON', 80, signals, makeDerived({ OVERHEATED: 0 }), makeRaw({}), 'long');
    const hotResult = computeAllocation('RISK_ON', 80, signals, makeDerived({ OVERHEATED: 1 }), makeRaw({}), 'long');
    expect(hotResult.allocations.cash).toBeGreaterThan(normalResult.allocations.cash);
  });

  it('high FX level reduces korea allocation', () => {
    const signals = makeSignals({});
    const normalResult = computeAllocation('NEUTRAL', 60, signals, makeDerived({ KRW_FX_LEVEL: 1 }), makeRaw({}), 'long');
    const highFxResult = computeAllocation('NEUTRAL', 60, signals, makeDerived({ KRW_FX_LEVEL: -2 }), makeRaw({}), 'long');
    expect(highFxResult.allocations.korea).toBeLessThan(normalResult.allocations.korea);
  });

  it('leverage HARD tier is capped at 15% after normalization (영상1 §전략C)', () => {
    // PANIC_BUT_OK 국면 + LEVERAGE STRONG_BUY(HARD) + 다른 자산 SELL 로 leverage 비중이 팽창할 조건
    const signals = makeSignals({
      LEVERAGE: 'STRONG_BUY',
      NASDAQ: 'SELL',
      KOSPI: 'SELL',
      GOLD: 'SELL',
      SILVER: 'SELL',
      COPPER: 'SELL',
    }, 'HARD');
    const result = computeAllocation('PANIC_BUT_OK', 30, signals, makeDerived({}), makeRaw({}), 'long');
    expect(result.leverageAllowed).toBe(true);
    expect(result.allocations.leverage).toBeLessThanOrEqual(15);
    expect(Object.values(result.allocations).reduce((a, b) => a + b, 0)).toBe(100);
  });

  it('leverage MEDIUM tier is capped at 10%', () => {
    const signals = makeSignals({
      LEVERAGE: 'BUY', NASDAQ: 'SELL', KOSPI: 'SELL', GOLD: 'SELL', SILVER: 'SELL', COPPER: 'SELL',
    }, 'MEDIUM');
    const result = computeAllocation('PANIC_BUT_OK', 30, signals, makeDerived({}), makeRaw({}), 'long');
    expect(result.leverageAllowed).toBe(true);
    expect(result.allocations.leverage).toBeLessThanOrEqual(10);
  });

  it('leverage SOFT tier is capped at 5%', () => {
    const signals = makeSignals({
      LEVERAGE: 'BUY', NASDAQ: 'SELL', KOSPI: 'SELL', GOLD: 'SELL', SILVER: 'SELL', COPPER: 'SELL',
    }, 'SOFT');
    const result = computeAllocation('PANIC_BUT_OK', 30, signals, makeDerived({}), makeRaw({}), 'long');
    expect(result.leverageAllowed).toBe(true);
    expect(result.allocations.leverage).toBeLessThanOrEqual(5);
  });

  it('leverage BUY without tier stays at 0% (no tier gate)', () => {
    const signals = makeSignals({ LEVERAGE: 'BUY' }, null);
    const result = computeAllocation('PANIC_BUT_OK', 30, signals, makeDerived({}), makeRaw({}), 'long');
    expect(result.leverageAllowed).toBe(false);
    expect(result.allocations.leverage).toBe(0);
  });

  it('overheated keeps allocations summing to 100 even when reduceKeys are small', () => {
    // RECESSION_RISK 는 nasdaq/leverage/korea/emerging/copper 총합이 작다.
    // 과열 + SELL 신호로 reduceKeys 가 25 보다 작을 때도 invariant 유지 검증.
    const signals = makeSignals({ NASDAQ: 'SELL', KOSPI: 'SELL', COPPER: 'SELL' });
    const result = computeAllocation(
      'RECESSION_RISK',
      10,
      signals,
      makeDerived({ OVERHEATED: 1 }),
      makeRaw({}),
      'long',
    );
    const sum = Object.values(result.allocations).reduce((a, b) => a + b, 0);
    expect(sum).toBe(100);
  });

  it('disables korea allocation when includeKR=false', () => {
    const signals = makeSignals({ KOSPI: 'BUY' });
    const result = computeAllocation(
      'NEUTRAL',
      60,
      signals,
      makeDerived({}),
      makeRaw({}),
      'long',
      undefined,
      { leverageEnabled: true, includeKR: false },
    );
    expect(result.allocations.korea).toBe(0);
    expect(result.allocations.cash).toBeGreaterThan(0);
  });

  it('disables leverage allocation when leverageEnabled=false', () => {
    const signals = makeSignals({ LEVERAGE: 'BUY' });
    const result = computeAllocation(
      'PANIC_BUT_OK',
      30,
      signals,
      makeDerived({}),
      makeRaw({}),
      'long',
      undefined,
      { leverageEnabled: false, includeKR: true },
    );
    expect(result.leverageAllowed).toBe(false);
    expect(result.allocations.leverage).toBe(0);
    expect(result.allocations.nasdaq).toBeGreaterThan(0);
  });

  it('returns explanation with signal and defense adjustments when requested', () => {
    const signals = makeSignals({ NASDAQ: 'STRONG_BUY', GOLD: 'BUY' });
    const result = computeAllocation(
      'RISK_ON',
      80,
      signals,
      makeDerived({ OVERHEATED: 1 }),
      makeRaw({}),
      'long',
      undefined,
      { leverageEnabled: true, includeKR: true },
      { includeExplanation: true },
    );
    expect(result.explanation?.signalAdjustments.some((item) => item.asset === 'NASDAQ')).toBe(true);
    expect(result.explanation?.defenseMode).toBe('overheated');
    expect(result.explanation?.adjustments.some((item) => item.step === 'defense-mode')).toBe(true);
    expect(result.explanation?.finalAllocations).toEqual(result.allocations);
  });
});
