import axios from 'axios';
import { MarketDataPoint } from '../types/indicators';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCache, readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const FRED_BASE = 'https://api.stlouisfed.org/fred/series/observations';
const log = childLogger({ module: 'collector.fred' });
const FRED_HEADERS = {
  'User-Agent': 'MacroSquare/1.0 (+https://macrosquare.local)',
  Accept: 'application/json,text/plain,*/*',
};
const FRED_LIVE_CONCURRENCY = 2;
// 2026-04 개선: verification probe 빈도 5min → 15min. FRED 자체가 일간 갱신이라
// 5분 간격 probe 는 과잉. collector cycle 간헐적 17~22s slow 의 주원인.
const FRED_VERIFY_INTERVAL_MS = 15 * 60 * 1000;
const FRED_PROBE_SERIES: [string, string] = ['DGS10', 'DGS10'];
type FredCadence = 'daily' | 'weekly' | 'monthly';

const FRED_SERIES: Record<string, string> = {
  DGS10: 'DGS10',
  DGS30: 'DGS30',  // 30년 국채 금리 — 영상4 "채권 자경단" 감지용
  T10YIE: 'T10YIE',
  T10Y2Y: 'T10Y2Y',
  VIXCLS: 'VIXCLS',
  BAMLH0A0HYM2: 'BAMLH0A0HYM2',
  STLFSI4: 'STLFSI4',
  WALCL: 'WALCL',
  WRESBAL: 'WRESBAL',
  RRPONTSYD: 'RRPONTSYD',
  WTREGEN: 'WTREGEN',
  WRMFNS: 'WRMFNS',
  M2SL: 'M2SL',
  WM2NS: 'WM2NS', // 주간 M2 (Non-Seasonally Adjusted) — YoY 방향 전환 훅
  UNRATE: 'UNRATE',
  ICSA: 'ICSA',
  SOFR: 'SOFR',
  EFFR: 'EFFR',
  IORB: 'IORB', // Interest on Reserve Balances — SOFR/IORB 스프레드 계산용
  INDPRO: 'INDPRO',
  // 14차 Phase B-3 (2026-04): 미국 연방 부채 GDP 비율 — video4 §채권 자경단
  //   "IMF 2031 미국 부채 GDP 140% 예측" 정합. 부채 규모 감지에 활용.
  FEDERAL_DEBT_GDP: 'GFDEGDQ188S',
  // 13차 (2026-04): M3_EURO/M3_JAPAN 제거. FRED 상 OECD 공급 시리즈가 장기 정체
  // (960일+ staleness) 로 GLOBAL_M2_PROXY 가 실질적으로 미국 M2 단일 기여였음.
  // 영상 원문(video1/4) "유동성 방향" 논의도 주로 미국 유동성 중심이라 단순화.
  // (구) M3_EURO: 'EA19MABMM301IXOBSAM', M3_JAPAN: 'JPNMABMM301IXOBSAM'
};

const FRED_LEVEL_SERIES_MIN_LATEST: Record<string, number> = {};

export { FRED_SERIES, FRED_LEVEL_SERIES_MIN_LATEST };

let lastFredProbeAt = 0;
let lastFredProbeOk = false;

const FRED_SERIES_CADENCE: Record<string, FredCadence> = {
  DGS10: 'daily',
  DGS30: 'daily',
  T10YIE: 'daily',
  T10Y2Y: 'daily',
  VIXCLS: 'daily',
  BAMLH0A0HYM2: 'daily',
  STLFSI4: 'weekly',
  WALCL: 'weekly',
  WRESBAL: 'weekly',
  RRPONTSYD: 'daily',
  WTREGEN: 'weekly',
  WRMFNS: 'weekly',
  M2SL: 'monthly',
  WM2NS: 'weekly',
  UNRATE: 'monthly',
  ICSA: 'weekly',
  SOFR: 'daily',
  EFFR: 'daily',
  IORB: 'daily',
  INDPRO: 'monthly',
  FEDERAL_DEBT_GDP: 'monthly', // 분기 발표 but monthly cadence 로 체크 충분
};

