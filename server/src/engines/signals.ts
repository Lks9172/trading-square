import { MarketDataPoint, DerivedIndicator, RegimeState, AssetSignal, Signal, UserProfile } from '../types/indicators';

function v(raw: Record<string, MarketDataPoint>, key: string): number | null {
  return raw[key]?.value ?? null;
}

function dv(derived: Record<string, DerivedIndicator>, key: string): number | null {
  return derived[key]?.value ?? null;
}

function signalFromScore(met: number, total: number, thresholds = [2, 3, 4]): Signal {
  if (met >= thresholds[2]) return 'STRONG_BUY';
  if (met >= thresholds[1]) return 'BUY';
  if (met >= thresholds[0]) return 'HOLD';
  return 'HOLD';
}

function nasdaqSignal(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>
): AssetSignal {
  const reasons: string[] = [];
  let met = 0;
  const total = 5;

  const above200 = dv(derived, 'NASDAQ_ABOVE_200DMA');
  if (above200 === 0) { met++; reasons.push('200DMA 하회'); }

  const icsa = v(raw, 'ICSA');
  if (icsa !== null && icsa < 300000) { met++; reasons.push(`실업수당 ${Math.round(icsa / 1000)}K < 300K`); }

  const vix = v(raw, 'VIXCLS');
  if (vix !== null && vix > 30) { met++; reasons.push(`VIX ${vix.toFixed(1)} > 30`); }

  const disparity = dv(derived, 'NASDAQ_DISPARITY');
  if (disparity !== null && disparity < -15) { met++; reasons.push(`이격도 ${disparity.toFixed(1)}% < -15%`); }

  const fng = v(raw, 'FEAR_GREED');
  if (fng !== null && fng < 25) { met++; reasons.push(`F&G ${fng} < 25`); }

  if (icsa !== null && icsa >= 300000 && above200 === 0) {
    return {
      asset: 'NASDAQ',
      signal: 'SELL',
      conditionsMet: met,
      conditionsTotal: total,
      reasons: ['200DMA 하회 + 실업수당 30만 초과 → 구조적 위험'],
      date: new Date().toISOString().split('T')[0],
    };
  }

  return {
    asset: 'NASDAQ',
    signal: signalFromScore(met, total, [2, 3, 4]),
    conditionsMet: met,
    conditionsTotal: total,
    reasons,
    date: new Date().toISOString().split('T')[0],
  };
}

function goldSignal(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  profile: UserProfile
): AssetSignal {
  const reasons: string[] = [];
  let score = 0;
  const maxScore = 8;

  const realYield = dv(derived, 'REAL_YIELD');
  if (realYield !== null && realYield < 1.0) { score += 3; reasons.push(`실질금리 ${realYield.toFixed(2)}% 하락 추세`); }

  const dxy = v(raw, 'DXY');
  if (dxy !== null && dxy < 103) { score += 2; reasons.push(`DXY ${dxy.toFixed(1)} 약세`); }

  if (profile.manualInputs.cbBuying) { score += 1.5; reasons.push('중앙은행 매수 지속'); }

  if (profile.manualInputs.geoRisk >= 3) { score += 0.5; reasons.push('지정학 리스크 확대'); }

  const pct = (score / maxScore) * 100;

  if (realYield !== null && realYield > 2.0 && dxy !== null && dxy > 106) {
    return {
      asset: 'GOLD',
      signal: 'HOLD',
      conditionsMet: Math.round(score),
      conditionsTotal: 4,
      reasons: ['실질금리 상승 + DXY 강세 → 지정학만으로 매수 위험'],
      date: new Date().toISOString().split('T')[0],
    };
  }

  let signal: Signal;
  if (pct > 70) signal = 'STRONG_BUY';
  else if (pct > 50) signal = 'BUY';
  else if (pct > 30) signal = 'HOLD';
  else signal = 'REDUCE';

  return {
    asset: 'GOLD',
    signal,
    conditionsMet: Math.round(score),
    conditionsTotal: 4,
    reasons,
    date: new Date().toISOString().split('T')[0],
  };
}

