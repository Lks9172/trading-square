import fs from 'fs';
import path from 'path';
import { MarketDataPoint, DerivedIndicator, RegimeState, AssetSignal, Signal, UserProfile, LeverageTier } from '../types/indicators';

// === LEVERAGE 진입일 영속 저장 ===
// 영상1 §전략C "레버리지는 2~3개월 짧게, 20~30% 익절, 횡보 시 원금잠식 위험".
// 진입일(BUY 첫 발생일)을 파일로 기록해 경과일에 따라 경고 / 강제 REDUCE 처리.
// 볼륨(/app/data)에 저장되어 재시작에도 보존.
const LEVERAGE_ENTRY_FILE = path.resolve(process.cwd(), 'data', 'leverage-entry.json');
const LEVERAGE_WARN_DAYS = 60;
const LEVERAGE_FORCE_EXIT_DAYS = 90;

function readLeverageEntry(): string | null {
  try {
    const content = fs.readFileSync(LEVERAGE_ENTRY_FILE, 'utf-8');
    const parsed = JSON.parse(content);
    return typeof parsed.entryDate === 'string' ? parsed.entryDate : null;
  } catch {
    return null;
  }
}

function writeLeverageEntry(date: string) {
  try {
    fs.mkdirSync(path.dirname(LEVERAGE_ENTRY_FILE), { recursive: true });
    fs.writeFileSync(LEVERAGE_ENTRY_FILE, JSON.stringify({ entryDate: date }));
  } catch {
    /* 쓰기 실패는 조용히 무시 — 저장 실패로 신호 자체를 막지는 않음 */
  }
}

function clearLeverageEntry() {
  try { fs.unlinkSync(LEVERAGE_ENTRY_FILE); } catch { /* 파일 없을 때 무시 */ }
}

function daysBetween(a: string, b: string): number {
  return Math.floor((new Date(a).getTime() - new Date(b).getTime()) / 86400000);
}

function v(raw: Record<string, MarketDataPoint>, key: string): number | null {
  return raw[key]?.value ?? null;
}

function dv(derived: Record<string, DerivedIndicator>, key: string): number | null {
  return derived[key]?.value ?? null;
}

function disabledAssetSignal(asset: string, reason: string): AssetSignal {
  return {
    asset,
    signal: 'HOLD',
    conditionsMet: 0,
    conditionsTotal: 0,
    weightedScore: 0,
    weightedMaxScore: 0,
    reasons: [reason],
    unmetReasons: [],
    date: new Date().toISOString().split('T')[0],
    explanation: {
      baseSignal: 'HOLD',
      finalSignal: 'HOLD',
      overrides: [],
    },
  };
}

function withSignalExplanation(
  signal: AssetSignal,
  baseSignal: Signal,
  overrides: string[] = [],
): AssetSignal {
  return {
    ...signal,
    explanation: {
      baseSignal,
      finalSignal: signal.signal,
      overrides,
    },
  };
}

/**
 * 5단계 임계치를 모두 받아 signal 을 결정.
 *
 * 기존 구현은 `[hold, buy, strongBuy]` 3값만 받고 REDUCE/SELL 분기를 사실상 제거해
 * 두 분기 모두 `return 'HOLD'` 로 귀결시켰다(감사 Fix #1 지적). 그 결과 어떤 자산도
 * met=0 이어도 최악 HOLD 에 그쳐 REDUCE/SELL 이 영원히 미발생했다.
 *
 * 수정 방향: **호출부에서 각 자산의 실제 conditions total 에 맞춘 임계치를 명시**로 지정.
 * 표준 권고: `{ strongBuy: total, buy: total-2, hold: total-4, reduce: total-5, sell: 0 }`
 * 단, PRD 에 자산별 스펙이 있으면 우선.
 *
 * 판정 순서 (내림차순):
 *   met ≥ strongBuy → STRONG_BUY
 *   met ≥ buy       → BUY
 *   met ≥ hold      → HOLD
 *   met ≥ reduce    → REDUCE
 *   else            → SELL
 *
 * 주의: 모든 임계치는 strongBuy ≥ buy ≥ hold ≥ reduce ≥ sell 오름차순 가정.
 * 저점 REDUCE 오판을 피하기 위해 호출부에서 기존 HOLD 범위는 보수적으로 유지하고
 * 아주 낮은 met(≤ total-5 수준)만 REDUCE 로 강등한다.
 */
export interface SignalThresholds {
  sell: number;        // met < reduce 시 sell (default 0)
  reduce: number;      // reduce ≤ met < hold 시 REDUCE
  hold: number;        // hold ≤ met < buy 시 HOLD
  buy: number;         // buy ≤ met < strongBuy 시 BUY
  strongBuy: number;   // strongBuy ≤ met 시 STRONG_BUY
}

function signalFromScore(met: number, total: number, thresholds: SignalThresholds): Signal {
  if (met >= thresholds.strongBuy) return 'STRONG_BUY';
  if (met >= thresholds.buy) return 'BUY';
  if (met >= thresholds.hold) return 'HOLD';
  if (met >= thresholds.reduce) return 'REDUCE';
  return 'SELL';
}

/**
 * 29차 fix-A: 가중치 차등화 헬퍼 — addCondition 패턴.
 *
 * NASDAQ/KOSPI signal 의 모든 reason 가중치 1.0 동일 결함 발견. 영상 강조도 차이 미반영.
 * 본 헬퍼는 met (정수 카운트), total (정수 max), weighted (가중 합), weightedMax (가중 max),
 * reasons / unmetReasons 를 동기화 갱신한다. weight 인자로 영상 강조도 반영.
 *
 * 사용:
 *   addCondition(state, condition_met_bool, weight, reason_label_when_met, reason_label_when_unmet?)
 *
 * 영상 강조도 매트릭스:
 *   2.0 = 역사적 기회 / 3축 정합 (예: VIX_HISTORIC_BUY, RECOVERY_3축, KOSPI_RECOVERY_LEVEL=2)
 *   1.5 = 영상 핵심 분기 (예: 실업수당, VIX, 정책, DRAWDOWN_LEVEL, 외인 streak, USDKRW)
 *   1.0 = 영상 표준 조건 (예: 200DMA, 이격도 -10%, F&G, M2, RSI<30)
 *   0.7 = 영상 보조 (예: F&G, EARNINGS_BEAT, TIMEFRAME, ENTRY_QUINTILE, ROE)
 *   0.5 = 그 외 보조 / 참조 통계
 */
interface ConditionState {
  met: number;
  total: number;
  weighted: number;
  weightedMax: number;
  reasons: string[];
  unmetReasons: string[];
}

function addCondition(
  state: ConditionState,
  isMet: boolean,
  weight: number,
  metReason: string,
  unmetReason?: string,
): void {
  state.total += 1;
  state.weightedMax += weight;
  if (isMet) {
    state.met += 1;
    state.weighted += weight;
    state.reasons.push(`${metReason} (가중치 ${weight.toFixed(1)})`);
  } else if (unmetReason !== undefined) {
    state.unmetReasons.push(`${unmetReason} (가중치 ${weight.toFixed(1)} 미충족)`);
  }
}

/**
 * 가산 전용 (already-counted-in-total bonus) — addCondition 과 달리 total 은 증가시키지 않고
 * met/weighted 만 증가. 기존 met += 1 + reason.push 를 가중치 표기와 함께 일관화.
 */
function addBonus(
  state: ConditionState,
  weight: number,
  reason: string,
  metPoints: number = 1,
): void {
  state.met += metPoints;
  state.weighted += weight * metPoints;
  state.reasons.push(`${reason} (가중치 ${weight.toFixed(1)} 가산${metPoints !== 1 ? ` ×${metPoints}` : ''})`);
}

function softenRiskSignal(signal: Signal): Signal {
  if (signal === 'STRONG_BUY') return 'BUY';
  if (signal === 'BUY') return 'HOLD';
  if (signal === 'HOLD') return 'REDUCE';
  return signal;
}

/**
 * 29차 fix-F: 자산군 × regime 정합 매트릭스 — 영상 철학 게이트.
 *
 * video2 §03:35 자산 3분류: 안전(금) / 위험(주식) / 중간(은·구리)
 * video4 §"빨간불/노란불/초록불": regime 정합성 = 자산 신호의 절대 게이트
 * video1 §전략C: STRONG_BUY 는 시스템 위기 직후 V자 반등 시점만 (3-of-3 동시)
 *
 * 룰: regime 부적합 자산이 STRONG_BUY 도달 시 BUY 자동 강등.
 *     CASH 의 STRONG_BUY 는 RISK_ON/NEUTRAL 에서 자동 BUY 강등.
 */
type RegimeName = 'RISK_ON' | 'NEUTRAL' | 'CAUTION' | 'CORRECTION' | 'RECESSION_RISK'
  | 'STAGFLATION' | 'BOND_VIGILANTE' | 'STAGFLATION_BOND_VIGILANTE' | 'PANIC_BUT_OK';

const STRONG_BUY_REGIME_FIT: Record<string, RegimeName[]> = {
  // 위험자산: 우호 regime 에서만 STRONG_BUY 허용
  NASDAQ:   ['RISK_ON', 'NEUTRAL', 'PANIC_BUT_OK'],
  KOSPI:    ['RISK_ON', 'NEUTRAL', 'PANIC_BUT_OK'],
  EMERGING: ['RISK_ON', 'NEUTRAL'],
  LEVERAGE: ['RISK_ON', 'PANIC_BUT_OK'],
  COPPER:   ['RISK_ON', 'NEUTRAL'],          // 중간 — 경기회복 시
  SILVER:   ['RISK_ON', 'NEUTRAL', 'STAGFLATION'], // 중간 — 인플레/회복
  // 안전자산: 위기 regime 에서 STRONG_BUY 허용
  GOLD:     ['NEUTRAL', 'CAUTION', 'CORRECTION', 'RECESSION_RISK',
             'STAGFLATION', 'BOND_VIGILANTE', 'STAGFLATION_BOND_VIGILANTE', 'PANIC_BUT_OK'],
  CASH:     ['CAUTION', 'CORRECTION', 'RECESSION_RISK', 'STAGFLATION',
             'BOND_VIGILANTE', 'STAGFLATION_BOND_VIGILANTE'],
};

function applyRegimeCoherenceGate(
  asset: string,
  signal: Signal,
  regime: string,
  overrides: string[],
  unmetReasons: string[],
): Signal {
  if (signal !== 'STRONG_BUY') return signal;
  const fitRegimes = STRONG_BUY_REGIME_FIT[asset];
  if (!fitRegimes) return signal;
  if (!fitRegimes.includes(regime as RegimeName)) {
    const overrideReason =
      `regime ${regime} 에서 ${asset} STRONG_BUY 부적합 (video2 §03:35 자산분류 + video4 §"빨간불/노란불/초록불") → STRONG_BUY → BUY`;
    overrides.push(overrideReason);
    unmetReasons.push(overrideReason);
    return 'BUY';
  }
  return signal;
}

