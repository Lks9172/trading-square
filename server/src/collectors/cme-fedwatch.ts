/**
 * CME FedWatch 확률 추정 (19차 Phase 1 #3).
 *
 * 노션 §"CME Group: 시장 분석". 실제 CME FedWatch Tool 은 JS 렌더링 페이지라 스크래핑 어려움.
 * 대안: 30-day Fed Funds Futures (ZQ=F) Yahoo continuous front-month 가격으로 implied rate 산출
 *      → current target rate 와의 갭으로 25bp 인하/인상 확률 근사.
 *
 * 공식:
 *   impliedRate = 100 - frontMonth_close
 *   target      = (target_lower + target_upper) / 2  (FRED DFEDTARU/DFEDTARL)
 *   gapBp       = (impliedRate - target) * 100
 *   gapBp ≤ -10  → 인하 우세
 *   gapBp ≥  10  → 인상 우세
 *   |gapBp| < 5  → 동결 우세
 *
 * 실제 FedWatch 와 1:1 일치하지 않지만 방향성과 강도는 유사. ZQ 결제는 월 평균
 * effective rate 라 단월 cut 한 번 = 약 12~13bp gap 으로 나타남.
 *
 * 캐시: fresh 4h / stale 24h.
 */

import axios from 'axios';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';
import { getYahooCrumb, invalidateYahooCrumb } from '../utils/yahoo-auth';

const log = childLogger({ module: 'collector.cme-fedwatch' });
const CACHE_KEY = 'cme-fedwatch-probabilities';
const FRESH_MS = 4 * 60 * 60 * 1000;
const STALE_MS = 24 * 60 * 60 * 1000;

export interface FedWatchSnapshot {
  impliedRatePct: number;
  currentTargetMidPct: number;
  gapBp: number;
  cutProb25bp: number;
  cutProb50bp: number;        // 25차 신규
  hikeProb25bp: number;
  hikeProb50bp: number;       // 25차 신규
  holdProb: number;
  source: 'zq-yahoo' | 'manual-fallback';
  fetchedAt: string;
}

// ZQ Fed Funds futures front-month price 다중 소스 fetch.
// 우선순위: 1) stooq CSV (무인증) → 2) Yahoo crumb 인증 quoteSummary → 3) Yahoo chart API.
async function fetchZqFront(): Promise<number | null> {
  // ── 1. stooq.com (무인증, ZQ continuous) ──
  try {
    const { data } = await axios.get<string>(
      'https://stooq.com/q/d/l/?s=zq.f&i=d',
      {
        headers: { 'User-Agent': 'Mozilla/5.0' },
        timeout: 10000,
        responseType: 'text',
      },
    );
    if (typeof data === 'string' && data.includes('Date,Open,High,Low,Close')) {
      const lines = data.trim().split('\n').filter((l) => l && !l.startsWith('Date'));
      const last = lines[lines.length - 1];
      if (last) {
        const parts = last.split(',');
        const close = parseFloat(parts[4]);
        if (Number.isFinite(close) && close > 50 && close < 105) {
          log.info({ source: 'stooq', close }, 'ZQ stooq fetched');
          return close;
        }
      }
    }
  } catch (err) {
    log.warn({ source: 'stooq', error: serializeError(err) }, 'stooq ZQ fetch failed');
  }

  // ── 2. Yahoo crumb 인증 quoteSummary (v10) ──
  try {
    const auth = await getYahooCrumb();
    if (auth) {
      const { data } = await axios.get(
        `https://query2.finance.yahoo.com/v10/finance/quoteSummary/ZQ%3DF?modules=price&crumb=${encodeURIComponent(auth.crumb)}`,
        {
          headers: { 'User-Agent': 'Mozilla/5.0', Cookie: auth.cookie, Accept: 'application/json' },
          timeout: 10000,
        },
      );
      const p = data?.quoteSummary?.result?.[0]?.price;
      const price = p?.regularMarketPrice?.raw ?? p?.postMarketPrice?.raw ?? p?.preMarketPrice?.raw;
      if (typeof price === 'number' && Number.isFinite(price) && price > 50) {
        log.info({ source: 'yahoo-crumb', price }, 'ZQ yahoo-crumb fetched');
        return price;
      }
    }
  } catch (err: any) {
    if (err?.response?.status === 401) invalidateYahooCrumb();
    log.warn({ source: 'yahoo-crumb', error: serializeError(err) }, 'yahoo-crumb ZQ fetch failed');
  }

  // ── 3. Yahoo chart API (인증 불필요한 경로) ──
  try {
    const period2 = Math.floor(Date.now() / 1000);
    const period1 = period2 - 7 * 86400;
    const { data } = await axios.get(
      `https://query1.finance.yahoo.com/v8/finance/chart/ZQ%3DF?period1=${period1}&period2=${period2}&interval=1d`,
      {
        headers: { 'User-Agent': 'Mozilla/5.0' },
        timeout: 10000,
      },
    );
    const closes: number[] = data?.chart?.result?.[0]?.indicators?.quote?.[0]?.close ?? [];
    const last = closes.filter((v) => Number.isFinite(v) && v > 50).pop();
    if (typeof last === 'number') {
      log.info({ source: 'yahoo-chart', last }, 'ZQ yahoo-chart fetched');
      return last;
    }
  } catch (err) {
    log.warn({ source: 'yahoo-chart', error: serializeError(err) }, 'yahoo-chart ZQ fetch failed');
  }

  return null;
}

