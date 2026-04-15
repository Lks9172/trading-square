/**
 * Gold weight 변형 백테스트 비교 스크립트.
 *
 * 실행: tsx src/scripts/gold-backtest.ts
 *
 * 각 변형 BASE_ALLOCATIONS 로 backtest.ts 와 동일한 일일 리밸런싱 루프를 돌려
 * 1Y / 3Y / 5Y 수익률·최대낙폭을 뽑고, 1Y 가중치를 가장 크게 두고 종합 점수를 계산.
 */

import { readHistory } from '../state/history-store';
import { classifyRegime } from '../engines/regime';
import { computeSignals } from '../engines/signals';
import { computeAllocation } from '../engines/allocation';
import { DEFAULT_PROFILE } from '../state/cache';
import { MarketDataPoint, DerivedIndicator, Regime } from '../types/indicators';

type Template = Record<Regime, Record<string, number>>;

/**
 * 변형 정의. 각 국면의 비중 합은 100 을 유지 (gold 감소분은 nasdaq 에 우선 흡수).
 */
// Fix #5: 레짐 8종 확장 — gold-backtest 는 STAGFLATION/BOND_VIGILANTE 탐색 대상 아님.
// 모든 변형 동일 방어 비중으로 타입 만족.
const DEFENSIVE_STAG = { cash: 25, nasdaq: 15, leverage: 0,  gold: 30, silver: 10, copper: 5,  korea: 8,  emerging: 7 };
const DEFENSIVE_VIG  = { cash: 30, nasdaq: 10, leverage: 0,  gold: 35, silver: 8,  copper: 3,  korea: 7,  emerging: 7 };

const VARIANTS: Record<string, Template> = {
  V0_current_PRD: {
    RISK_ON:        { cash: 10, nasdaq: 40, leverage: 0,  gold: 15, silver: 5, copper: 10, korea: 10, emerging: 10 },
    NEUTRAL:        { cash: 20, nasdaq: 35, leverage: 0,  gold: 20, silver: 5, copper: 5,  korea: 10, emerging: 5  },
    CAUTION:        { cash: 30, nasdaq: 25, leverage: 0,  gold: 25, silver: 0, copper: 5,  korea: 10, emerging: 5  },
    CORRECTION:     { cash: 25, nasdaq: 30, leverage: 0,  gold: 25, silver: 0, copper: 5,  korea: 10, emerging: 5  },
    PANIC_BUT_OK:   { cash: 15, nasdaq: 35, leverage: 10, gold: 20, silver: 5, copper: 5,  korea: 5,  emerging: 5  },
    RECESSION_RISK: { cash: 50, nasdaq: 15, leverage: 0,  gold: 25, silver: 0, copper: 0,  korea: 5,  emerging: 5  },
    STAGFLATION:    { ...DEFENSIVE_STAG },
    BOND_VIGILANTE: { ...DEFENSIVE_VIG },
  },
  V1_lean_aggressive: {
    // gold: 5 / 12 / 20 / 22 / 15 / 28, 차액을 nasdaq + korea + emerging 에 분배
    RISK_ON:        { cash: 10, nasdaq: 47, leverage: 0,  gold: 5,  silver: 5, copper: 10, korea: 12, emerging: 11 },
    NEUTRAL:        { cash: 20, nasdaq: 40, leverage: 0,  gold: 12, silver: 5, copper: 5,  korea: 12, emerging: 6  },
    CAUTION:        { cash: 30, nasdaq: 28, leverage: 0,  gold: 20, silver: 0, copper: 5,  korea: 12, emerging: 5  },
    CORRECTION:     { cash: 25, nasdaq: 33, leverage: 0,  gold: 22, silver: 0, copper: 5,  korea: 10, emerging: 5  },
    PANIC_BUT_OK:   { cash: 15, nasdaq: 40, leverage: 10, gold: 15, silver: 5, copper: 5,  korea: 5,  emerging: 5  },
    RECESSION_RISK: { cash: 48, nasdaq: 14, leverage: 0,  gold: 28, silver: 0, copper: 0,  korea: 5,  emerging: 5  },
    STAGFLATION:    { ...DEFENSIVE_STAG },
    BOND_VIGILANTE: { ...DEFENSIVE_VIG },
  },
  V2_moderate: {
    // gold: 10 / 15 / 22 / 23 / 18 / 26
    RISK_ON:        { cash: 10, nasdaq: 43, leverage: 0,  gold: 10, silver: 5, copper: 10, korea: 11, emerging: 11 },
    NEUTRAL:        { cash: 20, nasdaq: 38, leverage: 0,  gold: 15, silver: 5, copper: 5,  korea: 12, emerging: 5  },
    CAUTION:        { cash: 30, nasdaq: 27, leverage: 0,  gold: 22, silver: 0, copper: 5,  korea: 11, emerging: 5  },
    CORRECTION:     { cash: 25, nasdaq: 31, leverage: 0,  gold: 23, silver: 0, copper: 5,  korea: 11, emerging: 5  },
    PANIC_BUT_OK:   { cash: 15, nasdaq: 37, leverage: 10, gold: 18, silver: 5, copper: 5,  korea: 5,  emerging: 5  },
    RECESSION_RISK: { cash: 49, nasdaq: 15, leverage: 0,  gold: 26, silver: 0, copper: 0,  korea: 5,  emerging: 5  },
    STAGFLATION:    { ...DEFENSIVE_STAG },
    BOND_VIGILANTE: { ...DEFENSIVE_VIG },
  },
  V3_very_aggressive: {
    // gold: 3 / 10 / 18 / 20 / 12 / 25 (RECESSION 만 현 수준 유지)
    RISK_ON:        { cash: 10, nasdaq: 49, leverage: 0,  gold: 3,  silver: 5, copper: 10, korea: 12, emerging: 11 },
    NEUTRAL:        { cash: 20, nasdaq: 42, leverage: 0,  gold: 10, silver: 5, copper: 5,  korea: 12, emerging: 6  },
    CAUTION:        { cash: 30, nasdaq: 30, leverage: 0,  gold: 18, silver: 0, copper: 5,  korea: 12, emerging: 5  },
    CORRECTION:     { cash: 25, nasdaq: 35, leverage: 0,  gold: 20, silver: 0, copper: 5,  korea: 10, emerging: 5  },
    PANIC_BUT_OK:   { cash: 15, nasdaq: 43, leverage: 10, gold: 12, silver: 5, copper: 5,  korea: 5,  emerging: 5  },
    RECESSION_RISK: { cash: 50, nasdaq: 15, leverage: 0,  gold: 25, silver: 0, copper: 0,  korea: 5,  emerging: 5  },
    STAGFLATION:    { ...DEFENSIVE_STAG },
    BOND_VIGILANTE: { ...DEFENSIVE_VIG },
  },
  V4_mild: {
    // gold: 12 / 18 / 23 / 24 / 18 / 25 (현재보다 살짝만 낮춤)
    RISK_ON:        { cash: 10, nasdaq: 42, leverage: 0,  gold: 12, silver: 5, copper: 10, korea: 11, emerging: 10 },
    NEUTRAL:        { cash: 20, nasdaq: 37, leverage: 0,  gold: 18, silver: 5, copper: 5,  korea: 10, emerging: 5  },
    CAUTION:        { cash: 30, nasdaq: 27, leverage: 0,  gold: 23, silver: 0, copper: 5,  korea: 10, emerging: 5  },
    CORRECTION:     { cash: 25, nasdaq: 31, leverage: 0,  gold: 24, silver: 0, copper: 5,  korea: 10, emerging: 5  },
    PANIC_BUT_OK:   { cash: 15, nasdaq: 37, leverage: 10, gold: 18, silver: 5, copper: 5,  korea: 5,  emerging: 5  },
    RECESSION_RISK: { cash: 50, nasdaq: 15, leverage: 0,  gold: 25, silver: 0, copper: 0,  korea: 5,  emerging: 5  },
    STAGFLATION:    { ...DEFENSIVE_STAG },
    BOND_VIGILANTE: { ...DEFENSIVE_VIG },
  },
};

