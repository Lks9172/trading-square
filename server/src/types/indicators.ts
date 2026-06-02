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
// Fix #5: STAGFLATION / BOND_VIGILANTE 추가 (총 8종).
// - STAGFLATION: 물가 압력 + 성장 둔화 동시 (영상4 §145). 금·은 방어, 위험자산 축소.
// - BOND_VIGILANTE: 장기금리 급등 + DXY 약세 + HY 확대 (영상4 §137-147). 정책 실패 프리커서.
// 두 분기는 STAGFLATION_WARNING / BOND_VIGILANTE_WARNING 플래그에 의해 최우선 override.
export type Regime =
  | 'RISK_ON'
  | 'NEUTRAL'
  | 'CAUTION'
  | 'CORRECTION'
  | 'PANIC_BUT_OK'
  | 'RECESSION_RISK'
  | 'STAGFLATION'
  | 'BOND_VIGILANTE'
  | 'STAGFLATION_BOND_VIGILANTE'; // 24차 Phase 2#16: 동시 발동 합성 라벨

export interface RegimeState {
  regime: Regime;
  score: number; // 0~100
  components: Record<string, number>; // 지표별 점수
  date: string;
  explanation?: {
    preOverrideRegime: Regime;
    overrides: string[];
    positiveDrivers: Array<{
      component: string;
      score: number;
      weight: number;
      weightedContribution: number;
    }>;
    negativeDrivers: Array<{
      component: string;
      score: number;
      weight: number;
      weightedContribution: number;
    }>;
    weightedContributions: Record<string, {
      score: number;
      weight: number;
      weightedContribution: number;
    }>;
  };
}

// ── 자산 신호 ──
export type Signal = 'STRONG_BUY' | 'BUY' | 'HOLD' | 'REDUCE' | 'SELL';

export type LeverageTier = 'HARD' | 'MEDIUM' | 'SOFT';

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
  // LEVERAGE 전용 3단계 티어 (SOFT/MEDIUM/HARD). null 은 미발동.
  tier?: LeverageTier | null;
  explanation?: {
    baseSignal: Signal;
    finalSignal: Signal;
    overrides: string[];
    macroReasons?: string[];
    sectorReasons?: string[];
    assetReasons?: string[];
    flowReasons?: string[];
    timingNotes?: string[];
  };
}

export type SectorClassification = 'cyclical' | 'structural' | 'defensive';

export interface SectorQualityScore {
  policySupport: number;
  structuralDemand: number;
  supplyTightness: number;
  marketConcentration: number;
  totalScore: number;
}

export interface TopDownSectorView {
  key: string;
  label: string;
  classification: SectorClassification;
  score: number | null;
  quality?: SectorQualityScore;
  stance: 'favored' | 'avoided' | 'neutral';
  reasons: string[];
}

export interface TopDownAssetRationale {
  asset: string;
  label: string;
  macroReasons: string[];
  sectorReasons: string[];
  flowReasons: string[];
  timingNotes: string[];
}

export interface TopDownView {
  summary: string;
  macroView: Array<{
    key: string;
    label: string;
    stance: 'positive' | 'negative' | 'neutral';
    detail: string;
  }>;
  favoredSectors: TopDownSectorView[];
  avoidedSectors: TopDownSectorView[];
  assetRationale: TopDownAssetRationale[];
}

