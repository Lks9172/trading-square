/**
 * Execution Plan 엔진 — 영상 공통 철학 "지표는 진단, 실행은 분할매수 규칙".
 *
 * 근거:
 * - 이동평균선.md:236-257: 분할매수 3단계, 손절=지지선 이탈, 익절=단기추세선 이탈
 * - video5:99-102: "매수는 분할 3회 (1차: 현재가, 2차: 강한 하락, 3차: W 저점)"
 * - video1:211-232: "시스템적 투자 = 진입/비중/청산 규칙"
 *
 * 본 엔진은 regime + signals + derived + 현재 가격 을 입력받아 자산별 플레이북 반환.
 * 비중 결정(allocation.ts) 과 독립 — allocation은 "얼마" / execution_plan은 "언제·어떻게".
 */

import {
  AssetSignal,
  RegimeState,
  AllocationPlan,
  MarketDataPoint,
  DerivedIndicator,
  ExecutionPlan,
  ExecutionStage,
  ExecutionAction,
} from '../types/indicators';
import { ASSET_TO_ALLOC_KEY } from './asset-keys';

const vRaw = (raw: Record<string, MarketDataPoint>, k: string): number | null => raw[k]?.value ?? null;
const vDer = (d: Record<string, DerivedIndicator>, k: string): number | null => d[k]?.value ?? null;

// 7차 TOP3 Fix #3: 분할매수 default 30/30/40 표준.
// 영상1 §전략C + 이동평균선.md §분할매수 — 1·2차 30%, 3차 40% (W 반등 확인 후 더 큰 비중).
// 개별 플레이북의 stages 에 weightPct 가 이미 박혀 있으면 그 값을 우선, 없을 때만 이 fallback 사용.
export const DEFAULT_TRANCHE_WEIGHTS: readonly number[] = [30, 30, 40] as const;

function round(n: number, digits = 2): number {
  return parseFloat(n.toFixed(digits));
}

function defaultTrancheWeight(stage: 1 | 2 | 3): number {
  return DEFAULT_TRANCHE_WEIGHTS[stage - 1];
}

// Fix #8(2차 감사): allocation.ts 와 키 매핑을 engines/asset-keys.ts 로 통일.
// execution_plan 은 LEVERAGE 도 allocationPct 참조 대상이므로 포함된 채로 사용.
const ASSET_ALLOC_KEY = ASSET_TO_ALLOC_KEY;

// 21차 P1#4: tranche store entries 기반 stage status 동기화 헬퍼.
function applyExecutedStages(stages: ExecutionStage[], asset: string, entries: Array<{ asset: string; stage: number; executedAt?: string; priceAtEntry?: number | null; weightPct?: number }>): void {
  const assetEntries = entries.filter((e) => e.asset === asset);
  if (assetEntries.length === 0) return;
  const executedStageNumbers = new Set(assetEntries.map((e) => e.stage));
  for (const stage of stages) {
    if (executedStageNumbers.has(stage.stage)) {
      stage.status = 'filled';
    }
  }
}

// 21차 P1#1: avgEntry 기반 stop-loss/take-profit 절대값 계산.
function applyAbsoluteStopTake(
  asset: string,
  entries: Array<{ asset: string; stage: number; priceAtEntry?: number | null; weightPct?: number }>,
  fallbackStop: number | null,
  fallbackTake: number | null,
  stopLossPct = 5,
  takeProfitPct = 25,
): { stopPrice: number | null; takePrice: number | null; basedOnEntry: boolean } {
  const assetEntries = entries.filter(
    (e) => e.asset === asset && typeof e.priceAtEntry === 'number' && (e.priceAtEntry as number) > 0,
  );
  if (assetEntries.length === 0) {
    return { stopPrice: fallbackStop, takePrice: fallbackTake, basedOnEntry: false };
  }
  const sumWeight = assetEntries.reduce((s, e) => s + (e.weightPct ?? 33), 0);
  const wAvgEntry = sumWeight > 0
    ? assetEntries.reduce((s, e) => s + (e.priceAtEntry as number) * (e.weightPct ?? 33), 0) / sumWeight
    : assetEntries.reduce((s, e) => s + (e.priceAtEntry as number), 0) / assetEntries.length;
  const stopPrice = round(wAvgEntry * (1 - stopLossPct / 100));
  const takePrice = round(wAvgEntry * (1 + takeProfitPct / 100));
  return { stopPrice, takePrice, basedOnEntry: true };
}

