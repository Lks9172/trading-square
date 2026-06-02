import { readSourceCacheWithin, writeSourceCache } from '../../services/source-cache';
import { secGetJson, normalizeCik } from './_common';

const CACHE_KEY = 'sec-company-ticker-map';
const FRESH_MS = 24 * 60 * 60 * 1000;

interface SecTickerEntry {
  cik_str: number;
  ticker: string;
  title: string;
}

export interface SecTickerMapEntry {
  ticker: string;
  cik: string;
  title: string;
}

let memo: Record<string, SecTickerMapEntry> | null = null;

function normalizeTicker(ticker: string): string {
  return ticker.trim().toUpperCase().replace(/\./g, '-');
}

function convertMap(raw: Record<string, SecTickerEntry>): Record<string, SecTickerMapEntry> {
  const result: Record<string, SecTickerMapEntry> = {};
  for (const item of Object.values(raw)) {
    const ticker = normalizeTicker(item.ticker);
    result[ticker] = {
      ticker,
      cik: normalizeCik(item.cik_str),
      title: item.title,
    };
  }
  return result;
}

export async function getSecTickerMap(): Promise<Record<string, SecTickerMapEntry>> {
  if (memo) return memo;
  const cached = await readSourceCacheWithin<Record<string, SecTickerMapEntry>>(CACHE_KEY, FRESH_MS);
  if (cached?.value) {
    memo = cached.value;
    return memo;
  }
  const raw = await secGetJson<Record<string, SecTickerEntry>>('https://www.sec.gov/files/company_tickers.json');
  memo = convertMap(raw);
  await writeSourceCache(CACHE_KEY, memo);
  return memo;
}

export async function lookupSecCompanyByTicker(ticker: string): Promise<SecTickerMapEntry | null> {
  const map = await getSecTickerMap();
  return map[normalizeTicker(ticker)] ?? null;
}

export async function searchSecCompanies(query: string, limit = 8): Promise<SecTickerMapEntry[]> {
  const normalized = normalizeTicker(query);
  const map = await getSecTickerMap();
  const entries = Object.values(map);
  return entries
    .filter((entry) =>
      entry.ticker.includes(normalized)
      || entry.title.toUpperCase().includes(normalized),
    )
    .sort((a, b) => {
      const aExact = a.ticker === normalized ? -2 : a.ticker.startsWith(normalized) ? -1 : 0;
      const bExact = b.ticker === normalized ? -2 : b.ticker.startsWith(normalized) ? -1 : 0;
      return aExact - bExact || a.ticker.localeCompare(b.ticker);
    })
    .slice(0, limit);
}
