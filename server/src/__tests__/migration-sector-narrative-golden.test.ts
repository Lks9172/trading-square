import { computeNarrativeThemeState } from '../engines/narrative/heat-score';
import { listNarrativeThemes } from '../engines/narrative/theme-map';
import { buildSectorRotationView } from '../services/sector-rotation';
import { NarrativeExternalSignal } from '../types/narrative';

const rawValues: Record<string, number> = {
  DXY: 100,
  WTI: 68,
  T10Y2Y: 0.6,
  STLFSI4: -0.2,
  BAMLH0A0HYM2: 3.4,
  VIXCLS: 31,
};

const derivedValues: Record<string, number> = {
  LIQUIDITY_DIRECTION: 2,
  REAL_YIELD: 1.2,
  OVERHEATED: 0,
  COPPER_GOLD_RATIO_UPTURN: 1,
  CREDIT_HY_OAS_BP: 320,
  INSTITUTIONAL_SECTOR_TECH_FLOW: 1.4,
  INSTITUTIONAL_SECTOR_FIN_FLOW: 0.8,
  INSTITUTIONAL_SECTOR_ENERGY_FLOW: -0.4,
  SECTOR_SOXX: 14,
  SECTOR_GRID: 9,
  SECTOR_IGF: 7,
  SECTOR_ITA: 10,
  NASDAQ_DISPARITY: 16,
  GOLD_PRIORITY_SCORE: 0.8,
  GOLD_DISPARITY: 19,
  CB_GOLD_STRUCTURAL_DEMAND: 0.75,
  SECTOR_XLU: 4,
  SECTOR_XLF: 5,
  SECTOR_XLE: 6,
  SECTOR_XLC: 4,
  SECTOR_XLY: 3,
  SECTOR_XLP: 2,
  SECTOR_XLV: 2,
  SECTOR_XLB: 5,
  SECTOR_XLRE: 2,
};

const raw = Object.fromEntries(Object.entries(rawValues).map(([key, value]) => [
  key,
  { code: key, value, date: '2026-06-03', source: 'FRED' },
]));
const derived = Object.fromEntries(Object.entries(derivedValues).map(([key, value]) => [
  key,
  { name: key, value, date: '2026-06-03', formula: '' },
]));
const regime = { regime: 'RISK_ON', score: 78, date: '2026-06-03', components: { geoRisk: 1 } } as any;
const externalSignals: NarrativeExternalSignal[] = [
  { key: 'YOUTUBE_30D', label: 'YouTube 30D', value: 600, score: 9, detail: '30D 600건' },
];

const snapshot = {
  timestamp: '2026-06-03T00:00:00.000Z',
  raw,
  derived,
  regime,
  signals: [
    { asset: 'NASDAQ', signal: 'STRONG_BUY' },
    { asset: 'GOLD', signal: 'BUY' },
    { asset: 'COPPER', signal: 'BUY' },
  ],
  meta: { profile: { manualInputs: { geoRisk: 4, aiNarrativeStrength: 2 } } },
} as any;

const sectors = [
  { key: 'SECTOR_XLK', label: '기술', classification: 'structural', score: 8, shortTermScore: 5, earningsRevisionScore: 82, valuationScore: 62, quality: { totalScore: 84 }, stance: 'favored', appealScore: 79, crowdingScore: 52, buyScore: 76, reasons: [] },
  { key: 'SECTOR_XLI', label: '산업재', classification: 'cyclical', score: 4.5, shortTermScore: 4, earningsRevisionScore: 71, valuationScore: 68, quality: { totalScore: 78 }, stance: 'favored', appealScore: 74, crowdingScore: 42, buyScore: 73, reasons: [] },
  { key: 'SECTOR_XLF', label: '금융', classification: 'cyclical', score: 2, shortTermScore: 2, earningsRevisionScore: 68, valuationScore: 72, quality: { totalScore: 74 }, stance: 'neutral', appealScore: 70, crowdingScore: 35, buyScore: 71, reasons: [] },
  { key: 'SECTOR_XLE', label: '에너지', classification: 'cyclical', score: 8, shortTermScore: -5, earningsRevisionScore: 54, valuationScore: 75, quality: { totalScore: 70 }, stance: 'neutral', appealScore: 62, crowdingScore: 82, buyScore: 55, reasons: [] },
  { key: 'SECTOR_XLU', label: '유틸리티', classification: 'defensive', score: -4, shortTermScore: -5, earningsRevisionScore: 50, valuationScore: 55, quality: { totalScore: 68 }, stance: 'avoided', appealScore: 52, crowdingScore: 30, buyScore: 58, reasons: [] },
  { key: 'SECTOR_XLRE', label: '부동산', classification: 'defensive', score: -1, shortTermScore: 1, earningsRevisionScore: 57, valuationScore: 74, quality: { totalScore: 66 }, stance: 'neutral', appealScore: 64, crowdingScore: 28, buyScore: 68, reasons: [] },
] as any[];