function actionLabel(action: ExecutionAction): string {
  const map: Record<ExecutionAction, string> = {
    BUY_NOW: '🟢 지금 1차 매수',
    SCALE_IN: '🔵 추가 분할 대기',
    HOLD: '⚪ 관망',
    TAKE_PROFIT: '🟡 익절 준비',
    EXIT: '🔴 전량 청산',
    AVOID: '⚫ 진입 금지',
  };
  return map[action] || action;
}

/** NASDAQ 플레이북 — 이격도·200DMA·VIX·ICSA 기반 */
function nasdaqPlan(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  signal: AssetSignal,
  alloc: number,
): ExecutionPlan {
  const price = vRaw(raw, 'NASDAQ');
  const sma200 = vDer(derived, 'NASDAQ_SMA200');
  const disparity = vDer(derived, 'NASDAQ_DISPARITY');
  const vix = vRaw(raw, 'VIXCLS');
  const icsa = vRaw(raw, 'ICSA');
  const above200 = vDer(derived, 'NASDAQ_ABOVE_200DMA');
  const wBottom = vDer(derived, 'NASDAQ_W_BOTTOM');
  const monthPos = vDer(derived, 'NASDAQ_MONTH_POS');
  const channelPos = vDer(derived, 'NASDAQ_CHANNEL_POSITION');

  let action: ExecutionAction = 'HOLD';
  let reason = '조건 대기';
  const stages: ExecutionStage[] = [];
  let stopPrice: number | null = null;
  let stopCond = '';
  let takePrice: number | null = null;
  let takeCond = '';

  // ★ === 29차 P1-E #17: STAGE_FIB_ZONE_MAP — NASDAQ FIB 단계 unlock ===
  // video2 §23:27 "분할매수 단계는 fib zone 도달 시점 매핑".
  // stage1 = FIB_382 (±1% 내) / stage2 = FIB_500 / stage3 = FIB_618 + W_BOTTOM
  const fib382 = vDer(derived, 'NASDAQ_FIB_382');
  const fib500 = vDer(derived, 'NASDAQ_FIB_500');
  const fib618 = vDer(derived, 'NASDAQ_FIB_618');
  // ★ === 29차 P2-C #16: 절대가격 trigger 보강 ===
  // video3 §14:24 "여러 지지 요인 겹치는 곳" — channel mid/lower + 200DMA 절대가격을 stage 메시지에 노출.
  const channelMid = vDer(derived, 'NASDAQ_CHANNEL_MID');
  const channelLower = vDer(derived, 'NASDAQ_CHANNEL_LOWER');
  const inZone = (target: number | null, p: number | null, pct = 1): boolean => {
    if (target === null || p === null) return false;
    return Math.abs(p - target) / target * 100 <= pct;
  };

  if (signal.signal === 'STRONG_BUY') {
    action = 'BUY_NOW';
    reason = `STRONG_BUY (${signal.conditionsMet}/${signal.conditionsTotal}) — 즉시 1차 진입 후 분할`;
    const stage1Hit = inZone(fib382, price);
    const stage2Hit = inZone(fib500, price);
    const stage3Hit = inZone(fib618, price) && wBottom === 1;
    // 29차 P2-C #16: stage1 = FIB 0.382 또는 channel mid (절대가격 명시), stage2 = FIB 0.500 또는 channel lower, stage3 = FIB 0.618 + W or 200DMA
    const stage1Trigger = stage1Hit ? `FIB 0.382 zone ($${fib382?.toFixed(0)}) 도달 — 현재가 즉시 (video2 §23:27)` : (channelMid ? `현재가 즉시 (channel mid $${channelMid.toFixed(0)} 기준, video3 §14:24)` : '현재가에서 즉시');
    const stage2Trigger = `FIB 0.500 zone ($${fib500?.toFixed(0) ?? '?'}) 또는 channel lower ($${channelLower?.toFixed(0) ?? '?'}) 도달 (video3 §14:24)`;
    const stage3Trigger = wBottom === 1
      ? `FIB 0.618 zone ($${fib618?.toFixed(0) ?? '?'}) + W 반등 저점 (이미 확인, video2 §23:27) / 200DMA $${sma200?.toFixed(0) ?? '?'}`
      : `FIB 0.618 zone ($${fib618?.toFixed(0) ?? '?'}) + W 반등 저점 / 200DMA $${sma200?.toFixed(0) ?? '?'}`;
    stages.push({ stage: 1, weightPct: defaultTrancheWeight(1), triggerCondition: stage1Trigger, triggerPrice: price ?? undefined, status: 'ready' });
    stages.push({ stage: 2, weightPct: defaultTrancheWeight(2), triggerCondition: stage2Trigger, triggerPrice: fib500 ?? channelLower ?? (price ? round(price * 0.95) : undefined), status: stage2Hit ? 'ready' : 'pending' });
    stages.push({
      stage: 3,
      weightPct: defaultTrancheWeight(3),
      triggerCondition: stage3Trigger,
      triggerPrice: fib618 ?? sma200 ?? (price ? round(price * 0.9) : undefined),
      status: stage3Hit ? 'ready' : 'pending',
    });
    if (sma200) { stopPrice = round(sma200 * 0.85); stopCond = '200DMA × 0.85 이탈'; }
    if (price) { takePrice = round(price * 1.2); takeCond = '+20% 수익 또는 15년 채널 상단 터치'; }
  } else if (signal.signal === 'BUY') {
    action = 'SCALE_IN';
    reason = `BUY (${signal.conditionsMet}/${signal.conditionsTotal}) — 1차만 즉시, 2·3차 조건 대기`;
    // 29차 P2-C #16: 절대가격 명시
    stages.push({ stage: 1, weightPct: defaultTrancheWeight(1), triggerCondition: channelMid ? `현재가에서 1차 진입 (channel mid $${channelMid.toFixed(0)} 기준)` : '현재가에서 1차 진입', triggerPrice: price ?? undefined, status: 'ready' });
    stages.push({ stage: 2, weightPct: defaultTrancheWeight(2), triggerCondition: channelLower ? `이격도 -20% 또는 channel lower $${channelLower.toFixed(0)} 도달 시 (video3 §14:24)` : '이격도 -20% 도달 시', triggerPrice: channelLower ?? undefined, status: 'pending' });
    stages.push({ stage: 3, weightPct: defaultTrancheWeight(3), triggerCondition: sma200 ? `W 반등 저점 또는 200DMA $${sma200.toFixed(0)} 확인 시` : 'W 반등 저점 확인 시', triggerPrice: sma200 ?? undefined, status: 'pending' });
    if (sma200) { stopPrice = round(sma200 * 0.9); stopCond = '200DMA × 0.90 이탈'; }
  } else if (signal.signal === 'REDUCE') {
    action = 'TAKE_PROFIT';
    reason = 'REDUCE — 보유분 일부 익절';
    stages.push({ stage: 1, weightPct: 30, triggerCondition: '현재가에서 30% 익절', status: 'ready' });
    if (channelPos !== null && channelPos >= 95) { takeCond = '15년 채널 상단 도달 — 저항 확정'; }
    else if (monthPos !== null && monthPos >= 95) { takeCond = '월봉 12개월 고점 근처'; }
    else takeCond = '모멘텀 둔화';
  } else if (signal.signal === 'SELL') {
    action = 'EXIT';
    reason = 'SELL — 구조적 위험, 전량 청산';
    stages.push({ stage: 1, weightPct: 100, triggerCondition: '전량 청산', status: 'ready' });
  } else if (above200 === 0 && icsa !== null && icsa >= 300000) {
    action = 'AVOID';
    reason = '200DMA 하회 + 실업수당 30만 초과 — 구조적 위험 구간';
  } else {
    action = 'HOLD';
    reason = `HOLD (${signal.conditionsMet}/${signal.conditionsTotal}) — 진입 트리거 미충족, VIX ${vix?.toFixed(1) ?? '?'}`;
  }

  return {
    asset: 'NASDAQ',
    action,
    actionLabel: actionLabel(action),
    currentPrice: price,
    targetAllocationPct: alloc,
    stages,
    stopLoss: { price: stopPrice, condition: stopCond || '— ' },
    takeProfit: { price: takePrice, condition: takeCond || '— ' },
    validityDays: 45,
    primaryReason: reason,
  };
}

