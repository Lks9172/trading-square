import axios from 'axios';
import https from 'https';
import { AssetSignal, RegimeState, AllocationPlan } from '../types/indicators';

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
let previousRegime: string = '';
let previousAllocations: Record<string, number> = {};
let previousOverallSignal: string = '';

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

function formatSignalChange(asset: string, oldSignal: string, newSignal: string, reasons: string[]): string {
  const label = ASSET_LABELS[asset] || asset;
  const oldEmoji = SIGNAL_EMOJI[oldSignal] || '?';
  const newEmoji = SIGNAL_EMOJI[newSignal] || '?';
  const topReasons = reasons.slice(0, 3).join('\n  • ');
  return `📊 ${label} 신호 변경\n${oldEmoji} ${oldSignal} → ${newEmoji} ${newSignal}\n  • ${topReasons}`;
}

function formatRegimeChange(oldRegime: string, newRegime: string, score: number): string {
  const oldEmoji = REGIME_EMOJI[oldRegime] || '?';
  const newEmoji = REGIME_EMOJI[newRegime] || '?';
  return `🏛️ 국면 변경\n${oldEmoji} ${oldRegime} → ${newEmoji} ${newRegime} (${score}/100)`;
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

export async function checkAndNotify(
  signals: AssetSignal[],
  regime: RegimeState,
  allocation?: AllocationPlan
) {
  const token = process.env.TELEGRAM_BOT_TOKEN;
  const chatId = process.env.TELEGRAM_CHAT_ID;
  if (!token || !chatId) return;

  const messages: string[] = [];

  const overallSignal = overallSignalFromAllocation(allocation);

  if (previousRegime && previousRegime !== regime.regime) {
    messages.push(formatRegimeChange(previousRegime, regime.regime, regime.score));
  }
  previousRegime = regime.regime;

  if (previousOverallSignal && previousOverallSignal !== overallSignal) {
    messages.push(formatOverallSignalChange(previousOverallSignal, overallSignal, allocation));
  }
  previousOverallSignal = overallSignal;

  for (const sig of signals) {
    const prev = previousSignals[sig.asset];
    if (prev && prev !== sig.signal) {
      messages.push(formatSignalChange(sig.asset, prev, sig.signal, sig.reasons));
    }
    previousSignals[sig.asset] = sig.signal;
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

  if (messages.length === 0 && allocChanges.length === 0) return;

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
  } catch (err: any) {
    console.error('Telegram notification failed:', err.message);
  }
}

export async function sendStartupSnapshot(signals: AssetSignal[], regime: RegimeState, allocation?: AllocationPlan) {
  const token = process.env.TELEGRAM_BOT_TOKEN;
  const chatId = process.env.TELEGRAM_CHAT_ID;
  if (!token || !chatId) return false;

  previousRegime = regime.regime;
  previousOverallSignal = overallSignalFromAllocation(allocation);
  previousSignals = Object.fromEntries(signals.map((s) => [s.asset, s.signal]));
  previousAllocations = allocation ? { ...allocation.allocations } : {};

  const text = `🚀 MacroSquare 서버 시작\n${new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })}` + buildSummaryMessage(signals, regime, allocation);

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
