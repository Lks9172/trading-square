import { SectorClassification } from '../types/indicators';

export interface SectorDefinition {
  key: string;
  label: string;
  classification: SectorClassification;
}

export const SECTOR_DEFINITIONS: SectorDefinition[] = [
  { key: 'SECTOR_XLK', label: '기술', classification: 'structural' },
  { key: 'SECTOR_XLC', label: '커뮤니케이션', classification: 'structural' },
  { key: 'SECTOR_SOXX', label: '반도체', classification: 'structural' },
  { key: 'SECTOR_SMH', label: '반도체(대형주)', classification: 'structural' },
  { key: 'SECTOR_XLF', label: '금융', classification: 'cyclical' },
  { key: 'SECTOR_XLE', label: '에너지', classification: 'cyclical' },
  { key: 'SECTOR_XLI', label: '산업재', classification: 'cyclical' },
  { key: 'SECTOR_XLY', label: '임의소비재', classification: 'cyclical' },
  { key: 'SECTOR_XLB', label: '소재', classification: 'cyclical' },
  { key: 'SECTOR_XLV', label: '헬스케어', classification: 'defensive' },
  { key: 'SECTOR_XLU', label: '유틸리티', classification: 'defensive' },
  { key: 'SECTOR_XLP', label: '필수소비재', classification: 'defensive' },
  { key: 'SECTOR_XLRE', label: '리츠', classification: 'defensive' },
  { key: 'SECTOR_ITA', label: '방산/항공우주', classification: 'structural' },
  { key: 'SECTOR_GRID', label: '전력망', classification: 'structural' },
  { key: 'SECTOR_IGF', label: '인프라', classification: 'structural' },
];

const byKey = new Map(SECTOR_DEFINITIONS.map((item) => [item.key, item] as const));

export function classifySector(key: string): SectorClassification | null {
  return byKey.get(key)?.classification ?? null;
}

export function getSectorDefinition(key: string): SectorDefinition | null {
  return byKey.get(key) ?? null;
}

export function listSectorDefinitions(): SectorDefinition[] {
  return [...SECTOR_DEFINITIONS];
}