/** GOLD 플레이북 — 피보나치·200DMA·DXY */
function goldPlan(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  signal: AssetSignal,
  alloc: number,
): ExecutionPlan {
  const price = vRaw(raw, 'GOLD');
  const sma200 = vDer(derived, 'GOLD_SMA200');
  const fib382 = vDer(derived, 'GOLD_FIB_382');
  const fib500 = vDer(derived, 'GOLD_FIB_500');
  const fib618 = vDer(derived, 'GOLD_FIB_618');
  const goldZone = vDer(derived, 'GOLD_FIB_ZONE');

  let action: ExecutionAction = 'HOLD';
  let reason = '조건 대기';
  const stages: ExecutionStage[] = [];
  let stopPrice: number | null = null;
  let stopCond = '';
  let takePrice: number | null = null;
  let takeCond = '';

  // ★ === 29차 P1-E #17: STAGE_FIB_ZONE_MAP — GOLD FIB 단계 unlock ===
  // video2 §23:27 — stage1=FIB_382, stage2=FIB_500, stage3=FIB_618 + W_BOTTOM.
  const goldWBottom = vDer(derived, 'GOLD_W_BOTTOM');
  const inZoneGold = (target: number | null, p: number | null, pct = 1): boolean => {
    if (target === null || p === null) return false;
    return Math.abs(p - target) / target * 100 <= pct;
  };

  if (signal.signal === 'STRONG_BUY') {
    action = 'BUY_NOW';
    reason = `STRONG_BUY — 실질금리/DXY/CB매수 3축 이상 충족`;
    const stage1Hit = inZoneGold(fib382, price);
    const stage2Hit = inZoneGold(fib500, price);
    const stage3Hit = inZoneGold(fib618, price) && goldWBottom === 1;
    stages.push({ stage: 1, weightPct: defaultTrancheWeight(1), triggerCondition: stage1Hit ? `FIB 0.382 zone ($${fib382?.toFixed(0)}) 도달 — 즉시 (video2 §23:27)` : '현재가에서 즉시', triggerPrice: price ?? undefined, status: 'ready' });
    stages.push({ stage: 2, weightPct: defaultTrancheWeight(2), triggerCondition: `FIB 0.500 zone ($${fib500?.toFixed(0) ?? '?'}) 도달 시 (video2 §23:27)`, triggerPrice: fib500 ?? undefined, status: stage2Hit ? 'ready' : 'pending' });
    stages.push({ stage: 3, weightPct: defaultTrancheWeight(3), triggerCondition: `FIB 0.618 zone ($${fib618?.toFixed(0) ?? '?'}) + W 반등 저점 (video2 §23:27)`, triggerPrice: fib618 ?? undefined, status: stage3Hit ? 'ready' : 'pending' });
    if (sma200) { stopPrice = round(sma200 * 0.9); stopCond = '200DMA × 0.90 이탈 (실질금리 급등 시)'; }
  } else if (signal.signal === 'BUY') {
    action = 'SCALE_IN';
    reason = `BUY — 실질금리 하락 또는 DXY 약세 확인`;
    stages.push({ stage: 1, weightPct: defaultTrancheWeight(1), triggerCondition: '현재가 또는 FIB 0.382', triggerPrice: price ?? undefined, status: 'ready' });
    stages.push({ stage: 2, weightPct: defaultTrancheWeight(2), triggerCondition: 'FIB 0.5 도달 + DXY 약세', triggerPrice: fib500 ?? undefined, status: 'pending' });
    stages.push({ stage: 3, weightPct: defaultTrancheWeight(3), triggerCondition: 'FIB 0.618 도달 또는 실질금리 추가 하락 확인', triggerPrice: fib618 ?? undefined, status: 'pending' });
    if (sma200) { stopPrice = round(sma200 * 0.92); stopCond = '200DMA × 0.92 이탈'; }
  } else if (signal.signal === 'REDUCE') {
    action = 'TAKE_PROFIT';
    reason = '실질금리 상승 또는 DXY 강세 — 단기 익절';
    stages.push({ stage: 1, weightPct: 30, triggerCondition: '부분 익절 30%', status: 'ready' });
  } else if (goldZone === 3) {
    action = 'BUY_NOW';
    reason = 'FIB 0.618 하회 강한 바닥권 — 분할매수 적기';
    stages.push({ stage: 1, weightPct: defaultTrancheWeight(1), triggerCondition: '현재가에서 즉시', triggerPrice: price ?? undefined, status: 'ready' });
    stages.push({ stage: 2, weightPct: defaultTrancheWeight(2), triggerCondition: '추가 하락 -3% 시', status: 'pending' });
    stages.push({ stage: 3, weightPct: defaultTrancheWeight(3), triggerCondition: '추가 하락 -6% 또는 FIB 0.618 재확인', triggerPrice: fib618 ?? undefined, status: 'pending' });
  } else {
    action = 'HOLD';
    reason = 'HOLD — 진입 조건 미형성';
  }

  return {
    asset: 'GOLD',
    action,
    actionLabel: actionLabel(action),
    currentPrice: price,
    targetAllocationPct: alloc,
    stages,
    stopLoss: { price: stopPrice, condition: stopCond || '— ' },
    takeProfit: { price: takePrice, condition: takeCond || '— ' },
    validityDays: 60,
    primaryReason: reason,
  };
}

