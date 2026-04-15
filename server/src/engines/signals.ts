import fs from 'fs';
import path from 'path';
import { MarketDataPoint, DerivedIndicator, RegimeState, AssetSignal, Signal, UserProfile } from '../types/indicators';

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

function nasdaqSignal(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  profile: UserProfile
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
  const total = 7;

  // --- 저점 카테고리 (5) ---
  const above200 = dv(derived, 'NASDAQ_ABOVE_200DMA');
  if (above200 === 0) { met++; reasons.push('200DMA 하회 (가중치 1.0)'); }
  else { unmetReasons.push('200DMA 하회 아님 (가중치 1.0 미충족)'); }

  const icsa = v(raw, 'ICSA');
  if (icsa !== null && icsa < 300000) { met++; reasons.push(`실업수당 ${Math.round(icsa / 1000)}K < 300K (가중치 1.0)`); }
  else { unmetReasons.push('실업수당 300K 미만 조건 미충족 (가중치 1.0 미충족)'); }

  const vix = v(raw, 'VIXCLS');
  if (vix !== null && vix > 30) { met++; reasons.push(`VIX ${vix.toFixed(1)} > 30 (가중치 1.0)`); }
  else { unmetReasons.push('VIX 30 초과 조건 미충족 (가중치 1.0 미충족)'); }

  const disparity = dv(derived, 'NASDAQ_DISPARITY');
  if (disparity !== null && disparity < -10) { met++; reasons.push(`이격도 ${disparity.toFixed(1)}% < -10% (가중치 1.0)`); }
  else if (disparity !== null) { unmetReasons.push(`이격도 ${disparity.toFixed(1)}% → -10% 미만 미충족 (가중치 1.0 미충족)`); }
  else { unmetReasons.push('이격도 데이터 없음 (가중치 1.0 미충족)'); }

  const fng = v(raw, 'FEAR_GREED');
  if (fng !== null && fng < 25) { met++; reasons.push(`F&G ${fng} < 25 (가중치 1.0)`); }
  else { unmetReasons.push('Fear & Greed 25 미만 조건 미충족 (가중치 1.0 미충족)'); }

  // Fix #4: PSYCH_SUBSCORE 소비 — F&G · PC Ratio 10D · AAII · NAAIM 가중평균이 극공포(≤0.2) 면 +1 보너스.
  // 저점 확인 카테고리 보강. total 을 증가시키진 않아 BUY/STRONG_BUY 기준은 그대로지만 저점 매수 가점이 된다.
  const psych = dv(derived, 'PSYCH_SUBSCORE');
  if (psych !== null && psych <= 0.2) {
    met++;
    reasons.push(`심리 서브스코어 ${psych.toFixed(2)} ≤ 0.20 → 극공포 저점 가점 (보조 +1)`);
  }

  // --- 유동성 카테고리 (1) ---
  // RRP 감소(시장 유동성 유입) OR 글로벌 M2 YoY 양수(글로벌 유동성 확장) 중 하나 이상.
  const rrpDir = dv(derived, 'RRP_DIRECTION');
  const globalM2 = dv(derived, 'GLOBAL_M2_PROXY');
  const rrpLoosening = rrpDir !== null && rrpDir < 0;
  const m2Expanding = globalM2 !== null && globalM2 > 0;
  if (rrpLoosening || m2Expanding) {
    met++;
    reasons.push(
      `유동성 확장 (${rrpLoosening ? `RRP ${rrpDir?.toFixed(0)} 감소` : ''}` +
      `${rrpLoosening && m2Expanding ? ' · ' : ''}` +
      `${m2Expanding ? `글로벌 M2 YoY ${globalM2?.toFixed(1)}%` : ''}, 가중치 1.0)`
    );
  } else {
    unmetReasons.push(`유동성 확장 미충족 (RRP ${rrpDir?.toFixed(0) ?? '?'}, M2 ${globalM2?.toFixed(1) ?? '?'}%, 가중치 1.0 미충족)`);
  }

  // --- 정책 카테고리 (1) ---
  // 완화 방향 (policyDirection > 0: 금리인하·QE 기조) 이면 위험자산 우호.
  const policy = profile.manualInputs?.policyDirection ?? 0;
  if (policy > 0) {
    met++;
    reasons.push(`정책 완화 방향 (policyDirection=${policy}, 가중치 1.0)`);
  } else {
    unmetReasons.push(`정책 완화 미충족 (policyDirection=${policy}, 가중치 1.0 미충족)`);
  }

  const cross = dv(derived, 'NASDAQ_CROSS');
  if (cross === -1) { reasons.push('데드크로스 발생 → 역발상 분할매수 구간 (보조조건)'); }
  else if (cross === 1) { unmetReasons.push('골든크로스 발생 → 추격매수 주의 (보조조건)'); }
  else if (cross === -0.5) { reasons.push('역배열 유지 (50DMA < 200DMA, 보조조건)'); }

  const chaseNasdaq = dv(derived, 'CHASE_NASDAQ');
  if (chaseNasdaq !== null && chaseNasdaq > 15) { unmetReasons.push(`⚠️ 나스닥 20일 +${chaseNasdaq.toFixed(1)}% → 추격매수 주의 (보조조건)`); }

  const xlk = dv(derived, 'SECTOR_XLK');
  if (xlk !== null && xlk > 0) { reasons.push(`XLK 기술섹터 +${xlk.toFixed(1)}% → 성장주 랠리 질 양호 (보조조건)`); }
  else if (xlk !== null && xlk < 0) { unmetReasons.push(`XLK 기술섹터 ${xlk.toFixed(1)}% → 성장주 주도력 약함 (보조조건)`); }

  // 멀티 타임프레임 경고 (영상5 패턴 — 매수 신호 감쇠 요소)
  const mtfExhaustion = dv(derived, 'NASDAQ_MONTHLY_EXHAUSTION');
  const mtfReversal = dv(derived, 'NASDAQ_WEEKLY_REVERSAL');
  const mtfMonthPos = dv(derived, 'NASDAQ_MONTH_POS');
  if (mtfExhaustion === 1) { unmetReasons.push('⚠️ 월봉 소진 경고: 3개월 연속 장대양봉 + 아래꼬리 없음 (보조조건)'); }
  if (mtfReversal === 1) { unmetReasons.push('⚠️ 주봉 반전 경고: 상승 추세 후 장대음봉 (보조조건)'); }
  if (mtfMonthPos !== null && mtfMonthPos >= 95) { unmetReasons.push(`⚠️ 월봉 위치 ${mtfMonthPos.toFixed(0)}% → 12개월 고점 근처, 추격매수 주의 (보조조건)`); }
  else if (mtfMonthPos !== null && mtfMonthPos <= 15) { reasons.push(`월봉 위치 ${mtfMonthPos.toFixed(0)}% → 저점권, 분할매수 구간 (보조조건)`); }

  if (icsa !== null && icsa >= 300000 && above200 === 0) {
    return {
      asset: 'NASDAQ',
      signal: 'SELL',
      conditionsMet: met,
      conditionsTotal: total,
      weightedScore: met,
      weightedMaxScore: total,
      reasons: ['200DMA 하회 + 실업수당 30만 초과 → 구조적 위험'],
      unmetReasons,
      date: new Date().toISOString().split('T')[0],
    };
  }

  // total=7 기준 등급 경계 — STRONG_BUY 는 "저점 대부분 + 유동성 or 정책" 수준.
  // Fix #1: 기존 3단계 [3,4,5] 에 REDUCE/SELL 하한을 명시해 저점에서도 실제 강등 가능하게 복구.
  //   strongBuy=5 (기존 유지), buy=4 (기존 유지), hold=3 (기존 유지)
  //   reduce=2 (total-5, 1~2개만 충족 시 약세), sell=0 (아무것도 충족 없음)
  let signal = signalFromScore(met, total, { sell: 0, reduce: 2, hold: 3, buy: 4, strongBuy: 5 });

  // Fix #2: NASDAQ 과열 REDUCE override.
  // 4개 체크 중 2개 이상 발동 시 met 와 무관하게 REDUCE 강등(영상1 §추격매수 금지).
  //   a) 이격도 ≥ +25%  (200DMA 대비 과열)
  //   b) F&G ≥ 85       (극단 탐욕)
  //   c) VIX < 16       (변동성 방심)
  //   d) NASDAQ_CHASE_WARNING === 1  (이격률 ±15% 20일 지속)
  const overheatFlags: string[] = [];
  if (disparity !== null && disparity >= 25) overheatFlags.push(`이격도 +${disparity.toFixed(1)}% ≥ 25%`);
  if (fng !== null && fng >= 85) overheatFlags.push(`F&G ${fng} ≥ 85 극탐욕`);
  if (vix !== null && vix < 16) overheatFlags.push(`VIX ${vix.toFixed(1)} < 16 방심`);
  const chaseWarning = dv(derived, 'NASDAQ_CHASE_WARNING');
  if (chaseWarning === 1) overheatFlags.push('CHASE_WARNING (이격률 ±15% 20일 지속)');
  if (overheatFlags.length >= 2 && signal !== 'SELL') {
    signal = 'REDUCE';
    unmetReasons.push(`과열 REDUCE override: ${overheatFlags.join(' · ')}`);
  }

  return {
    asset: 'NASDAQ',
    signal,
    conditionsMet: met,
    conditionsTotal: total,
    weightedScore: met,
    weightedMaxScore: total,
    reasons,
    unmetReasons,
    date: new Date().toISOString().split('T')[0],
  };
}

