import { collectAll } from '../collectors';
import { computeDerived } from '../engines/derived';
import { attachInterpretations } from '../services/interpretations';
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
import { hardenFlag } from '../services/flagPersistence';
import { withSpan } from '../observability/trace';
import { childLogger, serializeError } from '../services/logger';
import { DEFAULT_MANUAL_INPUTS, mergeEffectiveManualInputs } from '../services/policy-inputs';
import { logDecisionDetails } from '../services/decision-logging';
import { buildTopDownView, enrichSignalExplanations } from '../services/topdown-view';

const log = childLogger({ module: 'state.cache' });

export const DEFAULT_PROFILE: UserProfile = {
  riskTolerance: 'moderate',
  investmentHorizon: 'long',
  leverageEnabled: true,
  includeCrypto: false,
  includeKR: true,
  manualInputs: DEFAULT_MANUAL_INPUTS,
};

export const CACHE_TTL = 5 * 60 * 1000;

let cachedSnapshot: SystemSnapshot | null = null;
let cacheTime = 0;
const inFlightSnapshots = new Map<string, Promise<SystemSnapshot>>();

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
  return withSpan('macrosquare.snapshot.build', async (rootSpan) => {
  const startedAt = Date.now();
  const apiKey = process.env.FRED_API_KEY || '';

  if (!cachedAutoInputs) {
    try {
      cachedAutoInputs = await computeAutoManualInputs(apiKey);
      log.info({ autoInputs: cachedAutoInputs }, 'cached auto inputs initialized');
    } catch (error) {
      log.warn({ error: serializeError(error) }, 'cached auto inputs initialization failed, using defaults');
      cachedAutoInputs = { policyDirection: 0, geoRisk: 2, cbBuying: true, ismPmi: null };
    }
  }

  const effectiveInputs = mergeEffectiveManualInputs(
    profile.manualInputs,
    cachedAutoInputs,
    DEFAULT_PROFILE.manualInputs,
  );
  const isDefaultManual = effectiveInputs !== profile.manualInputs;
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

  const smartMoney = await withSpan('macrosquare.collector.smartMoney', () =>
    fetchInsiderSummary().catch((error) => {
      log.warn({ error: serializeError(error) }, 'smart money collector failed, using null');
      return null;
    }),
  );
  const calendar = await withSpan('macrosquare.collector.calendar', () =>
    fetchEconomicCalendar(apiKey).catch((error) => {
      log.warn({ error: serializeError(error) }, 'calendar collector failed, using empty list');
      return [];
    }),
  );
  const derived = await withSpan('macrosquare.engine.derived', (s) =>
    computeDerived(raw, {
      ...effectiveInputs,
      // 19차: profile 의 horizon + manual DCA / ETF theme 통과
      investmentHorizon: profile.investmentHorizon,
      trancheUsedPct: effectiveInputs.trancheUsedPct,
      etfInflowTheme: effectiveInputs.etfInflowTheme,
    }).then((d) => {
      s.setAttribute('derived.keys', Object.keys(d).length);
      return d;
    }),
  );

  // === Fix #FE1: 이진 플래그 히스테리시스 ===
  // 5분 스냅샷 경로에서만 적용. raw 값이 1↔0 으로 깜빡여도 minDays 경과 전에는 persisted value 로
  // 덮어써서 BASE_ALLOCATIONS 가 즉각 뒤집히는 whipsaw 를 차단한다.
  // history 재계산(recomputeFullDerivedForDate) 경로는 이미 날짜별 확정값이라 적용하지 않는다.
  const flagHysteresis: Array<{ key: string; minDays: number }> = [
    { key: 'OVERHEATED', minDays: 7 },
    { key: 'FISCAL_STRESS', minDays: 7 },
    { key: 'FISCAL_STRESS_HARD', minDays: 7 },
    { key: 'BOND_VIGILANTE_WARNING', minDays: 7 },
    { key: 'STAGFLATION_WARNING', minDays: 14 }, // 구조적 경보 — 더 보수
    { key: 'CREDIT_STRESS_FLAG', minDays: 7 },
    { key: 'NASDAQ_CHASE_WARNING', minDays: 5 },
    { key: 'KOSPI_CHASE_WARNING', minDays: 5 },
  ];
  for (const { key, minDays } of flagHysteresis) {
    const ind = derived[key];
    if (!ind) continue; // 계산 경로에서 값이 없으면(결측) 히스테리시스 생략 — 0 주입 금지.
    const rawVal = ind.value === 1 ? 1 : 0;
    try {
      const hardened = await hardenFlag(key, rawVal, minDays);
      if (hardened !== rawVal) {
        derived[key] = {
          ...ind,
          value: hardened,
          formula: `${ind.formula} [히스테리시스 ${minDays}일 적용 — raw=${rawVal} / persisted=${hardened}]`,
        };
      } else {
        derived[key] = {
          ...ind,
          formula: `${ind.formula} [히스테리시스 ${minDays}일 적용]`,
        };
      }
    } catch (err) {
      // 영속 실패는 신호 흐름을 막지 않는다 — raw 값 그대로 통과.
      console.warn(`[flagPersistence] ${key} hardenFlag failed:`, err);
    }
  }

  // 스마트머니 점수를 derived 에도 publish — regime.ts 는 별도 인자로 받지만, UI/히스토리/
  // 관측성 경로는 derived 를 본다. 일관성 확보 (cachedLiveDerived 공유 구조).
  // 값은 regime.components.smartMoney 와 동일 (insider + dataroma 합성).
  if (smartMoney) {
    const today = new Date().toISOString().split('T')[0];
    const score = smartMoney.score ?? 0;
    derived.SMART_MONEY_SCORE = { name: 'SMART_MONEY_SCORE', value: score, date: today, formula: 'insider + dataroma 평균 (-2..+2)' };
    derived.SMART_MONEY_INSIDER_BUY_RATIO = { name: 'SMART_MONEY_INSIDER_BUY_RATIO', value: smartMoney.insiderBuyRatio, date: today, formula: 'openinsider 매수/(매수+매도) %' };
    if (smartMoney.dataromaScore !== undefined) {
      derived.SMART_MONEY_DATAROMA_SCORE = { name: 'SMART_MONEY_DATAROMA_SCORE', value: smartMoney.dataromaScore, date: today, formula: 'dataroma 13F net flow 점수 (-2..+2)' };
    }
    if (smartMoney.dataromaNetPortfolioFlow !== undefined && smartMoney.dataromaNetPortfolioFlow !== null) {
      derived.SMART_MONEY_DATAROMA_NET_FLOW = { name: 'SMART_MONEY_DATAROMA_NET_FLOW', value: smartMoney.dataromaNetPortfolioFlow, date: today, formula: 'dataroma 기관 순매수 포트폴리오 기여 (+ 유입 / - 유출)' };
    }
  }

  const regime = await withSpan('macrosquare.engine.regime', (s) => {
    const r = classifyRegime(
      { raw, derived, manualInputs: effectiveInputs, smartMoneyScore: smartMoney?.score ?? 0 },
      { includeExplanation: true },
    );
    s.setAttribute('regime.label', r.regime);
    s.setAttribute('regime.score', r.score);
    return Promise.resolve(r);
  });
  // 16차 Phase 1 B1 (2026-04): 지표별 자동 해설 레이어 — 노션 "now-panel" 정합.
  //   regime 결정 직후, signal/allocation 이전에 derived 엔트리에 interpretation 필드 첨가.
  //   UI/텔레그램이 숫자와 함께 한 문장 해석을 바로 소비 가능.
  attachInterpretations(
    derived as unknown as Record<string, { value: number | null | undefined }>,
    { regime: regime.regime, nasdaqDisparity: derived.NASDAQ_DISPARITY?.value ?? null, kospiDisparity: derived.KOSPI_DISPARITY?.value ?? null },
  );

  const signals = await withSpan('macrosquare.engine.signals', (s) => {
    const r = computeSignals(raw, derived, regime, effectiveProfile);
    s.setAttribute('signals.count', r.length);
    return Promise.resolve(r);
  });
  const topdown = buildTopDownView(raw, derived, regime, signals);
  enrichSignalExplanations(signals, topdown);
  const allocation = await withSpan('macrosquare.engine.allocation', () =>
    Promise.resolve(
      computeAllocation(
        regime.regime,
        regime.score,
        signals,
        derived,
        raw,
        effectiveProfile.investmentHorizon,
        undefined,
        effectiveProfile,
        { includeExplanation: true },
      ),
    ),
  );
  // 21차 P1#3 + 23차 Tier 1#4: signal hysteresis 1met 변동만 차단.
  // 직전 snapshot 의 met 와 현재 met 차이 = 1 인 경우만 강등 차단 (이전 metGap 휴리스틱은 met 큰 폭 변동도 보호).
  try {
    if (cachedSnapshot && cachedSnapshot.signals) {
      const prevByAsset = new Map(cachedSnapshot.signals.map((s) => [s.asset, s]));
      for (const sig of signals) {
        const prev = prevByAsset.get(sig.asset);
        if (!prev) continue;
        const prevSig = prev.signal;
        // BUY/STRONG_BUY → HOLD/REDUCE 로 떨어지는 경우 prev 유지 (whipsaw 차단)
        if ((prevSig === 'BUY' || prevSig === 'STRONG_BUY') && (sig.signal === 'HOLD' || sig.signal === 'REDUCE')) {
          // 23차 Tier 1#4 + 24차 Phase 2#15: BUY/STRONG_BUY → HOLD/REDUCE 강등 metDelta≤2 까지 차단.
          // 이전 1met 만 차단 → 24차에서 video1 §11:14 "남의 판단" 보호 강화로 ≤2 까지 확장.
          const metDelta = (prev.conditionsMet ?? 0) - (sig.conditionsMet ?? 0);
          if (metDelta >= 1 && metDelta <= 2) {
            sig.signal = prevSig as typeof sig.signal;
            sig.reasons.push(`✓ Hysteresis 보호 — 직전 ${prevSig} 유지 (metDelta=${metDelta}, 24차 ≤2 확장)`);
          }
        }
        // 24차 Phase 2#15 추가: STRONG_BUY → BUY 강등도 metDelta≤2 보호
        if (prevSig === 'STRONG_BUY' && sig.signal === 'BUY') {
          const metDelta = (prev.conditionsMet ?? 0) - (sig.conditionsMet ?? 0);
          if (metDelta >= 1 && metDelta <= 2) {
            sig.signal = 'STRONG_BUY';
            sig.reasons.push(`✓ Hysteresis 보호 — STRONG_BUY 유지 (metDelta=${metDelta}, 24차)`);
          }
        }
        // 25차: leverage tier flicker 차단 (HARD↔MEDIUM↔SOFT 한 단계 변동만 보호)
        if (sig.asset === 'LEVERAGE' && prev.tier && sig.tier && prev.tier !== sig.tier) {
          const tierOrder: Record<string, number> = { SOFT: 1, MEDIUM: 2, HARD: 3 };
          const prevLevel = tierOrder[prev.tier] ?? 0;
          const currLevel = tierOrder[sig.tier] ?? 0;
          // 한 단계 차이 (예: HARD → MEDIUM 또는 MEDIUM → SOFT) 면 직전 tier 유지
          if (Math.abs(prevLevel - currLevel) === 1) {
            sig.tier = prev.tier;
            sig.reasons.push(`✓ leverage tier hysteresis — ${prev.tier} 유지 (1단계 변동 차단, 25차)`);
          }
        }
      }
    }
  } catch { void 0; }

  const executionPlans = await withSpan('macrosquare.engine.executionPlan', () =>
    computeExecutionPlans(raw, derived, signals, allocation, regime),
  );
  rootSpan.setAttribute('snapshot.regime', regime.regime);
  rootSpan.setAttribute('snapshot.regime_score', regime.score);
  rootSpan.setAttribute('snapshot.duration_ms', Date.now() - startedAt);
  const fetchedAt = new Date().toISOString();

  logDecisionDetails({ raw, derived, regime, signals, allocation });

  log.info({
    durationMs: Date.now() - startedAt,
    rawKeys: Object.keys(raw).length,
    derivedKeys: Object.keys(derived).length,
    signalCount: signals.length,
    regime: regime.regime,
    regimeScore: regime.score,
    usPriceSource,
    smartMoneyPresent: Boolean(smartMoney),
    calendarEvents: calendar.length,
  }, 'snapshot build completed');

  return {
    timestamp: fetchedAt,
    raw,
    derived,
    regime: { ...regime, explanation: undefined },
    signals,
    allocation: { ...allocation, explanation: undefined },
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
      topdown,
      calendar,
      executionPlans,
    },
  };
  });
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

