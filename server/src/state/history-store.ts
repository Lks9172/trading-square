import fs from 'fs/promises';
import path from 'path';
import { fetchFredHistoryFrom, FRED_SERIES } from '../collectors/fred';
import { fetchYahooHistoryYears, YAHOO_SYMBOLS } from '../collectors/yahoo';
import { classifyRegime } from '../engines/regime';
import { computeSignals } from '../engines/signals';
import { computeAllocation } from '../engines/allocation';
import { DEFAULT_PROFILE } from './cache';
import { DerivedIndicator, MarketDataPoint } from '../types/indicators';

const DATA_DIR = path.resolve(process.cwd(), 'data');
const HISTORY_DIR = path.join(DATA_DIR, 'history');

const GUARANTEE = {
  FRED_YEARS: 10,
  YAHOO_YEARS: 5,
};

type HistoryPoint = { date: string; value: number };

function yearsAgo(years: number) {
  const d = new Date();
  d.setFullYear(d.getFullYear() - years);
  return d.toISOString().split('T')[0];
}

async function ensureDir() {
  await fs.mkdir(HISTORY_DIR, { recursive: true });
}

function filePath(source: string, key: string) {
  return path.join(HISTORY_DIR, `${source.toLowerCase()}-${key.toLowerCase()}.json`);
}

async function writeHistory(source: string, key: string, points: HistoryPoint[]) {
  await ensureDir();
  await fs.writeFile(filePath(source, key), JSON.stringify(points));
}

function dateToTime(date: string) {
  return new Date(`${date}T00:00:00Z`).getTime();
}

function latestAtOrBefore(points: HistoryPoint[], date: string): number | null {
  const target = dateToTime(date);
  let latest: number | null = null;
  for (const p of points) {
    if (dateToTime(p.date) <= target) latest = p.value;
    else break;
  }
  return latest;
}

function buildRawForDate(date: string, histories: Record<string, HistoryPoint[]>): Record<string, MarketDataPoint> {
  const map: Record<string, MarketDataPoint> = {};
  const defs: Array<[string, string, MarketDataPoint['source']]> = [
    ['DGS10', 'fred', 'FRED'], ['T10YIE', 'fred', 'FRED'], ['T10Y2Y', 'fred', 'FRED'], ['VIXCLS', 'fred', 'FRED'],
    ['BAMLH0A0HYM2', 'fred', 'FRED'], ['STLFSI4', 'fred', 'FRED'], ['ICSA', 'fred', 'FRED'], ['UNRATE', 'fred', 'FRED'],
    ['NASDAQ', 'yahoo', 'YAHOO'], ['GOLD', 'yahoo', 'YAHOO'], ['SILVER', 'yahoo', 'YAHOO'], ['COPPER', 'yahoo', 'YAHOO'],
    ['DXY', 'yahoo', 'YAHOO'], ['SP500', 'yahoo', 'YAHOO'], ['WTI', 'yahoo', 'YAHOO'], ['USDKRW', 'yahoo', 'YAHOO'],
  ];

  for (const [key, source, srcType] of defs) {
    const arr = histories[`${source}:${key}`] || [];
    const value = latestAtOrBefore(arr, date);
    if (value !== null) {
      map[key] = { code: key, value, date, source: srcType };
    }
  }

  return map;
}

function buildDerivedForDate(date: string, raw: Record<string, MarketDataPoint>, nasdaqHistory: HistoryPoint[]): Record<string, DerivedIndicator> {
  const derived: Record<string, DerivedIndicator> = {};
  const dgs10 = raw.DGS10?.value;
  const t10yie = raw.T10YIE?.value;
  if (dgs10 !== undefined && t10yie !== undefined) {
    derived.REAL_YIELD = { name: 'real_yield', value: dgs10 - t10yie, date, formula: 'DGS10 - T10YIE' };
  }

  const gold = raw.GOLD?.value;
  const silver = raw.SILVER?.value;
  const copper = raw.COPPER?.value;
  if (gold !== undefined && silver) {
    derived.GOLD_SILVER_RATIO = { name: 'gold_silver_ratio', value: gold / silver, date, formula: 'GOLD / SILVER' };
  }
  if (gold && copper !== undefined) {
    derived.COPPER_GOLD_RATIO = { name: 'copper_gold_ratio', value: copper / gold, date, formula: 'COPPER / GOLD' };
  }

  const eligible = nasdaqHistory.filter((p) => dateToTime(p.date) <= dateToTime(date));
  if (eligible.length >= 200) {
    const latest200 = eligible.slice(-200);
    const sma200 = latest200.reduce((sum, p) => sum + p.value, 0) / latest200.length;
    const current = eligible[eligible.length - 1].value;
    const allTimeHigh = Math.max(...eligible.map((p) => p.value));
    derived.NASDAQ_SMA200 = { name: 'nasdaq_sma200', value: sma200, date, formula: 'SMA200' };
    derived.NASDAQ_DISPARITY = { name: 'nasdaq_disparity_200', value: ((current - sma200) / sma200) * 100, date, formula: '(P-SMA)/SMA*100' };
    derived.NASDAQ_DRAWDOWN = { name: 'nasdaq_drawdown', value: ((current - allTimeHigh) / allTimeHigh) * 100, date, formula: '(P-ATH)/ATH*100' };
    derived.NASDAQ_ABOVE_200DMA = { name: 'nasdaq_above_200dma', value: current > sma200 ? 1 : 0, date, formula: 'P>SMA200' };
  }

  return derived;
}

