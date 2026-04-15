import { MarketDataPoint, DerivedIndicator, RegimeState, Regime } from '../types/indicators';

interface ScoringInput {
  raw: Record<string, MarketDataPoint>;
  derived: Record<string, DerivedIndicator>;
  manualInputs?: {
    policyDirection: number;
    geoRisk: number;
  };
  smartMoneyScore?: number;
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

// TODO(Fix #FE3): VIX 구간 컷 {40, 30, 20, 15} 근거 재확인 필요.
//   - >40 = Panic (GFC/COVID 수준), >30 = Crisis, >20 = Elevated, >15 = 정상, ≤15 = 저변동.
//   - "15" 경계는 영상4 §VIX 구간 — 필요 시 영상 원문 인용으로 근거 보강.
//   - 현재 컷은 2008~2024 VIX 분포에서 대체로 합리적이나 백테스트 기반 sweep 은 미실시.
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

function scoreWTI(wti: number | null): number {
  if (wti === null) return 0;
  if (wti > 100) return -2;
  if (wti > 85) return -1;
  if (wti > 65) return 0;
  if (wti > 50) return 1;
  return 2;
}

function scoreLiquidityDirection(derived: Record<string, DerivedIndicator>): number {
  let score = 0;
  let count = 0;
  const rrp = derived.RRP_DIRECTION?.value;
  const tga = derived.TGA_DIRECTION?.value;
  const mmf = derived.MMF_DIRECTION?.value;
  const gm2 = derived.GLOBAL_M2_PROXY?.value;

  if (rrp !== undefined) { score += rrp < -1 ? 1 : rrp > 1 ? -1 : 0; count++; }
  if (tga !== undefined) { score += tga < -10000 ? 1 : tga > 10000 ? -1 : 0; count++; }
  if (mmf !== undefined) { score += mmf < -5 ? 1 : mmf > 5 ? -1 : 0; count++; }
  if (gm2 !== undefined) { score += gm2 > 1 ? 1 : gm2 < -1 ? -1 : 0; count++; }

  if (count === 0) return 0;
  const avg = score / count;
  if (avg >= 0.6) return 2;
  if (avg > 0) return 1;
  if (avg <= -0.6) return -2;
  if (avg < 0) return -1;
  return 0;
}

const WEIGHTS: Record<string, number> = {
  vix: 1.5,
  yieldCurve: 1.0,
  hySpread: 1.2,
  joblessClaims: 1.5,
  nasdaqDisparity: 1.0,
  finStress: 1.0,
  dxy: 0.8,
  liquidityDir: 1.0,
  wti: 0.6,
  globalM2: 0.7,
  smartMoney: 0.6,
  sectorMomentum: 0.8,
  policy: 0.5,
  geoRisk: 0.5,
};

export function classifyRegime(input: ScoringInput): RegimeState {
  const { raw, derived, manualInputs, smartMoneyScore } = input;

  const components: Record<string, number> = {
    vix: scoreVIX(v(raw, 'VIXCLS')),
    yieldCurve: scoreYieldCurve(v(raw, 'T10Y2Y')),
    hySpread: scoreHYSpread(v(raw, 'BAMLH0A0HYM2')),
    joblessClaims: scoreJoblessClaims(v(raw, 'ICSA')),
    nasdaqDisparity: scoreNasdaqVs200DMA(dv(derived, 'NASDAQ_DISPARITY')),
    finStress: scoreFinStress(v(raw, 'STLFSI4')),
    dxy: scoreDXYDirection(v(raw, 'DXY')),
    liquidityDir: scoreLiquidityDirection(derived),
    wti: scoreWTI(v(raw, 'WTI')),
    globalM2: (() => {
      const gm2 = dv(derived, 'GLOBAL_M2_PROXY');
      if (gm2 === null) return 0;
      if (gm2 > 3) return 2;
      if (gm2 > 1) return 1;
      if (gm2 > -1) return 0;
      if (gm2 > -3) return -1;
      return -2;
    })(),
    smartMoney: clamp(smartMoneyScore ?? 0, -2, 2),
    sectorMomentum: (() => {
      const xlk = dv(derived, 'SECTOR_XLK');
      const xli = dv(derived, 'SECTOR_XLI');
      const xlv = dv(derived, 'SECTOR_XLV');
      const xle = dv(derived, 'SECTOR_XLE');
      let score = 0;
      let count = 0;
      if (xlk !== null) { score += xlk > 0 ? 1 : -1; count++; }
      if (xli !== null) { score += xli > 0 ? 1 : -1; count++; }
      if (xlv !== null) { score += xlv > 0 ? -0.5 : 0.5; count++; }
      if (xle !== null) { score += xle > 0 ? -0.25 : 0.25; count++; }
      if (count === 0) return 0;
      const avg = score / count;
      if (avg >= 0.75) return 2;
      if (avg > 0.2) return 1;
      if (avg <= -0.75) return -2;
      if (avg < -0.2) return -1;
      return 0;
    })(),
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
  const overheated = dv(derived, 'OVERHEATED');
  const creditStress = dv(derived, 'CREDIT_STRESS_FLAG');
  const stagflation = dv(derived, 'STAGFLATION_WARNING');
  const bondVigilante = dv(derived, 'BOND_VIGILANTE_WARNING');
  let regime: Regime;

  // Fix #5: STAGFLATION / BOND_VIGILANTE 최우선 override.
  // 두 국면은 일반 score 분류보다 우선한다 — 플래그가 의미하는 구조적 신호는
  // 0~100 점수에 완전히 매핑되지 않기 때문. 영상4 §137-147 / §145.
  if (stagflation === 1) {
    regime = 'STAGFLATION';
  } else if (bondVigilante === 1) {
    regime = 'BOND_VIGILANTE';
  // TODO(Fix #FE3): score 컷 {75, 55, 40, 25} 근거 재확인.
  //   Monte Carlo sweep + PRD §레짐 전환 구간 크로스체크 미실시. 현 컷은 0~100 score 를
  //   5분위(80/60/40/20 근방)에 살짝 당겨 놓은 heuristic. 14개 component × 가중합 분포가
  //   실제로 어떤 밴드에 분포하는지 backtest 로 관측 후 다시 결정 필요.
  } else if (overheated === 1 && score >= 55) {
    regime = 'CAUTION';
  } else if (score >= 75) {
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

  // Fix #4: CREDIT_STRESS_FLAG 소비.
  // HY OAS ≥ 600bp 또는 HYG/IEF z ≤ -2 발동 시 신용시장 스트레스.
  // RISK_ON 은 위험을 감내하는 판단이므로 크레딧 경보가 울리면 NEUTRAL 로 강등.
  // NEUTRAL 이하는 이미 방어적 톤이므로 단순 강등 대신 최소 CAUTION 수준을 보장.
  // STAGFLATION / BOND_VIGILANTE 는 이미 방어적이므로 추가 강등하지 않는다.
  if (creditStress === 1) {
    if (regime === 'RISK_ON') {
      regime = 'NEUTRAL';
    } else if (regime === 'NEUTRAL') {
      regime = 'CAUTION';
    }
  }

  return {
    regime,
    score,
    components,
    date: new Date().toISOString().split('T')[0],
  };
}
