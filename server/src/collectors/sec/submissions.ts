import { readSourceCacheWithin, writeSourceCache } from '../../services/source-cache';
import { CompanyFilingEvent, CompanyProfile } from '../../types/fundamentals';
import { secGetJson, normalizeCik } from './_common';
import { fetchSecFilingText, summarizeEarningsFiling } from './filing-detail';

const FRESH_MS = 12 * 60 * 60 * 1000;

interface SecRecentFilings {
  accessionNumber?: string[];
  filingDate?: string[];
  form?: string[];
  primaryDocument?: string[];
  primaryDocDescription?: string[];
}

interface SecSubmissionsResponse {
  cik?: string;
  name?: string;
  tickers?: string[];
  exchanges?: string[];
  sic?: string;
  filings?: {
    recent?: SecRecentFilings;
  };
}

export interface SecCompanySubmissions {
  profile: CompanyProfile;
  filings: CompanyFilingEvent[];
}

function cacheKey(cik: string) {
  return `sec-submissions-${normalizeCik(cik)}`;
}

function arrayAt(values: string[] | undefined, index: number): string | null {
  return Array.isArray(values) ? values[index] ?? null : null;
}

function isEarningsRelated(form: string, description: string | null) {
  return form === '8-K' && /item 2\.02|earnings|results of operations/i.test(description ?? '');
}

async function enrichFiling(cik: string, filing: CompanyFilingEvent): Promise<CompanyFilingEvent> {
  const accessionNoDash = filing.accessionNumber.replace(/-/g, '');
  const filingUrl = filing.primaryDocument
    ? `https://www.sec.gov/Archives/edgar/data/${parseInt(cik, 10)}/${accessionNoDash}/${filing.primaryDocument}`
    : null;
  if (!filing.isEarningsRelated || !filingUrl) {
    return { ...filing, filingUrl };
  }
  try {
    const text = await fetchSecFilingText(filingUrl);
    const { summary, guidanceSignals } = summarizeEarningsFiling(text ?? '', filing.primaryDocDescription);
    return { ...filing, filingUrl, summary, guidanceSignals };
  } catch {
    return { ...filing, filingUrl, summary: filing.primaryDocDescription ?? null, guidanceSignals: [] };
  }
}

function parseFilings(recent?: SecRecentFilings): CompanyFilingEvent[] {
  const forms = recent?.form ?? [];
  return forms.slice(0, 20).map((form, index) => {
    const description = arrayAt(recent?.primaryDocDescription, index);
    return {
      accessionNumber: arrayAt(recent?.accessionNumber, index) ?? '',
      filingDate: arrayAt(recent?.filingDate, index) ?? '',
      form,
      primaryDocument: arrayAt(recent?.primaryDocument, index),
      primaryDocDescription: description,
      isEarningsRelated: isEarningsRelated(form, description),
    };
  }).filter((item) => item.accessionNumber && item.filingDate && item.form);
}

export async function fetchSecSubmissions(cik: string): Promise<SecCompanySubmissions> {
  const normalized = normalizeCik(cik);
  const key = cacheKey(normalized);
  const cached = await readSourceCacheWithin<SecCompanySubmissions>(key, FRESH_MS);
  if (cached?.value) return cached.value;

  const data = await secGetJson<SecSubmissionsResponse>(`https://data.sec.gov/submissions/CIK${normalized}.json`);
  const parsedFilings = parseFilings(data.filings?.recent);
  const filings = await Promise.all(parsedFilings.map((item, index) =>
    index < 5 ? enrichFiling(normalized, item) : Promise.resolve({ ...item, filingUrl: item.primaryDocument ? `https://www.sec.gov/Archives/edgar/data/${parseInt(normalized, 10)}/${item.accessionNumber.replace(/-/g, '')}/${item.primaryDocument}` : null })
  ));

  const result: SecCompanySubmissions = {
    profile: {
      ticker: data.tickers?.[0] ?? '',
      cik: normalized,
      name: data.name ?? normalized,
      exchange: data.exchanges?.[0] ?? null,
      sic: data.sic ?? null,
    },
    filings,
  };
  await writeSourceCache(key, result);
  return result;
}