function signalValue(signal: string) {
  if (signal === 'STRONG_BUY') return 100;
  if (signal === 'BUY') return 75;
  if (signal === 'HOLD') return 50;
  if (signal === 'REDUCE') return 25;
  return 0;
}

export async function refreshComputedHistories() {
  const histories: Record<string, HistoryPoint[]> = {};
  const keys = ['NASDAQ', 'GOLD', 'SILVER', 'COPPER', 'SP500', 'WTI', 'USDKRW', 'DXY', 'DGS10', 'T10YIE', 'T10Y2Y', 'VIXCLS', 'BAMLH0A0HYM2', 'STLFSI4', 'ICSA', 'UNRATE'];
  for (const key of keys) {
    if (['NASDAQ', 'GOLD', 'SILVER', 'COPPER', 'SP500', 'WTI', 'USDKRW', 'DXY'].includes(key)) histories[`yahoo:${key}`] = await readHistory('yahoo', key);
    else histories[`fred:${key}`] = await readHistory('fred', key);
  }

  const base = histories['yahoo:NASDAQ'] || [];
  const computed: Record<string, HistoryPoint[]> = {
    REGIME: [],
    PORTFOLIO: [],
    NASDAQ: [],
    GOLD: [],
    SILVER: [],
    COPPER: [],
  };

  for (const anchor of base) {
    const raw = buildRawForDate(anchor.date, histories);
    const derived = buildDerivedForDate(anchor.date, raw, base);
    const regime = classifyRegime({ raw, derived, manualInputs: DEFAULT_PROFILE.manualInputs });
    const signals = computeSignals(raw, derived, regime, DEFAULT_PROFILE);
    const allocation = computeAllocation(regime.regime, regime.score, signals, derived, raw);
    const byAsset = Object.fromEntries(signals.map((s) => [s.asset, s]));

    computed.NASDAQ.push({ date: anchor.date, value: signalValue(byAsset['NASDAQ']?.signal || 'HOLD') });
    computed.GOLD.push({ date: anchor.date, value: signalValue(byAsset['GOLD']?.signal || 'HOLD') });
    computed.SILVER.push({ date: anchor.date, value: signalValue(byAsset['SILVER']?.signal || 'HOLD') });
    computed.COPPER.push({ date: anchor.date, value: signalValue(byAsset['COPPER']?.signal || 'HOLD') });
    computed.REGIME.push({ date: anchor.date, value: regime.score });
    computed.PORTFOLIO.push({ date: anchor.date, value: 100 - allocation.allocations.cash });
  }

  await writeHistory('signal', 'REGIME', computed.REGIME);
  await writeHistory('signal', 'PORTFOLIO', computed.PORTFOLIO);
  await writeHistory('signal', 'NASDAQ', computed.NASDAQ);
  await writeHistory('signal', 'GOLD', computed.GOLD);
  await writeHistory('signal', 'SILVER', computed.SILVER);
  await writeHistory('signal', 'COPPER', computed.COPPER);
}

export async function readHistory(source: string, key: string): Promise<HistoryPoint[]> {
  try {
    const content = await fs.readFile(filePath(source, key), 'utf-8');
    return JSON.parse(content);
  } catch {
    return [];
  }
}

export async function backfillFred(apiKey: string, onlyKeys?: string[]) {
  const start = yearsAgo(GUARANTEE.FRED_YEARS);
  const entries = Object.entries(FRED_SERIES).filter(
    ([key]) => !onlyKeys || onlyKeys.includes(key)
  );
  if (entries.length === 0) return;
  const settled = await Promise.allSettled(
    entries.map(([, seriesId]) => fetchFredHistoryFrom(seriesId, apiKey, start))
  );

  for (let i = 0; i < entries.length; i += 1) {
    const [key] = entries[i];
    const result = settled[i];
    if (result.status === 'fulfilled') {
      await writeHistory('fred', key, result.value.map((p) => ({ date: p.date, value: p.value })));
    }
  }
}

export async function backfillYahoo(onlyKeys?: string[]) {
  const entries = Object.entries(YAHOO_SYMBOLS).filter(
    ([key]) => !onlyKeys || onlyKeys.includes(key)
  );
  if (entries.length === 0) return;
  const settled = await Promise.allSettled(
    entries.map(([, symbol]) => fetchYahooHistoryYears(symbol, GUARANTEE.YAHOO_YEARS))
  );

  for (let i = 0; i < entries.length; i += 1) {
    const [key] = entries[i];
    const result = settled[i];
    if (result.status === 'fulfilled') {
      await writeHistory('yahoo', key, result.value.map((p) => ({ date: p.date, value: p.close })));
    }
  }
}

