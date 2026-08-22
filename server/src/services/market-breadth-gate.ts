import axios from 'axios';
import {
  MarketBreadthGateHistoryRow,
  MarketBreadthGateMarket,
  MarketBreadthGateSnapshot,
} from '../types/indicators';
import { readSourceCacheWithin, writeSourceCache } from './source-cache';
import { childLogger, serializeError } from './logger';

type Series = Array<number | null>;
type PricePoint = { date: string; close: number };

type RegimeFilter = 'slopepos_only' | 'above200_or_slopepos';

type GateConfig = {
  asset: 'NASDAQ' | 'SP500';
  label: string;
  priceSymbol: 'QQQ' | 'SPY';
  universe: 'nasdaq100' | 'sp500';
  shortThreshold: number;
  mediumOversoldThreshold: number;
  mediumRecoveryFloor: number;
  lookbackDays: number;
  regimeFilter: RegimeFilter;
  activeWindowDays: number;
  recentWindowDays: number;
  mode: '실전 개선형';
};

const CACHE_KEY = 'market-breadth-gate-v1';
const CACHE_TTL_MS = 6 * 60 * 60 * 1000;
const log = childLogger({ module: 'service.market-breadth-gate' });

const NASDAQ_100_SYMBOLS = ['ADBE','AMD','ABNB','ALNY','GOOGL','GOOG','AMZN','AEP','AMGN','ADI','AAPL','AMAT','APP','ARM','ASML','ADSK','ADP','AXON','BKR','BKNG','AVGO','CDNS','CHTR','CTAS','CSCO','CCEP','CTSH','CMCSA','CEG','CPRT','COST','CRWD','CSX','DDOG','DXCM','FANG','DASH','EA','EXC','FAST','FER','FTNT','GEHC','GILD','HON','IDXX','INSM','INTC','INTU','ISRG','KDP','KLAC','KHC','LRCX','LIN','LITE','MAR','MRVL','MELI','META','MCHP','MU','MSFT','MSTR','MDLZ','MPWR','MNST','NFLX','NVDA','NXPI','ORLY','ODFL','PCAR','PLTR','PANW','PAYX','PYPL','PDD','PEP','QCOM','REGN','ROP','ROST','SNDK','STX','SHOP','SBUX','SNPS','TMUS','TTWO','TSLA','TXN','TRI','VRSK','VRTX','WMT','WBD','WDC','WDAY','XEL','ZS'];

const GATE_CONFIGS: GateConfig[] = [
  {
    asset: 'NASDAQ',
    label: '나스닥 실전형 게이트',
    priceSymbol: 'QQQ',
    universe: 'nasdaq100',
    shortThreshold: 22.5,
    mediumOversoldThreshold: 42.5,
    mediumRecoveryFloor: 30,
    lookbackDays: 15,
    regimeFilter: 'slopepos_only',
    activeWindowDays: 90,
    recentWindowDays: 180,
    mode: '실전 개선형',
  },
  {
    asset: 'SP500',
    label: 'S&P 500 실전형 게이트',
    priceSymbol: 'SPY',
    universe: 'sp500',
    shortThreshold: 22.5,
    mediumOversoldThreshold: 42.5,
    mediumRecoveryFloor: 25,
    lookbackDays: 15,
    regimeFilter: 'above200_or_slopepos',
    activeWindowDays: 90,
    recentWindowDays: 180,
    mode: '실전 개선형',
  },
];

let inFlight: Promise<MarketBreadthGateSnapshot> | null = null;

function sma(arr: Series, n: number): Series {
  const out: Series = Array(arr.length).fill(null);
  let sum = 0;
  let cnt = 0;
  for (let i = 0; i < arr.length; i += 1) {
    const v = arr[i];
    if (v != null) { sum += v; cnt += 1; }
    if (i >= n) {
      const old = arr[i - n];
      if (old != null) { sum -= old; cnt -= 1; }
    }
    if (i >= n - 1 && cnt === n) out[i] = sum / n;
  }
  return out;
}

async function fetchSpark(symbols: string[], range = '6y') {
  const { data } = await axios.get(
    `https://query1.finance.yahoo.com/v7/finance/spark?symbols=${encodeURIComponent(symbols.join(','))}&range=${range}&interval=1d`,
    { headers: { 'User-Agent': 'Mozilla/5.0' }, timeout: 30000 },
  );
  return data.spark.result || [];
}

