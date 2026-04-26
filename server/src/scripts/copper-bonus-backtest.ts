/**
 * COPPER STRONG_BUY 3/3 조건부 bonus 검증 백테스트.
 *
 * 현 반영된 BASE_ALLOCATIONS 기준으로:
 *   V0 : 현 상태 (bonus 없음)
 *   V3 : copper 3/3 충족 시 +3%p (donors: nasdaq, cash 비례 차감)
 *   V5 : copper 3/3 충족 시 +5%p
 *   V7 : +7%p
 *
 * 각 variant 를 1/3/5/10Y 기간에 대해 백테스트 → 비교.
 */

import { readHistory } from '../state/history-store';
import { classifyRegime } from '../engines/regime';
import { computeSignals } from '../engines/signals';
import { computeAllocation } from '../engines/allocation';
import { DEFAULT_PROFILE } from '../state/cache';
import { MarketDataPoint, DerivedIndicator } from '../types/indicators';

function latestBefore(arr: Array<{ date: string; value: number }>, date: string): number | null {
  for (let i = arr.length - 1; i >= 0; i--) if (arr[i].date <= date) return arr[i].value;
  return null;
}

interface Result { return_pct: number; max_drawdown: number; }

async function runBacktest(
  bonusPct: number,
  days: number,
  histories: Record<string, Array<{ date: string; value: number }>>,
  fredHistories: Record<string, Array<{ date: string; value: number }>>,
  dxyHist: Array<{ date: string; value: number }>,
  nasdaqFull: Array<{ date: string; value: number }>,
): Promise<{ r: Result; boostedDays: number }> {
  const baseSlice = nasdaqFull.slice(-Math.min(days, nasdaqFull.length));
  const fredKeys = Object.keys(fredHistories);
  let pv = 100;
  let peak = 100;
  let maxDD = 0;
  let boostedDays = 0;

  for (let i = 1; i < baseSlice.length; i++) {
    const date = baseSlice[i].date;
    const prevDate = baseSlice[i - 1].date;

    const raw: Record<string, MarketDataPoint> = {};
    for (const k of fredKeys) {
      const v = latestBefore(fredHistories[k], prevDate);
      if (v !== null) raw[k] = { code: k, value: v, date: prevDate, source: 'FRED' };
    }
    const dx = latestBefore(dxyHist, prevDate);
    if (dx !== null) raw.DXY = { code: 'DXY', value: dx, date: prevDate, source: 'YAHOO' };

    const derived: Record<string, DerivedIndicator> = {};
    if (raw.DGS10 && raw.T10YIE) {
      derived.REAL_YIELD = { name: 'real_yield', value: raw.DGS10.value - raw.T10YIE.value, date: prevDate, formula: '' };
    }
    const nqEl = nasdaqFull.filter((p) => p.date <= prevDate);
    if (nqEl.length >= 200) {
      const sma200 = nqEl.slice(-200).reduce((s, p) => s + p.value, 0) / 200;
      const cur = nqEl[nqEl.length - 1].value;
      derived.NASDAQ_DISPARITY = { name: 'nasdaq_disparity_200', value: ((cur - sma200) / sma200) * 100, date: prevDate, formula: '' };
      derived.NASDAQ_ABOVE_200DMA = { name: 'nasdaq_above_200dma', value: cur > sma200 ? 1 : 0, date: prevDate, formula: '' };
    }

    const regime = classifyRegime({ raw, derived, manualInputs: DEFAULT_PROFILE.manualInputs });
    const signals = computeSignals(raw, derived, regime, DEFAULT_PROFILE);
    const alloc = computeAllocation(regime.regime, regime.score, signals, derived, raw, 'long');
    const allocations = { ...alloc.allocations };

    // copper intense bonus 적용 (3/3 AND STRONG_BUY)
    const copperSig = signals.find((s) => s.asset === 'COPPER');
    const isIntense = copperSig?.signal === 'STRONG_BUY' && copperSig.conditionsMet === 3 && copperSig.conditionsTotal === 3;
    if (isIntense && bonusPct > 0) {
      const donors: Array<'nasdaq' | 'cash'> = ['nasdaq', 'cash'];
      const pool = donors.reduce((s, k) => s + (allocations[k] || 0), 0);
      const actual = Math.min(bonusPct, pool);
      if (actual > 0 && pool > 0) {
        for (const k of donors) {
          allocations[k] = Math.max(0, (allocations[k] || 0) - ((allocations[k] || 0) / pool) * actual);
        }
        allocations.copper = (allocations.copper || 0) + actual;
        boostedDays += 1;
      }
    }

    let dayR = 0;
    for (const [an, pct] of Object.entries(allocations)) {
      if (an === 'cash' || an === 'leverage') continue;
      const h = histories[an];
      if (!h) continue;
      const p1 = latestBefore(h, prevDate);
      const p2 = latestBefore(h, date);
      if (p1 && p2 && p1 > 0) dayR += (pct / 100) * ((p2 - p1) / p1);
    }
    pv *= 1 + dayR;
    if (pv > peak) peak = pv;
    const dd = ((pv - peak) / peak) * 100;
    if (dd < maxDD) maxDD = dd;
  }

  return {
    r: {
      return_pct: parseFloat((pv - 100).toFixed(2)),
      max_drawdown: parseFloat(maxDD.toFixed(2)),
    },
    boostedDays,
  };
}

