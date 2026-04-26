import axios from 'axios';
import XLSX from 'xlsx';
import { readHistory } from '../state/history-store';
import { fetchFredHistory } from './fred';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';
import {
  deriveCBBuyingFromSeries,
  derivePolicyDirectionFromSeries,
  ManualInputsState,
} from '../services/policy-inputs';

const log = childLogger({ module: 'collector.auto-manual' });

type AutoManualInputs = ManualInputsState;

const GPR_CACHE_KEY = 'auto-manual-gpr';
const GPR_STALE_MS = 30 * 24 * 60 * 60 * 1000;
const ISM_OFFICIAL_CACHE_KEY = 'auto-manual-ism-official';
const ISM_OFFICIAL_FRESH_MS = 20 * 24 * 60 * 60 * 1000;
const ISM_OFFICIAL_STALE_MS = 45 * 24 * 60 * 60 * 1000;

function getFredHistoryFreshnessMs(seriesId: string): number {
  if (['ICSA'].includes(seriesId)) return 10 * 24 * 60 * 60 * 1000;
  if (['INDPRO', 'PAYEMS'].includes(seriesId)) return 45 * 24 * 60 * 60 * 1000;
  return 7 * 24 * 60 * 60 * 1000;
}

async function fetchFredHistoryWithFallback(seriesId: string, apiKey: string, limit: number) {
  const hist = await readHistory('fred', seriesId);
  if (hist.length > 0) {
    const latestDateMs = new Date(hist[hist.length - 1].date).getTime();
    const freshnessMs = getFredHistoryFreshnessMs(seriesId);
    if (Number.isFinite(latestDateMs) && Date.now() - latestDateMs <= freshnessMs) {
      return hist
        .slice(-limit)
        .reverse()
        .map((point) => ({
          code: seriesId,
          value: point.value,
          date: point.date,
          source: 'FRED' as const,
        }));
    }
  }

  try {
    return await fetchFredHistory(seriesId, apiKey, limit);
  } catch (error) {
    if (!hist.length) {
      throw error;
    }
    const fallback = hist
      .slice(-limit)
      .reverse()
      .map((point) => ({
        code: seriesId,
        value: point.value,
        date: point.date,
        source: 'FRED' as const,
      }));
    log.warn(
      {
        seriesId,
        limit,
        fallbackCount: fallback.length,
        latestDate: fallback[0]?.date ?? null,
        error: serializeError(error),
      },
      'auto input fred history fetch failed, using stored history fallback',
    );
    return fallback;
  }
}

async function fetchGPR(): Promise<number> {
  try {
    const url = 'https://www.matteoiacoviello.com/gpr_files/data_gpr_daily_recent.xls';
    const { data } = await axios.get(url, { responseType: 'arraybuffer', timeout: 30000 });
    const wb = XLSX.read(data, { type: 'buffer' });
    const ws = wb.Sheets[wb.SheetNames[0]];
    const rows: any[][] = XLSX.utils.sheet_to_json(ws, { header: 1 });

    const gprColIdx = 2;
    const lastRows = rows.slice(-30).filter((r) => typeof r[gprColIdx] === 'number');
    if (!lastRows.length) return 100;

    const avg = lastRows.reduce((sum, r) => sum + r[gprColIdx], 0) / lastRows.length;
    await writeSourceCache(GPR_CACHE_KEY, { value: avg });
    return avg;
  } catch (error) {
    const cached = await readSourceCacheWithin<{ value: number }>(GPR_CACHE_KEY, GPR_STALE_MS);
    if (cached) {
      log.warn({ ageMs: cached.ageMs, updatedAt: cached.updatedAt, error: serializeError(error) }, 'gpr fetch failed, serving cached value');
      return cached.value.value;
    }
    throw error;
  }
}

function gprToGeoRisk(gpr: number): number {
  if (gpr < 80) return 0;
  if (gpr < 100) return 1;
  if (gpr < 130) return 2;
  if (gpr < 200) return 3;
  return 4;
}

