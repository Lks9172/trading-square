import axios from 'axios';
import { readSourceCacheWithin, writeSourceCache } from '../../services/source-cache';
import { SEC_BASE_HEADERS } from './_common';

const FRESH_MS = 24 * 60 * 60 * 1000;

function cacheKey(url: string) {
  return `sec-filing-detail-${Buffer.from(url).toString('base64').slice(0, 64)}`;
}

function stripHtml(html: string): string {
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ')
    .trim();
}

export async function fetchSecFilingText(url: string): Promise<string | null> {
  const key = cacheKey(url);
  const cached = await readSourceCacheWithin<string>(key, FRESH_MS);
  if (cached?.value) return cached.value;
  const { data } = await axios.get<string>(url, {
    headers: SEC_BASE_HEADERS,
    timeout: 15000,
  });
  const text = stripHtml(data).slice(0, 30000);
  await writeSourceCache(key, text);
  return text;
}

export function summarizeEarningsFiling(text: string, description?: string | null): { summary: string | null; guidanceSignals: string[] } {
  const matches: string[] = [];
  const patterns = [
    /item 2\.02[^.]{0,220}\./i,
    /guidance[^.]{0,220}\./i,
    /outlook[^.]{0,220}\./i,
    /revenue[^.]{0,220}\./i,
    /earnings[^.]{0,220}\./i,
    /results of operations[^.]{0,220}\./i,
  ];
  for (const pattern of patterns) {
    const hit = text.match(pattern)?.[0];
    if (hit && !matches.includes(hit)) matches.push(hit);
  }
  const guidanceSignals = [
    /raise(d|s)? guidance/i.test(text) ? 'guidance_up' : null,
    /lower(ed|s)? guidance/i.test(text) ? 'guidance_down' : null,
    /above expectations|beat expectations|better than expected/i.test(text) ? 'beat' : null,
    /below expectations|miss(ed)? expectations|weaker than expected/i.test(text) ? 'miss' : null,
  ].filter((item): item is string => Boolean(item));

  const summary = matches.length > 0
    ? matches.slice(0, 2).join(' ')
    : description ?? null;

  return {
    summary: summary ? summary.slice(0, 400) : null,
    guidanceSignals,
  };
}

export function extractSegmentGeoMix(text: string): string | null {
  const segments: string[] = [];
  const geos: string[] = [];
  const segmentPatterns: Array<[RegExp, string]> = [
    [/\bdata center\b/i, 'Data Center'],
    [/\bgaming\b/i, 'Gaming'],
    [/\bclient\b/i, 'Client'],
    [/\bcloud\b/i, 'Cloud'],
    [/\badvertising\b/i, 'Advertising'],
    [/\benterprise\b/i, 'Enterprise'],
    [/\bautomotive\b/i, 'Automotive'],
    [/\bnetworking\b/i, 'Networking'],
    [/\bconsumer\b/i, 'Consumer'],
  ];
  const geoPatterns: Array<[RegExp, string]> = [
    [/\bamericas\b/i, 'Americas'],
    [/\bemea\b/i, 'EMEA'],
    [/\bapac\b/i, 'APAC'],
    [/\bgreater china\b/i, 'Greater China'],
    [/\bchina\b/i, 'China'],
    [/\bjapan\b/i, 'Japan'],
    [/\brest of world\b/i, 'Rest of World'],
    [/\bunited states\b/i, 'United States'],
  ];

  for (const [pattern, label] of segmentPatterns) {
    if (pattern.test(text) && !segments.includes(label)) segments.push(label);
  }
  for (const [pattern, label] of geoPatterns) {
    if (pattern.test(text) && !geos.includes(label)) geos.push(label);
  }

  if (segments.length === 0 && geos.length === 0) return null;
  const parts: string[] = [];
  if (segments.length > 0) parts.push(`세그먼트: ${segments.slice(0, 3).join(', ')}`);
  if (geos.length > 0) parts.push(`지역: ${geos.slice(0, 3).join(', ')}`);
  return parts.join(' / ');
}
