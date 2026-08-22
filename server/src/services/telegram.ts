import axios from 'axios';
import https from 'https';
import { AssetSignal, RegimeState, AllocationPlan, MarketBreadthGateSnapshot } from '../types/indicators';
import type { TelegramBottomCandidateSummary } from './telegram-bottom-candidates';

// IPv4 전용 소켓 강제. dns.setDefaultResultOrder('ipv4first') 는 해석 우선순위만
// 바꾸고 IPv6 시도를 완전히 차단하진 않는다. httpsAgent 에 family: 4 를 지정하면
// 소켓 단계에서 IPv4 only 로 못박아 Docker 브릿지 IPv6 라우팅 문제 우회.
const ipv4Agent = new https.Agent({ family: 4, keepAlive: false });

const SIGNAL_EMOJI: Record<string, string> = {
  STRONG_BUY: '🟢🟢',
  BUY: '🟢',
  HOLD: '⚪',
  REDUCE: '🟡',
  SELL: '🔴',
};

const ASSET_LABELS: Record<string, string> = {
  NASDAQ: '나스닥',
  KOSPI: '코스피',
  GOLD: '금',
  SILVER: '은',
  COPPER: '구리',
  CASH: '현금',
  LEVERAGE: '레버리지',
};

const REGIME_EMOJI: Record<string, string> = {
  RISK_ON: '🟢',
  NEUTRAL: '🔵',
  CAUTION: '🟡',
  CORRECTION: '🟠',
  PANIC_BUT_OK: '🔴',
  RECESSION_RISK: '⚫',
};

let previousSignals: Record<string, string> = {};
let previousSignalReasons: Record<string, string[]> = {};
let previousRegime: string = '';
let previousRegimeComponents: Record<string, number> = {};
let previousAllocations: Record<string, number> = {};
let previousOverallSignal: string = '';
let previousMarketGateSignalDates: Record<string, string | null> = {};
// 21차 P2#12: regime 변경 cooldown (60분) — single tick spam 방지
let lastRegimeChangeAt = 0;
const REGIME_COOLDOWN_MS = 60 * 60 * 1000;

// 22차 P2#11+P2#12: 알림 등급 + quietHours.
// quietHours 환경변수 형식: "HH-HH" (KST). 예: "22-7" → 22시~익일 7시 quiet.
// quiet 시 INFO/WARN 은 큐잉 없이 무시, CRITICAL 만 통과. 기본값 비활성.
export type NotificationLevel = 'INFO' | 'WARN' | 'CRITICAL';
function isQuietHourKST(level: NotificationLevel): boolean {
  if (level === 'CRITICAL') return false;
  const range = process.env.QUIET_HOURS_KST;
  if (!range) return false;
  const m = range.match(/^(\d{1,2})-(\d{1,2})$/);
  if (!m) return false;
  const start = parseInt(m[1], 10);
  const end = parseInt(m[2], 10);
  const now = new Date();
  const kst = new Date(now.getTime() + (9 * 60 - now.getTimezoneOffset()) * 60000);
  const h = kst.getHours();
  if (start <= end) return h >= start && h < end;
  return h >= start || h < end;
}

// 22차 P2#13: scheduled refresh 실패 누적 카운터 + 5회 연속 실패 escalation
let consecutiveRefreshFailures = 0;
export function recordRefreshSuccess(): void {
  consecutiveRefreshFailures = 0;
}
export async function recordRefreshFailure(reason: string): Promise<void> {
  consecutiveRefreshFailures++;
  if (consecutiveRefreshFailures >= 5) {
    const token = process.env.TELEGRAM_BOT_TOKEN;
    const chatId = process.env.TELEGRAM_CHAT_ID;
    if (token && chatId) {
      try {
        await axios.post(`https://api.telegram.org/bot${token}/sendMessage`, {
          chat_id: chatId,
          text: `🚨 [CRITICAL] MacroSquare 5회 연속 snapshot refresh 실패\n사유: ${reason.slice(0, 200)}\n시각: ${new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}`,
        }, { timeout: 10000, httpsAgent: ipv4Agent });
        consecutiveRefreshFailures = 0; // 알림 후 reset (재발생 시 다시 카운트)
      } catch { /* 알림 실패 무시 */ }
    }
  }
}

