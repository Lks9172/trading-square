/**
 * Weekly report 자동 생성 + 규칙 위반 경고 (17차 Phase 2 D1 + E2).
 *
 * 영상 원문 정합:
 *   - notion §전하는 말 "매일 쏟아지는 이슈 텍스트로 정리"
 *   - video1 §5부 "시스템이 있으면 심리 싸움에서 이길 수 있습니다"
 *
 * Weekly report:
 *   지난 주 핵심 변화 (regime / 주요 signal / 이격도 / 외인 / 기관 FLOW / 이벤트 D-Day)
 *   → Telegram / 로그 / API 로 제공.
 *
 * Rule violation detector:
 *   사용자 InvestmentPlan 규칙 vs 현재 시스템 권고 allocation 차이.
 *   예: leverage 권고 20% > plan.leverageMaxPct 15% → 경고.
 */

import { SystemSnapshot } from '../types/indicators';
import { readInvestmentPlan } from './investment-plan';
import { appendTradeLog } from './investment-plan';

export interface WeeklyReport {
  generatedAt: string;
  period: { from: string; to: string };
  regime: { current: string; score: number; preOverride?: string };
  keySignals: Array<{ asset: string; signal: string; met: string }>;
  topReasons: string[];
  warnings: string[];
  nextEvents: Array<{ event: string; dday: number }>;
  ruleViolations: string[];
}

export function buildWeeklyReport(snapshot: SystemSnapshot): WeeklyReport {
  const der = snapshot.derived || {};
  const sigs = snapshot.signals || [];
  const regime = snapshot.regime;
  const now = new Date();
  const weekAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);

  const keySignals = sigs
    .filter((s) => ['NASDAQ', 'KOSPI', 'GOLD', 'SILVER', 'COPPER'].includes(s.asset))
    .map((s) => ({
      asset: s.asset,
      signal: s.signal,
      met: `${s.conditionsMet}/${s.conditionsTotal}`,
    }));

  const topReasons: string[] = [];
  for (const s of sigs) {
    const firstReason = s.reasons?.[0];
    if (firstReason && s.signal !== 'HOLD') {
      topReasons.push(`[${s.asset} ${s.signal}] ${firstReason}`);
    }
  }

  const warnings: string[] = [];
  const warnKeys = [
    'TAIL_RISK_LEVEL', 'ECONOMY_STOCK_DIVERGENCE', 'FEDERAL_DEFICIT_GDP_TIER',
    'FEDERAL_DEBT_GDP_TIER', 'WTI_CPI_LAG_RISK', 'INSTITUTIONAL_NASDAQ_FLOW',
    'KOSPI_YEARLY_AREA_LEVEL', 'NASDAQ_YEARLY_BEAR_CANDLE',
  ];
  for (const k of warnKeys) {
    const entry = der[k];
    if (!entry) continue;
    const interp = (entry as unknown as Record<string, unknown>).interpretation;
    if (typeof interp === 'string' && (interp.startsWith('🔴') || interp.startsWith('🟠'))) {
      warnings.push(`[${k}] ${interp}`);
    }
  }

  const nextEvents: Array<{ event: string; dday: number }> = [];
  for (const [key, label] of [
    ['FOMC_DDAY', 'FOMC'],
    ['CPI_DDAY', 'CPI 발표'],
    ['BOK_FORECAST_DDAY', '한은 경제전망'],
  ] as const) {
    const e = der[key] as { value?: number } | undefined;
    if (e?.value !== undefined && e.value <= 30) {
      nextEvents.push({ event: label, dday: e.value });
    }
  }

  return {
    generatedAt: now.toISOString(),
    period: { from: weekAgo.toISOString().slice(0, 10), to: now.toISOString().slice(0, 10) },
    regime: {
      current: regime?.regime || 'unknown',
      score: regime?.score ?? 0,
      preOverride: regime?.explanation?.preOverrideRegime,
    },
    keySignals,
    topReasons: topReasons.slice(0, 5),
    warnings: warnings.slice(0, 8),
    nextEvents,
    ruleViolations: [], // 하단에서 채움
  };
}