describe('Spring migration sector/narrative characterization', () => {
  it('locks all narrative stage and heat outputs', () => {
    const states = listNarrativeThemes().map((theme) => computeNarrativeThemeState(theme, snapshot, externalSignals));

    expect(Object.fromEntries(states.map((state) => [state.theme.id, `${state.stage}:${state.heatScore}`]))).toEqual({
      'ai-power': 'OVERHEATED:85',
      'grid-capex': 'OVERHEATED:80',
      'defense-rearm': 'OVERHEATED:76',
      'finance-liquidity': 'OVERHEATED:68',
      'energy-supply': 'OVERHEATED:70',
      'digital-attention': 'OVERHEATED:78',
      'consumer-demand': 'OVERHEATED:68',
      'consumer-defensive': 'OVERHEATED:68',
      'materials-reflation': 'MID:65',
      'real-assets-rate': 'OVERHEATED:73',
      'safehaven-gold': 'OVERHEATED:85',
    });
    expect(states[0].drivers).toEqual([
      '반도체 모멘텀 14.0%',
      '전력망 프록시 9.0%',
      'NASDAQ STRONG_BUY',
      'YouTube 30D 600',
    ]);
    expect(states[0].risks).toEqual(['NASDAQ 이격도 16.0%', 'YouTube 30D 과열 600']);
    expect(states[states.length - 1].proxyScores.map((item) => `${item.key}:${item.score}:${item.detail}`)).toEqual([
      'GOLD_SIGNAL:7:GOLD BUY',
      'GOLD_PRIORITY_SCORE:9:score 0.80',
      'VIXCLS:9:VIX 31.0',
      'GOLD_DISPARITY:9:이격 19.0%',
      'CB_GOLD_STRUCTURAL_DEMAND:8:CB demand 0.75',
    ]);
  });

  it('locks rotation regime, ranking, states and leadership horizons', () => {
    const narratives = listNarrativeThemes().map((theme) => computeNarrativeThemeState(theme, snapshot, externalSignals));
    const result = buildSectorRotationView(raw as any, derived as any, regime, sectors, narratives);

    expect(result.regime).toBe('EARLY_CYCLICAL');
    expect(result.confidence).toBe(50);
    expect(result.regimeScores).toEqual({
      EARLY_CYCLICAL: 100,
      MID_GROWTH: 75,
      LATE_INFLATION: 0,
      DEFENSIVE: 1,
      RE_ACCELERATION: 100,
    });
    expect(result.summary).toBe('현재 섹터 순환은 초기 경기민감 단계(분리도 50)로 보고, 기술, 산업재, 금융 순으로 우선 관찰합니다.');
    expect(result.sectors.map((item) => [
      item.key,
      item.rotationScore,
      item.state,
      item.rotationLabel,
      item.expectedLeadershipWindow,
      item.relativeStrengthScore,
      item.fundamentalScore,
      item.flowScore,
    ])).toEqual([
      ['SECTOR_XLK', 80, 'LEADING', 'Leader', 'now', 84, 81, 75],
      ['SECTOR_XLI', 76, 'LEADING', 'Leader', '1_3m', 69, 75, 80],
      ['SECTOR_XLF', 73, 'IMPROVING', 'Rotation In', '1_3m', 58, 72, 64],
      ['SECTOR_XLRE', 66, 'IMPROVING', 'Rotation In', '3_6m', 46, 65, 41],
      ['SECTOR_XLE', 65, 'WEAKENING', 'Late Leader', 'unclear', 84, 67, 43],
      ['SECTOR_XLU', 44, 'LAGGING', 'Rotation Out', 'unclear', 33, 60, 41],
    ]);
  });
});
