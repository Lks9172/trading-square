/**
 * 전체 포트폴리오 자산 비율 Monte Carlo sweep.
 *
 * 각 국면마다 영상/PRD 철학 제약 내에서 N개 랜덤 포트폴리오를 샘플링하고
 * (다른 5국면은 현 PRD 유지) backtest 1Y. 국면별 Top-5 → 3Y/5Y 교차검증.
 * 마지막으로 각 국면 Top-1 합친 composed optimal 도 검증.
 *
 * === 실행 ===
 *
 *   # 기본 (top1 국면별 + top-decile 평균 동시 출력, composed=top1)
 *   npx tsx src/scripts/portfolio-sweep.ts [N=80]
 *
 *   # top-decile 재선정 모드 (BASE_ALLOCATIONS 재선정 후보 강조)
 *   npx tsx src/scripts/portfolio-sweep.ts --mode=decile [N=80]
 *   #   → composed 를 각 국면의 top-decile 평균으로 구성해 backtest
 *   #   → 샘플 내 과적합 완화 후보 제시용. 실제 숫자 교체는 사용자 검토 후 별도 진행.
 *
 * 환경변수 PORTFOLIO_SWEEP_MODE=decile 로도 전환 가능.
 *
 * === 용도 ===
 * BASE_ALLOCATIONS (server/src/engines/allocation.ts) 교체 후보 산출.
 * 현 BASE 가 Monte Carlo top1 (샘플 내 최대) 에 과적합될 수 있다는 금융감사 지적에 대응한다.
 * top-decile 평균은 상위 10% 조합의 평균이라 단일 극단에 덜 편향.
 *
 * === 주의 ===
 * 이 스크립트는 BASE_ALLOCATIONS 를 자동 덮어쓰지 않는다. 출력만 제시하며, 실제 반영은 별도 커밋.
 */

import fs from 'fs/promises';
import path from 'path';
import { readHistory } from '../state/history-store';
import { classifyRegime } from '../engines/regime';
import { computeSignals } from '../engines/signals';
import { computeAllocation } from '../engines/allocation';
import { DEFAULT_PROFILE } from '../state/cache';
import { MarketDataPoint, DerivedIndicator, Regime } from '../types/indicators';

type Template = Record<Regime, Record<string, number>>;

const CURRENT_PRD: Template = {
  RISK_ON:        { cash: 10, nasdaq: 40, leverage: 0,  gold: 15, silver: 5, copper: 10, korea: 10, emerging: 10 },
  NEUTRAL:        { cash: 20, nasdaq: 35, leverage: 0,  gold: 20, silver: 5, copper: 5,  korea: 10, emerging: 5  },
  CAUTION:        { cash: 30, nasdaq: 25, leverage: 0,  gold: 25, silver: 0, copper: 5,  korea: 10, emerging: 5  },
  CORRECTION:     { cash: 25, nasdaq: 30, leverage: 0,  gold: 25, silver: 0, copper: 5,  korea: 10, emerging: 5  },
  PANIC_BUT_OK:   { cash: 15, nasdaq: 35, leverage: 10, gold: 20, silver: 5, copper: 5,  korea: 5,  emerging: 5  },
  RECESSION_RISK: { cash: 50, nasdaq: 15, leverage: 0,  gold: 25, silver: 0, copper: 0,  korea: 5,  emerging: 5  },
  // Fix #5: 레짐 8종 확장 — portfolio-sweep 탐색 대상 아님, 타입 만족용 고정 방어 비중.
  STAGFLATION:    { cash: 25, nasdaq: 15, leverage: 0,  gold: 30, silver: 10, copper: 5,  korea: 8,  emerging: 7  },
  BOND_VIGILANTE: { cash: 30, nasdaq: 10, leverage: 0,  gold: 35, silver: 8,  copper: 3,  korea: 7,  emerging: 7  },
};

/** 각 국면의 자산별 [lo, hi] 제약. 영상/PRD 철학 최소 준수. */
const CONSTRAINTS: Record<Regime, Record<string, [number, number]>> = {
  // 2026-04 재조정: validateEnvelopeConstraints 와 정합 (원문 근거 없는 수치는 제거).
  //   - RISK_ON/NEUTRAL: 원문 수치 하한 없음 → 넓은 탐색 범위 복원
  //   - CAUTION/CORRECTION/RECESSION: cash ≥ 30 (video5_analysis), silver ≤ 5 (video2)
  //   - PANIC_BUT_OK: silver ≤ 5 (video2) 만 추가, gold 하한 없음 (영상 근거 없음)
  RISK_ON:        { cash: [5, 20],  nasdaq: [25, 55], leverage: [0, 0],   gold: [5, 30],  silver: [0, 10], copper: [5, 15],  korea: [5, 20], emerging: [5, 15] },
  NEUTRAL:        { cash: [10, 30], nasdaq: [20, 50], leverage: [0, 0],   gold: [10, 30], silver: [0, 10], copper: [0, 10],  korea: [5, 20], emerging: [0, 15] },
  CAUTION:        { cash: [30, 45], nasdaq: [15, 35], leverage: [0, 0],   gold: [15, 35], silver: [0, 5],  copper: [0, 10],  korea: [5, 20], emerging: [0, 15] },
  CORRECTION:     { cash: [30, 45], nasdaq: [15, 45], leverage: [0, 0],   gold: [15, 35], silver: [0, 5],  copper: [0, 10],  korea: [5, 20], emerging: [0, 10] },
  PANIC_BUT_OK:   { cash: [10, 25], nasdaq: [25, 55], leverage: [5, 15],  gold: [10, 30], silver: [0, 5],  copper: [0, 10],  korea: [0, 15], emerging: [0, 10] },
  RECESSION_RISK: { cash: [30, 65], nasdaq: [5, 25],  leverage: [0, 0],   gold: [15, 40], silver: [0, 5],  copper: [0, 5],   korea: [0, 15], emerging: [0, 10] },
  // Fix #5: STAGFLATION/BOND_VIGILANTE 는 탐색 대상 아님 — 고정 방어(스윕 비대상) 제약으로 통과.
  //   11차 시정 (2026-04): silver hi=5 로 축소 (envelope silver≤5 확장 정합, video2).
  //   BASE 값도 동기 (STAGFLATION silver 10→5, BOND_VIGILANTE silver 8→5). 감축분은 gold/cash 이관.
  STAGFLATION:    { cash: [27, 27], nasdaq: [15, 15], leverage: [0, 0],   gold: [33, 33], silver: [5, 5],   copper: [5, 5],  korea: [8, 8],  emerging: [7, 7]  },
  BOND_VIGILANTE: { cash: [31, 31], nasdaq: [10, 10], leverage: [0, 0],   gold: [37, 37], silver: [5, 5],   copper: [3, 3],  korea: [7, 7],  emerging: [7, 7]  },
};

