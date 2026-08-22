import {
  DerivedIndicator,
  MarketDataPoint,
  RegimeState,
  SectorRotationItem,
  SectorRotationHorizon,
  SectorRotationOutlookBucket,
  SectorRotationRegime,
  SectorRotationState,
  TopDownSectorView,
  TopDownView,
} from '../types/indicators';
import { NarrativeThemeState } from '../types/narrative';

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function rawValue(raw: Record<string, MarketDataPoint>, key: string): number | null {
  return raw[key]?.value ?? null;
}

function derivedValue(derived: Record<string, DerivedIndicator>, key: string): number | null {
  return derived[key]?.value ?? null;
}

function normalizeMomentum(score: number | null): number {
  if (score === null || Number.isNaN(score)) return 50;
  return Math.round(clamp(50 + score * 4.2, 5, 95));
}

function positiveScore(value: number | null, goodMin: number, goodMax: number): number {
  if (value === null || Number.isNaN(value)) return 50;
  if (value <= goodMin) return 0;
  if (value >= goodMax) return 100;
  return ((value - goodMin) / (goodMax - goodMin)) * 100;
}

function negativeScore(value: number | null, badMin: number, badMax: number): number {
  if (value === null || Number.isNaN(value)) return 50;
  if (value <= badMin) return 100;
  if (value >= badMax) return 0;
  return 100 - (((value - badMin) / (badMax - badMin)) * 100);
}

function regimeFlag(regime: RegimeState['regime'], targets: RegimeState['regime'][]): number {
  return targets.includes(regime) ? 100 : 0;
}

export function computeRotationRegimeScores(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  regime: RegimeState,
): Record<SectorRotationRegime, number> {
  const liquidity = derivedValue(derived, 'LIQUIDITY_DIRECTION');
  const realYield = derivedValue(derived, 'REAL_YIELD');
  const curve = rawValue(raw, 'T10Y2Y');
  const wti = rawValue(raw, 'WTI');
  const dxy = rawValue(raw, 'DXY');
  const stlfsi = rawValue(raw, 'STLFSI4');
  const hyOas = rawValue(raw, 'BAMLH0A0HYM2');
  const overheated = (derivedValue(derived, 'OVERHEATED') ?? 0) === 1 ? 100 : 0;
  const copperGoldUpturn = (derivedValue(derived, 'COPPER_GOLD_RATIO_UPTURN') ?? 0) === 1 ? 100 : 0;
  const riskOff = regimeFlag(regime.regime, ['CORRECTION', 'RECESSION_RISK', 'PANIC_BUT_OK', 'BOND_VIGILANTE']);
  const stagflation = regimeFlag(regime.regime, ['STAGFLATION', 'STAGFLATION_BOND_VIGILANTE']);

  const liquidityBull = positiveScore(liquidity, -1, 2);
  const curveSteep = positiveScore(curve, -0.15, 0.45);
  const curveFlat = 100 - Math.abs(clamp((curve ?? 0) * 180, -100, 100));
  const lowRealYield = negativeScore(realYield, 1.2, 2.5);
  const highRealYield = positiveScore(realYield, 1.6, 2.8);
  const benignDollar = negativeScore(dxy, 101.5, 106);
  const inflationHeat = positiveScore(wti, 72, 88);
  const moderateYield = 100 - Math.min(100, Math.abs((realYield ?? 2) - 1.9) * 75);
  const financialEase = clamp(
    (stlfsi === null ? 50 : negativeScore(stlfsi, -0.35, 1.1)) * 0.45
      + (hyOas === null ? 50 : negativeScore(hyOas, 3.3, 6.5)) * 0.55,
    0,
    100,
  );
  const financialStress = 100 - financialEase;

  const early = clamp(
    liquidityBull * 0.24
      + curveSteep * 0.22
      + lowRealYield * 0.18
      + benignDollar * 0.12
      + (100 - inflationHeat) * 0.12
      + financialEase * 0.08
      + (100 - overheated) * 0.06
      + (100 - riskOff) * 0.02,
    0,
    100,
  );

  const mid = clamp(
    liquidityBull * 0.22
      + moderateYield * 0.2
      + benignDollar * 0.14
      + curveFlat * 0.14
      + (100 - overheated) * 0.1
      + (100 - riskOff) * 0.08
      + (100 - inflationHeat) * 0.06
      + financialEase * 0.06,
    0,
    100,
  );

  const lateInflation = clamp(
    inflationHeat * 0.28
      + highRealYield * 0.18
      + curveFlat * 0.12
      + overheated * 0.14
      + stagflation * 0.18
      + financialStress * 0.06
      + (100 - liquidityBull) * 0.1,
    0,
    100,
  );

  const defensive = clamp(
    riskOff * 0.28
      + highRealYield * 0.18
      + (100 - liquidityBull) * 0.16
      + financialStress * 0.16
      + positiveScore(-(curve ?? 0), -0.15, 0.55) * 0.14
      + stagflation * 0.08,
    0,
    100,
  );

  const reAcceleration = clamp(
    liquidityBull * 0.22
      + copperGoldUpturn * 0.2
      + curveSteep * 0.16
      + benignDollar * 0.12
      + moderateYield * 0.12
      + (100 - riskOff) * 0.08
      + financialEase * 0.14
      + (100 - inflationHeat) * 0.08,
    0,
    100,
  );

  return {
    EARLY_CYCLICAL: Math.round(early),
    MID_GROWTH: Math.round(mid),
    LATE_INFLATION: Math.round(lateInflation),
    DEFENSIVE: Math.round(defensive),
    RE_ACCELERATION: Math.round(reAcceleration),
  };
}

