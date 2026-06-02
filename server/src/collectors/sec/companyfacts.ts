import { readSourceCacheWithin, writeSourceCache } from '../../services/source-cache';
import { secGetJson, normalizeCik } from './_common';

const FRESH_MS = 12 * 60 * 60 * 1000;

export interface SecFactPoint {
  fy?: number;
  fp?: string;
  form?: string;
  filed?: string;
  end?: string;
  frame?: string;
  val?: number;
}

export interface SecCompanyFactsResponse {
  cik?: number | string;
  entityName?: string;
  facts?: {
    'us-gaap'?: Record<string, {
      label?: string;
      description?: string;
      units?: Record<string, SecFactPoint[]>;
    }>;
    dei?: Record<string, {
      units?: Record<string, SecFactPoint[]>;
    }>;
  };
}

function cacheKey(cik: string) {
  return `sec-companyfacts-${normalizeCik(cik)}`;
}

export async function fetchSecCompanyFacts(cik: string): Promise<SecCompanyFactsResponse> {
  const normalized = normalizeCik(cik);
  const key = cacheKey(normalized);
  const cached = await readSourceCacheWithin<SecCompanyFactsResponse>(key, FRESH_MS);
  if (cached?.value) return cached.value;
  const data = await secGetJson<SecCompanyFactsResponse>(`https://data.sec.gov/api/xbrl/companyfacts/CIK${normalized}.json`);
  await writeSourceCache(key, data);
  return data;
}

