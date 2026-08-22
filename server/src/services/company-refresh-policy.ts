export const CORE_COMPANY_TICKERS = [
  'NVDA',
  'MSFT',
  'GOOGL',
  'AVGO',
  'TSM',
  'META',
  'AMZN',
  'ASML',
  'ORCL',
  'VRT',
  'ETN',
] as const;

const CORE_TICKER_SET = new Set<string>(CORE_COMPANY_TICKERS);

export const ANALYST_CONSENSUS_FRESH_MS = 60 * 60 * 1000; // 1시간
export const SEC_8K_FRESH_MS = 30 * 60 * 1000; // 30분
export const DEFAULT_COMPANY_SEC_FRESH_MS = 12 * 60 * 60 * 1000; // 12시간
export const CORE_COMPANY_SEC_FRESH_MS = 4 * 60 * 60 * 1000; // 4시간
export const DEFAULT_FILING_DETAIL_FRESH_MS = 24 * 60 * 60 * 1000; // 24시간
export const CORE_FILING_DETAIL_FRESH_MS = 6 * 60 * 60 * 1000; // 6시간

export function isCoreCompanyTicker(ticker: string | null | undefined): boolean {
  if (!ticker) return false;
  return CORE_TICKER_SET.has(ticker.trim().toUpperCase());
}

export function getCompanySecFreshMs(ticker: string | null | undefined): number {
  return isCoreCompanyTicker(ticker) ? CORE_COMPANY_SEC_FRESH_MS : DEFAULT_COMPANY_SEC_FRESH_MS;
}

export function getCompanyFilingDetailFreshMs(ticker: string | null | undefined): number {
  return isCoreCompanyTicker(ticker) ? CORE_FILING_DETAIL_FRESH_MS : DEFAULT_FILING_DETAIL_FRESH_MS;
}