const REGIME_COMPONENT_LABELS: Record<string, string> = {
  vix: 'VIX',
  yieldCurve: '수익률곡선',
  hySpread: 'HY 스프레드',
  joblessClaims: '실업수당',
  nasdaqDisparity: '나스닥 이격도',
  finStress: '금융 스트레스',
  dxy: '달러 방향',
  liquidityDir: '유동성 방향',
  wti: 'WTI 유가',
  globalM2: '글로벌 M2',
  smartMoney: '스마트머니',
  sectorMomentum: '섹터 모멘텀',
  policy: '정책',
  geoRisk: '지정학',
};

const ALLOC_LABELS: Record<string, string> = {
  cash: '현금',
  nasdaq: '나스닥',
  leverage: '레버리지',
  gold: '금',
  silver: '은',
  copper: '구리/원자재',
  korea: '한국',
  emerging: '신흥국',
};

function formatSignalChange(
  asset: string,
  oldSignal: string,
  newSignal: string,
  reasons: string[],
  prevReasons: string[] = [],
): string {
  const label = ASSET_LABELS[asset] || asset;
  const oldEmoji = SIGNAL_EMOJI[oldSignal] || '?';
  const newEmoji = SIGNAL_EMOJI[newSignal] || '?';

  // 이전 ↔ 현재 reasons diff — 양방향
  const prevSet = new Set(prevReasons);
  const curSet = new Set(reasons);
  const newlyMet = reasons.filter((r) => !prevSet.has(r));      // 새로 켜진 근거
  const newlyLost = prevReasons.filter((r) => !curSet.has(r));  // 사라진 근거

  const sections: string[] = [];
  if (newlyMet.length > 0) {
    sections.push(`🆕 새로 충족:\n  • ${newlyMet.slice(0, 3).join('\n  • ')}`);
  }
  if (newlyLost.length > 0) {
    sections.push(`❌ 사라진 근거:\n  • ${newlyLost.slice(0, 3).join('\n  • ')}`);
  }
  // 양쪽 다 비어있으면 (드물지만 가능) 현재 top 3 로 fallback
  if (sections.length === 0) {
    sections.push(`📌 현재 근거:\n  • ${reasons.slice(0, 3).join('\n  • ')}`);
  }

  return `📊 ${label} 신호 변경\n${oldEmoji} ${oldSignal} → ${newEmoji} ${newSignal}\n\n${sections.join('\n\n')}`;
}

function formatRegimeChange(
  oldRegime: string,
  newRegime: string,
  score: number,
  prevComponents: Record<string, number>,
  newComponents: Record<string, number>,
): string {
  const oldEmoji = REGIME_EMOJI[oldRegime] || '?';
  const newEmoji = REGIME_EMOJI[newRegime] || '?';

  // 컴포넌트 점수 변화 top-3 (절댓값 기준)
  const deltas: Array<{ key: string; delta: number; now: number }> = [];
  for (const [k, v] of Object.entries(newComponents)) {
    const prev = prevComponents[k] ?? 0;
    const delta = v - prev;
    if (Math.abs(delta) > 0.01) deltas.push({ key: k, delta, now: v });
  }
  deltas.sort((a, b) => Math.abs(b.delta) - Math.abs(a.delta));

  let deltaSection = '';
  if (deltas.length > 0) {
    const top = deltas.slice(0, 3).map((d) => {
      const label = REGIME_COMPONENT_LABELS[d.key] || d.key;
      const arrow = d.delta > 0 ? '📈' : '📉';
      const sign = d.delta > 0 ? '+' : '';
      return `${arrow} ${label}: ${sign}${d.delta.toFixed(1)} (현재 ${d.now.toFixed(1)})`;
    }).join('\n  ');
    deltaSection = `\n\n🔍 주요 변화 (top 3):\n  ${top}`;
  }

  return `🏛️ 국면 변경\n${oldEmoji} ${oldRegime} → ${newEmoji} ${newRegime} (${score}/100)${deltaSection}`;
}

