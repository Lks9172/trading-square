/**
 * 경제 캘린더 수집기 — FOMC/CPI/NFP/PCE 등 주요 매크로 이벤트 D-day.
 *
 * 데이터 소스:
 * 1. FRED `/fred/releases/dates` API — CPI/NFP/PCE 등 공식 release 발표일
 * 2. 하드코딩된 FOMC 회의 일정 (연 8회, FRED 미제공)
 *
 * 영상4 §04 "노란불 지표" 트리거로 D-day 체크용.
 */

import axios from 'axios';
import { childLogger } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

export interface CalendarEvent {
  date: string;            // YYYY-MM-DD (발표일)
  name: string;            // 이벤트명
  category: 'FOMC' | 'CPI' | 'NFP' | 'PCE' | 'GDP' | 'OTHER';
  releaseId?: number;
  daysUntil: number;       // 오늘 기준 D-일 (음수면 과거)
  importance: 'high' | 'medium';
}
const log = childLogger({ module: 'collector.calendar' });
const CALENDAR_CACHE_KEY = 'economic-calendar';
const CALENDAR_STALE_MS = 14 * 24 * 60 * 60 * 1000;

/** FRED release id 정의 */
const FRED_RELEASES: Array<{ id: number; name: string; category: CalendarEvent['category']; importance: 'high' | 'medium' }> = [
  { id: 10,  name: 'Consumer Price Index',            category: 'CPI',   importance: 'high' },
  { id: 50,  name: 'Employment Situation',            category: 'NFP',   importance: 'high' },
  { id: 21,  name: 'Personal Income and Outlays',     category: 'PCE',   importance: 'high' },
  { id: 53,  name: 'Gross Domestic Product',          category: 'GDP',   importance: 'medium' },
];

/**
 * FOMC 회의 일정 — 연 8회, 공식 공개. 2026 예상 일정 하드코딩.
 * FRED에 별도 시리즈 없음. 미국 Fed의 공식 schedule은 https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm
 * 정확한 연도별 업데이트는 매년 초.
 */
/**
 * 정적 정치·정책 이벤트 — FRED에 없는 굵직한 마커.
 * 영상4 §104 "모든 정책 퍼즐이 선거로 수렴" 철학 반영.
 */
const STATIC_POLITICAL_EVENTS: Array<{ date: string; name: string; category: CalendarEvent['category']; importance: 'high' | 'medium' }> = [
  { date: '2026-11-03', name: '🗳 미 중간선거', category: 'OTHER', importance: 'high' },
  // 8차 TOP7 Fix #7: 추경·WGBI 장기 정책 이벤트 + 6개월 효과 반영 마커
  { date: '2026-04-15', name: '🏛 추경 국회 제출 (성장률 +0.2%p 효과, 2026-10-15 반영 예상)', category: 'OTHER', importance: 'high' },
  { date: '2026-10-15', name: '🏛 추경 성장률 +0.2%p 6개월 효과 반영일', category: 'OTHER', importance: 'medium' },
  { date: '2026-10-01', name: '🇰🇷 WGBI 편입 예상일 (외국인 자금 유입 장기 환율 안정)', category: 'OTHER', importance: 'high' },
  { date: '2027-01-01', name: '🇰🇷 WGBI 편입 효과 1~2분기 지연 반영 시점', category: 'OTHER', importance: 'medium' },
];

const FOMC_MEETINGS_2026 = [
  '2026-01-27', '2026-01-28',
  '2026-03-17', '2026-03-18',
  '2026-04-28', '2026-04-29',
  '2026-06-16', '2026-06-17',
  '2026-07-28', '2026-07-29',
  '2026-09-15', '2026-09-16',
  '2026-10-27', '2026-10-28',
  '2026-12-15', '2026-12-16',
];

function daysBetween(a: string, b: string): number {
  return Math.round((new Date(a).getTime() - new Date(b).getTime()) / 86400000);
}

