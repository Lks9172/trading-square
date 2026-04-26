/**
 * Yahoo Finance crumb + cookie 인증 (공용).
 *
 * 2024+ Yahoo quoteSummary / v7/quote 일부 엔드포인트는 crumb + cookie 인증 필수.
 * fc.yahoo.com 으로 cookie 획득 → query2/v1/test/getcrumb 로 crumb 획득.
 *
 * TTL 1시간 캐시. 401 시 호출자가 cachedCrumb 무효화 → 다음 호출 재취득.
 */

import axios from 'axios';

interface CrumbCache {
  crumb: string;
  cookie: string;
  at: number;
}

let cached: CrumbCache | null = null;
const TTL_MS = 60 * 60 * 1000;

export async function getYahooCrumb(): Promise<{ crumb: string; cookie: string } | null> {
  if (cached && Date.now() - cached.at < TTL_MS) {
    return { crumb: cached.crumb, cookie: cached.cookie };
  }
  try {
    const step1 = await axios.get('https://fc.yahoo.com', {
      headers: { 'User-Agent': 'Mozilla/5.0' },
      timeout: 10000,
      validateStatus: () => true,
      maxRedirects: 5,
    });
    const setCookie = step1.headers['set-cookie'];
    const cookie = Array.isArray(setCookie) ? setCookie.map((c) => c.split(';')[0]).join('; ') : '';
    if (!cookie) return null;
    const step2 = await axios.get('https://query2.finance.yahoo.com/v1/test/getcrumb', {
      headers: { 'User-Agent': 'Mozilla/5.0', Cookie: cookie },
      timeout: 10000,
    });
    const crumb = typeof step2.data === 'string' ? step2.data.trim() : '';
    if (!crumb) return null;
    cached = { crumb, cookie, at: Date.now() };
    return { crumb, cookie };
  } catch {
    return null;
  }
}

export function invalidateYahooCrumb(): void {
  cached = null;
}
