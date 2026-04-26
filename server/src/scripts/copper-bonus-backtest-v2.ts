/**
 * COPPER STRONG_BUY 3/3 bonus 검증 백테스트 v2 (enhanced derived).
 *
 * v1 에서 백테스트 루프의 raw/derived 가 간소화되어 COPPER signal 3조건 중 2개
 * (COPPER_GOLD_RATIO, ISM_PROXY) 가 항상 null → 3/3 이 10년간 0일 발동.
 *
 * v2 는 백테스트 루프에서 다음 derived 를 직접 계산해 signal 정확도를 높임:
 *   - COPPER_GOLD_RATIO (copper/gold)
 *   - GOLD_SILVER_RATIO (gold/silver)
 *   - ISM_PROXY (INDPRO MoM + ICSA 4주추세 + PAYEMS MoM)
 *   - NASDAQ_ABOVE_200DMA / NASDAQ_DISPARITY (v1 에서도 있었음, 유지)
 *   - REAL_YIELD (v1 유지)
 *   - 추가 raw: GOLD, SILVER, COPPER 가격 (COPPER_GOLD_RATIO 계산용)
 *
 * 그 외는 여전히 누락(KRW_FX_LEVEL, DXY_TREND, KOSPI_* 등) — signal 정확도 불완전.
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

/** 최근 N 포인트 평균을 prevDate 기준으로. */
function avgLast(arr: Array<{ date: string; value: number }>, prevDate: string, n: number): number | null {
  const elig = arr.filter((p) => p.date <= prevDate).slice(-n);
  if (elig.length < n) return null;
  return elig.reduce((s, p) => s + p.value, 0) / n;
}

interface Result { return_pct: number; max_drawdown: number; }

