/**
 * Telegram 커맨드 핸들러 (17차 Phase 3 D2).
 *
 * 지원 커맨드:
 *   /status          → 현재 레짐 + 주요 신호 요약
 *   /signal <ASSET>  → 특정 자산 (NASDAQ/KOSPI/GOLD/SILVER/COPPER) 상세
 *   /plan            → 현재 InvestmentPlan 조회
 *   /weekly          → Weekly report 즉시 생성 + 전송
 *
 * getUpdates long-poll 방식. 저사양 홈서버 대응 — webhook 없이 Docker 내부에서 동작.
 * offset 을 파일에 저장하여 재시작 후 중복 처리 방지.
 */

import axios from 'axios';
import https from 'https';
import fs from 'fs/promises';
import path from 'path';
import { childLogger, serializeError } from './logger';
import { DEFAULT_PROFILE, getSnapshot } from '../state/cache';
import { readInvestmentPlan } from './investment-plan';
import { buildWeeklyReport, detectRuleViolations, formatWeeklyReportText } from './weekly-report';
import { sendWeeklyReportText } from './telegram';

const log = childLogger({ module: 'service.telegram-commands' });
const ipv4Agent = new https.Agent({ family: 4, keepAlive: false });

const OFFSET_FILE = path.join(
  process.env.INVESTMENT_DATA_DIR || '/app/data/investment',
  'telegram-offset.json',
);

interface TelegramUpdate {
  update_id: number;
  message?: {
    message_id: number;
    text?: string;
    chat: { id: number | string };
  };
}

async function readOffset(): Promise<number> {
  try {
    const raw = await fs.readFile(OFFSET_FILE, 'utf-8');
    const parsed = JSON.parse(raw) as { offset?: number };
    return parsed.offset || 0;
  } catch {
    return 0;
  }
}

async function writeOffset(offset: number): Promise<void> {
  try {
    await fs.mkdir(path.dirname(OFFSET_FILE), { recursive: true });
    await fs.writeFile(OFFSET_FILE, JSON.stringify({ offset }), 'utf-8');
  } catch (err) {
    log.warn({ error: serializeError(err) }, 'offset persist failed');
  }
}

async function sendReply(chatId: number | string, text: string): Promise<void> {
  const token = process.env.TELEGRAM_BOT_TOKEN;
  if (!token) return;
  try {
    await axios.post(
      `https://api.telegram.org/bot${token}/sendMessage`,
      { chat_id: chatId, text },
      { timeout: 10000, httpsAgent: ipv4Agent },
    );
  } catch (err: any) {
    log.warn({ error: serializeError(err) }, 'reply send failed');
  }
}

async function handleStatus(): Promise<string> {
  const snap = await getSnapshot(DEFAULT_PROFILE, false);
  const regime = snap.regime?.regime || 'unknown';
  const score = snap.regime?.score ?? 0;
  const sigs = (snap.signals || [])
    .filter((s) => ['NASDAQ', 'KOSPI', 'GOLD', 'SILVER', 'COPPER'].includes(s.asset))
    .map((s) => `  ${s.asset}: ${s.signal} (${s.conditionsMet}/${s.conditionsTotal})`)
    .join('\n');
  const alloc = snap.allocation?.allocations || {};
  const allocLine = Object.entries(alloc)
    .filter(([, v]) => (v as number) > 0)
    .map(([k, v]) => `${k}:${v}%`)
    .join(' / ');
  return `📊 MacroSquare /status\n레짐: ${regime} (${score})\n\n자산별 신호:\n${sigs}\n\n비중: ${allocLine}`;
}

