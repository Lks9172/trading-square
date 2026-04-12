import { MarketDataPoint, DerivedIndicator, RegimeState, Regime } from '../types/indicators';

interface ScoringInput {
  raw: Record<string, MarketDataPoint>;
  derived: Record<string, DerivedIndicator>;
  manualInputs?: {
    policyDirection: number;
    geoRisk: number;
  };
}

function v(raw: Record<string, MarketDataPoint>, key: string): number | null {
  return raw[key]?.value ?? null;
}

function dv(derived: Record<string, DerivedIndicator>, key: string): number | null {
  return derived[key]?.value ?? null;
}

function clamp(n: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, n));
}

function scoreVIX(vix: number | null): number {
  if (vix === null) return 0;
  if (vix > 40) return -2;
  if (vix > 30) return -1;
  if (vix > 20) return 0;
  if (vix > 15) return 1;
  return 2;
}

function scoreYieldCurve(spread: number | null): number {
  if (spread === null) return 0;
  if (spread < -0.5) return -2;
  if (spread < 0) return -1;
  if (spread < 0.5) return 0;
  if (spread < 1.5) return 1;
  return 2;
}

function scoreHYSpread(hy: number | null): number {
  if (hy === null) return 0;
  if (hy > 8) return -2;
  if (hy > 6) return -1;
  if (hy > 4) return 0;
  if (hy > 3) return 1;
  return 2;
}

function scoreJoblessClaims(icsa: number | null): number {
  if (icsa === null) return 0;
  if (icsa > 350000) return -2;
  if (icsa > 300000) return -1;
  if (icsa > 250000) return 0;
  if (icsa > 200000) return 1;
  return 2;
}

function scoreNasdaqVs200DMA(disparity: number | null): number {
  if (disparity === null) return 0;
  if (disparity < -25) return -2;
  if (disparity < -10) return -1;
  if (disparity < 0) return 0;
  if (disparity < 10) return 1;
  return 2;
}

function scoreFinStress(stlfsi: number | null): number {
  if (stlfsi === null) return 0;
  if (stlfsi > 3) return -2;
  if (stlfsi > 1) return -1;
  if (stlfsi > 0) return 0;
  if (stlfsi > -0.5) return 1;
  return 2;
}

function scoreDXYDirection(dxy: number | null): number {
  if (dxy === null) return 0;
  if (dxy > 108) return -2;
  if (dxy > 104) return -1;
  if (dxy > 100) return 0;
  if (dxy > 96) return 1;
  return 2;
}

const WEIGHTS: Record<string, number> = {
  vix: 1.5,
  yieldCurve: 1.0,
  hySpread: 1.2,
  joblessClaims: 1.5,
  nasdaqDisparity: 1.0,
  finStress: 1.0,
  dxy: 0.8,
  policy: 0.5,
  geoRisk: 0.5,
};

export function classifyRegime(input: ScoringInput): RegimeState {
  const { raw, derived, manualInputs } = input;

  const components: Record<string, number> = {
    vix: scoreVIX(v(raw, 'VIXCLS')),
    yieldCurve: scoreYieldCurve(v(raw, 'T10Y2Y')),
    hySpread: scoreHYSpread(v(raw, 'BAMLH0A0HYM2')),
    joblessClaims: scoreJoblessClaims(v(raw, 'ICSA')),
    nasdaqDisparity: scoreNasdaqVs200DMA(dv(derived, 'NASDAQ_DISPARITY')),
    finStress: scoreFinStress(v(raw, 'STLFSI4')),
    dxy: scoreDXYDirection(v(raw, 'DXY')),
    policy: clamp(manualInputs?.policyDirection ?? 0, -2, 2),
    geoRisk: clamp(2 - (manualInputs?.geoRisk ?? 2), -2, 2),
  };

  let weightedSum = 0;
  let totalWeight = 0;
  for (const [key, score] of Object.entries(components)) {
    const w = WEIGHTS[key] ?? 1;
    weightedSum += score * w;
    totalWeight += w;
  }

  const normalized = ((weightedSum / totalWeight + 2) / 4) * 100;
  const score = Math.round(clamp(normalized, 0, 100));

  const icsa = v(raw, 'ICSA');
  let regime: Regime;

  if (score >= 75) {
    regime = 'RISK_ON';
  } else if (score >= 55) {
    regime = 'NEUTRAL';
  } else if (score >= 40) {
    regime = 'CAUTION';
  } else if (score >= 25) {
    regime = 'CORRECTION';
  } else {
    regime = icsa !== null && icsa < 300000 ? 'PANIC_BUT_OK' : 'RECESSION_RISK';
  }

  return {
    regime,
    score,
    components,
    date: new Date().toISOString().split('T')[0],
  };
}
