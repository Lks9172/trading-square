/**
 * 전체 포트폴리오 자산 비율 Monte Carlo sweep.
 *
 * 각 국면마다 영상/PRD 철학 제약 내에서 N개 랜덤 포트폴리오를 샘플링하고
 * (다른 5국면은 현 PRD 유지) backtest 1Y. 국면별 Top-5 → 3Y/5Y 교차검증.
 * 마지막으로 각 국면 Top-1 합친 composed optimal 도 검증.
 *
 * 실행: tsx src/scripts/portfolio-sweep.ts [samplesPerRegime]
 */

import { readHistory } from '../state/history-store';
import { classifyRegime } from '../engines/regime';
import { computeSignals } from '../engines/signals';
import { computeAllocation } from '../engines/allocation';
import { DEFAULT_PROFILE } from '../state/cache';
import { MarketDataPoint, DerivedIndicator, Regime } from '../types/indicators';

type Template = Record<Regime, Record<string, number>>;

const CURRENT_PRD: Template = {
  RISK_ON:        { cash: 10, nasdaq: 40, leverage: 0,  gold: 15, silver: 5, copper: 10, korea: 10, emerging: 10 },
  NEUTRAL:        { cash: 20, nasdaq: 35, leverage: 0,  gold: 20, silver: 5, copper: 5,  korea: 10, emerging: 5  },
  CAUTION:        { cash: 30, nasdaq: 25, leverage: 0,  gold: 25, silver: 0, copper: 5,  korea: 10, emerging: 5  },
  CORRECTION:     { cash: 25, nasdaq: 30, leverage: 0,  gold: 25, silver: 0, copper: 5,  korea: 10, emerging: 5  },
  PANIC_BUT_OK:   { cash: 15, nasdaq: 35, leverage: 10, gold: 20, silver: 5, copper: 5,  korea: 5,  emerging: 5  },
  RECESSION_RISK: { cash: 50, nasdaq: 15, leverage: 0,  gold: 25, silver: 0, copper: 0,  korea: 5,  emerging: 5  },
};

/** 각 국면의 자산별 [lo, hi] 제약. 영상/PRD 철학 최소 준수. */
const CONSTRAINTS: Record<Regime, Record<string, [number, number]>> = {
  RISK_ON:        { cash: [5, 20],  nasdaq: [25, 55], leverage: [0, 0],   gold: [5, 30],  silver: [0, 10], copper: [5, 15],  korea: [5, 20], emerging: [5, 20] },
  NEUTRAL:        { cash: [10, 30], nasdaq: [20, 50], leverage: [0, 0],   gold: [10, 30], silver: [0, 10], copper: [0, 10],  korea: [5, 20], emerging: [0, 15] },
  CAUTION:        { cash: [20, 45], nasdaq: [15, 40], leverage: [0, 0],   gold: [15, 35], silver: [0, 10], copper: [0, 10],  korea: [5, 20], emerging: [0, 15] },
  CORRECTION:     { cash: [15, 40], nasdaq: [20, 45], leverage: [0, 0],   gold: [15, 35], silver: [0, 10], copper: [0, 10],  korea: [5, 20], emerging: [0, 15] },
  PANIC_BUT_OK:   { cash: [10, 25], nasdaq: [25, 55], leverage: [5, 15],  gold: [10, 30], silver: [0, 10], copper: [0, 10],  korea: [0, 15], emerging: [0, 15] },
  RECESSION_RISK: { cash: [40, 65], nasdaq: [5, 25],  leverage: [0, 0],   gold: [15, 40], silver: [0, 5],  copper: [0, 5],   korea: [0, 15], emerging: [0, 15] },
};

const REGIMES: Regime[] = ['RISK_ON', 'NEUTRAL', 'CAUTION', 'CORRECTION', 'PANIC_BUT_OK', 'RECESSION_RISK'];
const ASSETS = ['cash', 'nasdaq', 'leverage', 'gold', 'silver', 'copper', 'korea', 'emerging'];