function nasdaqSignal(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  profile: UserProfile,
  regime: RegimeState,
): AssetSignal {
  // 영상1 §전략B "5기준" 을 카테고리 축으로 반영:
  //   1) 저점 (200DMA / 이격도 / VIX / ICSA / F&G)
  //   2) 유동성 (RRP 감소 · 글로벌 M2 확장)
  //   3) 정책 (완화 방향)
  //   4) 지정학 (GPR) — geoRisk 는 GOLD 에서 이미 주요 트리거로 사용 중이라 NASDAQ 에서는 보조만
  //   5) 섹터 (XLK 기술 주도) — 기존에도 보조조건으로 존재
  const reasons: string[] = [];
  const unmetReasons: string[] = [];
  let met = 0;
  // 29차 fix-H: total 은 0 에서 시작하고, 핵심 if/else 분기마다 total += 1 먼저 가산.
  //   이전 total=7 고정 + bonus met++ 패턴은 bonus 누적이 total cap 을 채워 미충족 조건이
  //   존재해도 "7/7 = 100%" 표시 — UI 분수 misleading.
  //   수정: 각 핵심 조건 평가 전 total++ → 미충족도 total 합산 → 분수 정합.
  //   PSYCH 보너스 기존 total++ 동기화 패턴은 그대로 유지.
  let total = 0;
  // 29차 fix-A part 2: 가중치 차등화 — weightedMet / weightedMax 추적.
  //   영상 강조도 매트릭스: 2.0=역사적기회, 1.5=영상핵심, 1.0=표준, 0.7=보조, 0.5=참조.
  //   met/total 정수 카운트는 유지 (baseSignal 임계치 계산에 사용).
  //   weightedScore != conditionsMet 확인으로 차등화 활성 검증.
  let weightedMet = 0;
  let weightedMax = 0;

  // --- 저점 카테고리 (5) ---
  const above200 = dv(derived, 'NASDAQ_ABOVE_200DMA');
  total += 1; weightedMax += 1.0;
  if (above200 === 0) { met++; weightedMet += 1.0; reasons.push('200DMA 하회 (가중치 1.0)'); }
  else { unmetReasons.push('200DMA 하회 아님 (가중치 1.0 미충족)'); }

  const icsa = v(raw, 'ICSA');
  total += 1; weightedMax += 1.5;
  if (icsa !== null && icsa < 300000) { met++; weightedMet += 1.5; reasons.push(`실업수당 ${Math.round(icsa / 1000)}K < 300K (가중치 1.5)`); }
  else { unmetReasons.push('실업수당 300K 미만 조건 미충족 (가중치 1.5 미충족)'); }

  const vix = v(raw, 'VIXCLS');
  total += 1; weightedMax += 1.5;
  if (vix !== null && vix > 30) { met++; weightedMet += 1.5; reasons.push(`VIX ${vix.toFixed(1)} > 30 (가중치 1.5)`); }
  else { unmetReasons.push('VIX 30 초과 조건 미충족 (가중치 1.5 미충족)'); }

  // 11차 이격도 계층화 (2026-04): 영상1 전략C "이격도 -25% 이하 = 평균회귀 최강" 정합.
  //   기존 -10% 저점 조건은 "관찰 시작" 레벨이라 영상 근거 약함. 극저점(-25%) 시 +1 추가
  //   가점으로 영상 정합 보강 (저점 확신도 차등화).
  const disparity = dv(derived, 'NASDAQ_DISPARITY');
  total += 1; weightedMax += 1.0;
  if (disparity !== null && disparity <= -25) {
    met += 2; weightedMet += 2.0;
    reasons.push(`✓ 이격도 ${disparity.toFixed(1)}% ≤ -25% (영상1 §전략C 극저점, 보너스 +1, 총 2점, 가중치 2.0)`);
  } else if (disparity !== null && disparity < -10) {
    met++; weightedMet += 1.0;
    reasons.push(`이격도 ${disparity.toFixed(1)}% < -10% (약한 저점 관찰, 가중치 1.0)`);
  } else if (disparity !== null) {
    unmetReasons.push(`이격도 ${disparity.toFixed(1)}% → -10% 미만 미충족 (가중치 1.0 미충족)`);
  } else {
    unmetReasons.push('이격도 데이터 없음 (가중치 1.0 미충족)');
  }

  const fng = v(raw, 'FEAR_GREED');
  total += 1; weightedMax += 0.7;
  if (fng !== null && fng < 25) { met++; weightedMet += 0.7; reasons.push(`F&G ${fng} < 25 (가중치 0.7)`); }
  else { unmetReasons.push('Fear & Greed 25 미만 조건 미충족 (가중치 0.7 미충족)'); }

  // Fix #4: PSYCH_SUBSCORE 소비 — F&G · PC Ratio 10D · AAII · NAAIM 가중평균이 극공포(≤0.2) 면 보너스.
  // Fix #3(2차 감사): met++ 만 하던 것을 total++ 와 동기화 — "추가 조건 충족" 으로 승격.
  //   → met/total 비율 정상 유지, 임계치는 total 상대값(아래)으로 재정의해 보너스가 임계 붕괴를 유발하지 않도록.
  const psych = dv(derived, 'PSYCH_SUBSCORE');
  if (psych !== null && psych <= 0.2) {
    met++;
    total++;
    weightedMet += 0.5;
    weightedMax += 0.5;
    reasons.push(`심리 서브스코어 ${psych.toFixed(2)} ≤ 0.20 → 극공포 저점 가점 (+1 조건 추가, 가중치 0.5)`);
  }

  // --- 유동성 카테고리 (1) ---
  // 단일 RRP tick 보다 완만한 유동성 종합점수 우선. 하루 내 잦은 flicker 를 줄이기 위해
  total += 1; weightedMax += 1.0;
  // RRP/TGA/MMF/WRESBAL 평균 변화 + 글로벌 M2 를 합성한 LIQUIDITY_DIRECTION 을 본다.
  const liquidityDir = dv(derived, 'LIQUIDITY_DIRECTION');
  const rrpDir = dv(derived, 'RRP_DIRECTION');
  const globalM2 = dv(derived, 'GLOBAL_M2_PROXY');
  const liquidityExpanding = liquidityDir !== null && liquidityDir >= 1;
  const rrpLoosening = rrpDir !== null && rrpDir <= -1;
  const m2Expanding = globalM2 !== null && globalM2 > 0;
  if (liquidityExpanding || rrpLoosening || m2Expanding) {
    met++; weightedMet += 1.0;
    reasons.push(
      `유동성 확장 (${liquidityExpanding ? `종합점수 ${liquidityDir?.toFixed(0)}` : ''}` +
      `${(liquidityExpanding && (rrpLoosening || m2Expanding)) ? ' · ' : ''}` +
      `${rrpLoosening ? `RRP ${rrpDir?.toFixed(1)}%` : ''}` +
      `${(rrpLoosening && m2Expanding) ? ' · ' : ''}` +
      `${m2Expanding ? `글로벌 M2 YoY ${globalM2?.toFixed(1)}%` : ''}, 가중치 1.0)`
    );
  } else {
    unmetReasons.push(`유동성 확장 미충족 (종합 ${liquidityDir?.toFixed(0) ?? '?'}, RRP ${rrpDir?.toFixed(1) ?? '?' }%, M2 ${globalM2?.toFixed(1) ?? '?'}%, 가중치 1.0 미충족)`);
  }

  // --- 정책 카테고리 (1) ---
  // 완화 방향 (policyDirection > 0: 금리인하·QE 기조) 이면 위험자산 우호.
  total += 1; weightedMax += 1.5;
  const policy = profile.manualInputs?.policyDirection ?? 0;
  if (policy > 0) {
    met++; weightedMet += 1.5;
    reasons.push(`정책 완화 방향 (policyDirection=${policy}, 가중치 1.5)`);
  } else {
    unmetReasons.push(`정책 완화 미충족 (policyDirection=${policy}, 가중치 1.5 미충족)`);
  }

  const cross = dv(derived, 'NASDAQ_CROSS');
  if (cross === -1) {
    reasons.push('✓ 데드크로스 발생 → video3 §역발상 "공포 극점 = 분할매수 시작 구간" (보조조건 +1, 가중치 0.7)');
    met += 1; weightedMet += 0.7; weightedMax += 0.7;
  }
  else if (cross === 1) { unmetReasons.push('⚠️ 골든크로스 발생 → video3 §역발상 "이미 20-30% 오른 후 늦은 신호, 추격매수 주의" (보조조건)'); }
  else if (cross === -0.5) { reasons.push('역배열 유지 (50DMA < 200DMA, 보조조건)'); }

  const chaseNasdaq = dv(derived, 'CHASE_NASDAQ');
  if (chaseNasdaq !== null && chaseNasdaq > 15) { unmetReasons.push(`⚠️ 나스닥 20일 +${chaseNasdaq.toFixed(1)}% → 추격매수 주의 (보조조건)`); }

  // ★ === 29차 P2-B #12: BREAKOUT_CHASE_RISK ===
  // video2 §24:31-25:16 "박스권 돌파 후 V자 직행 거의 없음 / 추격매수 시 고점 물림".
  const breakoutChase = dv(derived, 'BREAKOUT_CHASE_RISK');
  if (breakoutChase === 1) {
    unmetReasons.push('⚠️ 60D 고점 break 후 5거래일 내 가격 ≈ break (±1%) — 추격 금지 플래그 (video2 §24:31)');
  }

  // ★ === 29차 P2-C #13: NASDAQ_RESISTANCE_REJECTION_LEVEL — overheat flag ===
  // video3 §12:20-12:38 "윗꼬리 비율 상승 + 고점 근접 = 저항 거부".
  const resistanceLvl = dv(derived, 'NASDAQ_RESISTANCE_REJECTION_LEVEL');
  if (resistanceLvl === 2) {
    unmetReasons.push('⚠️ NASDAQ 저항 거부 강 (윗꼬리 Δ≥1.0 + 고점 근접, video3 §12:20)');
  } else if (resistanceLvl === 1) {
    unmetReasons.push('⚠️ NASDAQ 저항 거부 (윗꼬리 Δ≥0.5 + 고점 근접, video3 §12:20)');
  }

  // ★ === 29차 P2-C #14: NASDAQ_LONG_POSITION_PRESSURE — overheat flag ===
  // video3 §11:14-11:30 "지지선 이탈 0회 = 롱 무게 → 단기 급락 위험".
  const longPosPressure = dv(derived, 'NASDAQ_LONG_POSITION_PRESSURE');
  if (longPosPressure === 2) {
    unmetReasons.push('⚠️ NASDAQ 채널 하단 250일 무이탈 — 단기 급락 위험 (video3 §11:14)');
  }

  // ★ === 29차 P2-C #15: NASDAQ_W_BOTTOM_CONFIRMED ===
  // video3 §15:02-15:55 — 3축 게이트 (RSI↑ + swing high break + W 패턴) → +2 (STRONG_BUY 가산).
  const wConfirmed = dv(derived, 'NASDAQ_W_BOTTOM_CONFIRMED');
  if (wConfirmed === 2) {
    total += 2; met += 2; weightedMet += 2.0; weightedMax += 2.0;
    reasons.push('✓ W_BOTTOM_CONFIRMED 3축 (RSI↑ + swing break + W 패턴, video3 §15:02, 가중치 2.0)');
  }

  // ★ === 29차 P2-C #19: EARNINGS_BEAT_RATIO_4Q ===
  // video6 §05:00 — megacap 어닝 평균 surprise.
  const earningsRatio = dv(derived, 'EARNINGS_BEAT_RATIO_4Q');
  if (earningsRatio === 1) {
    total += 1; met += 1; weightedMet += 0.7; weightedMax += 0.7;
    reasons.push('✓ 어닝 우호 — 평균 surprise ≥ +5% (video6 §05:00, 가중치 0.7)');
  } else if (earningsRatio === -1) {
    weightedMax += 0.7;
    unmetReasons.push('⚠️ 어닝 부정 — 평균 surprise ≤ -5% (video6 §05:00, 가중치 0.7 미충족)');
  }

  // ★ === 29차 P2-C #20: MULTIPLE_RATE_DECOUPLING_FLAG ===
  // video6 §08:21 — PER ↑ + DGS10 ↑ → 디커플 (멀티플 축소 부재).
  const multiDecouple = dv(derived, 'MULTIPLE_RATE_DECOUPLING_FLAG');
  if (multiDecouple === 1) {
    unmetReasons.push('⚠️ PER+DGS10 디커플 (멀티플 축소 부재) — video6 §08:21');
  }

  // ★ === 29차 P2-D #25: VIX_HISTORIC_BUY_OPPORTUNITY ===
  // video6 §10:36 "VIX 80 = 10년 만의 매수 기회".
  const vixHistoric = dv(derived, 'VIX_HISTORIC_BUY_OPPORTUNITY');
  if (vixHistoric !== null && vixHistoric >= 2) {
    total += 2; met += 2; weightedMet += 2.0; weightedMax += 2.0;
    reasons.push(`✓ VIX_HISTORIC_BUY level=${vixHistoric} — video6 §10:36 "10년 매수 기회" (가중치 2.0)`);
  }

  // ★ === 29차 P2-D #27: RETAIL_PANIC_SELL ===
  // video1 §03:06 "2020.3 저점 직후 한 달 개인 순매도 역대 최대" — 역발상 매수 신호.
  const retailPanic = dv(derived, 'RETAIL_PANIC_SELL');
  if (retailPanic === 1) {
    total += 1; met += 1; weightedMet += 0.7; weightedMax += 0.7;
    reasons.push('✓ RETAIL_PANIC_SELL — 개인 패닉 매도 환경 (역발상 매수, video1 §03:06, 가중치 0.7)');
  }

  // ★ === 29차 P2-D #24: NASDAQ_RSI_OVERSOLD_DURATION_DAYS ===
  // video6 §10:22 "RSI<30 14일+ → 추세 약함, 분할매수 정지".
  const rsiDuration = dv(derived, 'NASDAQ_RSI_OVERSOLD_DURATION_DAYS');
  if (rsiDuration !== null && rsiDuration >= 14) {
    unmetReasons.push(`⚠️ RSI<30 ${rsiDuration}일 연속 — 추세 약함, 분할매수 정지 (video6 §10:22)`);
  }

  // ★ === 29차 P2-E #35: INSIDER_CLUSTER_PURCHASES_COUNT_50K (OpenInsider) ===
  // 노션 §OpenInsider — cluster 종목 수 ≥ 5 → +1 (역발상 buy 환경).
  const oiCluster = dv(derived, 'INSIDER_CLUSTER_PURCHASES_COUNT_50K');
  if (oiCluster === 2) {
    total += 2; met += 2; weightedMet += 1.4; weightedMax += 1.4;
    reasons.push('✓ OpenInsider $50K↑ cluster ≥10 종목 — 역발상 buy 환경 강 (노션 §OpenInsider, 가중치 0.7×2)');
  } else if (oiCluster === 1) {
    total += 1; met += 1; weightedMet += 0.7; weightedMax += 0.7;
    reasons.push('✓ OpenInsider $50K↑ cluster ≥5 종목 — 역발상 buy 환경 (노션 §OpenInsider, 가중치 0.7)');
  }

  const xlk = dv(derived, 'SECTOR_XLK');
  if (xlk !== null && xlk > 0) { reasons.push(`XLK 기술섹터 +${xlk.toFixed(1)}% → 성장주 랠리 질 양호 (보조조건)`); }
  else if (xlk !== null && xlk < 0) { unmetReasons.push(`XLK 기술섹터 ${xlk.toFixed(1)}% → 성장주 주도력 약함 (보조조건)`); }

  // 14차 Phase B-2: W 반등 (video3 "W자 반등 저점 확인 후 진입")
  const wBottom = dv(derived, 'NASDAQ_W_BOTTOM');
  if (wBottom === 1) {
    reasons.push('✓ NASDAQ_W_BOTTOM 감지 — 이중 저점 확인 후 반등 구조 (video3 분할매수 3차 타이밍, 가중치 1.0)');
    total += 1; met += 1; weightedMet += 1.0; weightedMax += 1.0;
  }

  // 15차 Phase 2 B3 + 23차 Tier 1#1: NASDAQ_DRAWDOWN_ATH 매수 본진 -34% 까지 확장
  // video1 §"2020.3 -34% / 2022 -33% 펀더멘털 살아있는 기회" + §"-55% 시스템 위기".
  // 기회 구간: -20% ~ -34% (영상 명시 본진), 위험 구간: -34% 초과.
  const nqDd = dv(derived, 'NASDAQ_DRAWDOWN_ATH');
  if (nqDd !== null && nqDd <= -20 && nqDd >= -34) {
    reasons.push(`✓ NASDAQ ATH 대비 ${nqDd.toFixed(1)}% 조정 (video1 §1부 "2020.3 -34% / 2022 -33% 본진 기회 구간", 가중치 1.5)`);
    total += 1; met += 1; weightedMet += 1.5; weightedMax += 1.5;
  } else if (nqDd !== null && nqDd < -34 && nqDd >= -55) {
    weightedMax += 1.5;
    unmetReasons.push(`⚠️ NASDAQ ATH 대비 ${nqDd.toFixed(1)}% — 영상 본진(-30~-34%) 이탈, 시스템 위기(-55%) 경계 (가중치 1.5 미충족)`);
  } else if (nqDd !== null && nqDd < -55) {
    weightedMax += 1.5;
    unmetReasons.push(`🚨 NASDAQ ATH 대비 ${nqDd.toFixed(1)}% — video1 §"-55% 시스템 위기" 진입 (가중치 1.5 미충족)`);
  }

  // 15차 Phase 1 A1: NASDAQ_RSI_14 (video2 §22:51 RSI 중립/모멘텀 평가)
  const rsi = dv(derived, 'NASDAQ_RSI_14');
  if (rsi !== null) {
    if (rsi < 30) {
      reasons.push(`✓ NASDAQ RSI ${rsi.toFixed(1)} < 30 과매도 — video2 §RSI 평균회귀 매수 구간 (가중치 1.0)`);
      total += 1; met += 1; weightedMet += 1.0; weightedMax += 1.0;
    } else if (rsi > 70) {
      unmetReasons.push(`⚠️ NASDAQ RSI ${rsi.toFixed(1)} > 70 과매수 — 추격 매수 주의`);
    }
  }

  // 15차 Phase 3 B1: BTC 위험선호 reason 로만 (NASDAQ signal 참고 정보, allocation 은 별개)
  const btcMom = dv(derived, 'BTC_MOMENTUM');
  if (btcMom !== null && btcMom <= -20) {
    unmetReasons.push(`⚠️ BTC 20D ${btcMom.toFixed(1)}% 급락 — 위험회피 신호 (video4 proxy, 보조조건)`);
  } else if (btcMom !== null && btcMom >= 20) {
    unmetReasons.push(`⚠️ BTC 20D +${btcMom.toFixed(1)}% 급등 — 위험선호 극대, 과열 힌트 (video4)`);
  }

  // 13차 N8: DMA 수렴 (video3 §수렴 "폭발 직전")
  const dmaConv = dv(derived, 'DMA_CONVERGENCE_LEVEL');
  if (dmaConv !== null) {
    if (dmaConv === 2) reasons.push('🟢 DMA 극수렴 (CV≤1.5%, video3 "폭발 직전" — 진입 타이밍 관찰)');
    else if (dmaConv === 1) reasons.push('DMA 수렴 (CV≤3%, video3 에너지 응축)');
    else if (dmaConv === -2) reasons.push('DMA 극확산 (CV>8%, 강추세 진행중)');
  }
  // 13차 A7/A3 가점: 반대 방향 (overheat 플래그는 하단 override 블록에서 처리)
  {
    const econDiv = dv(derived, 'ECONOMY_STOCK_DIVERGENCE');
    const wtiCuLag = dv(derived, 'WTI_COPPER_LAG_LEVEL');
    if (econDiv === 1) {
      reasons.push('✓ ECONOMY_STOCK_DIVERGENCE 회복 저점 (ISM≥50 + 이격도<-10%) — 매수 기회 (가중치 1.0)');
      total += 1; met += 1; weightedMet += 1.0; weightedMax += 1.0;
    }
    if (wtiCuLag === 1) {
      reasons.push('✓ WTI_COPPER_LAG 회복 조기 (유가 과거 약세 → 구리 현재 강세, video2 §3부, 가중치 1.0) +1');
      total += 1; met += 1; weightedMet += 1.0; weightedMax += 1.0;
    }
  }

  // 12차 N3: 전략B 5가지 겹침 가점 — video1 "확신 깊이 최대"
  // 차트(200DMA↓ or 이격도<-10%) + 유동성(확장) + 정책(완화) + 지정학(낮음) + 모멘텀(XLK>0)
  {
    const stratChart = (above200 === 0) || (disparity !== null && disparity < -10);
    const stratLiq = liquidityExpanding || rrpLoosening || m2Expanding;
    const stratPolicy = (profile.manualInputs?.policyDirection ?? 0) > 0;
    const stratGeo = (profile.manualInputs?.geoRisk ?? 3) < 3;
    const stratMomentum = xlk !== null && xlk > 0;
    const stratCount =
      (stratChart ? 1 : 0) + (stratLiq ? 1 : 0) + (stratPolicy ? 1 : 0) +
      (stratGeo ? 1 : 0) + (stratMomentum ? 1 : 0);
    if (stratCount === 5) {
      reasons.push('🟢🟢 video1 §전략B 5가지 동시 충족 (차트+유동성+정책+지정학+모멘텀) — 확신 깊이 최대, 보너스 +1 (가중치 0.5)');
      total += 1; met += 1; weightedMet += 0.5; weightedMax += 0.5;
    } else if (stratCount >= 4) {
      reasons.push(`video1 §전략B 4/5 충족 — 관찰 수준 (보조조건)`);
    }
  }

  // 12차 N5: F&G 5단계 tier 매핑 — 노션 대시보드 정합 (극공포/공포/중립/탐욕/극탐욕)
  // 기존 <25 met++ 와 중복 피하기 위해 tier -1 (공포) 구간만 추가 가점.
  const fngTier = dv(derived, 'FNG_TIER');
  if (fngTier === -1) {
    reasons.push('F&G 공포 구간 (tier -1, 25-44) — 매수 기회 보조조건 +1 (가중치 0.7)');
    met += 1; weightedMet += 0.7; weightedMax += 0.7;
  } else if (fngTier === 1) {
    unmetReasons.push('⚠️ F&G 탐욕 구간 (tier +1, 56-74) — 신규 매수 주의 (보조조건)');
  }

  // 멀티 타임프레임 경고 (영상5 패턴 — 매수 신호 감쇠 요소)
  const mtfExhaustion = dv(derived, 'NASDAQ_MONTHLY_EXHAUSTION');
  const mtfReversal = dv(derived, 'NASDAQ_WEEKLY_REVERSAL');
  const mtfMonthPos = dv(derived, 'NASDAQ_MONTH_POS');
  if (mtfExhaustion === 1) { unmetReasons.push('⚠️ 월봉 소진 경고: 3개월 연속 장대양봉 + 아래꼬리 없음 (보조조건)'); }
  if (mtfReversal === 1) { unmetReasons.push('⚠️ 주봉 반전 경고: 상승 추세 후 장대음봉 (보조조건)'); }
  if (mtfMonthPos !== null && mtfMonthPos >= 95) { unmetReasons.push(`⚠️ 월봉 위치 ${mtfMonthPos.toFixed(0)}% → 12개월 고점 근처, 추격매수 주의 (보조조건)`); }
  else if (mtfMonthPos !== null && mtfMonthPos <= 15) { reasons.push(`월봉 위치 ${mtfMonthPos.toFixed(0)}% → 저점권, 분할매수 구간 (보조조건)`); }

  // ★ === 29차 P1-B #4: RECOVERY_TRIPLE_SIGNAL — 3축 회복 가산 ===
  // video2 §13:42-13:49 "ISM 반등 + 금구리비 하락 전환 + ICSA 감소" 동시 충족 시 +1.
  const recoveryLvl = dv(derived, 'RECOVERY_TRIPLE_SIGNAL');
  if (recoveryLvl !== null && recoveryLvl >= 2) {
    reasons.push('✓ 회복 3축 충족 (video2 §13:42, 가중치 2.0)');
    total += 1; met += 1; weightedMet += 2.0; weightedMax += 2.0;
  } else if (recoveryLvl !== null && recoveryLvl >= 1) {
    reasons.push('✓ 회복 2축 (video2 §13:42 보조, 가중치 1.0)');
    weightedMet += 1.0; weightedMax += 1.0;
  }

  // ★ === 29차 P1-C #9: NASDAQ_HEALTHY_PULLBACK — 정배열 분할매수 가산 ===
  // video3 §05:12-05:25 "정배열에서 50DMA -3~-8% pullback".
  const healthyPullback = dv(derived, 'NASDAQ_HEALTHY_PULLBACK');
  if (healthyPullback === 1) {
    reasons.push('✓ 정배열 healthy pullback (video3 §05:12, 가중치 1.0)');
    total += 1; met += 1; weightedMet += 1.0; weightedMax += 1.0;
  } else if (healthyPullback === -1) {
    unmetReasons.push('⚠️ 역배열 + 50DMA pullback — 대기 (video3 §05:25)');
  }

  if (icsa !== null && icsa >= 300000 && above200 === 0) {
    return withSignalExplanation({
      asset: 'NASDAQ',
      signal: 'SELL',
      conditionsMet: met,
      conditionsTotal: total,
      weightedScore: weightedMet,
      weightedMaxScore: weightedMax,
      reasons: ['200DMA 하회 + 실업수당 30만 초과 → 구조적 위험'],
      unmetReasons,
      date: new Date().toISOString().split('T')[0],
    }, 'SELL');
  }

  // 29차 fix-E: baseSignal 결정 위치 정합성 — 신규 P1/P2 가산 (RECOVERY_TRIPLE,
  //   HEALTHY_PULLBACK, TIMEFRAME_DECISION_SPLIT, EARNINGS_BEAT_RATIO 등) 이 met 표시값만
  //   늘리고 signal 결정에 못 끼친 결함 발견. 기존 baseSignal 결정 (L455~462) + 모든 override
  //   분기 (overheat REDUCE / CHASE_LEVEL / TRIPLE_GATE) 를 cap 직후 (L746) 로 이동.
  //   흐름: 모든 met/total 가산 → 과열/조정 플래그 수집 → cap → baseSignal 결정 → override 분기.
  const overrides: string[] = [];

  // === Fix #2 + 13차 옵션 D 재설계 (2026-04): 과열 REDUCE override ===
  //
  // 영상1 §전략C: "경제 펀더멘털 살아있는 상태 -30% = 위기 아닌 기회"
  // 영상3: "200일선 아래 + 실업수당 20만대 = 분할매수 구간"
  //
  // 이전 구현 버그:
  //   (1) 이격도 음수(저점) 구간에서도 과열 플래그 누적 시 REDUCE 강등 → 영상 원칙 위반
  //   (2) INSTITUTIONAL_NASDAQ_FLOW (메가캡 7종) + TECH_SECTOR_FLOW (테크 5종) 대부분
  //       같은 종목 중복 → 1개 시그널이 2개로 카운트되어 임계 쉽게 도달
  //   (3) 기관 매도는 "이미 진행된 조정 원인"인데 "고점 경고"로 오용 (후행 → 선행 오해)
  //
  // 재설계 원칙:
  //   A) **저점 가드**: 이격도 < -5% 면 과열 REDUCE 블록 전면 보류.
  //      대신 기관/거시 플래그는 "관찰 reason" 으로만 기록 (정보 보존).
  //   B) **13F 중복 통합**: INSTITUTIONAL_NASDAQ_FLOW + TECH_SECTOR_FLOW 를 "기관 집단"
  //      단일 플래그로 통합. 둘 중 하나만 -1 이어도 1회 카운트, 둘 다 -2 면 "강한".
  //   C) **가격/심리 과열 vs 기관 플래그 분리**:
  //      - "진짜 과열 플래그" (이격도/F&G/VIX/CHASE): 2개↑ 발동 시 REDUCE
  //      - "조정-확인 플래그" (기관/괴리/꼬리): 저점 구간(< -5%)에서는 관찰 reason 만
  const disparityIsLow = disparity !== null && disparity < -5;

  // --- 진짜 과열 플래그 (이격도/F&G/VIX/CHASE) — 저점 구간과 무관 ---
  const overheatFlags: string[] = [];
  if (disparity !== null && disparity >= 25) overheatFlags.push(`이격도 +${disparity.toFixed(1)}% ≥ 25%`);
  if (fng !== null && fng >= 85) overheatFlags.push(`F&G ${fng} ≥ 85 극탐욕`);
  if (vix !== null && vix < 16) overheatFlags.push(`VIX ${vix.toFixed(1)} < 16 방심`);
  // 27차 Phase 1#4: NASDAQ_LONGTERM_CHANNEL_RETURN ≥150 — video3 §11:03 "153% 역사상 최대"
  const longChannelRet = dv(derived, 'NASDAQ_LONGTERM_CHANNEL_RETURN');
  if (longChannelRet !== null && longChannelRet >= 150) {
    overheatFlags.push(`5년 저점 +${longChannelRet.toFixed(0)}% ≥ 150% (video3 §"153% 역사상 최대" 임박)`);
  }
  // 28차 영상6 #1: NASDAQ_RISK_REWARD_RATIO ≤ 0.5 (1:2+ 추격 위험) overheat 가산
  // video6 §"오를 폭 < 빠질 폭"
  const rrRatio = dv(derived, 'NASDAQ_RISK_REWARD_RATIO');
  if (rrRatio !== null && rrRatio <= 0.5) {
    const rrLabel = rrRatio > 0 ? `1:${(1/rrRatio).toFixed(1)}` : '1:∞ (upside 0)';
    overheatFlags.push(`손익비 ${rrLabel} 추격 위험 (video6 §"오를 폭 < 빠질 폭")`);
    unmetReasons.push(`⚠️ 손익비 ${rrLabel} ≤ 1:2 — video6 §"오를 폭 < 빠질 폭" 추격 매수 주의 (보조조건)`);
  }
  // RR ≥ 3 시 BUY 가산 (저점 기회)
  if (rrRatio !== null && rrRatio >= 3 && disparity !== null && disparity < 0) {
    met += 1; total += 1; weightedMet += 1.0; weightedMax += 1.0;
    reasons.push(`✓ 손익비 1:${rrRatio.toFixed(1)} 우호 + 이격 음수 (video6 §"손익비 본진", +1, 가중치 1.0)`);
  }
  const chaseWarning = dv(derived, 'NASDAQ_CHASE_WARNING');
  if (chaseWarning === 1) overheatFlags.push('CHASE_WARNING (이격률 ±15% 20일 지속)');

  // ★ === 29차 P1-E #15: TIMEFRAME_DECISION_SPLIT — daily/weekly axis 정합 ===
  // video2 §22:45 + video6 — horizon 별 daily/weekly 정합 평가.
  const timeframeSplit = dv(derived, 'TIMEFRAME_DECISION_SPLIT');
  if (timeframeSplit === 1) {
    reasons.push('✓ TIMEFRAME_DECISION_SPLIT=+1 (horizon 정합, video2 §22:45, 가중치 0.7)');
    total += 1; met += 1; weightedMet += 0.7; weightedMax += 0.7;
  } else if (timeframeSplit === -1) {
    unmetReasons.push('⚠️ TIMEFRAME_DECISION_SPLIT=-1 (horizon 미정합, video2 §22:45, 가중치 0.7 미충족)');
    total += 1; met = Math.max(0, met - 1); weightedMax += 0.7;
  }

  // ★ === 29차 P1-D #11: NASDAQ_FORWARD_PER overheat / met 가산 ===
  // video6 §05:54 "PER 25+ 멀티플 과열 / 12 이하 매수 우호".
  const fwdPER = dv(derived, 'NASDAQ_FORWARD_PER');
  if (fwdPER !== null && fwdPER >= 25) {
    overheatFlags.push(`PER ${fwdPER.toFixed(1)}+ 멀티플 과열 (video6 §"좋은 가격")`);
  }
  if (fwdPER !== null && fwdPER <= 12) {
    reasons.push(`✓ PER ${fwdPER.toFixed(1)} ≤ 12 매수 우호 (video6 §05:54, 가중치 1.0)`);
    total += 1; met += 1; weightedMet += 1.0; weightedMax += 1.0;
  }

  // ★ === 29차 P1-C #7+#8+#10: NASDAQ 차트 패턴 overheat 가산 ===
  const wkBearAtSupport = dv(derived, 'NASDAQ_WEEKLY_BEAR_STREAK_AT_SUPPORT');
  if (wkBearAtSupport !== null && wkBearAtSupport >= 2) {
    overheatFlags.push(`주봉 음봉 streak + 지지 근접 (video3 §11:31, level=${wkBearAtSupport})`);
  }
  const rangeTrap = dv(derived, 'NASDAQ_RANGE_TRAP');
  if (rangeTrap !== null && rangeTrap >= 2) {
    overheatFlags.push(`RANGE_TRAP 감지 (60D high 돌파 후 5일 내 60D low 이탈, video3 §12:54, level=${rangeTrap})`);
  }
  const doubleTop = dv(derived, 'NASDAQ_DOUBLE_TOP');
  if (doubleTop !== null && doubleTop >= 2) {
    overheatFlags.push(`이중천정 + 주봉 20MA 이탈 (video3 §13:38)`);
  } else if (doubleTop === 1) {
    overheatFlags.push(`이중천정 패턴 감지 (video3 §13:38)`);
  }

  // --- 조정-확인 플래그 (기관/거시) — 저점 구간에서는 관찰만 ---
  const confirmFlags: string[] = [];
  // 13F: NASDAQ_FLOW 와 TECH_SECTOR_FLOW 통합 (대부분 같은 종목 — 중복 방지)
  const instFlow = dv(derived, 'INSTITUTIONAL_NASDAQ_FLOW');
  const techFlow = dv(derived, 'INSTITUTIONAL_SECTOR_TECH_FLOW');
  const instCombined = Math.min(instFlow ?? 0, techFlow ?? 0); // 더 강한 신호 채택
  if (instCombined <= -1) {
    const magnitude = instCombined === -2 ? '강한 ' : '';
    confirmFlags.push(
      `기관 집단 ${magnitude}tech 감축 (NASDAQ_FLOW ${instFlow ?? 'n/a'} / TECH_FLOW ${techFlow ?? 'n/a'}, 13F, video4)`,
    );
  }
  // 구리-주식 괴리 (bearish)
  const copperStockDiv = dv(derived, 'COPPER_STOCK_DIVERGENCE');
  if (copperStockDiv === -1) {
    confirmFlags.push('COPPER_STOCK_DIVERGENCE bearish (구리 선행 하락, video2 §3)');
  }
  // TAIL_RISK
  const tailRisk = dv(derived, 'TAIL_RISK_LEVEL');
  if (tailRisk !== null && tailRisk >= 2) {
    confirmFlags.push('TAIL_RISK_LEVEL 고위험 (SKEW/VVIX/OVX 중 2개 이상 과열, video4)');
  }
  // HY 5-7% 주의
  const hyRaw = v(raw, 'BAMLH0A0HYM2');
  if (hyRaw !== null && hyRaw >= 5 && hyRaw < 8) {
    confirmFlags.push(`HY OAS ${hyRaw.toFixed(1)}% — 노션 "5-7% 주의" 구간 신용 스트레스`);
  }
  // ECONOMY_STOCK_DIVERGENCE (이건 원래 "이격도>+10% + ISM<50" 이라 저점 구간 발동 불가)
  const econStockDiv = dv(derived, 'ECONOMY_STOCK_DIVERGENCE');
  if (econStockDiv === -1) {
    confirmFlags.push('ECONOMY_STOCK_DIVERGENCE 유동성 왜곡 (ISM<50 + 이격도>+10%, video4)');
  }
  // WTI-COPPER lag
  const wtiCopperLag = dv(derived, 'WTI_COPPER_LAG_LEVEL');
  if (wtiCopperLag === -1) {
    confirmFlags.push('WTI_COPPER_LAG 둔화 임박 (유가 과거 급등 → 구리 현재 약세, video2 §3부)');
  }

  // --- 판정 ---
  // 저점 구간 (이격도 < -5%): confirmFlags 는 관찰 reason 으로만, 과열 REDUCE 블록
  //   (기관 매도는 이미 진행된 조정의 원인 → "저점 근접" 신호로 재해석)
  // 비저점 구간: confirmFlags 도 overheatFlags 에 합산, 2개↑ 발동 시 REDUCE
  if (disparityIsLow && confirmFlags.length > 0) {
    reasons.push(
      `ℹ️ 조정-확인 플래그 ${confirmFlags.length}개 (이격도 ${disparity!.toFixed(1)}% 저점 구간 — ` +
      `영상1 "펀더멘털 살아있는 -30% = 기회" 정합, REDUCE 보류): ${confirmFlags.join(' · ')}`,
    );
  } else if (!disparityIsLow) {
    overheatFlags.push(...confirmFlags);
  }

  // ★ === 29차 P3-C #14: COPPER_LEAD_DIVERGENCE_60D — 60D 명시 보강 ===
  const copperLead60 = dv(derived, 'COPPER_LEAD_DIVERGENCE_60D');
  if (copperLead60 === 1) {
    total += 1; met += 1; weightedMet += 1.0; weightedMax += 1.0;
    reasons.push('✓ 구리 60D ≥+5% + S&P 횡보 — 회복 선행 (video2 §13:08, 가중치 1.0)');
  } else if (copperLead60 === -1) {
    unmetReasons.push('⚠️ 구리 60D ≤-3% + S&P 양수 — 경고 선행 (video2 §13:08)');
  }

  // ★ === 29차 P3-C #16: ENTRY_TIMING_QUINTILE ===
  const entryQ = dv(derived, 'ENTRY_TIMING_QUINTILE');
  if (entryQ === 1) {
    total += 1; met += 1; weightedMet += 0.7; weightedMax += 0.7;
    reasons.push('✓ ENTRY_TIMING_QUINTILE=하위 20% (매수 우호, video1 §01:24 "타이밍이 목적지 결정", 가중치 0.7)');
  } else if (entryQ === -1) {
    unmetReasons.push('⚠️ ENTRY_TIMING_QUINTILE=상위 20% (추격 주의, video1 §01:24)');
  }

  // ★ === 29차 P3-C #17: NASDAQ_15Y_CHANNEL_POSITION ===
  const ch15y = dv(derived, 'NASDAQ_15Y_CHANNEL_POSITION');
  if (ch15y !== null) {
    if (ch15y <= -2) {
      total += 1; met += 1; weightedMet += 1.0; weightedMax += 1.0;
      reasons.push('✓ NASDAQ 15Y 채널 -1σ 이하 (구조적 매수 강, video3 §09:48, 가중치 1.0)');
    } else if (ch15y === -1) {
      reasons.push('✓ NASDAQ 15Y 채널 -1σ~0 (보조, video3 §09:48)');
    } else if (ch15y === 2) {
      unmetReasons.push('⚠️ NASDAQ 15Y 채널 +2σ 초과 (구조적 과열, video3 §09:48)');
    }
  }

  // ★ === 29차 P3-C #19: NASDAQ_PIN_BAR_NEXT_YEAR_BULLISH_RATE — 참조 통계 가산 ===
  const pinRate = dv(derived, 'NASDAQ_PIN_BAR_NEXT_YEAR_BULLISH_RATE');
  if (pinRate !== null && pinRate >= 0.7) {
    total += 0.5; met += 0.5; weightedMet += 0.5; weightedMax += 0.5;
    reasons.push(`✓ yearly pin bar 다음 해 양봉 ${(pinRate * 100).toFixed(0)}% (참조 통계, video3 §09:09 "100%", 가중치 0.5)`);
  }

  // ★ === 29차 P3-E #30: EARNINGS_SURPRISE_AGGREGATE_FLAG ===
  const earningsAgg = dv(derived, 'EARNINGS_SURPRISE_AGGREGATE_FLAG');
  if (earningsAgg === 1) {
    total += 1; met += 1; weightedMet += 0.7; weightedMax += 0.7;
    reasons.push('✓ 메가캡 평균 surprise ≥+5% — NASDAQ 우호 (video4 §06, 가중치 0.7)');
  } else if (earningsAgg === -1) {
    unmetReasons.push('⚠️ 메가캡 평균 surprise ≤-5% — NASDAQ 위협 (video4 §06)');
  }

  // ★ === 29차 P3-D #22: EVENT_DAY_VOLATILITY_GUARD — 신규 매수 보류 ===
  const eventGuard = dv(derived, 'EVENT_DAY_VOLATILITY_GUARD');
  if (eventGuard === 1) {
    unmetReasons.push('🛑 EVENT_DAY_VOLATILITY_GUARD=1 — CPI/FOMC 발표일 (video6 §08:18, 신규 매수 보류)');
  }

  // ★ === 29차 P3-D #23: ALGO_VOLATILITY_AMPLIFY_FLAG — 신규 매수 보류 ===
  const algoVol = dv(derived, 'ALGO_VOLATILITY_AMPLIFY_FLAG');
  if (algoVol === 1) {
    unmetReasons.push('🛑 ALGO_VOLATILITY_AMPLIFY_FLAG=1 — 알고 변동성 증폭 (일중 ≥3% + 거래량 1.5x, video6 "알고리즘·퀀트")');
  }

  // ★ === 29차 P3-D #25: POLICY_SECTOR_LIFT_PCT — 정책 효과 확인 ===
  const policyLift = dv(derived, 'POLICY_SECTOR_LIFT_PCT');
  if (policyLift === 2) {
    reasons.push('✓ POLICY_SECTOR_LIFT ≥+10% — 정책 효과 강 (video6 §"정책 = 보이는 손")');
  } else if (policyLift === -1) {
    unmetReasons.push('⚠️ POLICY_SECTOR_LIFT < 0% — 정책 무력 경고 (video6)');
  }

  // ★ === 29차 P3-A #5: JGB_10Y_LEVEL — 캐리 트레이드 unwind 위험 ===
  // video6 §04:28 "일본 금리 체크" — JGB ↑ 시 NASDAQ 보조 -0.5.
  const jgbLvl = dv(derived, 'JGB_10Y_LEVEL');
  if (jgbLvl !== null) {
    if (jgbLvl <= -2) {
      // -1 met 차감 (가점이 아니라 헤드윈드 인식)
      met = Math.max(0, met - 1);
      const reason = `⚠️ JGB10Y ≥1.5% — 엔 캐리 unwind 강 (NASDAQ 위험, video6 §04:28, met -1)`;
      unmetReasons.push(reason);
    } else if (jgbLvl === -1) {
      const reason = `⚠️ JGB10Y ≥1.0% — 캐리 unwind 위험 (video6 §04:28, 보조)`;
      unmetReasons.push(reason);
    } else if (jgbLvl === 1) {
      reasons.push('✓ JGB10Y < 0.5% — 캐리 우호 (video6 §04:28, 보조)');
    }
  }

  // 23차 Tier 1#3: NASDAQ signal met/total 정합 cap.
  //   기존 met++ 만 하는 가점들 (NASDAQ_DRAWDOWN, RSI<30, 이격도 -25%, W_BOTTOM, econDiv, wtiCuLag 등) 이
  //   total 동기화 안 돼 met/total > 100% 가능. PSYCH 외 가점은 "강도 가산"으로 보고 met cap = total.
  //   비율 신뢰성 확보 + STRONG_BUY 자동 도달 차단.
  if (met > total) met = total;
  // 29차 fix-A part 2: weightedMet cap — weightedMax 초과 방지.
  if (weightedMet > weightedMax) weightedMet = weightedMax;

  // 29차 fix-E: baseSignal 결정 — 모든 met/total 가산 + cap 후 호출.
  //   19차: NASDAQ STRONG_BUY 임계 1단 상향 — BUY 영역 1 met → 2 met 확보.
  //     기존 {strongBuy: total-2, buy: total-3} 는 BUY 영역이 1 met 폭만 차지해 HOLD→STRONG_BUY 직행 자주 발생.
  //     변경 {strongBuy: total-1, buy: total-3} → base(total=7) 기준 BUY=4~5 (2 met), STRONG_BUY=6~7 (만점 근접).
  //     PSYCH 보너스로 total=8 이 되면 +1 shift 동일.
  const baseSignal = signalFromScore(met, total, {
    sell: 0,
    reduce: Math.max(0, total - 5),
    hold: Math.max(0, total - 4),
    buy: Math.max(0, total - 3),
    strongBuy: Math.max(1, total - 1),
  });
  let signal = baseSignal;

  // === Override 1: 과열 REDUCE (overheatFlags 2개+) ===
  if (overheatFlags.length >= 2 && signal !== 'SELL') {
    const previous = signal;
    signal = 'REDUCE';
    const overrideReason = `과열 REDUCE override: ${overheatFlags.join(' · ')} (${previous} → REDUCE)`;
    overrides.push(overrideReason);
    unmetReasons.push(overrideReason);
  }

  // === Override 2: 9차 후속 Fix #2: NASDAQ_CHASE_LEVEL 계층화 ===
  //   level 0 → no-op
  //   level 1 (soft): reason 경고만, 신호 불변
  //   level 2 (medium): STRONG_BUY → BUY (한 단계 강등)
  //   level 3 (hard): STRONG_BUY/BUY → HOLD (기존 CHASE_WARNING 동등)
  //   level null: 기존 binary CHASE_WARNING 로직 유지 (하위 호환).
  const chaseLevel = dv(derived, 'NASDAQ_CHASE_LEVEL');
  if (chaseLevel !== null) {
    const levelReason = derived.NASDAQ_CHASE_LEVEL?.formula ?? '';
    if (chaseLevel >= 3 && (signal === 'STRONG_BUY' || signal === 'BUY')) {
      const previous = signal;
      signal = 'HOLD';
      const overrideReason = `CHASE_LEVEL=3 (hard): ${levelReason} (${previous} → HOLD)`;
      overrides.push(overrideReason);
      unmetReasons.push(overrideReason);
    } else if (chaseLevel >= 2 && signal === 'STRONG_BUY') {
      const previous = signal;
      signal = 'BUY';
      const overrideReason = `CHASE_LEVEL=2 (medium): ${levelReason} (${previous} → BUY)`;
      overrides.push(overrideReason);
      unmetReasons.push(overrideReason);
    } else if (chaseLevel >= 1) {
      unmetReasons.push(`CHASE_LEVEL=1 (soft): ${levelReason} — 관측 경고 (신호 불변)`);
    }
  }

  // === Override 3: 29차 P1-A #3 INVESTMENT_TRIPLE_GATE_SCORE 게이트 ===
  // video6 §04:48 "펀더 × 매크로 × 차트 3축 일치" — 3축 스코어 ≥0.66 시 STRONG_BUY 통과,
  // <0 시 STRONG_BUY → BUY 강등, ≤-0.33 시 STRONG_BUY 차단 + BUY → HOLD 강등.
  const tripleGate = dv(derived, 'INVESTMENT_TRIPLE_GATE_SCORE');
  if (tripleGate !== null) {
    if (tripleGate <= -0.33) {
      if (signal === 'STRONG_BUY' || signal === 'BUY') {
        const previous = signal;
        signal = 'HOLD';
        const overrideReason = `TRIPLE_GATE ${tripleGate.toFixed(2)} ≤ -0.33 (3축 분기, video6 §04:48) → ${previous} → HOLD`;
        overrides.push(overrideReason);
        unmetReasons.push(overrideReason);
      }
    } else if (tripleGate < 0.33) {
      // 29차 fix-F: 임계 < 0 → < 0.33 강화. 0.33 미만은 3축 정합 부족 → STRONG_BUY 차단.
      if (signal === 'STRONG_BUY') {
        const previous = signal;
        signal = 'BUY';
        const overrideReason = `TRIPLE_GATE ${tripleGate.toFixed(2)} < 0.33 (3축 정합 부족, video6 §04:48) → STRONG_BUY → BUY`;
        overrides.push(overrideReason);
        unmetReasons.push(overrideReason);
      }
    } else if (tripleGate >= 0.66) {
      reasons.push(`✓ TRIPLE_GATE ${tripleGate.toFixed(2)} ≥ 0.66 (3축 정합, video6 §04:48 "펀더×매크로×차트")`);
    }
  }

  // === Override 4: 29차 fix-F — 자산군 × regime 정합 게이트 ===
  signal = applyRegimeCoherenceGate('NASDAQ', signal, regime.regime, overrides, unmetReasons);

  return withSignalExplanation({
    asset: 'NASDAQ',
    signal,
    conditionsMet: met,
    conditionsTotal: total,
    weightedScore: weightedMet,
    weightedMaxScore: weightedMax,
    reasons,
    unmetReasons,
    date: new Date().toISOString().split('T')[0],
  }, baseSignal, overrides);
}

