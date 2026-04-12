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

interface ChartMeta {
  symbol: string;
  regularMarketPrice: number;
  regularMarketTime: number;
  previousClose?: number;
  chartPreviousClose?: number;
  fiftyTwoWeekHigh?: number;
}

async function fetchChart(symbol: string): Promise<ChartMeta | null> {
  const url = `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?range=5d&interval=1d`;
  try {
    const { data } = await axios.get(url, {
      headers: { 'User-Agent': 'Mozilla/5.0' },
      timeout: 10000,
    });
    const meta = data.chart?.result?.[0]?.meta;
    if (!meta || !meta.regularMarketPrice) return null;
    return meta;
  } catch {
    return null;
  }
}

export async function fetchAllYahoo(): Promise<Record<string, MarketDataPoint>> {
  const results: Record<string, MarketDataPoint> = {};
  const entries = Object.entries(YAHOO_SYMBOLS);

  const settled = await Promise.allSettled(
    entries.map(([, sym]) => fetchChart(sym))
  );

  entries.forEach(([key, symbol], i) => {
    const result = settled[i];
    if (result.status !== 'fulfilled' || !result.value) return;

    const meta = result.value;
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
  });

  return results;
}

export async function fetchYahooHistory(
  symbol: string,
  days = 250
): Promise<{ date: string; close: number }[]> {
  const period2 = Math.floor(Date.now() / 1000);
  const period1 = period2 - days * 86400;
  const url = `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?period1=${period1}&period2=${period2}&interval=1d`;

  try {
    const { data } = await axios.get(url, {
      headers: { 'User-Agent': 'Mozilla/5.0' },
      timeout: 15000,
    });

    const result = data.chart?.result?.[0];
    if (!result) return [];

    const timestamps: number[] = result.timestamp || [];
    const closes: number[] = result.indicators?.quote?.[0]?.close || [];

    return timestamps.map((ts, i) => ({
      date: new Date(ts * 1000).toISOString().split('T')[0],
      close: closes[i] ?? 0,
    })).filter((d) => d.close > 0);
  } catch {
    return [];
  }
}
