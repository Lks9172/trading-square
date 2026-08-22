import { DEFAULT_PROFILE, getSnapshot } from '../state/cache';
import { fetchNarrativeExternalSignals } from '../collectors/trends/external-proxies';
import { computeNarrativeThemeState } from '../engines/narrative/heat-score';
import { getNarrativeThemeById, listNarrativeThemes } from '../engines/narrative/theme-map';
import { SystemSnapshot } from '../types/indicators';
import { NarrativeThemeState } from '../types/narrative';
import { enrichNarrativeTrend, recordNarrativeState } from './narrative-history';

export async function buildNarrativeTheme(themeId: string): Promise<NarrativeThemeState> {
  const theme = getNarrativeThemeById(themeId);
  if (!theme) throw new Error('narrative theme not found');
  const [snapshot, externalSignals] = await Promise.all([
    getSnapshot(DEFAULT_PROFILE),
    fetchNarrativeExternalSignals(theme),
  ]);
  const state = computeNarrativeThemeState(theme, snapshot, externalSignals);
  const enriched = await enrichNarrativeTrend(state);
  await recordNarrativeState(enriched);
  return enriched;
}

export async function buildNarrativeThemesForSnapshot(snapshot: SystemSnapshot): Promise<NarrativeThemeState[]> {
  const themes = listNarrativeThemes();
  const externalSignalsList = await Promise.all(themes.map((theme) => fetchNarrativeExternalSignals(theme)));
  const states = themes.map((theme, index) => computeNarrativeThemeState(theme, snapshot, externalSignalsList[index]));
  const enriched = await Promise.all(states.map((state) => enrichNarrativeTrend(state)));
  await Promise.all(enriched.map((state) => recordNarrativeState(state)));
  return enriched;
}

export async function buildAllNarrativeThemes(): Promise<NarrativeThemeState[]> {
  const snapshot = await getSnapshot(DEFAULT_PROFILE);
  return buildNarrativeThemesForSnapshot(snapshot);
}

export { listNarrativeThemes };
