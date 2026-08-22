import { readSourceCache, writeSourceCache } from './source-cache';

interface AnalystHistoryPoint {
  date: string;
  analystScore: number | null;
  upsidePct: number | null;
}

function historyKey(ticker: string) {
  return `company-analyst-history-${ticker.toUpperCase()}`;
}

export async function getCompanyAnalystHistory(ticker: string): Promise<AnalystHistoryPoint[]> {
  const cached = await readSourceCache<AnalystHistoryPoint[]>(historyKey(ticker));
  const history = Array.isArray(cached?.value) ? cached!.value : [];
  return history.slice().sort((a, b) => a.date.localeCompare(b.date));
}

export async function recordCompanyAnalystSnapshot(
  ticker: string,
  analystScore: number | null,
  upsidePct: number | null,
): Promise<void> {
  const history = await getCompanyAnalystHistory(ticker);
  const today = new Date().toISOString().slice(0, 10);
  const next = history.filter((item) => item.date !== today);
  next.push({ date: today, analystScore, upsidePct });
  next.sort((a, b) => a.date.localeCompare(b.date));
  await writeSourceCache(historyKey(ticker), next.slice(-365));
}

function findCandidateByDays(history: AnalystHistoryPoint[], days: number): AnalystHistoryPoint | null {
  const today = new Date().toISOString().slice(0, 10);
  const target = Date.now() - days * 24 * 60 * 60 * 1000;
  let best: { item: AnalystHistoryPoint; distance: number } | null = null;
  for (const item of history) {
    if (item.date >= today) continue;
    const ts = new Date(item.date).getTime();
    if (!Number.isFinite(ts)) continue;
    const distance = Math.abs(ts - target);
    if (!best || distance < best.distance) best = { item, distance };
  }
  return best?.item ?? null;
}

async function revisionDeltaDays(
  ticker: string,
  currentValue: number | null,
  days: number,
  key: 'upsidePct' | 'analystScore',
  digits: number,
): Promise<number | null> {
  if (currentValue === null) return null;
  const history = await getCompanyAnalystHistory(ticker);
  const candidate = findCandidateByDays(history, days);
  const previous = candidate?.[key];
  if (previous === null || previous === undefined) return null;
  return Number((currentValue - previous).toFixed(digits));
}

export async function estimateRevisionDelta7d(ticker: string, currentUpsidePct: number | null): Promise<number | null> {
  return revisionDeltaDays(ticker, currentUpsidePct, 7, 'upsidePct', 2);
}

export async function estimateRevisionDelta30d(ticker: string, currentUpsidePct: number | null): Promise<number | null> {
  return revisionDeltaDays(ticker, currentUpsidePct, 30, 'upsidePct', 2);
}

export async function estimateRevisionDelta90d(ticker: string, currentUpsidePct: number | null): Promise<number | null> {
  return revisionDeltaDays(ticker, currentUpsidePct, 90, 'upsidePct', 2);
}

export async function analystScoreRevisionDelta7d(ticker: string, currentAnalystScore: number | null): Promise<number | null> {
  return revisionDeltaDays(ticker, currentAnalystScore, 7, 'analystScore', 3);
}

export async function analystScoreRevisionDelta30d(ticker: string, currentAnalystScore: number | null): Promise<number | null> {
  return revisionDeltaDays(ticker, currentAnalystScore, 30, 'analystScore', 3);
}

export async function analystScoreRevisionDelta90d(ticker: string, currentAnalystScore: number | null): Promise<number | null> {
  return revisionDeltaDays(ticker, currentAnalystScore, 90, 'analystScore', 3);
}
