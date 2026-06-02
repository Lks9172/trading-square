import { DerivedIndicator, MarketDataPoint, RegimeState } from '../types/indicators';
import { listSectorDefinitions } from './sector-classification';
import { computeSectorQuality } from '../services/sector-quality';

export function buildSectorQualityDerived(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  date: string,
  regime: Pick<RegimeState, 'regime' | 'score' | 'date' | 'components'>,
): Record<string, DerivedIndicator> {
  const out: Record<string, DerivedIndicator> = {};
  for (const sector of listSectorDefinitions()) {
    const quality = computeSectorQuality(sector, raw, derived, regime as RegimeState);
    const suffix = sector.key.replace('SECTOR_', '');
    out[`SECTOR_POLICY_SUPPORT_${suffix}`] = {
      name: `sector_policy_support_${suffix.toLowerCase()}`,
      value: quality.policySupport,
      date,
      formula: `${suffix} 정책/예산/레짐 수혜 점수 (0-100)`,
    };
    out[`SECTOR_STRUCTURAL_DEMAND_${suffix}`] = {
      name: `sector_structural_demand_${suffix.toLowerCase()}`,
      value: quality.structuralDemand,
      date,
      formula: `${suffix} 구조 수요 점수 (0-100)`,
    };
    out[`SECTOR_SUPPLY_TIGHTNESS_${suffix}`] = {
      name: `sector_supply_tightness_${suffix.toLowerCase()}`,
      value: quality.supplyTightness,
      date,
      formula: `${suffix} 공급 제약/병목 점수 (0-100)`,
    };
    out[`SECTOR_QUALITY_TOTAL_${suffix}`] = {
      name: `sector_quality_total_${suffix.toLowerCase()}`,
      value: quality.totalScore,
      date,
      formula: `${suffix} 섹터 종합 품질 점수 (0-100)`,
    };
  }
  return out;
}