/** KOSPI 플레이북 — 환율 게이트·외국인 수급·W반등 */
function kospiPlan(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  signal: AssetSignal,
  alloc: number,
): ExecutionPlan {
  const price = vRaw(raw, 'KOSPI');
  const sma200 = vDer(derived, 'KOSPI_SMA200');
  const fxGreen = vDer(derived, 'KRW_FX_GREEN');
  const fxRed = vDer(derived, 'KRW_FX_RED');
  const foreignStreak = vDer(derived, 'KOSPI_FOREIGN_BUY_STREAK');
  const foreignSell = vDer(derived, 'KOSPI_FOREIGN_SELL_STREAK');
  const wBottom = vDer(derived, 'KOSPI_W_BOTTOM');
  const atm = vDer(derived, 'KOSPI_ATM_WARNING');

  let action: ExecutionAction = 'HOLD';
  let reason = '조건 대기';
  const stages: ExecutionStage[] = [];
  let stopPrice: number | null = null;
  let stopCond = '';
  let takeCond = '';

  if (fxRed === 1 && signal.signal !== 'STRONG_BUY') {
    action = 'AVOID';
    reason = '환율 1500 레드 게이트 — 외국인 매도 압력 임계';
  } else if (signal.signal === 'STRONG_BUY' || (signal.signal === 'BUY' && wBottom === 1)) {
    action = 'BUY_NOW';
    reason = wBottom === 1 ? 'W 반등 확정 — 영상5 3차 매수 트리거' : 'STRONG_BUY 조건 충족';
    stages.push({ stage: 1, weightPct: defaultTrancheWeight(1), triggerCondition: '현재가에서 즉시', triggerPrice: price ?? undefined, status: 'ready' });
    stages.push({ stage: 2, weightPct: defaultTrancheWeight(2), triggerCondition: '환율 1470 하단 + 외국인 매수 지속', status: fxGreen === 1 ? 'ready' : 'pending' });
    stages.push({ stage: 3, weightPct: defaultTrancheWeight(3), triggerCondition: 'W 반등 저점 또는 거래량 확인', status: wBottom === 1 ? 'triggered' : 'pending' });
    if (sma200) { stopPrice = round(sma200 * 0.92); stopCond = '200DMA × 0.92 이탈 또는 환율 1500+ 재진입'; }
    takeCond = '추세선 이탈 또는 월봉 소진 경고';
  } else if (signal.signal === 'BUY') {
    action = 'SCALE_IN';
    reason = 'BUY — 환율·외국인 수급 우호, 분할 진입';
    // 20차 E5: stt_kospi §4부 명시 절대 레벨 (5150 1차 지지 / 4080 2차 지지) 사용.
    stages.push({ stage: 1, weightPct: defaultTrancheWeight(1), triggerCondition: '현재가 또는 5150 1차 지지', triggerPrice: price ?? undefined, status: 'ready' });
    stages.push({ stage: 2, weightPct: defaultTrancheWeight(2), triggerCondition: '5150 이탈 후 4080 2차 지지 (stt_kospi §4부) + 환율 1480 이하', triggerPrice: 4080, status: fxGreen === 1 && foreignStreak !== null && foreignStreak >= 5 ? 'ready' : 'pending' });
    stages.push({ stage: 3, weightPct: defaultTrancheWeight(3), triggerCondition: 'W 반등 또는 강한 하락 + 외국인 매수 streak 3+', status: 'pending' });
    if (sma200) { stopPrice = round(sma200 * 0.95); stopCond = '200DMA × 0.95 이탈 또는 4080 하단 이탈'; }
  } else if (signal.signal === 'REDUCE' || signal.signal === 'SELL') {
    action = signal.signal === 'SELL' ? 'EXIT' : 'TAKE_PROFIT';
    reason = signal.signal === 'SELL' ? 'SELL — 환율 1500+ AND 200DMA 하회 극단' : 'REDUCE — 부분 익절';
    stages.push({ stage: 1, weightPct: signal.signal === 'SELL' ? 100 : 30, triggerCondition: '현재가에서 청산', status: 'ready' });
  } else if (atm === 1) {
    action = 'BUY_NOW';
    reason = 'ATM 과매도 반발 조기신호 (영상5 §112)';
    stages.push({ stage: 1, weightPct: 30, triggerCondition: '소량 선진입', status: 'ready' });
  } else if (foreignSell !== null && foreignSell >= 5) {
    action = 'AVOID';
    reason = '외국인 5일 연속 순매도 — 구조적 이탈 확인 후 재검토';
  }

  // ★ === 29차 P2-E #33: KOSPI 분할 환전 액션 ===
  // stt_kospi §09:36 "장기 채널 활용 — 하단/상단 부근 환전".
  const krwChannelPos = vDer(derived, 'USDKRW_WEEKLY_CHANNEL_POSITION');
  if (krwChannelPos !== null) {
    if (krwChannelPos >= 0.9) {
      stages.push({ stage: 3, weightPct: 30, triggerCondition: `USDKRW 주봉채널 ${krwChannelPos.toFixed(2)} ≥ 0.9 → KRW→USD 분할 환전 30% 권고 (stt_kospi §09:36)`, status: 'ready' });
    } else if (krwChannelPos <= 0.1) {
      stages.push({ stage: 3, weightPct: 30, triggerCondition: `USDKRW 주봉채널 ${krwChannelPos.toFixed(2)} ≤ 0.1 → USD→KRW 분할 환전 30% 권고 (stt_kospi §09:36)`, status: 'ready' });
    }
  }

  return {
    asset: 'KOSPI',
    action,
    actionLabel: actionLabel(action),
    currentPrice: price,
    targetAllocationPct: alloc,
    stages,
    stopLoss: { price: stopPrice, condition: stopCond || '— ' },
    takeProfit: { price: null, condition: takeCond || '— ' },
    validityDays: 45,
    primaryReason: reason,
  };
}

