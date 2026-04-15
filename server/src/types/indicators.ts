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
  value: number | null;
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
  weightedScore: number;
  weightedMaxScore: number;
  reasons: string[];
  unmetReasons: string[];
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
  investmentHorizon: 'short' | 'medium' | 'long';
  leverageEnabled: boolean;
  includeCrypto: boolean;
  includeKR: boolean;
  manualInputs: {
    policyDirection: number;
    geoRisk: number;
    cbBuying: boolean;
    ismPmi: number | null | undefined;
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
  meta: {
    fetchedAt: string;
    cacheTtlMs: number;
    nextRefreshAt: string;
    usPriceSource: 'spot' | 'futures';
    sourceFrequencies: Record<string, string>;
    latestDates: Record<string, string>;
    historyGuarantee: Record<string, string>;
    profile: UserProfile;
    autoInputs: { policyDirection: number; geoRisk: number; cbBuying: boolean; ismPmi: number | null } | null;
    inputMode: 'auto' | 'manual';
    staleness: Record<string, { date: string; daysAgo: number; frequency: string }>;
    smartMoney?: { insiderBuyRatio: number; recentInsiderBuys: number; recentInsiderSells: number; score: number; lastUpdated: string } | null;
    calendar?: Array<{
      date: string;
      name: string;
      category: 'FOMC' | 'CPI' | 'NFP' | 'PCE' | 'GDP' | 'OTHER';
      daysUntil: number;
      importance: 'high' | 'medium';
    }>;
    executionPlans?: ExecutionPlan[];
  };
}

// ── 실행 플레이북 (execution_plan 엔진) ──
// 영상 공통 철학: 지표는 진단, 실행은 '분할매수·손절·익절·유효기간'.
// 이동평균선.md:236-257 / video5:99-102 / video1:211-232
export type ExecutionAction =
  | 'BUY_NOW'         // 지금 1차 매수 조건 충족
  | 'SCALE_IN'        // 1차 이미 완료, 2·3차 대기 중
  | 'HOLD'            // 관망 — 매수 조건 미충족
  | 'TAKE_PROFIT'     // 익절 준비
  | 'EXIT'            // 전량 익절 또는 손절
  | 'AVOID';          // 구조적 위험 구간, 진입 금지

export interface ExecutionStage {
  stage: 1 | 2 | 3;
  weightPct: number;          // 해당 자산 할당 중 N% 집행
  triggerCondition: string;   // 인간 가독 조건 ("현재가에서 즉시" / "-5% 추가 하락 시" 등)
  triggerPrice?: number;      // 조건이 가격 기반일 때 구체 값
  status: 'pending' | 'ready' | 'triggered';
}

export interface ExecutionPlan {
  asset: string;
  action: ExecutionAction;
  actionLabel: string;        // 사용자 표시용 짧은 요약
  currentPrice: number | null;
  targetAllocationPct: number;  // 현 regime×signal 기반 목표 비중(%)
  stages: ExecutionStage[];
  stopLoss: { price: number | null; condition: string };
  takeProfit: { price: number | null; condition: string };
  validityDays: number;         // 유효기간 (영상1 레버리지 60~90일, 일반 자산 기본 45일)
  primaryReason: string;        // 이 action 을 택한 1줄 이유
}