async function handleSignal(asset: string): Promise<string> {
  const snap = await getSnapshot(DEFAULT_PROFILE, false);
  const target = asset.toUpperCase();
  const sig = (snap.signals || []).find((s) => s.asset === target);
  if (!sig) return `❓ 알 수 없는 자산: ${asset}`;
  const reasons = (sig.reasons || []).slice(0, 5).map((r) => `  • ${r}`).join('\n');
  const unmet = (sig.unmetReasons || []).slice(0, 3).map((r) => `  × ${r}`).join('\n');
  return `📈 ${target} ${sig.signal} (${sig.conditionsMet}/${sig.conditionsTotal})\n\n충족:\n${reasons}${unmet ? '\n\n미충족:\n' + unmet : ''}`;
}

async function handlePlan(): Promise<string> {
  const plan = await readInvestmentPlan();
  let holdingsText = '';
  if (plan.currentHoldings) {
    const items = Object.entries(plan.currentHoldings).filter(([, v]) => typeof v === 'number' && v > 0);
    if (items.length > 0) {
      holdingsText = `\n\n📊 현재 보유: ${items.map(([k, v]) => `${k} ${v}%`).join(' / ')}`;
    }
  }
  let capitalText = '';
  if (plan.totalCapitalKRW) capitalText = `\n총 자본: ${plan.totalCapitalKRW.toLocaleString('ko-KR')} KRW`;
  return `🧭 My Plan\n시계열: ${plan.horizon}\n목표: 연 ${plan.targetReturnAnnualPct}%\n최대 MDD: ${plan.maxDrawdownTolerancePct}%\n레버리지 상한: ${plan.leverageMaxPct}%\n익절: ${plan.profitTakeTargetPct}% / 손절: ${plan.stopLossPct}%\n월 DCA: ${plan.monthlyDCA_KRW.toLocaleString('ko-KR')}원${capitalText}${holdingsText}\n업데이트: ${plan.updatedAt}`;
}

async function handleWeekly(): Promise<string> {
  const snap = await getSnapshot(DEFAULT_PROFILE, false);
  const report = buildWeeklyReport(snap);
  report.ruleViolations = await detectRuleViolations(snap);
  const text = formatWeeklyReportText(report);
  await sendWeeklyReportText(text);
  return '✅ Weekly report 전송됨.';
}

async function handleCommand(text: string): Promise<string> {
  const trimmed = text.trim();
  const [cmd, ...args] = trimmed.split(/\s+/);
  const normalized = cmd.replace(/@\w+$/, '').toLowerCase(); // /status@bot → /status
  switch (normalized) {
    case '/status':
      return handleStatus();
    case '/signal':
      if (!args[0]) return '사용법: /signal NASDAQ|KOSPI|GOLD|SILVER|COPPER';
      return handleSignal(args[0]);
    case '/plan':
      return handlePlan();
    case '/weekly':
      return handleWeekly();
    case '/log':
      // 22차 P2#18: /log <asset> <BUY|SELL> <qty?> <notes?> — 즉시 trade-log 기록
      if (!args[0] || !args[1]) return '사용법: /log <ASSET> <BUY|SELL> [qty] [notes]';
      return handleLog(args[0], args[1], args.slice(2).join(' '));
    case '/help':
    case '/start':
      return 'MacroSquare bot\n/status — 현재 레짐+신호\n/signal <ASSET> — 자산 상세\n/plan — 내 계획 (보유비중 포함)\n/weekly — 주간 리포트 전송\n/log <ASSET> <BUY|SELL> [qty] [notes] — Trade Log 즉시 기록';
    default:
      return '';
  }
}