/** 현재 allocation vs InvestmentPlan 규칙 비교 — 위반 목록 반환 */
export async function detectRuleViolations(snapshot: SystemSnapshot): Promise<string[]> {
  const plan = await readInvestmentPlan();
  const alloc = snapshot.allocation?.allocations || {};
  const violations: string[] = [];

  const leverage = alloc.leverage ?? 0;
  if (leverage > plan.leverageMaxPct) {
    violations.push(`⚠️ 레버리지 권고 ${leverage}% > 계획 상한 ${plan.leverageMaxPct}% (video1 §전략C 원칙 위반)`);
  }
  const cash = alloc.cash ?? 0;
  // horizon 별 최소 현금 (medium=10%, short=20%, long=5%)
  const minCash = plan.horizon === 'short' ? 20 : plan.horizon === 'long' ? 5 : 10;
  if (cash < minCash) {
    violations.push(`⚠️ 현금 ${cash}% < ${plan.horizon} 호라이즌 최소 권고 ${minCash}%`);
  }

  // NASDAQ DRAWDOWN 이 stopLossPct 초과 시 경고
  const ddEntry = snapshot.derived?.NASDAQ_DRAWDOWN_ATH as { value?: number } | undefined;
  const dd = ddEntry?.value;
  if (dd !== undefined && dd < -plan.stopLossPct) {
    violations.push(`⚠️ NASDAQ ATH 대비 ${dd}% > 손절 기준 -${plan.stopLossPct}% (계획 재점검 필요)`);
  }
  return violations;
}

/** Trade Log 자동 기록 — allocation 변화 / regime 변화 / 경고 이벤트 */
export async function autoLogSnapshotDelta(
  prev: SystemSnapshot | null,
  curr: SystemSnapshot,
): Promise<void> {
  if (!prev) return;
  const prevRegime = prev.regime?.regime;
  const currRegime = curr.regime?.regime;
  if (prevRegime && currRegime && prevRegime !== currRegime) {
    await appendTradeLog({
      kind: 'observation',
      notes: `레짐 변경: ${prevRegime} → ${currRegime}`,
      context: { prevScore: prev.regime?.score, currScore: curr.regime?.score },
    });
  }
  // NASDAQ signal 변화
  const prevNq = prev.signals?.find((s) => s.asset === 'NASDAQ');
  const currNq = curr.signals?.find((s) => s.asset === 'NASDAQ');
  if (prevNq && currNq && prevNq.signal !== currNq.signal) {
    await appendTradeLog({
      kind: 'signal_change',
      asset: 'NASDAQ',
      from: prevNq.signal,
      to: currNq.signal,
      notes: currNq.reasons?.[0],
    });
  }
  // KOSPI signal 변화
  const prevKospi = prev.signals?.find((s) => s.asset === 'KOSPI');
  const currKospi = curr.signals?.find((s) => s.asset === 'KOSPI');
  if (prevKospi && currKospi && prevKospi.signal !== currKospi.signal) {
    await appendTradeLog({
      kind: 'signal_change',
      asset: 'KOSPI',
      from: prevKospi.signal,
      to: currKospi.signal,
      notes: currKospi.reasons?.[0],
    });
  }
}

/** Telegram 전송용 포맷 */
export function formatWeeklyReportText(report: WeeklyReport): string {
  const lines: string[] = [];
  lines.push(`📊 MacroSquare Weekly Report (${report.period.from} ~ ${report.period.to})`);
  lines.push('');
  lines.push(`🎯 레짐: *${report.regime.current}* (score ${report.regime.score})`);
  if (report.regime.preOverride && report.regime.preOverride !== report.regime.current) {
    lines.push(`   (override 전: ${report.regime.preOverride})`);
  }
  lines.push('');
  lines.push('📈 자산별 신호:');
  for (const s of report.keySignals) lines.push(`  - ${s.asset}: *${s.signal}* (${s.met})`);
  if (report.topReasons.length > 0) {
    lines.push('');
    lines.push('💡 핵심 근거:');
    for (const r of report.topReasons) lines.push(`  • ${r.slice(0, 100)}`);
  }
  if (report.warnings.length > 0) {
    lines.push('');
    lines.push('⚠️ 경고:');
    for (const w of report.warnings) lines.push(`  • ${w.slice(0, 120)}`);
  }
  if (report.nextEvents.length > 0) {
    lines.push('');
    lines.push('📅 다가오는 이벤트:');
    for (const e of report.nextEvents) lines.push(`  • ${e.event}: D-${e.dday}`);
  }
  if (report.ruleViolations.length > 0) {
    lines.push('');
    lines.push('🚨 계획 규칙 위반:');
    for (const v of report.ruleViolations) lines.push(`  • ${v}`);
  }
  return lines.join('\n');
}