/** LEVERAGE 플레이북 — 이격도 -25% / VIX 35 / ICSA 30만 3조건 */
function leveragePlan(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  signal: AssetSignal,
  alloc: number,
): ExecutionPlan {
  const price = vRaw(raw, 'NASDAQ'); // 참조 가격
  const disparity = vDer(derived, 'NASDAQ_DISPARITY');

  let action: ExecutionAction = 'HOLD';
  let reason = '3조건 미충족 — 진입 금지';
  const stages: ExecutionStage[] = [];
  let stopCond = '';
  let takeCond = '';

  if (signal.signal === 'BUY') {
    action = 'BUY_NOW';
    reason = '이격도 -25% + VIX 35 + ICSA 30만 미만 — 3조건 동시 충족 (영상1 §전략C)';
    stages.push({ stage: 1, weightPct: 70, triggerCondition: '즉시 진입 (최대 15% 상한)', triggerPrice: price ?? undefined, status: 'ready' });
    stages.push({ stage: 2, weightPct: 30, triggerCondition: '-3% 추가 하락 시', status: 'pending' });
    stopCond = '진입가 -5% (영상1 "짧게")';
    takeCond = '+20% 수익 또는 이격도 -10% 복귀 시 전량';
  } else if (signal.signal === 'REDUCE') {
    action = 'EXIT';
    reason = '이격도 복귀 또는 시간 만료 — 익절';
    stages.push({ stage: 1, weightPct: 100, triggerCondition: '즉시 전량 청산', status: 'ready' });
  } else {
    action = 'AVOID';
    reason = `3조건 미충족 (이격도 ${disparity?.toFixed(1) ?? '?'})`;
  }

  return {
    asset: 'LEVERAGE',
    action,
    actionLabel: actionLabel(action),
    currentPrice: price,
    targetAllocationPct: alloc,
    stages,
    stopLoss: { price: null, condition: stopCond || '— ' },
    takeProfit: { price: null, condition: takeCond || '— ' },
    validityDays: 60,  // 영상1 "2~3개월 짧게"
    primaryReason: reason,
  };
}