function goldSignal(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  profile: UserProfile,
  regime: RegimeState,
): AssetSignal {
  // 18차 P2#8: 금·은·구리 우선순위 주석 (video2 §1부 "우선순위").
  //   1) 실질금리 하락 (가중치 3.0) — 최상위 전제. video2: "실질금리 깨면 다른 지표 의미 약함".
  //   2) DXY 약세 (가중치 2.0) — 2순위. 실질금리와 동조 시 강세 시너지.
  //   3) 중앙은행 구조적 수요 (가중치 1.5) — 3순위 구조적 뒷받침.
  //   4) 지정학 (보조조건만) — 4순위. video2: "전쟁=금 상승 단순공식은 깨짐" → 가중치 없이 보조.
  // 총합 가중치 6.5 + seasonal/RSI 보조 ≒ maxScore 8 (+0.5) 로 3순위까지 정렬.
  const reasons: string[] = [];
  const unmetReasons: string[] = [];
  let score = 0;
  let metCount = 0;
  // 29차 fix-H: conditionsTotal 을 0 에서 시작, 핵심 if/else 분기마다 condTotal += 1 먼저 가산.
  //   이전 conditionsTotal: 4 고정 은 미충족 조건이 있어도 동일 — UI 분수 정합.
  let condTotal = 0;
  // 23차 Tier 1#2: maxScore 정합 — 메인 가중치 합 = 3(real_yield) + 2(dxy) + 1.5(cb) + 0.5(geo) = 7.
  // seasonal/RSI/FIB 발동 시 +0.5 씩 max 8.5 까지 확장. 발동 안 하면 maxScore 7 유지 (이전 8 영구 cap 해소).
  let maxScore = 7;

  const realYield = dv(derived, 'REAL_YIELD');
  const ryTrend = dv(derived, 'REAL_YIELD_TREND');
  const ryFalling = ryTrend !== null ? ryTrend < -0.05 : (realYield !== null && realYield < 1.0);
  const ryLabel = ryTrend !== null ? `추세 ${ryTrend.toFixed(3)}` : (realYield !== null ? `절대값 ${realYield.toFixed(2)}% (추세 데이터 없어 1.0% 기준 fallback)` : '데이터 없음');
  condTotal += 1;
  if (ryFalling) { score += 3; metCount += 1; reasons.push(`실질금리 하락 확인 (${ryLabel}, 가중치 3.0)`); }
  else { unmetReasons.push(`실질금리 하락 미충족 (${ryLabel}, 가중치 3.0 미충족)`); }

  const dxy = v(raw, 'DXY');
  const dxyTrend = dv(derived, 'DXY_TREND');
  const dxyTrendLong = dv(derived, 'DXY_TREND_LONG');
  const dxyWeak = dxyTrend !== null ? dxyTrend < -0.5 : (dxy !== null && dxy < 103);
  condTotal += 1;
  if (dxyWeak) { score += 2; metCount += 1; reasons.push(`DXY ${dxy?.toFixed(1) ?? '?'} (단기: ${dxyTrend?.toFixed(2) ?? '?'}, 장기: ${dxyTrendLong?.toFixed(2) ?? '?'}, 약세, 가중치 2.0)`); }
  else { unmetReasons.push(`DXY 약세 추세 미충족 (단기: ${dxyTrend?.toFixed(2) ?? '?'}, 장기: ${dxyTrendLong?.toFixed(2) ?? '?'}, 가중치 2.0 미충족)`); }
  if (dxyTrendLong !== null && dxyTrendLong < -2) { reasons.push('DXY 구조적 약세 확인 → 금 장기 우호 (보조조건)'); }

  // 12차 N2: CB_GOLD_STRUCTURAL_DEMAND 자동 proxy 도입 — manual cbBuying 보완.
  //   manual=true OR proxy=1 → 가점. proxy 자체 근거 reason 에 표기.
  const cbProxy = dv(derived, 'CB_GOLD_STRUCTURAL_DEMAND');
  const cbActive = profile.manualInputs.cbBuying || cbProxy === 1;
  condTotal += 1;
  if (cbActive) {
    score += 1.5; metCount += 1;
    const source = profile.manualInputs.cbBuying ? 'manual' : 'proxy(12M 금↑+DXY↓+실질금리↓)';
    reasons.push(`중앙은행 매수 지속 (${source}, 가중치 1.5)`);
  } else {
    unmetReasons.push('중앙은행 매수 지속 아님 (manual + proxy 모두 미발동, 가중치 1.5 미충족)');
  }

  // 12차 N1: 금의 계절성 — video2 §4부 보조조건
  const seasonal = dv(derived, 'GOLD_SEASONAL');
  if (seasonal === 1) reasons.push('✓ 금 강시즌 (20년 기준 상위 4개월, video2 §4부 보조조건 +0.5)');
  else if (seasonal === -1) unmetReasons.push('⚠️ 금 약시즌 (20년 기준 하위 4개월, video2 §4부 보조조건)');
  if (seasonal === 1) { score += 0.5; maxScore += 0.5; }

  // 15차 Phase 1 A1 + Phase 2 A2: GOLD RSI + 피보나치 되돌림
  const goldRsi = dv(derived, 'GOLD_RSI_14');
  if (goldRsi !== null) {
    if (goldRsi < 35) {
      reasons.push(`✓ GOLD RSI ${goldRsi.toFixed(1)} 과매도 근접 (video2 §22:51 모멘텀 매수 구간)`);
      score += 0.5; maxScore += 0.5;
    } else if (goldRsi > 70) {
      unmetReasons.push(`⚠️ GOLD RSI ${goldRsi.toFixed(1)} 과매수 — 추격 매수 주의`);
    }
  }
  const goldFib = dv(derived, 'GOLD_FIB_LEVEL');
  if (goldFib !== null && goldFib >= 2) {
    reasons.push('✓ GOLD 피보나치 주요 지지 근접 (0.5 또는 0.618, video2 §23:34 분할매수 구간)');
    score += 0.5; maxScore += 0.5;
  } else if (goldFib !== null && goldFib === -2) {
    unmetReasons.push('⚠️ GOLD 피보나치 0.618 하방 이탈 — 약세 전환 가능');
  }

  condTotal += 1;
  if (profile.manualInputs.geoRisk >= 3) { score += 0.5; metCount += 1; reasons.push('지정학 리스크 확대 (가중치 0.5)'); }
  else { unmetReasons.push('지정학 리스크 확대 조건 미충족 (가중치 0.5 미충족)'); }

  const goldDisparity = dv(derived, 'GOLD_DISPARITY');
  if (goldDisparity !== null && goldDisparity <= -10) {
    reasons.push(`금 200DMA 근처 (이격도 ${goldDisparity.toFixed(1)}%) → 분할매수 유리 구간 (보조조건)`);
  } else if (goldDisparity !== null && goldDisparity > 15) {
    unmetReasons.push(`금 200DMA 대비 +${goldDisparity.toFixed(1)}% → 추격매수 주의 (보조조건)`);
  }

  const chaseGold = dv(derived, 'CHASE_GOLD');
  if (chaseGold !== null && chaseGold > 15) { unmetReasons.push(`⚠️ 금 20일 +${chaseGold.toFixed(1)}% → 추격매수 주의 (보조조건)`); }

  // 8차 TOP7 Fix #3: GOLD_PRIORITY_SCORE 소비 (derived 2축 + manual 2축 재가산)
  const goldPriority = dv(derived, 'GOLD_PRIORITY_SCORE');
  if (goldPriority !== null && goldPriority >= 0.7) {
    reasons.push(`금 우선순위 스코어 ${goldPriority.toFixed(2)} ≥ 0.7 → 실질금리·DXY 2축 금 매수 강화 (보조조건)`);
  } else if (goldPriority !== null && goldPriority <= 0.3) {
    unmetReasons.push(`금 우선순위 스코어 ${goldPriority.toFixed(2)} ≤ 0.3 → 금 우호 축 부족 (보조조건)`);
  }

  // 27차 Phase 1#1+#3+#5: 26차 신규 derived 의 goldSignal 가중치 합류
  // GOLD_COPPER_RATIO ≥ 200 (경기 둔화 / 위험 회피) — video2 §3부 정합
  const goldCopper = dv(derived, 'GOLD_COPPER_RATIO');
  if (goldCopper !== null && goldCopper >= 200) {
    score += 0.5; maxScore += 0.5;
    reasons.push(`✓ GOLD/COPPER ${goldCopper.toFixed(0)} ≥ 200 → 경기 둔화 / 위험 회피 우세 (video2 §3부, +0.5)`);
  }
  // GOLD_YEARLY_RETURN_HISTORICAL_RANK ≥ 3 (역대 3위 73%+) — video2 §16:54
  const goldRank = dv(derived, 'GOLD_YEARLY_RETURN_HISTORICAL_RANK');
  if (goldRank !== null && goldRank >= 3) {
    score += 0.5; maxScore += 0.5;
    reasons.push(`✓ 금 연봉 역대 3위 73%+ 진입 (video2 §16:54 "1979 130/1973 90/2024 73", +0.5)`);
  }
  // GOLD_LONGTERM_CUP_HANDLE = 2 (rim 재탈환 + handle 돌파, video2 §18:20 13년)
  const goldCup = dv(derived, 'GOLD_LONGTERM_CUP_HANDLE');
  if (goldCup === 2) {
    score += 1.0; maxScore += 1.0;
    reasons.push('✓ 금 13년 컵앤핸들 완성 (rim+handle 돌파, video2 §18:20 +1.0)');
  } else if (goldCup === 1) {
    score += 0.5; maxScore += 0.5;
    reasons.push('🔵 금 cup rim 재탈환 — handle 대기 (+0.5)');
  }

  const pct = (score / maxScore) * 100;

  // ★ === 29차 P2-A #1: GOLD_PANIC_BUY_TRIGGER 가산 ===
  // video2 §04:27 "공황 초기엔 금도 같이 빠진다 — 거시환경 우호면 그때가 매수 기회".
  const goldPanicBuy = dv(derived, 'GOLD_PANIC_BUY_TRIGGER');
  if (goldPanicBuy === 1) {
    score += 1; maxScore += 1; metCount += 1;
    reasons.push('✓ 공황 초기 동반 하락 매수 (video2 §04:27)');
  }

  // ★ === 29차 P2-A #2: GOLD_PINBAR_SEQUENCE — overheat flag ===
  // video2 §20:01-20:43 "윗·아래꼬리 핀바 연속 = 천장 경계".
  const goldPinbar = dv(derived, 'GOLD_PINBAR_SEQUENCE');
  if (goldPinbar === 1) {
    unmetReasons.push('⚠️ 핀바 연속 시퀀스 — 방향 혼란 박스권 (video2 §20:01)');
  }

  // ★ === 29차 P2-A #3: GOLD_WEDGE_PATTERN ===
  // video2 §22:24-22:41 "쐐기 — 상단/하단 추세선 한 점 수렴".
  const goldWedge = dv(derived, 'GOLD_WEDGE_PATTERN');
  if (goldWedge === 1) {
    score += 1; maxScore += 1;
    reasons.push('✓ 쐐기 상단 돌파 (video2 §22:24)');
  } else if (goldWedge === -1) {
    unmetReasons.push('⚠️ 쐐기 하단 이탈 (video2 §22:24)');
  }

  // ★ === 29차 P2-A #4: GOLD_BREAK_VOLUME_CONFIRM ===
  // video2 §25:21-25:42 "거래량 없이 빠지면 가짜 이탈".
  const goldBreakVol = dv(derived, 'GOLD_BREAK_VOLUME_CONFIRM');
  if (goldBreakVol === 1) {
    score += 0.5; maxScore += 0.5;
    reasons.push('✓ 박스권 break 거래량 확인 (video2 §25:21)');
  } else if (goldBreakVol === -1) {
    unmetReasons.push('⚠️ 박스권 break — 거래량 미확인 (가짜 이탈, video2 §25:21)');
  }

  // ★ === 29차 P2-A #5: GOLD_DXY_DECOUPLE ===
  // video2 §04:54-04:57 "달러 강한데 금이 안 빠지면 = 구조적 수요".
  const goldDxyDecouple = dv(derived, 'GOLD_DXY_DECOUPLE');
  if (goldDxyDecouple === 1) {
    score += 1; maxScore += 1; metCount += 1;
    reasons.push('✓ DXY 강세 + 금 상승 디커플 — 구조적 수요 (video2 §04:54)');
  }

  // ★ === 29차 P2-B #10: DOLLAR_STRUCTURAL_DIRECTION — 약달러 시 +1 ===
  // video2 §04:51-05:30 "트럼프 = 상대적 약달러".
  const dollarDir = dv(derived, 'DOLLAR_STRUCTURAL_DIRECTION');
  if (dollarDir === -1) {
    score += 1; maxScore += 1; metCount += 1;
    reasons.push('✓ 구조적 약달러 환경 — 금 우호 (video2 §04:51)');
  } else if (dollarDir === 1) {
    unmetReasons.push('⚠️ 구조적 강달러 환경 — 금 역풍 (video2 §04:51)');
  }

  // ★ === 29차 P2-B #11: CB_GOLD_TONNAGE_TREND ===
  // video2 §05:38-05:48 "전 세계 중앙은행 3년 연속 1000톤+".
  const cbTonnage = dv(derived, 'CB_GOLD_TONNAGE_TREND');
  if (cbTonnage === 2) {
    score += 1; maxScore += 1;
    reasons.push('✓ CB 12M 금 매입 ≥1100톤 — 구조적 매수 가속 (video2 §05:38)');
  } else if (cbTonnage === 1) {
    score += 0.5; maxScore += 0.5;
    reasons.push('✓ CB 12M 금 매입 ≥1000톤 — 3년 연속 트렌드 (video2 §05:38)');
  } else if (cbTonnage === -1) {
    unmetReasons.push('⚠️ CB 12M 금 매입 <800톤 — 구조적 매수 둔화 (video2 §05:38)');
  }

  // ★ === 29차 P3-A #1: FX_RESERVE_USD_RATIO — 탈달러 시 금 우호 가산 ===
  // video2 §09:04 "탈달러 71% → 58% 20년".
  const fxResUsd = dv(derived, 'FX_RESERVE_USD_RATIO');
  if (fxResUsd !== null && fxResUsd >= 2) {
    score += 2; maxScore += 2;
    reasons.push('✓ FX_RESERVE_USD_RATIO ≤55% — 탈달러 가속 강 (video2 §09:04, +2)');
  } else if (fxResUsd === 1) {
    score += 1; maxScore += 1;
    reasons.push('✓ FX_RESERVE_USD_RATIO ≤60% — 탈달러 진행 (video2 §09:04, +1)');
  } else if (fxResUsd === -1) {
    unmetReasons.push('⚠️ FX_RESERVE_USD_RATIO ≥65% — 달러 패권 회복 (video2 §09:04)');
  }

  // ★ === 29차 P3-A #6: DXY_12M_YOY 단독 (보조) ===
  const dxy12m = dv(derived, 'DXY_12M_YOY');
  if (dxy12m === 1) {
    score += 0.5; maxScore += 0.5;
    reasons.push('✓ DXY 12M YoY ≤-5% — 약달러 우호 (video2, +0.5)');
  } else if (dxy12m === -1) {
    unmetReasons.push('⚠️ DXY 12M YoY ≥+5% — 강달러 위협 (video2)');
  }

  // ★ === 29차 P1-B #6: GOLD_AXIS_GATE_FLAG — HEADWIND 시 지정학 단독 매수 차단 ===
  // video2 §10:48 "1·2순위 (실질금리·DXY) NG 시 추격 금지".
  // gateFlag = -1 (HEADWIND) AND 지정학(rank4) 단독 우호 시 reason 가산 (실제 강등은 baseSignal 결정 후 override 단계에서).
  const goldGateFlag = dv(derived, 'GOLD_AXIS_GATE_FLAG');
  if (goldGateFlag === -1) {
    unmetReasons.push('⚠️ GOLD_AXIS_GATE_FLAG=HEADWIND (실질금리↑+DXY↑ 1·2순위 NG) — video2 §10:48 추격 금지');
  } else if (goldGateFlag === 1) {
    reasons.push('✓ GOLD_AXIS_GATE_FLAG=STRONG_TAILWIND (1·2순위 OK, video2 §09:50)');
  }

  if (realYield !== null && realYield > 2.0 && dxy !== null && dxy > 106) {
    return withSignalExplanation({
      asset: 'GOLD',
      signal: 'HOLD',
      conditionsMet: metCount,
      conditionsTotal: condTotal,
      weightedScore: Number(score.toFixed(1)),
      weightedMaxScore: maxScore,
      reasons: ['실질금리 상승 + DXY 강세 → 지정학만으로 매수 위험'],
      unmetReasons,
      date: new Date().toISOString().split('T')[0],
    }, 'HOLD');
  }

  let signal: Signal;
  if (pct > 70) signal = 'STRONG_BUY';
  else if (pct > 50) signal = 'BUY';
  else if (pct > 30) signal = 'HOLD';
  else signal = 'REDUCE';
  const baseSignal = signal;
  const overrides: string[] = [];

  // ★ === 29차 P1-B #6: HEADWIND + 지정학 단독 매수 차단 override ===
  // video2 §10:48 "1·2순위 NG 시 지정학 단독 추격 금지"
  // 조건: HEADWIND (gateFlag=-1) AND 지정학/CB 만 우호 (rank3+rank4>0) AND 실질금리/DXY 약함.
  if (goldGateFlag === -1 && signal === 'STRONG_BUY') {
    const ryNg = ryTrend !== null && ryTrend > 0;
    const dxyNg = dxyTrend !== null && dxyTrend > 0;
    const geoOk = profile.manualInputs.geoRisk >= 3 || dv(derived, 'HORMUZ_CHAIN_SCORE') !== null;
    if (ryNg && dxyNg && geoOk) {
      signal = 'HOLD';
      const overrideReason = `⚠️ HEADWIND 추격 금지 (video2 §10:48 1·2순위 NG) — 지정학 단독 매수 차단 (STRONG_BUY → HOLD)`;
      overrides.push(overrideReason);
      reasons.push(overrideReason);
    }
  }

  const goldFibZone = dv(derived, 'GOLD_FIB_ZONE');
  if (signal === 'REDUCE' && goldFibZone !== null && goldFibZone >= 2) {
    signal = 'HOLD';
    const overrideReason = `피보나치 바닥권(구간 ${goldFibZone}) → 최소 HOLD 보장`;
    overrides.push(overrideReason);
    reasons.push(overrideReason);
  }
  if (signal === 'HOLD' && goldFibZone !== null && goldFibZone >= 3 && goldDisparity !== null && goldDisparity <= -15) {
    const hardMacroBlock = (realYield !== null && realYield > 2.5) && (dxy !== null && dxy > 106);
    if (!hardMacroBlock) {
      signal = 'BUY';
      const overrideReason = `강한 바닥권(피보 ${goldFibZone}, 이격도 ${goldDisparity.toFixed(1)}%) → BUY 승격`;
      overrides.push(overrideReason);
      reasons.push(overrideReason);
    }
  }

  // 29차 fix-B: GOLD weightedScore cap — score/maxScore 동시 증가 패턴이지만
  // 향후 신규 가산 도입 시 over-cap 방지 위해 명시 cap 일관 적용.
  if (score > maxScore) score = maxScore;

  // === 29차 fix-F — 자산군 × regime 정합 게이트 ===
  signal = applyRegimeCoherenceGate('GOLD', signal, regime.regime, overrides, unmetReasons);

  return withSignalExplanation({
    asset: 'GOLD',
    signal,
    conditionsMet: metCount,
    conditionsTotal: condTotal,
    weightedScore: Number(score.toFixed(1)),
    weightedMaxScore: maxScore,
    reasons,
    unmetReasons,
    date: new Date().toISOString().split('T')[0],
  }, baseSignal, overrides);
}

