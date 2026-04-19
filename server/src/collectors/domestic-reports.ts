/**
 * 국내 증권사 리포트 최신 발행일 경량 수집 (17차 Phase 3 B2/B3/B4).
 *
 * 영상 원문 정합:
 *   - video4 §국내기관 "증권사 리포트 발행 빈도 = 리스크 인지 속도"
 *   - notion §국내수급 "리포트 발행 활발 ↔ 수급 민감도"
 *
 * Naver Finance 리서치 리스트 (market outlook) 최신 발행일만 추적.
 * 본문 수집은 아직 불필요 — 최신 발행일만으로도 "리포트 빈도" 시그널 산출 가능.
 *
 * 목록:
 *   - 시황속보: /research/market_info_list.naver
 *   - 투자정보: /research/invest_list.naver
 *   - 경제분석: /research/economy_list.naver
 *
 * Fresh 24h, stale 7d — Naver 트래픽 최소화.
 */

import axios from 'axios';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const log = childLogger({ module: 'collector.domestic-reports' });
const CACHE_KEY = 'domestic-report-latest-dates';
const FRESH_MS = 24 * 60 * 60 * 1000;
const STALE_MS = 7 * 24 * 60 * 60 * 1000;

export interface DomesticReportsLatest {
  marketInfo?: { latestDate: string; daysAgo: number; title?: string };
  investInfo?: { latestDate: string; daysAgo: number; title?: string };
  economy?: { latestDate: string; daysAgo: number; title?: string };
  fetchedAt: string;
}

const LISTS = [
  { key: 'marketInfo', url: 'https://finance.naver.com/research/market_info_list.naver' },
  { key: 'investInfo', url: 'https://finance.naver.com/research/invest_list.naver' },
  { key: 'economy', url: 'https://finance.naver.com/research/economy_list.naver' },
] as const;

function daysBetween(a: Date, b: Date): number {
  return Math.floor((a.getTime() - b.getTime()) / 86400000);
}

function parseLatest(html: string): { latestDate: string; title?: string } | null {
  // Naver research list pages use <table class="type_1"> with date format "YY.MM.DD".
  // Regex 기반 경량 파서 — 외부 의존성 추가 없이 첫 번째 발견된 날짜/제목 추출.
  const tableMatch = html.match(/<table[^>]*class="type_1"[\s\S]*?<\/table>/i);
  const scope = tableMatch ? tableMatch[0] : html;
  const dateRe = /(\d{2,4})\.(\d{2})\.(\d{2})/;
  const rowRe = /<tr[\s\S]*?<\/tr>/gi;
  const rows = scope.match(rowRe) || [];
  for (const row of rows) {
    const m = row.match(dateRe);
    if (!m) continue;
    const yy = m[1].length === 2 ? 2000 + parseInt(m[1], 10) : parseInt(m[1], 10);
    const iso = `${yy}-${m[2]}-${m[3]}`;
    // 제목 추출: 첫 번째 anchor 텍스트
    const titleMatch = row.match(/<a[^>]*>([\s\S]*?)<\/a>/i);
    const title = titleMatch ? titleMatch[1].replace(/<[^>]+>/g, '').trim() : undefined;
    return { latestDate: iso, title: title || undefined };
  }
  return null;
}

async function fetchOne(url: string): Promise<{ latestDate: string; title?: string } | null> {
  try {
    const { data } = await axios.get(url, {
      timeout: 10000,
      headers: {
        'User-Agent': 'Mozilla/5.0',
        'Accept-Language': 'ko-KR,ko;q=0.9',
      },
    });
    return parseLatest(typeof data === 'string' ? data : '');
  } catch (err) {
    log.warn({ url, error: serializeError(err) }, 'domestic report fetch failed');
    return null;
  }
}

async function fetchLive(): Promise<DomesticReportsLatest | null> {
  const now = new Date();
  const out: DomesticReportsLatest = { fetchedAt: now.toISOString() };
  let hits = 0;
  for (const l of LISTS) {
    const parsed = await fetchOne(l.url);
    if (parsed) {
      const d = new Date(parsed.latestDate);
      (out as any)[l.key] = {
        latestDate: parsed.latestDate,
        daysAgo: daysBetween(now, d),
        title: parsed.title,
      };
      hits++;
    }
    await new Promise((r) => setTimeout(r, 300));
  }
  if (hits === 0) return null;
  return out;
}

export async function fetchDomesticReportsLatest(): Promise<DomesticReportsLatest | null> {
  const cached = await readSourceCacheWithin<DomesticReportsLatest>(CACHE_KEY, FRESH_MS);
  if (cached) {
    log.info({ ageMs: cached.ageMs }, 'domestic reports cache hit');
    return cached.value;
  }
  const live = await fetchLive();
  if (live) {
    await writeSourceCache(CACHE_KEY, live);
    log.info({}, 'domestic reports live collected');
    return live;
  }
  const stale = await readSourceCacheWithin<DomesticReportsLatest>(CACHE_KEY, STALE_MS);
  if (stale) {
    log.warn({ ageMs: stale.ageMs }, 'domestic reports stale fallback');
    return stale.value;
  }
  return null;
}
