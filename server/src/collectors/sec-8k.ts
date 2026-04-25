/**
 * SEC 8-K 미국 중대 이벤트 24h 카운트 (20차 노션 A4).
 *
 * 노션 §"8-K(미국) / 전자공시(한국): 기업의 중대 이벤트". 한국 DART 와 양국 병기.
 * SEC EDGAR full-text search RSS — form-type=8-K, 최근 1일 발행 카운트.
 *
 * 캐시: fresh 2h / stale 24h.
 */

import axios from 'axios';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const log = childLogger({ module: 'collector.sec-8k' });
const CACHE_KEY = 'sec-8k-24h';
const FRESH_MS = 2 * 60 * 60 * 1000;
const STALE_MS = 24 * 60 * 60 * 1000;

export interface Sec8KSnapshot {
  count24h: number;
  topItems: string[]; // top 10 form 제목/회사
  fetchedAt: string;
}

export async function fetchSec8KCount(): Promise<Sec8KSnapshot | null> {
  const cached = await readSourceCacheWithin<Sec8KSnapshot>(CACHE_KEY, FRESH_MS);
  if (cached) return cached.value;
  try {
    const { data: atom } = await axios.get<string>(
      'https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent&type=8-K&company=&dateb=&owner=include&count=100&output=atom',
      {
        headers: {
          'User-Agent': 'MacroSquare research (ekdan9172@gmail.com)',
          Accept: 'application/atom+xml',
        },
        timeout: 10000,
      },
    );
    const titleMatches = atom.match(/<title[^>]*>([^<]+)<\/title>/gi) || [];
    const titles = titleMatches.slice(1, 51).map((t) => t.replace(/<[^>]+>/g, '').trim());
    const result: Sec8KSnapshot = {
      count24h: titles.length,
      topItems: titles.slice(0, 10),
      fetchedAt: new Date().toISOString(),
    };
    await writeSourceCache(CACHE_KEY, result);
    log.info({ count: result.count24h }, 'sec 8-k live');
    return result;
  } catch (err) {
    log.warn({ error: serializeError(err) }, 'SEC 8-K fetch failed');
    const stale = await readSourceCacheWithin<Sec8KSnapshot>(CACHE_KEY, STALE_MS);
    return stale?.value ?? null;
  }
}