export function inferRotationRegime(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  regime: RegimeState,
): { regime: SectorRotationRegime; confidence: number; regimeScores: Record<SectorRotationRegime, number> } {
  const regimeScores = computeRotationRegimeScores(raw, derived, regime);
  const ranked = (Object.entries(regimeScores) as Array<[SectorRotationRegime, number]>).sort((a, b) => b[1] - a[1]);
  const selected = ranked[0][0];
  const confidence = Math.max(5, Math.min(95, ranked[0][1] - (ranked[1]?.[1] ?? 0) + 50));
  return { regime: selected, confidence, regimeScores };
}

export function computeSectorMacroFitScore(
  key: string,
  rotationRegime: SectorRotationRegime,
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
): number {
  const realYield = derivedValue(derived, 'REAL_YIELD');
  const curve = rawValue(raw, 'T10Y2Y');
  const lowRealYield = realYield !== null && realYield <= 2.15;
  const steepCurve = curve !== null && curve >= 0.05;
  const xlreRateSensitive = lowRealYield || steepCurve;

  const scoreTable: Record<SectorRotationRegime, Record<string, number>> = {
    EARLY_CYCLICAL: {
      SECTOR_XLY: 88,
      SECTOR_XLF: 85,
      SECTOR_XLRE: xlreRateSensitive ? 82 : 58,
      SECTOR_XLI: 82,
      SECTOR_XLK: 80,
      SECTOR_XLB: 76,
      SECTOR_SOXX: 72,
      SECTOR_SMH: 71,
      SECTOR_XLC: 68,
      SECTOR_IGF: 77,
      SECTOR_GRID: 64,
      SECTOR_XLE: 56,
      SECTOR_XLV: 42,
      SECTOR_XLU: 38,
      SECTOR_XLP: 35,
      SECTOR_ITA: 60,
    },
    MID_GROWTH: {
      SECTOR_XLK: 87,
      SECTOR_SOXX: 86,
      SECTOR_SMH: 85,
      SECTOR_XLC: 79,
      SECTOR_XLI: 69,
      SECTOR_XLF: 66,
      SECTOR_XLY: 65,
      SECTOR_XLRE: xlreRateSensitive ? 64 : 52,
      SECTOR_IGF: 64,
      SECTOR_GRID: 70,
      SECTOR_XLB: 51,
      SECTOR_XLE: 48,
      SECTOR_XLV: 45,
      SECTOR_XLU: 40,
      SECTOR_XLP: 39,
      SECTOR_ITA: 61,
    },
    LATE_INFLATION: {
      SECTOR_XLE: 89,
      SECTOR_XLP: 77,
      SECTOR_XLU: 76,
      SECTOR_XLV: 72,
      SECTOR_XLB: 69,
      SECTOR_ITA: 67,
      SECTOR_XLF: 58,
      SECTOR_XLI: 57,
      SECTOR_IGF: 54,
      SECTOR_XLRE: xlreRateSensitive ? 46 : 38,
      SECTOR_GRID: 60,
      SECTOR_XLK: 40,
      SECTOR_SOXX: 38,
      SECTOR_SMH: 37,
      SECTOR_XLC: 42,
      SECTOR_XLY: 35,
    },
    DEFENSIVE: {
      SECTOR_XLP: 89,
      SECTOR_XLU: 87,
      SECTOR_XLV: 83,
      SECTOR_XLRE: xlreRateSensitive ? 72 : 60,
      SECTOR_ITA: 66,
      SECTOR_XLE: 54,
      SECTOR_GRID: 58,
      SECTOR_XLC: 46,
      SECTOR_XLK: 42,
      SECTOR_SOXX: 39,
      SECTOR_SMH: 38,
      SECTOR_XLF: 34,
      SECTOR_XLI: 36,
      SECTOR_XLY: 33,
      SECTOR_XLB: 34,
      SECTOR_IGF: 41,
    },
    RE_ACCELERATION: {
      SECTOR_XLI: 85,
      SECTOR_XLF: 83,
      SECTOR_XLB: 81,
      SECTOR_IGF: 80,
      SECTOR_XLK: 75,
      SECTOR_XLY: 73,
      SECTOR_XLRE: xlreRateSensitive ? 69 : 55,
      SECTOR_GRID: 69,
      SECTOR_SOXX: 72,
      SECTOR_SMH: 71,
      SECTOR_XLC: 67,
      SECTOR_XLE: 60,
      SECTOR_XLV: 46,
      SECTOR_XLU: 44,
      SECTOR_XLP: 43,
      SECTOR_ITA: 64,
    },
  };

  return scoreTable[rotationRegime][key] ?? 55;
}