// 22차 P2#18: telegram /log 핸들러 — 시스템 권고 vs 사용자 행동 자동 비교
async function handleLog(asset: string, action: string, notes?: string): Promise<string> {
  const upperAction = action.toUpperCase();
  if (!/^(BUY|SELL|REDUCE|ADD|EXIT|TRIM)$/.test(upperAction)) {
    return `❓ action 은 BUY/SELL/REDUCE/ADD/EXIT/TRIM 중 하나`;
  }
  const snap = await getSnapshot(DEFAULT_PROFILE, false);
  const sig = snap.signals?.find((s) => s.asset === asset.toUpperCase());
  const sysSignal = sig?.signal;
  const userBuy = /BUY|ADD/i.test(upperAction);
  const userSell = /SELL|EXIT|REDUCE|TRIM/i.test(upperAction);
  const sysBuy = sysSignal === 'BUY' || sysSignal === 'STRONG_BUY';
  const sysSell = sysSignal === 'SELL' || sysSignal === 'REDUCE';
  let against = false;
  if ((userSell && sysBuy) || (userBuy && sysSell)) against = true;
  // 28차 영상6: horizon 정합 체크 — 사용자 horizon 과 신호 시간 프레임 일치도
  // 단기/스윙 신호 (NASDAQ_CROSS, RSI 등) vs long horizon 사용자 → 경고
  const { appendTradeLog, readInvestmentPlan } = await import('./investment-plan');
  const plan = await readInvestmentPlan();
  const horizon = plan.horizon ?? 'medium';
  let horizonWarning = '';
  // 단순 휴리스틱: short horizon 사용자가 매크로/장기 신호 (regime 기반) 결정에 따라 trade — OK
  // long horizon 사용자가 단기 변동에 자주 trade — 경고
  if (horizon === 'long' && userSell && sysSignal === 'BUY') {
    horizonWarning = '\n⚠️ video6 §"시간프레임이 정해지지 않으면 전략이 없다" — long horizon 인데 단기 매도 검토';
  } else if (horizon === 'short' && (sysSignal === 'STRONG_BUY' || sysSignal === 'BUY')) {
    horizonWarning = '\n💡 long signal 인데 short horizon — 단기 익절 후 재진입 시 손익비 1:3 점검 권장 (video6)';
  }
  await appendTradeLog({
    kind: 'user_action',
    asset: asset.toUpperCase(),
    to: upperAction,
    notes: notes || `telegram /log`,
    againstSystemRecommendation: against,
    context: { regimeAtAction: snap.regime?.regime, signalAtAction: sysSignal, userHorizon: horizon },
  });
  const flag = against ? '🔴 시스템 권고와 반대' : '🟢 시스템 권고 정합';
  return `✅ Trade Log 기록\n${asset.toUpperCase()} ${upperAction}\n시스템: ${sysSignal || '-'} (${snap.regime?.regime}) / horizon: ${horizon}\n${flag}${horizonWarning}`;
}

async function pollOnce(): Promise<void> {
  const token = process.env.TELEGRAM_BOT_TOKEN;
  if (!token) return;
  const offset = await readOffset();
  try {
    const { data } = await axios.get(`https://api.telegram.org/bot${token}/getUpdates`, {
      params: { offset: offset + 1, timeout: 0, limit: 20 },
      timeout: 15000,
      httpsAgent: ipv4Agent,
    });
    const updates: TelegramUpdate[] = data?.result || [];
    if (updates.length === 0) return;
    let maxId = offset;
    for (const u of updates) {
      if (u.update_id > maxId) maxId = u.update_id;
      const text = u.message?.text;
      const chatId = u.message?.chat.id;
      if (!text || !chatId) continue;
      if (!text.startsWith('/')) continue;
      try {
        const reply = await handleCommand(text);
        if (reply) await sendReply(chatId, reply);
      } catch (err) {
        log.warn({ error: serializeError(err), text }, 'command handler failed');
        await sendReply(chatId, '❌ 명령 처리 실패');
      }
    }
    await writeOffset(maxId);
  } catch (err) {
    log.warn({ error: serializeError(err) }, 'poll failed');
  }
}

export function startTelegramCommandPoller(intervalMs = 30000): void {
  const token = process.env.TELEGRAM_BOT_TOKEN;
  if (!token) {
    log.info('TELEGRAM_BOT_TOKEN 없음 — command poller 비활성');
    return;
  }
  log.info({ intervalMs }, 'telegram command poller started');
  setInterval(() => {
    void pollOnce();
  }, intervalMs);
}