function silverSignal(
  derived: Record<string, DerivedIndicator>,
  raw: Record<string, MarketDataPoint>
): AssetSignal {
  const reasons: string[] = [];
  let met = 0;
  const total = 2;

  const gsr = dv(derived, 'GOLD_SILVER_RATIO');
  if (gsr !== null && gsr >= 80) { met++; reasons.push(`금은비 ${gsr.toFixed(1)} ≥ 80`); }

  const icsa = v(raw, 'ICSA');
  if (icsa !== null && icsa < 250000) { met++; reasons.push('경기회복 신호 (실업수당 감소)'); }

  return {
    asset: 'SILVER',
    signal: met === 2 ? 'BUY' : 'HOLD',
    conditionsMet: met,
    conditionsTotal: total,
    reasons: reasons.length > 0 ? reasons : ['조건 미충족, 대기'],
    date: new Date().toISOString().split('T')[0],
  };
}

function copperSignal(
  derived: Record<string, DerivedIndicator>,
  raw: Record<string, MarketDataPoint>
): AssetSignal {
  const reasons: string[] = [];
  let met = 0;
  const total = 3;

  const icsa = v(raw, 'ICSA');
  if (icsa !== null && icsa < 250000) { met++; reasons.push('실업수당 감소 추세'); }

  const cgr = dv(derived, 'COPPER_GOLD_RATIO');
  if (cgr !== null) { reasons.push(`구리금비 ${cgr.toFixed(6)}`); }

  if (reasons.length === 0) reasons.push('조건 미충족, 대기');

  let signal: Signal;
  if (met >= 3) signal = 'STRONG_BUY';
  else if (met >= 2) signal = 'BUY';
  else signal = 'HOLD';

  return {
    asset: 'COPPER',
    signal,
    conditionsMet: met,
    conditionsTotal: total,
    reasons,
    date: new Date().toISOString().split('T')[0],
  };
}

function cashSignal(regime: RegimeState): AssetSignal {
  const map: Record<string, Signal> = {
    RECESSION_RISK: 'STRONG_BUY',
    CAUTION: 'BUY',
    NEUTRAL: 'HOLD',
    CORRECTION: 'REDUCE',
    PANIC_BUT_OK: 'REDUCE',
    RISK_ON: 'SELL',
  };

  const reasons: Record<string, string> = {
    RECESSION_RISK: '구조적 위험 → 현금 비중 극대화',
    CAUTION: '경계 → 현금 확보',
    NEUTRAL: '중립 → 현금 유지',
    CORRECTION: '조정 → 현금 투입 시작',
    PANIC_BUT_OK: '공포지만 펀더멘털 유지 → 적극 투입',
    RISK_ON: '위험선호 → 현금 최소화',
  };

  return {
    asset: 'CASH',
    signal: map[regime.regime] ?? 'HOLD',
    conditionsMet: 0,
    conditionsTotal: 0,
    reasons: [reasons[regime.regime] ?? ''],
    date: new Date().toISOString().split('T')[0],
  };
}

function leverageCheck(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>
): AssetSignal {
  const reasons: string[] = [];
  let met = 0;
  const total = 3;

  const disparity = dv(derived, 'NASDAQ_DISPARITY');
  if (disparity !== null && disparity <= -25) { met++; reasons.push(`이격도 ${disparity.toFixed(1)}% ≤ -25%`); }

  const vix = v(raw, 'VIXCLS');
  if (vix !== null && vix >= 35) { met++; reasons.push(`VIX ${vix.toFixed(1)} ≥ 35`); }

  const icsa = v(raw, 'ICSA');
  if (icsa !== null && icsa < 300000) { met++; reasons.push(`실업수당 ${Math.round(icsa / 1000)}K < 300K`); }

  return {
    asset: 'LEVERAGE',
    signal: met === 3 ? 'BUY' : 'HOLD',
    conditionsMet: met,
    conditionsTotal: total,
    reasons: met === 3
      ? [...reasons, '3조건 충족 → 2x ETF 최대 15% 허용']
      : [...reasons, `${3 - met}개 조건 미충족 → 레버리지 불허`],
    date: new Date().toISOString().split('T')[0],
  };
}

export function computeSignals(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  regime: RegimeState,
  profile: UserProfile
): AssetSignal[] {
  return [
    nasdaqSignal(raw, derived),
    goldSignal(raw, derived, profile),
    silverSignal(derived, raw),
    copperSignal(derived, raw),
    cashSignal(regime),
    leverageCheck(raw, derived),
  ];
}
