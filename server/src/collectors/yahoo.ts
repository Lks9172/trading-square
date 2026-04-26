import axios from 'axios';
import { MarketDataPoint } from '../types/indicators';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const YAHOO_SYMBOLS: Record<string, string> = {
  SP500: '^GSPC',
  NASDAQ: '^IXIC',
  KOSPI: '^KS11',
  KOSDAQ: '^KQ11',
  SAMSUNG: '005930.KS',
  GOLD: 'GC=F',
  SILVER: 'SI=F',
  COPPER: 'HG=F',
  WTI: 'CL=F',
  DXY: 'DX-Y.NYB',
  USDJPY: 'JPY=X',
  USDKRW: 'KRW=X',
  EWZ: 'EWZ',
  INDA: 'INDA',
  VNM: 'VNM',
  EWJ: 'EWJ',
  XLK: 'XLK',
  XLF: 'XLF',
  XLE: 'XLE',
  XLV: 'XLV',
  XLI: 'XLI',
  XLY: 'XLY',
  // 15차 Phase 2-G (2026-04): video4 §7가지 렌즈 섹터 확장. 기존 6종 → 10종.
  XLC: 'XLC',    // 통신 서비스 (메가캡 구글/메타 포함)
  XLB: 'XLB',    // 소재 (원자재/화학)
  XLRE: 'XLRE',  // 부동산 (금리 민감)
  XLU: 'XLU',    // 유틸리티 (방어 섹터, 배당주)
  SOXX: 'SOXX',  // 반도체 ETF (영상2·5 AI·헬륨 반도체 특화)
  SMH: 'SMH',    // 반도체 ETF (더 좁은 대형주 중심)
  TQQQ: 'TQQQ',  // 2x/3x 레버리지 ETF — 백테스트 leverage 비중 실현 수익률 계산용
  NQ_FUTURES: 'NQ=F',
  ES_FUTURES: 'ES=F',
  // 옵션·변동성 지수 (자산제곱 대시보드 gap 보완)
  SKEW: '^SKEW',   // CBOE SKEW Index — 꼬리위험(out-of-the-money put 수요)
  VVIX: '^VVIX',   // CBOE VVIX — VIX 의 변동성 (변동성 레짐 변화 선행)
  OVX: '^OVX',     // CBOE Oil VIX — WTI 변동성 (에너지/지정학 쇼크 감지)
  HYG: 'HYG',      // iShares iBoxx $ High Yield Corporate Bond ETF — 크레딧 스프레드 proxy
  IEF: 'IEF',      // iShares 7-10 Year Treasury Bond ETF — HYG 대비 무위험 기준
};

export { YAHOO_SYMBOLS };
const log = childLogger({ module: 'collector.yahoo' });

interface ChartMeta {
  symbol: string;
  regularMarketPrice: number;
  regularMarketTime: number;
  previousClose?: number;
  chartPreviousClose?: number;
  fiftyTwoWeekHigh?: number;
}

async function fetchChart(symbol: string): Promise<ChartMeta | null> {
  const urls = [
    `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?range=5d&interval=1d`,
    `https://query2.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?range=5d&interval=1d`,
  ];
  let lastError: unknown = null;
  for (const url of urls) {
    try {
      const { data } = await axios.get(url, {
        headers: { 'User-Agent': 'Mozilla/5.0' },
        timeout: 10000,
      });
      const meta = data.chart?.result?.[0]?.meta;
      if (!meta || !meta.regularMarketPrice) continue;
      return meta;
    } catch (error) {
      lastError = error;
    }
  }
  throw new Error(`Yahoo ${symbol} fetch failed: ${serializeError(lastError).message}`);
}

function chartUrls(symbol: string, query: string) {
  return [
    `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?${query}`,
    `https://query2.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?${query}`,
  ];
}