async function getSp500Symbols() {
  const { data } = await axios.get('https://raw.githubusercontent.com/datasets/s-and-p-500-companies/master/data/constituents.csv', { timeout: 20000 });
  return data.trim().split(/\r?\n/).slice(1).map((line: string) => line.split(',')[0].replace('.', '-'));
}

async function buildBreadth(symbols: string[]) {
  const dateSet = new Set<string>();
  const series: Record<string, Array<{ date: string; close: number }>> = {};

  for (let i = 0; i < symbols.length; i += 20) {
    const batch = symbols.slice(i, i + 20);
    const res = await fetchSpark(batch, '6y');
    for (const item of res) {
      const r = item.response?.[0];
      const closes = r?.indicators?.quote?.[0]?.close;
      if (!r?.timestamp || !closes) continue;
      const arr: Array<{ date: string; close: number }> = [];
      for (let j = 0; j < r.timestamp.length; j += 1) {
        const c = closes[j];
        if (typeof c !== 'number' || !Number.isFinite(c) || c <= 0) continue;
        const d = new Date(r.timestamp[j] * 1000).toISOString().slice(0, 10);
        dateSet.add(d);
        arr.push({ date: d, close: c });
      }
      if (arr.length > 500) series[item.symbol] = arr;
    }
  }

  const dates = Array.from(dateSet).sort().filter((d) => d >= '2021-06-27');
  const idx = new Map(dates.map((d, i) => [d, i] as const));
  const raw50 = Array(dates.length).fill(0);
  const raw100 = Array(dates.length).fill(0);

  for (const arr of Object.values(series)) {
    const closeArr: Series = Array(dates.length).fill(null);
    for (const p of arr) {
      const i = idx.get(p.date);
      if (i != null) closeArr[i] = p.close;
    }
    const ma50 = sma(closeArr, 50);
    const ma100 = sma(closeArr, 100);
    for (let i = 0; i < dates.length; i += 1) {
      const c = closeArr[i];
      if (c != null && ma50[i] != null) raw50[i] += 10000 + (c > ma50[i]! ? 1 : 0);
      if (c != null && ma100[i] != null) raw100[i] += 10000 + (c > ma100[i]! ? 1 : 0);
    }
  }

  const breadth50 = raw50.map((v) => {
    const cnt = Math.floor(v / 10000);
    const ab = v % 10000;
    return cnt ? (ab / cnt) * 100 : null;
  });
  const breadth100 = raw100.map((v) => {
    const cnt = Math.floor(v / 10000);
    const ab = v % 10000;
    return cnt ? (ab / cnt) * 100 : null;
  });

  return { dates, breadth50, breadth100 };
}

async function fetchPriceSeries(symbol: 'QQQ' | 'SPY'): Promise<PricePoint[]> {
  const r = (await fetchSpark([symbol], '6y'))[0]?.response?.[0];
  const q = r?.indicators?.quote?.[0];
  const out: PricePoint[] = [];
  if (r?.timestamp && q?.close) {
    for (let i = 0; i < r.timestamp.length; i += 1) {
      const close = q.close[i];
      if (typeof close !== 'number' || close <= 0) continue;
      out.push({ date: new Date(r.timestamp[i] * 1000).toISOString().slice(0, 10), close });
    }
  }
  return out.filter((x) => x.date >= '2021-06-27');
}

function minInWindow(arr: Series, endIdx: number, lookback: number): number | null {
  const start = Math.max(0, endIdx - lookback + 1);
  let m: number | null = null;
  for (let i = start; i <= endIdx; i += 1) {
    const v = arr[i];
    if (v == null) continue;
    m = m == null ? v : Math.min(m, v);
  }
  return m;
}

function dedupeGap(dates: string[], minGapDays = 45): string[] {
  const out: string[] = [];
  let last = -1e18;
  for (const d of dates) {
    const t = Date.parse(`${d}T00:00:00Z`);
    if (!out.length || (t - last) / 86400000 >= minGapDays) {
      out.push(d);
      last = t;
    }
  }
  return out;
}

function addMonths(dateStr: string, months: number): string {
  const d = new Date(`${dateStr}T00:00:00Z`);
  const day = d.getUTCDate();
  d.setUTCMonth(d.getUTCMonth() + months);
  if (d.getUTCDate() !== day) d.setUTCDate(0);
  return d.toISOString().slice(0, 10);
}

