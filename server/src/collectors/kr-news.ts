/**
 * 한국 거시·해외증시 뉴스 RSS 수집 (21차 P2#20).
 *
 * 노션 §"서울경제 / 한경 글로벌마켓 / 글로벌마켓모니터(einfomax)" 정합.
 * 정량 데이터 X — 헤드라인 발행 빈도 / 신선도만 추적.
 * 캐시: fresh 1h / stale 12h.
 */

import axios from 'axios';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const log = childLogger({ module: 'collector.kr-news' });
const CACHE_KEY = 'kr-news-headlines';
const FRESH_MS = 60 * 60 * 1000;
const STALE_MS = 12 * 60 * 60 * 1000;

const SOURCES: Array<{ name: string; url: string }> = [
  { name: 'hankyung-global', url: 'https://www.hankyung.com/feed/finance' },
  { name: 'sedaily-stock', url: 'https://rss.sedaily.com/RSS/RSS_Stock.xml' },
];

export interface KrNewsSnapshot {
  bySource: Record<string, { latestHeadline: string; minutesAgo: number; count24h: number }>;
  totalCount24h: number;
  fetchedAt: string;
}

function parseRss(xml: string): { title: string; dateStr: string; count24h: number } | null {
  const itemRe = /<item[\s\S]*?<\/item>/gi;
  const items = (xml.match(itemRe) || []).slice(0, 60);
  if (items.length === 0) return null;
  const titleMatch = items[0].match(/<title[^>]*>([\s\S]*?)<\/title>/i);
  const dateMatch = items[0].match(/<pubDate[^>]*>([^<]+)<\/pubDate>/i);
  const title = titleMatch ? titleMatch[1].replace(/<\!\[CDATA\[|\]\]>/g, '').replace(/<[^>]+>/g, '').trim() : '(no title)';
  const dateStr = dateMatch ? dateMatch[1].trim() : '';
  const cutoff = Date.now() - 86400000;
  let count24h = 0;
  for (const item of items) {
    const dm = item.match(/<pubDate[^>]*>([^<]+)<\/pubDate>/i);
    if (!dm) continue;
    const dt = new Date(dm[1].trim()).getTime();
    if (Number.isFinite(dt) && dt >= cutoff) count24h++;
  }
  return { title, dateStr, count24h };
}

async function tryFetchOne(name: string, url: string): Promise<{ name: string; latestHeadline: string; minutesAgo: number; count24h: number } | null> {
  try {
    const { data: xml } = await axios.get<string>(url, {
      headers: {
        'User-Agent': 'Mozilla/5.0',
        Accept: 'application/rss+xml, application/xml, text/xml, */*',
        'Accept-Language': 'ko-KR,ko;q=0.9',
      },
      timeout: 10000,
    });
    if (typeof xml !== 'string' || xml.length < 100) return null;
    const parsed = parseRss(xml);
    if (!parsed) return null;
    const publishedAt = parsed.dateStr ? new Date(parsed.dateStr) : null;
    const minutesAgo = publishedAt && !Number.isNaN(publishedAt.getTime())
      ? Math.floor((Date.now() - publishedAt.getTime()) / 60000)
      : 999;
    return { name, latestHeadline: parsed.title.slice(0, 120), minutesAgo, count24h: parsed.count24h };
  } catch (err: any) {
    log.warn({ source: name, status: err?.response?.status }, 'KR news source failed');
    return null;
  }
}

export async function fetchKrNewsHeadlines(): Promise<KrNewsSnapshot | null> {
  const cached = await readSourceCacheWithin<KrNewsSnapshot>(CACHE_KEY, FRESH_MS);
  if (cached) return cached.value;
  const results = await Promise.all(SOURCES.map((s) => tryFetchOne(s.name, s.url)));
  const bySource: KrNewsSnapshot['bySource'] = {};
  let totalCount24h = 0;
  for (const r of results) {
    if (!r) continue;
    bySource[r.name] = { latestHeadline: r.latestHeadline, minutesAgo: r.minutesAgo, count24h: r.count24h };
    totalCount24h += r.count24h;
  }
  if (Object.keys(bySource).length === 0) {
    const stale = await readSourceCacheWithin<KrNewsSnapshot>(CACHE_KEY, STALE_MS);
    return stale?.value ?? null;
  }
  const snapshot: KrNewsSnapshot = {
    bySource,
    totalCount24h,
    fetchedAt: new Date().toISOString(),
  };
  await writeSourceCache(CACHE_KEY, snapshot);
  log.info({ sources: Object.keys(bySource).length, totalCount24h }, 'KR news live');
  return snapshot;
}