// 스윕 대상은 원 6종 유지 (두 신규 국면은 데이터 부재로 탐색 의미 없음).
const REGIMES: Regime[] = ['RISK_ON', 'NEUTRAL', 'CAUTION', 'CORRECTION', 'PANIC_BUT_OK', 'RECESSION_RISK'];
const ASSETS = ['cash', 'nasdaq', 'leverage', 'gold', 'silver', 'copper', 'korea', 'emerging'];

/**
 * 영상 원칙 envelope 제약 — Monte Carlo 후보 필터.
 * 모든 규칙은 영상 원문(stt_video1~4, stt_kospi, video2/3/5_analysis)에 근거한 정성적 원칙만 수치화.
 * 원문에 수치 근거 없는 규칙(RISK_ON emerging≥12, NEUTRAL nasdaq≥28, PANIC gold≥15 등)은 도입하지 않는다.
 *
 *   - 모든 레짐 cash ≥ 5 (최소 현금 쿠션)
 *   - RISK_ON/NEUTRAL/CAUTION/CORRECTION gold ≥ 5 (구조적 헷지, video2 실질금리·달러·중앙은행 매수)
 *   - 모든 레짐 emerging ≤ 15, FX 악화 구간(CORRECTION/PANIC/RECESSION) emerging ≤ 10
 *     (stt_kospi 환율 1,500 돌파 시 외국인 이탈)
 *   - CAUTION/CORRECTION/PANIC_BUT_OK/RECESSION_RISK/STAGFLATION/BOND_VIGILANTE silver ≤ 5
 *     (video2: "은 아웃퍼폼 = 경기 회복 신호"; 2008 금융위기 금 -30% vs 은 -50%.
 *      경기확장 아닌 모든 국면에 상한 — STAGFLATION(성장↓) / BOND_VIGILANTE(재정·금리 급등 경제 불안) 포함)
 *   - CAUTION/CORRECTION/RECESSION_RISK cash ≥ 30
 *     (video5_analysis §3.3: "숨고르기/조정 국면 현금 30~40% 유지"; video3: "실업수당 30만+200DMA 아래 → 현금 비중 높여야")
 *
 * PANIC/RECESSION 은 gold 하한 미적용 — 영상 원문에 수치 근거 없으며 video2 "공황 초기엔 금도 같이 빠짐" 명시.
 * PANIC_BUT_OK 은 cash 하한 미적용 — video1 "아껴둔 현금을 쓰는 구간" (탄약 관점).
 * RISK_ON/NEUTRAL 은 cash 하한 5 만 적용 — 영상에 방어 현금 하한 근거 없음.
 */
function validateEnvelopeConstraints(
  alloc: Record<string, number>,
  regime: Regime,
): { ok: boolean; reason?: string } {
  if ((alloc.cash ?? 0) < 5) return { ok: false, reason: `${regime} cash<5` };
  if ((regime === 'RISK_ON' || regime === 'NEUTRAL' || regime === 'CAUTION' || regime === 'CORRECTION')
      && (alloc.gold ?? 0) < 5) {
    return { ok: false, reason: `${regime} gold<5` };
  }
  if ((alloc.emerging ?? 0) > 15) return { ok: false, reason: `${regime} emerging>15` };
  if ((regime === 'CORRECTION' || regime === 'PANIC_BUT_OK' || regime === 'RECESSION_RISK')
      && (alloc.emerging ?? 0) > 10) {
    return { ok: false, reason: `${regime} emerging>10 (FX)` };
  }
  // 은 상한: 경기확장 아닌 6개 레짐 (video2 정합). STAGFLATION/BOND_VIGILANTE 도 경기둔화
  // 범주라 11차에서 추가 (video2 "경기침체 시 은 더 크게 하락" 일관성).
  if ((regime === 'CAUTION' || regime === 'CORRECTION' || regime === 'PANIC_BUT_OK' ||
       regime === 'RECESSION_RISK' || regime === 'STAGFLATION' || regime === 'BOND_VIGILANTE')
      && (alloc.silver ?? 0) > 5) {
    return { ok: false, reason: `${regime} silver>5 (non-expansion, video2)` };
  }
  // 방어 현금 하한: CAUTION/CORRECTION/RECESSION (video5_analysis "30~40%")
  if ((regime === 'CAUTION' || regime === 'CORRECTION' || regime === 'RECESSION_RISK')
      && (alloc.cash ?? 0) < 30) {
    return { ok: false, reason: `${regime} cash<30 (방어 현금, video5_analysis §3.3)` };
  }

  return { ok: true };
}

/** 제약 내에서 랜덤 균등 샘플 → 정규화하여 합 100. 제약 밖이면 재시도.
 *  반환 `rejected` 는 validateEnvelopeConstraints 로 drop 한 횟수. */
