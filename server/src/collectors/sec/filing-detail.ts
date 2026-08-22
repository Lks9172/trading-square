import axios from 'axios';
import zlib from 'zlib';
import { DEFAULT_FILING_DETAIL_FRESH_MS } from '../../services/company-refresh-policy';
import { CompanyGuidanceMetricValue, CompanyGuidanceSummary, CompanyIrMaterial } from '../../types/fundamentals';
import { readSourceCacheWithin, writeSourceCache } from '../../services/source-cache';
import { SEC_BASE_HEADERS } from './_common';

const FRESH_MS = DEFAULT_FILING_DETAIL_FRESH_MS;

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

function detectContentType(url: string): CompanyIrMaterial['contentType'] {
  if (/\.pdf($|\?)/i.test(url)) return 'pdf';
  if (/\.(htm|html|xml)($|\?)/i.test(url)) return 'html';
  if (/\.(txt)($|\?)/i.test(url)) return 'txt';
  return 'other';
}

function decodePdfString(input: string): string {
  return input
    .replace(/\\\(/g, '(')
    .replace(/\\\)/g, ')')
    .replace(/\\n/g, ' ')
    .replace(/\\r/g, ' ')
    .replace(/\\t/g, ' ')
    .replace(/\\([0-7]{3})/g, (_, oct) => String.fromCharCode(parseInt(oct, 8)));
}

function extractPdfTextFromBuffer(buffer: Buffer): string {
  const textParts: string[] = [];
  const raw = buffer.toString('latin1');
  const streamRegex = /<<(.*?)>>\s*stream\r?\n([\s\S]*?)\r?\nendstream/g;
  let match: RegExpExecArray | null;
  while ((match = streamRegex.exec(raw)) !== null) {
    const dict = match[1] || '';
    const streamData = match[2] || '';
    let content = Buffer.from(streamData, 'latin1');
    if (/FlateDecode/i.test(dict)) {
      try {
        content = zlib.inflateSync(content);
      } catch {
        try {
          content = zlib.inflateRawSync(content);
        } catch {
          continue;
        }
      }
    }
    const decoded = content.toString('latin1');
    const literals = [...decoded.matchAll(/\((?:\\.|[^\\)])*\)\s*Tj/g), ...decoded.matchAll(/\[(.*?)\]\s*TJ/gs)];
    for (const literal of literals) {
      const source = literal[0] || '';
      const innerMatches = source.match(/\((?:\\.|[^\\)])*\)/g) || [];
      for (const inner of innerMatches) {
        const cleaned = decodePdfString(inner.slice(1, -1)).trim();
        if (cleaned.length >= 2) textParts.push(cleaned);
      }
    }
  }
  return textParts.join(' ').replace(/\s+/g, ' ').trim().slice(0, 30000);
}

async function fetchSecUrlBuffer(url: string): Promise<Buffer> {
  const { data } = await axios.get<ArrayBuffer>(url, {
    headers: SEC_BASE_HEADERS,
    timeout: 20000,
    responseType: 'arraybuffer',
  });
  return Buffer.from(data);
}

export async function fetchSecFilingText(url: string, options?: { maxAgeMs?: number }): Promise<string | null> {
  const key = cacheKey(url);
  const cached = await readSourceCacheWithin<string>(key, options?.maxAgeMs ?? FRESH_MS);
  if (cached?.value) return cached.value;

  const contentType = detectContentType(url);
  let text: string | null = null;
  if (contentType === 'pdf') {
    const buffer = await fetchSecUrlBuffer(url);
    text = extractPdfTextFromBuffer(buffer);
  } else {
    const { data } = await axios.get<string>(url, {
      headers: SEC_BASE_HEADERS,
      timeout: 15000,
    });
    text = stripHtml(data).slice(0, 30000);
  }

  await writeSourceCache(key, text);
  return text;
}

export async function fetchSecFilingIndex(url: string, options?: { maxAgeMs?: number }): Promise<string | null> {
  const indexUrl = url.replace(/\/[^/]+$/, '/index.html');
  return fetchSecFilingText(indexUrl, options);
}