function latestBefore(arr: Array<{ date: string; value: number }>, date: string): number | null {
  for (let i = arr.length - 1; i >= 0; i--) {
    if (arr[i].date <= date) return arr[i].value;
  }
  return null;
}

interface BacktestResult {
  return_pct: number;
  max_drawdown: number;
  final_value: number;
}

async function backtestVariant(
  variant: Template,
  days: number,
  histories: Record<string, Array<{ date: string; value: number }>>,
  fredHistories: Record<string, Array<{ date: string; value: number }>>,
  dxyHist: Array<{ date: string; value: number }>,
  nasdaqFull: Array<{ date: string; value: number }>,
): Promise<BacktestResult> {
  const baseSlice = nasdaqFull.slice(-Math.min(days, nasdaqFull.length));
  const fredKeys = Object.keys(fredHistories);

  let portfolioValue = 100;
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
    if (dxyVal !== null) raw['DXY'] = { code: 'DXY', value: dxyVal, date: prevDate, source: 'YAHOO' };

    const derived: Record<string, DerivedIndicator> = {};
    const dgs10 = raw.DGS10?.value;
    const t10yie = raw.T10YIE?.value;
    if (dgs10 !== undefined && t10yie !== undefined) {
      derived.REAL_YIELD = { name: 'real_yield', value: dgs10 - t10yie, date: prevDate, formula: '' };
    }
    const nasdaqEligible = nasdaqFull.filter((p) => p.date <= prevDate);
    if (nasdaqEligible.length >= 200) {
      const sma200 = nasdaqEligible.slice(-200).reduce((s, p) => s + p.value, 0) / 200;
      const current = nasdaqEligible[nasdaqEligible.length - 1].value;
      derived.NASDAQ_DISPARITY = { name: 'nasdaq_disparity_200', value: ((current - sma200) / sma200) * 100, date: prevDate, formula: '' };
      derived.NASDAQ_ABOVE_200DMA = { name: 'nasdaq_above_200dma', value: current > sma200 ? 1 : 0, date: prevDate, formula: '' };
    }

    const regime = classifyRegime({ raw, derived, manualInputs: DEFAULT_PROFILE.manualInputs });
    const signals = computeSignals(raw, derived, regime, DEFAULT_PROFILE);
    const allocation = computeAllocation(regime.regime, regime.score, signals, derived, raw, 'long', variant);

    let dailyReturn = 0;
    for (const [assetName, pct] of Object.entries(allocation.allocations)) {
      if (assetName === 'cash' || assetName === 'leverage') continue;
      const hist = histories[assetName];
      if (!hist) continue;
      const prevPrice = latestBefore(hist, prevDate);
      const curPrice = latestBefore(hist, date);
      if (prevPrice && curPrice && prevPrice > 0) {
        dailyReturn += (pct / 100) * ((curPrice - prevPrice) / prevPrice);
      }
    }
    portfolioValue *= (1 + dailyReturn);
    if (portfolioValue > peak) peak = portfolioValue;
    const dd = ((portfolioValue - peak) / peak) * 100;
    if (dd < maxDD) maxDD = dd;
  }

  return {
    return_pct: parseFloat((portfolioValue - 100).toFixed(2)),
    max_drawdown: parseFloat(maxDD.toFixed(2)),
    final_value: parseFloat(portfolioValue.toFixed(2)),
  };
}

