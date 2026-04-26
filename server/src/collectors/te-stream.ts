/**
 * 글로벌 매크로 헤드라인 신선도 (20차 노션 A5, 우회 활성화).
 *
 * 노션 §"전세계 주요 경제 뉴스". TradingEconomics 가 403 차단 →
 * MarketWatch / CNBC / Investing.com / Yahoo Finance RSS multi-fallback.
 * 캐시: fresh 1h / stale 12h.
 */

import axios from 'axios';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const log = childLogger({ module: 'collector.te-stream' });
const CACHE_KEY = 'te-stream-latest';
const FRESH_MS = 60 * 60 * 1000;
const STALE_MS = 12 * 60 * 60 * 1000;

// 우회 RSS 소스 — 우선순위 순. 모두 무인증 무료.
const RSS_SOURCES: Array<{ name: string; url: string }> = [
  { name: 'marketwatch-top', url: 'https://feeds.marketwatch.com/marketwatch/topstories/' },
  { name: 'marketwatch-econ', url: 'https://feeds.marketwatch.com/marketwatch/marketpulse/' },
  { name: 'cnbc-top', url: 'https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=10000664' },
  { name: 'cnbc-econ', url: 'https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=20910258' },
  { name: 'investing', url: 'https://www.investing.com/rss/news.rss' },
  { name: 'yahoo-finance', url: 'https://finance.yahoo.com/news/rssindex' },
  // TradingEconomics 는 마지막 fallback (403 차단 가능성)
  { name: 'tradingeconomics', url: 'https://tradingeconomics.com/rss/news.aspx' },
];

export interface TeStreamSnapshot {
  latestHeadline: string;
  publishedAtIso: string;
  minutesAgo: number;
  count24h: number;
  source: string;          // 활성 RSS 소스 이름
  fetchedAt: string;
}

function parseRss(xml: string): { title: string; dateStr: string; itemCount: number; count24h: number } | null {
  const itemRe = /<item[\s\S]*?<\/item>/gi;
  const items = (xml.match(itemRe) || []).slice(0, 80);
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
  return { title, dateStr, itemCount: items.length, count24h };
}

async function tryFetchOne(name: string, url: string): Promise<TeStreamSnapshot | null> {
  try {
    const { data: xml } = await axios.get<string>(url, {
      headers: {
        'User-Agent': 'Mozilla/5.0',
        Accept: 'application/rss+xml, application/xml, text/xml, */*',
        'Accept-Language': 'en-US,en;q=0.9',
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
    return {
      latestHeadline: parsed.title.slice(0, 140),
      publishedAtIso: publishedAt?.toISOString() ?? '',
      minutesAgo,
      count24h: parsed.count24h,
      source: name,
      fetchedAt: new Date().toISOString(),
    };
  } catch (err: any) {
    log.warn({ source: name, status: err?.response?.status, error: serializeError(err) }, 'RSS source failed');
    return null;
  }
}

export async function fetchTeStreamLatest(): Promise<TeStreamSnapshot | null> {
  const cached = await readSourceCacheWithin<TeStreamSnapshot>(CACHE_KEY, FRESH_MS);
  if (cached) return cached.value;
  // 우선순위 순서대로 시도 — 첫 성공 시 즉시 반환.
  for (const src of RSS_SOURCES) {
    const result = await tryFetchOne(src.name, src.url);
    if (result) {
      await writeSourceCache(CACHE_KEY, result);
      log.info({ source: src.name, minutesAgo: result.minutesAgo, count24h: result.count24h }, 'macro headlines live');
      return result;
    }
  }
  log.warn({}, 'all RSS sources failed');
  const stale = await readSourceCacheWithin<TeStreamSnapshot>(CACHE_KEY, STALE_MS);
  return stale?.value ?? null;
}
