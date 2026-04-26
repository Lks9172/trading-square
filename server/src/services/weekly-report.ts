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
import { readInvestmentPlan, appendTradeLog, readRecentTradeLog } from './investment-plan';

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

// 20차 D1 + 21차 P1#5 + 24차 Phase 2#18-20: 정성 정량화 3종 추가
export interface PlanDiscipline {
  reviewsLast7d: number;
  reviewWarning: string | null;
  impulsiveTrades24h: number;
  impulsiveWarning: string | null;
  againstSystemCount4w: number;
  patternWarning: string | null;
  horizonChangeCount30d: number;
  // 24차 Phase 2#18: 시스템 vs 사용자 행동 90D 일치율 (INDEPENDENCE_INDEX 보완)
  independenceIndex90d: number;       // 0~100, 높을수록 사용자 자기 판단 우세 (against_system 비율)
  // 24차 Phase 2#19: 30D 시그널 미충족 시 매수 자제 일수 비율 (WAIT_DISCIPLINE)
  waitDiscipline30d: number;          // 0~100, 높을수록 인내 양호
  // 24차 Phase 2#20: 복기 깊이 + 연속 streak
  reviewDepthAvgChars: number;        // 7일 observation 평균 글자수
  reviewStreakDays: number;           // 연속 복기 일수 (현재까지)
  reviewMaxStreakDays: number;        // 30일 내 최장 streak
  // 26차 Phase 2#10-13: 정성 정량화 4종
  recoveryResponseLatencyDays: number; // signal_change 후 user_action 첫 기록까지 (-1=대응 없음)
  // CONFIDENCE_GATE_BLOCKED_DAYS / LEADER_ROTATION_FREQUENCY / TIMING_QUALITY_SCORE 는
  // history-store 누적 후 재산출 정확도 향상 — 현재 stub.
}

export async function computePlanDiscipline(): Promise<PlanDiscipline> {
  const log = await readRecentTradeLog(500);
  const cutoff7d = Date.now() - 7 * 86400000;
  const cutoff24h = Date.now() - 86400000;
  const cutoff4w = Date.now() - 28 * 86400000;
  const cutoff30d = Date.now() - 30 * 86400000;
  const reviews7d = log.filter((e) => {
    const t = new Date(e.ts).getTime();
    return Number.isFinite(t) && t >= cutoff7d && (e.kind === 'observation' || e.kind === 'user_action');
  });
  const recent24h = log.filter((e) => {
    const t = new Date(e.ts).getTime();
    return Number.isFinite(t) && t >= cutoff24h;
  });
  const byAsset = new Map<string, string[]>();
  for (const e of recent24h) {
    if (!e.asset || !e.to) continue;
    if (!byAsset.has(e.asset)) byAsset.set(e.asset, []);
    byAsset.get(e.asset)!.push(e.to);
  }
  let impulsive = 0;
  for (const [, trail] of byAsset.entries()) {
    if (trail.length < 3) continue;
    impulsive++;
  }

  // 21차 P1#5: 4주간 시스템 권고와 반대 행동 카운트
  const recent4w = log.filter((e) => {
    const t = new Date(e.ts).getTime();
    return Number.isFinite(t) && t >= cutoff4w && e.kind === 'user_action';
  });
  const againstCount = recent4w.filter((e) => e.againstSystemRecommendation === true).length;

  // 21차 P2#15: 30일 horizon 변경 횟수
  const horizonChanges = log.filter((e) => {
    const t = new Date(e.ts).getTime();
    return Number.isFinite(t) && t >= cutoff30d && e.kind === 'observation' && /horizon change/i.test(e.notes ?? '');
  });

  // 24차 Phase 2#18: INDEPENDENCE_INDEX — 90D 동안 user_action 중 against_system 비율
  const cutoff90d = Date.now() - 90 * 86400000;
  const allActions90d = log.filter((e) => {
    const t = new Date(e.ts).getTime();
    return Number.isFinite(t) && t >= cutoff90d && e.kind === 'user_action';
  });
  const independenceIndex90d = allActions90d.length > 0
    ? Math.round((allActions90d.filter((e) => e.againstSystemRecommendation === true).length / allActions90d.length) * 100)
    : 0;

  // 24차 Phase 2#19: WAIT_DISCIPLINE — 30일 동안 user_action 횟수가 적을수록 양호
  // 단순화: 30D user_action 0건 = 100, 10건+ = 0, 선형 보간
  const userActions30d = log.filter((e) => {
    const t = new Date(e.ts).getTime();
    return Number.isFinite(t) && t >= cutoff30d && e.kind === 'user_action';
  }).length;
  const waitDiscipline30d = Math.max(0, Math.min(100, 100 - userActions30d * 10));

  // 26차 Phase 2#10: CONFIDENCE_GATE_BLOCKED_DAYS — CONVICTION<3 누적 일수 (snapshot history 부재 시 단순 대용)
  // 단순화: 현재 시점 conviction 만 사용해 0/1/X 표기 (정확한 누적은 history-store 누적 후 재계산)
  // 26차 Phase 2#11: RECOVERY_RESPONSE_LATENCY_DAYS — signal_change 후 user_action 첫 기록까지
  const cutoff60d = Date.now() - 60 * 86400000;
  const recent60d = log.filter((e) => Number.isFinite(new Date(e.ts).getTime()) && new Date(e.ts).getTime() >= cutoff60d);
  // signal_change 마지막 시점
  const lastSignalChange = recent60d.find((e) => e.kind === 'signal_change');
  let recoveryResponseLatencyDays = -1;
  if (lastSignalChange) {
    const firstActionAfter = recent60d
      .filter((e) => e.kind === 'user_action' && new Date(e.ts).getTime() > new Date(lastSignalChange.ts).getTime())
      .sort((a, b) => new Date(a.ts).getTime() - new Date(b.ts).getTime())[0];
    if (firstActionAfter) {
      recoveryResponseLatencyDays = Math.floor(
        (new Date(firstActionAfter.ts).getTime() - new Date(lastSignalChange.ts).getTime()) / 86400000,
      );
    }
  }

  // 24차 Phase 2#20: REVIEW_DEPTH 평균 글자수 + STREAK
  const reviews7dList = reviews7d.filter((e) => e.kind === 'observation');
  const reviewDepthAvgChars = reviews7dList.length > 0
    ? Math.round(reviews7dList.reduce((s, e) => s + (e.notes?.length ?? 0), 0) / reviews7dList.length)
    : 0;
  // streak: 일별 분포 — observation 기록된 날짜 set 만들고 연속 일수 카운트
  const reviewDates = new Set<string>();
  for (const e of log) {
    if (e.kind !== 'observation') continue;
    const t = new Date(e.ts).getTime();
    if (Number.isFinite(t) && t >= cutoff30d) reviewDates.add(new Date(t).toISOString().slice(0, 10));
  }
  let reviewStreakDays = 0;
  for (let i = 0; i < 30; i++) {
    const d = new Date(Date.now() - i * 86400000).toISOString().slice(0, 10);
    if (reviewDates.has(d)) reviewStreakDays++;
    else if (i > 0) break;
  }
  // max streak in 30d window
  let reviewMaxStreakDays = 0;
  let curStreak = 0;
  for (let i = 29; i >= 0; i--) {
    const d = new Date(Date.now() - i * 86400000).toISOString().slice(0, 10);
    if (reviewDates.has(d)) {
      curStreak++;
      if (curStreak > reviewMaxStreakDays) reviewMaxStreakDays = curStreak;
    } else {
      curStreak = 0;
    }
  }

  return {
    reviewsLast7d: reviews7d.length,
    reviewWarning: reviews7d.length === 0
      ? '⚠️ 7일간 복기 0회 — 노션 §"복기하고 공부" 정합 위반'
      : reviews7d.length < 3
        ? '🟡 7일간 복기 < 3회 — 빈도 부족'
        : null,
    impulsiveTrades24h: impulsive,
    impulsiveWarning: impulsive >= 1
      ? `⚠️ 24h 내 ${impulsive}개 자산에서 빈번한 변경 — 노션 §"성급한 매매" 가드`
      : null,
    againstSystemCount4w: againstCount,
    patternWarning: againstCount >= 3
      ? `⚠️ 4주간 시스템 권고 반대 ${againstCount}회 — 반복 패턴 점검 필요 (video1 §1부 "패닉 매도+추격 매수")`
      : null,
    horizonChangeCount30d: horizonChanges.length,
    // 24차 Phase 2#18-20
    independenceIndex90d,
    waitDiscipline30d,
    reviewDepthAvgChars,
    reviewStreakDays,
    reviewMaxStreakDays,
    recoveryResponseLatencyDays,
  };
}