async function computePolicyDirection(apiKey: string): Promise<number> {
  const [effrHistory, t10y2yHistory, icsaHistory] = await Promise.all([
    fetchFredHistoryWithFallback('EFFR', apiKey, 60),
    fetchFredHistoryWithFallback('T10Y2Y', apiKey, 10),
    fetchFredHistoryWithFallback('ICSA', apiKey, 10),
  ]);
  return derivePolicyDirectionFromSeries(effrHistory, t10y2yHistory, icsaHistory);
}

async function detectCBBuying(): Promise<boolean> {
  const goldHistory = await readHistory('yahoo', 'GOLD');
  const dxyHistory = await readHistory('yahoo', 'DXY');
  return deriveCBBuyingFromSeries([...goldHistory].reverse(), [...dxyHistory].reverse());
}

/**
 * investing.com economic calendar 의 ISM Manufacturing PMI 페이지에서 최신 공식 Actual 값.
 * URL 고정(event-id=173), 월 1회 갱신이라 레이아웃 변경 리스크 낮음.
 * 실패 시 null → 호출부에서 FRED proxy fallback.
 *
 * 파싱 타겟:
 *   <tr><td>Apr 01, 2026 (Mar)</td><td>14:00</td><td>52.7</td>...
 */
async function fetchISMFromInvesting(): Promise<{ value: number; asOf: string } | null> {
  const UA =
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
  try {
    const freshCached = await readSourceCacheWithin<{ value: number; asOf: string }>(
      ISM_OFFICIAL_CACHE_KEY,
      ISM_OFFICIAL_FRESH_MS,
    );
    if (freshCached) {
      log.info({
        ageMs: freshCached.ageMs,
        updatedAt: freshCached.updatedAt,
        asOf: freshCached.value.asOf,
      }, 'ism official source-fresh cache hit');
      return freshCached.value;
    }

    const { data: html } = await axios.get<string>(
      'https://www.investing.com/economic-calendar/ism-manufacturing-pmi-173',
      {
        headers: {
          'User-Agent': UA,
          Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
          Referer: 'https://www.investing.com/',
        },
        timeout: 15000,
      },
    );
    // 첫 번째 매치 = 최신 발표치
    const m = html.match(
      /<tr[^>]*>\s*<td[^>]*>([A-Z][a-z]{2}\s+\d{1,2},\s+\d{4}\s*\([^)]+\))<\/td>\s*<td[^>]*>[^<]*<\/td>\s*<td[^>]*>([0-9]+\.[0-9]+)<\/td>/,
    );
    if (!m) return null;
    const value = parseFloat(m[2]);
    if (!Number.isFinite(value) || value < 20 || value > 80) return null;
    const parsed = { value, asOf: m[1] };
    await writeSourceCache(ISM_OFFICIAL_CACHE_KEY, parsed);
    return parsed;
  } catch (error) {
    const cached = await readSourceCacheWithin<{ value: number; asOf: string }>(ISM_OFFICIAL_CACHE_KEY, ISM_OFFICIAL_STALE_MS);
    if (cached) {
      log.warn({ ageMs: cached.ageMs, updatedAt: cached.updatedAt, error: serializeError(error) }, 'ism official fetch failed, serving cached value');
      return cached.value;
    }
    return null;
  }
}

/**
 * ISM proxy: FRED 기반 복합 지표 (공식 소스 실패 시 fallback).
 *
 * INDPRO(산업생산) MoM + ICSA(실업수당) 추세 + PAYEMS(비농업고용) MoM 을 조합해
 * PMI 50 을 중심으로 ±20 범위의 proxy 값을 산출.
 */