// ── 비중 ──
export interface AllocationPlan {
  regime: Regime;
  score: number;
  allocations: Record<string, number>; // asset → percentage
  leverageAllowed: boolean;
  // Fix #6: 결측(`NASDAQ_ABOVE_200DMA` 없음) 은 null 로 명시. UI 는 "데이터 없음" 표시.
  buyStage: 0 | 1 | 2 | 3 | null;
  date: string;
  explanation?: {
    baseRegimeAllocations: Record<string, number>;
    horizonShift: Record<string, number>;
    baseAfterHorizon: Record<string, number>;
    signalAdjustments: Array<{
      asset: string;
      allocKey: string;
      signal: Signal;
      multiplier: number;
      before: number;
      after: number;
    }>;
    adjustments: Array<{
      step: string;
      detail: string;
      allocKey?: string;
      amount?: number;
      before?: number;
      after?: number;
      mode?: string;
    }>;
    defenseMode: 'fiscal-hard' | 'fiscal' | 'overheated' | 'goldilocks-bad' | 'none';
    preNormalize: Record<string, number>;
    finalAllocations: Record<string, number>;
  };
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
    // 19차 P3#14: 분할매수 진척도 (0~100%)
    trancheUsedPct?: number;
    // 19차 P2#10: 주간 마이핀플 ETF inflow 테마 (수동 입력)
    etfInflowTheme?: string;
    // 21차 Phase 2#11: 지정학 카운트다운 이벤트 (이름 + 목표일)
    geopoliticalCountdown?: Array<{ event: string; targetDate: string }>;
    // ★ 29차 P1-D #11: KOSPI Forward PER 수동 입력 (자동 fetch 어려움)
    kospiForwardPER?: number | null;
    // ★ 29차 P2-B #11: 중앙은행 12M 금 매입량 (톤, WGC 분기 보고)
    cbGoldTonnage12M?: number | null;
    // ★ 29차 P2-C #17: KOSPI PBR 수동 입력 (KRX 자동 어려움)
    kospiPBR?: number | null;
    // ★ 29차 P2-C #18: KOSPI 합산 ROE 수동 입력
    kospiROE?: number | null;
    // ★ 29차 P3-A #1: IMF COFER USD 비중 (분기 데이터)
    fxReserveUsdRatio?: number | null;
    // ★ 29차 P3-A #3: ICI MMF 전체 (조달러)
    mmfTotalTrillion?: number | null;
    // ★ 29차 P3-A #5: JGB 10Y 수익률 (%)
    jgb10y?: number | null;
    // ★ 29차 P3-B #7: 한국 가계부채 GDP 비율 (%)
    krHouseholdDebtPctGdp?: number | null;
    // ★ 29차 P3-B #7: 한국 CPI YoY (%)
    krCpi?: number | null;
    // ★ 29차 P3-E #28: KRX 연기금 5D 누적 (조원, 옵션)
    krxPensionFlow5DTrillion?: number | null;
    // ★ 29차 P3-E #29: KRX 공매도 잔고 비율 (%)
    krxShortInterestPct?: number | null;
    // ★ 30차 P2-B #8: 베센트/워시 정책 톤 레벨 (-2 매파 ~ +2 비둘기)
    bessentToneLevel?: number | null;
    warshToneLevel?: number | null;
    // ★ 30차 P2-B #9: Fed dot-plot 내포 인하 확률 (%)
    fedDotPlotImpliedCutsPct?: number | null;
    // ★ 30차 P2-B #10: 미중 칩 제재 건수 (30D) + AI 인프라 capex 비중
    usChinaChipSanctionCount30D?: number | null;
    usAiInfraCapexShare?: number | null;
    // ★ 30차 P2-D #23: 한국 GDP 성장률 전망 (%)
    krGdpGrowthForecast?: number | null;
    // ★ 30차 P2-E #27: Powell 발언일 D-Day (0=오늘)
    powellSpeechDDay?: number | null;
    // ★ 30차 P2-E #28: AI 내러티브 강도 (0=없음, 1=보통, 2=강)
    aiNarrativeStrength?: number | null;
    // ★ 30차 P2-E #30: 현금 비중 (%)
    cashPct?: number | null;
    // ★ 30차 P3-B #11: SMIC 7nm 차단 여부 (0=차단, 1=가능). usChinaChipSanctionCount30D 는 P2-B #10 에서 이미 선언됨
    smicCapability7nm?: number | null;
    // ★ 30차 P3-C #14: 사용자 현금 유출 이벤트 예정일 (자녀 대학/은퇴/부동산 등)
    userCashflowEventDate?: string | null;
    // ★ 30차 P3-C #17: 한국 전후 복구 정책 금융 발효일
    krWarReliefFundActivationDate?: string | null;
    // ★ 30차 P3-C #18: 에너지 외교 이벤트 수 (월간 수동 입력)
    krEnergyDiplomacyEventCount?: number | null;
    // ★ 30차 P3-C #19: 삼성/SK하이닉스 영업이익 전망 (조원)
    samsungOperProfitForecast?: number | null;
    hynixOperProfitForecast?: number | null;
    // ★ 30차 P3-C #20: 보유 종목 재무 지표 (수동)
    userHoldingsDebtRatio?: number | null;
    userHoldingsCurrentRatio?: number | null;
    userHoldingsCashFlow?: number | null;
    // ★ 30차 P3-D #22: TipRanks SmartScore (1~10)
    tipranksSmartScore?: number | null;
    // ★ 30차 P3-D #23: IMF WEO 글로벌 성장률 전망 (%)
    imfWeoGlobalGrowthForecast?: number | null;
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
    topdown?: TopDownView;
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
  status: 'pending' | 'ready' | 'triggered' | 'filled';
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
  timing?: {
    macroAligned: boolean;
    sectorAligned: boolean;
    flowConfirmed: boolean;
    chartConfirmed: boolean;
    overheatingRisk: boolean;
    notes: string[];
  };
}
