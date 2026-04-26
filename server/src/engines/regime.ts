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
  // 12차 노션 정합 시도 후 롤백 (2026-04): 노션 "3.5/5-7/8" 임계로 조정했으나
  //   regime score 에서 HY weight 1.2 × 2→1 점수 하락이 RISK_ON(≥75) 경계에
  //   크게 영향 → sweep 과거 10년 RISK_ON 활성일 17→0 (과도한 보수화).
  //   기존 5단계 세분화가 실용적. 노션 경고는 NASDAQ signal 레벨로 별도 처리.
  if (hy === null) return 0;
  if (hy > 8) return -2;
  if (hy > 6) return -1;
  if (hy > 4) return 0;
  if (hy > 3) return 1;
  return 2;
}

// 14차 Phase A (2026-04): DGS10_TIER / UNRATE_TIER 의 regime 통합.
//   - DGS10: 노션 "5%/4%/3%" 장기금리 부담 정도 (기존 regime 에 독립 score 없었음)
//   - UNRATE: 실업률 4%/5%/6% (기존 scoreJoblessClaims 는 ICSA 주간 기반, UNRATE 월간은 독립)
// 둘 다 가중치 0.5 (policy/geoRisk 와 동등, 보조 시그널 레벨)
function scoreDGS10Level(dgs10: number | null): number {
  if (dgs10 === null) return 0;
  if (dgs10 >= 5) return -2; // 경계
  if (dgs10 >= 4) return -1; // 부담
  if (dgs10 >= 3) return 0;  // 중립
  return 1;                   // 저금리 (위험자산 우호)
}

function scoreUnrateLevel(unrate: number | null): number {
  if (unrate === null) return 0;
  if (unrate < 4) return 2;   // 완전 고용
  if (unrate < 5) return 1;   // 양호
  if (unrate < 6) return -1;  // 주의
  return -2;                   // 침체 우려
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
  const liquidityDir = derived.LIQUIDITY_DIRECTION?.value;
  if (liquidityDir !== undefined && liquidityDir !== null) {
    if (liquidityDir >= 2) return 2;
    if (liquidityDir >= 1) return 1;
    if (liquidityDir <= -2) return -2;
    if (liquidityDir <= -1) return -1;
    return 0;
  }

  let score = 0;
  let count = 0;
  const rrp = derived.RRP_DIRECTION?.value;
  const tga = derived.TGA_DIRECTION?.value;
  const mmf = derived.MMF_DIRECTION?.value;
  const wresbal = derived.WRESBAL_DIRECTION?.value;
  const gm2 = derived.GLOBAL_M2_PROXY?.value;

  if (rrp !== undefined) { score += rrp <= -1 ? 1 : rrp >= 1 ? -1 : 0; count++; }
  if (tga !== undefined) { score += tga <= -2 ? 1 : tga >= 2 ? -1 : 0; count++; }
  if (mmf !== undefined) { score += mmf <= -0.5 ? 1 : mmf >= 0.5 ? -1 : 0; count++; }
  if (wresbal !== undefined) { score += wresbal >= 1 ? 1 : wresbal <= -1 ? -1 : 0; count++; }
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
  // 14차 Phase A (노션 정합): DGS10 장기금리 레벨 / UNRATE 실업률 절대 레벨
  dgs10Level: 0.5,
  unrateLevel: 0.5,
};

