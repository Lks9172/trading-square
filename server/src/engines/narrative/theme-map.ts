import { NarrativeThemeDefinition } from '../../types/narrative';

export const NARRATIVE_THEME_DEFINITIONS: NarrativeThemeDefinition[] = [
  {
    id: 'ai-power',
    title: 'AI / 반도체',
    description: 'AI CAPEX, 반도체, 데이터센터 전력 수요가 함께 강화되는 국면 추적',
    proxies: ['SECTOR_SOXX', 'SECTOR_GRID', 'SECTOR_IGF', 'NASDAQ_SIGNAL', 'NASDAQ_DISPARITY'],
    externalQueries: {
      youtubeQuery: 'AI infrastructure semiconductor datacenter power',
      newsQuery: 'AI infrastructure semiconductor datacenter power',
    },
  },
  {
    id: 'grid-capex',
    title: '전력망 / 인프라',
    description: '전력망, 냉각, EPC, 전력장비 CAPEX 내러티브의 확산 정도 측정',
    proxies: ['SECTOR_GRID', 'SECTOR_IGF', 'SECTOR_XLU', 'COPPER', 'AI_NARRATIVE_STRENGTH'],
    externalQueries: {
      youtubeQuery: 'grid capex power infrastructure transformer cooling data center',
      newsQuery: 'grid capex power infrastructure transformer cooling data center',
    },
  },
  {
    id: 'defense-rearm',
    title: '방산 / 재무장',
    description: '지정학 리스크와 국방예산 확대에 따른 방산 내러티브의 과열/확산 측정',
    proxies: ['SECTOR_ITA', 'GEO_RISK', 'WTI', 'GOLD_SIGNAL'],
    externalQueries: {
      youtubeQuery: 'defense rearmament missile drone aerospace',
      newsQuery: 'defense rearmament missile drone aerospace',
    },
  },
  {
    id: 'finance-liquidity',
    title: '금융 / 유동성',
    description: '금융, 거래소, 결제 레일의 유동성 확대/긴축 민감도를 추적',
    proxies: ['SECTOR_XLF', 'VIXCLS', 'NASDAQ_SIGNAL'],
    externalQueries: {
      youtubeQuery: 'bank liquidity capital markets payment rails',
      newsQuery: 'bank liquidity capital markets payment rails',
    },
  },
  {
    id: 'energy-supply',
    title: '에너지 / 공급',
    description: '원유, 정유, 오일서비스, 가스 파이프라인 공급 내러티브 추적',
    proxies: ['SECTOR_XLE', 'WTI', 'COPPER'],
    externalQueries: {
      youtubeQuery: 'energy supply oil services refining pipeline',
      newsQuery: 'energy supply oil services refining pipeline',
    },
  },
  {
    id: 'digital-attention',
    title: '디지털 플랫폼 / 미디어',
    description: '광고, 스트리밍, 통신·미디어 플랫폼의 관심도와 확산 속도를 추적',
    proxies: ['SECTOR_XLC', 'NASDAQ_SIGNAL', 'AI_NARRATIVE_STRENGTH'],
    externalQueries: {
      youtubeQuery: 'digital advertising streaming telecom platform media',
      newsQuery: 'digital advertising streaming telecom platform media',
    },
  },
  {
    id: 'consumer-demand',
    title: '소비 / 수요',
    description: '임의소비재와 외식·여행·유통 수요 회복의 강도를 추적',
    proxies: ['SECTOR_XLY', 'SECTOR_XLP', 'COPPER'],
    externalQueries: {
      youtubeQuery: 'consumer demand travel retail restaurant spending',
      newsQuery: 'consumer demand travel retail restaurant spending',
    },
  },
  {
    id: 'consumer-defensive',
    title: '소비 / 방어',
    description: '소비 강도와 필수소비재 방어 수요의 균형을 추적',
    proxies: ['SECTOR_XLY', 'SECTOR_XLP', 'SECTOR_XLV'],
    externalQueries: {
      youtubeQuery: 'consumer spending staples defensive retail quality',
      newsQuery: 'consumer spending staples defensive retail quality',
    },
  },
  {
    id: 'materials-reflation',
    title: '소재 / 리플레이션',
    description: '산업금속, 화학, 자본재 수요가 동반되는 리플레이션 내러티브 추적',
    proxies: ['SECTOR_XLB', 'COPPER', 'WTI'],
    externalQueries: {
      youtubeQuery: 'materials reflation copper chemicals industrial metals',
      newsQuery: 'materials reflation copper chemicals industrial metals',
    },
  },
  {
    id: 'real-assets-rate',
    title: '부동산 / 실물자산',
    description: '리츠와 인프라 실물자산이 금리 환경에서 어떻게 반응하는지 추적',
    proxies: ['SECTOR_XLRE', 'SECTOR_IGF', 'GOLD_SIGNAL'],
    externalQueries: {
      youtubeQuery: 'reit data center tower infrastructure rate sensitivity',
      newsQuery: 'reit data center tower infrastructure rate sensitivity',
    },
  },
  {
    id: 'safehaven-gold',
    title: '금 / 안전자산',
    description: '실질금리, 달러, 변동성, 중앙은행 수요를 반영한 금 내러티브 강도 측정',
    proxies: ['GOLD_SIGNAL', 'GOLD_PRIORITY_SCORE', 'VIXCLS', 'GOLD_DISPARITY', 'CB_GOLD_STRUCTURAL_DEMAND'],
    externalQueries: {
      youtubeQuery: 'gold safe haven central bank buying real yield',
      newsQuery: 'gold safe haven central bank buying real yield',
    },
  },
];

export function listNarrativeThemes(): NarrativeThemeDefinition[] {
  return NARRATIVE_THEME_DEFINITIONS;
}

export function getNarrativeThemeById(id: string): NarrativeThemeDefinition | null {
  return NARRATIVE_THEME_DEFINITIONS.find((item) => item.id === id) ?? null;
}