function buildRotationReasons(
  sector: TopDownSectorView,
  item: SectorRotationItem,
  rotationRegime: SectorRotationRegime,
): string[] {
  const reasons: string[] = [];
  if (item.state === 'IMPROVING') reasons.push('거시 정합과 중기 상대강도가 함께 개선되는 순환 후보입니다.');
  if (item.state === 'LEADING') reasons.push('현재 국면에서 이미 리더십이 확인된 섹터입니다.');
  if (item.state === 'WEAKENING') reasons.push('강했던 섹터지만 과열 또는 후행 피로가 누적되는 구간입니다.');
  if (item.state === 'LAGGING') reasons.push('현 국면 대비 우선순위가 아직 낮습니다.');
  if (item.macroFitScore >= 78) reasons.push(`현재 거시 국면(${rotationRegime})과 정합도가 높습니다.`);
  if ((sector.buyScore ?? 0) >= 70) reasons.push(`B 점수 ${sector.buyScore}로 섹터 체력은 양호합니다.`);
  if ((sector.crowdingScore ?? 0) >= 70) reasons.push(`과열 ${sector.crowdingScore}로 추격보다 눌림 확인이 우선입니다.`);
  else if ((sector.crowdingScore ?? 0) <= 45) reasons.push(`과열 ${sector.crowdingScore}로 혼잡도 부담은 낮은 편입니다.`);
  if ((sector.score ?? 0) > 0) reasons.push(`중기 상대강도 ${sector.score?.toFixed(1)}%가 플러스입니다.`);
  else if ((sector.score ?? 0) < 0) reasons.push(`중기 상대강도 ${sector.score?.toFixed(1)}%로 아직 약합니다.`);
  if ((sector.shortTermScore ?? 0) >= 4) reasons.push(`단기 1개월 탄력 ${sector.shortTermScore?.toFixed(1)}%로 추세 확인이 붙고 있습니다.`);
  else if ((sector.shortTermScore ?? 0) <= -4) reasons.push(`단기 1개월 탄력 ${sector.shortTermScore?.toFixed(1)}%로 아직 재가속 확인이 부족합니다.`);
  return reasons.slice(0, 3);
}