function goldSignal(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>,
  profile: UserProfile
): AssetSignal {
  const reasons: string[] = [];
  const unmetReasons: string[] = [];
  let score = 0;
  let metCount = 0;
  const maxScore = 8;

  const realYield = dv(derived, 'REAL_YIELD');
  const ryTrend = dv(derived, 'REAL_YIELD_TREND');
  const ryFalling = ryTrend !== null ? ryTrend < -0.05 : (realYield !== null && realYield < 1.0);
  const ryLabel = ryTrend !== null ? `추세 ${ryTrend.toFixed(3)}` : (realYield !== null ? `절대값 ${realYield.toFixed(2)}% (추세 데이터 없어 1.0% 기준 fallback)` : '데이터 없음');
  if (ryFalling) { score += 3; metCount += 1; reasons.push(`실질금리 하락 확인 (${ryLabel}, 가중치 3.0)`); }
  else { unmetReasons.push(`실질금리 하락 미충족 (${ryLabel}, 가중치 3.0 미충족)`); }

  const dxy = v(raw, 'DXY');
  const dxyTrend = dv(derived, 'DXY_TREND');
  const dxyTrendLong = dv(derived, 'DXY_TREND_LONG');
  const dxyWeak = dxyTrend !== null ? dxyTrend < -0.5 : (dxy !== null && dxy < 103);
  if (dxyWeak) { score += 2; metCount += 1; reasons.push(`DXY ${dxy?.toFixed(1) ?? '?'} (단기: ${dxyTrend?.toFixed(2) ?? '?'}, 장기: ${dxyTrendLong?.toFixed(2) ?? '?'}, 약세, 가중치 2.0)`); }
  else { unmetReasons.push(`DXY 약세 추세 미충족 (단기: ${dxyTrend?.toFixed(2) ?? '?'}, 장기: ${dxyTrendLong?.toFixed(2) ?? '?'}, 가중치 2.0 미충족)`); }
  if (dxyTrendLong !== null && dxyTrendLong < -2) { reasons.push('DXY 구조적 약세 확인 → 금 장기 우호 (보조조건)'); }

  if (profile.manualInputs.cbBuying) { score += 1.5; metCount += 1; reasons.push('중앙은행 매수 지속 (가중치 1.5)'); }
  else { unmetReasons.push('중앙은행 매수 지속 아님 (가중치 1.5 미충족)'); }

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

  const pct = (score / maxScore) * 100;

  if (realYield !== null && realYield > 2.0 && dxy !== null && dxy > 106) {
    return {
      asset: 'GOLD',
      signal: 'HOLD',
      conditionsMet: metCount,
      conditionsTotal: 4,
      weightedScore: Number(score.toFixed(1)),
      weightedMaxScore: maxScore,
      reasons: ['실질금리 상승 + DXY 강세 → 지정학만으로 매수 위험'],
      unmetReasons,
      date: new Date().toISOString().split('T')[0],
    };
  }

  let signal: Signal;
  if (pct > 70) signal = 'STRONG_BUY';
  else if (pct > 50) signal = 'BUY';
  else if (pct > 30) signal = 'HOLD';
  else signal = 'REDUCE';

  const goldFibZone = dv(derived, 'GOLD_FIB_ZONE');
  if (signal === 'REDUCE' && goldFibZone !== null && goldFibZone >= 2) {
    signal = 'HOLD';
    reasons.push(`피보나치 바닥권(구간 ${goldFibZone}) → 최소 HOLD 보장`);
  }
  if (signal === 'HOLD' && goldFibZone !== null && goldFibZone >= 3 && goldDisparity !== null && goldDisparity <= -15) {
    const hardMacroBlock = (realYield !== null && realYield > 2.5) && (dxy !== null && dxy > 106);
    if (!hardMacroBlock) {
      signal = 'BUY';
      reasons.push(`강한 바닥권(피보 ${goldFibZone}, 이격도 ${goldDisparity.toFixed(1)}%) → BUY 승격`);
    }
  }

  return {
    asset: 'GOLD',
    signal,
    conditionsMet: metCount,
    conditionsTotal: 4,
    weightedScore: Number(score.toFixed(1)),
    weightedMaxScore: maxScore,
    reasons,
    unmetReasons,
    date: new Date().toISOString().split('T')[0],
  };
}