function overallSignalFromAllocation(allocation?: AllocationPlan): string {
  if (!allocation) return 'UNKNOWN';
  const cash = allocation.allocations.cash ?? 0;
  if (cash >= 45) return 'DEFENSIVE';
  if (cash >= 25) return 'BALANCED';
  return 'OFFENSIVE';
}

function formatOverallSignalChange(oldSignal: string, newSignal: string, allocation?: AllocationPlan): string {
  return `🧭 종합 자산 신호 변경\n${oldSignal} → ${newSignal}\n현금 비중: ${allocation?.allocations.cash ?? 0}%`;
}

function buildSummaryMessage(signals: AssetSignal[], regime: RegimeState, allocation?: AllocationPlan) {
  const signalSummary = signals
    .map((s) => {
      const label = ASSET_LABELS[s.asset] || s.asset;
      const emoji = SIGNAL_EMOJI[s.signal] || '?';
      const score = s.conditionsTotal > 0 ? `(${s.conditionsMet}/${s.conditionsTotal})` : '';
      return `${emoji} ${label}: ${s.signal} ${score}`;
    })
    .join('\n');

  const allocSummary = allocation
    ? Object.entries(allocation.allocations)
        .filter(([, pct]) => pct > 0)
        .sort((a, b) => b[1] - a[1])
        .map(([asset, pct]) => `  ${ALLOC_LABELS[asset] || asset}: ${pct}%`)
        .join('\n')
    : '';

  const regimeEmoji = REGIME_EMOJI[regime.regime] || '?';
  const overall = overallSignalFromAllocation(allocation);
  const summarySection = `\n\n📋 전체 신호 현황\n${regimeEmoji} 국면: ${regime.regime} (${regime.score}/100)\n🧭 종합 자산 신호: ${overall}\n${signalSummary}`;
  const allocSection = allocSummary ? `\n\n💼 포트폴리오 비중\n${allocSummary}\n레버리지: ${allocation?.leverageAllowed ? '허용' : '불허'}` : '';
  return summarySection + allocSection;
}

function reversalConfirmationLabel(item: TelegramBottomCandidateSummary): 'OFF' | 'ON(보통)' | 'ON(강함)' {
  const hasSignal = Boolean(item.signalDate) && Array.isArray(item.reasons) && item.reasons.some((reason) => String(reason || '').trim().length > 0);
  if (!hasSignal) return 'OFF';
  const score = typeof item.confirmedBottomScore === 'number' ? item.confirmedBottomScore : null;
  if (item.confirmedBottomState === '확신' && score !== null && score >= 85) return 'ON(강함)';
  return 'ON(보통)';
}

function formatBottomSignalReasons(item: TelegramBottomCandidateSummary, limit = 2): string | null {
  const reasons = Array.isArray(item.reasons)
    ? item.reasons.map((reason) => String(reason || '').trim()).filter(Boolean).slice(0, limit)
    : [];
  if (!reasons.length) return null;
  return reasons.map((reason) => `   · ${reason}`).join('\n');
}

function formatStartupSectionItems(items: TelegramBottomCandidateSummary[]): string[] {
  return items.map((item, index) => {
    const symbol = item.kind === 'company' ? item.ticker : item.symbol;
    const action = item.action ? ` / ${item.action}` : '';
    const reversalOn = reversalConfirmationLabel(item);
    const reversalLine = `
   • 반전 확인 신호: ${reversalOn}`;
    const signalDate = item.signalDate ? `
   • 반전 확인일: ${item.signalDate}` : '';
    const reasons = formatBottomSignalReasons(item, 2);
    return `${index + 1}. ${symbol} ${item.confirmedBottomState} / B${item.buyScore} / 총${item.totalScore}${action}${reversalLine}${signalDate}${reasons ? `
${reasons}` : ''}`;
  });
}