function computeSectorFlowScore(
  key: string,
  derived: Record<string, DerivedIndicator>,
  sector: TopDownSectorView,
): number | null {
  const scale = (value: number | null) => {
    if (value === null || Number.isNaN(value)) return null;
    return Math.round(clamp(50 + value * 18, 10, 90));
  };
  if (['SECTOR_XLK', 'SECTOR_SOXX', 'SECTOR_SMH', 'SECTOR_XLC', 'SECTOR_GRID'].includes(key)) {
    return scale(derivedValue(derived, 'INSTITUTIONAL_SECTOR_TECH_FLOW'));
  }
  if (key === 'SECTOR_XLF') {
    return scale(derivedValue(derived, 'INSTITUTIONAL_SECTOR_FIN_FLOW'));
  }
  if (key === 'SECTOR_XLE') {
    return scale(derivedValue(derived, 'INSTITUTIONAL_SECTOR_ENERGY_FLOW'));
  }
  const liquidity = derivedValue(derived, 'LIQUIDITY_DIRECTION') ?? 0;
  const hyOasBp = derivedValue(derived, 'CREDIT_HY_OAS_BP');
  const creditTight = hyOasBp === null ? 0 : clamp((450 - hyOasBp) / 60, -2, 2);
  const defensiveKeys = ['SECTOR_XLP', 'SECTOR_XLU', 'SECTOR_XLV', 'SECTOR_XLRE'];
  const cyclicalKeys = ['SECTOR_XLI', 'SECTOR_XLB', 'SECTOR_XLY', 'SECTOR_IGF', 'SECTOR_ITA'];
  const styleFlow = defensiveKeys.includes(key)
    ? clamp(0.4 - liquidity * 0.22 - creditTight * 0.24, -2, 2)
    : cyclicalKeys.includes(key)
      ? clamp(liquidity * 0.32 + creditTight * 0.3 + ((sector.shortTermScore ?? 0) / 10), -2, 2)
      : clamp(liquidity * 0.22 + creditTight * 0.18, -2, 2);
  return scale(styleFlow);
}

function computeSectorFinancialConditionsScore(key: string, derived: Record<string, DerivedIndicator>): number {
  const hyOasBp = derivedValue(derived, 'CREDIT_HY_OAS_BP');
  const realYield = derivedValue(derived, 'REAL_YIELD');
  const liquidity = derivedValue(derived, 'LIQUIDITY_DIRECTION');
  const baseCredit = hyOasBp === null ? 50 : clamp(100 - ((hyOasBp - 300) / 4.5), 5, 95);
  const baseRate = realYield === null ? 50 : clamp(80 - ((realYield - 1.5) * 26), 5, 95);
  const baseLiquidity = liquidity === null ? 50 : clamp(50 + liquidity * 12, 5, 95);
  if (['SECTOR_XLP', 'SECTOR_XLU', 'SECTOR_XLV'].includes(key)) {
    return Math.round(clamp((100 - baseCredit) * 0.35 + baseRate * 0.35 + (100 - baseLiquidity) * 0.3, 0, 100));
  }
  if (['SECTOR_XLK', 'SECTOR_XLY', 'SECTOR_XLRE'].includes(key)) {
    return Math.round(clamp(baseRate * 0.45 + baseLiquidity * 0.3 + baseCredit * 0.25, 0, 100));
  }
  if (['SECTOR_XLF', 'SECTOR_XLI', 'SECTOR_XLB', 'SECTOR_IGF', 'SECTOR_ITA'].includes(key)) {
    return Math.round(clamp(baseCredit * 0.45 + baseLiquidity * 0.35 + baseRate * 0.2, 0, 100));
  }
  return Math.round(clamp(baseCredit * 0.4 + baseRate * 0.3 + baseLiquidity * 0.3, 0, 100));
}