function silverSignal(
  derived: Record<string, DerivedIndicator>,
  raw: Record<string, MarketDataPoint>,
  regime: RegimeState,
): AssetSignal {
  const reasons: string[] = [];
  const unmetReasons: string[] = [];
  let met = 0;
  const total = 2;

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

  let signal: Signal;
  if (met === 2 && auxMet >= 2) signal = 'STRONG_BUY';
  else if (met === 2) signal = 'BUY';
  else if (met === 1 && auxMet >= 2) { signal = 'BUY'; reasons.push('메인 1개 + 보조 2개 충족 → BUY 승격 (보조조건)'); }
  else signal = 'HOLD';

  if (auxMet === 0 && signal === 'BUY') {
    signal = 'HOLD';
    unmetReasons.push('경기방향 보조조건 전부 미충족 → BUY 차단 (보조조건)');
  }

  return {
    asset: 'SILVER',
    signal,
    conditionsMet: met,
    conditionsTotal: total,
    weightedScore: met,
    weightedMaxScore: total,
    reasons: reasons.length > 0 ? reasons : ['조건 미충족, 대기'],
    unmetReasons,
    date: new Date().toISOString().split('T')[0],
  };
}

function copperSignal(
  derived: Record<string, DerivedIndicator>,
  raw: Record<string, MarketDataPoint>,
  profile: UserProfile
): AssetSignal {
  const reasons: string[] = [];
  const unmetReasons: string[] = [];
  let met = 0;
  const total = 3;

  const icsa = v(raw, 'ICSA');
  if (icsa !== null && icsa < 250000) { met++; reasons.push('실업수당 감소 추세 (가중치 1.0)'); }
  else { unmetReasons.push('실업수당 감소 조건 미충족 (가중치 1.0 미충족)'); }

  const cgr = dv(derived, 'COPPER_GOLD_RATIO');
  if (cgr !== null && cgr > 0.00125) { met++; reasons.push(`구리금비 ${cgr.toFixed(6)} 상승 우위 (가중치 1.0)`); }
  else if (cgr !== null) { unmetReasons.push(`구리금비 ${cgr.toFixed(6)} 아직 약함 (가중치 1.0 미충족)`); }
  else { unmetReasons.push('구리금비 데이터 없음 (가중치 1.0 미충족)'); }

  const ismManual = profile.manualInputs.ismPmi;
  const ismAuto = dv(derived, 'ISM_PROXY');
  const ismValue = ismManual ?? ismAuto;
  if (ismValue !== null && ismValue >= 50) { met++; reasons.push(`ISM ${ismValue.toFixed(1)} ≥ 50 확장 ${ismManual ? '(수동)' : '(자동)'} (가중치 1.0)`); }
  else if (ismValue !== null && ismValue >= 48) { reasons.push(`ISM ${ismValue.toFixed(1)} 바닥 근접 (보조조건)`); }
  else if (ismValue !== null) { unmetReasons.push(`ISM ${ismValue.toFixed(1)} 수축 구간 (가중치 1.0 미충족)`); }
  else { unmetReasons.push('ISM 데이터 없음 (가중치 1.0 미충족)'); }

  const xli = dv(derived, 'SECTOR_XLI');
  const xle = dv(derived, 'SECTOR_XLE');
  if (xli !== null && xli > 0) { reasons.push(`XLI 산업재 +${xli.toFixed(1)}% → 경기민감섹터 강세 (보조조건)`); }
  else if (xli !== null && xli < 0) { unmetReasons.push(`XLI 산업재 ${xli.toFixed(1)}% → 경기회복 신뢰 약화 (보조조건)`); }
  if (xle !== null && xle > 5) { unmetReasons.push(`XLE 에너지 +${xle.toFixed(1)}% → 유가/전쟁 주도 가능성 (보조조건)`); }

  if (reasons.length === 0) reasons.push('조건 미충족, 대기');

  // 영상2 명시적 3조건 동시 충족 복합 플래그
  const strongSetup = dv(derived, 'COPPER_STRONG_SETUP');
  if (strongSetup === 1) reasons.push('🟢🟢 구리 강매수 3조건 복합 (ISM+금구리비+ICSA) 전부 충족 — 영상2 명시');

  let signal: Signal;
  if (met >= 3) signal = 'STRONG_BUY';
  else if (met >= 2) signal = 'BUY';
  else signal = 'HOLD';

  return {
    asset: 'COPPER',
    signal,
    conditionsMet: met,
    conditionsTotal: total,
    weightedScore: met,
    weightedMaxScore: total,
    reasons,
    unmetReasons,
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
    weightedScore: 0,
    weightedMaxScore: 0,
    reasons: [reasons[regime.regime] ?? ''],
    unmetReasons: [],
    date: new Date().toISOString().split('T')[0],
  };
}