/** 현재 allocation vs InvestmentPlan 규칙 비교 — 위반 목록 반환 */
export async function detectRuleViolations(snapshot: SystemSnapshot): Promise<string[]> {
  const plan = await readInvestmentPlan();
  const alloc = snapshot.allocation?.allocations || {};
  const violations: string[] = [];

  // 20차 D1 + 21차 P1#5: 복기·impulsive·반복 패턴·horizon 변경 가드
  try {
    const discipline = await computePlanDiscipline();
    if (discipline.reviewWarning) violations.push(discipline.reviewWarning);
    if (discipline.impulsiveWarning) violations.push(discipline.impulsiveWarning);
    if (discipline.patternWarning) violations.push(discipline.patternWarning);
    if (discipline.horizonChangeCount30d >= 2) {
      violations.push(`⚠️ 30일간 horizon ${discipline.horizonChangeCount30d}회 변경 — video1 §5부 "흔들리지 않는다" 위반`);
    }
  } catch { /* skip */ }

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

  // 22차 P2#10 + P1#2: 권고 vs 사용자 currentHoldings drift ≥10%p 위반
  const holdings = plan.currentHoldings;
  if (holdings) {
    const drifts: Array<[string, number]> = [];
    for (const [k, v] of Object.entries(alloc)) {
      const userPct = (holdings as Record<string, number | undefined>)[k] ?? 0;
      const drift = Math.abs((v ?? 0) - userPct);
      if (drift >= 10) drifts.push([k, drift]);
    }
    if (drifts.length > 0) {
      const top = drifts.sort((a, b) => b[1] - a[1]).slice(0, 3).map(([k, d]) => `${k} ${d.toFixed(0)}%p`).join(', ');
      violations.push(`⚠️ Portfolio drift ≥10%p — ${top} (권고 vs 실제 보유 차이, video1 §"비중 기준")`);
    }
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
  // 22차 P2#24: 운영자 한마디 회전 헤더
  try {
    // 동적 import 에러는 무시 (formatWeeklyReportText 는 동기 함수라 require)
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { getDailyQuote } = require('./operator-quotes');
    const q = getDailyQuote();
    lines.push(`💬 ${q.short} — 자산제곱`);
    lines.push('');
  } catch { /* skip */ }
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
  // 22차 P2#25: footer 영구 메시지
  lines.push('');
  lines.push('— 우린 함께 웃자 (자산제곱)');
  return lines.join('\n');
}
