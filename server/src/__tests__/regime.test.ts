import { classifyRegime } from '../engines/regime';
import { MarketDataPoint, DerivedIndicator } from '../types/indicators';

function makeRaw(overrides: Record<string, number>): Record<string, MarketDataPoint> {
  const defaults: Record<string, number> = {
    VIXCLS: 20, T10Y2Y: 0.5, BAMLH0A0HYM2: 4, ICSA: 220000,
    STLFSI4: 0, DXY: 100, WTI: 70,
    // 14차 Phase A: DGS10 / UNRATE 기본값 (부담 / 양호 상쇄, score net 0).
    DGS10: 4.2, UNRATE: 4.3,
  };
  const merged = { ...defaults, ...overrides };
  const raw: Record<string, MarketDataPoint> = {};
  for (const [k, v] of Object.entries(merged)) {
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

describe('classifyRegime', () => {
  it('returns RISK_ON when all indicators positive', () => {
    const raw = makeRaw({ VIXCLS: 12, T10Y2Y: 2, BAMLH0A0HYM2: 2.5, ICSA: 190000, STLFSI4: -1, DXY: 94, WTI: 55 });
    const derived = makeDerived({ NASDAQ_DISPARITY: 5, RRP_DIRECTION: -5, TGA_DIRECTION: -50000, MMF_DIRECTION: -10, GLOBAL_M2_PROXY: 3, SECTOR_XLK: 5, SECTOR_XLI: 3, SECTOR_XLV: -1, SECTOR_XLE: -1 });
    const result = classifyRegime({ raw, derived, manualInputs: { policyDirection: 2, geoRisk: 0 }, smartMoneyScore: 2 });
    expect(result.regime).toBe('RISK_ON');
    expect(result.score).toBeGreaterThanOrEqual(75);
  });

  it('returns RECESSION_RISK when jobless claims high and score low', () => {
    const raw = makeRaw({ VIXCLS: 45, T10Y2Y: -1, BAMLH0A0HYM2: 9, ICSA: 400000, STLFSI4: 4, DXY: 110, WTI: 110 });
    const derived = makeDerived({ NASDAQ_DISPARITY: -30 });
    const result = classifyRegime({ raw, derived, manualInputs: { policyDirection: -2, geoRisk: 4 }, smartMoneyScore: -2 });
    expect(result.regime).toBe('RECESSION_RISK');
    expect(result.score).toBeLessThan(25);
  });

  it('returns PANIC_BUT_OK when score low but jobless claims under 300K', () => {
    // 14차 Phase A: DGS10 경계 + UNRATE 침체 override 로 score 충분히 음수화
    const raw = makeRaw({ VIXCLS: 42, T10Y2Y: -0.8, BAMLH0A0HYM2: 8, ICSA: 250000, STLFSI4: 3, DXY: 109, WTI: 105, DGS10: 5.5, UNRATE: 6.5 });
    const derived = makeDerived({ NASDAQ_DISPARITY: -28 });
    const result = classifyRegime({ raw, derived, manualInputs: { policyDirection: -2, geoRisk: 4 }, smartMoneyScore: -2 });
    expect(result.regime).toBe('PANIC_BUT_OK');
  });

  it('forces CAUTION when overheated even if score is high', () => {
    const raw = makeRaw({ VIXCLS: 12, T10Y2Y: 2, BAMLH0A0HYM2: 2.5, ICSA: 190000, STLFSI4: -1, DXY: 94, WTI: 55 });
    const derived = makeDerived({ NASDAQ_DISPARITY: 25, OVERHEATED: 1 });
    const result = classifyRegime({ raw, derived, manualInputs: { policyDirection: 2, geoRisk: 0 }, smartMoneyScore: 2 });
    expect(result.regime).toBe('CAUTION');
  });

  it('includes sectorMomentum and smartMoney components', () => {
    const raw = makeRaw({});
    const derived = makeDerived({ SECTOR_XLK: 5, SECTOR_XLI: 3, SECTOR_XLV: -2, SECTOR_XLE: -1 });
    const result = classifyRegime({ raw, derived, smartMoneyScore: 1 });
    expect(result.components).toHaveProperty('sectorMomentum');
    expect(result.components).toHaveProperty('smartMoney');
    expect(result.components.smartMoney).toBe(1);
  });

  it('prefers smoothed LIQUIDITY_DIRECTION when present', () => {
    const raw = makeRaw({ VIXCLS: 18, T10Y2Y: 1.5, BAMLH0A0HYM2: 3.2, ICSA: 210000, STLFSI4: -0.5, DXY: 98, WTI: 62 });
    const derived = makeDerived({
      NASDAQ_DISPARITY: 4,
      LIQUIDITY_DIRECTION: 3,
      RRP_DIRECTION: 0,
      TGA_DIRECTION: 0,
      MMF_DIRECTION: 0,
      GLOBAL_M2_PROXY: 0,
    });
    const result = classifyRegime({ raw, derived, manualInputs: { policyDirection: 1, geoRisk: 1 }, smartMoneyScore: 1 });
    expect(result.components.liquidityDir).toBe(2);
  });

  it('returns explanation with pre-override regime and override reasons when requested', () => {
    const raw = makeRaw({ VIXCLS: 12, T10Y2Y: 2, BAMLH0A0HYM2: 2.5, ICSA: 190000, STLFSI4: -1, DXY: 94, WTI: 55 });
    const derived = makeDerived({ NASDAQ_DISPARITY: 5, OVERHEATED: 1 });
    const result = classifyRegime(
      { raw, derived, manualInputs: { policyDirection: 2, geoRisk: 0 }, smartMoneyScore: 2 },
      { includeExplanation: true },
    );
    expect(result.explanation?.preOverrideRegime).toBe('RISK_ON');
    expect(result.explanation?.overrides).toContain('OVERHEATED=1 and score>=55 -> regime forced to CAUTION');
    expect(result.explanation?.positiveDrivers.length).toBeGreaterThan(0);
  });
});