function buildMarketBreadthLines(gate?: MarketBreadthGateSnapshot | null): string[] {
  if (!gate?.markets?.length) return [];
  return gate.markets.map((market) => {
    const label = market.asset === 'NASDAQ' ? 'NASDAQ' : 'S&P500';
    const recent = market.signalDate ? ` / 최근 ${market.signalDate}` : '';
    return `   • ${label} 반전신호: ${market.status}${recent}`;
  });
}

function buildConfirmedBottomSummary(items?: TelegramBottomCandidateSummary[], gate?: MarketBreadthGateSnapshot | null) {
  if (!items?.length) return '';
  const marketLines = buildMarketBreadthLines(gate);
  const marketPrefix = marketLines.length ? `${marketLines.join('\n')}\n` : '';
  const companies = items.filter((item) => item.kind === 'company');
  const cryptos = items.filter((item) => item.kind === 'crypto');
  const sections: string[] = [];
  if (companies.length) sections.push(`📈 회사\n${marketPrefix}${formatStartupSectionItems(companies).join('\n')}`);
  if (cryptos.length) sections.push(`🪙 코인\n${marketPrefix}${formatStartupSectionItems(cryptos).join('\n')}`);
  return `\n\n🟣 현재 반전 후보/확신 (Buy≥70 · 총점≥70)\n${sections.join('\n\n')}`;
}

function buildMarketBreadthGateSummary(gate?: MarketBreadthGateSnapshot | null) {
  if (!gate?.markets?.length) return '';
  const lines = gate.markets.map((market) => {
    const base = `${market.asset === 'NASDAQ' ? '🟣 NASDAQ' : '🔵 S&P500'} ${market.status}`;
    const signal = market.signalDate ? ` / 최근 ${market.signalDate}` : '';
    const stats = ` / 1·2·3M ${market.avg1m ?? '—'}·${market.avg2m ?? '—'}·${market.avg3m ?? '—'}%`;
    return `${base}${signal}${stats}`;
  });
  return `\n\n📡 시장 Breadth 실전 게이트\n${lines.join('\n')}`;
}

function formatMarketBreadthGateAlert(gate: MarketBreadthGateSnapshot) {
  const turnedOn = gate.markets.filter((market) => {
    const prev = previousMarketGateSignalDates[market.asset] ?? null;
    return market.status === 'ON' && market.signalDate && market.signalDate !== prev;
  });
  if (!turnedOn.length) return null;
  const body = turnedOn.map((market) => [
    `${market.asset === 'NASDAQ' ? '🟣 NASDAQ' : '🔵 S&P500'} ON`,
    `• 신호일: ${market.signalDate}`,
    `• 요약: ${market.summary}`,
    `• 평균 수익률: 1M ${market.avg1m ?? '—'}% / 2M ${market.avg2m ?? '—'}% / 3M ${market.avg3m ?? '—'}%`,
  ].join('\n')).join('\n\n');
  return `🚦 시장 Breadth 게이트 ON\n${new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}\n\n${body}`;
}

export function previewStartupBottomCandidateSummary(items?: TelegramBottomCandidateSummary[], gate?: MarketBreadthGateSnapshot | null) {
  return buildConfirmedBottomSummary(items, gate);
}