function silverSignal(
  derived: Record<string, DerivedIndicator>,
  raw: Record<string, MarketDataPoint>,
  regime: RegimeState,
): AssetSignal {
  const reasons: string[] = [];
  const unmetReasons: string[] = [];
  let met = 0;
  // 29차 fix-H: total 을 0 에서 시작, 핵심 if/else 분기마다 total += 1 먼저 가산.
  let total = 0;

  // 영상2 "금은비 60~80 이상이면 은 저평가 가능성 + 경기회복 동반 확인 필요".
  // 기존 임계 80은 상한선 기준이라 2021년 피크(~100) 같은 극단 구간에서만 점화.
  // 실제 영상 해석상 70 정도부터 시작해 저평가 신호로 간주 가능 → 70 메인,
  // 60~70 구간은 보조 가점.
  // 6차 TOP3 Step 1B — 이중 게이트: 금은비 단독은 침체 초입에도 점화하므로
  // 경기국면(ISM_PROXY 또는 regime) 동반 확인을 필수 게이트로 추가.
  const gsr = dv(derived, 'GOLD_SILVER_RATIO');
  const ismProxyForGate = dv(derived, 'ISM_PROXY') ?? v(raw, 'ISM_MANUFACTURING');
  const regimeOk = regime.regime === 'RISK_ON' || regime.regime === 'NEUTRAL';
  const ismOk = ismProxyForGate !== null && ismProxyForGate >= 50;
  const contextOk = ismOk || regimeOk;
  total += 1;
  if (gsr !== null && gsr >= 70 && contextOk) {
    met++;
    const ctxLabel = ismOk
      ? `ISM ${ismProxyForGate!.toFixed(1)} ≥ 50`
      : `regime ${regime.regime}`;
    reasons.push(`금은비 ${gsr.toFixed(1)} ≥ 70 + 경기국면 OK (${ctxLabel}, 가중치 1.0)`);
  } else if (gsr !== null && gsr >= 70 && !contextOk) {
    unmetReasons.push(
      `금은비 ${gsr.toFixed(1)} ≥ 70 이지만 침체구간 GSR 진입 억제 (ISM ${ismProxyForGate?.toFixed(1) ?? '?'} < 50 AND regime=${regime.regime})`,
    );
  } else if (gsr !== null && gsr >= 60) {
    reasons.push(`금은비 ${gsr.toFixed(1)} → 60~70 관찰 구간 (보조조건)`);
  } else {
    unmetReasons.push(`금은비 ${gsr?.toFixed(1) ?? '?'} < 70 미충족 (가중치 1.0 미충족)`);
  }

  const icsa = v(raw, 'ICSA');
  total += 1;
  if (icsa !== null && icsa < 250000) { met++; reasons.push('경기회복 신호 (실업수당 감소, 가중치 1.0)'); }
  else { unmetReasons.push('경기회복(실업수당 감소) 조건 미충족 (가중치 1.0 미충족)'); }

  const ismProxy = dv(derived, 'ISM_PROXY');
  const xli = dv(derived, 'SECTOR_XLI');
  let auxMet = 0;
  if (ismProxy !== null && ismProxy >= 50) { auxMet++; reasons.push(`ISM ${ismProxy.toFixed(1)} ≥ 50 → 은 산업수요 우호 (보조조건)`); }
  else if (ismProxy !== null) { unmetReasons.push(`ISM ${ismProxy.toFixed(1)} < 50 → 산업수요 약함 (보조조건)`); }
  if (xli !== null && xli > 0) { auxMet++; reasons.push(`XLI 산업재 +${xli.toFixed(1)}% → 경기회복 동반 (보조조건)`); }
  else if (xli !== null) { unmetReasons.push(`XLI 산업재 ${xli.toFixed(1)}% → 경기민감섹터 약세 (보조조건)`); }

  // 영상2 명시적 복합 플래그 (금은비 ≥70 + ISM ≥50) — STRONG_BUY 승격에 참고
  const outperformSetup = dv(derived, 'SILVER_OUTPERFORM_SETUP');
  if (outperformSetup === 1) reasons.push('은 아웃퍼폼 2조건 복합 (금은비≥70 + ISM≥50) 충족 — 영상2 명시 (보조조건)');

  // 27차 Phase 1#2: GOLD_SILVER_RATIO_HISTORICAL_BAND value=2 (GSR≥100 극단) 시 STRONG_BUY 가산
  // video2 §"코로나 130 → 4개월 150% 반등" 사례 정합 — 은 매수 강화
  // ★ 29차 P2-B #7: SILVER_GSR_SIGNAL_GUARD — 침체 regime 시 GSR_EXTREME 무력화.
  const gsrBand = dv(derived, 'GOLD_SILVER_RATIO_HISTORICAL_BAND');
  const gsrGuard = dv(derived, 'SILVER_GSR_SIGNAL_GUARD');
  if (gsrGuard === 1 && (gsrBand === 1 || gsrBand === 2)) {
    unmetReasons.push('⚠️ SILVER_GSR_SIGNAL_GUARD=1 (침체 regime) — GSR 극단 가산 무력화 (video2 §11:48)');
  } else if (gsrBand === 2) {
    auxMet += 2; // STRONG_BUY 승격 ladder 보강
    reasons.push('✓ GSR 100+ 극단 — video2 §"코로나 130→은 150%" 사례 구간 (auxMet +2, 27차)');
  } else if (gsrBand === 1) {
    auxMet += 1;
    reasons.push('🟡 GSR 80-100 금 우세 — 은 매수 우호 (auxMet +1, 27차)');
  }

  // ★ === 29차 P2-B #6: SILVER_OUTPERFORM_SETUP_V2 ===
  // video2 §11:32-11:48 — GSR≥60 + ISM≥50 + ISM 분기 추세 상승 → +1 (3축 환경).
  const silverV2 = dv(derived, 'SILVER_OUTPERFORM_SETUP_V2');
  if (silverV2 === 1) {
    met += 1;
    reasons.push('✓ 은 아웃퍼폼 V2 3축 환경 (video2 §11:32)');
  }

  // Fix #6(2차 감사): REDUCE 분기 복구 — 기존에는 met=0 이어도 HOLD 만 부여하여 약세 강등이 없었다.
  //   signalFromScore 로 전환: total=2 기준 {strongBuy:2, buy:1, hold:1, reduce:1, sell:0}.
  //   여기에 이중 게이트(aux 보강) 유지: 메인 풀 충족 시 aux 2+ → STRONG_BUY 승격,
  //   메인 1개 + aux 2+ → BUY 승격, aux 0 → BUY 차단 후 HOLD.
  const overrides: string[] = [];
  const baseSignal = signalFromScore(met, total, {
    sell: 0,
    reduce: 1,
    hold: total - 1, // 2-1=1
    buy: total,       // 2
    strongBuy: total, // 2 (기본)
  });
  let signal: Signal = baseSignal;
  // STRONG_BUY 승격은 aux 2+ 필요 (기존 이중 게이트 유지)
  if (met === 2 && auxMet >= 2) signal = 'STRONG_BUY';
  else if (met === 2) signal = 'BUY';
  else if (met === 1 && auxMet >= 2) {
    signal = 'BUY';
    const overrideReason = '메인 1개 + 보조 2개 충족 → BUY 승격 (보조조건)';
    overrides.push(overrideReason);
    reasons.push(overrideReason);
  }
  // met=0 이면 signalFromScore 결과(REDUCE 또는 SELL) 유지.

  if (auxMet === 0 && signal === 'BUY') {
    signal = 'HOLD';
    const overrideReason = '경기방향 보조조건 전부 미충족 → BUY 차단 (보조조건)';
    overrides.push(overrideReason);
    unmetReasons.push(overrideReason);
  }

  // 29차 fix-B: SILVER weightedScore cap — V2 등 신규 가산으로 met>total 가능 (over-cap 방지).
  if (met > total) met = total;

  // === 29차 fix-F — 자산군 × regime 정합 게이트 ===
  signal = applyRegimeCoherenceGate('SILVER', signal, regime.regime, overrides, unmetReasons);

  return withSignalExplanation({
    asset: 'SILVER',
    signal,
    conditionsMet: met,
    conditionsTotal: total,
    weightedScore: met,
    weightedMaxScore: total,
    reasons: reasons.length > 0 ? reasons : ['조건 미충족, 대기'],
    unmetReasons,
    date: new Date().toISOString().split('T')[0],
  }, baseSignal, overrides);
}

