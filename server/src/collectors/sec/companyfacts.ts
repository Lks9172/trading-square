import { getCompanySecFreshMs } from '../../services/company-refresh-policy';
import { readSourceCache, readSourceCacheWithin, writeSourceCache } from '../../services/source-cache';
import { CompanySegmentMixEntry } from '../../types/fundamentals';
import { secGetJson, normalizeCik } from './_common';

const DEFAULT_FRESH_MS = 12 * 60 * 60 * 1000;

export interface SecFactPoint {
  fy?: number;
  fp?: string;
  form?: string;
  filed?: string;
  end?: string;
  frame?: string;
  val?: number;
}

interface SecFactUnitRecord {
  label?: string;
  description?: string;
  units?: Record<string, SecFactPoint[]>;
}

export interface SecCompanyFactsResponse {
  cik?: number | string;
  entityName?: string;
  facts?: {
    'us-gaap'?: Record<string, SecFactUnitRecord>;
    dei?: Record<string, SecFactUnitRecord>;
  };
}

function cacheKey(cik: string) {
  return `sec-companyfacts-${normalizeCik(cik)}`;
}

export async function fetchSecCompanyFacts(
  cik: string,
  options?: { ticker?: string | null; maxAgeMs?: number },
): Promise<SecCompanyFactsResponse> {
  const normalized = normalizeCik(cik);
  const key = cacheKey(normalized);
  const freshMs = options?.maxAgeMs ?? getCompanySecFreshMs(options?.ticker) ?? DEFAULT_FRESH_MS;
  const cached = await readSourceCacheWithin<SecCompanyFactsResponse>(key, freshMs);
  if (cached?.value) return cached.value;
  try {
    const data = await secGetJson<SecCompanyFactsResponse>(`https://data.sec.gov/api/xbrl/companyfacts/CIK${normalized}.json`);
    await writeSourceCache(key, data);
    return data;
  } catch (error) {
    const stale = await readSourceCache<SecCompanyFactsResponse>(key);
    if (stale?.value) return stale.value;
    throw error;
  }
}

function latestValueFromUnits(units?: Record<string, SecFactPoint[]>): { value: number | null; unit: string | null } {
  if (!units) return { value: null, unit: null };
  for (const [unit, points] of Object.entries(units)) {
    const latest = points
      .filter((item) => typeof item.val === 'number' && item.end)
      .sort((a, b) => String(b.end).localeCompare(String(a.end)))[0];
    if (latest?.val !== undefined) return { value: latest.val, unit };
  }
  return { value: null, unit: null };
}

function applyPercentages(items: CompanySegmentMixEntry[]): CompanySegmentMixEntry[] {
  const values = items.map((item) => item.value).filter((v): v is number => v !== null && v > 0);
  const total = values.reduce((a, b) => a + b, 0);
  if (!total) return items.map((item) => ({ ...item, percentOfTotal: null }));
  return items.map((item) => ({
    ...item,
    percentOfTotal: item.value !== null && item.value > 0 ? Number(((item.value / total) * 100).toFixed(1)) : null,
  }));
}

function summarizeFactEntries(
  bucket: Record<string, SecFactUnitRecord> | undefined,
  mode: 'segment' | 'geo',
): CompanySegmentMixEntry[] {
  if (!bucket) return [];
  const entries: CompanySegmentMixEntry[] = [];
  const primaryPattern = mode === 'segment'
    ? /(segment|business|product|platform|cloud|gaming|data center|automotive|networking|consumer)/i
    : /(geograph|region|country|china|apac|emea|americas|japan|united states|foreign)/i;
  const revenuePattern = /(revenue|sales|net sales|turnover)/i;
  const excludePattern = /(expense|expenses|liabilit|receivable|tax|deferred|pro forma|acquisition|cash flow|asset|inventory|payable)/i;

  for (const [key, fact] of Object.entries(bucket)) {
    const haystack = `${key} ${fact.label ?? ''} ${fact.description ?? ''}`;
    if (!primaryPattern.test(haystack)) continue;
    if (!revenuePattern.test(haystack)) continue;
    if (excludePattern.test(haystack)) continue;
    const { value, unit } = latestValueFromUnits(fact.units);
    entries.push({
      label: fact.label || key,
      value,
      unit,
    });
  }

  const sorted = entries
    .sort((a, b) => Math.abs(b.value ?? -1) - Math.abs(a.value ?? -1))
    .slice(0, 5);
  return applyPercentages(sorted);
}

export function extractSegmentGeoMixFromFacts(facts: SecCompanyFactsResponse): {
  segmentMix: CompanySegmentMixEntry[];
  geoMix: CompanySegmentMixEntry[];
  note: string | null;
} {
  const usGaap = facts.facts?.['us-gaap'];
  const segmentMix = summarizeFactEntries(usGaap, 'segment');
  const geoMix = summarizeFactEntries(usGaap, 'geo');

  const parts: string[] = [];
  if (segmentMix.length > 0) {
    parts.push(`세그먼트(XBRL): ${segmentMix.slice(0, 3).map((item) => item.percentOfTotal !== null && item.percentOfTotal !== undefined ? `${item.label} ${item.percentOfTotal}%` : item.label).join(', ')}`);
  }
  if (geoMix.length > 0) {
    parts.push(`지역(XBRL): ${geoMix.slice(0, 3).map((item) => item.percentOfTotal !== null && item.percentOfTotal !== undefined ? `${item.label} ${item.percentOfTotal}%` : item.label).join(', ')}`);
  }

  return {
    segmentMix,
    geoMix,
    note: parts.length > 0 ? parts.join(' / ') : null,
  };
}
