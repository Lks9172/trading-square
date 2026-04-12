// ── 원시 지표 ──
export interface MarketDataPoint {
  code: string;
  value: number;
  date: string;
  source: 'FRED' | 'YAHOO' | 'CBOE' | 'CNN' | 'USER' | 'CALC';
}

// ── 파생 지표 ──
export interface DerivedIndicator {
  name: string;
  value: number;
  date: string;
  formula: string;
}

// ── 국면 ──
export type Regime =
  | 'RISK_ON'
  | 'NEUTRAL'
  | 'CAUTION'
  | 'CORRECTION'
  | 'PANIC_BUT_OK'
  | 'RECESSION_RISK';

export interface RegimeState {
  regime: Regime;
  score: number; // 0~100
  components: Record<string, number>; // 지표별 점수
  date: string;
}

// ── 자산 신호 ──
export type Signal = 'STRONG_BUY' | 'BUY' | 'HOLD' | 'REDUCE' | 'SELL';

export interface AssetSignal {
  asset: string;
  signal: Signal;
  conditionsMet: number;
  conditionsTotal: number;
  reasons: string[];
  date: string;
}

// ── 비중 ──
export interface AllocationPlan {
  regime: Regime;
  score: number;
  allocations: Record<string, number>; // asset → percentage
  leverageAllowed: boolean;
  buyStage: 0 | 1 | 2 | 3;
  date: string;
}

// ── 사용자 설정 ──
export interface UserProfile {
  riskTolerance: 'conservative' | 'moderate' | 'aggressive';
  leverageEnabled: boolean;
  includeCrypto: boolean;
  includeKR: boolean;
  manualInputs: {
    policyDirection: number;   // -2 ~ +2
    geoRisk: number;           // 0 ~ 4
    cbBuying: boolean;
  };
}

// ── 전체 스냅샷 ──
export interface SystemSnapshot {
  timestamp: string;
  raw: Record<string, MarketDataPoint>;
  derived: Record<string, DerivedIndicator>;
  regime: RegimeState;
  signals: AssetSignal[];
  allocation: AllocationPlan;
}