function ageDaysFromDate(date: string): number {
  return Math.floor((Date.now() - new Date(`${date}T00:00:00Z`).getTime()) / 86400000);
}

export function shouldRefreshFredSeries(
  key: string,
  cachedDate: string | null | undefined,
  probeDate: string | null | undefined,
): boolean {
  if (!cachedDate) return true;
  const cadence = FRED_SERIES_CADENCE[key] ?? 'daily';
  if (cadence === 'daily') {
    if (!probeDate) return true;
    return cachedDate < probeDate;
  }
  const ageDays = ageDaysFromDate(cachedDate);
  if (cadence === 'weekly') return ageDays >= 8;
  return ageDays >= 35;
}

function getFredLiveCacheKey(key: string) {
  return `fred-live-${key}`;
}

function getFredLiveCacheTtlMs(key: string): number {
  if (['WALCL', 'WRESBAL', 'RRPONTSYD', 'WTREGEN', 'WRMFNS', 'WM2NS', 'ICSA', 'STLFSI4'].includes(key)) {
    return 36 * 60 * 60 * 1000;
  }
  if (['M2SL', 'UNRATE', 'INDPRO', 'FEDERAL_DEBT_GDP'].includes(key)) {
    return 7 * 24 * 60 * 60 * 1000;
  }
  return 12 * 60 * 60 * 1000;
}

function getDateRange(): { start: string; end: string } {
  const end = new Date();
  const start = new Date();
  start.setFullYear(start.getFullYear() - 3);
  return {
    start: start.toISOString().split('T')[0],
    end: end.toISOString().split('T')[0],
  };
}

async function mapWithConcurrency<T, R>(
  items: T[],
  limit: number,
  mapper: (item: T) => Promise<R>,
): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let cursor = 0;

  async function worker() {
    while (cursor < items.length) {
      const current = cursor;
      cursor += 1;
      results[current] = await mapper(items[current]);
    }
  }

  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, () => worker()));
  return results;
}

async function fetchSeriesLatest(seriesId: string, apiKey: string, retries = 1, timeoutMs = 10000): Promise<MarketDataPoint[]> {
  // 5분 snapshot 에서는 최신 non-dot 값만 필요하다.
  // observation_start/end 로 3년 범위를 매번 요청하면 FRED/Akamai 차단 빈도가 높아져 latest 쿼리는 최소화한다.
  const url = `${FRED_BASE}?series_id=${seriesId}&api_key=${apiKey}&file_type=json&sort_order=desc&limit=10`;

  let lastErr: unknown = null;
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const { data } = await axios.get(url, { timeout: timeoutMs, headers: FRED_HEADERS });
      const observations = data.observations || [];
      return observations
        .filter((obs: any) => obs.value !== '.')
        .map((obs: any) => ({
          code: seriesId,
          value: parseFloat(obs.value),
          date: obs.date,
          source: 'FRED' as const,
        }));
    } catch (err) {
      lastErr = err;
      if (attempt < retries) {
        await new Promise((r) => setTimeout(r, 350 + Math.random() * 450));
        continue;
      }
    }
  }
  const msg = (lastErr as { response?: { status?: number }; message?: string })?.response?.status || (lastErr as { message?: string })?.message;
  throw new Error(`FRED ${seriesId} fetch failed after ${retries + 1} attempts: ${msg}`);
}

async function probeFredSource(apiKey: string): Promise<{ ok: boolean; point?: MarketDataPoint; reason?: string }> {
  const [, seriesId] = FRED_PROBE_SERIES;
  try {
    // 2026-04 개선: probe 는 fail-fast (timeout 5s, retry 0). 전체 cycle 지연 방지.
    const points = await fetchSeriesLatest(seriesId, apiKey, 0, 5000);
    return { ok: true, point: points[0] };
  } catch (error) {
    return {
      ok: false,
      reason: (error as Error)?.message || String(error),
    };
  }
}

/**
 * 22개 시리즈 병렬 호출 시 일부가 transient (네트워크/rate-limit) 로 drop 되면
 * raw 에서 해당 키가 누락된 채로 derived/signals 가 돌아 신호 왜곡. 대책:
 * 1) fetchSeries 에 1회 retry (위)
 * 2) allSettled 후 실패/빈 결과를 readHistory('fred', key) 마지막 값으로 fallback
 * 3) 실패 사유 명시 로깅 (silent drop 금지)
 */