/** 제약 내에서 랜덤 균등 샘플 → 정규화하여 합 100. 제약 밖이면 재시도. */
function sampleRow(regime: Regime): Record<string, number> {
  const cs = CONSTRAINTS[regime];
  for (let attempt = 0; attempt < 500; attempt++) {
    const row: Record<string, number> = {};
    for (const a of ASSETS) {
      const [lo, hi] = cs[a];
      row[a] = lo + Math.random() * (hi - lo);
    }
    const sum = Object.values(row).reduce((s, v) => s + v, 0);
    if (sum === 0) continue;
    const scaled: Record<string, number> = {};
    let valid = true;
    for (const a of ASSETS) {
      scaled[a] = Math.round((row[a] / sum) * 100);
      const [lo, hi] = cs[a];
      if (scaled[a] < lo - 2 || scaled[a] > hi + 2) { valid = false; break; } // 정규화 후 허용 오차 ±2
    }
    if (!valid) continue;
    // 반올림 오차 보정
    let s2 = Object.values(scaled).reduce((s, v) => s + v, 0);
    if (s2 !== 100) {
      scaled.nasdaq += 100 - s2;
      if (scaled.nasdaq < cs.nasdaq[0] || scaled.nasdaq > cs.nasdaq[1]) continue;
    }
    return scaled;
  }
  // fallback: current PRD row
  return { ...CURRENT_PRD[regime] };
}

function latestBefore(arr: Array<{ date: string; value: number }>, date: string): number | null {
  for (let i = arr.length - 1; i >= 0; i--) if (arr[i].date <= date) return arr[i].value;
  return null;
}

interface Result { return_pct: number; max_drawdown: number; }

async function backtest(
  variant: Template,
  days: number,
  histories: Record<string, Array<{ date: string; value: number }>>,
  fredHistories: Record<string, Array<{ date: string; value: number }>>,
  dxyHist: Array<{ date: string; value: number }>,
  nasdaqFull: Array<{ date: string; value: number }>,
): Promise<Result> {
  const baseSlice = nasdaqFull.slice(-Math.min(days, nasdaqFull.length));
  const fredKeys = Object.keys(fredHistories);
  let pv = 100; let peak = 100; let maxDD = 0;
  for (let i = 1; i < baseSlice.length; i++) {
    const date = baseSlice[i].date;
    const prevDate = baseSlice[i - 1].date;
    const raw: Record<string, MarketDataPoint> = {};
    for (const k of fredKeys) { const v = latestBefore(fredHistories[k], prevDate); if (v !== null) raw[k] = { code: k, value: v, date: prevDate, source: 'FRED' }; }
    const dxyVal = latestBefore(dxyHist, prevDate); if (dxyVal !== null) raw.DXY = { code: 'DXY', value: dxyVal, date: prevDate, source: 'YAHOO' };
    const derived: Record<string, DerivedIndicator> = {};
    if (raw.DGS10 && raw.T10YIE) derived.REAL_YIELD = { name: 'real_yield', value: raw.DGS10.value - raw.T10YIE.value, date: prevDate, formula: '' };
    const nqEl = nasdaqFull.filter((p) => p.date <= prevDate);
    if (nqEl.length >= 200) {
      const sma200 = nqEl.slice(-200).reduce((s, p) => s + p.value, 0) / 200;
      const cur = nqEl[nqEl.length - 1].value;
      derived.NASDAQ_DISPARITY = { name: 'nasdaq_disparity_200', value: ((cur - sma200) / sma200) * 100, date: prevDate, formula: '' };
      derived.NASDAQ_ABOVE_200DMA = { name: 'nasdaq_above_200dma', value: cur > sma200 ? 1 : 0, date: prevDate, formula: '' };
    }
    const regime = classifyRegime({ raw, derived, manualInputs: DEFAULT_PROFILE.manualInputs });
    const signals = computeSignals(raw, derived, regime, DEFAULT_PROFILE);
    const alloc = computeAllocation(regime.regime, regime.score, signals, derived, raw, 'long', variant);
    let dayR = 0;
    for (const [an, pct] of Object.entries(alloc.allocations)) {
      if (an === 'cash' || an === 'leverage') continue;
      const h = histories[an]; if (!h) continue;
      const p1 = latestBefore(h, prevDate); const p2 = latestBefore(h, date);
      if (p1 && p2 && p1 > 0) dayR += (pct / 100) * ((p2 - p1) / p1);
    }
    pv *= 1 + dayR; if (pv > peak) peak = pv;
    const dd = ((pv - peak) / peak) * 100; if (dd < maxDD) maxDD = dd;
  }
  return { return_pct: parseFloat((pv - 100).toFixed(2)), max_drawdown: parseFloat(maxDD.toFixed(2)) };
}