function leverageCheck(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>
): AssetSignal {
  const reasons: string[] = [];
  const unmetReasons: string[] = [];
  let met = 0;
  const total = 3;

  const disparity = dv(derived, 'NASDAQ_DISPARITY');
  if (disparity !== null && disparity <= -25) { met++; reasons.push(`이격도 ${disparity.toFixed(1)}% ≤ -25% (가중치 1.0)`); }
  else { unmetReasons.push('이격도 -25% 이하 조건 미충족 (가중치 1.0 미충족)'); }

  const vix = v(raw, 'VIXCLS');
  if (vix !== null && vix >= 35) { met++; reasons.push(`VIX ${vix.toFixed(1)} ≥ 35 (가중치 1.0)`); }
  else { unmetReasons.push('VIX 35 이상 조건 미충족 (가중치 1.0 미충족)'); }

  const icsa = v(raw, 'ICSA');
  if (icsa !== null && icsa < 300000) { met++; reasons.push(`실업수당 ${Math.round(icsa / 1000)}K < 300K (가중치 1.0)`); }
  else { unmetReasons.push('실업수당 300K 미만 조건 미충족 (가중치 1.0 미충족)'); }

  if (disparity !== null && disparity >= 0 && met < 3) {
    return {
      asset: 'LEVERAGE',
      signal: 'REDUCE',
      conditionsMet: met,
      conditionsTotal: total,
      weightedScore: met,
      weightedMaxScore: total,
      reasons: [`이격도 ${disparity.toFixed(1)}% → 200DMA 복귀/초과. 레버리지 익절 구간 (목표 20~30% 도달 추정)`],
      unmetReasons,
      date: new Date().toISOString().split('T')[0],
    };
  }

  if (disparity !== null && disparity > -10 && disparity < 0 && met < 3) {
    reasons.push(`이격도 ${disparity.toFixed(1)}% → 회복 중. 목표 수익 근접. 익절 준비 (보조조건)`);
  }

  if (vix !== null && vix < 20 && disparity !== null && disparity > -15) {
    unmetReasons.push(`⚠️ VIX ${vix.toFixed(1)} 안정 + 이격도 ${disparity.toFixed(1)}% → 횡보 원금잠식 위험 (보조조건)`);
  }

  const today = new Date().toISOString().split('T')[0];
  let signal: Signal = met === 3 ? 'BUY' : 'HOLD';
  const decisionReasons = met === 3
    ? [...reasons, '3조건 충족 → 2x ETF 최대 15% 허용']
    : [...reasons, `${3 - met}개 조건 미충족 → 레버리지 불허`];

  // === 시간 기반 청산 (영상1 §전략C) ===
  const entryDate = readLeverageEntry();
  if (signal === 'BUY') {
    if (!entryDate) {
      writeLeverageEntry(today);
      decisionReasons.push(`레버리지 진입일 ${today} 기록 (상한 ${LEVERAGE_FORCE_EXIT_DAYS}일)`);
    } else {
      const elapsed = daysBetween(today, entryDate);
      if (elapsed >= LEVERAGE_FORCE_EXIT_DAYS) {
        signal = 'REDUCE';
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
    // BUY 아닌 상태 → 포지션 종료로 간주, 진입일 리셋
    clearLeverageEntry();
    decisionReasons.push(`신호 종료 → 진입일 기록 삭제 (다음 BUY 시 재기록)`);
  }

  return {
    asset: 'LEVERAGE',
    signal,
    conditionsMet: met,
    conditionsTotal: total,
    weightedScore: met,
    weightedMaxScore: total,
    reasons: decisionReasons,
    unmetReasons,
    date: today,
  };
}

function kospiSignal(
  raw: Record<string, MarketDataPoint>,
  derived: Record<string, DerivedIndicator>
): AssetSignal {
  const reasons: string[] = [];
  const unmetReasons: string[] = [];
  let met = 0;
  const total = 7;

  const above200 = dv(derived, 'KOSPI_ABOVE_200DMA');
  if (above200 === 0) { met++; reasons.push('코스피 200DMA 하회 (가중치 1.0)'); }
  else { unmetReasons.push('코스피 200DMA 상회 중 (가중치 1.0 미충족)'); }

  const fxLevel = dv(derived, 'KRW_FX_LEVEL');
  if (fxLevel !== null && fxLevel >= 1) { met++; reasons.push(`환율 우호 (레벨 ${fxLevel}, 가중치 1.0)`); }
  else { unmetReasons.push('환율 1480원 이하 조건 미충족 (가중치 1.0 미충족)'); }

  const disparity = dv(derived, 'KOSPI_DISPARITY');
  if (disparity !== null && disparity < -15) { met++; reasons.push(`코스피 이격도 ${disparity.toFixed(1)}% < -15% (가중치 1.0)`); }
  else { unmetReasons.push('코스피 이격도 -15% 이하 조건 미충족 (가중치 1.0 미충족)'); }

  const wti = v(raw, 'WTI');
  if (wti !== null && wti < 80) { met++; reasons.push(`유가 $${wti.toFixed(1)} < $80 안정 (가중치 1.0)`); }
  else { unmetReasons.push('유가 $80 미만 조건 미충족 (가중치 1.0 미충족)'); }

  const chaseKospi = dv(derived, 'CHASE_KOSPI');
  if (chaseKospi !== null && chaseKospi > 15) { unmetReasons.push(`⚠️ 코스피 20일 +${chaseKospi.toFixed(1)}% → 추격매수 주의 (보조조건)`); }

  const vix = v(raw, 'VIXCLS');
  if (vix !== null && vix < 25) { met++; reasons.push(`VIX ${vix.toFixed(1)} < 25 안정 (가중치 1.0)`); }
  else { unmetReasons.push('VIX 25 미만 조건 미충족 (가중치 1.0 미충족)'); }

  const volumeConfirm = dv(derived, 'KOSPI_VOLUME_CONFIRM');
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
  // 극단 이벤트 보조 경고
  if (foreignExtreme === 1) unmetReasons.push(`⚠️ 외국인 20일 누적 +3조 초과 — 단기 과열 구간 (보조조건)`);
  else if (foreignExtreme === -1) reasons.push(`외국인 20일 누적 -3조 초과 — 과매도 반등 후보 (보조조건)`);
  if (foreignSellStreak !== null && foreignSellStreak >= 5) {
    unmetReasons.push(`⚠️ 외국인 ${foreignSellStreak}일 연속 순매도 → 구조적 이탈 경고 (보조조건)`);
  }

  // 영상5 이중 게이트: 환율 1480↓ 그린 / 1500↑ 레드 (단일 KRW_FX_LEVEL 보완 보조조건)
  const fxGreen = dv(derived, 'KRW_FX_GREEN');
  const fxRed = dv(derived, 'KRW_FX_RED');
  if (fxGreen === 1) reasons.push(`환율 ≤1480 그린 게이트 — 외국인 복귀 우호 (보조조건, 영상5 §3-1)`);
  if (fxRed === 1) unmetReasons.push(`⚠️ 환율 ≥1500 레드 게이트 — 외국인 매도 압력 임계 (보조조건, 영상5 §3-1)`);

  const usdkrw = v(raw, 'USDKRW');
  if (usdkrw !== null && usdkrw >= 1500 && above200 === 0) {
    return {
      asset: 'KOSPI',
      signal: 'SELL',
      conditionsMet: met,
      conditionsTotal: total,
      weightedScore: met,
      weightedMaxScore: total,
      reasons: ['코스피 200DMA 하회 + 환율 1500원 돌파 → 외국인 매도 압력 극대화'],
      unmetReasons,
      date: new Date().toISOString().split('T')[0],
    };
  }

  // Fix #1: total=7 기준 [2,3,4] 에 REDUCE/SELL 하한을 명시. 기존 HOLD 시작 met=2 는 유지하고
  // met=1 만 REDUCE, met=0 만 SELL 로 강등. KOSPI 는 환율·외인·거래량 축이 하나라도 깨지면
  // met 급락 가능하므로 total-5=2 대신 보수적으로 reduce=1 채택.
  let signal = signalFromScore(met, total, { sell: 0, reduce: 1, hold: 2, buy: 3, strongBuy: 4 });

  const trendRecovery = dv(derived, 'KOSPI_TREND_RECOVERY');
  const trendConfirmCount = [
    trendRecovery === 1 ? 1 : 0,
    volumeConfirm === 1 ? 1 : 0,
    (fxLevel !== null && fxLevel >= 1) ? 1 : 0,
  ].reduce((a, b) => a + b, 0);

  if (trendConfirmCount < 2 && (signal === 'STRONG_BUY')) {
    signal = 'BUY';
    reasons.push(`추세전환 3조건 ${trendConfirmCount}/3 미충족 → BUY 상한 (보조조건)`);
  }
  if (trendConfirmCount === 0 && (signal === 'BUY' || signal === 'STRONG_BUY')) {
    signal = 'HOLD';
    reasons.push(`추세전환 3조건 전부 미충족 → HOLD 상한 (보조조건)`);
  }

  // Fix #2: KOSPI 과열 REDUCE override.
  // 3개 체크 중 2개 이상 발동 시 REDUCE 강등(영상5 "환율 5% 상승 대비 외인 매도 2배 과잉" 경고 포함).
  //   a) 이격도 ≥ +20%
  //   b) KOSPI_CHASE_WARNING === 1  (이격률 ±15% 20일 지속)
  //   c) KOSPI_FX_ELASTICITY_DEVIATION ≥ 2  (외인 실매도가 환율 기대 대비 2배 이상)
  const kOverheatFlags: string[] = [];
  if (disparity !== null && disparity >= 20) kOverheatFlags.push(`코스피 이격도 +${disparity.toFixed(1)}% ≥ 20%`);
  const kChaseWarning = dv(derived, 'KOSPI_CHASE_WARNING');
  if (kChaseWarning === 1) kOverheatFlags.push('CHASE_WARNING (이격률 ±15% 20일 지속)');
  const fxElasticity = dv(derived, 'KOSPI_FX_ELASTICITY_DEVIATION');
  if (fxElasticity !== null && fxElasticity >= 2) kOverheatFlags.push(`FX_ELASTICITY_DEVIATION ${fxElasticity.toFixed(2)} ≥ 2 (외인 과매도 ATM화)`);
  if (kOverheatFlags.length >= 2 && signal !== 'SELL') {
    signal = 'REDUCE';
    unmetReasons.push(`과열 REDUCE override: ${kOverheatFlags.join(' · ')}`);
  }

  return {
    asset: 'KOSPI',
    signal,
    conditionsMet: met,
    conditionsTotal: total,
    weightedScore: met,
    weightedMaxScore: total,
    reasons,
    unmetReasons,
    date: new Date().toISOString().split('T')[0],
  };
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
): AssetSignal {
  const reasons: string[] = [];
  const unmetReasons: string[] = [];
  let met = 0;
  const total = 3;

  const dxy = v(raw, 'DXY');
  const dxyTrend = dv(derived, 'DXY_TREND');
  const dxyWeak = (dxyTrend !== null && dxyTrend < -0.5) || (dxy !== null && dxy < 103);
  if (dxyWeak) {
    met += 1;
    reasons.push(`DXY ${dxy?.toFixed(1) ?? '?'} (단기: ${dxyTrend?.toFixed(2) ?? '?'}) 약세 — 달러약세 수혜 (가중치 1.0)`);
  } else {
    unmetReasons.push(`DXY ${dxy?.toFixed(1) ?? '?'} 강세 — 신흥국 자본 유출 압력 (가중치 1.0 미충족)`);
  }

  const m2 = dv(derived, 'GLOBAL_M2_PROXY');
  if (m2 !== null && m2 > 0) {
    met += 1;
    reasons.push(`글로벌 M2 YoY +${m2.toFixed(1)}% → 유동성 확장 (가중치 1.0)`);
  } else if (m2 !== null) {
    unmetReasons.push(`글로벌 M2 YoY ${m2.toFixed(1)}% — 유동성 위축 (가중치 1.0 미충족)`);
  } else {
    unmetReasons.push('글로벌 M2 데이터 없음 (가중치 1.0 미충족)');
  }

  const policy = profile.manualInputs?.policyDirection ?? 0;
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

  // DXY 급등 방어: DXY_TREND > +1 이면 REDUCE 강등
  if (dxyTrend !== null && dxyTrend > 1 && met < 3) {
    signal = 'REDUCE';
    unmetReasons.push('⚠️ DXY 단기 강세 — 신흥국 자본 유출 경계 (보조조건)');
  }

  return {
    asset: 'EMERGING',
    signal,
    conditionsMet: met,
    conditionsTotal: total,
    weightedScore: met,
    weightedMaxScore: total,
    reasons: reasons.length > 0 ? reasons : ['조건 미충족, 대기'],
    unmetReasons,
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
    nasdaqSignal(raw, derived, profile),
    kospiSignal(raw, derived),
    goldSignal(raw, derived, profile),
    silverSignal(derived, raw, regime),
    copperSignal(derived, raw, profile),
    emergingSignal(raw, derived, profile),
    cashSignal(regime),
    leverageCheck(raw, derived),
  ];
}