function copperSignal(
  derived: Record<string, DerivedIndicator>,
  raw: Record<string, MarketDataPoint>,
  profile: UserProfile,
  regime: RegimeState,
): AssetSignal {
  const reasons: string[] = [];
  const unmetReasons: string[] = [];
  let met = 0;
  // 29차 fix-H: total 을 0 에서 시작, 핵심 if/else 분기마다 total += 1 먼저 가산.
  let total = 0;

  const icsa = v(raw, 'ICSA');
  total += 1;
  if (icsa !== null && icsa < 250000) { met++; reasons.push('실업수당 감소 추세 (가중치 1.0)'); }
  else { unmetReasons.push('실업수당 감소 조건 미충족 (가중치 1.0 미충족)'); }

  const cgr = dv(derived, 'COPPER_GOLD_RATIO');
  // TODO(Fix #FE3): CGR > 0.00125 임계 근거 불명.
  //   영상2 원전은 "구리/금 비율 상승세" 만 언급 — 절대 0.00125 경계는 코드 상수로만 존재.
  //   후보 개선: (a) 90일 z-score > 0 으로 상대 정규화, (b) 60일 추세 기울기 > 0 으로 방향 기반.
  //   현 상수는 2015~2024 CGR 레벨 중앙값 근방이라 방향성은 얼추 맞지만 엄밀한 근거 없음.
  total += 1;
  if (cgr !== null && cgr > 0.00125) { met++; reasons.push(`구리금비 ${cgr.toFixed(6)} 상승 우위 (가중치 1.0)`); }
  else if (cgr !== null) { unmetReasons.push(`구리금비 ${cgr.toFixed(6)} 아직 약함 (가중치 1.0 미충족)`); }
  else { unmetReasons.push('구리금비 데이터 없음 (가중치 1.0 미충족)'); }

  const ismManual = profile.manualInputs.ismPmi;
  const ismAuto = dv(derived, 'ISM_PROXY');
  const ismValue = ismManual ?? ismAuto;
  total += 1;
  if (ismValue !== null && ismValue >= 50) { met++; reasons.push(`ISM ${ismValue.toFixed(1)} ≥ 50 확장 ${ismManual ? '(수동)' : '(자동)'} (가중치 1.0)`); }
  else if (ismValue !== null && ismValue >= 48) { reasons.push(`ISM ${ismValue.toFixed(1)} 바닥 근접 (보조조건)`); }
  else if (ismValue !== null) { unmetReasons.push(`ISM ${ismValue.toFixed(1)} 수축 구간 (가중치 1.0 미충족)`); }
  else { unmetReasons.push('ISM 데이터 없음 (가중치 1.0 미충족)'); }

  const xli = dv(derived, 'SECTOR_XLI');
  const xle = dv(derived, 'SECTOR_XLE');
  if (xli !== null && xli > 0) { reasons.push(`XLI 산업재 +${xli.toFixed(1)}% → 경기민감섹터 강세 (보조조건)`); }
  else if (xli !== null && xli < 0) { unmetReasons.push(`XLI 산업재 ${xli.toFixed(1)}% → 경기회복 신뢰 약화 (보조조건)`); }
  if (xle !== null && xle > 5) { unmetReasons.push(`XLE 에너지 +${xle.toFixed(1)}% → 유가/전쟁 주도 가능성 (보조조건)`); }

  // ★ === 29차 P1-B #4: RECOVERY_TRIPLE_SIGNAL — copperSignal 가산 ===
  // video2 §13:42 "회복 3가지" 정합 — 구리는 회복 트리거의 핵심 수혜자.
  const recoveryLvlCu = dv(derived, 'RECOVERY_TRIPLE_SIGNAL');
  if (recoveryLvlCu !== null && recoveryLvlCu >= 2) {
    reasons.push('✓ 회복 3축 충족 (video2 §13:42)');
    met += 1;
  }

  // ★ === 29차 P2-B #8: COPPER_TIMEFRAME_SPLIT 가산 ===
  // video2 §14:31-14:53 "장기 우상향 / 단기 인플레→에너지→제조 위축 역풍".
  const copperTimeframe = dv(derived, 'COPPER_TIMEFRAME_SPLIT');
  if (copperTimeframe !== null) {
    if (copperTimeframe >= 1) {
      met += 1;
      reasons.push(`✓ COPPER_TIMEFRAME_SPLIT=${copperTimeframe} (장기 우호 우세, video2 §14:31)`);
    } else if (copperTimeframe === -1) {
      unmetReasons.push('⚠️ 단기 역풍 (WTI 30D >+10% + FXI 60D <0, video2 §14:31)');
    }
  }

  if (reasons.length === 0) reasons.push('조건 미충족, 대기');

  // 영상2 명시적 3조건 동시 충족 복합 플래그
  const strongSetup = dv(derived, 'COPPER_STRONG_SETUP');
  if (strongSetup === 1) reasons.push('🟢🟢 구리 강매수 3조건 복합 (ISM+금구리비+ICSA) 전부 충족 — 영상2 명시');

  // 11차 신규 (2026-04): 금구리비 "하락 전환" (=CGR 상승 전환) 추세 감지 — video2 §3부 정합.
  // video2: "경기 회복 3가지 동시 — ISM 바닥 반등 + 금구리비 하락 전환 + 실업수당 감소".
  // 절대 레벨(CGR>0.00125) 외에 "전환 시점" 감지 — UPTURN=1 시 met 보조 가점.
  const cgrUpturn = dv(derived, 'COPPER_GOLD_RATIO_UPTURN');
  if (cgrUpturn === 1) {
    reasons.push('✓ CGR 상승 전환 (영상2 "금구리비 하락 전환 = 경기회복 전조") — 타이밍 확정 +1');
    met += 1;
  }

  // 29차 fix-B: COPPER weightedScore cap — 28차/29차 신규 가산
  // (RECOVERY_TRIPLE / COPPER_TIMEFRAME / CGR_UPTURN) 으로 met>total 발생 (133% over-cap).
  if (met > total) met = total;

  // Fix #6(2차 감사): REDUCE 분기 복구 — 기존 `met≥3 STRONG_BUY / met===2 BUY / else HOLD` 는
  //   met=0(3조건 모두 미충족) 에서도 HOLD 로 약세 강등이 없었다. signalFromScore 로 통일:
  //   total=3 기준 {strongBuy:3, buy:2, hold:1, reduce:0, sell:0} — 기존 BUY/STRONG_BUY 분기는 보존.
  const baseSignal = signalFromScore(met, total, {
    sell: 0,
    reduce: 0,
    hold: 1,
    buy: 2,
    strongBuy: 3,
  });
  let signal: Signal = baseSignal;
  const overrides: string[] = [];

  // === 29차 fix-F — 자산군 × regime 정합 게이트 ===
  signal = applyRegimeCoherenceGate('COPPER', signal, regime.regime, overrides, unmetReasons);

  return withSignalExplanation({
    asset: 'COPPER',
    signal,
    conditionsMet: met,
    conditionsTotal: total,
    weightedScore: met,
    weightedMaxScore: total,
    reasons,
    unmetReasons,
    date: new Date().toISOString().split('T')[0],
  }, baseSignal, overrides);
}

