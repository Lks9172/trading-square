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
 * ISM proxy: FRED 기반 복합 지표.
 *
 * 기존 TradingEconomics HTML 스크래핑은 페이지 레이아웃 변경에 취약해 제거.
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
  const [gpr, policyDirection, cbBuying, ismProxy] = await Promise.allSettled([
    fetchGPR(),
    computePolicyDirection(apiKey),
    detectCBBuying(),
    fetchISMProxy(apiKey),
  ]);

  return {
    geoRisk: gpr.status === 'fulfilled' ? gprToGeoRisk(gpr.value) : 2,
    policyDirection: policyDirection.status === 'fulfilled' ? policyDirection.value : 0,
    cbBuying: cbBuying.status === 'fulfilled' ? cbBuying.value : true,
    ismPmi: ismProxy.status === 'fulfilled' ? ismProxy.value : null,
  };
}
