import { listSectorDefinitions } from '../engines/sector-classification';
import { readHistory } from '../state/history-store';
import { DerivedIndicator, MarketDataPoint, RegimeState, SectorRotationRegime } from '../types/indicators';
import { computeSectorMacroFitScore, inferRotationRegime } from './sector-rotation';

type HistoryPoint = { date: string; value: number };

function latestAtOrBefore(points: HistoryPoint[], date: string): number | null {
  for (let i = points.length - 1; i >= 0; i -= 1) {
    if (points[i].date <= date) return points[i].value;
  }
  return null;
}

function historyIndexAtOrBefore(points: HistoryPoint[], date: string): number {
  for (let i = points.length - 1; i >= 0; i -= 1) {
    if (points[i].date <= date) return i;
  }
  return -1;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function buildApproximateRegime(raw: Record<string, MarketDataPoint>, derived: Record<string, DerivedIndicator>): RegimeState {
  const liquidity = derived.LIQUIDITY_DIRECTION?.value ?? 0;
  const realYield = derived.REAL_YIELD?.value ?? null;
  const curve = raw.T10Y2Y?.value ?? null;
  const wti = raw.WTI?.value ?? null;
  const overheated = (derived.OVERHEATED?.value ?? 0) === 1;

  let regime: RegimeState['regime'] = 'NEUTRAL';
  if ((wti !== null && wti >= 84) && realYield !== null && realYield >= 2.2) regime = 'STAGFLATION';
  else if ((curve !== null && curve <= -0.2) && liquidity < 0) regime = 'RECESSION_RISK';
  else if (realYield !== null && realYield >= 2.45 && liquidity <= 0) regime = 'BOND_VIGILANTE';
  else if (overheated && liquidity > 0) regime = 'CAUTION';
  else if (liquidity > 0 && (curve ?? 0) > 0) regime = 'RISK_ON';

  return {
    regime,
    score: regime === 'RISK_ON' ? 75 : regime === 'NEUTRAL' ? 60 : regime === 'CAUTION' ? 50 : 35,
    date: raw.T10Y2Y?.date ?? Object.values(raw)[0]?.date ?? '',
    components: {},
  };
}

function computeTrailingReturn(points: HistoryPoint[], index: number, lookback: number): number | null {
  if (index < lookback || index < 0 || index >= points.length) return null;
  const prev = points[index - lookback]?.value;
  const curr = points[index]?.value;
  if (!prev || !curr || prev <= 0) return null;
  return ((curr / prev) - 1) * 100;
}

function computeForwardReturn(points: HistoryPoint[], index: number, forward: number): number | null {
  if (index < 0 || index + forward >= points.length) return null;
  const prev = points[index]?.value;
  const next = points[index + forward]?.value;
  if (!prev || !next || prev <= 0) return null;
  return ((next / prev) - 1) * 100;
}

function percentileRank(values: number[], current: number): number {
  if (!values.length) return 50;
  const sorted = [...values].sort((a, b) => a - b);
  const below = sorted.filter((item) => item < current).length;
  const equal = sorted.filter((item) => item === current).length;
  return Math.round((((below + Math.max(0, equal - 1) / 2) / Math.max(1, sorted.length - 1)) * 100));
}

function monthEndDates(points: HistoryPoint[]): string[] {
  const dates: string[] = [];
  let lastMonth = '';
  for (const point of points) {
    const month = point.date.slice(0, 7);
    if (lastMonth && month !== lastMonth) {
      dates.push(points[points.indexOf(point) - 1].date);
    }
    lastMonth = month;
  }
  if (points.length) dates.push(points[points.length - 1].date);
  return [...new Set(dates)];
}

export async function buildSectorRotationBacktest(years = 5) {
  const sectorDefs = listSectorDefinitions();
  const sectorSymbols = sectorDefs.map((item) => ({ key: item.key, symbol: item.key.replace('SECTOR_', '') }));

  const [benchmark, ...histories] = await Promise.all([
    readHistory('yahoo', 'SP500'),
    ...sectorSymbols.map((item) => readHistory('yahoo', item.symbol)),
    readHistory('yahoo', 'DXY'),
    readHistory('yahoo', 'WTI'),
    readHistory('fred', 'T10Y2Y'),
    readHistory('fred', 'BAMLH0A0HYM2'),
    readHistory('fred', 'STLFSI4'),
    readHistory('derived', 'LIQUIDITY_DIRECTION'),
    readHistory('derived', 'REAL_YIELD'),
    readHistory('derived', 'OVERHEATED'),
    readHistory('derived', 'COPPER_GOLD_RATIO_UPTURN'),
  ]);

  const sectorHistories = new Map<string, HistoryPoint[]>();
  sectorSymbols.forEach((item, index) => {
    sectorHistories.set(item.key, histories[index] as HistoryPoint[]);
  });
  const offset = sectorSymbols.length;
  const dxyHist = histories[offset] as HistoryPoint[];
  const wtiHist = histories[offset + 1] as HistoryPoint[];
  const curveHist = histories[offset + 2] as HistoryPoint[];
  const hyOasHist = histories[offset + 3] as HistoryPoint[];
  const stlfsiHist = histories[offset + 4] as HistoryPoint[];
  const liquidityHist = histories[offset + 5] as HistoryPoint[];
  const realYieldHist = histories[offset + 6] as HistoryPoint[];
  const overheatedHist = histories[offset + 7] as HistoryPoint[];
  const copperGoldUpturnHist = histories[offset + 8] as HistoryPoint[];

  const base = benchmark.slice(-Math.min(benchmark.length, years * 252));
  const rebalanceDates = monthEndDates(base).filter((date) => {
    const idx = historyIndexAtOrBefore(benchmark, date);
    return idx >= 126 && idx + 126 < benchmark.length;
  });

  const evaluationRows = rebalanceDates.map((date) => {
    const raw: Record<string, MarketDataPoint> = {};
    const derived: Record<string, DerivedIndicator> = {};

    const dxy = latestAtOrBefore(dxyHist, date);
    const wti = latestAtOrBefore(wtiHist, date);
    const curve = latestAtOrBefore(curveHist, date);
    const hyOas = latestAtOrBefore(hyOasHist, date);
    const stlfsi = latestAtOrBefore(stlfsiHist, date);
    if (typeof dxy === 'number') raw.DXY = { code: 'DXY', value: dxy, date, source: 'YAHOO' };
    if (typeof wti === 'number') raw.WTI = { code: 'WTI', value: wti, date, source: 'YAHOO' };
    if (typeof curve === 'number') raw.T10Y2Y = { code: 'T10Y2Y', value: curve, date, source: 'FRED' };
    if (typeof hyOas === 'number') raw.BAMLH0A0HYM2 = { code: 'BAMLH0A0HYM2', value: hyOas, date, source: 'FRED' };
    if (typeof stlfsi === 'number') raw.STLFSI4 = { code: 'STLFSI4', value: stlfsi, date, source: 'FRED' };

    const liquidity = latestAtOrBefore(liquidityHist, date);
    const realYield = latestAtOrBefore(realYieldHist, date);
    const overheated = latestAtOrBefore(overheatedHist, date);
    const copperGoldUpturn = latestAtOrBefore(copperGoldUpturnHist, date);
    if (typeof liquidity === 'number') derived.LIQUIDITY_DIRECTION = { name: 'LIQUIDITY_DIRECTION', value: liquidity, date, formula: 'history' };
    if (typeof realYield === 'number') derived.REAL_YIELD = { name: 'REAL_YIELD', value: realYield, date, formula: 'history' };
    if (typeof overheated === 'number') derived.OVERHEATED = { name: 'OVERHEATED', value: overheated, date, formula: 'history' };
    if (typeof copperGoldUpturn === 'number') derived.COPPER_GOLD_RATIO_UPTURN = { name: 'COPPER_GOLD_RATIO_UPTURN', value: copperGoldUpturn, date, formula: 'history' };

    const approxRegime = buildApproximateRegime(raw, derived);
    const rotation = inferRotationRegime(raw, derived, approxRegime);

    const momentumRows = sectorDefs.flatMap((sector) => {
      const hist = sectorHistories.get(sector.key) ?? [];
      const idx = historyIndexAtOrBefore(hist, date);
      if (idx < 126) return [];
      const ret1m = computeTrailingReturn(hist, idx, 21);
      const ret3m = computeTrailingReturn(hist, idx, 63);
      const ret6m = computeTrailingReturn(hist, idx, 126);
      if (ret1m === null || ret3m === null || ret6m === null) return [];
      return [{ sector, hist, idx, ret1m, ret3m, ret6m }];
    });

    const oneMonthSet = momentumRows.map((item) => item.ret1m);
    const threeMonthSet = momentumRows.map((item) => item.ret3m);
    const sixMonthSet = momentumRows.map((item) => item.ret6m);

    const ranked = momentumRows.map((item) => {
      const momentumScore = Math.round(
        percentileRank(oneMonthSet, item.ret1m) * 0.15
          + percentileRank(threeMonthSet, item.ret3m) * 0.35
          + percentileRank(sixMonthSet, item.ret6m) * 0.5,
      );
      const macroFitScore = computeSectorMacroFitScore(item.sector.key, rotation.regime, raw, derived);
      const finalScore = Math.round(macroFitScore * 0.6 + momentumScore * 0.4);
      return {
        key: item.sector.key,
        label: item.sector.label,
        macroFitScore,
        momentumScore,
        finalScore,
        hist: item.hist,
        idx: item.idx,
      };
    }).sort((a, b) => b.finalScore - a.finalScore);

    const top1 = ranked[0] ?? null;
    const top3 = ranked.slice(0, 3);
    const universe1m = ranked.map((item) => computeForwardReturn(item.hist, item.idx, 21)).filter((v): v is number => typeof v === 'number');
    const universe3m = ranked.map((item) => computeForwardReturn(item.hist, item.idx, 63)).filter((v): v is number => typeof v === 'number');
    const universe6m = ranked.map((item) => computeForwardReturn(item.hist, item.idx, 126)).filter((v): v is number => typeof v === 'number');
    const avg = (arr: number[]) => arr.length ? arr.reduce((sum, value) => sum + value, 0) / arr.length : null;

    const top1Forward1m = top1 ? computeForwardReturn(top1.hist, top1.idx, 21) : null;
    const top1Forward3m = top1 ? computeForwardReturn(top1.hist, top1.idx, 63) : null;
    const top1Forward6m = top1 ? computeForwardReturn(top1.hist, top1.idx, 126) : null;
    const top3Forward1m = avg(top3.map((item) => computeForwardReturn(item.hist, item.idx, 21)).filter((v): v is number => typeof v === 'number'));
    const top3Forward3m = avg(top3.map((item) => computeForwardReturn(item.hist, item.idx, 63)).filter((v): v is number => typeof v === 'number'));
    const top3Forward6m = avg(top3.map((item) => computeForwardReturn(item.hist, item.idx, 126)).filter((v): v is number => typeof v === 'number'));

    return {
      date,
      regime: rotation.regime,
      confidence: rotation.confidence,
      top1: top1 ? { key: top1.key, label: top1.label } : null,
      top3: top3.map((item) => ({ key: item.key, label: item.label })),
      forward: {
        top1_1m: top1Forward1m,
        top1_3m: top1Forward3m,
        top1_6m: top1Forward6m,
        top3_1m: top3Forward1m,
        top3_3m: top3Forward3m,
        top3_6m: top3Forward6m,
        universe_1m: avg(universe1m),
        universe_3m: avg(universe3m),
        universe_6m: avg(universe6m),
      },
    };
  });

  const summarize = (key: '1m' | '3m' | '6m') => {
    const top1Wins = evaluationRows.filter((row) => {
      const a = row.forward[`top1_${key}` as const];
      const b = row.forward[`universe_${key}` as const];
      return typeof a === 'number' && typeof b === 'number' && a > b;
    }).length;
    const top3Wins = evaluationRows.filter((row) => {
      const a = row.forward[`top3_${key}` as const];
      const b = row.forward[`universe_${key}` as const];
      return typeof a === 'number' && typeof b === 'number' && a > b;
    }).length;
    const top1Excess = evaluationRows
      .map((row) => {
        const a = row.forward[`top1_${key}` as const];
        const b = row.forward[`universe_${key}` as const];
        return typeof a === 'number' && typeof b === 'number' ? a - b : null;
      })
      .filter((v): v is number => typeof v === 'number');
    const top3Excess = evaluationRows
      .map((row) => {
        const a = row.forward[`top3_${key}` as const];
        const b = row.forward[`universe_${key}` as const];
        return typeof a === 'number' && typeof b === 'number' ? a - b : null;
      })
      .filter((v): v is number => typeof v === 'number');
    const avg = (arr: number[]) => arr.length ? arr.reduce((sum, value) => sum + value, 0) / arr.length : null;
    return {
      top1HitRate: evaluationRows.length ? Math.round((top1Wins / evaluationRows.length) * 100) : null,
      top3HitRate: evaluationRows.length ? Math.round((top3Wins / evaluationRows.length) * 100) : null,
      top1AvgExcessPct: avg(top1Excess) === null ? null : Math.round(avg(top1Excess)! * 100) / 100,
      top3AvgExcessPct: avg(top3Excess) === null ? null : Math.round(avg(top3Excess)! * 100) / 100,
    };
  };

  return {
    dateRange: {
      from: evaluationRows[0]?.date ?? null,
      to: evaluationRows[evaluationRows.length - 1]?.date ?? null,
    },
    methodology: {
      rebalance: 'monthly',
      macro: 'phase probability from liquidity, curve, real yield, dollar, oil, overheating',
      momentum: '1M/3M/6M relative-strength percentile blend',
      score: 'macroFit 60% + momentum 40%',
      note: '섹터 ETF 5년 히스토리 기준 프레임이며, 실제 EPS revision/ETF flow가 완전 반영된 institutional backtest는 아님',
    },
    rebalanceCount: evaluationRows.length,
    summary: {
      oneMonth: summarize('1m'),
      threeMonth: summarize('3m'),
      sixMonth: summarize('6m'),
    },
    recentSamples: evaluationRows.slice(-12),
  };
}
