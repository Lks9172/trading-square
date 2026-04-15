import axios from 'axios';
import XLSX from 'xlsx';
import { readHistory } from '../state/history-store';
import { fetchFredHistory } from './fred';

interface AutoManualInputs {
  policyDirection: number;
  geoRisk: number;
  cbBuying: boolean;
  ismPmi: number | null;
}

async function fetchGPR(): Promise<number> {
  const url = 'https://www.matteoiacoviello.com/gpr_files/data_gpr_daily_recent.xls';
  const { data } = await axios.get(url, { responseType: 'arraybuffer', timeout: 30000 });
  const wb = XLSX.read(data, { type: 'buffer' });
  const ws = wb.Sheets[wb.SheetNames[0]];
  const rows: any[][] = XLSX.utils.sheet_to_json(ws, { header: 1 });

  const gprColIdx = 2;
  const lastRows = rows.slice(-30).filter((r) => typeof r[gprColIdx] === 'number');
  if (!lastRows.length) return 100;

  const avg = lastRows.reduce((sum, r) => sum + r[gprColIdx], 0) / lastRows.length;
  return avg;
}

function gprToGeoRisk(gpr: number): number {
  if (gpr < 80) return 0;
  if (gpr < 100) return 1;
  if (gpr < 130) return 2;
  if (gpr < 200) return 3;
  return 4;
}

async function computePolicyDirection(apiKey: string): Promise<number> {
  const [effrHistory, t10y2yHistory, icsaHistory] = await Promise.all([
    fetchFredHistory('EFFR', apiKey, 60),
    fetchFredHistory('T10Y2Y', apiKey, 10),
    fetchFredHistory('ICSA', apiKey, 10),
  ]);

  if (effrHistory.length < 30) return 0;

  const recent = effrHistory.slice(0, 10);
  const older = effrHistory.slice(20, 30);
  const recentAvg = recent.reduce((s, p) => s + p.value, 0) / recent.length;
  const olderAvg = older.reduce((s, p) => s + p.value, 0) / older.length;
  const effrDelta = recentAvg - olderAvg;

  const yieldCurve = t10y2yHistory.length > 0 ? t10y2yHistory[0].value : 0;

  // ICSA(신규 실업수당) 추세: 증가 = 고용 악화 = 정책 완화 압력
  let icsaPressure = 0;
  if (icsaHistory.length >= 8) {
    const icsaRecent = icsaHistory.slice(0, 4).reduce((s, p) => s + p.value, 0) / 4;
    const icsaOlder = icsaHistory.slice(4, 8).reduce((s, p) => s + p.value, 0) / 4;
    const delta = (icsaRecent - icsaOlder) / icsaOlder;
    if (delta > 0.05) icsaPressure = 1;
    else if (delta > 0.02) icsaPressure = 0.5;
    else if (delta < -0.02) icsaPressure = -0.5;
  }

  let score = 0;
  if (effrDelta < -0.3) score = 2;
  else if (effrDelta < -0.1) score = 1;
  else if (effrDelta > 0.3 && yieldCurve < -0.5) score = -2;
  else if (effrDelta > 0.1) score = -1;

  score += icsaPressure;
  return Math.max(-2, Math.min(2, Math.round(score)));
}

async function detectCBBuying(): Promise<boolean> {
  const goldHistory = await readHistory('yahoo', 'GOLD');
  const dxyHistory = await readHistory('yahoo', 'DXY');

  if (goldHistory.length < 60 || dxyHistory.length < 60) return true;

  const goldRecent = goldHistory.slice(-20);
  const goldOlder = goldHistory.slice(-60, -40);
  const goldRecentAvg = goldRecent.reduce((s, p) => s + p.value, 0) / goldRecent.length;
  const goldOlderAvg = goldOlder.reduce((s, p) => s + p.value, 0) / goldOlder.length;
  const goldUp = goldRecentAvg > goldOlderAvg * 1.02;

  const dxyRecent = dxyHistory.slice(-20);
  const dxyOlder = dxyHistory.slice(-60, -40);
  const dxyRecentAvg = dxyRecent.reduce((s, p) => s + p.value, 0) / dxyRecent.length;
  const dxyOlderAvg = dxyOlder.reduce((s, p) => s + p.value, 0) / dxyOlder.length;
  const dxyStrongOrFlat = dxyRecentAvg >= dxyOlderAvg * 0.98;

  return goldUp && dxyStrongOrFlat;
}

/**
 * investing.com economic calendar 의 ISM Manufacturing PMI 페이지에서 최신 공식 Actual 값.
 * URL 고정(event-id=173), 월 1회 갱신이라 레이아웃 변경 리스크 낮음.
 * 실패 시 null → 호출부에서 FRED proxy fallback.
 *
 * 파싱 타겟:
 *   <tr><td>Apr 01, 2026 (Mar)</td><td>14:00</td><td>52.7</td>...
 */
