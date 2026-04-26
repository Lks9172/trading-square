/**
 * 사용자 투자 계획 + Trade Log 통합 엔진 (16차 Phase 1 A1 / Phase 2 D1).
 *
 * video1 §5부 "투자에서 가장 큰 적은 나 자신" / notion "복기하고 공부하면서 실력을 쌓아야" 정합.
 *
 * 파일 기반 저장 (SQLite 도입 전 MVP):
 *   - plans.json: { horizon, targetReturnPct, tolerance, rules, updatedAt }
 *   - trade-log.jsonl: append-only 각 매매/관찰 이벤트
 *
 * 향후 확장: DB / 멀티 사용자 / 실제 브로커 연동.
 */

import fs from 'fs/promises';
import path from 'path';
import { childLogger, serializeError } from './logger';

const log = childLogger({ module: 'service.investment-plan' });

const DATA_DIR = process.env.INVESTMENT_DATA_DIR || '/app/data/investment';

export interface InvestmentPlan {
  horizon: 'short' | 'medium' | 'long'; // video1 §5부 "시계열 먼저 정해야"
  targetReturnAnnualPct: number;         // 목표 연 수익률 (%)
  maxDrawdownTolerancePct: number;       // 허용 MDD (%)
  rebalanceIntervalDays: number;         // 리밸런싱 주기 (일)
  leverageMaxPct: number;                // 레버리지 상한 (video1 §전략C "최대 15%")
  profitTakeTargetPct: number;           // 익절 목표 (video1 §전략C "20~30%")
  stopLossPct: number;                   // 손절 기준
  monthlyDCA_KRW: number;                // 월간 분할매수 금액
  // 21차 Phase 1#2: 사용자 실제 보유 비중 (allocKey → %)
  currentHoldings?: {
    cash?: number;
    nasdaq?: number;
    leverage?: number;
    gold?: number;
    silver?: number;
    copper?: number;
    korea?: number;
    emerging?: number;
  };
  // 21차 Phase 1#8: 자본 규모 / 투자 연차 / 계좌 종류
  totalCapitalKRW?: number;
  // 26차 P1#4: USD 자본 분리 추적 + 자산별 USD 보유 (권고 vs 실제 USD 갭 측정)
  totalCapitalUSD?: number;
  currentHoldingsUSD?: {
    cash?: number;
    nasdaq?: number;
    leverage?: number;
    gold?: number;
    silver?: number;
    copper?: number;
    korea?: number;
    emerging?: number;
  };
  // 28차 영상6: 시작 시점 + 시작 자본 (USD 기준 누적 수익률 산출)
  accountStartDate?: string;       // ISO date
  startingCapitalUSD?: number;     // 시작 자본 USD
  startingCapitalKRW?: number;     // 시작 자본 KRW
  investmentExperienceYears?: number;
  accountType?: 'general' | 'isa' | 'pension' | 'foreign';
  notes?: string;
  updatedAt: string;
}

export const DEFAULT_PLAN: InvestmentPlan = {
  horizon: 'medium',
  targetReturnAnnualPct: 12,      // 명목 나스닥 장기 18% 대비 보수적
  maxDrawdownTolerancePct: 25,
  rebalanceIntervalDays: 90,      // 분기 리밸런싱
  leverageMaxPct: 15,             // video1 §전략C
  profitTakeTargetPct: 25,        // video1 §전략C (20~30% 중간값)
  stopLossPct: 15,
  monthlyDCA_KRW: 1_000_000,      // 월 100만원 DCA 기본
  updatedAt: new Date().toISOString(),
};

export interface TradeLogEntry {
  ts: string;
  kind: 'signal_change' | 'allocation_change' | 'user_action' | 'observation';
  asset?: string;                 // NASDAQ / GOLD / etc.
  from?: string;
  to?: string;
  notes?: string;
  // 21차 Phase 2#14: 시스템 권고와 반대 행동 여부 (user_action 기록 시)
  againstSystemRecommendation?: boolean;
  // 21차: 행동 시점의 시스템 상태 스냅샷 (regime/signal) — 반복 패턴 분석용
  context?: Record<string, unknown> & {
    regimeAtAction?: string;
    signalAtAction?: string;
  };
}

async function ensureDir() {
  await fs.mkdir(DATA_DIR, { recursive: true });
}

const PLAN_FILE = () => path.join(DATA_DIR, 'plan.json');
const LOG_FILE = () => path.join(DATA_DIR, 'trade-log.jsonl');

export async function readInvestmentPlan(): Promise<InvestmentPlan> {
  try {
    await ensureDir();
    const raw = await fs.readFile(PLAN_FILE(), 'utf-8');
    const parsed = JSON.parse(raw) as Partial<InvestmentPlan>;
    return { ...DEFAULT_PLAN, ...parsed };
  } catch {
    return DEFAULT_PLAN;
  }
}

export async function writeInvestmentPlan(patch: Partial<InvestmentPlan>): Promise<InvestmentPlan> {
  await ensureDir();
  const current = await readInvestmentPlan();
  const next: InvestmentPlan = {
    ...current,
    ...patch,
    updatedAt: new Date().toISOString(),
  };
  await fs.writeFile(PLAN_FILE(), JSON.stringify(next, null, 2));
  log.info({ plan: next }, 'investment plan updated');
  return next;
}

/** Trade log append (영속, jsonl 한 줄씩). */
export async function appendTradeLog(entry: Omit<TradeLogEntry, 'ts'>): Promise<void> {
  try {
    await ensureDir();
    const full: TradeLogEntry = { ts: new Date().toISOString(), ...entry };
    await fs.appendFile(LOG_FILE(), JSON.stringify(full) + '\n');
  } catch (error) {
    log.warn({ error: serializeError(error) }, 'tradeLog append failed');
  }
}

/** 최근 N개 log 읽기 (JSON 파싱 실패 시 해당 줄 skip). */
export async function readRecentTradeLog(limit = 200): Promise<TradeLogEntry[]> {
  try {
    await ensureDir();
    const raw = await fs.readFile(LOG_FILE(), 'utf-8');
    const lines = raw.split('\n').filter((l) => l.trim());
    const out: TradeLogEntry[] = [];
    for (const line of lines.slice(-limit)) {
      try { out.push(JSON.parse(line) as TradeLogEntry); } catch { /* skip malformed */ }
    }
    return out;
  } catch {
    return [];
  }
}