async function main() {
  const N = parseInt(process.argv[2] || '80', 10);

  const assets: Record<string, { key: string; source: string }> = {
    nasdaq: { key: 'NASDAQ', source: 'yahoo' }, gold: { key: 'GOLD', source: 'yahoo' },
    silver: { key: 'SILVER', source: 'yahoo' }, copper: { key: 'COPPER', source: 'yahoo' },
    korea: { key: 'KOSPI', source: 'yahoo' }, emerging: { key: 'EWZ', source: 'yahoo' },
  };
  const histories: Record<string, Array<{ date: string; value: number }>> = {};
  for (const [n, { key, source }] of Object.entries(assets)) histories[n] = await readHistory(source, key);
  const fredKeys = ['DGS10', 'T10YIE', 'T10Y2Y', 'VIXCLS', 'BAMLH0A0HYM2', 'STLFSI4', 'ICSA', 'UNRATE'];
  const fredHistories: Record<string, Array<{ date: string; value: number }>> = {};
  for (const k of fredKeys) fredHistories[k] = await readHistory('fred', k);
  const dxyHist = await readHistory('yahoo', 'DXY');

  const base1y = await backtest(CURRENT_PRD, 252, histories, fredHistories, dxyHist, histories.nasdaq);
  const base3y = await backtest(CURRENT_PRD, 756, histories, fredHistories, dxyHist, histories.nasdaq);
  const base5y = await backtest(CURRENT_PRD, 1260, histories, fredHistories, dxyHist, histories.nasdaq);
  const base10y = await backtest(CURRENT_PRD, 2520, histories, fredHistories, dxyHist, histories.nasdaq);
  console.log(`\n=== BASELINE (현 PRD) ===`);
  console.log(`  1Y:  ${base1y.return_pct}% (DD ${base1y.max_drawdown}%)`);
  console.log(`  3Y:  ${base3y.return_pct}% (DD ${base3y.max_drawdown}%)`);
  console.log(`  5Y:  ${base5y.return_pct}% (DD ${base5y.max_drawdown}%)`);
  console.log(`  10Y: ${base10y.return_pct}% (DD ${base10y.max_drawdown}%)`);

  // 종합 점수 가중치: 1Y 25% + 3Y 10% + 5Y 30% + 10Y 35%, DD penalty 0.15
  // 장기(5Y/10Y) 일관성 + 단기(1Y) 반응 모두 반영.
  const W1Y = 0.25, W3Y = 0.10, W5Y = 0.30, W10Y = 0.35, DD_PENALTY = 0.15;
  const scoreFn = (r1: Result, r3: Result, r5: Result, r10: Result) =>
    r1.return_pct * W1Y + r3.return_pct * W3Y + r5.return_pct * W5Y + r10.return_pct * W10Y
    - (Math.abs(r1.max_drawdown) + Math.abs(r10.max_drawdown)) / 2 * DD_PENALTY;

  const bestPerRegime: Record<Regime, { row: Record<string, number>; r1y: Result; r3y: Result; r5y: Result; r10y: Result; score: number }> = {} as any;
  for (const regime of REGIMES) {
    process.stderr.write(`\n[${regime}] sampling N=${N} (1Y+3Y+5Y+10Y) ...\n`);
    const samples: Array<{ row: Record<string, number>; r1y: Result; r3y: Result; r5y: Result; r10y: Result; score: number }> = [];
    for (let i = 0; i < N; i++) {
      const row = sampleRow(regime);
      const variant: Template = JSON.parse(JSON.stringify(CURRENT_PRD));
      variant[regime] = row as any;
      const r1 = await backtest(variant, 252, histories, fredHistories, dxyHist, histories.nasdaq);
      const r3 = await backtest(variant, 756, histories, fredHistories, dxyHist, histories.nasdaq);
      const r5 = await backtest(variant, 1260, histories, fredHistories, dxyHist, histories.nasdaq);
      const r10 = await backtest(variant, 2520, histories, fredHistories, dxyHist, histories.nasdaq);
      const sc = scoreFn(r1, r3, r5, r10);
      samples.push({ row, r1y: r1, r3y: r3, r5y: r5, r10y: r10, score: sc });
      if ((i + 1) % 5 === 0) process.stderr.write(`  ${i + 1}/${N}\n`);
    }
    samples.sort((a, b) => b.score - a.score);
    const top5 = samples.slice(0, 5);

    const baseScore = scoreFn(base1y, base3y, base5y, base10y);
    console.log(`\n=== ${regime} TOP 5 (score = 1Y*0.25 + 3Y*0.10 + 5Y*0.30 + 10Y*0.35 - DDpen) ===`);
    console.log(`현 PRD row: ${JSON.stringify(CURRENT_PRD[regime])}  score=${baseScore.toFixed(2)}`);
    for (let i = 0; i < top5.length; i++) {
      const t = top5[i];
      console.log(`#${i + 1} score=${t.score.toFixed(2)} 1Y=${t.r1y.return_pct.toFixed(1)}% 3Y=${t.r3y.return_pct.toFixed(1)}% 5Y=${t.r5y.return_pct.toFixed(1)}% 10Y=${t.r10y.return_pct.toFixed(1)}%/${t.r10y.max_drawdown.toFixed(1)} → ${JSON.stringify(t.row)}`);
    }
    bestPerRegime[regime] = top5[0];
  }

  // composed optimal: 각 국면 top1 합침
  const composed: Template = {} as any;
  for (const r of REGIMES) composed[r] = bestPerRegime[r].row as any;
  const c1y = await backtest(composed, 252, histories, fredHistories, dxyHist, histories.nasdaq);
  const c3y = await backtest(composed, 756, histories, fredHistories, dxyHist, histories.nasdaq);
  const c5y = await backtest(composed, 1260, histories, fredHistories, dxyHist, histories.nasdaq);
  const c10y = await backtest(composed, 2520, histories, fredHistories, dxyHist, histories.nasdaq);

  console.log(`\n=== COMPOSED OPTIMAL (국면별 top1 합침) ===`);
  console.log(JSON.stringify(composed, null, 2));
  console.log(`\n  1Y:  ${c1y.return_pct}% (dd ${c1y.max_drawdown}%) Δ vs PRD = ${(c1y.return_pct - base1y.return_pct).toFixed(2)}%p`);
  console.log(`  3Y:  ${c3y.return_pct}% (dd ${c3y.max_drawdown}%) Δ vs PRD = ${(c3y.return_pct - base3y.return_pct).toFixed(2)}%p`);
  console.log(`  5Y:  ${c5y.return_pct}% (dd ${c5y.max_drawdown}%) Δ vs PRD = ${(c5y.return_pct - base5y.return_pct).toFixed(2)}%p`);
  console.log(`  10Y: ${c10y.return_pct}% (dd ${c10y.max_drawdown}%) Δ vs PRD = ${(c10y.return_pct - base10y.return_pct).toFixed(2)}%p`);
}

main().catch((e) => { console.error(e); process.exit(1); });
