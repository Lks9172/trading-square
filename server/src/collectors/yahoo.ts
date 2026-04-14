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
  NQ_FUTURES: 'NQ=F',
  ES_FUTURES: 'ES=F',
  // 옵션·변동성 지수 (자산제곱 대시보드 gap 보완)
  SKEW: '^SKEW',   // CBOE SKEW Index — 꼬리위험(out-of-the-money put 수요)
  VVIX: '^VVIX',   // CBOE VVIX — VIX 의 변동성 (변동성 레짐 변화 선행)
  OVX: '^OVX',     // CBOE Oil VIX — WTI 변동성 (에너지/지정학 쇼크 감지)
};

export { YAHOO_SYMBOLS };

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
): Promise<{ date: string; close: number; volume?: number }[]> {
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
    const volumes: number[] = result.indicators?.quote?.[0]?.volume || [];

    return timestamps.map((ts, i) => ({
      date: new Date(ts * 1000).toISOString().split('T')[0],
      close: closes[i] ?? 0,
      volume: volumes[i] ?? 0,
    })).filter((d) => d.close > 0);
  } catch {
    return [];
  }
}

export async function fetchYahooHistoryYears(
  symbol: string,
  years = 5
): Promise<{ date: string; close: number }[]> {
  return fetchYahooHistory(symbol, Math.round(years * 365.25));
}
