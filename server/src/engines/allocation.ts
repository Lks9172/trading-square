import { Regime, Signal, AssetSignal, AllocationPlan, DerivedIndicator, MarketDataPoint } from '../types/indicators';
import { ASSET_TO_ALLOC_KEY } from './asset-keys';

// 백테스트 1/3/5/10Y 가중(1Y 25% + 3Y 10% + 5Y 30% + 10Y 35%, DD penalty 0.15) 기준
// Monte Carlo sweep (N=40, 국면별 독립, 나머지 국면 PRD 유지) 결과의 top1 을 반영.
// - RISK_ON/NEUTRAL/CAUTION/CORRECTION: 10년 기간 내 실제 발동된 국면 → 데이터 기반 갱신
// - PANIC_BUT_OK/RECESSION_RISK: 10년 내 미발동에 가까워 sample 구분력 없음 → PRD 유지
//
// 주요 관찰:
//   * RISK_ON/NEUTRAL 에서 korea/emerging 비중 상향 (2016~2026 신흥국·코스피 강세 흡수)
//   * CAUTION/CORRECTION 에서 silver 0→7~8 로 편입 (영상2 "금은비 저평가 + 경기 확인" 정합)
//   * cash 전반적으로 소폭 축소 (강세장 기간 수익 기여 극대화)
// 효과: COMPOSED 10Y 225%→296% (+70%p), 5Y +17%p, 1Y +14%p (sweep 기준)
const BASE_ALLOCATIONS: Record<Regime, Record<string, number>> = {
  RISK_ON:        { cash: 8,  nasdaq: 32, leverage: 0,  gold: 7,  silver: 10, copper: 6,  korea: 18, emerging: 19 },
  NEUTRAL:        { cash: 8,  nasdaq: 38, leverage: 0,  gold: 14, silver: 8,  copper: 7,  korea: 14, emerging: 11 },
  CAUTION:        { cash: 28, nasdaq: 29, leverage: 0,  gold: 21, silver: 7,  copper: 4,  korea: 11, emerging: 0  },
  CORRECTION:     { cash: 13, nasdaq: 35, leverage: 0,  gold: 19, silver: 8,  copper: 5,  korea: 11, emerging: 9  },
  PANIC_BUT_OK:   { cash: 15, nasdaq: 35, leverage: 10, gold: 20, silver: 5,  copper: 5,  korea: 5,  emerging: 5  },
  RECESSION_RISK: { cash: 50, nasdaq: 15, leverage: 0,  gold: 25, silver: 0,  copper: 0,  korea: 5,  emerging: 5  },
  // Fix #5: 스태그플레이션 — 물가↑ + 성장↓. 금·은 방어, 위험자산 축소. (영상4 §145)
  STAGFLATION:    { cash: 25, nasdaq: 15, leverage: 0,  gold: 30, silver: 10, copper: 5,  korea: 8,  emerging: 7  },
  // Fix #5: 채권 자경단 — 장기금리 급등 + DXY 약세 + HY 확대. 현금·금 극단 방어. (영상4 §137-147)
  BOND_VIGILANTE: { cash: 30, nasdaq: 10, leverage: 0,  gold: 35, silver: 8,  copper: 3,  korea: 7,  emerging: 7  },
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

// Fix #8(2차 감사): 자산 키 매핑은 engines/asset-keys.ts 의 ASSET_TO_ALLOC_KEY 로 통일.
// LEVERAGE 는 맵에 포함되지만 이 루프에서는 signal multiplier 를 적용하지 않는다
// (base.leverage 는 leverageAllowed 게이트로만 결정됨). 아래 signals 루프 가드 참조.
const SIGNAL_ASSET_MAP = ASSET_TO_ALLOC_KEY;

function determineBuyStage(
  derived: Record<string, DerivedIndicator>,
  raw: Record<string, MarketDataPoint>
): 0 | 1 | 2 | 3 | null {
  const disparity = derived.NASDAQ_DISPARITY?.value ?? null;
  // Fix #6: NASDAQ_ABOVE_200DMA 결측 시 `?? 1` 은 "암묵 상승추세" 로 가정해 stage 0 반환 →
  // 실제 불확실을 HOLD 로 위장한다. null 유지하고 호출부/타입에서 '결측' 을 표현.
  const above200 = derived.NASDAQ_ABOVE_200DMA?.value ?? null;
  const vix = raw.VIXCLS?.value ?? null;

  if (above200 === null) return null;
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

/** 백테스트 변형 비교 등 외부 실험용으로 BASE_ALLOCATIONS 를 override 할 수 있는 주입구. */
export { BASE_ALLOCATIONS };

export function computeAllocation(
  regime: Regime,
  score: number,
  signals: AssetSignal[],
  derived: Record<string, DerivedIndicator>,
  raw: Record<string, MarketDataPoint>,
  horizon: string = 'medium',
  baseAllocationsOverride?: Record<Regime, Record<string, number>>,
): AllocationPlan {
  const bases = baseAllocationsOverride || BASE_ALLOCATIONS;
  const base = { ...bases[regime] };
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

  // FX 불리 (KRW_FX_LEVEL <= -1, 환율 1500원 초과) 시 한국 비중 일부 cash 이관.
  // 기존 50% 삭감은 영상5 "외국인 매도 압력"을 과하게 반영해 base 10 → 5 수준으로
  // 포지션을 사실상 소멸시킴. 영상5는 "환율이 방향의 70% 결정"이라고 했지만,
  // 동시에 "외국인 복귀 시 반발 가능성"도 시사했으므로 최소한의 포지션은 쥐고 있어야 한다.
  // → 삭감률 30% 로 완화 (base 10 → 7). 환율 -2(1550원 초과) 에서는 50% 적용으로 강화.
  //
  // Fix #6: 결측 시 `?? 0` 은 "환율 중립" 으로 간주해 아무 보정도 하지 않는 효과라
  // 동작은 같지만 의도가 모호했다. null 명시 가드로 교체 — 결측이면 FX 보정 블록 전체 skip.
  const fxLevel = derived.KRW_FX_LEVEL?.value ?? null;
  if (fxLevel !== null) {
    if (fxLevel <= -2) {
      const cut = base.korea * 0.5;
      base.korea -= cut;
      base.cash += cut;
    } else if (fxLevel <= -1) {
      const cut = base.korea * 0.3;
      base.korea -= cut;
      base.cash += cut;
    }
  }

  // === 방어 보정 우선순위 정책 (Fix #7) ===
  // 기존: FISCAL_STRESS / OVERHEATED 가 독립적으로 순차 발동 가능했음 →
  //   두 플래그 동시 on 이면 `cash` 가 이중으로 팽창(원래 base 50 → 실측 60+)하거나
  //   `gold` 가 누적 상한 없이 45% 를 넘어설 수 있어, 포트폴리오 분산이 무너진다.
  //
  // 채택 정책: **더 강한 쪽 하나만 적용 (exclusive)**.
  //   우선순위: FISCAL_STRESS_HARD  (재정 +  수익률곡선 스티프닝)
  //          >  FISCAL_STRESS / BOND_VIGILANTE (재정 자경단 경보)
  //          >  OVERHEATED         (이격+공포 / 이격+VIX)
  //
  // 철학적 근거: FISCAL 은 "구조적/거시 위기" 신호이고 OVERHEATED 는 "단기 추세 과열"
  //   신호다. 두 성격이 다르므로 동시 가중은 의미가 불명확하고, 가장 강한 위험
  //   원인 하나로 집중해 방어하는 편이 신호 일관성·해석 가능성 모두에 유리.
  //   (PRD 철학 "하나의 렌즈로 보지 않는다"는 판정 자체에 대한 원칙이고,
  //    판정 확정 후 보정 단계에서는 "이중 보정이 dampen 을 왜곡" 리스크가 크다.)
  const fiscalStress =
    derived.FISCAL_STRESS?.value === 1 ||
    derived.BOND_VIGILANTE_WARNING?.value === 1;
  const fiscalStressHard = derived.FISCAL_STRESS_HARD?.value === 1;
  const overheated = derived.OVERHEATED?.value === 1;

  type DefenseMode = 'fiscal-hard' | 'fiscal' | 'overheated' | 'none';
  const defenseMode: DefenseMode =
    fiscalStressHard ? 'fiscal-hard' :
    fiscalStress     ? 'fiscal' :
    overheated       ? 'overheated' :
                       'none';

  if (defenseMode === 'fiscal-hard' || defenseMode === 'fiscal') {
    // 재정 리스크 보정 (영상4 §07 채권 자경단).
    // 30년 금리 급등 + 높은 레벨 → 위험자산 축소, 금·현금 방어.
    // hard 는 더 강하게(15), 일반은 보수적(8).
    const amount = defenseMode === 'fiscal-hard' ? 15 : 8;
    const reduceKeys = ['nasdaq', 'leverage', 'korea', 'emerging'];
    const available = reduceKeys.reduce((s, k) => s + (base[k] || 0), 0);
    const actual = Math.min(available, amount);
    if (available > 0 && actual > 0) {
      for (const k of reduceKeys) {
        const v = base[k] || 0;
        base[k] = Math.max(0, v - (v / available) * actual);
      }
    }
    // 60% cash, 40% gold 로 이관 (영상4 § 금은 장기 방어)
    base.cash = (base.cash || 0) + actual * 0.6;
    base.gold = (base.gold || 0) + actual * 0.4;
  } else if (defenseMode === 'overheated') {
    // 과열 보정 (OVERHEATED=1).
    // 철학: 과열 국면에서는 위험자산을 줄이고 현금·금으로 이관한다.
    // 기존 구현은 `cash+20, gold+5` 를 먼저 더한 뒤 reduceKeys 총합에서 비례 25 를
    // 차감했지만, Math.max(0, …) 가드 때문에 실제 차감량이 25 에 못 미치면
    // 합이 100 을 초과해 normalize 가 전체 비율을 왜곡시킨다(특히 CAUTION/
    // RECESSION_RISK 처럼 reduceKeys 총합이 작은 국면).
    // 해결: 실제 차감 가능한 총량만큼만 cash/gold 에 이관하고, 20:5 비율은 유지한다.
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

  // Fix #4: M2_YOY_CROSS_DAYS 소비 — 글로벌 유동성 음→양 교차 후 90일 이내면
  // NASDAQ/LEVERAGE 에 +5% 쿠션(cash 에서 이관). 교차 직후의 유동성 랠리 초기 구간을 포착.
  // 단 OVERHEATED=1 이면 비활성 — 과열 위에 쿠션 추가는 리스크 증폭이므로 금지.
  const m2CrossDays = derived.M2_YOY_CROSS_DAYS?.value ?? null;
  if (!overheated && m2CrossDays !== null && m2CrossDays >= 0 && m2CrossDays <= 90) {
    const cushion = 5;
    const cashAvail = base.cash || 0;
    const actualCushion = Math.min(cashAvail, cushion);
    if (actualCushion > 0) {
      // NASDAQ 우선, 남으면 LEVERAGE 에 (단 leverageAllowed 는 아래에서 결정되므로 무조건 base 에 얹고 이후 차단 시 nasdaq 로 합산됨)
      base.cash = cashAvail - actualCushion;
      base.nasdaq = (base.nasdaq || 0) + actualCushion;
    }
  }

  const leverageSignal = signals.find((s) => s.asset === 'LEVERAGE');
  // Fix #3: STRONG_BUY 도 허용. 기존엔 === 'BUY' 만 통과시켜 STRONG_BUY 시 레버리지 0%
  // 처리되는 비대칭이 있었다(3/3 조건 충족 후 승격되면 오히려 차단되는 모순).
  const leverageSig = leverageSignal?.signal;
  const leverageAllowed = leverageSig === 'BUY' || leverageSig === 'STRONG_BUY';

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
