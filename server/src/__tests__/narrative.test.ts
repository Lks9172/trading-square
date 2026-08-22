import { computeNarrativeThemeState } from '../engines/narrative/heat-score';
import { getNarrativeThemeById } from '../engines/narrative/theme-map';
import { SystemSnapshot } from '../types/indicators';

function makeSnapshot(): SystemSnapshot {
  return {
    timestamp: '2026-06-03T00:00:00.000Z',
    raw: {
      VIXCLS: { code: 'VIXCLS', value: 31, date: '2026-06-03', source: 'FRED' },
      WTI: { code: 'WTI', value: 82, date: '2026-06-03', source: 'YAHOO' },
    },
    derived: {
      SECTOR_SOXX: { name: 'SECTOR_SOXX', value: 14, date: '2026-06-03', formula: '' },
      SECTOR_GRID: { name: 'SECTOR_GRID', value: 9, date: '2026-06-03', formula: '' },
      SECTOR_IGF: { name: 'SECTOR_IGF', value: 7, date: '2026-06-03', formula: '' },
      SECTOR_ITA: { name: 'SECTOR_ITA', value: 10, date: '2026-06-03', formula: '' },
      NASDAQ_DISPARITY: { name: 'NASDAQ_DISPARITY', value: 16, date: '2026-06-03', formula: '' },
      GOLD_PRIORITY_SCORE: { name: 'GOLD_PRIORITY_SCORE', value: 0.8, date: '2026-06-03', formula: '' },
      GOLD_DISPARITY: { name: 'GOLD_DISPARITY', value: 19, date: '2026-06-03', formula: '' },
      CB_GOLD_STRUCTURAL_DEMAND: { name: 'CB_GOLD_STRUCTURAL_DEMAND', value: 0.75, date: '2026-06-03', formula: '' },
    },
    regime: { regime: 'NEUTRAL', score: 50, components: {}, date: '2026-06-03' },
    signals: [
      { asset: 'NASDAQ', signal: 'STRONG_BUY', conditionsMet: 0, conditionsTotal: 0, weightedScore: 0, weightedMaxScore: 0, reasons: [], unmetReasons: [], date: '2026-06-03' },
      { asset: 'GOLD', signal: 'BUY', conditionsMet: 0, conditionsTotal: 0, weightedScore: 0, weightedMaxScore: 0, reasons: [], unmetReasons: [], date: '2026-06-03' },
      { asset: 'COPPER', signal: 'BUY', conditionsMet: 0, conditionsTotal: 0, weightedScore: 0, weightedMaxScore: 0, reasons: [], unmetReasons: [], date: '2026-06-03' },
    ],
    allocation: { regime: 'NEUTRAL', score: 50, allocations: {}, leverageAllowed: false, buyStage: null, date: '2026-06-03' },
    meta: {
      fetchedAt: '2026-06-03T00:00:00.000Z', cacheTtlMs: 300000, nextRefreshAt: '2026-06-03T00:05:00.000Z', usPriceSource: 'spot', sourceFrequencies: {}, latestDates: {}, historyGuarantee: {}, profile: { riskTolerance: 'moderate', investmentHorizon: 'long', leverageEnabled: true, includeCrypto: false, includeKR: true, manualInputs: { policyDirection: 0, geoRisk: 4, cbBuying: true, ismPmi: 52, aiNarrativeStrength: 2 } }, autoInputs: { policyDirection: 0, geoRisk: 2, cbBuying: true, ismPmi: 52 }, inputMode: 'manual', staleness: {}, smartMoney: null, topdown: { summary: '', macroView: [], favoredSectors: [], avoidedSectors: [], assetRationale: [] }, calendar: [], executionPlans: [] },
  } as unknown as SystemSnapshot;
}

describe('narrative heat score', () => {
  it('computes AI narrative stage', () => {
    const theme = getNarrativeThemeById('ai-power');
    expect(theme).toBeTruthy();
    const result = computeNarrativeThemeState(theme!, makeSnapshot(), [{ key: 'YOUTUBE_30D', label: 'YouTube 30D', value: 600, score: 9, detail: '30D 600건' }]);
    expect(result.heatScore).toBeGreaterThan(60);
    expect(result.stage).toBe('OVERHEATED');
  });

  it('stores external signals on narrative state', () => {
    const theme = getNarrativeThemeById('grid-capex');
    const result = computeNarrativeThemeState(theme!, makeSnapshot(), [{ key: 'GOOGLE_NEWS_7D', label: 'Google News 7D', value: 18, score: 6.5, detail: '7D 18건' }]);
    expect(result.externalSignals.length).toBe(1);
    expect(result.heatScore).toBeGreaterThan(0);
  });

  it('computes gold narrative with risk note', () => {
    const theme = getNarrativeThemeById('safehaven-gold');
    const result = computeNarrativeThemeState(theme!, makeSnapshot(), [{ key: 'YOUTUBE_30D', label: 'YouTube 30D', value: 600, score: 9, detail: '30D 600건' }]);
    expect(result.drivers.length).toBeGreaterThan(0);
    expect(result.risks.some((item) => item.includes('이격도'))).toBe(true);
  });
});
