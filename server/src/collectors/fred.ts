import axios from 'axios';
import { MarketDataPoint } from '../types/indicators';

const FRED_BASE = 'https://api.stlouisfed.org/fred/series/observations';

const FRED_SERIES: Record<string, string> = {
  DGS10: 'DGS10',
  T10YIE: 'T10YIE',
  T10Y2Y: 'T10Y2Y',
  VIXCLS: 'VIXCLS',
  BAMLH0A0HYM2: 'BAMLH0A0HYM2',
  STLFSI4: 'STLFSI4',
  WALCL: 'WALCL',
  WRESBAL: 'WRESBAL',
  RRPONTSYD: 'RRPONTSYD',
  WTREGEN: 'WTREGEN',
  WRMFNS: 'WRMFNS',
  M2SL: 'M2SL',
  UNRATE: 'UNRATE',
  ICSA: 'ICSA',
  SOFR: 'SOFR',
  EFFR: 'EFFR',
};

function getDateRange(): { start: string; end: string } {
  const end = new Date();
  const start = new Date();
  start.setFullYear(start.getFullYear() - 3);
  return {
    start: start.toISOString().split('T')[0],
    end: end.toISOString().split('T')[0],
  };
}

async function fetchSeries(seriesId: string, apiKey: string): Promise<MarketDataPoint[]> {
  const { start, end } = getDateRange();
  const url = `${FRED_BASE}?series_id=${seriesId}&api_key=${apiKey}&file_type=json&observation_start=${start}&observation_end=${end}&sort_order=desc&limit=10`;

  const { data } = await axios.get(url);
  const observations = data.observations || [];

  return observations
    .filter((obs: any) => obs.value !== '.')
    .map((obs: any) => ({
      code: seriesId,
      value: parseFloat(obs.value),
      date: obs.date,
      source: 'FRED' as const,
    }));
}

export async function fetchAllFred(apiKey: string): Promise<Record<string, MarketDataPoint>> {
  const results: Record<string, MarketDataPoint> = {};
  const entries = Object.entries(FRED_SERIES);

  const settled = await Promise.allSettled(
    entries.map(([key, seriesId]) => fetchSeries(seriesId, apiKey))
  );

  entries.forEach(([key], i) => {
    const result = settled[i];
    if (result.status === 'fulfilled' && result.value.length > 0) {
      results[key] = result.value[0];
    }
  });

  return results;
}

export async function fetchFredHistory(
  seriesId: string,
  apiKey: string,
  limit = 200
): Promise<MarketDataPoint[]> {
  const { start, end } = getDateRange();
  const url = `${FRED_BASE}?series_id=${seriesId}&api_key=${apiKey}&file_type=json&observation_start=${start}&observation_end=${end}&sort_order=desc&limit=${limit}`;

  const { data } = await axios.get(url);
  return (data.observations || [])
    .filter((obs: any) => obs.value !== '.')
    .map((obs: any) => ({
      code: seriesId,
      value: parseFloat(obs.value),
      date: obs.date,
      source: 'FRED' as const,
    }));
}