function cashSignal(regime: RegimeState): AssetSignal {
  const map: Record<string, Signal> = {
    RECESSION_RISK: 'STRONG_BUY',
    BOND_VIGILANTE: 'STRONG_BUY',
    STAGFLATION: 'BUY',
    CAUTION: 'BUY',
    NEUTRAL: 'HOLD',
    CORRECTION: 'REDUCE',
    PANIC_BUT_OK: 'REDUCE',
    RISK_ON: 'SELL',
  };

  const reasons: Record<string, string> = {
    RECESSION_RISK: '구조적 위험 → 현금 비중 극대화',
    BOND_VIGILANTE: '장기금리·신용 스트레스 → 현금 방어 강화',
    STAGFLATION: '물가 압력 + 성장 둔화 → 현금 방어 유지',
    CAUTION: '경계 → 현금 확보',
    NEUTRAL: '중립 → 현금 유지',
    CORRECTION: '조정 → 현금 투입 시작',
    PANIC_BUT_OK: '공포지만 펀더멘털 유지 → 적극 투입',
    RISK_ON: '위험선호 → 현금 최소화',
  };

  const baseSignal: Signal = map[regime.regime] ?? 'HOLD';
  let signal = baseSignal;
  const overrides: string[] = [];
  const unmetReasons: string[] = [];

  // === 29차 fix-F — 자산군 × regime 정합 게이트 ===
  signal = applyRegimeCoherenceGate('CASH', signal, regime.regime, overrides, unmetReasons);

  return withSignalExplanation({
    asset: 'CASH',
    signal,
    conditionsMet: 0,
    conditionsTotal: 0,
    weightedScore: 0,
    weightedMaxScore: 0,
    reasons: [reasons[regime.regime] ?? ''],
    unmetReasons,
    date: new Date().toISOString().split('T')[0],
  }, baseSignal, overrides);
}

function leverageCheck(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  profile: UserProfile,
  regime: RegimeState,
): AssetSignal {
  if (!profile.leverageEnabled) {
    clearLeverageEntry();
    return disabledAssetSignal('LEVERAGE', '사용자 설정 leverageEnabled=false — 레버리지 비활성화');
  }

  const reasons: string[] = [];
  const unmetReasons: string[] = [];
  let met = 0;
  // 29차 fix-H: total 을 0 에서 시작, 핵심 if/else 분기마다 total += 1 먼저 가산.
  let total = 0;

  const disparity = dv(derived, 'NASDAQ_DISPARITY');
  total += 1;
  if (disparity !== null && disparity <= -25) { met++; reasons.push(`이격도 ${disparity.toFixed(1)}% ≤ -25% (가중치 1.0)`); }
  else { unmetReasons.push('이격도 -25% 이하 조건 미충족 (가중치 1.0 미충족)'); }

  const vix = v(raw, 'VIXCLS');
  total += 1;
  if (vix !== null && vix >= 35) { met++; reasons.push(`VIX ${vix.toFixed(1)} ≥ 35 (가중치 1.0)`); }
  else { unmetReasons.push('VIX 35 이상 조건 미충족 (가중치 1.0 미충족)'); }

  const icsa = v(raw, 'ICSA');
  total += 1;
  if (icsa !== null && icsa < 300000) { met++; reasons.push(`실업수당 ${Math.round(icsa / 1000)}K < 300K (가중치 1.0)`); }
  else { unmetReasons.push('실업수당 300K 미만 조건 미충족 (가중치 1.0 미충족)'); }

  if (disparity !== null && disparity >= 0 && met < 3) {
    return withSignalExplanation({
      asset: 'LEVERAGE',
      signal: 'REDUCE',
      conditionsMet: met,
      conditionsTotal: total,
      weightedScore: met,
      weightedMaxScore: total,
      reasons: [`이격도 ${disparity.toFixed(1)}% → 200DMA 복귀/초과. 레버리지 익절 구간 (목표 20~30% 도달 추정)`],
      unmetReasons,
      date: new Date().toISOString().split('T')[0],
      tier: null,
    }, 'REDUCE');
  }

  if (disparity !== null && disparity > -10 && disparity < 0 && met < 3) {
    reasons.push(`이격도 ${disparity.toFixed(1)}% → 회복 중. 목표 수익 근접. 익절 준비 (보조조건)`);
  }

  if (vix !== null && vix < 20 && disparity !== null && disparity > -15) {
    unmetReasons.push(`⚠️ VIX ${vix.toFixed(1)} 안정 + 이격도 ${disparity.toFixed(1)}% → 횡보 원금잠식 위험 (보조조건)`);
  }

  // === 3단계 티어 분류 (HARD > MEDIUM > SOFT) ===
  // 영상1 원전(기존 -25/35/<300K=HARD) 보존 + 저점 유사 구간(-5/-15) 확대.
  // 모든 티어는 3조건 AND (이격·VIX·ICSA). ICSA 게이트는 공통.
  let tier: LeverageTier | null = null;
  let tierSignal: Signal = 'HOLD';
  let tierCap = 0;
  const icsaOk = icsa !== null && icsa < 300000;
  if (disparity !== null && vix !== null && icsaOk) {
    if (disparity <= -25 && vix >= 35) {
      tier = 'HARD'; tierSignal = 'STRONG_BUY'; tierCap = 15;
    } else if (disparity <= -15 && vix >= 30) {
      tier = 'MEDIUM'; tierSignal = 'BUY'; tierCap = 10;
    } else if (disparity <= -5 && vix >= 30) {
      tier = 'SOFT'; tierSignal = 'BUY'; tierCap = 5;
    }
  }

  const today = new Date().toISOString().split('T')[0];
  let signal: Signal = tierSignal;
  const baseSignal = signal;
  const overrides: string[] = [];
  const decisionReasons = tier !== null
    ? [
        ...reasons,
        `LEVERAGE_TIER: ${tier} (이격 ${disparity!.toFixed(1)}%, VIX ${vix!.toFixed(1)}, ICSA ${Math.round(icsa! / 1000)}K) → ${tierCap}% 상한`,
      ]
    : [...reasons, `티어 미발동(HARD/MEDIUM/SOFT 조건 모두 미충족) → 레버리지 불허`];

  // === 시간 기반 청산 (영상1 §전략C) ===
  const entryDate = readLeverageEntry();
  const isEntryActive = signal === 'BUY' || signal === 'STRONG_BUY';
  if (isEntryActive) {
    if (!entryDate) {
      writeLeverageEntry(today);
      decisionReasons.push(`레버리지 진입일 ${today} 기록 (상한 ${LEVERAGE_FORCE_EXIT_DAYS}일)`);
    } else {
      const elapsed = daysBetween(today, entryDate);
      if (elapsed >= LEVERAGE_FORCE_EXIT_DAYS) {
        signal = 'REDUCE';
        overrides.push(`진입 ${elapsed}일 경과 (>= ${LEVERAGE_FORCE_EXIT_DAYS}일) -> REDUCE`);
        decisionReasons.push(
          `⏰ 진입 ${elapsed}일 경과 (≥ ${LEVERAGE_FORCE_EXIT_DAYS}일) → 영상1 §전략C "2~3개월 짧게" 원칙 강제 익절`,
        );
      } else if (elapsed >= LEVERAGE_WARN_DAYS) {
        decisionReasons.push(
          `⚠️ 진입 ${elapsed}일차 (${LEVERAGE_WARN_DAYS}~${LEVERAGE_FORCE_EXIT_DAYS}일) → 익절 준비 구간`,
        );
        unmetReasons.push(
          `⏰ 레버리지 진입 ${elapsed}일 — 영상1 2~3개월 상한 접근, 이익실현 권고`,
        );
      } else {
        decisionReasons.push(`진입 ${elapsed}일차 (상한 ${LEVERAGE_FORCE_EXIT_DAYS}일)`);
      }
    }
  } else if (entryDate) {
    // BUY/STRONG_BUY 아닌 상태 → 포지션 종료로 간주, 진입일 리셋
    clearLeverageEntry();
    decisionReasons.push(`신호 종료 → 진입일 기록 삭제 (다음 진입 시 재기록)`);
  }

  // 29차 fix-B: LEVERAGE weightedScore cap (일관 패턴 적용).
  if (met > total) met = total;

  // === 29차 fix-F — 자산군 × regime 정합 게이트 ===
  signal = applyRegimeCoherenceGate('LEVERAGE', signal, regime.regime, overrides, unmetReasons);

  return withSignalExplanation({
    asset: 'LEVERAGE',
    signal,
    conditionsMet: met,
    conditionsTotal: total,
    weightedScore: met,
    weightedMaxScore: total,
    reasons: decisionReasons,
    unmetReasons,
    date: today,
    tier,
  }, baseSignal, overrides);
}