async function fetchISMProxy(apiKey: string): Promise<number | null> {
  try {
    const [indproHist, icsaHist, payemsHist] = await Promise.all([
      fetchFredHistoryWithFallback('INDPRO', apiKey, 6),
      fetchFredHistoryWithFallback('ICSA', apiKey, 10),
      fetchFredHistoryWithFallback('PAYEMS', apiKey, 6),
    ]);

    if (indproHist.length < 3) return null;

    const indCurrent = indproHist[0].value;
    const indPrev = indproHist[1].value;
    const indPrev2 = indproHist[2].value;
    const indMom = ((indCurrent - indPrev) / indPrev) * 100;
    const indExpanding = indCurrent > indPrev && indPrev > indPrev2;
    const indContracting = indCurrent < indPrev && indPrev < indPrev2;

    // ICSA 4주 평균 추세 (증가 = 고용악화 = PMI 하방)
    let icsaAdj = 0;
    if (icsaHist.length >= 8) {
      const r = icsaHist.slice(0, 4).reduce((s, p) => s + p.value, 0) / 4;
      const o = icsaHist.slice(4, 8).reduce((s, p) => s + p.value, 0) / 4;
      const delta = (r - o) / o;
      if (delta < -0.05) icsaAdj = 2;
      else if (delta < -0.02) icsaAdj = 1;
      else if (delta > 0.05) icsaAdj = -2;
      else if (delta > 0.02) icsaAdj = -1;
    }

    // PAYEMS MoM (양수 = 고용 확장)
    let payemsAdj = 0;
    if (payemsHist.length >= 2) {
      const mom = ((payemsHist[0].value - payemsHist[1].value) / payemsHist[1].value) * 100;
      if (mom > 0.2) payemsAdj = 1;
      else if (mom > 0.05) payemsAdj = 0.5;
      else if (mom < -0.1) payemsAdj = -1;
    }

    let ismProxy = 50 + indMom * 5 + icsaAdj + payemsAdj;
    if (indExpanding) ismProxy = Math.max(ismProxy, 51);
    if (indContracting) ismProxy = Math.min(ismProxy, 49);
    return Math.max(30, Math.min(70, parseFloat(ismProxy.toFixed(1))));
  } catch {
    return null;
  }
}

export async function computeAutoManualInputs(apiKey: string): Promise<AutoManualInputs> {
  const startedAt = Date.now();
  const [gpr, policyDirection, cbBuying, ismOfficial, ismProxy] = await Promise.allSettled([
    fetchGPR(),
    computePolicyDirection(apiKey),
    detectCBBuying(),
    fetchISMFromInvesting(),
    fetchISMProxy(apiKey),
  ]);

  // 공식 값 우선 → 실패 시 proxy fallback
  let ismPmi: number | null = null;
  if (ismOfficial.status === 'fulfilled' && ismOfficial.value) {
    ismPmi = ismOfficial.value.value;
  } else if (ismProxy.status === 'fulfilled') {
    ismPmi = ismProxy.value;
  }

  if (gpr.status === 'rejected') log.warn({ input: 'geoRisk', error: serializeError(gpr.reason) }, 'auto input source failed');
  if (policyDirection.status === 'rejected') log.warn({ input: 'policyDirection', error: serializeError(policyDirection.reason) }, 'auto input source failed');
  if (cbBuying.status === 'rejected') log.warn({ input: 'cbBuying', error: serializeError(cbBuying.reason) }, 'auto input source failed');
  if (ismOfficial.status === 'rejected') log.warn({ input: 'ismOfficial', error: serializeError(ismOfficial.reason) }, 'auto input source failed');
  if (ismProxy.status === 'rejected') log.warn({ input: 'ismProxy', error: serializeError(ismProxy.reason) }, 'auto input source failed');

  log.info({
    durationMs: Date.now() - startedAt,
    geoRisk: gpr.status === 'fulfilled' ? gprToGeoRisk(gpr.value) : 2,
    policyDirection: policyDirection.status === 'fulfilled' ? policyDirection.value : 0,
    cbBuying: cbBuying.status === 'fulfilled' ? cbBuying.value : true,
    ismPmi,
    ismSource:
      ismOfficial.status === 'fulfilled' && ismOfficial.value
        ? 'official'
        : ismProxy.status === 'fulfilled' && ismProxy.value !== null
          ? 'proxy'
          : 'default-null',
  }, 'auto manual inputs computed');

  return {
    geoRisk: gpr.status === 'fulfilled' ? gprToGeoRisk(gpr.value) : 2,
    policyDirection: policyDirection.status === 'fulfilled' ? policyDirection.value : 0,
    cbBuying: cbBuying.status === 'fulfilled' ? cbBuying.value : true,
    ismPmi,
  };
}
