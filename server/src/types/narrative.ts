export type NarrativeStage = 'EARLY' | 'MID' | 'OVERHEATED';

export interface NarrativeExternalQueryConfig {
  youtubeQuery?: string;
  newsQuery?: string;
}

export interface NarrativeThemeDefinition {
  id: string;
  title: string;
  description: string;
  proxies: string[];
  externalQueries?: NarrativeExternalQueryConfig;
}

export interface NarrativeExternalSignal {
  key: string;
  label: string;
  value: number | null;
  score: number;
  detail: string;
}

export interface NarrativeHistoryPoint {
  date: string;
  heatScore: number;
}

export interface NarrativeThemeState {
  theme: NarrativeThemeDefinition;
  generatedAt: string;
  stage: NarrativeStage;
  trend?: 'HEATING' | 'COOLING' | 'STABLE';
  heatScore: number;
  heatDelta7d?: number | null;
  heatDelta30d?: number | null;
  drivers: string[];
  risks: string[];
  proxyScores: Array<{
    key: string;
    label: string;
    score: number;
    detail: string;
  }>;
  externalSignals: NarrativeExternalSignal[];
  heatHistory?: NarrativeHistoryPoint[];
}
