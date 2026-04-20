/**
 * Yahoo Finance quoteSummary recommendationTrend 수집 (16차 Phase 2 C1).
 *
 * 영상4 §기관 "말은 거짓말 할 수 있지만 돈은 거짓말을 하지 않거든요" 정합 준비.
 *
 * 애널리스트 컨센서스 = "말", 13F 기관 포지션 = "돈". 둘의 괴리 측정용.
 *
 * NASDAQ 메가캡 7종 rating: strongBuy / buy / hold / sell / strongSell.
 * value = 평균 점수 (-2=극매도, +2=극매수).
 */

import axios from 'axios';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const log = childLogger({ module: 'collector.analyst-consensus' });
const CACHE_KEY = 'analyst-consensus-nasdaq-megacap';
const FRESH_MS = 6 * 60 * 60 * 1000; // 6시간
const STALE_MS = 7 * 24 * 60 * 60 * 1000;

const MEGACAP = ['AAPL', 'MSFT', 'GOOGL', 'AMZN', 'NVDA', 'META', 'TSLA'];

interface Trend {
  period: string;
  strongBuy: number;
  buy: number;
  hold: number;
  sell: number;
  strongSell: number;
}

function scoreFromTrend(t: Trend): number {
  const total = t.strongBuy + t.buy + t.hold + t.sell + t.strongSell;
  if (total === 0) return 0;
  const weighted = t.strongBuy * 2 + t.buy * 1 + t.hold * 0 + t.sell * -1 + t.strongSell * -2;
  return weighted / total;
}

// Yahoo Finance crumb + cookie 인증 (2024+ 필수)
let cachedCrumb: { crumb: string; cookie: string; at: number } | null = null;
const CRUMB_TTL_MS = 60 * 60 * 1000; // 1시간

async function getCrumb(): Promise<{ crumb: string; cookie: string } | null> {
  if (cachedCrumb && Date.now() - cachedCrumb.at < CRUMB_TTL_MS) {
    return { crumb: cachedCrumb.crumb, cookie: cachedCrumb.cookie };
  }
  try {
    // 1) cookie 획득
    const step1 = await axios.get('https://fc.yahoo.com', {
      headers: { 'User-Agent': 'Mozilla/5.0' },
      timeout: 10000,
      validateStatus: () => true,
      maxRedirects: 5,
    });
    const setCookie = step1.headers['set-cookie'];
    const cookie = Array.isArray(setCookie) ? setCookie.map((c) => c.split(';')[0]).join('; ') : '';
    if (!cookie) return null;
    // 2) crumb 획득
    const step2 = await axios.get('https://query2.finance.yahoo.com/v1/test/getcrumb', {
      headers: { 'User-Agent': 'Mozilla/5.0', Cookie: cookie },
      timeout: 10000,
    });
    const crumb = typeof step2.data === 'string' ? step2.data.trim() : '';
    if (!crumb) return null;
    cachedCrumb = { crumb, cookie, at: Date.now() };
    return { crumb, cookie };
  } catch (err) {
    log.warn({ error: serializeError(err) }, 'crumb acquire failed');
    return null;
  }
}

async function fetchOneTicker(ticker: string): Promise<{ ticker: string; score: number; upsidePct?: number } | null> {
  try {
    const auth = await getCrumb();
    if (!auth) return null;
    // 18차 P2#10: financialData 모듈 동시 요청하여 targetMean/current 획득 → upside % 계산.
    const url = `https://query2.finance.yahoo.com/v10/finance/quoteSummary/${ticker}?modules=recommendationTrend,financialData&crumb=${encodeURIComponent(auth.crumb)}`;
    const { data } = await axios.get(url, {
      headers: {
        'User-Agent': 'Mozilla/5.0',
        Accept: 'application/json',
        Cookie: auth.cookie,
      },
      timeout: 10000,
    });
    const trends: Trend[] = data?.quoteSummary?.result?.[0]?.recommendationTrend?.trend || [];
    if (trends.length === 0) return null;
    const current = trends.find((t) => t.period === '0m') || trends[0];
    const fd = data?.quoteSummary?.result?.[0]?.financialData;
    const targetMean = fd?.targetMeanPrice?.raw ?? fd?.targetMeanPrice;
    const currentPrice = fd?.currentPrice?.raw ?? fd?.currentPrice;
    let upsidePct: number | undefined;
    if (typeof targetMean === 'number' && typeof currentPrice === 'number' && currentPrice > 0) {
      upsidePct = parseFloat((((targetMean - currentPrice) / currentPrice) * 100).toFixed(2));
    }
    return { ticker, score: scoreFromTrend(current), upsidePct };
  } catch (error: any) {
    if (error?.response?.status === 401) cachedCrumb = null;
    log.warn({ ticker, error: serializeError(error) }, 'analyst fetch failed');
    return null;
  }
}

export interface AnalystConsensus {
  avgScore: number;               // -2~+2
  perTicker: Record<string, number>;
  tickerCount: number;
  fetchedAt: string;
  // 18차 P2#10: 목표가 대비 상승여력
  avgUpsidePct?: number;
  perTickerUpsidePct?: Record<string, number>;
}

async function fetchLive(): Promise<AnalystConsensus | null> {
  const results: Array<{ ticker: string; score: number; upsidePct?: number }> = [];
  for (const t of MEGACAP) {
    const r = await fetchOneTicker(t);
    if (r) results.push(r);
    await new Promise((rs) => setTimeout(rs, 200));
  }
  if (results.length < 3) return null;
  const avg = results.reduce((s, r) => s + r.score, 0) / results.length;
  const perTicker: Record<string, number> = {};
  for (const r of results) perTicker[r.ticker] = parseFloat(r.score.toFixed(3));
  const upsideValues = results.filter((r) => typeof r.upsidePct === 'number').map((r) => r.upsidePct!);
  const avgUpsidePct = upsideValues.length > 0
    ? parseFloat((upsideValues.reduce((a, b) => a + b, 0) / upsideValues.length).toFixed(2))
    : undefined;
  const perTickerUpsidePct: Record<string, number> = {};
  for (const r of results) {
    if (typeof r.upsidePct === 'number') perTickerUpsidePct[r.ticker] = r.upsidePct;
  }
  return {
    avgScore: parseFloat(avg.toFixed(3)),
    perTicker,
    tickerCount: results.length,
    fetchedAt: new Date().toISOString(),
    avgUpsidePct,
    perTickerUpsidePct: Object.keys(perTickerUpsidePct).length ? perTickerUpsidePct : undefined,
  };
}

export async function fetchAnalystConsensus(): Promise<AnalystConsensus | null> {
  const cached = await readSourceCacheWithin<AnalystConsensus>(CACHE_KEY, FRESH_MS);
  if (cached) {
    log.info({ ageHours: Math.round(cached.ageMs / 3600000), avg: cached.value.avgScore }, 'analyst consensus cache hit');
    return cached.value;
  }
  const live = await fetchLive();
  if (live) {
    await writeSourceCache(CACHE_KEY, live);
    log.info({ avgScore: live.avgScore, tickerCount: live.tickerCount }, 'analyst consensus live collected');
    return live;
  }
  const stale = await readSourceCacheWithin<AnalystConsensus>(CACHE_KEY, STALE_MS);
  if (stale) {
    log.warn({ ageMs: stale.ageMs }, 'analyst consensus stale fallback');
    return stale.value;
  }
  return null;
}
