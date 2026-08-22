import { readSourceCache, writeSourceCache } from './source-cache';
import { NarrativeHistoryPoint, NarrativeThemeState } from '../types/narrative';

function historyKey(themeId: string) {
  return `narrative-history-${themeId}`;
}

export async function getNarrativeHistory(themeId: string): Promise<NarrativeHistoryPoint[]> {
  const cached = await readSourceCache<NarrativeHistoryPoint[]>(historyKey(themeId));
  const history = Array.isArray(cached?.value) ? cached!.value : [];
  return history.slice().sort((a, b) => a.date.localeCompare(b.date));
}

export async function recordNarrativeState(state: NarrativeThemeState): Promise<void> {
  const history = await getNarrativeHistory(state.theme.id);
  const today = state.generatedAt.slice(0, 10);
  const next = history.filter((item) => item.date !== today);
  next.push({ date: today, heatScore: state.heatScore });
  next.sort((a, b) => a.date.localeCompare(b.date));
  await writeSourceCache(historyKey(state.theme.id), next.slice(-365));
}

function closest(history: NarrativeHistoryPoint[], days: number): NarrativeHistoryPoint | null {
  const today = new Date().toISOString().slice(0, 10);
  const target = Date.now() - days * 24 * 60 * 60 * 1000;
  let best: { point: NarrativeHistoryPoint; distance: number } | null = null;
  for (const point of history) {
    if (point.date >= today) continue;
    const ts = new Date(point.date).getTime();
    if (!Number.isFinite(ts)) continue;
    const distance = Math.abs(ts - target);
    if (!best || distance < best.distance) best = { point, distance };
  }
  return best?.point ?? null;
}

export async function enrichNarrativeTrend(state: NarrativeThemeState): Promise<NarrativeThemeState> {
  const history = await getNarrativeHistory(state.theme.id);
  const point7 = closest(history, 7);
  const point30 = closest(history, 30);
  const heatDelta7d = point7 ? state.heatScore - point7.heatScore : null;
  const heatDelta30d = point30 ? state.heatScore - point30.heatScore : null;
  const trend = heatDelta7d === null ? 'STABLE' : heatDelta7d >= 6 ? 'HEATING' : heatDelta7d <= -6 ? 'COOLING' : 'STABLE';
  return {
    ...state,
    trend,
    heatDelta7d: heatDelta7d === null ? null : Number(heatDelta7d.toFixed(1)),
    heatDelta30d: heatDelta30d === null ? null : Number(heatDelta30d.toFixed(1)),
    heatHistory: [...history.slice(-11), { date: state.generatedAt.slice(0, 10), heatScore: state.heatScore }]
      .filter((item, index, arr) => arr.findIndex((candidate) => candidate.date === item.date) === index)
      .slice(-12),
  };
}