async function fetchISMFromInvesting(): Promise<{ value: number; asOf: string } | null> {
  const UA =
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
  try {
    const { data: html } = await axios.get<string>(
      'https://www.investing.com/economic-calendar/ism-manufacturing-pmi-173',
      {
        headers: {
          'User-Agent': UA,
          Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
          Referer: 'https://www.investing.com/',
        },
        timeout: 15000,
      },
    );
    // 첫 번째 매치 = 최신 발표치
    const m = html.match(
      /<tr[^>]*>\s*<td[^>]*>([A-Z][a-z]{2}\s+\d{1,2},\s+\d{4}\s*\([^)]+\))<\/td>\s*<td[^>]*>[^<]*<\/td>\s*<td[^>]*>([0-9]+\.[0-9]+)<\/td>/,
    );
    if (!m) return null;
    const value = parseFloat(m[2]);
    if (!Number.isFinite(value) || value < 20 || value > 80) return null;
    return { value, asOf: m[1] };
  } catch {
    return null;
  }
}

/**
 * ISM proxy: FRED 기반 복합 지표 (공식 소스 실패 시 fallback).
 *
 * INDPRO(산업생산) MoM + ICSA(실업수당) 추세 + PAYEMS(비농업고용) MoM 을 조합해
 * PMI 50 을 중심으로 ±20 범위의 proxy 값을 산출.
 */
async function fetchISMProxy(apiKey: string): Promise<number | null> {
  try {
    const [indproHist, icsaHist, payemsHist] = await Promise.all([
      fetchFredHistory('INDPRO', apiKey, 6),
      fetchFredHistory('ICSA', apiKey, 10),
      fetchFredHistory('PAYEMS', apiKey, 6),
    ]);

    if (indproHist.length < 3) return null;

    const indCurrent = indproHist[0].value;
    const indPrev = indproHist[1].value;
    const indPrev2 = indproHist[2].value;
    const indMom = ((indCurrent - indPrev) / indPrev) * 100;
    const indExpanding = indCurrent > indPrev && indPrev > indPrev2;
    const indContracting = indCurrent < indPrev && indPrev < indPrev2;

    // ICSA 4주 평균 추세 (증가 = 고용악화 = PMI 하방)
    let icsaAdj = 0;
    if (icsaHist.length >= 8) {
      const r = icsaHist.slice(0, 4).reduce((s, p) => s + p.value, 0) / 4;
      const o = icsaHist.slice(4, 8).reduce((s, p) => s + p.value, 0) / 4;
      const delta = (r - o) / o;
      if (delta < -0.05) icsaAdj = 2;
      else if (delta < -0.02) icsaAdj = 1;
      else if (delta > 0.05) icsaAdj = -2;
      else if (delta > 0.02) icsaAdj = -1;
    }

    // PAYEMS MoM (양수 = 고용 확장)
    let payemsAdj = 0;
    if (payemsHist.length >= 2) {
      const mom = ((payemsHist[0].value - payemsHist[1].value) / payemsHist[1].value) * 100;
      if (mom > 0.2) payemsAdj = 1;
      else if (mom > 0.05) payemsAdj = 0.5;
      else if (mom < -0.1) payemsAdj = -1;
    }

    let ismProxy = 50 + indMom * 5 + icsaAdj + payemsAdj;
    if (indExpanding) ismProxy = Math.max(ismProxy, 51);
    if (indContracting) ismProxy = Math.min(ismProxy, 49);
    return Math.max(30, Math.min(70, parseFloat(ismProxy.toFixed(1))));
  } catch {
    return null;
  }
}

export async function computeAutoManualInputs(apiKey: string): Promise<AutoManualInputs> {
  const [gpr, policyDirection, cbBuying, ismOfficial, ismProxy] = await Promise.allSettled([
    fetchGPR(),
    computePolicyDirection(apiKey),
    detectCBBuying(),
    fetchISMFromInvesting(),
    fetchISMProxy(apiKey),
  ]);

  // 공식 값 우선 → 실패 시 proxy fallback
  let ismPmi: number | null = null;
  if (ismOfficial.status === 'fulfilled' && ismOfficial.value) {
    ismPmi = ismOfficial.value.value;
  } else if (ismProxy.status === 'fulfilled') {
    ismPmi = ismProxy.value;
  }

  return {
    geoRisk: gpr.status === 'fulfilled' ? gprToGeoRisk(gpr.value) : 2,
    policyDirection: policyDirection.status === 'fulfilled' ? policyDirection.value : 0,
    cbBuying: cbBuying.status === 'fulfilled' ? cbBuying.value : true,
    ismPmi,
  };
}