/** FRED release dates API로 최근/향후 N개 이벤트 가져오기. */
async function fetchFredReleaseDates(
  apiKey: string,
  releaseId: number,
  limit = 3,
): Promise<string[]> {
  try {
    const today = new Date().toISOString().split('T')[0];
    const { data } = await axios.get(
      `https://api.stlouisfed.org/fred/release/dates?release_id=${releaseId}&api_key=${apiKey}&file_type=json&sort_order=desc&include_release_dates_with_no_data=true&realtime_start=${today}&limit=${limit}`,
      { timeout: 10000 },
    );
    const dates = (data.release_dates || []).map((r: any) => r.date as string);
    if (dates.length > 0) return dates;
    // realtime_start 필터에서 하나도 안 나오면 과거 최근 3개
    const { data: past } = await axios.get(
      `https://api.stlouisfed.org/fred/release/dates?release_id=${releaseId}&api_key=${apiKey}&file_type=json&sort_order=desc&limit=${limit}`,
      { timeout: 10000 },
    );
    return (past.release_dates || []).map((r: any) => r.date as string);
  } catch {
    return [];
  }
}

export async function fetchEconomicCalendar(apiKey: string): Promise<CalendarEvent[]> {
  const today = new Date().toISOString().split('T')[0];
  const events: CalendarEvent[] = [];
  const startedAt = Date.now();

  // 1. FRED release dates
  const settled = await Promise.allSettled(
    FRED_RELEASES.map((r) => fetchFredReleaseDates(apiKey, r.id, 3)),
  );
  FRED_RELEASES.forEach((r, i) => {
    const s = settled[i];
    if (s.status === 'fulfilled') {
      for (const d of s.value) {
        events.push({
          date: d,
          name: r.name,
          category: r.category,
          releaseId: r.id,
          daysUntil: daysBetween(d, today),
          importance: r.importance,
        });
      }
    }
  });

  // 2. FOMC 회의
  for (const d of FOMC_MEETINGS_2026) {
    events.push({
      date: d,
      name: 'FOMC Meeting',
      category: 'FOMC',
      daysUntil: daysBetween(d, today),
      importance: 'high',
    });
  }

  // 3. 정적 정치·정책 이벤트 (미 중간선거 등 — 장기 카운트다운이라 ±100일 범위)
  for (const e of STATIC_POLITICAL_EVENTS) {
    const daysU = daysBetween(e.date, today);
    if (daysU >= -30 && daysU <= 400) {
      events.push({
        date: e.date,
        name: e.name,
        category: e.category,
        daysUntil: daysU,
        importance: e.importance,
      });
    }
  }

  // 정렬 + 향후 30일 이내 + 과거 7일 이내 + 정치 이벤트는 장기도 포함
  events.sort((a, b) => a.date.localeCompare(b.date));
  const filtered = events.filter((e) => {
    if (e.category === 'OTHER') return e.daysUntil >= -30 && e.daysUntil <= 400;
    return e.daysUntil >= -7 && e.daysUntil <= 30;
  });
  log.info({
    durationMs: Date.now() - startedAt,
    totalEvents: filtered.length,
    categories: filtered.reduce<Record<string, number>>((acc, event) => {
      acc[event.category] = (acc[event.category] || 0) + 1;
      return acc;
    }, {}),
  }, 'economic calendar collected');
  if (filtered.length > 0) {
    await writeSourceCache(CALENDAR_CACHE_KEY, filtered, { asOf: today });
    return filtered;
  }

  const cached = await readSourceCacheWithin<CalendarEvent[]>(CALENDAR_CACHE_KEY, CALENDAR_STALE_MS);
  if (cached) {
    log.warn({
      ageMs: cached.ageMs,
      updatedAt: cached.updatedAt,
    }, 'economic calendar empty, serving cached value');
    return cached.value;
  }
  return filtered;
}

/** 임박 이벤트(D-3 이내) 요약 */
export function imminentEvents(events: CalendarEvent[]): CalendarEvent[] {
  return events.filter((e) => e.daysUntil >= 0 && e.daysUntil <= 3).sort((a, b) => a.daysUntil - b.daysUntil);
}
