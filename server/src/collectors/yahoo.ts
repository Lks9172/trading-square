import axios from 'axios';
import { MarketDataPoint } from '../types/indicators';

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
  BTC: 'BTC-USD',
  DXY: 'DX-Y.NYB',
  USDJPY: 'JPY=X',
  USDKRW: 'KRW=X',
  EWZ: 'EWZ',
  INDA: 'INDA',
  VNM: 'VNM',
  EWJ: 'EWJ',
};

interface YahooQuote {
  symbol: string;
  regularMarketPrice: number;
  regularMarketTime: number;
  regularMarketPreviousClose: number;
  fiftyTwoWeekHigh: number;
}

async function fetchQuotes(symbols: string[]): Promise<YahooQuote[]> {
  const joined = symbols.join(',');
  const url = `https://query1.finance.yahoo.com/v7/finance/quote?symbols=${encodeURIComponent(joined)}`;

  const { data } = await axios.get(url, {
    headers: { 'User-Agent': 'Mozilla/5.0' },
  });

  return data.quoteResponse?.result || [];
}

async function fetchQuotesV6(symbols: string[]): Promise<YahooQuote[]> {
  const joined = symbols.join(',');
  const url = `https://query2.finance.yahoo.com/v6/finance/quote?symbols=${encodeURIComponent(joined)}`;

  try {
    const { data } = await axios.get(url, {
      headers: { 'User-Agent': 'Mozilla/5.0' },
    });
    return data.quoteResponse?.result || [];
  } catch {
    return [];
  }
}

async function fetchWithChart(symbol: string): Promise<YahooQuote | null> {
  const url = `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?range=5d&interval=1d`;
  try {
    const { data } = await axios.get(url, {
      headers: { 'User-Agent': 'Mozilla/5.0' },
    });
    const meta = data.chart?.result?.[0]?.meta;
    if (!meta) return null;
    return {
      symbol: meta.symbol,
      regularMarketPrice: meta.regularMarketPrice,
      regularMarketTime: meta.regularMarketTime,
      regularMarketPreviousClose: meta.previousClose || meta.chartPreviousClose || 0,
      fiftyTwoWeekHigh: meta.fiftyTwoWeekHigh || 0,
    };
  } catch {
    return null;
  }
}

export async function fetchAllYahoo(): Promise<Record<string, MarketDataPoint>> {
  const results: Record<string, MarketDataPoint> = {};
  const entries = Object.entries(YAHOO_SYMBOLS);
  const symbols = entries.map(([, sym]) => sym);

  let quotes = await fetchQuotes(symbols);

  if (quotes.length === 0) {
    quotes = await fetchQuotesV6(symbols);
  }

  if (quotes.length === 0) {
    const chartResults = await Promise.allSettled(
      symbols.map((sym) => fetchWithChart(sym))
    );
    quotes = chartResults
      .filter((r): r is PromiseFulfilledResult<YahooQuote | null> => r.status === 'fulfilled')
      .map((r) => r.value)
      .filter((q): q is YahooQuote => q !== null);
  }

  const quoteMap = new Map(quotes.map((q) => [q.symbol, q]));

  for (const [key, symbol] of entries) {
    const quote = quoteMap.get(symbol);
    if (quote) {
      results[key] = {
        code: symbol,
        value: quote.regularMarketPrice,
        date: new Date(quote.regularMarketTime * 1000).toISOString().split('T')[0],
        source: 'YAHOO',
      };

      if (quote.fiftyTwoWeekHigh > 0) {
        results[`${key}_52WH`] = {
          code: `${symbol}_52WH`,
          value: quote.fiftyTwoWeekHigh,
          date: results[key].date,
          source: 'YAHOO',
        };
      }
    }
  }

  return results;
}

export async function fetchYahooHistory(
  symbol: string,
  days = 250
): Promise<{ date: string; close: number }[]> {
  const period2 = Math.floor(Date.now() / 1000);
  const period1 = period2 - days * 86400;
  const url = `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?period1=${period1}&period2=${period2}&interval=1d`;

  const { data } = await axios.get(url, {
    headers: { 'User-Agent': 'Mozilla/5.0' },
  });

  const result = data.chart?.result?.[0];
  if (!result) return [];

  const timestamps: number[] = result.timestamp || [];
  const closes: number[] = result.indicators?.quote?.[0]?.close || [];

  return timestamps.map((ts, i) => ({
    date: new Date(ts * 1000).toISOString().split('T')[0],
    close: closes[i] ?? 0,
  })).filter((d) => d.close > 0);
}