function sampleRow(regime: Regime): { row: Record<string, number>; rejected: number; reasons: Record<string, number> } {
  const cs = CONSTRAINTS[regime];
  const reasons: Record<string, number> = {};
  let rejected = 0;
  for (let attempt = 0; attempt < 500; attempt++) {
    const row: Record<string, number> = {};
    for (const a of ASSETS) {
      const [lo, hi] = cs[a];
      row[a] = lo + Math.random() * (hi - lo);
    }
    const sum = Object.values(row).reduce((s, v) => s + v, 0);
    if (sum === 0) continue;
    const scaled: Record<string, number> = {};
    let valid = true;
    for (const a of ASSETS) {
      scaled[a] = Math.round((row[a] / sum) * 100);
      const [lo, hi] = cs[a];
      if (scaled[a] < lo - 2 || scaled[a] > hi + 2) { valid = false; break; } // 정규화 후 허용 오차 ±2
    }
    if (!valid) continue;
    // 반올림 오차 보정
    const s2 = Object.values(scaled).reduce((s, v) => s + v, 0);
    if (s2 !== 100) {
      scaled.nasdaq += 100 - s2;
      if (scaled.nasdaq < cs.nasdaq[0] || scaled.nasdaq > cs.nasdaq[1]) continue;
    }
    const envelopeCheck = validateEnvelopeConstraints(scaled, regime);
    if (!envelopeCheck.ok) {
      rejected += 1;
      if (envelopeCheck.reason) reasons[envelopeCheck.reason] = (reasons[envelopeCheck.reason] || 0) + 1;
      continue;
    }
    return { row: scaled, rejected, reasons };
  }
  // fallback: current PRD row (500회 실패 시 — envelope 체크 없이 반환)
  return { row: { ...CURRENT_PRD[regime] }, rejected, reasons };
}

function latestBefore(arr: Array<{ date: string; value: number }>, date: string): number | null {
  for (let i = arr.length - 1; i >= 0; i--) if (arr[i].date <= date) return arr[i].value;
  return null;
}

interface Result { return_pct: number; max_drawdown: number; }

interface BacktestOpts {
  /** 일일 allocation diff 에 적용할 편도 거래비용 (basis points). 기본 5bp.
   *  계산: Σ|Δpct|/2 × (bps/10000) — buy/sell 페어 평균이므로 /2.
   *  0 설정 시 비활성. */
  txCostBps?: number;
  /** 샘플 날짜 필터. [fromDate, toDate] (ISO YYYY-MM-DD) 사이만 backtest. 미지정 시 전체 days 만큼 뒤에서 자른다. */
  from?: string;
  to?: string;
}

async function backtest(
  variant: Template,
  days: number,
  histories: Record<string, Array<{ date: string; value: number }>>,
  fredHistories: Record<string, Array<{ date: string; value: number }>>,
  dxyHist: Array<{ date: string; value: number }>,
  nasdaqFull: Array<{ date: string; value: number }>,
  opts: BacktestOpts = {},
): Promise<Result> {
  const txCostBps = opts.txCostBps ?? 5;
  const txCostRate = txCostBps / 10000; // 5bp → 0.0005
  let baseSlice: Array<{ date: string; value: number }>;
  if (opts.from || opts.to) {
    baseSlice = nasdaqFull.filter((p) => (!opts.from || p.date >= opts.from) && (!opts.to || p.date <= opts.to));
  } else {
    baseSlice = nasdaqFull.slice(-Math.min(days, nasdaqFull.length));
  }
  const fredKeys = Object.keys(fredHistories);
  let pv = 100; let peak = 100; let maxDD = 0;
  let prevAlloc: Record<string, number> | null = null;
  for (let i = 1; i < baseSlice.length; i++) {
    const date = baseSlice[i].date;
    const prevDate = baseSlice[i - 1].date;
    const raw: Record<string, MarketDataPoint> = {};
    for (const k of fredKeys) { const v = latestBefore(fredHistories[k], prevDate); if (v !== null) raw[k] = { code: k, value: v, date: prevDate, source: 'FRED' }; }
    const dxyVal = latestBefore(dxyHist, prevDate); if (dxyVal !== null) raw.DXY = { code: 'DXY', value: dxyVal, date: prevDate, source: 'YAHOO' };
    const derived: Record<string, DerivedIndicator> = {};
    if (raw.DGS10 && raw.T10YIE) derived.REAL_YIELD = { name: 'real_yield', value: raw.DGS10.value - raw.T10YIE.value, date: prevDate, formula: '' };
    const nqEl = nasdaqFull.filter((p) => p.date <= prevDate);
    if (nqEl.length >= 200) {
      const sma200 = nqEl.slice(-200).reduce((s, p) => s + p.value, 0) / 200;
      const cur = nqEl[nqEl.length - 1].value;
      derived.NASDAQ_DISPARITY = { name: 'nasdaq_disparity_200', value: ((cur - sma200) / sma200) * 100, date: prevDate, formula: '' };
      derived.NASDAQ_ABOVE_200DMA = { name: 'nasdaq_above_200dma', value: cur > sma200 ? 1 : 0, date: prevDate, formula: '' };
    }
    const regime = classifyRegime({ raw, derived, manualInputs: DEFAULT_PROFILE.manualInputs });
    const signals = computeSignals(raw, derived, regime, DEFAULT_PROFILE);
    const alloc = computeAllocation(regime.regime, regime.score, signals, derived, raw, 'long', variant);
    let dayR = 0;
    for (const [an, pct] of Object.entries(alloc.allocations)) {
      if (an === 'cash' || an === 'leverage') continue;
      const h = histories[an]; if (!h) continue;
      const p1 = latestBefore(h, prevDate); const p2 = latestBefore(h, date);
      if (p1 && p2 && p1 > 0) dayR += (pct / 100) * ((p2 - p1) / p1);
    }
    // Fix #2: 거래 비용 — 전일 alloc 대비 |Δ| 합의 절반 × 수수료율.
    //   buy/sell 페어이므로 /2. 5bp 기본. 0 설정 시 비활성.
    if (txCostRate > 0 && prevAlloc) {
      let diffAbs = 0;
      const keys = new Set([...Object.keys(alloc.allocations), ...Object.keys(prevAlloc)]);
      for (const k of keys) {
        const a = alloc.allocations[k] ?? 0;
        const b = prevAlloc[k] ?? 0;
        diffAbs += Math.abs(a - b);
      }
      const turnover = (diffAbs / 2) / 100; // %p → 비율
      dayR -= turnover * txCostRate;
    }
    prevAlloc = { ...alloc.allocations };
    pv *= 1 + dayR; if (pv > peak) peak = pv;
    const dd = ((pv - peak) / peak) * 100; if (dd < maxDD) maxDD = dd;
  }
  return { return_pct: parseFloat((pv - 100).toFixed(2)), max_drawdown: parseFloat(maxDD.toFixed(2)) };
}

