import { Regime, Signal, AssetSignal, AllocationPlan, DerivedIndicator, MarketDataPoint } from '../types/indicators';

const BASE_ALLOCATIONS: Record<Regime, Record<string, number>> = {
  RISK_ON:        { cash: 10, nasdaq: 43, leverage: 0,  gold: 12, silver: 5,  copper: 10, korea: 7,  emerging: 8 },
  NEUTRAL:        { cash: 18, nasdaq: 37, leverage: 0,  gold: 18, silver: 5,  copper: 5,  korea: 7,  emerging: 5 },
  CAUTION:        { cash: 28, nasdaq: 27, leverage: 0,  gold: 23, silver: 0,  copper: 5,  korea: 7,  emerging: 5 },
  CORRECTION:     { cash: 22, nasdaq: 32, leverage: 0,  gold: 23, silver: 0,  copper: 5,  korea: 7,  emerging: 6 },
  PANIC_BUT_OK:   { cash: 12, nasdaq: 38, leverage: 10, gold: 18, silver: 5,  copper: 5,  korea: 5,  emerging: 5 },
  RECESSION_RISK: { cash: 50, nasdaq: 15, leverage: 0,  gold: 25, silver: 0,  copper: 0,  korea: 5,  emerging: 5 },
};

// 신호 배수는 PRD §6.3.2 스펙을 따른다.
// 코드에 기존 1.4/1.2/0.65/0.25 가 박혀있었으나, 영상 5편의 공통 원칙인
// "추격매수 금지 / 분할매수 필수 / 단일 신호에 과신 금지" 철학은
// 더 완만한 계수(PRD 1.3/1.1/0.7/0.3) 와 정합한다.
const SIGNAL_MULTIPLIERS: Record<Signal, number> = {
  STRONG_BUY: 1.3,
  BUY: 1.1,
  HOLD: 1.0,
  REDUCE: 0.7,
  SELL: 0.3,
};

const SIGNAL_ASSET_MAP: Record<string, string> = {
  NASDAQ: 'nasdaq',
  KOSPI: 'korea',
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

const HORIZON_SHIFT: Record<string, Record<string, number>> = {
  short:  { cash: 5, nasdaq: -3, gold: -2 },
  medium: { cash: 0, nasdaq: 0, gold: 0 },
  long:   { cash: -5, nasdaq: 3, gold: 2 },
};

export function computeAllocation(
  regime: Regime,
  score: number,
  signals: AssetSignal[],
  derived: Record<string, DerivedIndicator>,
  raw: Record<string, MarketDataPoint>,
  horizon: string = 'medium'
): AllocationPlan {
  const base = { ...BASE_ALLOCATIONS[regime] };
  const shift = HORIZON_SHIFT[horizon] || HORIZON_SHIFT.medium;
  for (const [k, v] of Object.entries(shift)) {
    if (base[k] !== undefined) base[k] = Math.max(0, base[k] + v);
  }

  for (const sig of signals) {
    const allocKey = SIGNAL_ASSET_MAP[sig.asset];
    if (allocKey && base[allocKey] !== undefined && sig.asset !== 'LEVERAGE') {
      base[allocKey] = base[allocKey] * SIGNAL_MULTIPLIERS[sig.signal];
    }
  }

  const fxLevel = derived.KRW_FX_LEVEL?.value ?? 0;
  if (fxLevel <= -1) {
    const koreaReduction = base.korea * 0.5;
    base.korea -= koreaReduction;
    base.cash += koreaReduction;
  }

  // === 과열 보정 (OVERHEATED=1) ===
  // 철학: 과열 국면에서는 위험자산을 줄이고 현금·금으로 이관한다.
  // 기존 구현은 `cash+20, gold+5` 를 먼저 더한 뒤 reduceKeys 총합에서 비례 25 를
  // 차감했지만, Math.max(0, …) 가드 때문에 실제 차감량이 25 에 못 미치면
  // 합이 100 을 초과해 normalize 가 전체 비율을 왜곡시킨다(특히 CAUTION/
  // RECESSION_RISK 처럼 reduceKeys 총합이 작은 국면).
  // 해결: 실제 차감 가능한 총량만큼만 cash/gold 에 이관하고, 20:5 비율은 유지한다.
  const overheated = derived.OVERHEATED?.value === 1;
  if (overheated) {
    const reduceKeys = ['nasdaq', 'leverage', 'korea', 'emerging', 'copper'];
    const available = reduceKeys.reduce((s, k) => s + (base[k] || 0), 0);
    const desired = 25;
    const actual = Math.min(available, desired);

    if (available > 0 && actual > 0) {
      for (const k of reduceKeys) {
        const v = base[k] || 0;
        base[k] = Math.max(0, v - (v / available) * actual);
      }
    }
    // 20:5 비율 유지 (cash:gold = 4:1)
    base.cash = (base.cash || 0) + actual * (20 / 25);
    base.gold = (base.gold || 0) + actual * (5 / 25);
  }

  const leverageSignal = signals.find((s) => s.asset === 'LEVERAGE');
  const leverageAllowed = leverageSignal?.signal === 'BUY';

  if (!leverageAllowed) {
    if (base.leverage > 0) {
      base.nasdaq += base.leverage;
      base.leverage = 0;
    }
  }
  // 주의: base.leverage 에 대한 pre-normalize clamp 는 의도적으로 제거.
  // normalize() 가 전체 합 기준 재스케일을 수행하므로 pre-clamp 15 는 normalize
  // 후 실제 20% 까지 팽창 가능. 영상1 §전략C "짧게/20~30% 익절"의 상한을
  // 보호하기 위해 normalize 이후에 최종 clamp 한다(아래).

  let allocations = normalize(base);

  // === 레버리지 최종 상한 (영상1 §전략C): 15% ===
  // normalize 이후 실제 비중 기준. 초과분은 cash 로 이관해 현금 쿠션 유지.
  if (allocations.leverage > 15) {
    const excess = allocations.leverage - 15;
    allocations = {
      ...allocations,
      leverage: 15,
      cash: (allocations.cash || 0) + excess,
    };
  }

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