export async function fetchAllFred(apiKey: string): Promise<Record<string, MarketDataPoint>> {
  const results: Record<string, MarketDataPoint> = {};
  const entries = Object.entries(FRED_SERIES);
  const startedAt = Date.now();
  const freshCacheKeys: string[] = [];
  const staleCacheKeys: string[] = [];
  const liveKeys: string[] = [];
  const skippedProbeKeys: string[] = [];
  const fallbackKeys: string[] = [];
  const failedKeys: string[] = [];

  const now = Date.now();
  const needsVerification = now - lastFredProbeAt >= FRED_VERIFY_INTERVAL_MS;
  let sourceProbeOk = lastFredProbeOk;
  let sourceProbeReason: string | null = null;

  if (needsVerification) {
    const probe = await probeFredSource(apiKey);
    lastFredProbeAt = now;
    lastFredProbeOk = probe.ok;
    sourceProbeOk = probe.ok;
    sourceProbeReason = probe.reason ?? null;

    if (probe.ok && probe.point) {
      const [probeKey] = FRED_PROBE_SERIES;
      await writeSourceCache(getFredLiveCacheKey(probeKey), probe.point, {
        seriesId: probe.point.code,
        source: 'live-probe',
      });
      results[probeKey] = probe.point;
      liveKeys.push(probeKey);
    } else {
      log.warn({
        probeSeries: FRED_PROBE_SERIES[0],
        reason: sourceProbeReason,
      }, 'fred source verification probe failed, skipping live refresh for this cycle');
    }
  }

  const pendingLiveEntries: Array<[string, string]> = [];
  const probeDate = results[FRED_PROBE_SERIES[0]]?.date ?? null;
  for (const [key, seriesId] of entries) {
    if (key === FRED_PROBE_SERIES[0] && results[key]) {
      continue;
    }
    const cached = await readSourceCacheWithin<MarketDataPoint>(
      getFredLiveCacheKey(key),
      getFredLiveCacheTtlMs(key),
    );
    if (cached && (!needsVerification || !shouldRefreshFredSeries(key, cached.value.date, probeDate))) {
      results[key] = cached.value;
      freshCacheKeys.push(key);
      if (needsVerification) {
        skippedProbeKeys.push(key);
      }
      continue;
    }
    if (sourceProbeOk) {
      pendingLiveEntries.push([key, seriesId]);
      continue;
    }
    if (cached) {
      results[key] = cached.value;
      freshCacheKeys.push(key);
      skippedProbeKeys.push(key);
    } else {
      pendingLiveEntries.push([key, seriesId]);
    }
  }

  const settled = sourceProbeOk
    ? await mapWithConcurrency(
        pendingLiveEntries,
        FRED_LIVE_CONCURRENCY,
        async ([key, seriesId]) => {
          try {
            const value = await fetchSeriesLatest(seriesId, apiKey);
            if (value[0]) {
              await writeSourceCache(getFredLiveCacheKey(key), value[0], { seriesId });
            }
            return { key, seriesId, status: 'fulfilled' as const, value };
          } catch (error) {
            return { key, seriesId, status: 'rejected' as const, reason: error };
          }
        },
      )
    : pendingLiveEntries.map(([key, seriesId]) => ({
        key,
        seriesId,
        status: 'rejected' as const,
        reason: new Error(`fred source probe failed: ${sourceProbeReason ?? 'unreachable'}`),
      }));

  const { readHistory } = await import('../state/history-store');

  for (const result of settled) {
    const { key, seriesId } = result;
    if (result.status === 'fulfilled' && result.value.length > 0) {
      results[key] = result.value[0];
      liveKeys.push(key);
      continue;
    }

    const staleCached = await readSourceCache<MarketDataPoint>(getFredLiveCacheKey(key));
    if (staleCached?.value) {
      results[key] = staleCached.value;
      staleCacheKeys.push(key);
      const reason = result.status === 'rejected' ? (result.reason as Error)?.message : 'empty observations';
      log.warn({
        key,
        seriesId,
        cachedDate: staleCached.value.date,
        cachedValue: staleCached.value.value,
        cachedUpdatedAt: staleCached.updatedAt,
        reason,
      }, 'fred live fetch failed, stale source cache applied');
      continue;
    }

    try {
      const hist = await readHistory('fred', key);
      if (hist.length > 0) {
        const last = hist[hist.length - 1];
        results[key] = {
          code: seriesId,
          value: last.value,
          date: last.date,
          source: 'FRED',
        };
        await writeSourceCache(getFredLiveCacheKey(key), results[key], {
          seriesId,
          source: 'history-fallback',
        });
        const reason = result.status === 'rejected' ? (result.reason as Error)?.message : 'empty observations';
        fallbackKeys.push(key);
        log.warn({
          key,
          seriesId,
          fallbackDate: last.date,
          fallbackValue: last.value,
          reason,
        }, 'fred live fetch failed, history fallback applied');
      } else {
        const reason = result.status === 'rejected' ? (result.reason as Error)?.message : 'empty observations';
        failedKeys.push(key);
        log.error({
          key,
          seriesId,
          reason,
        }, 'fred live fetch failed and no history fallback available');
      }
    } catch (histErr) {
      failedKeys.push(key);
      log.error({
        key,
        seriesId,
        error: serializeError(histErr),
      }, 'fred history fallback failed');
    }
  }

  log.info({
    durationMs: Date.now() - startedAt,
    requestedSeries: entries.length,
    returnedSeries: Object.keys(results).length,
    needsVerification,
    sourceProbeOk,
    sourceProbeReason,
    freshCacheCount: freshCacheKeys.length,
    staleCacheCount: staleCacheKeys.length,
    liveCount: liveKeys.length,
    skippedProbeCount: skippedProbeKeys.length,
    fallbackCount: fallbackKeys.length,
    failedCount: failedKeys.length,
    freshCacheKeys,
    staleCacheKeys,
    liveKeys,
    skippedProbeKeys,
    fallbackKeys,
    failedKeys,
  }, 'fred collection completed');

  return results;
}

