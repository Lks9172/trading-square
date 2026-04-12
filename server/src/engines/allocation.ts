import { Regime, Signal, AssetSignal, AllocationPlan, DerivedIndicator, MarketDataPoint } from '../types/indicators';

const BASE_ALLOCATIONS: Record<Regime, Record<string, number>> = {
  RISK_ON:        { cash: 10, nasdaq: 40, leverage: 0,  gold: 15, silver: 5,  copper: 10, korea: 10, emerging: 10 },
  NEUTRAL:        { cash: 20, nasdaq: 35, leverage: 0,  gold: 20, silver: 5,  copper: 5,  korea: 10, emerging: 5 },
  CAUTION:        { cash: 30, nasdaq: 25, leverage: 0,  gold: 25, silver: 0,  copper: 5,  korea: 10, emerging: 5 },
  CORRECTION:     { cash: 25, nasdaq: 30, leverage: 0,  gold: 25, silver: 0,  copper: 5,  korea: 10, emerging: 5 },
  PANIC_BUT_OK:   { cash: 15, nasdaq: 35, leverage: 10, gold: 20, silver: 5,  copper: 5,  korea: 5,  emerging: 5 },
  RECESSION_RISK: { cash: 50, nasdaq: 15, leverage: 0,  gold: 25, silver: 0,  copper: 0,  korea: 5,  emerging: 5 },
};

const SIGNAL_MULTIPLIERS: Record<Signal, number> = {
  STRONG_BUY: 1.3,
  BUY: 1.1,
  HOLD: 1.0,
  REDUCE: 0.7,
  SELL: 0.3,
};

const SIGNAL_ASSET_MAP: Record<string, string> = {
  NASDAQ: 'nasdaq',
  GOLD: 'gold',
  SILVER: 'silver',
  COPPER: 'copper',
  CASH: 'cash',
};

function determineBuyStage(
  derived: Record<string, DerivedIndicator>,
  raw: Record<string, MarketDataPoint>
): 0 | 1 | 2 | 3 {
  const disparity = derived.NASDAQ_DISPARITY?.value ?? null;
  const above200 = derived.NASDAQ_ABOVE_200DMA?.value ?? 1;
  const vix = raw.VIXCLS?.value ?? null;

  if (above200 === 1) return 0;

  if (disparity !== null && disparity <= -25 && vix !== null && vix >= 35) return 3;
  if (disparity !== null && disparity <= -20) return 2;
  return 1;
}

function normalize(alloc: Record<string, number>): Record<string, number> {
  const sum = Object.values(alloc).reduce((a, b) => a + b, 0);
  if (sum === 0) return alloc;
  const result: Record<string, number> = {};
  for (const [k, val] of Object.entries(alloc)) {
    result[k] = Math.round((val / sum) * 100);
  }

  const newSum = Object.values(result).reduce((a, b) => a + b, 0);
  if (newSum !== 100) {
    const maxKey = Object.entries(result).sort((a, b) => b[1] - a[1])[0][0];
    result[maxKey] += 100 - newSum;
  }

  return result;
}

export function computeAllocation(
  regime: Regime,
  score: number,
  signals: AssetSignal[],
  derived: Record<string, DerivedIndicator>,
  raw: Record<string, MarketDataPoint>
): AllocationPlan {
  const base = { ...BASE_ALLOCATIONS[regime] };

  for (const sig of signals) {
    const allocKey = SIGNAL_ASSET_MAP[sig.asset];
    if (allocKey && base[allocKey] !== undefined && sig.asset !== 'LEVERAGE') {
      base[allocKey] = base[allocKey] * SIGNAL_MULTIPLIERS[sig.signal];
    }
  }

  const leverageSignal = signals.find((s) => s.asset === 'LEVERAGE');
  const leverageAllowed = leverageSignal?.signal === 'BUY';

  if (!leverageAllowed) {
    if (base.leverage > 0) {
      base.nasdaq += base.leverage;
      base.leverage = 0;
    }
  } else {
    base.leverage = Math.min(base.leverage, 15);
  }

  const allocations = normalize(base);
  const buyStage = determineBuyStage(derived, raw);

  return {
    regime,
    score,
    allocations,
    leverageAllowed,
    buyStage,
    date: new Date().toISOString().split('T')[0],
  };
}