function lookupOnOrAfter(series: PricePoint[], target: string): PricePoint | null {
  let lo = 0;
  let hi = series.length - 1;
  let ans = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (series[mid].date >= target) {
      ans = mid;
      hi = mid - 1;
    } else {
      lo = mid + 1;
    }
  }
  return ans === -1 ? null : series[ans];
}

function daysBetween(date: string, baseDate: string): number {
  return Math.floor((Date.parse(`${baseDate}T00:00:00Z`) - Date.parse(`${date}T00:00:00Z`)) / 86400000);
}

function round(value: number | null): number | null {
  return value == null ? null : +value.toFixed(2);
}

function buildHistoryRows(dates: string[], priceSeries: PricePoint[]): MarketBreadthGateHistoryRow[] {
  const exact = new Map(priceSeries.map((point) => [point.date, point.close] as const));
  return dates.map((date) => {
    const entry = exact.get(date);
    const calc = (months: number) => {
      if (entry == null) return null;
      const next = lookupOnOrAfter(priceSeries, addMonths(date, months));
      return next ? round(((next.close / entry) - 1) * 100) : null;
    };
    return {
      signalDate: date,
      oneMonthReturn: calc(1),
      twoMonthReturn: calc(2),
      threeMonthReturn: calc(3),
    };
  });
}

function summarizeReturns(rows: MarketBreadthGateHistoryRow[]) {
  const pick = (key: keyof Pick<MarketBreadthGateHistoryRow, 'oneMonthReturn' | 'twoMonthReturn' | 'threeMonthReturn'>) =>
    rows.map((row) => row[key]).filter((value): value is number => typeof value === 'number');
  const avg = (values: number[]) => values.length ? round(values.reduce((sum, value) => sum + value, 0) / values.length) : null;
  const win = (values: number[]) => values.length ? round((values.filter((value) => value > 0).length / values.length) * 100) : null;
  return {
    avg1m: avg(pick('oneMonthReturn')),
    avg2m: avg(pick('twoMonthReturn')),
    avg3m: avg(pick('threeMonthReturn')),
    win1m: win(pick('oneMonthReturn')),
    win2m: win(pick('twoMonthReturn')),
    win3m: win(pick('threeMonthReturn')),
  };
}

function buildSignalDates(
  config: GateConfig,
  breadthDates: string[],
  breadth50: Series,
  breadth100: Series,
  priceDates: string[],
  priceClose: Series,
): string[] {
  const ma200 = sma(priceClose, 200);
  const hits: string[] = [];
  const priceIndex = new Map(priceDates.map((d, i) => [d, i] as const));

  for (let i = 1; i < breadthDates.length; i += 1) {
    const p50 = breadth50[i - 1];
    const n50 = breadth50[i];
    const n100 = breadth100[i];
    if ([p50, n50, n100].some((value) => value == null)) continue;
    const c50 = p50! < config.shortThreshold && n50! >= config.shortThreshold;
    if (!c50) continue;

    const min50 = minInWindow(breadth50, i, config.lookbackDays);
    const min100 = minInWindow(breadth100, i, config.lookbackDays);
    const oversold = (min50 != null && min50 < config.shortThreshold)
      && (min100 != null && min100 < config.mediumOversoldThreshold);
    if (!oversold || n100! < config.mediumRecoveryFloor) continue;

    const date = breadthDates[i];
    const priceIdx = priceIndex.get(date);
    if (priceIdx == null) continue;
    const close = priceClose[priceIdx];
    const above200 = close != null && ma200[priceIdx] != null && close > ma200[priceIdx]!;
    const slopePositive = priceIdx >= 20
      && ma200[priceIdx] != null
      && ma200[priceIdx - 20] != null
      && ma200[priceIdx]! > ma200[priceIdx - 20]!;

    const regimeOk = config.regimeFilter === 'slopepos_only'
      ? slopePositive
      : (above200 || slopePositive);
    if (!regimeOk) continue;

    hits.push(date);
  }

  return dedupeGap(hits, 45);
}

function buildStatus(config: GateConfig, signalDate: string | null, today: string): Pick<MarketBreadthGateMarket, 'status' | 'active' | 'daysSinceSignal'> {
  if (!signalDate) return { status: 'OFF', active: false, daysSinceSignal: null };
  const daysSinceSignal = daysBetween(signalDate, today);
  if (daysSinceSignal <= config.activeWindowDays) return { status: 'ON', active: true, daysSinceSignal };
  if (daysSinceSignal <= config.recentWindowDays) return { status: 'RECENT', active: false, daysSinceSignal };
  return { status: 'OFF', active: false, daysSinceSignal };
}