function computeExpectedLeadershipWindow(
  item: Pick<SectorRotationItem, 'rotationScore' | 'state' | 'rotationLabel' | 'relativeStrengthScore' | 'crowdingReliefScore'>,
): { window: SectorRotationHorizon; message: string } {
  if (item.state === 'LEADING') {
    if (item.relativeStrengthScore >= 80) return { window: 'now', message: '이미 주도 구간 — 지금~3개월 내 리더 유지 여부를 보는 단계' };
    return { window: '1_3m', message: '주도 초입 — 1~3개월 내 리더십 고착 여부를 확인' };
  }
  if (item.state === 'IMPROVING') {
    if (item.rotationScore >= 70 && item.relativeStrengthScore >= 55) {
      return { window: '1_3m', message: '1~3개월 내 주도 편입 가능성이 높은 후보' };
    }
    if (item.rotationScore >= 63) {
      return { window: '3_6m', message: '3~6개월 내 주도 2차 확산 후보' };
    }
    return { window: '6m_plus', message: '주도 전환까지는 아직 시간이 더 필요한 후보' };
  }
  if (item.rotationLabel === 'Late Leader' || item.state === 'WEAKENING') {
    return { window: 'unclear', message: '과열/후행 피로 구간 — 다음 주도보다는 약화 위험을 먼저 봐야 함' };
  }
  if (item.rotationScore >= 58 && item.crowdingReliefScore >= 70) {
    return { window: '6m_plus', message: '당장 주도는 아니지만 다음 사이클 대기 후보' };
  }
  return { window: 'unclear', message: '현재는 주도 전환 가시성이 낮음' };
}

function buildOutlookBucket(item: SectorRotationItem): SectorRotationOutlookBucket {
  return {
    label: item.label,
    sectorKey: item.key,
    rotationScore: item.rotationScore,
    state: item.state,
    rotationLabel: item.rotationLabel,
    expectedLeadershipWindow: item.expectedLeadershipWindow ?? 'unclear',
    expectedLeadershipMessage: item.expectedLeadershipMessage ?? '가시성 낮음',
    note: item.reasons[0] ?? '',
  };
}