/** EMERGING 플레이북 — DXY 약세 + 글로벌 M2 + 정책 완화 3축 */
function emergingPlan(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  signal: AssetSignal,
  alloc: number,
): ExecutionPlan {
  const price = vRaw(raw, 'EWZ');  // 대표 대리 심볼
  const dxy = vRaw(raw, 'DXY');
  const dxyTrend = vDer(derived, 'DXY_TREND');
  const m2 = vDer(derived, 'GLOBAL_M2_PROXY');

  let action: ExecutionAction = 'HOLD';
  let reason = '조건 대기';
  const stages: ExecutionStage[] = [];
  let stopCond = '';
  let takeCond = '';

  if (signal.signal === 'STRONG_BUY') {
    action = 'BUY_NOW';
    reason = 'DXY 약세 + M2 확장 + 정책 완화 3축 동시 충족 (영상2 §30 달러약세 수혜)';
    stages.push({ stage: 1, weightPct: defaultTrancheWeight(1), triggerCondition: '현재가에서 즉시 진입', triggerPrice: price ?? undefined, status: 'ready' });
    stages.push({ stage: 2, weightPct: defaultTrancheWeight(2), triggerCondition: 'EWZ -5% 추가 하락 시', status: 'pending' });
    stages.push({ stage: 3, weightPct: defaultTrancheWeight(3), triggerCondition: 'DXY 추가 약세 (DXY < 100) 확인', status: 'pending' });
    stopCond = 'DXY > 110 급등 또는 정책 긴축 전환';
    takeCond = 'DXY < 95 구조적 약세 완료 또는 +25% 수익';
  } else if (signal.signal === 'BUY') {
    action = 'SCALE_IN';
    reason = '신흥국 2/3 조건 충족 — 분할 진입';
    stages.push({ stage: 1, weightPct: defaultTrancheWeight(1), triggerCondition: '현재가에서 1차 진입', triggerPrice: price ?? undefined, status: 'ready' });
    stages.push({ stage: 2, weightPct: defaultTrancheWeight(2), triggerCondition: '나머지 조건 확정 시', status: 'pending' });
    stages.push({ stage: 3, weightPct: defaultTrancheWeight(3), triggerCondition: 'DXY 추가 약세 (DXY < 100) 또는 M2 확장 강화', status: 'pending' });
    stopCond = 'DXY > 107 또는 M2 YoY 마이너스 전환';
  } else if (signal.signal === 'REDUCE') {
    action = 'TAKE_PROFIT';
    reason = 'DXY 단기 강세 — 신흥국 자본 유출 경계';
    stages.push({ stage: 1, weightPct: 30, triggerCondition: '부분 익절 30%', status: 'ready' });
  } else if (dxyTrend !== null && dxyTrend > 1) {
    action = 'AVOID';
    reason = `DXY 급등 추세 (${dxyTrend.toFixed(2)}) — 자본유출 우려, 진입 지양`;
  } else {
    action = 'HOLD';
    reason = `HOLD (${signal.conditionsMet}/${signal.conditionsTotal}) — M2 ${m2?.toFixed(1) ?? '?'}% / DXY ${dxy?.toFixed(1) ?? '?'}`;
  }

  return {
    asset: 'EMERGING',
    action,
    actionLabel: actionLabel(action),
    currentPrice: price,
    targetAllocationPct: alloc,
    stages,
    stopLoss: { price: null, condition: stopCond || '— ' },
    takeProfit: { price: null, condition: takeCond || '— ' },
    validityDays: 90, // 신흥국은 달러 사이클이 길어 유효기간 길게
    primaryReason: reason,
  };
}

