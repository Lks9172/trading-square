import {
  DerivedIndicator,
  MarketDataPoint,
  RegimeState,
  SectorQualityScore,
} from '../types/indicators';
import { SectorDefinition } from '../engines/sector-classification';

function rawValue(raw: Record<string, MarketDataPoint>, key: string): number | null {
  return raw[key]?.value ?? null;
}

function derivedValue(derived: Record<string, DerivedIndicator>, key: string): number | null {
  return derived[key]?.value ?? null;
}

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

function isRiskOffRegime(regime: RegimeState['regime']): boolean {
  return ['CAUTION', 'CORRECTION', 'PANIC_BUT_OK', 'RECESSION_RISK', 'STAGFLATION', 'BOND_VIGILANTE', 'STAGFLATION_BOND_VIGILANTE'].includes(regime);
}

function baseConcentrationScore(key: string): number {
  switch (key) {
    case 'SECTOR_SOXX':
    case 'SECTOR_SMH':
      return 85;
    case 'SECTOR_XLK':
    case 'SECTOR_XLC':
    case 'SECTOR_XLE':
    case 'SECTOR_ITA':
    case 'SECTOR_GRID':
    case 'SECTOR_IGF':
      return 72;
    case 'SECTOR_XLU':
    case 'SECTOR_XLV':
    case 'SECTOR_XLP':
      return 60;
    case 'SECTOR_XLI':
    case 'SECTOR_XLF':
    case 'SECTOR_XLB':
      return 55;
    case 'SECTOR_XLRE':
      return 45;
    default:
      return 50;
  }
}