export function classifyRegime(
  input: ScoringInput,
  options?: { includeExplanation?: boolean },
): RegimeState {
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
      // 15차 Phase 2-G (2026-04): 10 섹터로 확장. 공격적 섹터 (성장 우호 시 +1),
      // 방어 섹터 (성장 우호 시 -0.5, 방어 활성화 시 +0.5), 중립 0.
      const get = (k: string) => dv(derived, `SECTOR_${k}`);
      const xlk = get('XLK'); const xli = get('XLI'); const xly = get('XLY');
      const xlc = get('XLC'); const xlb = get('XLB'); const xlf = get('XLF');
      const xlv = get('XLV'); const xle = get('XLE'); const xlre = get('XLRE'); const xlu = get('XLU');
      let score = 0;
      let count = 0;
      // 공격적 (+1 / -1) — 성장 우호 = 위험자산 강세
      if (xlk !== null) { score += xlk > 0 ? 1 : -1; count++; }
      if (xli !== null) { score += xli > 0 ? 1 : -1; count++; }
      if (xly !== null) { score += xly > 0 ? 1 : -1; count++; }
      if (xlc !== null) { score += xlc > 0 ? 1 : -1; count++; }
      if (xlb !== null) { score += xlb > 0 ? 0.5 : -0.5; count++; }
      if (xlf !== null) { score += xlf > 0 ? 0.5 : -0.5; count++; }
      // 방어/역상관 (성장 우호 시 -0.5, 방어 강세 = 위험 신호)
      if (xlv !== null) { score += xlv > 0 ? -0.5 : 0.5; count++; }
      if (xle !== null) { score += xle > 0 ? -0.25 : 0.25; count++; }
      if (xlu !== null) { score += xlu > 0 ? -0.5 : 0.5; count++; }
      if (xlre !== null) { score += xlre > 0 ? 0.25 : -0.25; count++; }
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
    // 14차 Phase A: DGS10 / UNRATE 절대 레벨 통합
    dgs10Level: scoreDGS10Level(v(raw, 'DGS10')),
    unrateLevel: scoreUnrateLevel(v(raw, 'UNRATE')),
  };

  let weightedSum = 0;
  let totalWeight = 0;
  const weightedContributions: NonNullable<RegimeState['explanation']>['weightedContributions'] = {};
  for (const [key, score] of Object.entries(components)) {
    const w = WEIGHTS[key] ?? 1;
    weightedSum += score * w;
    totalWeight += w;
    weightedContributions[key] = {
      score,
      weight: w,
      weightedContribution: parseFloat((score * w).toFixed(2)),
    };
  }

  const normalized = ((weightedSum / totalWeight + 2) / 4) * 100;
  let score = Math.round(clamp(normalized, 0, 100));

  // ★ 29차 P1-A #2: DRAWDOWN_TYPE_CLASSIFIER SYSTEMIC_RISK 시 score 5점 패널티 (video1 §08:38).
  const ddTypeForScore = dv(derived, 'DRAWDOWN_TYPE_CLASSIFIER');
  if (ddTypeForScore !== null && ddTypeForScore <= -2) {
    score = Math.max(0, score - 5);
  }

  const icsa = v(raw, 'ICSA');
  const overheated = dv(derived, 'OVERHEATED');
  const creditStress = dv(derived, 'CREDIT_STRESS_FLAG');
  const stagflation = dv(derived, 'STAGFLATION_WARNING');
  const bondVigilante = dv(derived, 'BOND_VIGILANTE_WARNING');
  let regime: Regime;
  const overrides: string[] = [];

  // 13차 재검증 (2026-04): score 컷 {75, 55, 40, 25} heuristic 의 **sweep 기반 관측**.
  //   portfolio-sweep.ts 활성일 10년 스캔 결과 (HY 롤백 후):
  //     RISK_ON=17일, NEUTRAL=2097일, CAUTION=371일, CORRECTION=29일, PANIC/RECESSION=0일
  //   → NEUTRAL 이 과반 차지, 극단 레짐(PANIC/RECESSION)은 10년 데이터에 0일.
  //   영상 원문에 명시 수치 없음 (video4 §7가지 렌즈 는 정성적 개수만).
  //   현재 컷 유지 근거:
  //     1) STAGFLATION/BOND_VIGILANTE override 가 극단 이벤트 포착 (점수 무시)
  //     2) CAUTION 371일 ≈ 10년 중 15% = 영상 "역사적 조정 구간" 과 얼추 정합
  //     3) RISK_ON 75 컷은 7개 렌즈 동시 긍정 시만 통과 → "확신 깊이" 영상 정합
  //   추후 검토 여지:
  //     - RISK_ON 17일 (1%) 너무 희소하면 72 or 70 완화 검토
  //     - backtest sensitivity sweep 에 cutoff 가변 추가 (별도 작업)
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
  const preOverrideRegime = regime;

  // 23차 Tier 2#7 + 24차 Phase 2#16: STAGFLATION + BOND_VIGILANTE 동시 발동 시 합성 라벨.
  if (stagflation === 1 && bondVigilante === 1) {
    overrides.push('STAGFLATION+BOND_VIGILANTE 동시 -> 합성 라벨 STAGFLATION_BOND_VIGILANTE');
    regime = 'STAGFLATION_BOND_VIGILANTE';
  } else if (stagflation === 1) {
    overrides.push('STAGFLATION_WARNING=1 -> regime forced to STAGFLATION');
    regime = 'STAGFLATION';
  } else if (bondVigilante === 1) {
    overrides.push('BOND_VIGILANTE_WARNING=1 -> regime forced to BOND_VIGILANTE');
    regime = 'BOND_VIGILANTE';
  } else if (overheated === 1 && score >= 55) {
    overrides.push('OVERHEATED=1 and score>=55 -> regime forced to CAUTION');
    regime = 'CAUTION';
  }

  // 20차 E3: GOLDILOCKS_ZONE = -1 (recession 진입 가능성) AND score >= 55 인 경우
  // RISK_ON 으로 분류되면 안 된다. video4 §매크로 신호등 정합 — 빨간불 영역에서 RISK_ON 차단.
  const goldilocks = dv(derived, 'GOLDILOCKS_ZONE');
  if (goldilocks === -1 && (regime === 'RISK_ON' || regime === 'NEUTRAL')) {
    overrides.push('GOLDILOCKS_ZONE=-1 (recession 신호) -> 최소 CAUTION 강등');
    regime = 'CAUTION';
  }

  // ★ === 29차 P1-A #2: DRAWDOWN_TYPE_CLASSIFIER SYSTEMIC_RISK 강제 RECESSION_RISK ===
  // video1 §08:38 + video3 §17:50-18:31 "회복 수년 vs 빠른 반등 분기점".
  const ddType = dv(derived, 'DRAWDOWN_TYPE_CLASSIFIER');
  if (ddType !== null && ddType <= -2) {
    if (regime !== 'RECESSION_RISK' && regime !== 'STAGFLATION' && regime !== 'BOND_VIGILANTE' && regime !== 'STAGFLATION_BOND_VIGILANTE') {
      overrides.push('DRAWDOWN_TYPE_CLASSIFIER=SYSTEMIC_RISK -> regime forced to RECESSION_RISK (video1 §08:38 + video3 §17:50)');
      regime = 'RECESSION_RISK';
    }
  }

  // Fix #4: CREDIT_STRESS_FLAG 소비.
  // HY OAS ≥ 600bp 또는 HYG/IEF z ≤ -2 발동 시 신용시장 스트레스.
  // RISK_ON 은 위험을 감내하는 판단이므로 크레딧 경보가 울리면 NEUTRAL 로 강등.
  // NEUTRAL 이하는 이미 방어적 톤이므로 단순 강등 대신 최소 CAUTION 수준을 보장.
  // STAGFLATION / BOND_VIGILANTE 는 이미 방어적이므로 추가 강등하지 않는다.
  if (creditStress === 1) {
    if (regime === 'RISK_ON') {
      overrides.push('CREDIT_STRESS_FLAG=1 -> RISK_ON downgraded to NEUTRAL');
      regime = 'NEUTRAL';
    } else if (regime === 'NEUTRAL') {
      overrides.push('CREDIT_STRESS_FLAG=1 -> NEUTRAL downgraded to CAUTION');
      regime = 'CAUTION';
    }
  }

  const sortedDrivers = Object.entries(weightedContributions).sort(
    (a, b) => b[1].weightedContribution - a[1].weightedContribution,
  );
  const positiveDrivers = sortedDrivers
    .filter(([, value]) => value.weightedContribution > 0)
    .slice(0, 4)
    .map(([component, value]) => ({ component, ...value }));
  const negativeDrivers = [...sortedDrivers]
    .reverse()
    .filter(([, value]) => value.weightedContribution < 0)
    .slice(0, 4)
    .map(([component, value]) => ({ component, ...value }));

  return {
    regime,
    score,
    components,
    date: new Date().toISOString().split('T')[0],
    explanation: options?.includeExplanation ? {
      preOverrideRegime,
      overrides,
      positiveDrivers,
      negativeDrivers,
      weightedContributions,
    } : undefined,
  };
}
