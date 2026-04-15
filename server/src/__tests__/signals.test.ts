import { computeSignals } from '../engines/signals';
import { MarketDataPoint, DerivedIndicator, RegimeState, UserProfile } from '../types/indicators';

function makeRaw(overrides: Record<string, number>): Record<string, MarketDataPoint> {
  const raw: Record<string, MarketDataPoint> = {};
  for (const [k, v] of Object.entries(overrides)) {
    raw[k] = { code: k, value: v, date: '2026-01-01', source: 'FRED' };
  }
  return raw;
}

function makeDerived(overrides: Record<string, number>): Record<string, DerivedIndicator> {
  const d: Record<string, DerivedIndicator> = {};
  for (const [k, v] of Object.entries(overrides)) {
    d[k] = { name: k, value: v, date: '2026-01-01', formula: '' };
  }
  return d;
}

const defaultProfile: UserProfile = {
  riskTolerance: 'moderate', investmentHorizon: 'long', leverageEnabled: false,
  includeCrypto: false, includeKR: true,
  manualInputs: { policyDirection: 0, geoRisk: 2, cbBuying: true, ismPmi: null },
};

const defaultRegime: RegimeState = { regime: 'NEUTRAL', score: 60, components: {}, date: '2026-01-01' };

describe('computeSignals', () => {
  it('returns 8 asset signals (incl. EMERGING)', () => {
    const raw = makeRaw({ VIXCLS: 20, ICSA: 220000, FEAR_GREED: 50, DXY: 100, WTI: 70, USDKRW: 1450 });
    const derived = makeDerived({ NASDAQ_ABOVE_200DMA: 1, NASDAQ_DISPARITY: 5, KOSPI_ABOVE_200DMA: 1, KOSPI_DISPARITY: 5, KRW_FX_LEVEL: 1, REAL_YIELD: 1.5, GOLD_SILVER_RATIO: 70, COPPER_GOLD_RATIO: 0.0013, ISM_PROXY: 51 });
    const signals = computeSignals(raw, derived, defaultRegime, defaultProfile);
    expect(signals).toHaveLength(8);
    const assets = signals.map((s) => s.asset);
    expect(assets).toContain('NASDAQ');
    expect(assets).toContain('KOSPI');
    expect(assets).toContain('GOLD');
    expect(assets).toContain('SILVER');
    expect(assets).toContain('COPPER');
    expect(assets).toContain('CASH');
    expect(assets).toContain('LEVERAGE');
    expect(assets).toContain('EMERGING');
  });

  it('NASDAQ returns SELL when 200DMA below + ICSA > 300K', () => {
    const raw = makeRaw({ VIXCLS: 35, ICSA: 350000, FEAR_GREED: 15 });
    const derived = makeDerived({ NASDAQ_ABOVE_200DMA: 0, NASDAQ_DISPARITY: -20 });
    const signals = computeSignals(raw, derived, defaultRegime, defaultProfile);
    const nasdaq = signals.find((s) => s.asset === 'NASDAQ');
    expect(nasdaq?.signal).toBe('SELL');
  });

  it('LEVERAGE returns BUY only when all 3 conditions met', () => {
    const raw = makeRaw({ VIXCLS: 40, ICSA: 250000 });
    const derived = makeDerived({ NASDAQ_DISPARITY: -27 });
    const signals = computeSignals(raw, derived, defaultRegime, defaultProfile);
    const lev = signals.find((s) => s.asset === 'LEVERAGE');
    expect(lev?.signal).toBe('BUY');
    expect(lev?.conditionsMet).toBe(3);
  });

  it('LEVERAGE returns HOLD when conditions not met', () => {
    const raw = makeRaw({ VIXCLS: 18, ICSA: 220000 });
    const derived = makeDerived({ NASDAQ_DISPARITY: -5 });
    const signals = computeSignals(raw, derived, defaultRegime, defaultProfile);
    const lev = signals.find((s) => s.asset === 'LEVERAGE');
    expect(lev?.signal).toBe('HOLD');
  });

  it('GOLD upgrades to BUY in strong bottom zone', () => {
    const raw = makeRaw({ DXY: 100, VIXCLS: 20, ICSA: 220000 });
    const derived = makeDerived({ REAL_YIELD: 1.5, REAL_YIELD_TREND: -0.1, DXY_TREND: -1, GOLD_DISPARITY: -18, GOLD_FIB_ZONE: 3 });
    const profile = { ...defaultProfile, manualInputs: { ...defaultProfile.manualInputs, geoRisk: 3 } };
    const signals = computeSignals(raw, derived, defaultRegime, profile);
    const gold = signals.find((s) => s.asset === 'GOLD');
    expect(['BUY', 'STRONG_BUY']).toContain(gold?.signal);
  });

  it('KOSPI caps at BUY when trend confirmation < 2', () => {
    const raw = makeRaw({ VIXCLS: 18, ICSA: 220000, WTI: 75, USDKRW: 1450 });
    const derived = makeDerived({ KOSPI_ABOVE_200DMA: 0, KOSPI_DISPARITY: -20, KRW_FX_LEVEL: 1, KOSPI_TREND_RECOVERY: 0, KOSPI_VOLUME_CONFIRM: 0 });
    const signals = computeSignals(raw, derived, defaultRegime, defaultProfile);
    const kospi = signals.find((s) => s.asset === 'KOSPI');
    expect(['BUY', 'HOLD']).toContain(kospi?.signal);
    expect(kospi?.signal).not.toBe('STRONG_BUY');
  });

  it('all signals include weight labels', () => {
    const raw = makeRaw({ VIXCLS: 20, ICSA: 220000, DXY: 100, WTI: 70, USDKRW: 1450 });
    const derived = makeDerived({ NASDAQ_ABOVE_200DMA: 1, NASDAQ_DISPARITY: 5, KOSPI_ABOVE_200DMA: 1, KRW_FX_LEVEL: 1, REAL_YIELD: 1.5, GOLD_SILVER_RATIO: 70, ISM_PROXY: 51 });
    const signals = computeSignals(raw, derived, defaultRegime, defaultProfile);
    for (const sig of signals) {
      if (sig.conditionsTotal > 0) {
        const allReasons = [...sig.reasons, ...sig.unmetReasons];
        const hasWeight = allReasons.some((r) => r.includes('가중치') || r.includes('보조조건'));
        expect(hasWeight).toBe(true);
      }
    }
  });
});