async function main() {
  // 자산 히스토리 로드
  const assets: Record<string, { key: string; source: string }> = {
    nasdaq: { key: 'NASDAQ', source: 'yahoo' },
    gold: { key: 'GOLD', source: 'yahoo' },
    silver: { key: 'SILVER', source: 'yahoo' },
    copper: { key: 'COPPER', source: 'yahoo' },
    korea: { key: 'KOSPI', source: 'yahoo' },
    emerging: { key: 'EWZ', source: 'yahoo' },
  };
  const histories: Record<string, Array<{ date: string; value: number }>> = {};
  for (const [name, { key, source }] of Object.entries(assets)) {
    histories[name] = await readHistory(source, key);
  }
  const fredKeys = ['DGS10', 'T10YIE', 'T10Y2Y', 'VIXCLS', 'BAMLH0A0HYM2', 'STLFSI4', 'ICSA', 'UNRATE'];
  const fredHistories: Record<string, Array<{ date: string; value: number }>> = {};
  for (const key of fredKeys) fredHistories[key] = await readHistory('fred', key);
  const dxyHist = await readHistory('yahoo', 'DXY');

  const periods = [
    { label: '1Y', days: 252, weight: 0.60 },
    { label: '3Y', days: 756, weight: 0.25 },
    { label: '5Y', days: 1260, weight: 0.15 },
  ];

  const results: Record<string, Record<string, BacktestResult>> = {};
  for (const [name, variant] of Object.entries(VARIANTS)) {
    results[name] = {};
    for (const p of periods) {
      process.stderr.write(`... ${name} ${p.label}\n`);
      results[name][p.label] = await backtestVariant(
        variant,
        p.days,
        histories,
        fredHistories,
        dxyHist,
        histories.nasdaq,
      );
    }
  }

  console.log('\n=== RESULTS (수익률% / 최대낙폭%) ===');
  console.log('variant              |     1Y        |     3Y        |     5Y        | score(1Y*0.6+3Y*0.25+5Y*0.15)');
  console.log('-'.repeat(120));

  const scores: Array<{ name: string; score: number; detail: string }> = [];
  for (const [name, r] of Object.entries(results)) {
    const scoreRaw = periods.reduce((s, p) => s + r[p.label].return_pct * p.weight, 0);
    // 최대낙폭 penalty (1Y 낙폭 기준, 절대값 * 0.2 차감)
    const ddPenalty = Math.abs(r['1Y'].max_drawdown) * 0.2;
    const score = parseFloat((scoreRaw - ddPenalty).toFixed(2));
    const detail = periods
      .map((p) => `${r[p.label].return_pct.toFixed(1).padStart(6)}/${r[p.label].max_drawdown.toFixed(1).padStart(5)}`)
      .join(' | ');
    console.log(`${name.padEnd(20)} | ${detail} | ${score.toFixed(2).padStart(7)}  (DDpenalty -${ddPenalty.toFixed(2)})`);
    scores.push({ name, score, detail });
  }
  scores.sort((a, b) => b.score - a.score);

  console.log('\n=== 순위 (score 내림차순) ===');
  scores.forEach((s, i) => console.log(`${i + 1}. ${s.name}  score=${s.score}`));
  console.log(`\n🏆 우승: ${scores[0].name}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
