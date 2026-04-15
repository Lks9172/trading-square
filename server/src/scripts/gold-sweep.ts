/**
 * Gold weight 국면별 독립 sweep 백테스트.
 *
 * 각 국면마다 gold 비중을 0~35% (5%p 단위) 로 쓸면서 다른 5국면은 현 PRD 유지.
 * 차액은 nasdaq 에 흡수. 1Y 기준으로 각 국면별 optimal gold 발견.
 * 이후 모든 최적점을 합친 "composed optimal" 도 1/3/5Y 로 추가 검증.
 *
 * 실행: tsx src/scripts/gold-sweep.ts
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
  // Fix #5: 레짐 8종 확장. gold-sweep 은 기존 6종 대상이므로 두 신규 레짐은 RECESSION_RISK 수준 방어 비중 사용.
  STAGFLATION:    { cash: 25, nasdaq: 15, leverage: 0,  gold: 30, silver: 10, copper: 5,  korea: 8,  emerging: 7  },
  BOND_VIGILANTE: { cash: 30, nasdaq: 10, leverage: 0,  gold: 35, silver: 8,  copper: 3,  korea: 7,  emerging: 7  },
};

const GOLD_CANDIDATES = [0, 5, 10, 15, 20, 25, 30, 35];
// 기존 gold sweep 은 6종 한정. STAGFLATION/BOND_VIGILANTE 는 Fix #5 에서 추가된 방어 국면이라 스윕 대상 아님.
const REGIMES: Regime[] = ['RISK_ON', 'NEUTRAL', 'CAUTION', 'CORRECTION', 'PANIC_BUT_OK', 'RECESSION_RISK'];

/** 특정 국면의 gold 만 바꾸고 차액은 nasdaq 에 흡수한 변형 템플릿을 만든다. */
function buildVariant(base: Template, regime: Regime, newGold: number): Template {
  const copy: Template = JSON.parse(JSON.stringify(base));
  const row = copy[regime];
  const delta = newGold - row.gold;
  row.gold = newGold;
  row.nasdaq = Math.max(0, row.nasdaq - delta); // 차액을 nasdaq 에서 흡수
  // 합이 여전히 100 을 유지하도록 (nasdaq 이 음수가 될 만큼 gold 가 크면 다른 자산에서 균등 차감)
  let sum = Object.values(row).reduce((s, v) => s + v, 0);
  if (sum !== 100) {
    const diff = 100 - sum;
    row.nasdaq = Math.max(0, row.nasdaq + diff); // 최종 보정
  }
  return copy;
}

function latestBefore(arr: Array<{ date: string; value: number }>, date: string): number | null {
  for (let i = arr.length - 1; i >= 0; i--) {
    if (arr[i].date <= date) return arr[i].value;
  }
  return null;
}

interface BacktestResult {
  return_pct: number;
  max_drawdown: number;
}

