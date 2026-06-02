import { readSourceCache, writeSourceCache } from './source-cache';

interface AnalystHistoryPoint {
  date: string;
  analystScore: number | null;
  upsidePct: number | null;
}

function historyKey(ticker: string) {
  return `company-analyst-history-${ticker.toUpperCase()}`;
}

export async function recordCompanyAnalystSnapshot(
  ticker: string,
  analystScore: number | null,
  upsidePct: number | null,
): Promise<void> {
  const key = historyKey(ticker);
  const cached = await readSourceCache<AnalystHistoryPoint[]>(key);
  const history = Array.isArray(cached?.value) ? cached!.value : [];
  const today = new Date().toISOString().slice(0, 10);
  const next = history.filter((item) => item.date !== today);
  next.push({ date: today, analystScore, upsidePct });
  next.sort((a, b) => a.date.localeCompare(b.date));
  await writeSourceCache(key, next.slice(-120));
}

export async function estimateRevisionDelta30d(ticker: string, currentUpsidePct: number | null): Promise<number | null> {
  if (currentUpsidePct === null) return null;
  const key = historyKey(ticker);
  const cached = await readSourceCache<AnalystHistoryPoint[]>(key);
  const history = Array.isArray(cached?.value) ? cached!.value : [];
  const today = new Date().toISOString().slice(0, 10);
  const threshold = Date.now() - 30 * 24 * 60 * 60 * 1000;
  const candidate = history.find((item) => {
    const ts = new Date(item.date).getTime();
    return item.date < today && Number.isFinite(ts) && ts >= threshold && item.upsidePct !== null;
  });
  if (!candidate || candidate.upsidePct === null) return null;
  return Number((currentUpsidePct - candidate.upsidePct).toFixed(2));
}

