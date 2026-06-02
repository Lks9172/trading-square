import { computeSectorQuality } from '../services/sector-quality';
import { getSectorDefinition } from '../engines/sector-classification';
import { DerivedIndicator, MarketDataPoint, RegimeState } from '../types/indicators';

function makeRaw(overrides: Record<string, number> = {}): Record<string, MarketDataPoint> {
  const merged = { DXY: 101, WTI: 72, ...overrides };
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

function makeRegime(regime: RegimeState['regime'], geoRisk = 0): RegimeState {
  return {
    regime,
    score: 60,
    date: '2026-01-01',
    components: { geoRisk },
  };
}

describe('sector-quality', () => {
  it('assigns high structural demand to semiconductor/AI proxy sectors', () => {
    const sector = getSectorDefinition('SECTOR_SOXX');
    expect(sector).not.toBeNull();

    const quality = computeSectorQuality(
      sector!,
      makeRaw(),
      makeDerived({ LIQUIDITY_DIRECTION: 2, HELIUM_AI_BOTTLENECK: 1, POLICY_SECTOR_LIFT_PCT: 8 }),
      makeRegime('RISK_ON'),
    );

    expect(quality.structuralDemand).toBeGreaterThanOrEqual(80);
    expect(quality.supplyTightness).toBeGreaterThanOrEqual(80);
    expect(quality.totalScore).toBeGreaterThanOrEqual(70);
  });

  it('differentiates energy and utilities policy regimes', () => {
    const energy = getSectorDefinition('SECTOR_XLE');
    const utilities = getSectorDefinition('SECTOR_XLU');
    expect(energy).not.toBeNull();
    expect(utilities).not.toBeNull();

    const energyQuality = computeSectorQuality(
      energy!,
      makeRaw({ DXY: 105, WTI: 84 }),
      makeDerived({ LIQUIDITY_DIRECTION: 0 }),
      makeRegime('CAUTION', -1),
    );
    const utilityQuality = computeSectorQuality(
      utilities!,
      makeRaw({ DXY: 106, WTI: 70 }),
      makeDerived({ LIQUIDITY_DIRECTION: -1, HELIUM_AI_BOTTLENECK: 1 }),
      makeRegime('RECESSION_RISK'),
    );

    expect(energyQuality.policySupport).toBeGreaterThanOrEqual(60);
    expect(energyQuality.supplyTightness).toBeGreaterThanOrEqual(80);
    expect(utilityQuality.structuralDemand).toBeGreaterThanOrEqual(65);
    expect(utilityQuality.totalScore).toBeGreaterThanOrEqual(60);
  });

  it('scores defense and power proxies with structural support', () => {
    const defense = getSectorDefinition('SECTOR_ITA');
    const power = getSectorDefinition('SECTOR_GRID');
    expect(defense).not.toBeNull();
    expect(power).not.toBeNull();

    const defenseQuality = computeSectorQuality(
      defense!,
      makeRaw({ DXY: 104, WTI: 82 }),
      makeDerived({ POLICY_SECTOR_LIFT_PCT: 6 }),
      makeRegime('CAUTION', -1),
    );
    const powerQuality = computeSectorQuality(
      power!,
      makeRaw({ DXY: 101, WTI: 72 }),
      makeDerived({ LIQUIDITY_DIRECTION: 2, HELIUM_AI_BOTTLENECK: 1, POLICY_SECTOR_LIFT_PCT: 6 }),
      makeRegime('RISK_ON'),
    );

    expect(defenseQuality.policySupport).toBeGreaterThanOrEqual(60);
    expect(defenseQuality.structuralDemand).toBeGreaterThanOrEqual(70);
    expect(powerQuality.structuralDemand).toBeGreaterThanOrEqual(80);
    expect(powerQuality.totalScore).toBeGreaterThanOrEqual(70);
  });

  it('boosts defensive utilities quality in risk-off regimes', () => {
    const sector = getSectorDefinition('SECTOR_XLU');
    expect(sector).not.toBeNull();

    const quality = computeSectorQuality(
      sector!,
      makeRaw({ DXY: 107 }),
      makeDerived({ LIQUIDITY_DIRECTION: -1 }),
      makeRegime('RECESSION_RISK'),
    );

    expect(quality.policySupport).toBeGreaterThanOrEqual(60);
    expect(quality.totalScore).toBeGreaterThanOrEqual(55);
  });
});
