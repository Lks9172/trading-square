import { collectAll } from '../collectors';
import { computeDerived } from '../engines/derived';
import { classifyRegime } from '../engines/regime';
import { computeSignals } from '../engines/signals';
import { computeAllocation } from '../engines/allocation';
import { SystemSnapshot, UserProfile } from '../types/indicators';
import { HISTORY_GUARANTEE } from './history-store';
import { computeAutoManualInputs } from '../collectors/auto-manual';
import { fetchInsiderSummary } from '../collectors/smart-money';
import { fetchEconomicCalendar } from '../collectors/calendar';
import { computeExecutionPlans } from '../engines/execution_plan';
import { getUSPriceSource } from '../utils/market-hours';

export const DEFAULT_PROFILE: UserProfile = {
  riskTolerance: 'moderate',
  investmentHorizon: 'long',
  leverageEnabled: false,
  includeCrypto: false,
  includeKR: true,
  manualInputs: {
    policyDirection: 0,
    geoRisk: 2,
    cbBuying: true,
    ismPmi: null,
  },
};

export const CACHE_TTL = 5 * 60 * 1000;

let cachedSnapshot: SystemSnapshot | null = null;
let cacheTime = 0;

function latestDates(raw: SystemSnapshot['raw'], derived: SystemSnapshot['derived']) {
  return {
    FRED: Object.values(raw).filter((v) => v.source === 'FRED').map((v) => v.date).sort().reverse()[0] || '',
    YAHOO: Object.values(raw).filter((v) => v.source === 'YAHOO').map((v) => v.date).sort().reverse()[0] || '',
    CNN: Object.values(raw).filter((v) => v.source === 'CNN').map((v) => v.date).sort().reverse()[0] || '',
    DERIVED: Object.values(derived).map((v) => v.date).sort().reverse()[0] || '',
  };
}

let cachedAutoInputs: { policyDirection: number; geoRisk: number; cbBuying: boolean; ismPmi: number | null } | null = null;

export async function buildSnapshot(profile: UserProfile): Promise<SystemSnapshot> {
  const apiKey = process.env.FRED_API_KEY || '';

  if (!cachedAutoInputs) {
    try {
      cachedAutoInputs = await computeAutoManualInputs(apiKey);
    } catch {
      cachedAutoInputs = { policyDirection: 0, geoRisk: 2, cbBuying: true, ismPmi: null };
    }
  }

  const isDefaultManual =
    profile.manualInputs.policyDirection === DEFAULT_PROFILE.manualInputs.policyDirection &&
    profile.manualInputs.geoRisk === DEFAULT_PROFILE.manualInputs.geoRisk &&
    profile.manualInputs.cbBuying === DEFAULT_PROFILE.manualInputs.cbBuying;

  const autoInputsWithIsm = { ...cachedAutoInputs, ismPmi: cachedAutoInputs.ismPmi ?? profile.manualInputs.ismPmi ?? null };
  const effectiveInputs = isDefaultManual ? autoInputsWithIsm : profile.manualInputs;
  const effectiveProfile: UserProfile = { ...profile, manualInputs: effectiveInputs };

  const raw = await collectAll(apiKey);

  const usPriceSource = getUSPriceSource();
  if (usPriceSource === 'futures') {
    if (raw.NASDAQ) raw.NASDAQ_SPOT = { ...raw.NASDAQ };
    if (raw.SP500) raw.SP500_SPOT = { ...raw.SP500 };
    if (raw.NQ_FUTURES) raw.NASDAQ = { ...raw.NQ_FUTURES, code: '^IXIC_F' };
    if (raw.ES_FUTURES) raw.SP500 = { ...raw.ES_FUTURES, code: '^GSPC_F' };
  } else {
    if (raw.NASDAQ) raw.NASDAQ_SPOT = { ...raw.NASDAQ };
    if (raw.SP500) raw.SP500_SPOT = { ...raw.SP500 };
  }

  const smartMoney = await fetchInsiderSummary().catch(() => null);
  const calendar = await fetchEconomicCalendar(apiKey).catch(() => []);
  const derived = await computeDerived(raw);
  const regime = classifyRegime({ raw, derived, manualInputs: effectiveInputs, smartMoneyScore: smartMoney?.score ?? 0 });
  const signals = computeSignals(raw, derived, regime, effectiveProfile);
  const allocation = computeAllocation(regime.regime, regime.score, signals, derived, raw, effectiveProfile.investmentHorizon);
  const executionPlans = computeExecutionPlans(raw, derived, signals, allocation, regime);
  const fetchedAt = new Date().toISOString();

  return {
    timestamp: fetchedAt,
    raw,
    derived,
    regime,
    signals,
    allocation,
    meta: {
      fetchedAt,
      cacheTtlMs: CACHE_TTL,
      nextRefreshAt: new Date(Date.now() + CACHE_TTL).toISOString(),
      usPriceSource,
      sourceFrequencies: {
        FRED: '일간~월간 / 서버 5분 캐시',
        YAHOO: `일간 / 서버 5분 캐시 / 미국 ${usPriceSource === 'futures' ? '선물 기준' : '현물 기준'}`,
        CNN: '일간 / 서버 5분 캐시',
        DERIVED: '원시데이터 갱신 시 재계산',
      },
      latestDates: latestDates(raw, derived),
      historyGuarantee: {
        FRED: `${HISTORY_GUARANTEE.FRED_YEARS}년`,
        YAHOO: `${HISTORY_GUARANTEE.YAHOO_YEARS}년`,
        CNN: '최신값 중심',
        DERIVED: '원시데이터 보장 범위만큼',
      },
      profile: effectiveProfile,
      autoInputs: cachedAutoInputs,
      inputMode: isDefaultManual ? 'auto' : 'manual',
      staleness: computeStaleness(raw),
      smartMoney,
      calendar,
      executionPlans,
    },
  };
}

function computeStaleness(raw: SystemSnapshot['raw']): Record<string, { date: string; daysAgo: number; frequency: string }> {
  const today = new Date();
  const result: Record<string, { date: string; daysAgo: number; frequency: string }> = {};
  const defs: Array<[string, string]> = [
    ['ICSA', '주간'], ['UNRATE', '월간'], ['M2SL', '월간'],
    ['DGS10', '일간'], ['VIXCLS', '일간'], ['T10Y2Y', '일간'],
    ['BAMLH0A0HYM2', '일간'], ['WALCL', '주간'], ['WRESBAL', '주간'],
    ['RRPONTSYD', '일간'], ['WTREGEN', '주간'], ['WRMFNS', '주간'],
  ];
  for (const [key, freq] of defs) {
    const dp = raw[key];
    if (dp) {
      const diff = Math.floor((today.getTime() - new Date(dp.date).getTime()) / 86400000);
      result[key] = { date: dp.date, daysAgo: diff, frequency: freq };
    }
  }
  return result;
}

export function readCache() {
  return { cachedSnapshot, cacheTime };
}

export function writeCache(snapshot: SystemSnapshot) {
  cachedSnapshot = snapshot;
  cacheTime = Date.now();
}

export async function getSnapshot(profile: UserProfile, force = false) {
  const now = Date.now();
  if (!force && cachedSnapshot && now - cacheTime < CACHE_TTL) {
    return cachedSnapshot;
  }

  const snapshot = await buildSnapshot(profile);
  writeCache(snapshot);
  return snapshot;
}