async function fetchTargetMid(): Promise<number | null> {
  // 우선 DFEDTARU/L 평균, 없으면 EFFR (effective rate, 항상 수집됨) fallback.
  try {
    const { readHistory } = await import('../state/history-store');
    const upper = await readHistory('fred', 'DFEDTARU');
    const lower = await readHistory('fred', 'DFEDTARL');
    const u = upper.length > 0 ? upper[upper.length - 1].value : null;
    const l = lower.length > 0 ? lower[lower.length - 1].value : null;
    if (typeof u === 'number' && typeof l === 'number') return (u + l) / 2;
    // Fallback: EFFR (effective rate). target mid 와 1-2bp 차이만 있어 ZQ 갭 계산에 거의 영향 없음.
    const effr = await readHistory('fred', 'EFFR');
    if (effr.length > 0) {
      const v = effr[effr.length - 1].value;
      if (typeof v === 'number') return v;
    }
    return null;
  } catch {
    return null;
  }
}

function probsFromGap(gapBp: number): { cut25: number; cut50: number; hike25: number; hike50: number; hold: number } {
  // 25차: 다단계 확률 분포 (-50bp / -25bp / hold / +25bp / +50bp).
  // 단순 선형 매핑이지만 ±50bp 까지 분리해 시장 매파/비둘기파 강도 차등화.
  let cut25 = 0;
  let cut50 = 0;
  let hike25 = 0;
  let hike50 = 0;
  if (gapBp <= -45) {
    // -45bp 이하: 50bp 인하 우세
    cut50 = Math.min(100, ((-gapBp - 45) / 15 + 1) * 50);
    cut25 = Math.max(0, 100 - cut50);
  } else if (gapBp <= -20) {
    // -20 ~ -45bp: 25bp 인하 우세
    cut25 = ((-gapBp - 5) / 25) * 100;
    cut25 = Math.min(100, cut25);
  } else if (gapBp <= -5) {
    // -5 ~ -20bp: 25bp 인하 약세
    cut25 = ((-gapBp - 5) / 25) * 100;
  }
  if (gapBp >= 45) {
    hike50 = Math.min(100, ((gapBp - 45) / 15 + 1) * 50);
    hike25 = Math.max(0, 100 - hike50);
  } else if (gapBp >= 20) {
    hike25 = ((gapBp - 5) / 25) * 100;
    hike25 = Math.min(100, hike25);
  } else if (gapBp >= 5) {
    hike25 = ((gapBp - 5) / 25) * 100;
  }
  const hold = Math.max(0, 100 - cut25 - cut50 - hike25 - hike50);
  return {
    cut25: parseFloat(cut25.toFixed(1)),
    cut50: parseFloat(cut50.toFixed(1)),
    hike25: parseFloat(hike25.toFixed(1)),
    hike50: parseFloat(hike50.toFixed(1)),
    hold: parseFloat(hold.toFixed(1)),
  };
}

export async function fetchFedWatchProbabilities(): Promise<FedWatchSnapshot | null> {
  const cached = await readSourceCacheWithin<FedWatchSnapshot>(CACHE_KEY, FRESH_MS);
  if (cached) {
    log.info({ ageMs: cached.ageMs }, 'fedwatch cache hit');
    return cached.value;
  }
  const [zq, targetMid] = await Promise.all([fetchZqFront(), fetchTargetMid()]);
  if (zq === null || targetMid === null) {
    const stale = await readSourceCacheWithin<FedWatchSnapshot>(CACHE_KEY, STALE_MS);
    if (stale) return stale.value;
    return null;
  }
  const impliedRate = 100 - zq;
  const gapBp = (impliedRate - targetMid) * 100;
  const probs = probsFromGap(gapBp);
  const snapshot: FedWatchSnapshot = {
    impliedRatePct: parseFloat(impliedRate.toFixed(3)),
    currentTargetMidPct: parseFloat(targetMid.toFixed(3)),
    gapBp: parseFloat(gapBp.toFixed(1)),
    cutProb25bp: probs.cut25,
    cutProb50bp: probs.cut50,
    hikeProb25bp: probs.hike25,
    hikeProb50bp: probs.hike50,
    holdProb: probs.hold,
    source: 'zq-yahoo',
    fetchedAt: new Date().toISOString(),
  };
  await writeSourceCache(CACHE_KEY, snapshot);
  log.info({ gapBp: snapshot.gapBp, cut25: snapshot.cutProb25bp }, 'fedwatch live computed');
  return snapshot;
}