function kospiSignal(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  profile: UserProfile,
  regime: RegimeState,
): AssetSignal {
  if (!profile.includeKR) {
    return disabledAssetSignal('KOSPI', '사용자 설정 includeKR=false — 한국 자산 제외');
  }

  const reasons: string[] = [];
  const unmetReasons: string[] = [];
  let met = 0;
  // 29차 fix-H: total 을 0 에서 시작, 핵심 if/else 분기마다 total += 1 먼저 가산.
  //   미충족 조건도 total 에 카운트되어야 분수 정합 — UI "7/7 misleading" 수정.
  let total = 0;

  const above200 = dv(derived, 'KOSPI_ABOVE_200DMA');
  total += 1;
  if (above200 === 0) { met++; reasons.push('코스피 200DMA 하회 (가중치 1.0)'); }
  else { unmetReasons.push('코스피 200DMA 상회 중 (가중치 1.0 미충족)'); }

  const fxLevel = dv(derived, 'KRW_FX_LEVEL');
  total += 1;
  if (fxLevel !== null && fxLevel >= 1) { met++; reasons.push(`환율 우호 (레벨 ${fxLevel}, 가중치 1.0)`); }
  else { unmetReasons.push('환율 1480원 이하 조건 미충족 (가중치 1.0 미충족)'); }

  const disparity = dv(derived, 'KOSPI_DISPARITY');
  // 11차 이격도 계층화 (2026-04): stt_kospi 에 구체 수치 근거 없으나 역사적 "75% 상승 후
  //   조정 통상 15-30%" 범위 기반. 극저점(-25%) 시 +1 추가 가점 — 2025년 2~3월 수준 대응.
  total += 1;
  if (disparity !== null && disparity <= -25) {
    met += 2;
    reasons.push(`✓ 코스피 이격도 ${disparity.toFixed(1)}% ≤ -25% (stt_kospi "역대급 하락" 수준, 보너스 +1, 총 2점)`);
  } else if (disparity !== null && disparity < -15) {
    met++;
    reasons.push(`코스피 이격도 ${disparity.toFixed(1)}% < -15% (실용 저점 임계, 가중치 1.0)`);
  } else {
    unmetReasons.push('코스피 이격도 -15% 이하 조건 미충족 (가중치 1.0 미충족)');
  }

  // 11차 시정 (2026-04): 영상 stt_kospi / video5_analysis "유가 60달러대 안정 /
  //   100달러 재돌파 경고" 정합을 위해 WTI 임계 80→65 로 강화 (영상 기준 60 + 여유 5).
  //   기존 80 은 영상의 "위험 구간 75-85" 상단이라 안정 판정 기준으로 부적절.
  const wti = v(raw, 'WTI');
  total += 1;
  if (wti !== null && wti < 65) { met++; reasons.push(`유가 $${wti.toFixed(1)} < $65 안정 (영상5 §Takeaway, 가중치 1.0)`); }
  else { unmetReasons.push('유가 $65 미만 조건 미충족 (영상 "60달러대 안정", 가중치 1.0 미충족)'); }

  const chaseKospi = dv(derived, 'CHASE_KOSPI');
  if (chaseKospi !== null && chaseKospi > 15) { unmetReasons.push(`⚠️ 코스피 20일 +${chaseKospi.toFixed(1)}% → 추격매수 주의 (보조조건)`); }

  const vix = v(raw, 'VIXCLS');
  total += 1;
  if (vix !== null && vix < 25) { met++; reasons.push(`VIX ${vix.toFixed(1)} < 25 안정 (가중치 1.0)`); }
  else { unmetReasons.push('VIX 25 미만 조건 미충족 (가중치 1.0 미충족)'); }

  const volumeConfirm = dv(derived, 'KOSPI_VOLUME_CONFIRM');
  total += 1;
  if (volumeConfirm === 1) { met++; reasons.push('거래량 확인 (최근5일 평균 >= 20일 평균의 110%, 가중치 1.0)'); }
  else { unmetReasons.push('거래량 지속 조건 미충족 (가중치 1.0 미충족)'); }

  // Fix #4: USDKRW_WEEKLY_CHANNEL_POSITION 소비 — 5년 주봉 회귀채널 내 위치(0~1).
  // ≥ 0.9 (상단 근접) 이면 원화 약세 극단 → 외국인 매도 리스크 격상. FX 게이트 강화용.
  // 이미 획득한 met 에서 1 차감(floor=0) 하고, 원화 강세 극단(≤0.1) 에서는 추가 가점 없이 reason 만.
  const krwChannelPos = dv(derived, 'USDKRW_WEEKLY_CHANNEL_POSITION');
  if (krwChannelPos !== null && krwChannelPos >= 0.9) {
    met = Math.max(0, met - 1);
    unmetReasons.push(`⚠️ USD/KRW 주봉채널 ${krwChannelPos.toFixed(2)} ≥ 0.9 → 원화 약세 극단, FX 게이트 강화 (met -1)`);
  } else if (krwChannelPos !== null && krwChannelPos <= 0.1) {
    reasons.push(`USD/KRW 주봉채널 ${krwChannelPos.toFixed(2)} ≤ 0.1 → 원화 강세 극단, 외국인 복귀 우호 (보조조건)`);
  }

  // 멀티 타임프레임 경고 (영상5 코스피 연봉/월봉 "정상성" 판정)
  const kMtfExhaustion = dv(derived, 'KOSPI_MONTHLY_EXHAUSTION');
  const kMtfReversal = dv(derived, 'KOSPI_WEEKLY_REVERSAL');
  const kMtfMonthPos = dv(derived, 'KOSPI_MONTH_POS');
  const kMtfLowerWick = dv(derived, 'KOSPI_MONTHLY_LOWER_WICK_PCT');
  const kMtfBody = dv(derived, 'KOSPI_MONTHLY_BODY_PCT');
  if (kMtfExhaustion === 1) { unmetReasons.push('⚠️ 코스피 월봉 소진 경고: 3개월 연속 장대양봉 + 아래꼬리 없음 (영상5 과열 패턴, 보조조건)'); }
  if (kMtfReversal === 1) { unmetReasons.push('⚠️ 코스피 주봉 반전 경고: 상승 추세 후 장대음봉 (보조조건)'); }
  if (kMtfMonthPos !== null && kMtfMonthPos >= 95) { unmetReasons.push(`⚠️ 코스피 월봉 위치 ${kMtfMonthPos.toFixed(0)}% → 12개월 고점 근처 (보조조건)`); }
  else if (kMtfMonthPos !== null && kMtfMonthPos <= 15) { reasons.push(`코스피 월봉 위치 ${kMtfMonthPos.toFixed(0)}% → 저점권 (보조조건)`); }
  if (kMtfBody !== null && kMtfBody >= 90 && kMtfLowerWick !== null && kMtfLowerWick < 5) {
    unmetReasons.push(`⚠️ 최근 월봉 마루보주 (body ${kMtfBody.toFixed(0)}%, 아래꼬리 ${kMtfLowerWick.toFixed(0)}%) → 매수 검증 부족 (보조조건)`);
  }

  // 외국인 수급 축 (영상5 "환율·추세·거래량·외국인수급" 4축 중 마지막)
  const foreignNet20D = dv(derived, 'KOSPI_FOREIGN_NET_20D');
  const foreignTrend = dv(derived, 'KOSPI_FOREIGN_TREND');
  const foreignBuyStreak = dv(derived, 'KOSPI_FOREIGN_BUY_STREAK');
  const foreignSellStreak = dv(derived, 'KOSPI_FOREIGN_SELL_STREAK');
  const foreignExtreme = dv(derived, 'KOSPI_FOREIGN_EXTREME');
  total += 1;
  if (foreignNet20D !== null && foreignNet20D > 0) {
    met++;
    const trendTag = foreignTrend !== null && foreignTrend > 0 ? ' + 추세 가속' : '';
    const streakTag = foreignBuyStreak !== null && foreignBuyStreak >= 5 ? ` · ${foreignBuyStreak}일 연속 매수` : '';
    reasons.push(`외국인 20일 순매수 ${foreignNet20D >= 0 ? '+' : ''}${Math.round(foreignNet20D).toLocaleString('en-US')}억${trendTag}${streakTag} (가중치 1.0)`);
  } else if (foreignNet20D !== null) {
    unmetReasons.push(`외국인 20일 순매도 ${Math.round(foreignNet20D).toLocaleString('en-US')}억 → 매수 기반 부족 (가중치 1.0 미충족)`);
  } else {
    unmetReasons.push('외국인 수급 데이터 없음 (가중치 1.0 미충족)');
  }
  // 극단 이벤트 보조 경고 (일상 ±3조 임계)
  if (foreignExtreme === 1) unmetReasons.push(`⚠️ 외국인 20일 누적 +3조 초과 — 단기 과열 구간 (보조조건)`);
  else if (foreignExtreme === -1) reasons.push(`외국인 20일 누적 -3조 초과 — 과매도 반등 후보 (보조조건)`);

  // 역사적 대량 이벤트 (±20조, 11차 신규) — stt_kospi "2025년 2~3월 45~60조 매도" 규모
  const foreignHistoric = dv(derived, 'KOSPI_FOREIGN_HISTORIC_EXTREME');
  if (foreignHistoric === -1) {
    reasons.push('✓ 외국인 20일 누적 -20조 초과 — stt_kospi "2~3월 공황성 매도" 수준, 반등 후보 강화 (보조조건 +1)');
    met += 1; // 역사적 과매도 = 반등 기회
  } else if (foreignHistoric === 1) {
    unmetReasons.push('⚠️ 외국인 20일 누적 +20조 초과 — 역사적 대규모 매수 과열 (보조조건)');
  }
  if (foreignSellStreak !== null && foreignSellStreak >= 5) {
    unmetReasons.push(`⚠️ 외국인 ${foreignSellStreak}일 연속 순매도 → 구조적 이탈 경고 (보조조건)`);
  }

  // 8차 TOP7 Fix #1: 외인-개인 괴리 경보 (경고만, met 변동 없음)
  const fgIndividualDiv = dv(derived, 'KOSPI_FOREIGN_INDIVIDUAL_DIVERGENCE');
  if (fgIndividualDiv === 1) {
    unmetReasons.push('⚠️ 개인이 외인 매물 흡수 (역사적 악성 구도) — 외인5D -3조↓ + 개인5D +3조↑ (보조조건)');
  } else if (fgIndividualDiv === -1) {
    reasons.push('외인 매수 + 개인 매도 구도 — 외인 주도 강세 후보 (보조조건)');
  }

  // 영상5 이중 게이트: 환율 1480↓ 그린 / 1500↑ 레드 (단일 KRW_FX_LEVEL 보완 보조조건)
  const fxGreen = dv(derived, 'KRW_FX_GREEN');
  const fxRed = dv(derived, 'KRW_FX_RED');
  if (fxGreen === 1) reasons.push(`환율 ≤1480 그린 게이트 — 외국인 복귀 우호 (보조조건, 영상5 §3-1)`);
  if (fxRed === 1) unmetReasons.push(`⚠️ 환율 ≥1500 레드 게이트 — 외국인 매도 압력 임계 (보조조건, 영상5 §3-1)`);

  const usdkrw = v(raw, 'USDKRW');
  if (usdkrw !== null && usdkrw >= 1500 && above200 === 0) {
    return withSignalExplanation({
      asset: 'KOSPI',
      signal: 'SELL',
      conditionsMet: met,
      conditionsTotal: total,
      weightedScore: met,
      weightedMaxScore: total,
      reasons: ['코스피 200DMA 하회 + 환율 1500원 돌파 → 외국인 매도 압력 극대화'],
      unmetReasons,
      date: new Date().toISOString().split('T')[0],
    }, 'SELL');
  }

  // 29차 fix-E: KOSPI baseSignal 결정 위치 정합성 — NASDAQ 와 동일 패턴.
  //   기존 baseSignal (L1573) 이후에도 met += (월봉회복 / 외인 streak / FX reversal /
  //   PBR / ROE / 회복 3축 / RSI / DRAWDOWN / W_BOTTOM / WGBI / 추경 / 연기금 / SHORT_INTEREST /
  //   거래량 tier / 반기봉 윗꼬리 등) 이 다수 발생 — 이전엔 met 표시값만 늘리고 신호 결정에 못 끼침.
  //   해결: 모든 met/total 가산 → cap → baseSignal 결정 → override 분기.
  const overrides: string[] = [];

  const trendRecovery = dv(derived, 'KOSPI_TREND_RECOVERY');
  const trendConfirmCount = [
    trendRecovery === 1 ? 1 : 0,
    volumeConfirm === 1 ? 1 : 0,
    (fxLevel !== null && fxLevel >= 1) ? 1 : 0,
  ].reduce((a, b) => a + b, 0);

  // ★ === 29차 P1-D #12: KOSPI_MONTHLY_BEAR_COVER_FLAG ===
  // stt_kospi §03:28 "직전 월봉 음봉 5%+ 후 현재 월봉이 직전 시가 회복 = 매수 신호".
  const monthlyCover = dv(derived, 'KOSPI_MONTHLY_BEAR_COVER_FLAG');
  if (monthlyCover === 1) {
    reasons.push('✓ 월봉 회복 (직전 음봉 5%+ → 현재 월봉 시가 돌파, stt_kospi §03:28)');
    total += 1; met += 1;
  }

  // ★ === 29차 P1-D #13: KOSPI_FOREIGN_STREAK_DAYS + OVERSELL_30T ===
  // stt_kospi §3-1 "외국인 5일+ 연속 매수 = 추세 복귀 / 60D 누적 -30조 = 공황 매도".
  const foreignStreakDays = dv(derived, 'KOSPI_FOREIGN_STREAK_DAYS');
  if (foreignStreakDays !== null && foreignStreakDays >= 5) {
    reasons.push(`✓ 외국인 ${foreignStreakDays}일 연속 순매수 (stt_kospi §3-1 "추세 복귀")`);
    total += 1; met += 1;
  }
  const oversell30T = dv(derived, 'KOSPI_FOREIGN_OVERSELL_30T_FLAG');
  if (oversell30T === 1) {
    reasons.push('✓ 외국인 60D 누적 -30조 매도 — 반발 후보 (stt_kospi §3-1)');
    total += 1; met += 1;
  }

  // ★ === 29차 P1-D #14: KRW_FX_REVERSAL_TRIGGER ===
  // stt_kospi §11:39 "환율 1480↓ 5일 연속 + 외인 복귀 streak ≥+5 = 환율 반전 + 외인 복귀 정합".
  const fxReversal = dv(derived, 'KRW_FX_REVERSAL_TRIGGER');
  if (fxReversal === 1) {
    reasons.push('✓ 환율 반전 + 외인 복귀 정합 (stt_kospi §11:39)');
    total += 1; met += 1;
  }

  // ★ === 29차 P2-C #17: KOSPI_PBR ===
  // video6 §05:55 — < 0.9 → +1 / > 2.0 → -1.
  const kospiPbrLvl = dv(derived, 'KOSPI_PBR');
  if (kospiPbrLvl === 1) {
    total += 1; met += 1;
    reasons.push('✓ KOSPI PBR < 0.9 (밸류 우호, video6 §05:55)');
  } else if (kospiPbrLvl === -1) {
    unmetReasons.push('⚠️ KOSPI PBR > 2.0 (밸류 과열, video6 §05:55)');
  }

  // ★ === 29차 P2-C #18: KOSPI_AGGREGATE_ROE ===
  // video6 §04:35 — ≥10 → +1 / <5 → -1.
  const kospiRoeLvl = dv(derived, 'KOSPI_AGGREGATE_ROE');
  if (kospiRoeLvl === 1) {
    total += 1; met += 1;
    reasons.push('✓ KOSPI 합산 ROE ≥ 10% (video6 §04:35)');
  } else if (kospiRoeLvl === -1) {
    unmetReasons.push('⚠️ KOSPI 합산 ROE < 5% (video6 §04:35)');
  }

  // ★ === 29차 P1-B #5: KOSPI_RECOVERY_3AXIS_LEVEL gate 강화 ===
  // stt_kospi §05:35 "3축 동시 + 연속일수 = 진짜 추세 전환".
  // 단발 trendConfirmCount 평가를 level≥1 (연속 3일+) 게이트로 승격. level=2 시 STRONG_BUY 우호.
  const kRecLevel = dv(derived, 'KOSPI_RECOVERY_3AXIS_LEVEL');
  const kRecDays = dv(derived, 'KOSPI_RECOVERY_TRIO_DAYS');
  if (kRecLevel !== null && kRecLevel >= 1) {
    reasons.push(`✓ KOSPI 회복 3축 ${kRecDays ?? 0}일 연속 (level=${kRecLevel}, stt_kospi §05:35)`);
    total += 1; met += 1;
  }

  // 15차 Phase 1+2: KOSPI RSI + DRAWDOWN_ATH
  // ★ 29차 P2-D #24: RSI 임계 35 → 30 정렬 (NASDAQ RSI 기준 통일).
  const kRsi = dv(derived, 'KOSPI_RSI_14');
  if (kRsi !== null) {
    if (kRsi < 30) {
      reasons.push(`✓ KOSPI RSI ${kRsi.toFixed(1)} < 30 과매도 (video2 §RSI 정합, 29차 P2-D #24 임계 정렬)`);
      total += 1; met += 1;
    } else if (kRsi > 70) {
      unmetReasons.push(`⚠️ KOSPI RSI ${kRsi.toFixed(1)} 과매수 — 추격 주의`);
    }
  }
  const kDd = dv(derived, 'KOSPI_DRAWDOWN_ATH');
  if (kDd !== null && kDd <= -15 && kDd >= -30) {
    reasons.push(`✓ KOSPI ATH 대비 ${kDd.toFixed(1)}% 조정 (stt_kospi "75% 상승 후 통상 15-30% 조정" 범위)`);
    total += 1; met += 1;
  }

  // 14차 Phase B-2: KOSPI W 반등 + 연봉 아래꼬리 지수 reason 노출
  const kWBottom = dv(derived, 'KOSPI_W_BOTTOM');
  if (kWBottom === 1) {
    reasons.push('✓ KOSPI_W_BOTTOM 감지 — 이중 저점 확인 (video3 분할매수 3차)');
    total += 1; met += 1;
  }
  const kAreaLevel = dv(derived, 'KOSPI_YEARLY_AREA_LEVEL');
  if (kAreaLevel === -1) {
    unmetReasons.push('⚠️ KOSPI_YEARLY_AREA_INDEX <15% — 연봉 아래꼬리 부족, 누적 매수 포지션 미소화 (video5_analysis §1부)');
  } else if (kAreaLevel === 1) {
    reasons.push('✓ KOSPI_YEARLY_AREA_INDEX ≥15% — 연봉 한 번 눌림 흡수 (video5_analysis §1부, 보조조건)');
  }

  // ★ === 29차 P2-D #29: FX_FOREIGN_BASELINE_GAP_TRILLION ===
  // stt_kospi §08:21 — 갭 ≥30조 → ATM 화 강 경고 (unmetReason).
  const fxForeignGap = dv(derived, 'FX_FOREIGN_BASELINE_GAP_TRILLION');
  if (fxForeignGap === 1) {
    unmetReasons.push('⚠️ FX-외인 baseline 갭 ≥30조 — ATM 화 강 경고 (stt_kospi §08:21)');
  }

  // ★ === 29차 P2-E #31: KOSPI_HALFYEAR_UPPER_WICK_THRESHOLD (met 가산만 사전 처리, 강등은 override 단계) ===
  const kospiWickThr = dv(derived, 'KOSPI_HALFYEAR_UPPER_WICK_THRESHOLD');
  if (kospiWickThr === 1) {
    reasons.push('✓ 반기봉 윗꼬리 < 15% (stt_kospi §03:18)');
    total += 1; met += 1;
  }

  // ★ === 29차 P3-B #7: KR_BOK_LOCKED_FLAG → KOSPI -1 패널티 ===
  // stt_kospi §09:55 "한은 사방이 막힌 미로".
  const bokLocked = dv(derived, 'KR_BOK_LOCKED_FLAG');
  if (bokLocked === 1) {
    total += 1; met = Math.max(0, met - 1);
    unmetReasons.push('⚠️ KR_BOK_LOCKED_FLAG=1 — 한은 정책 무력 (USDKRW≥1500 OR 가계부채>100% OR CPI≥3, KOSPI -1, stt_kospi §09:55)');
  }

  // ★ === 29차 P3-B #8: WGBI_INFLOW_TAILWIND → +1 가점 ===
  // stt_kospi §09:33 "WGBI 편입 외국인 자금 유입".
  const wgbiTw = dv(derived, 'WGBI_INFLOW_TAILWIND');
  if (wgbiTw === 1) {
    total += 1; met += 1;
    reasons.push('✓ WGBI 편입 window + 환율 안정 (외국인 자금 유입 기반, stt_kospi §09:33)');
  }

  // ★ === 29차 P3-B #9: KR_FISCAL_LAG_PROGRESS_DAYS → 효과 반영 시점 가산 ===
  // stt_kospi §10:30 "추경 27조 → 6개월 후 효과".
  const fiscalProg = dv(derived, 'KR_FISCAL_LAG_PROGRESS_DAYS');
  if (fiscalProg !== null && fiscalProg >= 1) {
    total += 1; met += 1;
    reasons.push('✓ 추경 효과 반영 시점 도달 (D+180 경과, stt_kospi §10:30)');
  } else if (fiscalProg !== null && fiscalProg >= 0.5) {
    reasons.push('🟡 추경 효과 절반 진입 (보조, stt_kospi §10:30)');
  }

  // ★ === 29차 P3-E #28: KRX_PENSION_FUND_FLOW — 연기금 5D +1조 가점 ===
  const pensionFlow = dv(derived, 'KRX_PENSION_FUND_FLOW');
  if (pensionFlow === 1) {
    total += 1; met += 1;
    reasons.push('✓ KRX 연기금 5D ≥ +1조 — 안정적 매수 기반 (보조)');
  } else if (pensionFlow === -1) {
    unmetReasons.push('⚠️ KRX 연기금 5D ≤ -1조 — 매도 압력 (보조)');
  }

  // ★ === 29차 P3-E #29: KRX_SHORT_INTEREST_LEVEL — 숏스퀴즈 후보 ===
  const shortLvl = dv(derived, 'KRX_SHORT_INTEREST_LEVEL');
  if (shortLvl === 2) {
    total += 1; met += 1;
    reasons.push('✓ 공매도 ≥5% — 강 숏스퀴즈 후보 (video6 §06:31)');
  } else if (shortLvl === 1) {
    reasons.push('✓ 공매도 ≥3% — 숏스퀴즈 후보 (video6 §06:31, 보조)');
  }

  // ★ === 29차 P3-C #20: KOSPI_UNCHARTED_TERRITORY_FLAG — 심리 불안 경고 ===
  const kUncharted = dv(derived, 'KOSPI_UNCHARTED_TERRITORY_FLAG');
  if (kUncharted === 1) {
    unmetReasons.push('⚠️ KOSPI_UNCHARTED_TERRITORY=1 — 역대 high 95%↑ 30일 연속 (심리 불안정성, stt_kospi §"5천/6천도 처음")');
  }

  // ★ === 29차 P3-B #11: KOSPI_HISTORIC_OVERSHOOT_RANK — 다음 해 평균 -44% 경고 ===
  // stt_kospi §01:55 "1999/2007/2020 사례".
  const overshootRank = dv(derived, 'KOSPI_HISTORIC_OVERSHOOT_RANK');
  if (overshootRank === 2) {
    unmetReasons.push('⚠️⚠️ KOSPI 역사적 과열 rank=2 (≥83% IT버블 수준) — 다음 해 평균 -44% (stt_kospi §01:55)');
  } else if (overshootRank === 3) {
    unmetReasons.push('⚠️ KOSPI 역사적 과열 rank=3 (≥75% 코로나 수준) — 다음 해 평균 -44% (stt_kospi §01:55)');
  } else if (overshootRank === 4) {
    unmetReasons.push('⚠️ KOSPI 과열 rank=4 (≥50% GFC 직전 수준, stt_kospi §01:55)');
  }

  // ★ === 29차 P2-E #36: KOSPI_VOLUME_TIER ===
  // stt_kospi §"주간 평균 20조" — tier +1/0/-1.
  const volTier = dv(derived, 'KOSPI_VOLUME_TIER');
  if (volTier === 1) {
    reasons.push('✓ KOSPI 거래량 tier+1 (≥20조 지속성 강, stt_kospi)');
    total += 1; met += 1;
  } else if (volTier === -1) {
    unmetReasons.push('⚠️ KOSPI 거래량 tier-1 (<15조 관심 약화, stt_kospi)');
  }

  // 29차 fix-B: KOSPI met cap (NASDAQ 와 동일 패턴) — 모든 가산 후 비율 정상화.
  if (met > total) met = total;

  // 29차 fix-E: baseSignal 결정 — 모든 met/total 가산 + cap 후 호출.
  // Fix #1: total=7 기준 [2,3,4] 에 REDUCE/SELL 하한을 명시. 기존 HOLD 시작 met=2 는 유지하고
  // met=1 만 REDUCE, met=0 만 SELL 로 강등. KOSPI 는 환율·외인·거래량 축이 하나라도 깨지면
  // met 급락 가능하므로 total-5=2 대신 보수적으로 reduce=1 채택.
  const baseSignal = signalFromScore(met, total, { sell: 0, reduce: 1, hold: 2, buy: 3, strongBuy: 4 });
  let signal = baseSignal;

  // === Override 1: trendConfirmCount + kRecLevel 게이트 ===
  if (trendConfirmCount < 2 && (signal === 'STRONG_BUY')) {
    // 29차 P1-B #5: kRecLevel=2 (5일+) 시 STRONG_BUY 보호 — 단발 trendConfirmCount 미충족 무시.
    if (kRecLevel === 2) {
      reasons.push('✓ KOSPI_RECOVERY_3AXIS_LEVEL=2 (5일+ 연속) → STRONG_BUY 보호 (stt_kospi §05:35)');
    } else {
      const previous = signal;
      signal = 'BUY';
      const overrideReason = `추세전환 3조건 ${trendConfirmCount}/3 미충족 → ${previous} → BUY 상한 (보조조건)`;
      overrides.push(overrideReason);
      reasons.push(overrideReason);
    }
  }
  if (trendConfirmCount === 0 && (signal === 'BUY' || signal === 'STRONG_BUY')) {
    if (kRecLevel === 2) {
      reasons.push('✓ KOSPI_RECOVERY_3AXIS_LEVEL=2 → HOLD 강등 면제');
    } else {
      const previous = signal;
      signal = 'HOLD';
      const overrideReason = `추세전환 3조건 전부 미충족 → ${previous} → HOLD 상한 (보조조건)`;
      overrides.push(overrideReason);
      reasons.push(overrideReason);
    }
  }

  // === Override 2: 11차 신규 — 지정학 급변 숏커버링 반등 가드 ===
  // video5_analysis §3부: "환율 1,500 돌파 예상하고 코스피 하락 베팅한 세력이 휴전
  //   뉴스에 놀라 급격히 손절" → 반등의 50% 는 숏커버링, 진짜 추세 아님.
  const geoUnwind = dv(derived, 'GEOPOLITICAL_UNWIND_EVENT');
  const shortCover = dv(derived, 'SHORT_COVER_SUSPECTED');
  if (geoUnwind === 1 && shortCover === 1 && (signal === 'BUY' || signal === 'STRONG_BUY')) {
    const previous = signal;
    signal = 'HOLD';
    const overrideReason =
      `⚠️ 지정학 급변 숏커버링 반등 (stt_kospi §2부) — 휴전/종전 뉴스 + 외인 1일 순매수 ≥1조. ` +
      `반등의 질 감별 필요 → ${previous} → HOLD (추세 재개 3조건 확인 후 재진입)`;
    overrides.push(overrideReason);
    reasons.push(overrideReason);
  }

  // === Override 3: 29차 P2-D #28 BOUNCE_QUALITY ===
  const bounceQuality = dv(derived, 'BOUNCE_QUALITY_FOLLOWTHROUGH_DAYS');
  if (bounceQuality === 0 && (signal === 'BUY' || signal === 'STRONG_BUY')) {
    const previous = signal;
    signal = 'HOLD';
    const overrideReason = `⚠️ BOUNCE_QUALITY=0 (D+5 재하락 또는 거래량 미확인) → ${previous} → HOLD (stt_kospi §05:40)`;
    overrides.push(overrideReason);
    unmetReasons.push(overrideReason);
  }

  // === Override 4: 29차 P2-E #31 KOSPI_HALFYEAR_UPPER_WICK 강등 ===
  if (kospiWickThr === -1 && signal === 'STRONG_BUY') {
    const previous = signal;
    signal = 'BUY';
    const overrideReason = `⚠️ 반기봉 윗꼬리 ≥30% (매도압력 강) → ${previous} → BUY (stt_kospi §03:18)`;
    overrides.push(overrideReason);
    unmetReasons.push(overrideReason);
  }

  // === Override 5: 코스피 완화 규칙 (이격도/CHASE/FX_ELASTICITY) ===
  // - "실제 가격 과열"이 있는 경우에만 강한 다운그레이드를 허용한다.
  // - CHASE_WARNING 은 과열뿐 아니라 과매도 연속구간도 포함하므로, 단독으로는 REDUCE 근거로 쓰지 않는다.
  // - FX_ELASTICITY 는 흐름 경고로만 보고, 가격 과열이 없으면 최대 HOLD 까지만 캡한다.
  const kOverheatFlags: string[] = [];
  const kCautionFlags: string[] = [];
  const priceOverheated = disparity !== null && disparity >= 20;
  if (priceOverheated) kOverheatFlags.push(`코스피 이격도 +${disparity!.toFixed(1)}% ≥ 20%`);
  const kOverheatStreak = dv(derived, 'KOSPI_DISPARITY_STREAK_OVERHEATED');
  if (kOverheatStreak !== null && kOverheatStreak >= 20) {
    kOverheatFlags.push(`과열 이격도 연속 ${kOverheatStreak.toFixed(0)}일`);
  }
  const kChaseWarning = dv(derived, 'KOSPI_CHASE_WARNING');
  if (kChaseWarning === 1) {
    kCautionFlags.push('CHASE_WARNING (이격률 ±15% 20일 지속)');
  }
  const fxElasticity = dv(derived, 'KOSPI_FX_ELASTICITY_DEVIATION');
  if (fxElasticity !== null && fxElasticity >= 2) {
    kCautionFlags.push(`FX_ELASTICITY_DEVIATION ${fxElasticity.toFixed(2)} ≥ 2 (외인 과매도 ATM화)`);
  }
  if (kOverheatFlags.length >= 1 && (kOverheatFlags.length + kCautionFlags.length) >= 2 && signal !== 'SELL') {
    const previous = signal;
    signal = softenRiskSignal(signal);
    const overrideReason = `가격 과열 완화 override: ${[...kOverheatFlags, ...kCautionFlags].join(' · ')} (${previous} → ${signal})`;
    overrides.push(overrideReason);
    unmetReasons.push(overrideReason);
  } else if (!priceOverheated && kCautionFlags.length >= 2 && (signal === 'BUY' || signal === 'STRONG_BUY')) {
    const previous = signal;
    signal = 'HOLD';
    const overrideReason = `흐름 경고 HOLD 캡: ${kCautionFlags.join(' · ')} (${previous} → HOLD)`;
    overrides.push(overrideReason);
    unmetReasons.push(overrideReason);
  }

  // === Override 6: 9차 후속 Fix #2 — KOSPI_CHASE_LEVEL 계층화 ===
  //   level 0 → no-op
  //   level 1 (soft): reason 경고만, 신호 불변
  //   level 2 (medium): STRONG_BUY → BUY
  //   level 3 (hard): STRONG_BUY/BUY → HOLD
  //   level null: 기존 binary CHASE_WARNING 로직 유지 (하위 호환).
  const kChaseLevel = dv(derived, 'KOSPI_CHASE_LEVEL');
  if (kChaseLevel !== null) {
    const levelReason = derived.KOSPI_CHASE_LEVEL?.formula ?? '';
    if (kChaseLevel >= 3 && (signal === 'STRONG_BUY' || signal === 'BUY')) {
      const previous = signal;
      signal = 'HOLD';
      const overrideReason = `CHASE_LEVEL=3 (hard): ${levelReason} (${previous} → HOLD)`;
      overrides.push(overrideReason);
      unmetReasons.push(overrideReason);
    } else if (kChaseLevel >= 2 && signal === 'STRONG_BUY') {
      const previous = signal;
      signal = 'BUY';
      const overrideReason = `CHASE_LEVEL=2 (medium): ${levelReason} (${previous} → BUY)`;
      overrides.push(overrideReason);
      unmetReasons.push(overrideReason);
    } else if (kChaseLevel >= 1) {
      unmetReasons.push(`CHASE_LEVEL=1 (soft): ${levelReason} — 관측 경고 (신호 불변)`);
    }
  }

  // === 29차 fix-F — 자산군 × regime 정합 게이트 ===
  signal = applyRegimeCoherenceGate('KOSPI', signal, regime.regime, overrides, unmetReasons);

  return withSignalExplanation({
    asset: 'KOSPI',
    signal,
    conditionsMet: met,
    conditionsTotal: total,
    weightedScore: met,
    weightedMaxScore: total,
    reasons,
    unmetReasons,
    date: new Date().toISOString().split('T')[0],
  }, baseSignal, overrides);
}