/** CLI 파싱 — --mode=decile | --mode=top1 (기본) + positional N + --output=<path>
 *  + --tx-cost=<bps> (기본 5) + --walk-forward. 환경변수도 허용. */
function parseArgs(): {
  mode: 'top1' | 'decile';
  N: number;
  outputPath: string | null;
  txCostBps: number;
  walkForward: boolean;
} {
  const rawArgs = process.argv.slice(2);
  let mode: 'top1' | 'decile' = 'top1';
  let nArg: string | undefined;
  let outputPath: string | null = null;
  let txCostBps = 5;
  let walkForward = false;
  for (const a of rawArgs) {
    if (a.startsWith('--mode=')) {
      const m = a.slice(7).toLowerCase();
      if (m === 'decile' || m === 'top1') mode = m;
    } else if (a.startsWith('--output=')) {
      outputPath = a.slice('--output='.length) || null;
    } else if (a === '--output') {
      // 값 없는 플래그 → 기본 경로 사용 (아래 main 에서 결정).
      outputPath = '';
    } else if (a.startsWith('--tx-cost=')) {
      const v = parseFloat(a.slice('--tx-cost='.length));
      if (!Number.isNaN(v) && v >= 0) txCostBps = v;
    } else if (a === '--walk-forward') {
      walkForward = true;
    } else if (/^\d+$/.test(a)) {
      nArg = a;
    }
  }
  const envMode = (process.env.PORTFOLIO_SWEEP_MODE || '').toLowerCase();
  if (envMode === 'decile' || envMode === 'top1') mode = mode === 'top1' && envMode ? (envMode as 'top1' | 'decile') : mode;
  if (envMode === 'decile') mode = 'decile';
  // 환경변수로도 --output 토글 지원.
  if (outputPath === null && process.env.PORTFOLIO_SWEEP_OUTPUT) {
    outputPath = process.env.PORTFOLIO_SWEEP_OUTPUT;
  }
  // Fix #4: 기본 N 100 (이전 80). 여유 있으면 200 권장.
  const N = parseInt(nArg || '100', 10);
  return { mode, N, outputPath, txCostBps, walkForward };
}