export async function fetchFredHistory(
  seriesId: string,
  apiKey: string,
  limit = 200
): Promise<MarketDataPoint[]> {
  const { start, end } = getDateRange();
  const url = `${FRED_BASE}?series_id=${seriesId}&api_key=${apiKey}&file_type=json&observation_start=${start}&observation_end=${end}&sort_order=desc&limit=${limit}`;
  let lastErr: unknown = null;
  for (let attempt = 0; attempt <= 1; attempt += 1) {
    try {
      const { data } = await axios.get(url, { timeout: 10000, headers: FRED_HEADERS });
      return (data.observations || [])
        .filter((obs: any) => obs.value !== '.')
        .map((obs: any) => ({
          code: seriesId,
          value: parseFloat(obs.value),
          date: obs.date,
          source: 'FRED' as const,
        }));
    } catch (error) {
      lastErr = error;
      if (attempt < 1) {
        await new Promise((resolve) => setTimeout(resolve, 250 + Math.random() * 250));
      }
    }
  }
  const msg =
    (lastErr as { response?: { status?: number }; message?: string })?.response?.status ||
    (lastErr as { message?: string })?.message;
  throw new Error(`FRED history ${seriesId} fetch failed after 2 attempts: ${msg}`);
}

export async function fetchFredHistoryFrom(
  seriesId: string,
  apiKey: string,
  observationStart: string
): Promise<MarketDataPoint[]> {
  const end = new Date().toISOString().split('T')[0];
  const url = `${FRED_BASE}?series_id=${seriesId}&api_key=${apiKey}&file_type=json&observation_start=${observationStart}&observation_end=${end}&sort_order=asc&limit=100000`;

  const { data } = await axios.get(url, { headers: FRED_HEADERS, timeout: 10000 });
  return (data.observations || [])
    .filter((obs: any) => obs.value !== '.')
    .map((obs: any) => ({
      code: seriesId,
      value: parseFloat(obs.value),
      date: obs.date,
      source: 'FRED' as const,
    }));
}