async function main() {
  const assets: Record<string, { key: string; source: string }> = {
    nasdaq: { key: 'NASDAQ', source: 'yahoo' }, gold: { key: 'GOLD', source: 'yahoo' },
    silver: { key: 'SILVER', source: 'yahoo' }, copper: { key: 'COPPER', source: 'yahoo' },
    korea: { key: 'KOSPI', source: 'yahoo' }, emerging: { key: 'EWZ', source: 'yahoo' },
  };
  const histories: Record<string, Array<{ date: string; value: number }>> = {};
  for (const [n, v] of Object.entries(assets)) histories[n] = await readHistory(v.source, v.key);
  const fredKeys = ['DGS10', 'T10YIE', 'T10Y2Y', 'VIXCLS', 'BAMLH0A0HYM2', 'STLFSI4', 'ICSA', 'UNRATE'];
  const fredHistories: Record<string, Array<{ date: string; value: number }>> = {};
  for (const k of fredKeys) fredHistories[k] = await readHistory('fred', k);
  const dxyHist = await readHistory('yahoo', 'DXY');

  const periods = [
    { label: '1Y', days: 252 },
    { label: '3Y', days: 756 },
    { label: '5Y', days: 1260 },
    { label: '10Y', days: 2520 },
  ];
  const bonuses = [0, 3, 5, 7];

  const results: Record<number, Record<string, Result & { boostedDays: number }>> = {};
  for (const b of bonuses) {
    results[b] = {};
    for (const p of periods) {
      process.stderr.write(`... bonus=${b} ${p.label}\n`);
      const { r, boostedDays } = await runBacktest(b, p.days, histories, fredHistories, dxyHist, histories.nasdaq);
      results[b][p.label] = { ...r, boostedDays };
    }
  }

  // 출력
  console.log('\n=== COPPER INTENSE BONUS BACKTEST ===\n');
  console.log('variant    | 1Y 수익/낙폭      | 3Y                | 5Y                | 10Y                 | intense days (10Y)');
  console.log('-'.repeat(130));
  for (const b of bonuses) {
    const label = b === 0 ? 'V0 현재' : `V+${b}%p`;
    const cells = periods.map((p) => {
      const x = results[b][p.label];
      return `${x.return_pct.toFixed(2).padStart(7)}% / ${x.max_drawdown.toFixed(2).padStart(6)}%`;
    }).join(' | ');
    console.log(`${label.padEnd(10)} | ${cells} | ${results[b]['10Y'].boostedDays}일`);
  }

  // 가중 점수 재계산
  const W1Y = 0.25, W3Y = 0.10, W5Y = 0.30, W10Y = 0.35;
  console.log('\n=== WEIGHTED SCORE (1Y 0.25 + 3Y 0.10 + 5Y 0.30 + 10Y 0.35) ===');
  for (const b of bonuses) {
    const r = results[b];
    const score = r['1Y'].return_pct * W1Y + r['3Y'].return_pct * W3Y + r['5Y'].return_pct * W5Y + r['10Y'].return_pct * W10Y;
    const label = b === 0 ? 'V0 현재' : `V+${b}%p`;
    console.log(`  ${label.padEnd(10)}: ${score.toFixed(2)}`);
  }
}

main().catch((e) => { console.error(e); process.exit(1); });
