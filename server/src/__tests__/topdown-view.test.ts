import { buildTopDownView, enrichSignalExplanations } from '../services/topdown-view';
import { AssetSignal, DerivedIndicator, MarketDataPoint, RegimeState } from '../types/indicators';

function makeRaw(overrides: Record<string, number> = {}): Record<string, MarketDataPoint> {
  const merged = { DXY: 100, WTI: 68, T10Y2Y: 0.6, ...overrides };
  const raw: Record<string, MarketDataPoint> = {};
  for (const [key, value] of Object.entries(merged)) {
    raw[key] = { code: key, value, date: '2026-01-01', source: 'FRED' };
  }
  return raw;
}

function makeDerived(overrides: Record<string, number> = {}): Record<string, DerivedIndicator> {
  const derived: Record<string, DerivedIndicator> = {};
  for (const [key, value] of Object.entries(overrides)) {
    derived[key] = { name: key, value, date: '2026-01-01', formula: '' };
  }
  return derived;
}

function makeSignal(asset: string, signal: AssetSignal['signal']): AssetSignal {
  return {
    asset,
    signal,
    conditionsMet: 3,
    conditionsTotal: 5,
    weightedScore: 6,
    weightedMaxScore: 10,
    reasons: [`${asset} base reason`],
    unmetReasons: [],
    date: '2026-01-01',
    explanation: {
      baseSignal: signal,
      finalSignal: signal,
      overrides: [],
    },
  };
}

describe('topdown-view', () => {
  it('does not add KOSPI/EMERGING flow rationales when supporting data is absent', () => {
    const raw = makeRaw();
    const derived = makeDerived({
      LIQUIDITY_DIRECTION: 1,
      REAL_YIELD: 1.4,
      SECTOR_XLK: 2.1,
    });
    const regime: RegimeState = {
      regime: 'NEUTRAL',
      score: 55,
      date: '2026-01-01',
      components: { geoRisk: 0 },
    };

    const view = buildTopDownView(raw, derived, regime, []);
    const kospi = view.assetRationale.find((item) => item.asset === 'KOSPI');
    const emerging = view.assetRationale.find((item) => item.asset === 'EMERGING');

    expect(kospi?.flowReasons ?? []).toHaveLength(0);
    expect(emerging?.flowReasons ?? []).toHaveLength(0);
  });

  it('builds favored sectors with quality metadata', () => {
    const raw = makeRaw();
    const derived = makeDerived({
      LIQUIDITY_DIRECTION: 2,
      REAL_YIELD: 1.2,
      SECTOR_XLK: 5.5,
      SECTOR_SOXX: 6.2,
      HELIUM_AI_BOTTLENECK: 1,
      POLICY_SECTOR_LIFT_PCT: 8,
      INSTITUTIONAL_NASDAQ_FLOW: 1,
    });
    const regime: RegimeState = {
      regime: 'RISK_ON',
      score: 78,
      date: '2026-01-01',
      components: { geoRisk: 1 },
    };
    const signals = [makeSignal('NASDAQ', 'BUY')];

    const view = buildTopDownView(raw, derived, regime, signals);

    expect(view.favoredSectors.length).toBeGreaterThan(0);
    expect(view.favoredSectors[0].quality?.totalScore).toBeGreaterThanOrEqual(60);
    expect(view.favoredSectors.some((sector) => sector.key === 'SECTOR_XLK' || sector.key === 'SECTOR_SOXX')).toBe(true);
  });

  it('enriches signal explanations with macro/sector/flow/timing layers', () => {
    const raw = makeRaw();
    const derived = makeDerived({
      LIQUIDITY_DIRECTION: 2,
      REAL_YIELD: 1.1,
      SECTOR_XLK: 4.4,
      SECTOR_XLC: 3.2,
      INSTITUTIONAL_NASDAQ_FLOW: 1,
      OVERHEATED: 1,
    });
    const regime: RegimeState = {
      regime: 'NEUTRAL',
      score: 62,
      date: '2026-01-01',
      components: { geoRisk: 1 },
    };
    const signals = [makeSignal('NASDAQ', 'BUY')];

    const view = buildTopDownView(raw, derived, regime, signals);
    enrichSignalExplanations(signals, view);

    expect(signals[0].explanation?.macroReasons?.length).toBeGreaterThan(0);
    expect(signals[0].explanation?.sectorReasons?.length).toBeGreaterThan(0);
    expect(signals[0].explanation?.flowReasons?.length).toBeGreaterThan(0);
    expect(signals[0].explanation?.timingNotes?.length).toBeGreaterThan(0);
  });

  it('caps merged explanation groups to prioritized top reasons', () => {
    const raw = makeRaw();
    const derived = makeDerived({
      LIQUIDITY_DIRECTION: 2,
      REAL_YIELD: 1.1,
      SECTOR_XLK: 4.4,
      SECTOR_XLC: 3.2,
      INSTITUTIONAL_NASDAQ_FLOW: 1,
      SMART_MONEY_SCORE: 1,
      OVERHEATED: 1,
    });
    const regime: RegimeState = {
      regime: 'NEUTRAL',
      score: 62,
      date: '2026-01-01',
      components: { geoRisk: 1 },
    };
    const signals = [makeSignal('NASDAQ', 'BUY')];
    signals[0].explanation = {
      baseSignal: 'BUY',
      finalSignal: 'BUY',
      overrides: [],
      macroReasons: ['달러 약세'],
      flowReasons: ['existing flow'],
      timingNotes: ['existing timing'],
    };

    const view = buildTopDownView(raw, derived, regime, signals);
    enrichSignalExplanations(signals, view);

    expect((signals[0].explanation?.macroReasons?.length ?? 0)).toBeLessThanOrEqual(3);
    expect((signals[0].explanation?.timingNotes?.length ?? 0)).toBeLessThanOrEqual(3);
  });

  it('merges existing signal-layer explanations with topdown rationale instead of overwriting', () => {
    const raw = makeRaw();
    const derived = makeDerived({
      LIQUIDITY_DIRECTION: 2,
      REAL_YIELD: 1.1,
      SECTOR_XLK: 4.4,
      INSTITUTIONAL_NASDAQ_FLOW: 1,
    });
    const regime: RegimeState = {
      regime: 'NEUTRAL',
      score: 62,
      date: '2026-01-01',
      components: { geoRisk: 1 },
    };
    const signals = [makeSignal('NASDAQ', 'BUY')];
    signals[0].explanation = {
      baseSignal: 'BUY',
      finalSignal: 'BUY',
      overrides: [],
      macroReasons: ['existing macro'],
      flowReasons: ['existing flow'],
    };

    const view = buildTopDownView(raw, derived, regime, signals);
    enrichSignalExplanations(signals, view);

    expect(signals[0].explanation?.macroReasons).toContain('existing macro');
    expect(signals[0].explanation?.flowReasons).toContain('existing flow');
    expect((signals[0].explanation?.macroReasons?.length ?? 0)).toBeGreaterThan(1);
  });
});
