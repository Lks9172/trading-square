import axios from 'axios';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

interface EarningsEvent {
  ticker: string;
  company: string;
  date: string;
  time: string;
}

// 18차 P2#11: 과거 분기 실적 서프라이즈 (EPS actual vs estimate)
export interface EarningsSurprise {
  ticker: string;
  quarter: string;            // 예: "2026Q1"
  actualEps: number;
  estimateEps: number;
  surprisePct: number;        // (actual-estimate)/|estimate|*100
}

const SURPRISE_CACHE_KEY = 'earnings-surprise-megacap';
const SURPRISE_FRESH_MS = 12 * 60 * 60 * 1000;
const SURPRISE_STALE_MS = 14 * 24 * 60 * 60 * 1000;
const MEGACAP_SURPRISE_TICKERS = ['AAPL', 'MSFT', 'GOOGL', 'AMZN', 'NVDA', 'META', 'TSLA'];

async function fetchYahooEarningsSurprise(ticker: string): Promise<EarningsSurprise | null> {
  try {
    // Yahoo quoteSummary earnings module — earningsChart.quarterly: 최근 4분기
    // crumb 인증은 analyst-consensus 의 헬퍼를 재사용하는 게 안전하나, 여기서는 독립 호출 시도.
    const { data } = await axios.get(
      `https://query1.finance.yahoo.com/v10/finance/quoteSummary/${ticker}?modules=earnings`,
      {
        headers: { 'User-Agent': 'Mozilla/5.0', Accept: 'application/json' },
        timeout: 10000,
      },
    );
    const quarterly = data?.quoteSummary?.result?.[0]?.earnings?.earningsChart?.quarterly || [];
    if (!Array.isArray(quarterly) || quarterly.length === 0) return null;
    const latest = quarterly[quarterly.length - 1];
    const actual = latest?.actual?.raw ?? latest?.actual;
    const estimate = latest?.estimate?.raw ?? latest?.estimate;
    const quarter = latest?.date;
    if (typeof actual !== 'number' || typeof estimate !== 'number' || Math.abs(estimate) < 0.001) return null;
    const surprisePct = parseFloat((((actual - estimate) / Math.abs(estimate)) * 100).toFixed(2));
    return { ticker, quarter: String(quarter), actualEps: actual, estimateEps: estimate, surprisePct };
  } catch {
    return null;
  }
}

export interface EarningsSurpriseAggregate {
  avgSurprisePct: number;
  beatCount: number;
  missCount: number;
  totalCount: number;
  perTicker: Record<string, EarningsSurprise>;
  fetchedAt: string;
}

export async function fetchEarningsSurprises(): Promise<EarningsSurpriseAggregate | null> {
  const cached = await readSourceCacheWithin<EarningsSurpriseAggregate>(SURPRISE_CACHE_KEY, SURPRISE_FRESH_MS);
  if (cached) {
    log.info({ ageMs: cached.ageMs }, 'earnings surprise cache hit');
    return cached.value;
  }
  const results: EarningsSurprise[] = [];
  for (const t of MEGACAP_SURPRISE_TICKERS) {
    const r = await fetchYahooEarningsSurprise(t);
    if (r) results.push(r);
    await new Promise((rs) => setTimeout(rs, 250));
  }
  if (results.length < 3) {
    const stale = await readSourceCacheWithin<EarningsSurpriseAggregate>(SURPRISE_CACHE_KEY, SURPRISE_STALE_MS);
    if (stale) return stale.value;
    return null;
  }
  const beats = results.filter((r) => r.surprisePct > 0).length;
  const misses = results.filter((r) => r.surprisePct < 0).length;
  const avg = results.reduce((s, r) => s + r.surprisePct, 0) / results.length;
  const perTicker: Record<string, EarningsSurprise> = {};
  for (const r of results) perTicker[r.ticker] = r;
  const agg: EarningsSurpriseAggregate = {
    avgSurprisePct: parseFloat(avg.toFixed(2)),
    beatCount: beats,
    missCount: misses,
    totalCount: results.length,
    perTicker,
    fetchedAt: new Date().toISOString(),
  };
  await writeSourceCache(SURPRISE_CACHE_KEY, agg);
  return agg;
}

const log = childLogger({ module: 'collector.earnings' });
const EARNINGS_CACHE_KEY = 'earnings-upcoming';
const EARNINGS_FRESH_MS = 6 * 60 * 60 * 1000;
const EARNINGS_STALE_MS = 7 * 24 * 60 * 60 * 1000;
const MAJOR_TICKERS = new Set(['AAPL', 'MSFT', 'GOOGL', 'AMZN', 'META', 'NVDA', 'TSLA', 'JPM', 'BAC', 'GS', 'JNJ', 'UNH', 'V', 'MA', 'WMT', 'HD', 'DIS', 'NFLX', 'CRM', 'AMD', 'INTC', 'QCOM', 'TSM', 'PEP', 'ABT', 'SCHW', 'PLD', 'USB', 'TRV']);