async function main() {
  const { mode, N, outputPath, txCostBps, walkForward } = parseArgs();
  const btOpts: BacktestOpts = { txCostBps };
  console.log(`[portfolio-sweep] mode=${mode} N=${N}/regime tx-cost=${txCostBps}bp${walkForward ? ' walk-forward=on' : ''}${outputPath !== null ? ` output=${outputPath || '(auto)'}` : ''}`);
  if (N < 100) console.log(`  (권장) N=100 이상 — 연산 여유 있으면 200 권장 (수렴 안정성).`);
  // 9차 후속 Fix #3 + 이번 Fix #4/#5/#6: 국면별 결과 구조.
  const perRegimeOut: Record<string, {
    current: Record<string, number>;
    top1: Record<string, number>;
    decileMean: Record<string, number>;
    diff: Record<string, number>;
    top1Score: { r1y: number; r3y: number; r5y: number; r10y: number; dd10y: number; score: number } | null;
    sampleStats: { requested: number; valid: number; rejected: number; rejectReasons: Record<string, number> };
    sampleWarning: string | null;
    activeDays: number;
    envelopeViolations: string[]; // Fix #6: decileMean 이 envelope 어긴 항목들
    recommendation: 'BLOCKED' | 'CANDIDATE'; // Fix C: 저표본/위반 시 BLOCKED
    blockReason?: string;
  }> = {};

  const assets: Record<string, { key: string; source: string }> = {
    nasdaq: { key: 'NASDAQ', source: 'yahoo' }, gold: { key: 'GOLD', source: 'yahoo' },
    silver: { key: 'SILVER', source: 'yahoo' }, copper: { key: 'COPPER', source: 'yahoo' },
    korea: { key: 'KOSPI', source: 'yahoo' }, emerging: { key: 'EWZ', source: 'yahoo' },
  };
  const histories: Record<string, Array<{ date: string; value: number }>> = {};
  for (const [n, { key, source }] of Object.entries(assets)) histories[n] = await readHistory(source, key);
  const fredKeys = ['DGS10', 'T10YIE', 'T10Y2Y', 'VIXCLS', 'BAMLH0A0HYM2', 'STLFSI4', 'ICSA', 'UNRATE'];
  const fredHistories: Record<string, Array<{ date: string; value: number }>> = {};
  for (const k of fredKeys) fredHistories[k] = await readHistory('fred', k);
  const dxyHist = await readHistory('yahoo', 'DXY');

  // Fix #5: 레짐 활성일 scan — 전체 10Y 기간 각 레짐 classifyRegime 호출 횟수.
  //   RISK_ON/NEUTRAL 외 레짐이 30일 미만이면 sweep 결과 신뢰도 낮음 경고.
  const regimeActiveDays: Record<string, number> = {};
  {
    const scanSlice = histories.nasdaq.slice(-Math.min(2520, histories.nasdaq.length));
    for (let i = 1; i < scanSlice.length; i++) {
      const prevDate = scanSlice[i - 1].date;
      const raw: Record<string, MarketDataPoint> = {};
      for (const k of Object.keys(fredHistories)) { const v = latestBefore(fredHistories[k], prevDate); if (v !== null) raw[k] = { code: k, value: v, date: prevDate, source: 'FRED' }; }
      const dxyVal = latestBefore(dxyHist, prevDate); if (dxyVal !== null) raw.DXY = { code: 'DXY', value: dxyVal, date: prevDate, source: 'YAHOO' };
      const derived: Record<string, DerivedIndicator> = {};
      if (raw.DGS10 && raw.T10YIE) derived.REAL_YIELD = { name: 'real_yield', value: raw.DGS10.value - raw.T10YIE.value, date: prevDate, formula: '' };
      const nqEl = histories.nasdaq.filter((p) => p.date <= prevDate);
      if (nqEl.length >= 200) {
        const sma200 = nqEl.slice(-200).reduce((s, p) => s + p.value, 0) / 200;
        const cur = nqEl[nqEl.length - 1].value;
        derived.NASDAQ_DISPARITY = { name: 'nasdaq_disparity_200', value: ((cur - sma200) / sma200) * 100, date: prevDate, formula: '' };
        derived.NASDAQ_ABOVE_200DMA = { name: 'nasdaq_above_200dma', value: cur > sma200 ? 1 : 0, date: prevDate, formula: '' };
      }
      const rr = classifyRegime({ raw, derived, manualInputs: DEFAULT_PROFILE.manualInputs });
      regimeActiveDays[rr.regime] = (regimeActiveDays[rr.regime] || 0) + 1;
    }
    console.log(`\n[regime active-days 10Y scan] ${JSON.stringify(regimeActiveDays)}`);
  }

  const base1y = await backtest(CURRENT_PRD, 252, histories, fredHistories, dxyHist, histories.nasdaq, btOpts);
  const base3y = await backtest(CURRENT_PRD, 756, histories, fredHistories, dxyHist, histories.nasdaq, btOpts);
  const base5y = await backtest(CURRENT_PRD, 1260, histories, fredHistories, dxyHist, histories.nasdaq, btOpts);
  const base10y = await backtest(CURRENT_PRD, 2520, histories, fredHistories, dxyHist, histories.nasdaq, btOpts);
  console.log(`\n=== BASELINE (현 PRD) ===`);
  console.log(`  1Y:  ${base1y.return_pct}% (DD ${base1y.max_drawdown}%)`);
  console.log(`  3Y:  ${base3y.return_pct}% (DD ${base3y.max_drawdown}%)`);
  console.log(`  5Y:  ${base5y.return_pct}% (DD ${base5y.max_drawdown}%)`);
  console.log(`  10Y: ${base10y.return_pct}% (DD ${base10y.max_drawdown}%)`);

  // 종합 점수 가중치: 1Y 25% + 3Y 10% + 5Y 30% + 10Y 35%, DD penalty 0.15
  // 장기(5Y/10Y) 일관성 + 단기(1Y) 반응 모두 반영.
  const W1Y = 0.25, W3Y = 0.10, W5Y = 0.30, W10Y = 0.35, DD_PENALTY = 0.15;
  const scoreFn = (r1: Result, r3: Result, r5: Result, r10: Result) =>
    r1.return_pct * W1Y + r3.return_pct * W3Y + r5.return_pct * W5Y + r10.return_pct * W10Y
    - (Math.abs(r1.max_drawdown) + Math.abs(r10.max_drawdown)) / 2 * DD_PENALTY;

  const bestPerRegime: Record<Regime, { row: Record<string, number>; r1y: Result; r3y: Result; r5y: Result; r10y: Result; score: number }> = {} as any;
  const decilePerRegime: Record<Regime, Record<string, number>> = {} as any;
  for (const regime of REGIMES) {
    process.stderr.write(`\n[${regime}] sampling N=${N} (1Y+3Y+5Y+10Y) ...\n`);
    const samples: Array<{ row: Record<string, number>; r1y: Result; r3y: Result; r5y: Result; r10y: Result; score: number }> = [];
    let regimeRejected = 0;
    const regimeReject: Record<string, number> = {};
    for (let i = 0; i < N; i++) {
      const { row, rejected, reasons } = sampleRow(regime);
      regimeRejected += rejected;
      for (const [k, v] of Object.entries(reasons)) regimeReject[k] = (regimeReject[k] || 0) + v;
      const variant: Template = JSON.parse(JSON.stringify(CURRENT_PRD));
      variant[regime] = row as any;
      const r1 = await backtest(variant, 252, histories, fredHistories, dxyHist, histories.nasdaq, btOpts);
      const r3 = await backtest(variant, 756, histories, fredHistories, dxyHist, histories.nasdaq, btOpts);
      const r5 = await backtest(variant, 1260, histories, fredHistories, dxyHist, histories.nasdaq, btOpts);
      const r10 = await backtest(variant, 2520, histories, fredHistories, dxyHist, histories.nasdaq, btOpts);
      const sc = scoreFn(r1, r3, r5, r10);
      samples.push({ row, r1y: r1, r3y: r3, r5y: r5, r10y: r10, score: sc });
      if ((i + 1) % 5 === 0) process.stderr.write(`  ${i + 1}/${N}\n`);
    }
    samples.sort((a, b) => b.score - a.score);
    const top5 = samples.slice(0, 5);

    const baseScore = scoreFn(base1y, base3y, base5y, base10y);
    console.log(`\n=== ${regime} TOP 5 (score = 1Y*0.25 + 3Y*0.10 + 5Y*0.30 + 10Y*0.35 - DDpen) ===`);
    console.log(`현 PRD row: ${JSON.stringify(CURRENT_PRD[regime])}  score=${baseScore.toFixed(2)}`);
    for (let i = 0; i < top5.length; i++) {
      const t = top5[i];
      console.log(`#${i + 1} score=${t.score.toFixed(2)} 1Y=${t.r1y.return_pct.toFixed(1)}% 3Y=${t.r3y.return_pct.toFixed(1)}% 5Y=${t.r5y.return_pct.toFixed(1)}% 10Y=${t.r10y.return_pct.toFixed(1)}%/${t.r10y.max_drawdown.toFixed(1)} → ${JSON.stringify(t.row)}`);
    }

    // Fix #FE3: top-decile 평균 근거 출력.
    //   상위 10% 샘플을 키별로 평균낸 뒤 100 으로 재정규화. BASE_ALLOCATIONS 교체 후보로 검토.
    //   top1 대비 덜 sample-내 과적합이라 산출값이 top1 보다 보수적이면 교체 우선순위 高.
    const decileSize = Math.max(1, Math.ceil(samples.length / 10));
    const topDecile = samples.slice(0, decileSize);
    const sumRow: Record<string, number> = {};
    for (const s of topDecile) {
      for (const [k, v] of Object.entries(s.row)) {
        sumRow[k] = (sumRow[k] || 0) + v;
      }
    }
    const meanRaw: Record<string, number> = {};
    for (const [k, v] of Object.entries(sumRow)) meanRaw[k] = v / topDecile.length;
    const meanSum = Object.values(meanRaw).reduce((a, b) => a + b, 0);
    const meanRow: Record<string, number> = {};
    for (const [k, v] of Object.entries(meanRaw)) {
      meanRow[k] = Math.round((v / meanSum) * 100);
    }
    // 반올림 누적 오차 보정 — 최대 키에 잔차 더한다.
    const meanTotal = Object.values(meanRow).reduce((a, b) => a + b, 0);
    if (meanTotal !== 100) {
      const maxKey = Object.entries(meanRow).sort((a, b) => b[1] - a[1])[0][0];
      meanRow[maxKey] += 100 - meanTotal;
    }
    console.log(`[top-decile mean] N=${topDecile.length}/${samples.length} → ${JSON.stringify(meanRow)}`);
    console.log(`[top1 vs decile diff] ${Object.keys(top5[0].row).map((k) => `${k}:${top5[0].row[k]}→${meanRow[k]}`).join(' ')}`);
    // Fix #1: envelope 제약 reject 통계.
    if (regimeRejected > 0) {
      const reasonStr = Object.entries(regimeReject).sort((a, b) => b[1] - a[1]).map(([r, c]) => `${r}×${c}`).join(', ');
      console.log(`[envelope-reject] ${regime} total=${regimeRejected} (${reasonStr})`);
    }

    bestPerRegime[regime] = top5[0];
    decilePerRegime[regime] = meanRow;

    // 9차 후속 Fix #3: 구조화 결과 수집 (diff = decileMean - current, %p).
    const current = CURRENT_PRD[regime];
    const diff: Record<string, number> = {};
    for (const k of ASSETS) {
      diff[k] = (meanRow[k] ?? 0) - (current[k] ?? 0);
    }
    perRegimeOut[regime] = {
      current: { ...current },
      top1: { ...top5[0].row },
      decileMean: { ...meanRow },
      diff,
      top1Score: {
        r1y: top5[0].r1y.return_pct,
        r3y: top5[0].r3y.return_pct,
        r5y: top5[0].r5y.return_pct,
        r10y: top5[0].r10y.return_pct,
        dd10y: top5[0].r10y.max_drawdown,
        score: top5[0].score,
      },
      // Fix #4: 샘플 통계 — 요청 N, envelope 통과 수, reject 통계.
      sampleStats: {
        requested: N,
        valid: samples.length,
        rejected: regimeRejected,
        rejectReasons: regimeReject,
      },
      // Fix #5: 저표본 레짐 경고. RISK_ON/NEUTRAL 외 레짐 활성일 < 30 이면 라벨.
      activeDays: regimeActiveDays[regime] || 0,
      sampleWarning: ((): string | null => {
        const d = regimeActiveDays[regime] || 0;
        if (regime !== 'RISK_ON' && regime !== 'NEUTRAL' && d < 30) {
          return `표본 부족 (활성일 ${d} < 30) — 신뢰도 낮음`;
        }
        return null;
      })(),
      // Fix #6: decileMean envelope 준수 체크. sampleRow 재샘플링에도 meanRow 정규화 과정에서 ±2 허용오차로 위반 가능.
      envelopeViolations: ((): string[] => {
        const violations: string[] = [];
        if ((meanRow.cash ?? 0) < 5) violations.push(`cash=${meanRow.cash} (기준 ≥5)`);
        if ((regime === 'RISK_ON' || regime === 'NEUTRAL' || regime === 'CAUTION' || regime === 'CORRECTION')
            && (meanRow.gold ?? 0) < 5) violations.push(`gold=${meanRow.gold} (기준 ≥5)`);
        if ((meanRow.emerging ?? 0) > 15) violations.push(`emerging=${meanRow.emerging} (기준 ≤15)`);
        if ((regime === 'CORRECTION' || regime === 'PANIC_BUT_OK' || regime === 'RECESSION_RISK')
            && (meanRow.emerging ?? 0) > 10) violations.push(`emerging=${meanRow.emerging} (FX 악화 기준 ≤10)`);
        if ((regime === 'CAUTION' || regime === 'CORRECTION' || regime === 'PANIC_BUT_OK' ||
             regime === 'RECESSION_RISK' || regime === 'STAGFLATION' || regime === 'BOND_VIGILANTE')
            && (meanRow.silver ?? 0) > 5)
          violations.push(`silver=${meanRow.silver} (경기확장 아님 기준 ≤5, video2)`);
        if ((regime === 'CAUTION' || regime === 'CORRECTION' || regime === 'RECESSION_RISK')
            && (meanRow.cash ?? 0) < 30)
          violations.push(`cash=${meanRow.cash} (방어 현금 기준 ≥30, video5_analysis)`);
        return violations;
      })(),
      recommendation: 'CANDIDATE', // 아래 블록에서 덮어씀
    };
    // Fix C (2026-04): 저표본/envelope 위반 시 BASE 교체 BLOCKED 라벨.
    //   활성일 100 미만: 레짐 표본 부족 → 교체 금지
    //   envelope 위반 존재: 영상 원칙 위반 → 교체 금지
    {
      const activeD = regimeActiveDays[regime] || 0;
      const violations = perRegimeOut[regime].envelopeViolations;
      if (activeD < 100 || violations.length > 0) {
        perRegimeOut[regime].recommendation = 'BLOCKED';
        perRegimeOut[regime].blockReason =
          activeD < 100 ? `저표본 (활성일 ${activeD} < 100)` :
          `envelope 위반 ${violations.length}건`;
      } else {
        perRegimeOut[regime].recommendation = 'CANDIDATE';
      }
    }
    const activeDaysStr = regimeActiveDays[regime] || 0;
    const warnTag = perRegimeOut[regime].sampleWarning ? `  ⚠️ ${perRegimeOut[regime].sampleWarning}` : '';
    console.log(`[sample-stats] ${regime} valid=${samples.length}/${N} (envelope-rejected ${regimeRejected}) activeDays=${activeDaysStr}${warnTag}`);
  }

  // composed optimal: mode 에 따라 top1 또는 top-decile 평균 합침.
  //   - top1: 국면별 최고점 샘플 — 샘플 내 과적합 위험 존재
  //   - decile: 국면별 상위 10% 평균 — BASE_ALLOCATIONS 교체 후보 robust 추정
  const composed: Template = {} as any;
  for (const r of REGIMES) {
    composed[r] = (mode === 'decile' ? decilePerRegime[r] : bestPerRegime[r].row) as any;
  }
  const c1y = await backtest(composed, 252, histories, fredHistories, dxyHist, histories.nasdaq, btOpts);
  const c3y = await backtest(composed, 756, histories, fredHistories, dxyHist, histories.nasdaq, btOpts);
  const c5y = await backtest(composed, 1260, histories, fredHistories, dxyHist, histories.nasdaq, btOpts);
  const c10y = await backtest(composed, 2520, histories, fredHistories, dxyHist, histories.nasdaq, btOpts);

  const header = mode === 'decile'
    ? 'COMPOSED (국면별 top-decile 평균) — BASE_ALLOCATIONS 재선정 후보'
    : 'COMPOSED OPTIMAL (국면별 top1 합침)';
  console.log(`\n=== ${header} ===`);
  console.log(JSON.stringify(composed, null, 2));
  console.log(`\n  1Y:  ${c1y.return_pct}% (dd ${c1y.max_drawdown}%) Δ vs PRD = ${(c1y.return_pct - base1y.return_pct).toFixed(2)}%p`);
  console.log(`  3Y:  ${c3y.return_pct}% (dd ${c3y.max_drawdown}%) Δ vs PRD = ${(c3y.return_pct - base3y.return_pct).toFixed(2)}%p`);
  console.log(`  5Y:  ${c5y.return_pct}% (dd ${c5y.max_drawdown}%) Δ vs PRD = ${(c5y.return_pct - base5y.return_pct).toFixed(2)}%p`);
  console.log(`  10Y: ${c10y.return_pct}% (dd ${c10y.max_drawdown}%) Δ vs PRD = ${(c10y.return_pct - base10y.return_pct).toFixed(2)}%p`);
  if (mode === 'decile') {
    console.log(`\n(주의) 위 composed 는 BASE_ALLOCATIONS 자동 교체 대상이 아니다. 사용자 검토 후 별도 커밋으로 반영.`);
  }

  // Fix #6: 영상 원칙 준수 체크 레포트 — decile-mean 후보가 envelope 제약 몇 개 위반하는지 집계.
  {
    let totalViolations = 0;
    const violationLines: string[] = [];
    for (const r of REGIMES) {
      const v = perRegimeOut[r]?.envelopeViolations || [];
      totalViolations += v.length;
      for (const item of v) violationLines.push(`  - ${r} ${item} — 위반`);
    }
    if (totalViolations > 0) {
      console.log(`\n⚠️ 영상 원칙 준수 검토 (decile-mean 후보 기준, 총 ${totalViolations}건):`);
      for (const line of violationLines) console.log(line);
      console.log(`→ 검증 필요 — envelope 제약 재조정 또는 BASE_ALLOCATIONS 수동 반영 시 주의`);
    } else {
      console.log(`\n✓ 영상 원칙 준수 검토: 모든 decile-mean 후보가 envelope 제약 내.`);
    }

    // Fix C (2026-04): BASE_ALLOCATIONS 교체 후보/보류 카운트.
    const candidates = Object.entries(perRegimeOut)
      .filter(([, v]) => v.recommendation === 'CANDIDATE').map(([r]) => r);
    const blocked = Object.entries(perRegimeOut)
      .filter(([, v]) => v.recommendation === 'BLOCKED');
    console.log('');
    console.log(`✅ BASE 교체 후보 ${candidates.length}개: ${candidates.join(', ') || '없음'}`);
    console.log(`⛔ 보류 ${blocked.length}개:`);
    for (const [r, v] of blocked) {
      console.log(`   - ${r}: ${v.blockReason}`);
    }
  }

  // Fix #3 (walk-forward OOS): 과적합 진단.
  //   train window: 2016-01-01 ~ 2023-12-31 — 이 구간으로 composed 재백테스트
  //   test window:  2024-01-01 ~ 현재 — composed 후보만 OOS 평가
  //   alpha_decay = train_alpha − test_alpha (%p). 15%p 초과 시 과적합 경고.
  let walkForwardBlock: null | {
    train: { from: string; to: string; return_pct: number; max_drawdown: number; alpha_pct: number };
    test:  { from: string; to: string; return_pct: number; max_drawdown: number; alpha_pct: number };
    alpha_decay: number;
    overfit_warning: boolean;
  } = null;
  if (walkForward) {
    const TRAIN_FROM = '2016-01-01';
    const TRAIN_TO   = '2023-12-31';
    const TEST_FROM  = '2024-01-01';
    const TEST_TO    = new Date().toISOString().slice(0, 10);

    const composedTrain = await backtest(composed, 0, histories, fredHistories, dxyHist, histories.nasdaq, { ...btOpts, from: TRAIN_FROM, to: TRAIN_TO });
    const baseTrain     = await backtest(CURRENT_PRD, 0, histories, fredHistories, dxyHist, histories.nasdaq, { ...btOpts, from: TRAIN_FROM, to: TRAIN_TO });
    const composedTest  = await backtest(composed, 0, histories, fredHistories, dxyHist, histories.nasdaq, { ...btOpts, from: TEST_FROM,  to: TEST_TO });
    const baseTest      = await backtest(CURRENT_PRD, 0, histories, fredHistories, dxyHist, histories.nasdaq, { ...btOpts, from: TEST_FROM,  to: TEST_TO });

    const trainAlpha = composedTrain.return_pct - baseTrain.return_pct;
    const testAlpha  = composedTest.return_pct  - baseTest.return_pct;
    const alphaDecay = trainAlpha - testAlpha;
    const overfit = alphaDecay > 15;

    console.log(`\n=== WALK-FORWARD OOS (train ${TRAIN_FROM}~${TRAIN_TO} / test ${TEST_FROM}~${TEST_TO}) ===`);
    console.log(`  TRAIN composed=${composedTrain.return_pct}% (dd ${composedTrain.max_drawdown}%)  base=${baseTrain.return_pct}%  α=${trainAlpha.toFixed(2)}%p`);
    console.log(`  TEST  composed=${composedTest.return_pct}%  (dd ${composedTest.max_drawdown}%)  base=${baseTest.return_pct}%   α=${testAlpha.toFixed(2)}%p`);
    console.log(`  alpha_decay = train_α - test_α = ${alphaDecay.toFixed(2)}%p${overfit ? '  ⚠️ 15%p 초과 — 과적합 의심' : ''}`);

    walkForwardBlock = {
      train: {
        from: TRAIN_FROM, to: TRAIN_TO,
        return_pct: composedTrain.return_pct,
        max_drawdown: composedTrain.max_drawdown,
        alpha_pct: parseFloat(trainAlpha.toFixed(2)),
      },
      test: {
        from: TEST_FROM, to: TEST_TO,
        return_pct: composedTest.return_pct,
        max_drawdown: composedTest.max_drawdown,
        alpha_pct: parseFloat(testAlpha.toFixed(2)),
      },
      alpha_decay: parseFloat(alphaDecay.toFixed(2)),
      overfit_warning: overfit,
    };
  }

  // 9차 후속 Fix #3: --output 플래그가 지정된 경우 JSON 저장.
  //   빈 문자열('') 또는 '--output' 단독 → 자동 경로 (server/data/sweep-results/top-decile-<ISO>.json).
  //   명시적 경로가 있으면 그대로 사용. 상위 디렉토리는 자동 생성.
  if (outputPath !== null) {
    const ranAt = new Date().toISOString();
    const payload = {
      ranAt,
      mode,
      N,
      txCostBps,
      walkForward: walkForwardBlock,
      baseline: {
        r1y: base1y.return_pct,
        r3y: base3y.return_pct,
        r5y: base5y.return_pct,
        r10y: base10y.return_pct,
        dd10y: base10y.max_drawdown,
      },
      composed: {
        template: composed,
        r1y: c1y.return_pct,
        r3y: c3y.return_pct,
        r5y: c5y.return_pct,
        r10y: c10y.return_pct,
        dd10y: c10y.max_drawdown,
        delta: {
          r1y: parseFloat((c1y.return_pct - base1y.return_pct).toFixed(2)),
          r3y: parseFloat((c3y.return_pct - base3y.return_pct).toFixed(2)),
          r5y: parseFloat((c5y.return_pct - base5y.return_pct).toFixed(2)),
          r10y: parseFloat((c10y.return_pct - base10y.return_pct).toFixed(2)),
        },
      },
      perRegime: perRegimeOut,
    };

    let resolved: string;
    if (outputPath === '') {
      const stamp = ranAt.replace(/[:.]/g, '-');
      const prefix = mode === 'decile' ? 'top-decile' : 'top1';
      resolved = path.resolve(process.cwd(), 'data/sweep-results', `${prefix}-${stamp}.json`);
    } else {
      resolved = path.resolve(process.cwd(), outputPath);
    }
    await fs.mkdir(path.dirname(resolved), { recursive: true });
    await fs.writeFile(resolved, JSON.stringify(payload, null, 2));
    console.log(`\n[sweep-output] JSON 저장 → ${resolved}`);
  }
}

main().catch((e) => { console.error(e); process.exit(1); });