export function computeSectorQuality(
  sector: SectorDefinition,
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  regime: RegimeState,
): SectorQualityScore {
  const liquidity = derivedValue(derived, 'LIQUIDITY_DIRECTION') ?? 0;
  const policyLift = derivedValue(derived, 'POLICY_SECTOR_LIFT_PCT') ?? 0;
  const heliumBottleneck = derivedValue(derived, 'HELIUM_AI_BOTTLENECK') ?? 0;
  const copperGoldUpturn = derivedValue(derived, 'COPPER_GOLD_RATIO_UPTURN') ?? 0;
  const institutionalTechFlow = derivedValue(derived, 'INSTITUTIONAL_SECTOR_TECH_FLOW') ?? 0;
  const overheated = derivedValue(derived, 'OVERHEATED') ?? 0;
  const dxy = rawValue(raw, 'DXY');
  const wti = rawValue(raw, 'WTI');
  const geoComponent = regime.components.geoRisk ?? 0;

  let policySupport = 44;
  if (sector.classification === 'structural' && liquidity > 0) policySupport += 10;
  if (sector.classification === 'cyclical' && dxy !== null && dxy < 103) policySupport += 10;
  if (sector.classification === 'defensive' && isRiskOffRegime(regime.regime)) policySupport += 18;
  if (sector.key === 'SECTOR_SOXX' || sector.key === 'SECTOR_SMH') {
    policySupport += 8;
    if (policyLift > 0) policySupport += 6;
    if (institutionalTechFlow > 0) policySupport += 4;
  }
  if (sector.key === 'SECTOR_XLU') {
    if (isRiskOffRegime(regime.regime)) policySupport += 10;
    if (heliumBottleneck === 1) policySupport += 10;
  }
  if (sector.key === 'SECTOR_XLP' && isRiskOffRegime(regime.regime)) policySupport += 12;
  if (sector.key === 'SECTOR_ITA') {
    if (geoComponent <= -1) policySupport += 16;
    if (policyLift > 0) policySupport += 6;
  }
  if (sector.key === 'SECTOR_GRID' || sector.key === 'SECTOR_IGF') {
    if (heliumBottleneck === 1) policySupport += 12;
    if (policyLift > 0) policySupport += 6;
  }
  if (sector.key === 'SECTOR_XLE') {
    if ((wti !== null && wti > 78) || geoComponent <= -1) policySupport += 18;
    if (dxy !== null && dxy > 104) policySupport += 4;
  }
  if (policyLift > 0) policySupport += Math.min(10, Math.round(policyLift / 2));
  if (policyLift < 0) policySupport -= Math.min(10, Math.round(Math.abs(policyLift) / 2));

  let structuralDemand = sector.classification === 'structural' ? 68 : sector.classification === 'defensive' ? 54 : 44;
  if (['SECTOR_XLK', 'SECTOR_XLC', 'SECTOR_SOXX', 'SECTOR_SMH'].includes(sector.key) && heliumBottleneck === 1) structuralDemand += 16;
  if (['SECTOR_SOXX', 'SECTOR_SMH'].includes(sector.key)) structuralDemand += 8;
  if (sector.key === 'SECTOR_XLU' && heliumBottleneck === 1) structuralDemand += 15;
  if (sector.key === 'SECTOR_XLP' && isRiskOffRegime(regime.regime)) structuralDemand += 8;
  if (sector.key === 'SECTOR_ITA') structuralDemand += geoComponent <= -1 ? 14 : 8;
  if (sector.key === 'SECTOR_GRID' || sector.key === 'SECTOR_IGF') structuralDemand += heliumBottleneck === 1 ? 18 : 10;
  if (['SECTOR_XLI', 'SECTOR_XLB'].includes(sector.key) && copperGoldUpturn === 1) structuralDemand += 10;
  if (sector.key === 'SECTOR_XLE' && ((wti !== null && wti > 75) || geoComponent <= -1)) structuralDemand += 8;

  let supplyTightness = 40;
  if (sector.key === 'SECTOR_SOXX') supplyTightness = 88;
  else if (sector.key === 'SECTOR_SMH') supplyTightness = 85;
  else if (sector.key === 'SECTOR_XLE') supplyTightness = 72;
  else if (sector.key === 'SECTOR_XLB') supplyTightness = 66;
  else if (sector.key === 'SECTOR_XLU') supplyTightness = 60;
  else if (sector.key === 'SECTOR_XLP') supplyTightness = 52;
  else if (sector.key === 'SECTOR_ITA') supplyTightness = 72;
  else if (sector.key === 'SECTOR_GRID') supplyTightness = 70;
  else if (sector.key === 'SECTOR_IGF') supplyTightness = 64;
  else if (sector.classification === 'structural') supplyTightness = 58;
  else if (sector.classification === 'defensive') supplyTightness = 48;
  if (sector.key === 'SECTOR_XLE' && geoComponent <= -1) supplyTightness += 10;
  if (sector.key === 'SECTOR_XLU' && heliumBottleneck === 1) supplyTightness += 8;
  if (sector.key === 'SECTOR_ITA' && geoComponent <= -1) supplyTightness += 8;
  if ((sector.key === 'SECTOR_GRID' || sector.key === 'SECTOR_IGF') && heliumBottleneck === 1) supplyTightness += 6;

  let marketConcentration = baseConcentrationScore(sector.key);
  if (sector.key === 'SECTOR_SOXX') marketConcentration = 88;
  if (sector.key === 'SECTOR_SMH') marketConcentration = 86;
  if (sector.key === 'SECTOR_XLU') marketConcentration = 64;
  if (sector.key === 'SECTOR_XLP') marketConcentration = 62;
  if (sector.key === 'SECTOR_XLE') marketConcentration = 74;
  if (sector.key === 'SECTOR_ITA') marketConcentration = 76;
  if (sector.key === 'SECTOR_GRID') marketConcentration = 70;
  if (sector.key === 'SECTOR_IGF') marketConcentration = 66;

  if ((sector.key === 'SECTOR_SOXX' || sector.key === 'SECTOR_SMH') && overheated === 1) {
    policySupport -= 4;
    structuralDemand -= 2;
  }

  policySupport = clamp(policySupport, 0, 100);
  structuralDemand = clamp(structuralDemand, 0, 100);
  supplyTightness = clamp(supplyTightness, 0, 100);
  marketConcentration = clamp(marketConcentration, 0, 100);

  const totalScore = Math.round(
    policySupport * 0.3
      + structuralDemand * 0.3
      + supplyTightness * 0.25
      + marketConcentration * 0.15,
  );

  return {
    policySupport,
    structuralDemand,
    supplyTightness,
    marketConcentration,
    totalScore,
  };
}