/**
 * EMERGING 신호 — 영상2 §30 "신흥국 ETF, 달러약세 수혜" + 영상4 유동성·정책 렌즈
 *
 * 메인 3조건:
 *   1. DXY 약세 추세 (DXY_TREND < -0.5 or 절대값 < 103)
 *   2. 글로벌 M2 확장 (GLOBAL_M2_PROXY > 0)
 *   3. 정책 완화 방향 (manualInputs.policyDirection > 0)
 *
 * 보조:
 *   - 실질금리 하락 추세 (REAL_YIELD_TREND < -0.05) → 신흥국 유동성 수혜
 *   - DXY 장기 약세 (DXY_TREND_LONG < -2) → 구조적 우호
 */
function emergingSignal(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  profile: UserProfile,
  regime: RegimeState,
): AssetSignal {
  const reasons: string[] = [];
  const unmetReasons: string[] = [];
  let met = 0;
  // 29차 fix-H: total 을 0 에서 시작, 핵심 if/else 분기마다 total += 1 먼저 가산.
  let total = 0;

  const dxy = v(raw, 'DXY');
  const dxyTrend = dv(derived, 'DXY_TREND');
  const dxyWeak = (dxyTrend !== null && dxyTrend < -0.5) || (dxy !== null && dxy < 103);
  total += 1;
  if (dxyWeak) {
    met += 1;
    reasons.push(`DXY ${dxy?.toFixed(1) ?? '?'} (단기: ${dxyTrend?.toFixed(2) ?? '?'}) 약세 — 달러약세 수혜 (가중치 1.0)`);
  } else {
    unmetReasons.push(`DXY ${dxy?.toFixed(1) ?? '?'} 강세 — 신흥국 자본 유출 압력 (가중치 1.0 미충족)`);
  }

  const m2 = dv(derived, 'GLOBAL_M2_PROXY');
  total += 1;
  if (m2 !== null && m2 > 0) {
    met += 1;
    reasons.push(`글로벌 M2 YoY +${m2.toFixed(1)}% → 유동성 확장 (가중치 1.0)`);
  } else if (m2 !== null) {
    unmetReasons.push(`글로벌 M2 YoY ${m2.toFixed(1)}% — 유동성 위축 (가중치 1.0 미충족)`);
  } else {
    unmetReasons.push('글로벌 M2 데이터 없음 (가중치 1.0 미충족)');
  }

  const policy = profile.manualInputs?.policyDirection ?? 0;
  total += 1;
  if (policy > 0) {
    met += 1;
    reasons.push(`정책 완화 방향 (policyDirection=${policy}) → 신흥국 리스크 우호 (가중치 1.0)`);
  } else {
    unmetReasons.push(`정책 완화 미확인 (policyDirection=${policy}) (가중치 1.0 미충족)`);
  }

  // 보조
  const ryTrend = dv(derived, 'REAL_YIELD_TREND');
  if (ryTrend !== null && ryTrend < -0.05) reasons.push('실질금리 하락 추세 → 신흥국 자본 유입 우호 (보조조건)');
  const dxyTrendLong = dv(derived, 'DXY_TREND_LONG');
  if (dxyTrendLong !== null && dxyTrendLong < -2) reasons.push(`DXY 장기 ${dxyTrendLong.toFixed(2)} 구조적 약세 (보조조건)`);

  let signal: Signal;
  if (met === 3) signal = 'STRONG_BUY';
  else if (met >= 2) signal = 'BUY';
  else if (met >= 1) signal = 'HOLD';
  else signal = 'HOLD';
  const baseSignal = signal;
  const overrides: string[] = [];

  // DXY 급등 방어 완화:
  // - 단기 강세(+1 초과)는 한 단계 감속만 적용
  // - 아주 강한 달러 모멘텀(+2 초과) + 메인 조건 약함(met<=1) 에서만 REDUCE
  if (dxyTrend !== null && dxyTrend > 2 && met <= 1) {
    const previous = signal;
    signal = 'REDUCE';
    const overrideReason = `⚠️ DXY 매우 강한 단기 강세(${dxyTrend.toFixed(2)}) — 신흥국 REDUCE (${previous} → REDUCE)`;
    overrides.push(overrideReason);
    unmetReasons.push(overrideReason);
  } else if (dxyTrend !== null && dxyTrend > 1 && met < 3) {
    const previous = signal;
    signal = softenRiskSignal(signal);
    const overrideReason = `⚠️ DXY 단기 강세(${dxyTrend.toFixed(2)}) — 신흥국 한 단계 완화 (${previous} → ${signal})`;
    overrides.push(overrideReason);
    unmetReasons.push(overrideReason);
  }

  // 29차 fix-B: EMERGING weightedScore cap (일관 패턴 적용).
  if (met > total) met = total;

  // === 29차 fix-F — 자산군 × regime 정합 게이트 ===
  signal = applyRegimeCoherenceGate('EMERGING', signal, regime.regime, overrides, unmetReasons);

  return withSignalExplanation({
    asset: 'EMERGING',
    signal,
    conditionsMet: met,
    conditionsTotal: total,
    weightedScore: met,
    weightedMaxScore: total,
    reasons: reasons.length > 0 ? reasons : ['조건 미충족, 대기'],
    unmetReasons,
    date: new Date().toISOString().split('T')[0],
  }, baseSignal, overrides);
}

export function computeSignals(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  regime: RegimeState,
  profile: UserProfile
): AssetSignal[] {
  return [
    nasdaqSignal(raw, derived, profile, regime),
    kospiSignal(raw, derived, profile, regime),
    goldSignal(raw, derived, profile, regime),
    silverSignal(derived, raw, regime),
    copperSignal(derived, raw, profile, regime),
    emergingSignal(raw, derived, profile, regime),
    cashSignal(regime),
    leverageCheck(raw, derived, profile, regime),
  ].map((signal) => signal.explanation
    ? signal
    : withSignalExplanation(signal, signal.signal));
}
