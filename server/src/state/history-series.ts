import { readHistory } from './history-store';

type RangeKey = '1D' | '1W' | '1M' | '1Y' | '5Y';
type IntervalKey = '1D' | '1W' | '1M';
type HistoryPoint = { date: string; value: number };

const RANGE_DAYS: Record<RangeKey, number> = {
  '1D': 1,
  '1W': 7,
  '1M': 31,
  '1Y': 366,
  '5Y': 1826,
};

const INTERVAL_STEP: Record<IntervalKey, number> = {
  '1D': 1,
  '1W': 5,
  '1M': 21,
};

function dateToTime(date: string) {
  return new Date(`${date}T00:00:00Z`).getTime();
}

function filterRange(points: HistoryPoint[], range: RangeKey) {
  const cutoff = Date.now() - RANGE_DAYS[range] * 86400 * 1000;
  return points.filter((p) => dateToTime(p.date) >= cutoff);
}

function downsample(points: HistoryPoint[], interval: IntervalKey) {
  const step = INTERVAL_STEP[interval];
  return points.filter((_, idx) => idx % step === 0 || idx === points.length - 1);
}

export async function getHistorySeries(keys: string[], range: RangeKey, interval: IntervalKey) {
  const output: Record<string, HistoryPoint[]> = {};

  for (const key of keys) {
    const [source, name] = key.split(':');
    if (!source || !name) continue;
    const points = await readHistory(source.toLowerCase(), name);
    output[key] = downsample(filterRange(points, range), interval);
  }

  return output;
}