export async function fetchAllYahoo(): Promise<Record<string, MarketDataPoint>> {
  const results: Record<string, MarketDataPoint> = {};
  const entries = Object.entries(YAHOO_SYMBOLS);
  const startedAt = Date.now();
  const failedSymbols: Array<{ key: string; symbol: string; reason: string }> = [];

  const settled = await Promise.allSettled(
    entries.map(async ([key, sym]) => {
      const meta = await fetchChart(sym);
      if (!meta) {
        throw new Error(`Yahoo ${sym} returned empty meta or regularMarketPrice`);
      }
      return { key, symbol: sym, meta };
    })
  );

  const persistMeta = async (key: string, meta: ChartMeta) => {
    await writeSourceCache(`yahoo-meta-${key}`, meta, { symbol: meta.symbol });
  };

  for (let i = 0; i < entries.length; i += 1) {
    const [key, symbol] = entries[i];
    const result = settled[i];
    let meta = result.status === 'fulfilled' && result.value ? result.value.meta : null;

    if (!meta) {
      const cached = await readSourceCacheWithin<ChartMeta>(`yahoo-meta-${key}`, 7 * 24 * 60 * 60 * 1000);
      if (cached?.value?.regularMarketPrice) {
        meta = cached.value;
        log.warn({ key, symbol, ageMs: cached.ageMs }, 'yahoo live fetch failed, serving cached meta');
      }
    }

    if (!meta) {
      try {
        const { readHistory } = await import('../state/history-store');
        const history = await readHistory('yahoo', key);
        const last = history[history.length - 1];
        if (last) {
          results[key] = {
            code: symbol,
            value: last.value,
            date: last.date,
            source: 'YAHOO',
          };
          log.warn({ key, symbol, fallbackDate: last.date, fallbackValue: last.value }, 'yahoo live fetch failed, history fallback applied');
          return;
        }
      } catch (historyError) {
        failedSymbols.push({
          key,
          symbol,
          reason: `history fallback failed: ${serializeError(historyError).message}`,
        });
        return;
      }

      failedSymbols.push({
        key,
        symbol,
        reason: result.status === 'rejected' ? (result.reason as Error)?.message || 'unknown error' : 'empty result',
      });
      return;
    }

    void persistMeta(key, meta).catch(() => undefined);
    const date = new Date(meta.regularMarketTime * 1000).toISOString().split('T')[0];

    results[key] = {
      code: symbol,
      value: meta.regularMarketPrice,
      date,
      source: 'YAHOO',
    };

    const high52 = meta.fiftyTwoWeekHigh ?? 0;
    if (high52 > 0) {
      results[`${key}_52WH`] = {
        code: `${symbol}_52WH`,
        value: high52,
        date,
        source: 'YAHOO',
      };
    }
  }

  await Promise.allSettled(entries.map(async ([key, symbol], i) => {
    const result = settled[i];
    if (result.status === 'fulfilled' && result.value?.meta) {
      await persistMeta(key, result.value.meta);
    }
    return { key, symbol };
  }));

  if (failedSymbols.length > 0) {
    log.warn({ failedSymbols }, 'yahoo symbols failed during collection');
  }
  log.info({
    durationMs: Date.now() - startedAt,
    requestedSymbols: entries.length,
    returnedKeys: Object.keys(results).length,
    failedCount: failedSymbols.length,
  }, 'yahoo collection completed');

  return results;
}

export async function fetchYahooHistory(
  symbol: string,
  days = 250
): Promise<{ date: string; close: number; volume?: number }[]> {
  const period2 = Math.floor(Date.now() / 1000);
  const period1 = period2 - days * 86400;
  const urls = chartUrls(symbol, `period1=${period1}&period2=${period2}&interval=1d`);
  let lastError: unknown = null;

  for (const url of urls) {
    try {
      const { data } = await axios.get(url, {
        headers: { 'User-Agent': 'Mozilla/5.0' },
        timeout: 15000,
      });

      const result = data.chart?.result?.[0];
      if (!result) continue;

      const timestamps: number[] = result.timestamp || [];
      const closes: number[] = result.indicators?.quote?.[0]?.close || [];
      const volumes: number[] = result.indicators?.quote?.[0]?.volume || [];

      return timestamps.map((ts, i) => ({
        date: new Date(ts * 1000).toISOString().split('T')[0],
        close: closes[i] ?? 0,
        volume: volumes[i] ?? 0,
      })).filter((d) => d.close > 0);
    } catch (error) {
      lastError = error;
    }
  }

  log.warn({ symbol, days, interval: '1d', error: serializeError(lastError) }, 'yahoo history fetch failed');
  return [];
}