function buildSummaryLabel(market: MarketBreadthGateMarket): string {
  if (!market.signalDate) return `${market.label}은 최근 5년 기준 유효 신호가 없습니다.`;
  if (market.status === 'ON') {
    return `${market.label} ON — 하락장 억제 필터를 통과한 중기 반전 구간입니다. 최근 ${market.daysSinceSignal}일 전 신호입니다.`;
  }
  if (market.status === 'RECENT') {
    return `${market.label}는 최근 실전형 신호가 있었지만 지금은 초기 ON 구간을 지난 상태입니다. 마지막 신호는 ${market.daysSinceSignal}일 전입니다.`;
  }
  return `${market.label}는 현재 OFF 입니다. 마지막 유효 신호는 ${market.daysSinceSignal}일 전입니다.`;
}

async function buildMarket(config: GateConfig, sp500Symbols: string[]): Promise<MarketBreadthGateMarket> {
  const symbols = config.universe === 'nasdaq100' ? NASDAQ_100_SYMBOLS : sp500Symbols;
  const [{ dates, breadth50, breadth100 }, priceSeries] = await Promise.all([
    buildBreadth(symbols),
    fetchPriceSeries(config.priceSymbol),
  ]);
  const priceDates = priceSeries.map((point) => point.date);
  const priceClose: Series = priceSeries.map((point) => point.close);
  const signalDates = buildSignalDates(config, dates, breadth50, breadth100, priceDates, priceClose);
  const historyRows = buildHistoryRows(signalDates, priceSeries);
  const latest = signalDates.length ? signalDates[signalDates.length - 1] : null;
  const today = new Date().toISOString().slice(0, 10);
  const status = buildStatus(config, latest, today);
  const summary = summarizeReturns(historyRows);

  const market: MarketBreadthGateMarket = {
    asset: config.asset,
    label: config.label,
    mode: config.mode,
    signalDate: latest,
    perYear: round(signalDates.length / 5) ?? 0,
    avg1m: summary.avg1m,
    avg2m: summary.avg2m,
    avg3m: summary.avg3m,
    win1m: summary.win1m,
    win2m: summary.win2m,
    win3m: summary.win3m,
    shortThreshold: config.shortThreshold,
    mediumOversoldThreshold: config.mediumOversoldThreshold,
    mediumRecoveryFloor: config.mediumRecoveryFloor,
    lookbackDays: config.lookbackDays,
    regimeFilter: config.regimeFilter === 'slopepos_only' ? '200일선 기울기 상승' : '200일선 위 또는 200일선 기울기 상승',
    summary: '',
    recentSignals: historyRows.slice(-5).reverse(),
    ...status,
  };
  market.summary = buildSummaryLabel(market);
  return market;
}

async function computeSnapshot(): Promise<MarketBreadthGateSnapshot> {
  const sp500Symbols = await getSp500Symbols();
  const markets = await Promise.all(GATE_CONFIGS.map((config) => buildMarket(config, sp500Symbols)));
  const onMarkets = markets.filter((market) => market.status === 'ON').map((market) => market.asset);
  return {
    updatedAt: new Date().toISOString(),
    mode: '실전 개선형',
    summary: onMarkets.length
      ? `현재 실전형 breadth 게이트 ON: ${onMarkets.join(', ')}`
      : '현재 실전형 breadth 게이트는 모두 OFF 또는 RECENT 상태입니다.',
    markets,
  };
}

export async function getMarketBreadthGateSnapshot(force = false): Promise<MarketBreadthGateSnapshot | null> {
  if (!force) {
    const cached = await readSourceCacheWithin<MarketBreadthGateSnapshot>(CACHE_KEY, CACHE_TTL_MS);
    if (cached?.value) return cached.value;
  }

  if (inFlight) return inFlight;

  inFlight = computeSnapshot()
    .then(async (snapshot) => {
      await writeSourceCache(CACHE_KEY, snapshot, {
        mode: snapshot.mode,
        summary: snapshot.summary,
      });
      return snapshot;
    })
    .catch((error) => {
      log.error({ error: serializeError(error) }, 'market breadth gate compute failed');
      return null;
    })
    .finally(() => {
      inFlight = null;
    });

  return inFlight;
}