/** 단순 자산(SILVER/COPPER) 플레이북 — signal 기반 generic */
function genericAssetPlan(
  asset: string,
  raw: Record<string, MarketDataPoint>,
  signal: AssetSignal,
  alloc: number,
  rawKey: string,
): ExecutionPlan {
  const price = vRaw(raw, rawKey);
  let action: ExecutionAction = 'HOLD';
  let reason = `${signal.signal} (${signal.conditionsMet}/${signal.conditionsTotal})`;
  const stages: ExecutionStage[] = [];

  if (signal.signal === 'STRONG_BUY') {
    action = 'BUY_NOW';
    stages.push({ stage: 1, weightPct: defaultTrancheWeight(1), triggerCondition: '현재가에서 1차', triggerPrice: price ?? undefined, status: 'ready' });
    stages.push({ stage: 2, weightPct: defaultTrancheWeight(2), triggerCondition: '-3% 추가 하락 시', status: 'pending' });
    stages.push({ stage: 3, weightPct: defaultTrancheWeight(3), triggerCondition: '-7% 추가 하락 또는 W반등 저점', status: 'pending' });
    reason = `STRONG_BUY — 메인조건 전부 충족`;
  } else if (signal.signal === 'BUY') {
    action = 'SCALE_IN';
    stages.push({ stage: 1, weightPct: defaultTrancheWeight(1), triggerCondition: '현재가에서 1차', triggerPrice: price ?? undefined, status: 'ready' });
    stages.push({ stage: 2, weightPct: defaultTrancheWeight(2), triggerCondition: '-5% 추가 하락 시', status: 'pending' });
    stages.push({ stage: 3, weightPct: defaultTrancheWeight(3), triggerCondition: '-8% 추가 하락 또는 추세 재확인 시', status: 'pending' });
  } else if (signal.signal === 'REDUCE') {
    action = 'TAKE_PROFIT';
    stages.push({ stage: 1, weightPct: 30, triggerCondition: '부분 익절', status: 'ready' });
  } else if (signal.signal === 'SELL') {
    action = 'EXIT';
    stages.push({ stage: 1, weightPct: 100, triggerCondition: '전량 청산', status: 'ready' });
  }

  return {
    asset,
    action,
    actionLabel: actionLabel(action),
    currentPrice: price,
    targetAllocationPct: alloc,
    stages,
    stopLoss: { price: null, condition: '— ' },
    takeProfit: { price: null, condition: '— ' },
    validityDays: 45,
    primaryReason: reason,
  };
}