async function runBacktest(
  bonusPct: number,
  days: number,
  histories: Record<string, Array<{ date: string; value: number }>>,
  fredHistories: Record<string, Array<{ date: string; value: number }>>,
  dxyHist: Array<{ date: string; value: number }>,
  payemsHist: Array<{ date: string; value: number }>,
  nasdaqFull: Array<{ date: string; value: number }>,
): Promise<{ r: Result; intenseDays: number; copperDays: Record<string, number> }> {
  const baseSlice = nasdaqFull.slice(-Math.min(days, nasdaqFull.length));
  const fredKeys = Object.keys(fredHistories);
  let pv = 100;
  let peak = 100;
  let maxDD = 0;
  let intenseDays = 0;
  const copperDays: Record<string, number> = {};

  for (let i = 1; i < baseSlice.length; i++) {
    const date = baseSlice[i].date;
    const prevDate = baseSlice[i - 1].date;

    const raw: Record<string, MarketDataPoint> = {};
    // FRED
    for (const k of fredKeys) {
      const v = latestBefore(fredHistories[k], prevDate);
      if (v !== null) raw[k] = { code: k, value: v, date: prevDate, source: 'FRED' };
    }
    // Yahoo prices
    const gold = latestBefore(histories.gold, prevDate);
    const silver = latestBefore(histories.silver, prevDate);
    const copper = latestBefore(histories.copper, prevDate);
    const dxyVal = latestBefore(dxyHist, prevDate);
    if (gold !== null) raw.GOLD = { code: 'GOLD', value: gold, date: prevDate, source: 'YAHOO' };
    if (silver !== null) raw.SILVER = { code: 'SILVER', value: silver, date: prevDate, source: 'YAHOO' };
    if (copper !== null) raw.COPPER = { code: 'COPPER', value: copper, date: prevDate, source: 'YAHOO' };
    if (dxyVal !== null) raw.DXY = { code: 'DXY', value: dxyVal, date: prevDate, source: 'YAHOO' };

    const derived: Record<string, DerivedIndicator> = {};

    // REAL_YIELD
    if (raw.DGS10 && raw.T10YIE) {
      derived.REAL_YIELD = { name: 'real_yield', value: raw.DGS10.value - raw.T10YIE.value, date: prevDate, formula: '' };
    }

    // NASDAQ_SMA200 / DISPARITY / ABOVE_200DMA
    const nqEl = nasdaqFull.filter((p) => p.date <= prevDate);
    if (nqEl.length >= 200) {
      const sma200 = nqEl.slice(-200).reduce((s, p) => s + p.value, 0) / 200;
      const cur = nqEl[nqEl.length - 1].value;
      derived.NASDAQ_DISPARITY = { name: 'nasdaq_disparity_200', value: ((cur - sma200) / sma200) * 100, date: prevDate, formula: '' };
      derived.NASDAQ_ABOVE_200DMA = { name: 'nasdaq_above_200dma', value: cur > sma200 ? 1 : 0, date: prevDate, formula: '' };
    }

    // COPPER_GOLD_RATIO
    if (gold !== null && copper !== null && gold > 0) {
      derived.COPPER_GOLD_RATIO = { name: 'copper_gold_ratio', value: copper / gold, date: prevDate, formula: '' };
    }

    // GOLD_SILVER_RATIO
    if (gold !== null && silver !== null && silver > 0) {
      derived.GOLD_SILVER_RATIO = { name: 'gold_silver_ratio', value: gold / silver, date: prevDate, formula: '' };
    }

    // ISM_PROXY — INDPRO MoM + ICSA 4주 추세 + PAYEMS MoM (auto-manual.ts 와 동일 로직 간이화)
    const indpro = fredHistories.INDPRO;
    const icsa = fredHistories.ICSA;
    if (indpro && icsa && indpro.length >= 3 && icsa.length >= 8) {
      const indEligible = indpro.filter((p) => p.date <= prevDate);
      const icsaEligible = icsa.filter((p) => p.date <= prevDate);
      const payemsEligible = payemsHist.filter((p) => p.date <= prevDate);

      if (indEligible.length >= 3 && icsaEligible.length >= 8) {
        const indCur = indEligible[indEligible.length - 1].value;
        const indPrev = indEligible[indEligible.length - 2].value;
        const indPrev2 = indEligible[indEligible.length - 3].value;
        const indMom = ((indCur - indPrev) / indPrev) * 100;
        const indExpanding = indCur > indPrev && indPrev > indPrev2;
        const indContracting = indCur < indPrev && indPrev < indPrev2;

        let icsaAdj = 0;
        const icsaRecent = icsaEligible.slice(-4).reduce((s, p) => s + p.value, 0) / 4;
        const icsaOlder = icsaEligible.slice(-8, -4).reduce((s, p) => s + p.value, 0) / 4;
        const delta = (icsaRecent - icsaOlder) / icsaOlder;
        if (delta < -0.05) icsaAdj = 2;
        else if (delta < -0.02) icsaAdj = 1;
        else if (delta > 0.05) icsaAdj = -2;
        else if (delta > 0.02) icsaAdj = -1;

        let payemsAdj = 0;
        if (payemsEligible.length >= 2) {
          const pCur = payemsEligible[payemsEligible.length - 1].value;
          const pPrev = payemsEligible[payemsEligible.length - 2].value;
          const mom = ((pCur - pPrev) / pPrev) * 100;
          if (mom > 0.2) payemsAdj = 1;
          else if (mom > 0.05) payemsAdj = 0.5;
          else if (mom < -0.1) payemsAdj = -1;
        }

        let ism = 50 + indMom * 5 + icsaAdj + payemsAdj;
        if (indExpanding) ism = Math.max(ism, 51);
        if (indContracting) ism = Math.min(ism, 49);
        ism = Math.max(30, Math.min(70, parseFloat(ism.toFixed(1))));
        derived.ISM_PROXY = { name: 'ism_proxy', value: ism, date: prevDate, formula: '' };
      }
    }

    const regime = classifyRegime({ raw, derived, manualInputs: DEFAULT_PROFILE.manualInputs });
    const signals = computeSignals(raw, derived, regime, DEFAULT_PROFILE);
    const alloc = computeAllocation(regime.regime, regime.score, signals, derived, raw, 'long');
    const allocations = { ...alloc.allocations };

    const copperSig = signals.find((s) => s.asset === 'COPPER');
    if (copperSig) {
      const sig = copperSig.signal;
      copperDays[sig] = (copperDays[sig] || 0) + 1;
    }
    const isIntense = copperSig?.signal === 'STRONG_BUY' && copperSig.conditionsMet === 3 && copperSig.conditionsTotal === 3;
    if (isIntense) intenseDays += 1;

    if (isIntense && bonusPct > 0) {
      const donors: Array<'nasdaq' | 'cash'> = ['nasdaq', 'cash'];
      const pool = donors.reduce((s, k) => s + (allocations[k] || 0), 0);
      const actual = Math.min(bonusPct, pool);
      if (actual > 0 && pool > 0) {
        for (const k of donors) {
          allocations[k] = Math.max(0, (allocations[k] || 0) - ((allocations[k] || 0) / pool) * actual);
        }
        allocations.copper = (allocations.copper || 0) + actual;
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
    r: { return_pct: parseFloat((pv - 100).toFixed(2)), max_drawdown: parseFloat(maxDD.toFixed(2)) },
    intenseDays,
    copperDays,
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
  const fredKeys = ['DGS10', 'T10YIE', 'T10Y2Y', 'VIXCLS', 'BAMLH0A0HYM2', 'STLFSI4', 'ICSA', 'UNRATE', 'INDPRO'];
  const fredHistories: Record<string, Array<{ date: string; value: number }>> = {};
  for (const k of fredKeys) fredHistories[k] = await readHistory('fred', k);
  const dxyHist = await readHistory('yahoo', 'DXY');
  const payemsHist = await readHistory('fred', 'PAYEMS');
  console.log(`PAYEMS history: ${payemsHist.length} pts, INDPRO: ${fredHistories.INDPRO.length} pts`);

  const periods = [
    { label: '1Y', days: 252 },
    { label: '3Y', days: 756 },
    { label: '5Y', days: 1260 },
    { label: '10Y', days: 2520 },
  ];
  const bonuses = [0, 3, 5, 7];

  const results: Record<number, Record<string, Result & { intenseDays: number; copperDays?: Record<string, number> }>> = {};
  for (const b of bonuses) {
    results[b] = {};
    for (const p of periods) {
      process.stderr.write(`... bonus=${b} ${p.label}\n`);
      const { r, intenseDays, copperDays } = await runBacktest(b, p.days, histories, fredHistories, dxyHist, payemsHist, histories.nasdaq);
      results[b][p.label] = { ...r, intenseDays, copperDays: b === 0 ? copperDays : undefined };
    }
  }

  // 신호 분포 확인 (bonus=0 기준)
  console.log('\n=== COPPER 신호 분포 (bonus=0 기준) ===');
  for (const p of periods) {
    const days = results[0][p.label].copperDays;
    console.log(`  ${p.label}: ${JSON.stringify(days)} / intense(3/3 STRONG_BUY) = ${results[0][p.label].intenseDays}일`);
  }

  console.log('\n=== 성과 (bonus × 기간) ===');
  console.log('variant    | 1Y                | 3Y                | 5Y                | 10Y                 | 10Y intense');
  console.log('-'.repeat(125));
  for (const b of bonuses) {
    const label = b === 0 ? 'V0 현재' : `V+${b}%p`;
    const cells = periods.map((p) => {
      const x = results[b][p.label];
      return `${x.return_pct.toFixed(2).padStart(7)}% / ${x.max_drawdown.toFixed(2).padStart(6)}%`;
    }).join(' | ');
    console.log(`${label.padEnd(10)} | ${cells} | ${results[b]['10Y'].intenseDays}일`);
  }

  const W1Y = 0.25, W3Y = 0.10, W5Y = 0.30, W10Y = 0.35;
  console.log('\n=== 가중 점수 (1Y 0.25 + 3Y 0.10 + 5Y 0.30 + 10Y 0.35) ===');
  for (const b of bonuses) {
    const r = results[b];
    const score = r['1Y'].return_pct * W1Y + r['3Y'].return_pct * W3Y + r['5Y'].return_pct * W5Y + r['10Y'].return_pct * W10Y;
    const label = b === 0 ? 'V0 현재' : `V+${b}%p`;
    console.log(`  ${label.padEnd(10)}: ${score.toFixed(2)}`);
  }
}

main().catch((e) => { console.error(e); process.exit(1); });
