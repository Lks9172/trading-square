import axios from 'axios';
import { MarketDataPoint } from '../types/indicators';

const FRED_BASE = 'https://api.stlouisfed.org/fred/series/observations';

const FRED_SERIES: Record<string, string> = {
  DGS10: 'DGS10',
  DGS30: 'DGS30',  // 30년 국채 금리 — 영상4 "채권 자경단" 감지용
  T10YIE: 'T10YIE',
  T10Y2Y: 'T10Y2Y',
  VIXCLS: 'VIXCLS',
  BAMLH0A0HYM2: 'BAMLH0A0HYM2',
  STLFSI4: 'STLFSI4',
  WALCL: 'WALCL',
  WRESBAL: 'WRESBAL',
  RRPONTSYD: 'RRPONTSYD',
  WTREGEN: 'WTREGEN',
  WRMFNS: 'WRMFNS',
  M2SL: 'M2SL',
  WM2NS: 'WM2NS', // 주간 M2 (Non-Seasonally Adjusted) — YoY 방향 전환 훅
  UNRATE: 'UNRATE',
  ICSA: 'ICSA',
  SOFR: 'SOFR',
  EFFR: 'EFFR',
  IORB: 'IORB', // Interest on Reserve Balances — SOFR/IORB 스프레드 계산용
  INDPRO: 'INDPRO',
  M3_EURO: 'MABMM301EZM657S',
  M3_JAPAN: 'MABMM301JPM657S',
};

export { FRED_SERIES };

function getDateRange(): { start: string; end: string } {
  const end = new Date();
  const start = new Date();
  start.setFullYear(start.getFullYear() - 3);
  return {
    start: start.toISOString().split('T')[0],
    end: end.toISOString().split('T')[0],
  };
}

async function fetchSeries(seriesId: string, apiKey: string, retries = 1): Promise<MarketDataPoint[]> {
  const { start, end } = getDateRange();
  const url = `${FRED_BASE}?series_id=${seriesId}&api_key=${apiKey}&file_type=json&observation_start=${start}&observation_end=${end}&sort_order=desc&limit=10`;

  let lastErr: unknown = null;
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const { data } = await axios.get(url, { timeout: 10000 });
      const observations = data.observations || [];
      return observations
        .filter((obs: any) => obs.value !== '.')
        .map((obs: any) => ({
          code: seriesId,
          value: parseFloat(obs.value),
          date: obs.date,
          source: 'FRED' as const,
        }));
    } catch (err) {
      lastErr = err;
      if (attempt < retries) {
        // FRED rate-limit 완화: 재시도 전 짧은 백오프 (200ms + jitter)
        await new Promise((r) => setTimeout(r, 200 + Math.random() * 300));
        continue;
      }
    }
  }
  const msg = (lastErr as { response?: { status?: number }; message?: string })?.response?.status || (lastErr as { message?: string })?.message;
  throw new Error(`FRED ${seriesId} fetch failed after ${retries + 1} attempts: ${msg}`);
}

/**
 * 22개 시리즈 병렬 호출 시 일부가 transient (네트워크/rate-limit) 로 drop 되면
 * raw 에서 해당 키가 누락된 채로 derived/signals 가 돌아 신호 왜곡. 대책:
 * 1) fetchSeries 에 1회 retry (위)
 * 2) allSettled 후 실패/빈 결과를 readHistory('fred', key) 마지막 값으로 fallback
 * 3) 실패 사유 명시 로깅 (silent drop 금지)
 */
export async function fetchAllFred(apiKey: string): Promise<Record<string, MarketDataPoint>> {
  const results: Record<string, MarketDataPoint> = {};
  const entries = Object.entries(FRED_SERIES);

  const settled = await Promise.allSettled(
    entries.map(([, seriesId]) => fetchSeries(seriesId, apiKey))
  );

  // fallback 용 — 순환 import 방지 위해 동적 import
  const { readHistory } = await import('../state/history-store');

  for (let i = 0; i < entries.length; i++) {
    const [key, seriesId] = entries[i];
    const result = settled[i];
    if (result.status === 'fulfilled' && result.value.length > 0) {
      results[key] = result.value[0];
      continue;
    }
    // 실패 또는 빈 결과 → history 마지막 값 fallback
    try {
      const hist = await readHistory('fred', key);
      if (hist.length > 0) {
        const last = hist[hist.length - 1];
        results[key] = {
          code: seriesId,
          value: last.value,
          date: last.date,
          source: 'FRED',
        };
        const reason = result.status === 'rejected' ? (result.reason as Error)?.message : 'empty observations';
        console.warn(`[fred] ${key} live fetch 실패 — history fallback ${last.date}=${last.value} (reason: ${reason})`);
      } else {
        const reason = result.status === 'rejected' ? (result.reason as Error)?.message : 'empty observations';
        console.warn(`[fred] ${key} live fetch 실패 + history 없음 — null 유지 (reason: ${reason})`);
      }
    } catch (histErr) {
      console.warn(`[fred] ${key} history fallback 실패:`, histErr);
    }
  }

  return results;
}

export async function fetchFredHistory(
  seriesId: string,
  apiKey: string,
  limit = 200
): Promise<MarketDataPoint[]> {
  const { start, end } = getDateRange();
  const url = `${FRED_BASE}?series_id=${seriesId}&api_key=${apiKey}&file_type=json&observation_start=${start}&observation_end=${end}&sort_order=desc&limit=${limit}`;

  const { data } = await axios.get(url);
  return (data.observations || [])
    .filter((obs: any) => obs.value !== '.')
    .map((obs: any) => ({
      code: seriesId,
      value: parseFloat(obs.value),
      date: obs.date,
      source: 'FRED' as const,
    }));
}

export async function fetchFredHistoryFrom(
  seriesId: string,
  apiKey: string,
  observationStart: string
): Promise<MarketDataPoint[]> {
  const end = new Date().toISOString().split('T')[0];
  const url = `${FRED_BASE}?series_id=${seriesId}&api_key=${apiKey}&file_type=json&observation_start=${observationStart}&observation_end=${end}&sort_order=asc&limit=100000`;

  const { data } = await axios.get(url);
  return (data.observations || [])
    .filter((obs: any) => obs.value !== '.')
    .map((obs: any) => ({
      code: seriesId,
      value: parseFloat(obs.value),
      date: obs.date,
      source: 'FRED' as const,
    }));
}