export async function fetchYahooHistoryYears(
  symbol: string,
  years = 5
): Promise<{ date: string; close: number }[]> {
  return fetchYahooHistory(symbol, Math.round(years * 365.25));
}

export type OHLCInterval = '1d' | '1wk' | '1mo';

export interface OHLCCandle {
  date: string;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

/**
 * Yahoo chart API 로 OHLC 캔들을 받아온다. interval 로 일/주/월봉 직접 요청 가능.
 * Yahoo 의 공식 집계(주봉·월봉)를 그대로 쓰므로 로컬 aggregation 불필요.
 */
export async function fetchYahooOHLC(
  symbol: string,
  days: number,
  interval: OHLCInterval = '1d',
): Promise<OHLCCandle[]> {
  const period2 = Math.floor(Date.now() / 1000);
  const period1 = period2 - days * 86400;
  const urls = chartUrls(symbol, `period1=${period1}&period2=${period2}&interval=${interval}`);
  let lastError: unknown = null;
  for (const url of urls) {
    try {
      const { data } = await axios.get(url, {
        headers: { 'User-Agent': 'Mozilla/5.0' },
        timeout: 15000,
      });
      const result = data.chart?.result?.[0];
      if (!result) continue;
      const ts: number[] = result.timestamp || [];
      const q = result.indicators?.quote?.[0] || {};
      const opens: number[] = q.open || [];
      const highs: number[] = q.high || [];
      const lows: number[] = q.low || [];
      const closes: number[] = q.close || [];
      const vols: number[] = q.volume || [];
      return ts
        .map((t, i) => ({
          date: new Date(t * 1000).toISOString().split('T')[0],
          open: opens[i] ?? 0,
          high: highs[i] ?? 0,
          low: lows[i] ?? 0,
          close: closes[i] ?? 0,
          volume: vols[i] ?? 0,
        }))
        .filter((c) => c.close > 0 && c.high > 0 && c.low > 0 && c.open > 0);
    } catch (error) {
      lastError = error;
    }
  }
  log.warn({ symbol, days, interval, error: serializeError(lastError) }, 'yahoo OHLC fetch failed');
  return [];
}

/**
 * ★ 29차 P1-D #11: Yahoo Finance defaultKeyStatistics.forwardPE 수집.
 *   ticker 우선순위로 시도 (^NDX, ^IXIC, QQQ 등 ETF proxy fallback).
 *   crumb 인증 필요. 실패 시 null.
 */
export async function fetchYahooForwardPE(tickers: string[]): Promise<{ ticker: string; forwardPE: number } | null> {
  const { getYahooCrumb, invalidateYahooCrumb } = await import('../utils/yahoo-auth');
  for (const ticker of tickers) {
    try {
      const auth = await getYahooCrumb();
      if (!auth) return null;
      const url = `https://query2.finance.yahoo.com/v10/finance/quoteSummary/${encodeURIComponent(ticker)}?modules=defaultKeyStatistics,summaryDetail&crumb=${encodeURIComponent(auth.crumb)}`;
      const { data } = await axios.get(url, {
        headers: { 'User-Agent': 'Mozilla/5.0', Accept: 'application/json', Cookie: auth.cookie },
        timeout: 10000,
      });
      const dks = data?.quoteSummary?.result?.[0]?.defaultKeyStatistics;
      const sd = data?.quoteSummary?.result?.[0]?.summaryDetail;
      const fpeRaw = dks?.forwardPE?.raw ?? dks?.forwardPE ?? sd?.forwardPE?.raw ?? sd?.forwardPE;
      if (typeof fpeRaw === 'number' && Number.isFinite(fpeRaw) && fpeRaw > 0) {
        return { ticker, forwardPE: parseFloat(fpeRaw.toFixed(2)) };
      }
    } catch (error: any) {
      if (error?.response?.status === 401) invalidateYahooCrumb();
      log.warn({ ticker, error: serializeError(error) }, 'yahoo forwardPE fetch failed');
    }
    await new Promise((rs) => setTimeout(rs, 150));
  }
  return null;
}
