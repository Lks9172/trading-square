import { sendTelegramText } from './telegram';
import { checkAndNotifyTelegramBottomCompanyCandidates, getCurrentTelegramBottomCompanyCandidates, TelegramBottomCompanySummary } from './company-bottom-alerts';
import { getCurrentTelegramBottomCryptoCandidates, refreshTelegramBottomCryptoCandidates, TelegramBottomCryptoSummary } from './crypto-bottom-alerts';
import { getMarketBreadthGateSnapshot } from './market-breadth-gate';

export type TelegramBottomCandidateSummary = TelegramBottomCompanySummary | TelegramBottomCryptoSummary;

function compareCandidates(a: TelegramBottomCandidateSummary, b: TelegramBottomCandidateSummary) {
  const stateDelta = (b.confirmedBottomState === '확신' ? 1 : 0) - (a.confirmedBottomState === '확신' ? 1 : 0);
  if (stateDelta !== 0) return stateDelta;
  const bottomDelta = (b.confirmedBottomScore ?? -1) - (a.confirmedBottomScore ?? -1);
  if (bottomDelta !== 0) return bottomDelta;
  const buyDelta = b.buyScore - a.buyScore;
  if (buyDelta !== 0) return buyDelta;
  return b.totalScore - a.totalScore;
}

function candidateKey(item: TelegramBottomCandidateSummary) {
  return `${item.kind}:${item.kind === 'company' ? item.ticker : item.symbol}`;
}

function reversalConfirmationLabel(item: TelegramBottomCandidateSummary): 'OFF' | 'ON(보통)' | 'ON(강함)' {
  const hasSignal = Boolean(item.signalDate) && Array.isArray(item.reasons) && item.reasons.some((reason) => String(reason || '').trim().length > 0);
  if (!hasSignal) return 'OFF';
  const score = typeof item.confirmedBottomScore === 'number' ? item.confirmedBottomScore : null;
  if (item.confirmedBottomState === '확신' && score !== null && score >= 85) return 'ON(강함)';
  return 'ON(보통)';
}

function formatCandidateReasons(item: TelegramBottomCandidateSummary, limit = 3): string[] {
  return Array.isArray(item.reasons)
    ? item.reasons.map((reason) => String(reason || '').trim()).filter(Boolean).slice(0, limit).map((reason) => `   · ${reason}`)
    : [];
}

function formatSectionItems(items: TelegramBottomCandidateSummary[]): string[] {
  return items.map((item, index) => {
    const symbol = item.kind === 'company' ? item.ticker : item.symbol;
    const scope = item.kind === 'company' ? (item.sectorLabel ?? '기업') : (item.category ?? '코인');
    return [
      `${index + 1}. ${symbol} — ${item.name}`,
      `   • 상태: ${item.confirmedBottomState}${typeof item.confirmedBottomScore === 'number' ? ` (${item.confirmedBottomScore})` : ''}`,
      `   • Buy점수: ${item.buyScore} / 총점: ${item.totalScore}`,
      item.action ? `   • 실행 액션: ${item.action}` : null,
      scope ? `   • 분류: ${scope}` : null,
      `   • 반전 확인 신호: ${reversalConfirmationLabel(item)}`,
      item.signalDate ? `   • 반전 확인일: ${item.signalDate}` : null,
      ...formatCandidateReasons(item, 3),
    ].filter((line): line is string => Boolean(line)).join('\n');
  });
}

function buildMarketBreadthLines(gate: Awaited<ReturnType<typeof getMarketBreadthGateSnapshot>>): string[] {
  if (!gate?.markets?.length) return [];
  return gate.markets.map((market) => {
    const label = market.asset === 'NASDAQ' ? 'NASDAQ' : 'S&P500';
    const recent = market.signalDate ? ` / 최근 ${market.signalDate}` : '';
    return `   • ${label} 반전신호: ${market.status}${recent}`;
  });
}

export function formatEntryAlertMessage(items: TelegramBottomCandidateSummary[], marketLines: string[] = []): string {
  const timestamp = new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' });
  const marketPrefix = marketLines.length ? `${marketLines.join('\n')}\n` : '';
  const companies = items.filter((item): item is TelegramBottomCompanySummary => item.kind === 'company');
  const cryptos = items.filter((item): item is TelegramBottomCryptoSummary => item.kind === 'crypto');
  const sections: string[] = [];
  if (companies.length) sections.push(`📈 회사\n${marketPrefix}${formatSectionItems(companies).join('\n')}`);
  if (cryptos.length) sections.push(`🪙 코인\n${marketPrefix}${formatSectionItems(cryptos).join('\n')}`);
  return `🚨 신규 반전 후보/확신 편입\n${timestamp}\n\n${sections.join('\n\n')}`;
}

export async function getCurrentTelegramBottomCandidates(limit = 8, options?: { allowFullScan?: boolean }): Promise<TelegramBottomCandidateSummary[]> {
  const [companies, cryptos] = await Promise.all([
    getCurrentTelegramBottomCompanyCandidates(limit, options),
    getCurrentTelegramBottomCryptoCandidates(limit),
  ]);
  return [...companies, ...cryptos].sort(compareCandidates).slice(0, limit);
}

export async function refreshAndNotifyTelegramBottomCandidates(triggeredBy: string): Promise<void> {
  const before = await getCurrentTelegramBottomCandidates(30, { allowFullScan: false });
  const beforeKeys = new Set(before.map(candidateKey));

  await Promise.all([
    checkAndNotifyTelegramBottomCompanyCandidates(triggeredBy),
    refreshTelegramBottomCryptoCandidates(triggeredBy),
  ]);

  const after = await getCurrentTelegramBottomCandidates(30, { allowFullScan: false });
  const newlyQualified = after.filter((item) => !beforeKeys.has(candidateKey(item)));
  if (!newlyQualified.length) return;

  const gate = await getMarketBreadthGateSnapshot(false);
  await sendTelegramText(formatEntryAlertMessage(newlyQualified.slice(0, 12), buildMarketBreadthLines(gate)));
}
