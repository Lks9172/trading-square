import { AllocationPlan, AssetSignal, DerivedIndicator, MarketDataPoint, RegimeState } from '../types/indicators';
import { childLogger } from './logger';

const derivedLog = childLogger({ module: 'decision.derived' });
const regimeLog = childLogger({ module: 'decision.regime' });
const signalsLog = childLogger({ module: 'decision.signals' });
const allocationLog = childLogger({ module: 'decision.allocation' });

function pickDerived(derived: Record<string, DerivedIndicator>, key: string) {
  const value = derived[key];
  if (!value) return null;
  return {
    value: value.value,
    formula: value.formula,
  };
}

function pickRaw(raw: Record<string, MarketDataPoint>, key: string) {
  const value = raw[key];
  if (!value) return null;
  return {
    value: value.value,
    date: value.date,
    source: value.source,
  };
}

function summarizeSignals(signals: AssetSignal[]) {
  return signals.map((signal) => ({
    asset: signal.asset,
    baseSignal: signal.explanation?.baseSignal ?? signal.signal,
    finalSignal: signal.signal,
    overrides: signal.explanation?.overrides ?? [],
    signal: signal.signal,
    conditionsMet: signal.conditionsMet,
    conditionsTotal: signal.conditionsTotal,
    weightedScore: signal.weightedScore,
    weightedMaxScore: signal.weightedMaxScore,
    topReasons: signal.reasons.slice(0, 3),
    topUnmetReasons: signal.unmetReasons.slice(0, 3),
  }));
}

export function logDecisionDetails(input: {
  raw: Record<string, MarketDataPoint>;
  derived: Record<string, DerivedIndicator>;
  regime: RegimeState;
  signals: AssetSignal[];
  allocation: AllocationPlan;
}) {
  const { raw, derived, regime, signals, allocation } = input;

  derivedLog.info({
    liquidity: {
      liquidityDirection: pickDerived(derived, 'LIQUIDITY_DIRECTION'),
      globalM2: pickDerived(derived, 'GLOBAL_M2_PROXY'),
      rrpDirection: pickDerived(derived, 'RRP_DIRECTION'),
      tgaDirection: pickDerived(derived, 'TGA_DIRECTION'),
      mmfDirection: pickDerived(derived, 'MMF_DIRECTION'),
      reserveDirection: pickDerived(derived, 'WRESBAL_DIRECTION'),
    },
    sentiment: {
      fearGreed: pickRaw(raw, 'FEAR_GREED'),
      psychSubscore: pickDerived(derived, 'PSYCH_SUBSCORE'),
      pcRatio: pickRaw(raw, 'PC_RATIO_10D'),
      aaii: pickRaw(raw, 'AAII_BULL_BEAR_SPREAD'),
      naaim: pickRaw(raw, 'NAAIM_EXPOSURE'),
    },
    trendAndKorea: {
      nasdaqDisparity: pickDerived(derived, 'NASDAQ_DISPARITY'),
      kospiTrendRecovery: pickDerived(derived, 'KOSPI_TREND_RECOVERY'),
      kospiVolumeConfirm: pickDerived(derived, 'KOSPI_VOLUME_CONFIRM'),
      usdkrw: pickRaw(raw, 'USDKRW'),
      krwFxLevel: pickDerived(derived, 'KRW_FX_LEVEL'),
      fxForeignComboAlert: pickDerived(derived, 'FX_FOREIGN_COMBO_ALERT'),
    },
    warnings: {
      overheated: pickDerived(derived, 'OVERHEATED'),
      creditStress: pickDerived(derived, 'CREDIT_STRESS_FLAG'),
      stagflation: pickDerived(derived, 'STAGFLATION_WARNING'),
      bondVigilante: pickDerived(derived, 'BOND_VIGILANTE_WARNING'),
      nasdaqChaseWarning: pickDerived(derived, 'NASDAQ_CHASE_WARNING'),
      kospiChaseWarning: pickDerived(derived, 'KOSPI_CHASE_WARNING'),
    },
  }, 'derived decision summary');

  regimeLog.info({
    regime: regime.regime,
    score: regime.score,
    preOverrideRegime: regime.explanation?.preOverrideRegime ?? regime.regime,
    overrides: regime.explanation?.overrides ?? [],
    positiveDrivers: regime.explanation?.positiveDrivers ?? [],
    negativeDrivers: regime.explanation?.negativeDrivers ?? [],
    components: regime.components,
  }, 'regime decision summary');

  signalsLog.info({
    signals: summarizeSignals(signals),
  }, 'signal decision summary');

  allocationLog.info({
    regime: allocation.regime,
    score: allocation.score,
    leverageAllowed: allocation.leverageAllowed,
    buyStage: allocation.buyStage,
    explanation: allocation.explanation ?? null,
    finalAllocations: allocation.allocations,
  }, 'allocation decision summary');
}
