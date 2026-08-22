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

// 2026-06-17 기준 SEC company_tickers.json 에 누락되지만 실제 EDGAR 제출은 존재하는 종목 fallback.
// 확인 근거:
// - SPR  -> Spirit AeroSystems Holdings, Inc. / CIK 0001364885
// - MMC  -> Marsh & McLennan Companies, Inc. / CIK 0000062709
// - CTRA -> Coterra Energy Inc. / CIK 0000858470
// - 추가 40개 유니버스 확장 후 누락 종목들은 SEC 공식 cik-lookup-data.txt / company_tickers.json 기준으로 보강.
const MANUAL_TICKER_OVERRIDES: Record<string, SecTickerMapEntry> = {
  SPR: { ticker: 'SPR', cik: '0001364885', title: 'Spirit AeroSystems Holdings, Inc.' },
  MMC: { ticker: 'MMC', cik: '0000062709', title: 'Marsh & McLennan Companies, Inc.' },
  CTRA: { ticker: 'CTRA', cik: '0000858470', title: 'Coterra Energy Inc.' },
  SQ: { ticker: 'SQ', cik: '0001512673', title: 'Block, Inc.' },
  ABB: { ticker: 'ABB', cik: '0001091587', title: 'ABB Ltd' },
  TGI: { ticker: 'TGI', cik: '0001021162', title: 'TRIUMPH GROUP INC' },
  HOLX: { ticker: 'HOLX', cik: '0000859737', title: 'HOLOGIC INC' },
  PARA: { ticker: 'PARA', cik: '0000813828', title: 'PARAMOUNT GLOBAL' },
  IPG: { ticker: 'IPG', cik: '0000051644', title: 'INTERPUBLIC GROUP OF COMPANIES, INC.' },
  ATUS: { ticker: 'ATUS', cik: '0001702780', title: 'ALTICE USA, INC.' },
  TGNA: { ticker: 'TGNA', cik: '0000039899', title: 'TEGNA INC' },
  EDR: { ticker: 'EDR', cik: '0001766363', title: 'ENDEAVOR GROUP HOLDINGS, INC.' },
  BK: { ticker: 'BK', cik: '0001390777', title: 'Bank of New York Mellon Corp' },
  DFS: { ticker: 'DFS', cik: '0001393612', title: 'DISCOVER FINANCIAL SERVICES' },
  MRO: { ticker: 'MRO', cik: '0000101778', title: 'MARATHON OIL CORP' },
  HES: { ticker: 'HES', cik: '0000004447', title: 'HESS CORP' },
  PXD: { ticker: 'PXD', cik: '0001038357', title: 'PIONEER NATURAL RESOURCES CO' },
  CIVI: { ticker: 'CIVI', cik: '0001509589', title: 'CIVITAS RESOURCES, INC.' },
  VTLE: { ticker: 'VTLE', cik: '0001528129', title: 'VITAL ENERGY, INC.' },
  WRK: { ticker: 'WRK', cik: '0002005951', title: 'Smurfit Westrock plc' },
  SEE: { ticker: 'SEE', cik: '0001012100', title: 'SEALED AIR CORP/DE' },
  ALE: { ticker: 'ALE', cik: '0000066756', title: 'ALLETE INC' },
  WBA: { ticker: 'WBA', cik: '0001618921', title: 'WALGREENS BOOTS ALLIANCE, INC.' },
  K: { ticker: 'K', cik: '0000055067', title: 'KELLANOVA' },
  PEAK: { ticker: 'PEAK', cik: '0000765880', title: 'HEALTHPEAK PROPERTIES, INC.' },
};

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
  return applyManualOverrides(result);
}

function applyManualOverrides(map: Record<string, SecTickerMapEntry>): Record<string, SecTickerMapEntry> {
  const merged = { ...map };
  for (const [ticker, entry] of Object.entries(MANUAL_TICKER_OVERRIDES)) {
    if (!merged[ticker]) merged[ticker] = entry;
  }
  return merged;
}

export async function getSecTickerMap(): Promise<Record<string, SecTickerMapEntry>> {
  if (memo) return memo;
  const cached = await readSourceCacheWithin<Record<string, SecTickerMapEntry>>(CACHE_KEY, FRESH_MS);
  if (cached?.value) {
    memo = applyManualOverrides(cached.value);
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
