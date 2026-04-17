import { DerivedIndicator, MarketDataPoint, UserProfile } from '../types/indicators';

type ValuePoint = { value: number; date?: string };

export type ManualInputsState = UserProfile['manualInputs'];

export const DEFAULT_MANUAL_INPUTS: ManualInputsState = {
  policyDirection: 0,
  geoRisk: 2,
  cbBuying: true,
  ismPmi: null,
};

export function usesDefaultPolicyControls(
  inputs: ManualInputsState,
  defaults: ManualInputsState = DEFAULT_MANUAL_INPUTS,
): boolean {
  return (
    inputs.policyDirection === defaults.policyDirection &&
    inputs.geoRisk === defaults.geoRisk &&
    inputs.cbBuying === defaults.cbBuying
  );
}

export function mergeEffectiveManualInputs(
  manualInputs: ManualInputsState,
  autoInputs: ManualInputsState,
  defaults: ManualInputsState = DEFAULT_MANUAL_INPUTS,
): ManualInputsState {
  if (!usesDefaultPolicyControls(manualInputs, defaults)) {
    return manualInputs;
  }

  return {
    ...autoInputs,
    ismPmi: autoInputs.ismPmi ?? manualInputs.ismPmi ?? null,
  };
}

export function derivePolicyDirectionFromSeries(
  effrHistory: ValuePoint[],
  t10y2yHistory: ValuePoint[],
  icsaHistory: ValuePoint[],
): number {
  if (effrHistory.length < 30) return 0;

  const recent = effrHistory.slice(0, 10);
  const older = effrHistory.slice(20, 30);
  const recentAvg = recent.reduce((sum, point) => sum + point.value, 0) / recent.length;
  const olderAvg = older.reduce((sum, point) => sum + point.value, 0) / older.length;
  const effrDelta = recentAvg - olderAvg;

  const yieldCurve = t10y2yHistory.length > 0 ? t10y2yHistory[0].value : 0;

  let icsaPressure = 0;
  if (icsaHistory.length >= 8) {
    const recentIcssa = icsaHistory.slice(0, 4).reduce((sum, point) => sum + point.value, 0) / 4;
    const olderIcssa = icsaHistory.slice(4, 8).reduce((sum, point) => sum + point.value, 0) / 4;
    const delta = (recentIcssa - olderIcssa) / olderIcssa;
    if (delta > 0.05) icsaPressure = 1;
    else if (delta > 0.02) icsaPressure = 0.5;
    else if (delta < -0.02) icsaPressure = -0.5;
  }

  let score = 0;
  if (effrDelta < -0.3) score = 2;
  else if (effrDelta < -0.1) score = 1;
  else if (effrDelta > 0.3 && yieldCurve < -0.5) score = -2;
  else if (effrDelta > 0.1) score = -1;

  score += icsaPressure;
  return Math.max(-2, Math.min(2, Math.round(score)));
}

export function deriveCBBuyingFromSeries(
  goldHistory: ValuePoint[],
  dxyHistory: ValuePoint[],
): boolean {
  // 2026-04 개선: "중앙은행 매수 지속" 은 분기/연 단위 구조적 수요 지표.
  // 기존 20일 vs 40~60일 전 window 는 단기 금 가격 노이즈 (이격도 -17.7% 과매도
  // 구간 등) 에 false 오판. 60일 vs 180~250일 (약 1년) 장기 추세로 전환.
  // 장기적으로 금이 오른 상태가 지속되면 true — 단기 하락에도 구조적 매수 판정 유지.
  if (goldHistory.length < 250 || dxyHistory.length < 250) return true;

  const goldRecent = goldHistory.slice(0, 60);
  const goldOlder = goldHistory.slice(180, 250);
  const goldRecentAvg = goldRecent.reduce((sum, point) => sum + point.value, 0) / goldRecent.length;
  const goldOlderAvg = goldOlder.reduce((sum, point) => sum + point.value, 0) / goldOlder.length;
  // 1년 기준 5% 이상 상승이면 구조적 매수 지속으로 판정 (연 단위 허용)
  const goldUp = goldRecentAvg > goldOlderAvg * 1.05;

  const dxyRecent = dxyHistory.slice(0, 60);
  const dxyOlder = dxyHistory.slice(180, 250);
  const dxyRecentAvg = dxyRecent.reduce((sum, point) => sum + point.value, 0) / dxyRecent.length;
  const dxyOlderAvg = dxyOlder.reduce((sum, point) => sum + point.value, 0) / dxyOlder.length;
  // DXY 는 5% 이상 강세가 아니면 "약세 아님" 으로 관대하게 (중앙은행은 달러 헷지 성격)
  const dxyStrongOrFlat = dxyRecentAvg >= dxyOlderAvg * 0.95;

  return goldUp && dxyStrongOrFlat;
}

export function inferAutoManualInputsFromState(input: {
  raw: Record<string, MarketDataPoint>;
  derived: Record<string, DerivedIndicator>;
  effrHistory: ValuePoint[];
  t10y2yHistory: ValuePoint[];
  icsaHistory: ValuePoint[];
  goldHistory: ValuePoint[];
  dxyHistory: ValuePoint[];
  geoRisk?: number;
}): ManualInputsState {
  const {
    raw,
    derived,
    effrHistory,
    t10y2yHistory,
    icsaHistory,
    goldHistory,
    dxyHistory,
    geoRisk = DEFAULT_MANUAL_INPUTS.geoRisk,
  } = input;

  return {
    policyDirection: derivePolicyDirectionFromSeries(effrHistory, t10y2yHistory, icsaHistory),
    geoRisk,
    cbBuying: deriveCBBuyingFromSeries(goldHistory, dxyHistory),
    ismPmi: raw.ISM_MANUFACTURING?.value ?? derived.ISM_PROXY?.value ?? null,
  };
}