export async function checkAndNotify(
  signals: AssetSignal[],
  regime: RegimeState,
  allocation?: AllocationPlan,
  marketBreadthGate?: MarketBreadthGateSnapshot | null,
) {
  const token = process.env.TELEGRAM_BOT_TOKEN;
  const chatId = process.env.TELEGRAM_CHAT_ID;
  if (!token || !chatId) return;

  const messages: string[] = [];

  const overallSignal = overallSignalFromAllocation(allocation);

  if (previousRegime && previousRegime !== regime.regime) {
    // 21차 P2#12: 60분 내 재변경은 알림 무시 (whipsaw 방지). 단 처음 변경은 항상 알림.
    const sinceLastChange = Date.now() - lastRegimeChangeAt;
    if (lastRegimeChangeAt === 0 || sinceLastChange >= REGIME_COOLDOWN_MS) {
      messages.push(formatRegimeChange(previousRegime, regime.regime, regime.score, previousRegimeComponents, regime.components));
      lastRegimeChangeAt = Date.now();
    } else {
      console.log(`[regime-cooldown] ${previousRegime} → ${regime.regime} suppressed (since ${Math.floor(sinceLastChange / 60000)}min)`);
    }
  }
  previousRegime = regime.regime;
  previousRegimeComponents = { ...regime.components };

  if (previousOverallSignal && previousOverallSignal !== overallSignal) {
    messages.push(formatOverallSignalChange(previousOverallSignal, overallSignal, allocation));
  }
  previousOverallSignal = overallSignal;

  for (const sig of signals) {
    const prev = previousSignals[sig.asset];
    if (prev && prev !== sig.signal) {
      const prevReasons = previousSignalReasons[sig.asset] || [];
      messages.push(formatSignalChange(sig.asset, prev, sig.signal, sig.reasons, prevReasons));
    }
    previousSignals[sig.asset] = sig.signal;
    previousSignalReasons[sig.asset] = [...sig.reasons];
  }

  const allocChanges: string[] = [];
  if (allocation) {
    for (const [asset, pct] of Object.entries(allocation.allocations)) {
      const prev = previousAllocations[asset];
      if (prev !== undefined && prev !== pct) {
        const label = ALLOC_LABELS[asset] || asset;
        const arrow = pct > prev ? '🔼' : '🔽';
        allocChanges.push(`${arrow} ${label}: ${prev}% → ${pct}%`);
      }
    }
    previousAllocations = { ...allocation.allocations };
  }

  const marketGateAlert = marketBreadthGate ? formatMarketBreadthGateAlert(marketBreadthGate) : null;
  if (messages.length === 0 && allocChanges.length === 0 && !marketGateAlert) return;

  // 22차 P2#11+P2#12: quietHours 적용 — regime 변경/신호 변경은 WARN, allocChanges 만 있으면 INFO
  const level: NotificationLevel = (messages.some((m) => m.includes('국면 변경') || m.includes('신호 변경'))) ? 'WARN' : 'INFO';
  if (isQuietHourKST(level)) {
    console.log(`[quiet-hours] ${level} suppressed`);
    return;
  }

  const header = `⏰ MacroSquare 알림\n${new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}`;

  let changeSection = '';
  if (messages.length > 0) {
    changeSection = `\n\n🔔 신호 변경\n${messages.join('\n\n')}`;
  }
  if (allocChanges.length > 0) {
    changeSection += `\n\n📦 비중 변경\n${allocChanges.join('\n')}`;
  }

  const text = header + changeSection + buildSummaryMessage(signals, regime, allocation);

  try {
    await axios.post(`https://api.telegram.org/bot${token}/sendMessage`, {
      chat_id: chatId,
      text,
    }, { timeout: 10000, httpsAgent: ipv4Agent });
    if (marketGateAlert) {
      await axios.post(`https://api.telegram.org/bot${token}/sendMessage`, {
        chat_id: chatId,
        text: marketGateAlert,
      }, { timeout: 10000, httpsAgent: ipv4Agent });
    }
  } catch (err: any) {
    console.error('Telegram notification failed:', err.message);
  } finally {
    if (marketBreadthGate?.markets?.length) {
      previousMarketGateSignalDates = Object.fromEntries(marketBreadthGate.markets.map((market) => [market.asset, market.signalDate ?? null]));
    }
  }
}