function snapshotCacheKey(profile: UserProfile) {
  return JSON.stringify({
    riskTolerance: profile.riskTolerance,
    investmentHorizon: profile.investmentHorizon,
    leverageEnabled: profile.leverageEnabled,
    includeCrypto: profile.includeCrypto,
    includeKR: profile.includeKR,
    manualInputs: profile.manualInputs,
  });
}

export function resetSnapshotStateForTests() {
  cachedSnapshot = null;
  cacheTime = 0;
  cachedAutoInputs = null;
  inFlightSnapshots.clear();
}

export async function getSnapshot(profile: UserProfile, force = false) {
  const now = Date.now();
  if (!force && cachedSnapshot && now - cacheTime < CACHE_TTL) {
    return cachedSnapshot;
  }

  const key = snapshotCacheKey(profile);
  const existing = inFlightSnapshots.get(key);
  if (existing) {
    return existing;
  }

  const prevSnapshot = cachedSnapshot;
  const pending = buildSnapshot(profile)
    .then((snapshot) => {
      writeCache(snapshot);
      // 17차 Phase 2 E2: regime/signal 변화 자동 trade log 기록
      // 비동기 로깅 — 실패해도 snapshot 반환에는 영향 없음.
      import('../services/weekly-report')
        .then(({ autoLogSnapshotDelta }) => autoLogSnapshotDelta(prevSnapshot, snapshot))
        .catch(() => {/* swallow */});
      return snapshot;
    })
    .finally(() => {
      inFlightSnapshots.delete(key);
    });
  inFlightSnapshots.set(key, pending);
  return pending;
}
