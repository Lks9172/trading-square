export type BottomPatternPoint = {
  date: string;
  close: number;
  volume?: number;
};

export type BottomPatternPhase = 'decline' | 'candidate' | 'retest' | 'confirm';

export interface BottomPatternAnalysis {
  peakPoint: BottomPatternPoint | null;
  candidatePoint: BottomPatternPoint | null;
  retestPoint: BottomPatternPoint | null;
  confirmPoint: BottomPatternPoint | null;
  currentPoint: BottomPatternPoint | null;
  phase: BottomPatternPhase;
  declinePctFromPeak: number | null;
  reboundPctFromCandidate: number | null;
  retestGapPct: number | null;
}

function pctChange(current: number, previous: number): number | null {
  if (!previous) return null;
  return Number((((current - previous) / previous) * 100).toFixed(1));
}

export function analyzeBottomPattern(history: BottomPatternPoint[]): BottomPatternAnalysis {
  const series = history.filter((item) => Number.isFinite(item.close) && item.close > 0);
  if (series.length < 40) {
    return {
      peakPoint: null,
      candidatePoint: null,
      retestPoint: null,
      confirmPoint: null,
      currentPoint: series.length ? series[series.length - 1] ?? null : null,
      phase: 'decline',
      declinePctFromPeak: null,
      reboundPctFromCandidate: null,
      retestGapPct: null,
    };
  }

  const scanEnd = Math.max(20, series.length - 15);
  let peakIndex = 0;
  for (let index = 1; index < scanEnd; index += 1) {
    if (series[index].close > series[peakIndex].close) peakIndex = index;
  }

  let candidateIndex = Math.min(series.length - 1, peakIndex + 5);
  for (let index = Math.min(series.length - 1, peakIndex + 5); index < series.length; index += 1) {
    if (series[index].close < series[candidateIndex].close) candidateIndex = index;
  }

  const peakPoint = series[peakIndex] ?? null;
  const candidatePoint = series[candidateIndex] ?? null;
  const currentPoint = series[series.length - 1] ?? null;
  const declinePctFromPeak = peakPoint && candidatePoint ? pctChange(candidatePoint.close, peakPoint.close) : null;
  const reboundPctFromCandidate = candidatePoint && currentPoint ? pctChange(currentPoint.close, candidatePoint.close) : null;

  let swingHighIndex: number | null = null;
  if (candidatePoint) {
    for (let index = Math.min(series.length - 1, candidateIndex + 3); index < series.length; index += 1) {
      if (series[index].close >= candidatePoint.close * 1.08) {
        swingHighIndex = index;
        break;
      }
    }
    if (swingHighIndex !== null) {
      for (let index = swingHighIndex; index < series.length; index += 1) {
        if (series[index].close > series[swingHighIndex].close) swingHighIndex = index;
      }
    }
  }

  let retestIndex: number | null = null;
  if (candidatePoint && swingHighIndex !== null && swingHighIndex < series.length - 3) {
    let localMinIndex = swingHighIndex + 1;
    for (let index = swingHighIndex + 1; index < series.length - 1; index += 1) {
      if (series[index].close < series[localMinIndex].close) localMinIndex = index;
    }
    const withinRetestBand =
      series[localMinIndex].close >= candidatePoint.close * 0.93
      && series[localMinIndex].close <= candidatePoint.close * 1.12;
    if (withinRetestBand) retestIndex = localMinIndex;
  }

  let confirmIndex: number | null = null;
  const confirmBase = swingHighIndex !== null ? series[swingHighIndex].close : (candidatePoint ? candidatePoint.close * 1.15 : null);
  const confirmStart = retestIndex !== null ? retestIndex + 1 : candidateIndex + 1;
  if (confirmBase !== null) {
    for (let index = confirmStart; index < series.length; index += 1) {
      const close = series[index].close;
      if (close >= confirmBase * 0.98 && close >= (candidatePoint?.close ?? close) * 1.12) {
        confirmIndex = index;
        break;
      }
    }
  }

  const retestPoint = retestIndex !== null ? series[retestIndex] : null;
  const confirmPoint = confirmIndex !== null ? series[confirmIndex] : null;
  const retestGapPct = candidatePoint && retestPoint ? pctChange(retestPoint.close, candidatePoint.close) : null;
  const phase: BottomPatternPhase =
    confirmPoint ? 'confirm'
      : retestPoint ? 'retest'
        : reboundPctFromCandidate !== null && reboundPctFromCandidate >= 6 ? 'candidate'
          : 'decline';

  return {
    peakPoint,
    candidatePoint,
    retestPoint,
    confirmPoint,
    currentPoint,
    phase,
    declinePctFromPeak,
    reboundPctFromCandidate,
    retestGapPct,
  };
}
