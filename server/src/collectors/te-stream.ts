/**
 * TradingEconomics stream 헤드라인 신선도 (20차 노션 A5).
 *
 * 노션 §"전세계 주요 경제 뉴스". 정량 데이터 X, 헤드라인 발행 빈도 = 매크로 노이즈 강도.
 * 캐시: fresh 1h / stale 12h.
 */

import axios from 'axios';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const log = childLogger({ module: 'collector.te-stream' });
const CACHE_KEY = 'te-stream-latest';
const FRESH_MS = 60 * 60 * 1000;
const STALE_MS = 12 * 60 * 60 * 1000;

export interface TeStreamSnapshot {
  latestHeadline: string;
  publishedAtIso: string;
  minutesAgo: number;
  count24h: number;
  fetchedAt: string;
}

export async function fetchTeStreamLatest(): Promise<TeStreamSnapshot | null> {
  const cached = await readSourceCacheWithin<TeStreamSnapshot>(CACHE_KEY, FRESH_MS);
  if (cached) return cached.value;
  try {
    // TE RSS — 무료 공개, 키 없이도 헤드라인만 추출 가능
    const { data: xml } = await axios.get<string>(
      'https://tradingeconomics.com/rss/news.aspx',
      {
        headers: {
          'User-Agent': 'MacroSquare research (ekdan9172@gmail.com)',
          Accept: 'application/rss+xml, application/xml, text/xml',
        },
        timeout: 10000,
      },
    );
    const itemRe = /<item[\s\S]*?<\/item>/gi;
    const items = (xml.match(itemRe) || []).slice(0, 50);
    if (items.length === 0) {
      log.warn({}, 'TE stream returned 0 items');
      return null;
    }
    const titleMatch = items[0].match(/<title[^>]*>([\s\S]*?)<\/title>/i);
    const dateMatch = items[0].match(/<pubDate[^>]*>([^<]+)<\/pubDate>/i);
    const title = titleMatch ? titleMatch[1].replace(/<\!\[CDATA\[|\]\]>/g, '').trim() : '(no title)';
    const dateStr = dateMatch ? dateMatch[1].trim() : '';
    const publishedAt = dateStr ? new Date(dateStr) : null;
    const minutesAgo = publishedAt && !Number.isNaN(publishedAt.getTime())
      ? Math.floor((Date.now() - publishedAt.getTime()) / 60000)
      : 999;
    // 24h 내 카운트
    const cutoff = Date.now() - 86400000;
    let count24h = 0;
    for (const item of items) {
      const dm = item.match(/<pubDate[^>]*>([^<]+)<\/pubDate>/i);
      if (!dm) continue;
      const dt = new Date(dm[1].trim()).getTime();
      if (Number.isFinite(dt) && dt >= cutoff) count24h++;
    }
    const result: TeStreamSnapshot = {
      latestHeadline: title.slice(0, 140),
      publishedAtIso: publishedAt?.toISOString() ?? '',
      minutesAgo,
      count24h,
      fetchedAt: new Date().toISOString(),
    };
    await writeSourceCache(CACHE_KEY, result);
    log.info({ minutesAgo, count24h }, 'TE stream live');
    return result;
  } catch (err) {
    log.warn({ error: serializeError(err) }, 'TE stream fetch failed');
    const stale = await readSourceCacheWithin<TeStreamSnapshot>(CACHE_KEY, STALE_MS);
    return stale?.value ?? null;
  }
}