export async function computeExecutionPlans(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  signals: AssetSignal[],
  allocation: AllocationPlan,
  _regime: RegimeState,
): Promise<ExecutionPlan[]> {
  const allocMap = allocation.allocations;
  const plans: ExecutionPlan[] = [];

  for (const sig of signals) {
    const allocKey = ASSET_ALLOC_KEY[sig.asset];
    const alloc = allocKey ? allocMap[allocKey] ?? 0 : 0;

    if (sig.asset === 'NASDAQ') plans.push(nasdaqPlan(raw, derived, sig, alloc));
    else if (sig.asset === 'KOSPI') plans.push(kospiPlan(raw, derived, sig, alloc));
    else if (sig.asset === 'GOLD') plans.push(goldPlan(raw, derived, sig, alloc));
    else if (sig.asset === 'LEVERAGE') plans.push(leveragePlan(raw, derived, sig, alloc));
    else if (sig.asset === 'SILVER') plans.push(genericAssetPlan('SILVER', raw, sig, alloc, 'SILVER'));
    else if (sig.asset === 'COPPER') plans.push(genericAssetPlan('COPPER', raw, sig, alloc, 'COPPER'));
    else if (sig.asset === 'EMERGING') plans.push(emergingPlan(raw, derived, sig, alloc));
    // CASH 는 플레이북 없음 (보조 자산)
  }

  // 21차 P1#1+P1#4: trancheStore entries 기반 stage status 동기화 + avgEntry 절대 stop/take.
  try {
    const { listTranches } = await import('../services/trancheStore');
    const { readInvestmentPlan } = await import('../services/investment-plan');
    const entries = await listTranches();
    const plan = await readInvestmentPlan();
    const stopPct = plan.stopLossPct ?? 5;
    const takePct = plan.profitTakeTargetPct ?? 25;
    for (const p of plans) {
      applyExecutedStages(p.stages, p.asset, entries);
      const result = applyAbsoluteStopTake(p.asset, entries, p.stopLoss?.price ?? null, p.takeProfit?.price ?? null, stopPct, takePct);
      if (result.basedOnEntry) {
        p.stopLoss = { price: result.stopPrice, condition: `진입가 평균 -${stopPct}% (21차 P1#1)` };
        p.takeProfit = { price: result.takePrice, condition: `진입가 평균 +${takePct}% (video1 §전략C)` };
      }
    }
  } catch { /* trancheStore 미사용 시 plans 그대로 반환 */ }

  return plans;
}
