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
  riskTolerance: 'moderate', investmentHorizon: 'long', leverageEnabled: true,
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

  it('LEVERAGE HARD tier (disp<=-25, VIX>=35, ICSA<300K) → STRONG_BUY, 15% cap', () => {
    const raw = makeRaw({ VIXCLS: 38, ICSA: 200000 });
    const derived = makeDerived({ NASDAQ_DISPARITY: -26 });
    // 29차 fix-F: LEVERAGE STRONG_BUY 는 RISK_ON/PANIC_BUT_OK 에서만 허용.
    // VIX=38 + 이격도=-26% 는 PANIC_BUT_OK 환경 — regime 정합 반영.
    const signals = computeSignals(raw, derived, { regime: 'PANIC_BUT_OK', score: 30, components: {}, date: '2026-01-01' }, defaultProfile);
    const lev = signals.find((s) => s.asset === 'LEVERAGE');
    expect(lev?.signal).toBe('STRONG_BUY');
    expect(lev?.tier).toBe('HARD');
    expect(lev?.reasons.some((r) => r.includes('LEVERAGE_TIER: HARD') && r.includes('15% 상한'))).toBe(true);
  });

  it('LEVERAGE MEDIUM tier (disp<=-15, VIX>=30, ICSA<300K) → BUY, 10% cap', () => {
    const raw = makeRaw({ VIXCLS: 32, ICSA: 220000 });
    const derived = makeDerived({ NASDAQ_DISPARITY: -18 });
    const signals = computeSignals(raw, derived, defaultRegime, defaultProfile);
    const lev = signals.find((s) => s.asset === 'LEVERAGE');
    expect(lev?.signal).toBe('BUY');
    expect(lev?.tier).toBe('MEDIUM');
    expect(lev?.reasons.some((r) => r.includes('LEVERAGE_TIER: MEDIUM') && r.includes('10% 상한'))).toBe(true);
  });

  it('LEVERAGE SOFT tier (disp<=-5, VIX>=30, ICSA<300K) → BUY, 5% cap', () => {
    const raw = makeRaw({ VIXCLS: 31, ICSA: 250000 });
    const derived = makeDerived({ NASDAQ_DISPARITY: -6 });
    const signals = computeSignals(raw, derived, defaultRegime, defaultProfile);
    const lev = signals.find((s) => s.asset === 'LEVERAGE');
    expect(lev?.signal).toBe('BUY');
    expect(lev?.tier).toBe('SOFT');
    expect(lev?.reasons.some((r) => r.includes('LEVERAGE_TIER: SOFT') && r.includes('5% 상한'))).toBe(true);
  });

  it('LEVERAGE HOLD when VIX below 30 even if disp deep', () => {
    const raw = makeRaw({ VIXCLS: 25, ICSA: 250000 });
    const derived = makeDerived({ NASDAQ_DISPARITY: -20 });
    const signals = computeSignals(raw, derived, defaultRegime, defaultProfile);
    const lev = signals.find((s) => s.asset === 'LEVERAGE');
    expect(lev?.signal).toBe('HOLD');
    expect(lev?.tier ?? null).toBeNull();
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

  it('caps KOSPI at HOLD when only flow warnings fire without actual price overheat', () => {
    const raw = makeRaw({ VIXCLS: 18, ICSA: 220000, WTI: 75, USDKRW: 1450 });
    const derived = makeDerived({
      KOSPI_ABOVE_200DMA: 0,
      KOSPI_DISPARITY: -20,
      KRW_FX_LEVEL: 1,
      KOSPI_TREND_RECOVERY: 1,
      KOSPI_VOLUME_CONFIRM: 1,
      KOSPI_CHASE_WARNING: 1,
      KOSPI_FX_ELASTICITY_DEVIATION: 2.5,
      KOSPI_FOREIGN_NET_20D: 1000,
    });
    const signals = computeSignals(raw, derived, defaultRegime, defaultProfile);
    const kospi = signals.find((s) => s.asset === 'KOSPI');
    expect(kospi?.explanation?.baseSignal).toBe('STRONG_BUY');
    expect(kospi?.signal).toBe('HOLD');
    expect(kospi?.explanation?.overrides.some((item) => item.includes('흐름 경고 HOLD 캡'))).toBe(true);
  });

  it('downgrades EMERGING only one notch on moderate DXY short-term strength', () => {
    const raw = makeRaw({ DXY: 98 });
    const derived = makeDerived({
      GLOBAL_M2_PROXY: 5,
      DXY_TREND: 1.7,
      REAL_YIELD_TREND: -0.1,
    });
    const signals = computeSignals(
      raw,
      derived,
      defaultRegime,
      { ...defaultProfile, manualInputs: { ...defaultProfile.manualInputs, policyDirection: 0 } },
    );
    const emerging = signals.find((s) => s.asset === 'EMERGING');
    expect(emerging?.explanation?.baseSignal).toBe('BUY');
    expect(emerging?.signal).toBe('HOLD');
    expect(emerging?.explanation?.overrides.some((item) => item.includes('한 단계 완화'))).toBe(true);
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

  it('marks cash defensive in STAGFLATION and BOND_VIGILANTE regimes', () => {
    const raw = makeRaw({});
    const derived = makeDerived({});

    const stagSignals = computeSignals(raw, derived, { ...defaultRegime, regime: 'STAGFLATION' }, defaultProfile);
    const bondSignals = computeSignals(raw, derived, { ...defaultRegime, regime: 'BOND_VIGILANTE' }, defaultProfile);

    expect(stagSignals.find((s) => s.asset === 'CASH')?.signal).toBe('BUY');
    expect(bondSignals.find((s) => s.asset === 'CASH')?.signal).toBe('STRONG_BUY');
  });

  it('respects includeKR and leverageEnabled profile toggles', () => {
    const raw = makeRaw({ VIXCLS: 40, ICSA: 250000, WTI: 70, USDKRW: 1450 });
    const derived = makeDerived({
      NASDAQ_DISPARITY: -27,
      KOSPI_ABOVE_200DMA: 0,
      KOSPI_DISPARITY: -20,
      KRW_FX_LEVEL: 1,
    });
    const profile = { ...defaultProfile, includeKR: false, leverageEnabled: false };
    const signals = computeSignals(raw, derived, defaultRegime, profile);

    expect(signals.find((s) => s.asset === 'KOSPI')?.reasons[0]).toContain('includeKR=false');
    expect(signals.find((s) => s.asset === 'LEVERAGE')?.reasons[0]).toContain('leverageEnabled=false');
    expect(signals.find((s) => s.asset === 'KOSPI')?.signal).toBe('HOLD');
    expect(signals.find((s) => s.asset === 'LEVERAGE')?.signal).toBe('HOLD');
  });
});
