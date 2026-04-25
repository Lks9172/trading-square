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

const log = childLogger({ module: 'collector.cme-fedwatch' });
const CACHE_KEY = 'cme-fedwatch-probabilities';
const FRESH_MS = 4 * 60 * 60 * 1000;
const STALE_MS = 24 * 60 * 60 * 1000;

export interface FedWatchSnapshot {
  impliedRatePct: number;        // ZQ-implied effective rate
  currentTargetMidPct: number;   // 현재 target mid
  gapBp: number;                 // (implied - target) * 100
  cutProb25bp: number;           // 0~100
  hikeProb25bp: number;          // 0~100
  holdProb: number;              // 0~100
  source: 'zq-yahoo' | 'manual-fallback';
  fetchedAt: string;
}

async function fetchZqFront(): Promise<number | null> {
  try {
    const { data } = await axios.get(
      'https://query1.finance.yahoo.com/v7/finance/quote?symbols=ZQ%3DF',
      {
        headers: { 'User-Agent': 'Mozilla/5.0', Accept: 'application/json' },
        timeout: 10000,
      },
    );
    const r = data?.quoteResponse?.result?.[0];
    const price = r?.regularMarketPrice ?? r?.postMarketPrice ?? r?.preMarketPrice;
    if (typeof price === 'number' && Number.isFinite(price) && price > 50) return price;
    return null;
  } catch (err) {
    log.warn({ error: serializeError(err) }, 'ZQ=F fetch failed');
    return null;
  }
}

async function fetchTargetMid(): Promise<number | null> {
  // FRED DFEDTARU (upper) + DFEDTARL (lower) 평균. derived 에 raw 로 들어와 있을 가능성도 있지만
  // 여기서는 collectors 인덱스를 우회해 직접 history 로 접근.
  try {
    const { readHistory } = await import('../state/history-store');
    const upper = await readHistory('fred', 'DFEDTARU');
    const lower = await readHistory('fred', 'DFEDTARL');
    const u = upper.length > 0 ? upper[upper.length - 1].value : null;
    const l = lower.length > 0 ? lower[lower.length - 1].value : null;
    if (typeof u === 'number' && typeof l === 'number') return (u + l) / 2;
    return null;
  } catch {
    return null;
  }
}

function probsFromGap(gapBp: number): { cut25: number; hike25: number; hold: number } {
  // 단순 선형 매핑. -25bp gap = ~100% cut, +25bp gap = ~100% hike, 0 = ~100% hold.
  // 실제 시장에선 시간가치 + 다음 FOMC 까지 잔여일수에 따라 달라지지만, daily snapshot 용으로는 충분.
  let cut25 = 0;
  let hike25 = 0;
  let hold = 100;
  if (gapBp <= -25) cut25 = 100;
  else if (gapBp <= -5) cut25 = ((-gapBp - 5) / 20) * 100;
  if (gapBp >= 25) hike25 = 100;
  else if (gapBp >= 5) hike25 = ((gapBp - 5) / 20) * 100;
  hold = Math.max(0, 100 - cut25 - hike25);
  return {
    cut25: parseFloat(cut25.toFixed(1)),
    hike25: parseFloat(hike25.toFixed(1)),
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
    hikeProb25bp: probs.hike25,
    holdProb: probs.hold,
    source: 'zq-yahoo',
    fetchedAt: new Date().toISOString(),
  };
  await writeSourceCache(CACHE_KEY, snapshot);
  log.info({ gapBp: snapshot.gapBp, cut25: snapshot.cutProb25bp }, 'fedwatch live computed');
  return snapshot;
}