async function fetchNasdaqEarnings(date: string): Promise<EarningsEvent[]> {
  const { data } = await axios.get<{
    data?: { rows?: Array<{ symbol?: string; name?: string; time?: string }> };
  }>(
    `https://api.nasdaq.com/api/calendar/earnings?date=${date}`,
    {
      headers: {
        'User-Agent': 'Mozilla/5.0',
        Accept: 'application/json, text/plain, */*',
        Referer: 'https://www.nasdaq.com/',
      },
      timeout: 12000,
    },
  );

  const rows = data?.data?.rows ?? [];
  return rows
    .filter((row) => row.symbol && MAJOR_TICKERS.has(row.symbol))
    .map((row) => ({
      ticker: row.symbol!,
      company: row.name || row.symbol!,
      date,
      time: row.time || 'TBD',
    }));
}

async function fetchYahooEarnings(date: string): Promise<EarningsEvent[]> {
  const { data: html } = await axios.get(
    `https://finance.yahoo.com/calendar/earnings?day=${date}`,
    { headers: { 'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)' }, timeout: 10000 }
  );

  const tickerMatches = html.match(/data-symbol="([A-Z]+)"/g) || [];
  const tickers = tickerMatches.map((m: string) => m.replace(/data-symbol="|"/g, ''));
  const filtered = tickers.filter((ticker: string) => MAJOR_TICKERS.has(ticker));
  return filtered.map((ticker) => ({ ticker, company: ticker, date, time: 'TBD' }));
}

export async function fetchUpcomingEarnings(): Promise<EarningsEvent[]> {
  const startedAt = Date.now();
  try {
    const freshCached = await readSourceCacheWithin<EarningsEvent[]>(EARNINGS_CACHE_KEY, EARNINGS_FRESH_MS);
    if (freshCached) {
      log.info({ ageMs: freshCached.ageMs, updatedAt: freshCached.updatedAt }, 'earnings fresh cache hit');
      return freshCached.value;
    }

    const today = new Date();
    const dates = Array.from({ length: 5 }, (_, i) => {
      const d = new Date(today);
      d.setDate(d.getDate() + i);
      return d.toISOString().split('T')[0];
    });

    const results: EarningsEvent[] = [];
    const failedDates: string[] = [];

    for (const date of dates) {
      try {
        let events: EarningsEvent[] = [];
        try {
          events = await fetchNasdaqEarnings(date);
          log.info({ date, source: 'nasdaq', count: events.length }, 'earnings source collected');
        } catch (nasdaqError) {
          log.warn({ date, source: 'nasdaq', error: serializeError(nasdaqError) }, 'earnings source fetch failed, trying yahoo fallback');
          events = await fetchYahooEarnings(date);
          log.info({ date, source: 'yahoo', count: events.length }, 'earnings source collected');
        }
        results.push(...events);
      } catch (error) {
        failedDates.push(date);
        log.warn({ date, error: serializeError(error) }, 'earnings source fetch failed');
      }
    }

    const deduped = [...new Map(results.map((event) => [`${event.ticker}-${event.date}`, event])).values()];
    if (deduped.length > 0) {
      await writeSourceCache(EARNINGS_CACHE_KEY, deduped, { failedDates });
      log.info({
        durationMs: Date.now() - startedAt,
        count: deduped.length,
        failedDates,
      }, 'earnings collection completed');
      return deduped;
    }

    const cached = await readSourceCacheWithin<EarningsEvent[]>(EARNINGS_CACHE_KEY, EARNINGS_STALE_MS);
    if (cached) {
      log.warn({
        ageMs: cached.ageMs,
        updatedAt: cached.updatedAt,
        failedDates,
      }, 'earnings live fetch returned empty, serving cached value');
      return cached.value;
    }

    log.warn({
      durationMs: Date.now() - startedAt,
      failedDates,
    }, 'earnings collection returned empty with no cache');
    return results;
  } catch (error) {
    const cached = await readSourceCacheWithin<EarningsEvent[]>(EARNINGS_CACHE_KEY, EARNINGS_STALE_MS);
    if (cached) {
      log.warn({
        ageMs: cached.ageMs,
        updatedAt: cached.updatedAt,
        error: serializeError(error),
      }, 'earnings collection failed, serving cached value');
      return cached.value;
    }
    log.error({ error: serializeError(error) }, 'earnings collection failed');
    return [];
  }
}