export async function sendStartupSnapshot(
  signals: AssetSignal[],
  regime: RegimeState,
  allocation?: AllocationPlan,
  options?: { confirmedBottomCompanies?: TelegramBottomCandidateSummary[]; marketBreadthGate?: MarketBreadthGateSnapshot | null },
) {
  const token = process.env.TELEGRAM_BOT_TOKEN;
  const chatId = process.env.TELEGRAM_CHAT_ID;
  if (!token || !chatId) return false;

  previousRegime = regime.regime;
  previousRegimeComponents = { ...regime.components };
  previousOverallSignal = overallSignalFromAllocation(allocation);
  previousSignals = Object.fromEntries(signals.map((s) => [s.asset, s.signal]));
  previousSignalReasons = Object.fromEntries(signals.map((s) => [s.asset, [...s.reasons]]));
  previousAllocations = allocation ? { ...allocation.allocations } : {};
  previousMarketGateSignalDates = Object.fromEntries((options?.marketBreadthGate?.markets ?? []).map((market) => [market.asset, market.signalDate ?? null]));

  const text = `🚀 MacroSquare 서버 시작\n${new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}`
    + buildSummaryMessage(signals, regime, allocation)
    + buildConfirmedBottomSummary(options?.confirmedBottomCompanies, options?.marketBreadthGate)
    + buildMarketBreadthGateSummary(options?.marketBreadthGate);

  // 재시도 5회, timeout 짧게(10s), 지수 backoff. 총 최대 ~2분 안에 6번 시도.
  const MAX_ATTEMPTS = 5;
  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    try {
      await axios.post(`https://api.telegram.org/bot${token}/sendMessage`, {
        chat_id: chatId,
        text,
      }, { timeout: 10000, httpsAgent: ipv4Agent });
      console.log(`Startup telegram sent (attempt ${attempt})`);
      return true;
    } catch (err: any) {
      console.error(`Startup telegram attempt ${attempt} failed:`, err?.message || err?.code);
      if (attempt < MAX_ATTEMPTS) {
        const waitMs = 10000 + attempt * 10000; // 20s, 30s, 40s, 50s
        await new Promise((r) => setTimeout(r, waitMs));
      }
    }
  }
  return false;
}

export async function sendStartupNotice(options?: { confirmedBottomCompanies?: TelegramBottomCandidateSummary[]; marketBreadthGate?: MarketBreadthGateSnapshot | null }): Promise<boolean> {
  const text = `🚀 MacroSquare 서버 시작\n${new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}`
    + `\n\n서버 재기동 완료. 상세 스냅샷/캐시는 순차적으로 워밍업됩니다.`
    + buildConfirmedBottomSummary(options?.confirmedBottomCompanies, options?.marketBreadthGate)
    + buildMarketBreadthGateSummary(options?.marketBreadthGate);
  try {
    return await sendTelegramText(text);
  } catch {
    return false;
  }
}

// 17차 Phase 2 D1: Weekly report Telegram 전송
export async function sendWeeklyReportText(text: string): Promise<boolean> {
  const token = process.env.TELEGRAM_BOT_TOKEN;
  const chatId = process.env.TELEGRAM_CHAT_ID;
  if (!token || !chatId) return false;
  try {
    await sendTelegramText(text);
    return true;
  } catch (err: any) {
    console.error('Weekly report telegram failed:', err?.message || err?.code);
    return false;
  }
}

export async function sendTelegramText(text: string): Promise<boolean> {
  const token = process.env.TELEGRAM_BOT_TOKEN;
  const chatId = process.env.TELEGRAM_CHAT_ID;
  if (!token || !chatId) return false;
  const MAX = 3800;
  const chunks: string[] = [];
  for (let i = 0; i < text.length; i += MAX) chunks.push(text.slice(i, i + MAX));
  for (const chunk of chunks) {
    await axios.post(`https://api.telegram.org/bot${token}/sendMessage`, {
      chat_id: chatId,
      text: chunk,
    }, { timeout: 10000, httpsAgent: ipv4Agent });
  }
  return true;
}

export async function sendTestMessage() {
  const token = process.env.TELEGRAM_BOT_TOKEN;
  const chatId = process.env.TELEGRAM_CHAT_ID;
  if (!token || !chatId) return false;

  try {
    await axios.post(`https://api.telegram.org/bot${token}/sendMessage`, {
      chat_id: chatId,
      text: '✅ MacroSquare 텔레그램 알림 연결 완료!',
    }, { timeout: 10000, httpsAgent: ipv4Agent });
    return true;
  } catch {
    return false;
  }
}