async function runBacktest(
  variant: Template,
  days: number,
  histories: Record<string, Array<{ date: string; value: number }>>,
  fredHistories: Record<string, Array<{ date: string; value: number }>>,
  dxyHist: Array<{ date: string; value: number }>,
  nasdaqFull: Array<{ date: string; value: number }>,
): Promise<BacktestResult> {
  const baseSlice = nasdaqFull.slice(-Math.min(days, nasdaqFull.length));
  const fredKeys = Object.keys(fredHistories);
  let pv = 100;
  let peak = 100;
  let maxDD = 0;

  for (let i = 1; i < baseSlice.length; i++) {
    const date = baseSlice[i].date;
    const prevDate = baseSlice[i - 1].date;
    const raw: Record<string, MarketDataPoint> = {};
    for (const key of fredKeys) {
      const v = latestBefore(fredHistories[key], prevDate);
      if (v !== null) raw[key] = { code: key, value: v, date: prevDate, source: 'FRED' };
    }
    const dxyVal = latestBefore(dxyHist, prevDate);
    if (dxyVal !== null) raw.DXY = { code: 'DXY', value: dxyVal, date: prevDate, source: 'YAHOO' };

    const derived: Record<string, DerivedIndicator> = {};
    if (raw.DGS10 && raw.T10YIE) {
      derived.REAL_YIELD = { name: 'real_yield', value: raw.DGS10.value - raw.T10YIE.value, date: prevDate, formula: '' };
    }
    const nasdaqEligible = nasdaqFull.filter((p) => p.date <= prevDate);
    if (nasdaqEligible.length >= 200) {
      const sma200 = nasdaqEligible.slice(-200).reduce((s, p) => s + p.value, 0) / 200;
      const cur = nasdaqEligible[nasdaqEligible.length - 1].value;
      derived.NASDAQ_DISPARITY = { name: 'nasdaq_disparity_200', value: ((cur - sma200) / sma200) * 100, date: prevDate, formula: '' };
      derived.NASDAQ_ABOVE_200DMA = { name: 'nasdaq_above_200dma', value: cur > sma200 ? 1 : 0, date: prevDate, formula: '' };
    }

    const regime = classifyRegime({ raw, derived, manualInputs: DEFAULT_PROFILE.manualInputs });
    const signals = computeSignals(raw, derived, regime, DEFAULT_PROFILE);
    const alloc = computeAllocation(regime.regime, regime.score, signals, derived, raw, 'long', variant);

    let dayR = 0;
    for (const [an, pct] of Object.entries(alloc.allocations)) {
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
  return { return_pct: parseFloat((pv - 100).toFixed(2)), max_drawdown: parseFloat(maxDD.toFixed(2)) };
}

async function main() {
  const assets: Record<string, { key: string; source: string }> = {
    nasdaq: { key: 'NASDAQ', source: 'yahoo' },
    gold: { key: 'GOLD', source: 'yahoo' },
    silver: { key: 'SILVER', source: 'yahoo' },
    copper: { key: 'COPPER', source: 'yahoo' },
    korea: { key: 'KOSPI', source: 'yahoo' },
    emerging: { key: 'EWZ', source: 'yahoo' },
  };
  const histories: Record<string, Array<{ date: string; value: number }>> = {};
  for (const [n, { key, source }] of Object.entries(assets)) histories[n] = await readHistory(source, key);

  const fredKeys = ['DGS10', 'T10YIE', 'T10Y2Y', 'VIXCLS', 'BAMLH0A0HYM2', 'STLFSI4', 'ICSA', 'UNRATE'];
  const fredHistories: Record<string, Array<{ date: string; value: number }>> = {};
  for (const k of fredKeys) fredHistories[k] = await readHistory('fred', k);
  const dxyHist = await readHistory('yahoo', 'DXY');

  // 현 PRD 베이스라인
  console.log('\n=== BASELINE (current PRD) ===');
  const base1y = await runBacktest(CURRENT_PRD, 252, histories, fredHistories, dxyHist, histories.nasdaq);
  const base3y = await runBacktest(CURRENT_PRD, 756, histories, fredHistories, dxyHist, histories.nasdaq);
  const base5y = await runBacktest(CURRENT_PRD, 1260, histories, fredHistories, dxyHist, histories.nasdaq);
  console.log(`  1Y: ${base1y.return_pct}% (DD ${base1y.max_drawdown}%)`);
  console.log(`  3Y: ${base3y.return_pct}% (DD ${base3y.max_drawdown}%)`);
  console.log(`  5Y: ${base5y.return_pct}% (DD ${base5y.max_drawdown}%)`);

  // 국면별 독립 gold sweep (1Y 우선)
  console.log('\n=== REGIME × GOLD SWEEP (1Y 수익률% / 최대낙폭%) ===');
  const header = 'gold:      ' + GOLD_CANDIDATES.map((g) => String(g).padStart(7)).join('');
  console.log(header);
  console.log('-'.repeat(header.length));

  const optimal: Record<Regime, number> = {} as any;
  for (const regime of REGIMES) {
    const row: number[] = [];
    const dds: number[] = [];
    for (const g of GOLD_CANDIDATES) {
      const v = buildVariant(CURRENT_PRD, regime, g);
      const r = await runBacktest(v, 252, histories, fredHistories, dxyHist, histories.nasdaq);
      row.push(r.return_pct);
      dds.push(r.max_drawdown);
    }
    // best = 1Y 수익률 - |DD|*0.2
    let bestIdx = 0;
    let bestScore = -Infinity;
    for (let i = 0; i < row.length; i++) {
      const s = row[i] - Math.abs(dds[i]) * 0.2;
      if (s > bestScore) { bestScore = s; bestIdx = i; }
    }
    optimal[regime] = GOLD_CANDIDATES[bestIdx];
    const cells = row.map((r, i) => `${r.toFixed(1).padStart(4)}${i === bestIdx ? '*' : ' '} `).join('');
    const curGold = CURRENT_PRD[regime].gold;
    const curIdx = GOLD_CANDIDATES.indexOf(curGold);
    console.log(`${regime.padEnd(16)} ${cells}`);
    console.log(`${' '.repeat(16)} ` + dds.map((d) => d.toFixed(1).padStart(5) + '  ').join(''));
    console.log(`  → 현 PRD gold=${curGold} (1Y ${row[curIdx]?.toFixed(1) ?? '?'}%) / optimal gold=${optimal[regime]} (1Y ${row[bestIdx].toFixed(1)}%)`);
  }

  // 모든 최적점을 합친 composed variant 검증
  let composed: Template = JSON.parse(JSON.stringify(CURRENT_PRD));
  for (const r of REGIMES) {
    composed = buildVariant(composed, r, optimal[r]);
  }
  console.log('\n=== COMPOSED OPTIMAL (국면별 1Y 최적 gold 합친 템플릿) ===');
  console.log(JSON.stringify(composed, null, 2));

  const c1y = await runBacktest(composed, 252, histories, fredHistories, dxyHist, histories.nasdaq);
  const c3y = await runBacktest(composed, 756, histories, fredHistories, dxyHist, histories.nasdaq);
  const c5y = await runBacktest(composed, 1260, histories, fredHistories, dxyHist, histories.nasdaq);
  console.log(`  1Y: ${c1y.return_pct}% (DD ${c1y.max_drawdown}%) Δ vs PRD = ${(c1y.return_pct - base1y.return_pct).toFixed(2)}%p`);
  console.log(`  3Y: ${c3y.return_pct}% (DD ${c3y.max_drawdown}%) Δ vs PRD = ${(c3y.return_pct - base3y.return_pct).toFixed(2)}%p`);
  console.log(`  5Y: ${c5y.return_pct}% (DD ${c5y.max_drawdown}%) Δ vs PRD = ${(c5y.return_pct - base5y.return_pct).toFixed(2)}%p`);
}

main().catch((e) => { console.error(e); process.exit(1); });
