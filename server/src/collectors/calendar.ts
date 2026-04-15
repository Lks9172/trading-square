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

export interface CalendarEvent {
  date: string;            // YYYY-MM-DD (발표일)
  name: string;            // 이벤트명
  category: 'FOMC' | 'CPI' | 'NFP' | 'PCE' | 'GDP' | 'OTHER';
  releaseId?: number;
  daysUntil: number;       // 오늘 기준 D-일 (음수면 과거)
  importance: 'high' | 'medium';
}

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

  // 정렬 + 향후 7일 이내 + 과거 7일 이내만 반환
  events.sort((a, b) => a.date.localeCompare(b.date));
  return events.filter((e) => e.daysUntil >= -7 && e.daysUntil <= 30);
}

/** 임박 이벤트(D-3 이내) 요약 */
export function imminentEvents(events: CalendarEvent[]): CalendarEvent[] {
  return events.filter((e) => e.daysUntil >= 0 && e.daysUntil <= 3).sort((a, b) => a.daysUntil - b.daysUntil);
}