export function buildSectorRotationView(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  regime: RegimeState,
  sectorViews: TopDownSectorView[],
  narratives: NarrativeThemeState[] = [],
): TopDownView['rotation'] {
  const { regime: rotationRegime, confidence, regimeScores } = inferRotationRegime(raw, derived, regime);
  const overheated = (derivedValue(derived, 'OVERHEATED') ?? 0) === 1;

  const sectors: SectorRotationItem[] = sectorViews.map((sector) => {
    const momentum = normalizeMomentum(sector.score ?? null);
    const macroFitScore = computeSectorMacroFitScore(sector.key, rotationRegime, raw, derived);
    let fundamentalScore = Math.round(clamp(
      ((sector.quality?.totalScore ?? 50) * 0.5)
        + ((sector.appealScore ?? 50) * 0.2)
        + ((sector.valuationScore ?? 50) * 0.15)
        + ((sector.earningsRevisionScore ?? 50) * 0.15),
      0,
      100,
    ));
    const earningsRevisionScore = sector.earningsRevisionScore ?? 50;
    const financialConditionsScore = computeSectorFinancialConditionsScore(sector.key, derived);
    const flowScore = computeSectorFlowScore(sector.key, derived, sector);
    const crowdingReliefScore = Math.round(clamp(100 - (sector.crowdingScore ?? 50), 0, 100));

    const aiNarrative = narratives.find((item) => item.theme.id === 'ai-power');
    const energyNarrative = narratives.find((item) => item.theme.id === 'oil-supply');
    const defenseNarrative = narratives.find((item) => item.theme.id === 'defense-rearm');
    if (['SECTOR_XLK', 'SECTOR_SOXX', 'SECTOR_SMH', 'SECTOR_GRID'].includes(sector.key) && aiNarrative) {
      fundamentalScore = Math.round(clamp(fundamentalScore + clamp((aiNarrative.heatScore - 52) * 0.06, -3, 4), 0, 100));
    }
    if (sector.key === 'SECTOR_XLE' && energyNarrative) {
      fundamentalScore = Math.round(clamp(fundamentalScore + clamp((energyNarrative.heatScore - 50) * 0.05, -2, 3), 0, 100));
    }
    if (sector.key === 'SECTOR_ITA' && defenseNarrative) {
      fundamentalScore = Math.round(clamp(fundamentalScore + clamp((defenseNarrative.heatScore - 50) * 0.04, -2, 2), 0, 100));
    }

    const rotationScore = Math.round(clamp(
      macroFitScore * 0.3
        + momentum * 0.24
        + fundamentalScore * 0.18
        + earningsRevisionScore * 0.12
        + financialConditionsScore * 0.08
        + crowdingReliefScore * 0.04
        + ((flowScore ?? 50) * 0.04)
        - (overheated && (sector.crowdingScore ?? 0) >= 70 ? 8 : 0),
      0,
      100,
    ));

    let state: SectorRotationState = 'LAGGING';
    if (rotationScore >= 76 && momentum >= 60 && (sector.crowdingScore ?? 0) < 72) state = 'LEADING';
    else if (rotationScore >= 66 && momentum >= 50 && macroFitScore >= 68) state = 'IMPROVING';
    else if (momentum >= 58 && (sector.crowdingScore ?? 0) >= 68) state = 'WEAKENING';
    else if (rotationScore >= 60 && macroFitScore >= 74 && (sector.crowdingScore ?? 0) < 65) state = 'IMPROVING';

    let rotationLabel: SectorRotationItem['rotationLabel'] = 'Rotation Out';
    if (state === 'LEADING') rotationLabel = (sector.crowdingScore ?? 0) >= 68 ? 'Late Leader' : 'Leader';
    else if (state === 'IMPROVING') rotationLabel = 'Rotation In';
    else if (state === 'WEAKENING') rotationLabel = 'Late Leader';
    else if (rotationRegime === 'DEFENSIVE' && ['SECTOR_XLV', 'SECTOR_XLU', 'SECTOR_XLP', 'SECTOR_XLRE'].includes(sector.key)) rotationLabel = 'Defensive Hold';

    const item: SectorRotationItem = {
      key: sector.key,
      label: sector.label,
      classification: sector.classification,
      rotationScore,
      macroFitScore,
      relativeStrengthScore: momentum,
      fundamentalScore,
      valuationScore: sector.valuationScore ?? null,
      earningsRevisionScore,
      flowScore,
      crowdingReliefScore,
      state,
      rotationLabel,
      ...(() => {
        const outlook = computeExpectedLeadershipWindow({
          rotationScore,
          state,
          rotationLabel,
          relativeStrengthScore: momentum,
          crowdingReliefScore,
        });
        return {
          expectedLeadershipWindow: outlook.window,
          expectedLeadershipMessage: outlook.message,
        };
      })(),
      reasons: [],
    };
    item.reasons = buildRotationReasons(sector, item, rotationRegime);
    return item;
  }).sort((a, b) => b.rotationScore - a.rotationScore);

  const favoredNext = sectors
    .filter((item) => item.state === 'IMPROVING' || item.state === 'LEADING')
    .slice(0, 3)
    .map((item) => item.label);
  const fadingNext = sectors
    .filter((item) => item.state === 'WEAKENING' || item.rotationLabel === 'Rotation Out')
    .slice(0, 3)
    .map((item) => item.label);

  const regimeLabelMap: Record<SectorRotationRegime, string> = {
    MID_GROWTH: '중기 성장',
    EARLY_CYCLICAL: '초기 경기민감',
    LATE_INFLATION: '후기 인플레',
    DEFENSIVE: '방어',
    RE_ACCELERATION: '재가속',
  };

  const summary = favoredNext.length
    ? `현재 섹터 순환은 ${regimeLabelMap[rotationRegime]} 단계(분리도 ${confidence})로 보고, ${favoredNext.join(', ')} 순으로 우선 관찰합니다.`
    : `현재 섹터 순환은 ${regimeLabelMap[rotationRegime]} 단계(분리도 ${confidence})지만 뚜렷한 차기 리더는 제한적입니다.`;

  const currentLeaders = sectors
    .filter((item) => item.state === 'LEADING')
    .slice(0, 3)
    .map(buildOutlookBucket);
  const nextCandidates = sectors
    .filter((item) => item.state === 'IMPROVING' && item.rotationScore >= 68)
    .slice(0, 3)
    .map(buildOutlookBucket);
  const secondaryCandidates = sectors
    .filter((item) => item.state === 'IMPROVING' && item.rotationScore < 68)
    .slice(0, 3)
    .map(buildOutlookBucket);
  const fadingCandidates = sectors
    .filter((item) => item.state === 'WEAKENING' || item.rotationLabel === 'Rotation Out')
    .slice(0, 3)
    .map(buildOutlookBucket);

  return {
    regime: rotationRegime,
    confidence,
    regimeScores,
    summary,
    favoredNext,
    fadingNext,
    currentLeaders,
    nextCandidates,
    secondaryCandidates,
    fadingCandidates,
    sectors,
  };
}