export function parseIrMaterialsFromIndex(indexText: string, filingDate: string, form: string): CompanyIrMaterial[] {
  const items: CompanyIrMaterial[] = [];
  const hrefRegex = /href=["']([^"']+)["'][^>]*>([^<]{0,200})/gi;
  let match: RegExpExecArray | null;
  while ((match = hrefRegex.exec(indexText)) !== null) {
    const href = match[1] || '';
    const label = (match[2] || '').trim();
    if (!href) continue;
    if (!/(ex99|99\.|presentation|slides|deck|supplement|investor)/i.test(`${href} ${label}`)) continue;
    if (!/^https?:\/\//i.test(href) && !href.startsWith('/')) continue;
    const url = href.startsWith('http') ? href : `https://www.sec.gov${href}`;
    items.push({
      title: label || href.split('/').pop() || 'Exhibit',
      form,
      filingDate,
      url,
      type: /(presentation|slides|deck|supplement|investor)/i.test(`${href} ${label}`) ? 'presentation' : 'earnings-release',
      source: 'index',
      contentType: detectContentType(url),
      summary: null,
    });
  }
  return items.slice(0, 6);
}

function extractMetricText(text: string, keywords: string[]): string | null {
  for (const keyword of keywords) {
    const pattern = new RegExp(`(?:${keyword})[^.\\n]{0,160}`, 'i');
    const hit = text.match(pattern)?.[0];
    if (hit) return hit.trim().slice(0, 220);
  }
  return null;
}


function isolateMetricClause(raw: string | null, stopKeywords: string[]): string | null {
  if (!raw) return null;
  let result = raw;
  const stopPattern = new RegExp(`\b(?:${stopKeywords.join('|')})\b`, 'i');
  const match = result.match(stopPattern);
  if (match && typeof match.index === 'number' && match.index > 0) {
    result = result.slice(0, match.index);
  }
  return result.split(/[,;](?=\s)/)[0].trim();
}

function bandRange(zone: string, bucket: string): { min: number; max: number } | null {
  const normalizedBucket = bucket.toLowerCase().replace(/\s+/g, ' ').trim();
  const baseMap: Record<string, [number, number]> = {
    'single digit': [1, 9],
    'single digits': [1, 9],
    'double digit': [10, 19],
    'double digits': [10, 19],
    'teens': [10, 19],
    '20s': [20, 29],
    '30s': [30, 39],
    '40s': [40, 49],
    '50s': [50, 59],
    '60s': [60, 69],
  };
  const range = baseMap[normalizedBucket];
  if (!range) return null;
  const [low, high] = range;
  if (zone === 'low') return { min: low, max: low + 3 };
  if (zone === 'mid') return { min: low + 3, max: low + 6 };
  return { min: high - 3, max: high };
}

function fuzzyPercentValue(text: string): { min: number | null; max: number | null; unit: 'percent' | null } | null {
  const lower = text.toLowerCase();
  const aroundBand = lower.match(/\b(around|about|approximately|roughly)\s+(low|mid|high)\s*[- ]?(single digit(?:s)?|double digit(?:s)?|teens|20s|30s|40s|50s|60s)\b/i);
  if (aroundBand) {
    const range = bandRange(aroundBand[2], aroundBand[3]);
    if (range) return { min: range.min, max: range.max, unit: 'percent' };
  }
  const betweenBands = lower.match(/\b(low|mid|high)\s*(?:-|to|and)?\s*(low|mid|high)\s*[- ]?(single digit(?:s)?|double digit(?:s)?|teens|20s|30s|40s|50s|60s)\b/i);
  if (betweenBands) {
    const left = bandRange(betweenBands[1], betweenBands[3]);
    const right = bandRange(betweenBands[2], betweenBands[3]);
    if (left && right) return { min: Math.min(left.min, right.min), max: Math.max(left.max, right.max), unit: 'percent' };
  }

  const band = lower.match(/\b(low|mid|high)\s*[- ]?(single digit(?:s)?|double digit(?:s)?|teens|20s|30s|40s|50s|60s)\b/i);
  if (band) {
    const range = bandRange(band[1], band[2]);
    if (range) return { min: range.min, max: range.max, unit: 'percent' };
  }

  const percentRange = lower.match(/\b(?:between\s+)?(\d+(?:\.\d+)?)\s*%?\s*(?:and|to|\-|–)\s*(\d+(?:\.\d+)?)\s*%/i);
  if (percentRange) {
    return { min: Number(percentRange[1]), max: Number(percentRange[2]), unit: 'percent' };
  }

  const approx = lower.match(/\b(about|approximately|around|roughly)\s+(\d+(?:\.\d+)?)\s*%/i);
  if (approx) {
    const value = Number(approx[2]);
    return { min: value, max: value, unit: 'percent' };
  }
  const above = lower.match(/\b(at least|greater than|more than|above)\s+(\d+(?:\.\d+)?)\s*%/i);
  if (above) {
    const value = Number(above[2]);
    return { min: value, max: null, unit: 'percent' };
  }
  const below = lower.match(/\b(up to|less than|below)\s+(\d+(?:\.\d+)?)\s*%/i);
  if (below) {
    const value = Number(below[2]);
    return { min: null, max: value, unit: 'percent' };
  }
  return null;
}

function magnitudeMultiplier(unit?: string | null): number {
  const normalized = (unit ?? '').toLowerCase();
  if (['billion', 'bn', 'b'].includes(normalized)) return 1_000_000_000;
  if (['million', 'mn', 'm'].includes(normalized)) return 1_000_000;
  return 1;
}

function fuzzyUsdValue(text: string): { min: number | null; max: number | null; unit: 'usd' | null } | null {
  const cleaned = text.replace(/,/g, '');
  const between = cleaned.match(/\bbetween\s*\$?\s*(\d+(?:\.\d+)?)\s*(billion|bn|b|million|mn|m)?\s*(?:and|to)\s*\$?\s*(\d+(?:\.\d+)?)\s*(billion|bn|b|million|mn|m)?/i);
  if (between) {
    const leftMultiplier = magnitudeMultiplier(between[2]);
    const rightMultiplier = magnitudeMultiplier(between[4] || between[2]);
    return { min: Number(between[1]) * leftMultiplier, max: Number(between[3]) * rightMultiplier, unit: 'usd' };
  }
  const approx = cleaned.match(/\b(about|approximately|around|roughly)\s*\$?\s*(\d+(?:\.\d+)?)\s*(billion|bn|b|million|mn|m)/i);
  if (approx) {
    const value = Number(approx[2]) * magnitudeMultiplier(approx[3]);
    return { min: value, max: value, unit: 'usd' };
  }
  const above = cleaned.match(/\b(at least|greater than|more than|above)\s*\$?\s*(\d+(?:\.\d+)?)\s*(billion|bn|b|million|mn|m)?/i);
  if (above) {
    const value = Number(above[2]) * magnitudeMultiplier(above[3]);
    return { min: value, max: null, unit: 'usd' };
  }
  const below = cleaned.match(/\b(up to|less than|below)\s*\$?\s*(\d+(?:\.\d+)?)\s*(billion|bn|b|million|mn|m)?/i);
  if (below) {
    const value = Number(below[2]) * magnitudeMultiplier(below[3]);
    return { min: null, max: value, unit: 'usd' };
  }
  return null;
}

export function parseGuidanceValue(raw: string | null): CompanyGuidanceMetricValue | null {
  if (!raw) return null;
  const cleaned = raw.replace(/,/g, '');
  const fuzzyPct = fuzzyPercentValue(cleaned);
  if (fuzzyPct) return { raw, min: fuzzyPct.min, max: fuzzyPct.max, unit: 'percent' };
  const singlePct = cleaned.match(/(-?\d+(?:\.\d+)?)\s*%/i);
  if (singlePct) return { raw, min: Number(singlePct[1]), max: Number(singlePct[1]), unit: 'percent' };
  const pctRange = [...cleaned.matchAll(/(-?\d+(?:\.\d+)?)\s*%?\s*(?:to|and|\-|–)\s*(-?\d+(?:\.\d+)?)\s*%/gi)][0];
  if (pctRange) {
    return { raw, min: Number(pctRange[1]), max: Number(pctRange[2]), unit: 'percent' };
  }
  const bpsRange = [...cleaned.matchAll(/(-?\d+(?:\.\d+)?)\s*(?:to|and|\-|–)\s*(-?\d+(?:\.\d+)?)\s*bps/gi)][0];
  if (bpsRange) {
    return { raw, min: Number(bpsRange[1]), max: Number(bpsRange[2]), unit: 'bps' };
  }
  const billions = [...cleaned.matchAll(/\$?(-?\d+(?:\.\d+)?)\s*(?:to|and|\-|–)\s*\$?(-?\d+(?:\.\d+)?)\s*(billion|bn|b)/gi)][0];
  if (billions) {
    const multiplier = magnitudeMultiplier(billions[3]);
    return { raw, min: Number(billions[1]) * multiplier, max: Number(billions[2]) * multiplier, unit: 'usd' };
  }
  const millions = [...cleaned.matchAll(/\$?(-?\d+(?:\.\d+)?)\s*(?:to|and|\-|–)\s*\$?(-?\d+(?:\.\d+)?)\s*(million|mn|m)/gi)][0];
  if (millions) {
    const multiplier = magnitudeMultiplier(millions[3]);
    return { raw, min: Number(millions[1]) * multiplier, max: Number(millions[2]) * multiplier, unit: 'usd' };
  }
  const fuzzyUsd = fuzzyUsdValue(cleaned);
  if (fuzzyUsd) return { raw, min: fuzzyUsd.min, max: fuzzyUsd.max, unit: 'usd' };
  const singleUsd = cleaned.match(/\$\s*(-?\d+(?:\.\d+)?)(?:\s*(billion|bn|b|million|mn|m))?/i);
  if (singleUsd) {
    const multiplier = magnitudeMultiplier(singleUsd[2]);
    return { raw, min: Number(singleUsd[1]) * multiplier, max: Number(singleUsd[1]) * multiplier, unit: 'usd' };
  }
  return { raw, min: null, max: null, unit: 'other' };
}

function detectMetricDirection(text: string, keywords: string[]): CompanyGuidanceSummary['revenue'] {
  const around = keywords.map((k) => `(?:${k})[^.]{0,160}`).join('|');
  if (!around) return null;
  const raised = new RegExp(`${around}(?:raise|raised|increase|increased|higher|up)`, 'i');
  const lowered = new RegExp(`${around}(?:lower|lowered|decrease|decreased|down|reduced|softer)`, 'i');
  const affirmed = new RegExp(`${around}(?:affirm|affirmed|maintain|maintained|reiterate|reiterated|unchanged)`, 'i');
  const mentioned = new RegExp(`${around}`, 'i');
  if (raised.test(text)) return 'raised';
  if (lowered.test(text)) return 'lowered';
  if (affirmed.test(text)) return 'affirmed';
  if (mentioned.test(text)) return 'mentioned';
  return null;
}

export function summarizeGuidance(text: string): CompanyGuidanceSummary {
  const revenueKeywords = ['revenue', 'sales', 'top line'];
  const marginKeywords = ['margin', 'gross margin', 'operating margin'];
  const capexKeywords = ['capex', 'capital expenditure', 'capital spending'];
  const fcfKeywords = ['free cash flow', 'fcf', 'cash flow'];

  const revenue = detectMetricDirection(text, revenueKeywords);
  const margin = detectMetricDirection(text, marginKeywords);
  const capex = detectMetricDirection(text, capexKeywords);
  const fcf = detectMetricDirection(text, fcfKeywords);

  const revenueText = isolateMetricClause(extractMetricText(text, revenueKeywords), [...marginKeywords, ...capexKeywords, ...fcfKeywords]);
  const marginText = isolateMetricClause(extractMetricText(text, marginKeywords), [...capexKeywords, ...fcfKeywords]);
  const capexText = isolateMetricClause(extractMetricText(text, capexKeywords), fcfKeywords);
  const fcfText = isolateMetricClause(extractMetricText(text, fcfKeywords), capexKeywords);

  const revenueValue = parseGuidanceValue(revenueText);
  const marginValue = parseGuidanceValue(marginText);
  const capexValue = parseGuidanceValue(capexText);
  const fcfValue = parseGuidanceValue(fcfText);

  const evidence = [
    text.match(/(raised|lowered|affirmed|maintained|reiterated)[^.]{0,120}(guidance|outlook)/i)?.[0],
    revenueText,
    marginText,
    capexText,
    fcfText,
  ].filter((x): x is string => Boolean(x)).slice(0, 5);

  const directions = [revenue, margin, capex, fcf];
  const hasRaised = directions.includes('raised');
  const hasLowered = directions.includes('lowered');
  const hasAffirmed = directions.includes('affirmed');

  let stance: CompanyGuidanceSummary['stance'] = 'unclear';
  if ((hasRaised && hasLowered) || ([hasRaised, hasLowered, hasAffirmed].filter(Boolean).length >= 2)) stance = 'mixed';
  else if (hasRaised) stance = 'raised';
  else if (hasLowered) stance = 'lowered';
  else if (hasAffirmed) stance = 'affirmed';

  return { stance, revenue, margin, capex, fcf, revenueText, marginText, capexText, fcfText, revenueValue, marginValue, capexValue, fcfValue, evidence };
}

export function summarizeEarningsFiling(text: string, description?: string | null): { summary: string | null; guidanceSignals: string[]; guidanceSummary: CompanyGuidanceSummary } {
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
  const guidanceSummary = summarizeGuidance(text);
  const guidanceSignals = [
    guidanceSummary.stance === 'raised' ? 'guidance_up' : null,
    guidanceSummary.stance === 'lowered' ? 'guidance_down' : null,
    guidanceSummary.stance === 'affirmed' ? 'guidance_affirmed' : null,
    /above expectations|beat expectations|better than expected/i.test(text) ? 'beat' : null,
    /below expectations|miss(ed)? expectations|weaker than expected/i.test(text) ? 'miss' : null,
  ].filter((item): item is string => Boolean(item));

  const summary = matches.length > 0 ? matches.slice(0, 2).join(' ') : description ?? null;

  return {
    summary: summary ? summary.slice(0, 400) : null,
    guidanceSignals,
    guidanceSummary,
  };
}

export function summarizeIrMaterialText(text: string): string | null {
  const patterns = [
    /investor presentation[^.]{0,180}\./i,
    /revenue[^.]{0,180}\./i,
    /guidance[^.]{0,180}\./i,
    /free cash flow[^.]{0,180}\./i,
    /capital expenditure[^.]{0,180}\./i,
  ];
  const hits: string[] = [];
  for (const pattern of patterns) {
    const hit = text.match(pattern)?.[0];
    if (hit && !hits.includes(hit)) hits.push(hit);
  }
  return hits.length ? hits.slice(0, 2).join(' ').slice(0, 320) : null;
}


export function inferSegmentGeoEntriesFromText(text: string): { segmentMix: Array<{ label: string; value: number | null; unit?: string | null; percentOfTotal?: number | null }>; geoMix: Array<{ label: string; value: number | null; unit?: string | null; percentOfTotal?: number | null }>; note: string | null } {
  const segmentPatterns: Array<[RegExp, string]> = [
    [/\bdata center\b/ig, 'Data Center'],
    [/\bgaming\b/ig, 'Gaming'],
    [/\bclient\b/ig, 'Client'],
    [/\bcloud\b/ig, 'Cloud'],
    [/\badvertising\b/ig, 'Advertising'],
    [/\benterprise\b/ig, 'Enterprise'],
    [/\bautomotive\b/ig, 'Automotive'],
    [/\bnetworking\b/ig, 'Networking'],
    [/\bconsumer\b/ig, 'Consumer'],
  ];
  const geoPatterns: Array<[RegExp, string]> = [
    [/\bamericas\b/ig, 'Americas'],
    [/\bemea\b/ig, 'EMEA'],
    [/\bapac\b/ig, 'APAC'],
    [/\bgreater china\b/ig, 'Greater China'],
    [/\bchina\b/ig, 'China'],
    [/\bjapan\b/ig, 'Japan'],
    [/\brest of world\b/ig, 'Rest of World'],
    [/\bunited states\b/ig, 'United States'],
  ];
  const percentify = (items: Array<{ label: string; value: number | null; unit?: string | null }>) => {
    const total = items.reduce((sum, item) => sum + (item.value ?? 0), 0);
    return items.map((item) => ({
      ...item,
      percentOfTotal: total > 0 && item.value !== null ? Number(((item.value / total) * 100).toFixed(1)) : null,
    }));
  };
  const build = (patterns: Array<[RegExp, string]>) => patterns
    .map(([pattern, label]) => ({ label, value: (text.match(pattern) ?? []).length || null, unit: 'mentions' }))
    .filter((item) => (item.value ?? 0) > 0)
    .sort((a, b) => (b.value ?? 0) - (a.value ?? 0))
    .slice(0, 5);
  const segmentMix = percentify(build(segmentPatterns));
  const geoMix = percentify(build(geoPatterns));
  const parts: string[] = [];
  if (segmentMix.length) parts.push(`세그먼트(텍스트): ${segmentMix.slice(0, 3).map((item) => `${item.label} ${item.percentOfTotal}%`).join(', ')}`);
  if (geoMix.length) parts.push(`지역(텍스트): ${geoMix.slice(0, 3).map((item) => `${item.label} ${item.percentOfTotal}%`).join(', ')}`);
  return { segmentMix, geoMix, note: parts.length ? parts.join(' / ') : null };
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

  for (const [pattern, label] of segmentPatterns) if (pattern.test(text) && !segments.includes(label)) segments.push(label);
  for (const [pattern, label] of geoPatterns) if (pattern.test(text) && !geos.includes(label)) geos.push(label);

  if (segments.length === 0 && geos.length === 0) return null;
  const parts: string[] = [];
  if (segments.length > 0) parts.push(`세그먼트: ${segments.slice(0, 3).join(', ')}`);
  if (geos.length > 0) parts.push(`지역: ${geos.slice(0, 3).join(', ')}`);
  return parts.join(' / ');
}