/** 파일이 아예 없거나, 보장 범위 대비 포인트 수가 현저히 부족하면 재백필 대상으로 간주. */
async function identifyMissingOrShallow(
  source: 'fred' | 'yahoo',
  seriesKeys: string[],
  files: string[],
  minPoints: number,
): Promise<string[]> {
  const missing: string[] = [];
  for (const key of seriesKeys) {
    const filename = `${source}-${key.toLowerCase()}.json`;
    if (!files.includes(filename)) {
      missing.push(key);
      continue;
    }
    try {
      const raw = await fs.readFile(path.join(HISTORY_DIR, filename), 'utf-8');
      const points = JSON.parse(raw) as HistoryPoint[];
      if (points.length < minPoints) missing.push(key);
    } catch {
      missing.push(key);
    }
  }
  return missing;
}

export async function ensureBackfill(apiKey: string) {
  await ensureDir();
  const files = await fs.readdir(HISTORY_DIR).catch(() => [] as string[]);

  // 개별 시리즈 단위로 누락/부족 여부 검사.
  // FRED: 월간/주간 시리즈 섞여있어 하한을 12포인트(=최소 YoY 계산 가능)로 둠.
  // Yahoo: 일간 시리즈. 영업일 기준 120일 이상이면 정상 수집으로 간주.
  const fredMissing = await identifyMissingOrShallow(
    'fred',
    Object.keys(FRED_SERIES),
    files,
    12,
  );
  const yahooMissing = await identifyMissingOrShallow(
    'yahoo',
    Object.keys(YAHOO_SYMBOLS),
    files,
    120,
  );
  const signalMissing = !files.some((f) => f.startsWith('signal-'));

  if (fredMissing.length > 0) {
    console.log(`[backfill] FRED re-filling: ${fredMissing.join(', ')}`);
    await backfillFred(apiKey, fredMissing);
  }
  if (yahooMissing.length > 0) {
    console.log(`[backfill] Yahoo re-filling: ${yahooMissing.join(', ')}`);
    await backfillYahoo(yahooMissing);
  }
  if (fredMissing.length || yahooMissing.length || signalMissing) {
    await refreshComputedHistories();
  }
}

export async function coverage() {
  await ensureDir();
  const files = await fs.readdir(HISTORY_DIR).catch(() => [] as string[]);

  const result: Record<string, { count: number; oldest: string; newest: string; guaranteedYears: number }> = {};

  for (const file of files) {
    const content = await fs.readFile(path.join(HISTORY_DIR, file), 'utf-8');
    const points: HistoryPoint[] = JSON.parse(content);
    const name = file.replace('.json', '').toUpperCase();
    result[name] = {
      count: points.length,
      oldest: points[0]?.date || '',
      newest: points[points.length - 1]?.date || '',
        guaranteedYears: file.startsWith('fred-') ? GUARANTEE.FRED_YEARS : file.startsWith('yahoo-') ? GUARANTEE.YAHOO_YEARS : GUARANTEE.YAHOO_YEARS,
    };
  }

  return result;
}

export async function appendDailyData(apiKey: string) {
  const today = new Date().toISOString().split('T')[0];

  const fredEntries = Object.entries(FRED_SERIES);
  for (const [key, seriesId] of fredEntries) {
    try {
      const existing = await readHistory('fred', key);
      const lastDate = existing.length > 0 ? existing[existing.length - 1].date : '';
      if (lastDate >= today) continue;

      const recent = await fetchFredHistoryFrom(seriesId, apiKey, lastDate || yearsAgo(1));
      const newPoints = recent
        .filter((p) => p.date > lastDate)
        .map((p) => ({ date: p.date, value: p.value }));

      if (newPoints.length > 0) {
        await writeHistory('fred', key, [...existing, ...newPoints]);
      }
    } catch {
      void 0;
    }
  }

  const yahooEntries = Object.entries(YAHOO_SYMBOLS);
  for (const [key, symbol] of yahooEntries) {
    try {
      const existing = await readHistory('yahoo', key);
      const lastDate = existing.length > 0 ? existing[existing.length - 1].date : '';
      if (lastDate >= today) continue;

      const recent = await fetchYahooHistoryYears(symbol, 0.1);
      const newPoints = recent
        .filter((p) => p.date > lastDate)
        .map((p) => ({ date: p.date, value: p.close }));

      if (newPoints.length > 0) {
        await writeHistory('yahoo', key, [...existing, ...newPoints]);
      }
    } catch {
      void 0;
    }
  }
}

export const HISTORY_GUARANTEE = GUARANTEE;
