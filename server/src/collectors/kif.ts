/**
 * KIF (Korea Institute of Finance) 최신 이슈 발행일 추적 (30차 P2-E #31).
 *
 * 노션 §KIF — 최신 issue 발행일 자동 추적.
 * https://www.kif.re.kr 에서 최신 보고서 날짜 파싱.
 */

import axios from 'axios';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const CACHE_KEY = 'kif-latest-issue';
const FRESH_MS = 12 * 60 * 60 * 1000; // 12h

export async function fetchKifLatestIssueDaysAgo(): Promise<number | null> {
  const cached = await readSourceCacheWithin<number>(CACHE_KEY, FRESH_MS);
  if (cached) return cached.value;
  try {
    const { data: html } = await axios.get<string>(
      'https://www.kif.re.kr/kif2/publication/wp_list.aspx',
      {
        headers: {
          'User-Agent': 'Mozilla/5.0',
          'Accept-Language': 'ko-KR,ko;q=0.9',
        },
        timeout: 10000,
      },
    );
    // 날짜 패턴 YYYY-MM-DD 또는 YYYY.MM.DD
    const dateMatch = html.match(/(\d{4})[.\-](\d{2})[.\-](\d{2})/);
    if (!dateMatch) return null;
    const dateStr = `${dateMatch[1]}-${dateMatch[2]}-${dateMatch[3]}`;
    const published = new Date(dateStr);
    const now = new Date();
    const daysAgo = Math.floor((now.getTime() - published.getTime()) / (1000 * 60 * 60 * 24));
    await writeSourceCache(CACHE_KEY, daysAgo);
    return daysAgo;
  } catch {
    return null;
  }
}
