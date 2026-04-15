import fs from 'fs/promises';
import path from 'path';
import { fetchFredHistoryFrom, FRED_SERIES } from '../collectors/fred';
import { fetchYahooHistoryYears, YAHOO_SYMBOLS } from '../collectors/yahoo';
import { classifyRegime } from '../engines/regime';
import { computeSignals } from '../engines/signals';
import { computeAllocation } from '../engines/allocation';
import { computeDerived } from '../engines/derived';
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

/**
 * date 시점 시계열로 200DMA 기반 파생지표 (SMA200/DISPARITY/DRAWDOWN/ABOVE_200DMA) 와
 * ±15% 구간 연속 일수(DISPARITY_STREAK_OVERHEATED/OVERSOLD) + CHASE_WARNING 까지 재구성.
 *
 * derived 의 라이브 동등 키 집합:
 *   {PREFIX}_SMA200, {PREFIX}_DISPARITY, {PREFIX}_DRAWDOWN, {PREFIX}_ABOVE_200DMA,
 *   {PREFIX}_DISPARITY_STREAK_OVERHEATED, {PREFIX}_DISPARITY_STREAK_OVERSOLD,
 *   {PREFIX}_CHASE_WARNING.
 */
function reconstruct200DmaSuite(
  date: string,
  history: HistoryPoint[],
  prefix: 'NASDAQ' | 'KOSPI',
): Record<string, DerivedIndicator> {
  const derived: Record<string, DerivedIndicator> = {};
  const eligible = history.filter((p) => dateToTime(p.date) <= dateToTime(date));
  if (eligible.length < 200) return derived;

  const latest200 = eligible.slice(-200);
  const sma200 = latest200.reduce((sum, p) => sum + p.value, 0) / latest200.length;
  const current = eligible[eligible.length - 1].value;
  const allTimeHigh = Math.max(...eligible.map((p) => p.value));
  const lower = prefix.toLowerCase();

  derived[`${prefix}_SMA200`] = { name: `${lower}_sma200`, value: sma200, date, formula: 'SMA200' };
  derived[`${prefix}_DISPARITY`] = {
    name: `${lower}_disparity_200`,
    value: ((current - sma200) / sma200) * 100,
    date,
    formula: '(P-SMA)/SMA*100',
  };
  derived[`${prefix}_DRAWDOWN`] = {
    name: `${lower}_drawdown`,
    value: ((current - allTimeHigh) / allTimeHigh) * 100,
    date,
    formula: '(P-ATH)/ATH*100',
  };
  derived[`${prefix}_ABOVE_200DMA`] = {
    name: `${lower}_above_200dma`,
    value: current > sma200 ? 1 : 0,
    date,
    formula: 'P>SMA200',
  };

  // 이격률 ±15% 연속 유지일 (live derived.ts 의 computeDisparityStreak 동일 로직).
  // 인덱스 0=최신 가정으로 작성된 라이브 함수 시그니처에 맞춰 reverse 시리즈 사용.
  const closesNewestFirst = [...eligible.map((p) => p.value)].reverse();
  let overStreak = 0;
  let underStreak = 0;
  for (const price of closesNewestFirst) {
    const disp = ((price - sma200) / sma200) * 100;
    if (disp >= 15) overStreak += 1;
    else break;
  }
  for (const price of closesNewestFirst) {
    const disp = ((price - sma200) / sma200) * 100;
    if (disp <= -15) underStreak += 1;
    else break;
  }
  if (overStreak > 0) {
    derived[`${prefix}_DISPARITY_STREAK_OVERHEATED`] = {
      name: `${lower}_disparity_streak_overheated`,
      value: overStreak,
      date,
      formula: '이격률 ≥ +15% 연속 유지일 (200DMA 기준)',
    };
  }
  if (underStreak > 0) {
    derived[`${prefix}_DISPARITY_STREAK_OVERSOLD`] = {
      name: `${lower}_disparity_streak_oversold`,
      value: underStreak,
      date,
      formula: '이격률 ≤ -15% 연속 유지일 (200DMA 기준)',
    };
  }
  if (overStreak >= 20 || underStreak >= 20) {
    derived[`${prefix}_CHASE_WARNING`] = {
      name: `${lower}_chase_warning`,
      value: 1,
      date,
      formula: '이격률 ±15% 구간 20일 이상 지속 → 추격/투매 경고',
    };
  }
  return derived;
}

/**
 * date 시점의 HYG/IEF 비율, 252일 z-score, CREDIT_STRESS_FLAG 재구성.
 * HY OAS(BAMLH0A0HYM2) 가 raw 에 있으면 600bp 임계 OR z<=-2 로 flag 결정.
 */
function reconstructCreditStress(
  date: string,
  hygHist: HistoryPoint[],
  iefHist: HistoryPoint[],
  hyOasRawValue: number | undefined,
): Record<string, DerivedIndicator> {
  const derived: Record<string, DerivedIndicator> = {};
  const target = dateToTime(date);

  // HY OAS bp 환산 (라이브 로직과 동일).
  let hyOasBp: number | null = null;
  if (hyOasRawValue !== undefined) {
    hyOasBp = hyOasRawValue > 50 ? hyOasRawValue : hyOasRawValue * 100;
    derived.CREDIT_HY_OAS_BP = {
      name: 'credit_hy_oas_bp',
      value: parseFloat(hyOasBp.toFixed(1)),
      date,
      formula: 'BAMLH0A0HYM2 (ICE BofA US HY OAS) — bp 환산',
    };
  }

  // HYG/IEF 비율 시리즈를 date 까지로 잘라 252일 z-score 계산.
  const iefMap = new Map<string, number>();
  for (const p of iefHist) {
    if (dateToTime(p.date) <= target) iefMap.set(p.date, p.value);
  }
  const ratios: number[] = [];
  let latestRatio: number | null = null;
  for (const h of hygHist) {
    if (dateToTime(h.date) > target) break;
    const ief = iefMap.get(h.date);
    if (ief && ief > 0 && h.value > 0) {
      const r = h.value / ief;
      ratios.push(r);
      latestRatio = r;
    }
  }

  let hygIefZ: number | null = null;
  if (latestRatio !== null) {
    derived.CREDIT_HYG_IEF_RATIO = {
      name: 'credit_hyg_ief_ratio',
      value: parseFloat(latestRatio.toFixed(4)),
      date,
      formula: 'Yahoo HYG 종가 / IEF 종가',
    };
    if (ratios.length >= 252) {
      const window = ratios.slice(-252);
      const mean = window.reduce((s, v) => s + v, 0) / window.length;
      const variance = window.reduce((s, v) => s + (v - mean) ** 2, 0) / window.length;
      const stdev = Math.sqrt(variance);
      if (stdev > 0) {
        hygIefZ = (latestRatio - mean) / stdev;
        derived.CREDIT_HYG_IEF_ZSCORE = {
          name: 'credit_hyg_ief_zscore',
          value: parseFloat(hygIefZ.toFixed(2)),
          date,
          formula: '(HYG/IEF 현재 - 252일 평균) / 252일 표준편차',
        };
      }
    }
  }

  if (hyOasBp !== null || hygIefZ !== null) {
    const stressHy = hyOasBp !== null && hyOasBp >= 600;
    const stressZ = hygIefZ !== null && hygIefZ <= -2;
    derived.CREDIT_STRESS_FLAG = {
      name: 'credit_stress_flag',
      value: stressHy || stressZ ? 1 : 0,
      date,
      formula: `HY OAS ≥ 600bp(${stressHy ? 'Y' : 'N'}) OR HYG/IEF z ≤ -2(${stressZ ? 'Y' : 'N'})`,
    };
  }
  return derived;
}

/**
 * date 시점의 WM2NS YoY%, 음→양 교차 후 경과일 재구성.
 * 라이브 derived.ts === 동일 시그니처: M2_YOY_PCT, M2_YOY_DELTA_3M, M2_YOY_CROSS_DAYS.
 */
function reconstructM2Yoy(
  date: string,
  m2Hist: HistoryPoint[],
): Record<string, DerivedIndicator> {
  const derived: Record<string, DerivedIndicator> = {};
  const target = dateToTime(date);
  const eligible = m2Hist.filter((p) => dateToTime(p.date) <= target);
  if (eligible.length < 60) return derived;

  const yoySeries: { date: string; yoy: number }[] = [];
  for (let i = 0; i < eligible.length; i += 1) {
    const cur = eligible[i];
    const targetDt = new Date(cur.date);
    targetDt.setFullYear(targetDt.getFullYear() - 1);
    const targetMs = targetDt.getTime();
    let past: { date: string; value: number } | null = null;
    for (let j = i - 1; j >= 0; j -= 1) {
      if (new Date(eligible[j].date).getTime() <= targetMs) {
        past = eligible[j];
        break;
      }
    }
    if (past && past.value > 0) {
      yoySeries.push({ date: cur.date, yoy: (cur.value / past.value - 1) * 100 });
    }
  }
  if (yoySeries.length === 0) return derived;
  const latest = yoySeries[yoySeries.length - 1];
  derived.M2_YOY_PCT = {
    name: 'm2_yoy_pct',
    value: parseFloat(latest.yoy.toFixed(2)),
    date,
    formula: 'WM2NS 현재 / 52주 전 - 1 (%)',
  };
  if (yoySeries.length >= 14) {
    const past3m = yoySeries[yoySeries.length - 14];
    derived.M2_YOY_DELTA_3M = {
      name: 'm2_yoy_delta_3m',
      value: parseFloat((latest.yoy - past3m.yoy).toFixed(2)),
      date,
      formula: 'M2 YoY 현재 - 약 13주 전 YoY (포인트)',
    };
  }
  if (latest.yoy > 0) {
    let crossDate: string | null = null;
    for (let i = yoySeries.length - 1; i > 0; i -= 1) {
      const prev = yoySeries[i - 1];
      const cur = yoySeries[i];
      if (prev.yoy < 0 && cur.yoy >= 0) {
        crossDate = cur.date;
        break;
      }
    }
    if (crossDate) {
      const daysElapsed = Math.floor(
        (new Date(latest.date).getTime() - new Date(crossDate).getTime()) / 86400000,
      );
      derived.M2_YOY_CROSS_DAYS = {
        name: 'm2_yoy_cross_days',
        value: daysElapsed,
        date,
        formula: `WM2NS YoY 음→양 교차(${crossDate}) 이후 경과일`,
      };
    } else {
      derived.M2_YOY_CROSS_DAYS = {
        name: 'm2_yoy_cross_days',
        value: null,
        date,
        formula: '히스토리 내 음→양 교차 미확인',
      };
    }
  } else {
    derived.M2_YOY_CROSS_DAYS = {
      name: 'm2_yoy_cross_days',
      value: null,
      date,
      formula: '현재 YoY 음수 — 교차 미발생',
    };
  }
  return derived;
}

/**
 * Fix(3차 감사): cachedLiveDerived 과거 일자 오염 차단.
 *
 * 변경 전: 라이브 derived 를 shallow clone 후 NASDAQ/비율만 덮어써 STAGFLATION/MTF/PSYCH 등
 * 11개+ 단발 snapshot 지표가 1,258일 전체에 오늘 값으로 주입 → 과거 신호 왜곡.
 *
 * 변경 후:
 * - todayIso === date 일 때만 cachedLiveDerived 를 전체 사용 (오늘 스냅샷은 라이브와 동일).
 * - 과거 일자에는 cachedLiveDerived 를 사용하지 않고 빈 맵에서 시작.
 * - date-aware 재계산 가능한 지표 (NASDAQ/KOSPI 200DMA suite, 가격 비율, HYG/IEF/CREDIT,
 *   M2 YoY, USDKRW 채널 등) 만 채움. 단발 snapshot 지표 (STAGFLATION/BOND_VIGILANTE/
 *   OVERHEATED/MTF/PSYCH_SUBSCORE/RRP_DIRECTION/GLOBAL_M2_PROXY/SECTOR_xxx/
 *   SMART_MONEY_xxx/KRX_xxx/FISCAL_STRESS 등) 는 명시적으로 null 유지 → signals.ts 가 null 가드로 깔끔히 스킵.
 */
export async function recomputeFullDerivedForDate(
  date: string,
  raw: Record<string, MarketDataPoint>,
  nasdaqHistory: HistoryPoint[],
  cachedLiveDerived: Record<string, DerivedIndicator>,
  ctx?: {
    todayIso: string;
    kospiHistory: HistoryPoint[];
    hygHistory: HistoryPoint[];
    iefHistory: HistoryPoint[];
    m2Wm2nsHistory: HistoryPoint[];
  },
): Promise<Record<string, DerivedIndicator>> {
  // 오늘 날짜 anchor 면 라이브 derived 를 그대로 사용 (NEW: 단일 스냅샷 신선도 보존).
  // 그 외 모든 과거 일자는 빈 맵에서 시작 — cachedLiveDerived 를 일부러 무시한다.
  const isToday = ctx?.todayIso === date;
  const derived: Record<string, DerivedIndicator> = isToday ? { ...cachedLiveDerived } : {};

  // 가격 비율 — raw 가 있으면 date 시점으로 재계산.
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

  // NASDAQ 날짜 의존 필드 + 이격 streak.
  for (const [k, v] of Object.entries(reconstruct200DmaSuite(date, nasdaqHistory, 'NASDAQ'))) {
    derived[k] = v;
  }

  if (ctx) {
    // KOSPI 200DMA suite (오늘이 아닌 일자는 위 빈 맵에서 시작했으므로 여기서 채움).
    if (ctx.kospiHistory.length >= 200) {
      for (const [k, v] of Object.entries(reconstruct200DmaSuite(date, ctx.kospiHistory, 'KOSPI'))) {
        derived[k] = v;
      }
    }
    // 크레딧 스트레스 (HY OAS + HYG/IEF z-score).
    for (const [k, v] of Object.entries(
      reconstructCreditStress(date, ctx.hygHistory, ctx.iefHistory, raw.BAMLH0A0HYM2?.value),
    )) {
      derived[k] = v;
    }
    // M2 YoY 방향 전환.
    for (const [k, v] of Object.entries(reconstructM2Yoy(date, ctx.m2Wm2nsHistory))) {
      derived[k] = v;
    }
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
  const yahooKeys = new Set(['NASDAQ', 'GOLD', 'SILVER', 'COPPER', 'SP500', 'WTI', 'USDKRW', 'DXY']);
  for (const key of keys) {
    if (yahooKeys.has(key)) histories[`yahoo:${key}`] = await readHistory('yahoo', key);
    else histories[`fred:${key}`] = await readHistory('fred', key);
  }

  // Fix(3차 감사): date-aware 재계산 확장에 필요한 보조 히스토리. 없으면 빈 배열 → 해당 지표 null.
  const kospiHistory = await readHistory('yahoo', 'KOSPI');
  const hygHistory = await readHistory('yahoo', 'HYG');
  const iefHistory = await readHistory('yahoo', 'IEF');
  const m2Wm2nsHistory = await readHistory('fred', 'WM2NS');

  const base = histories['yahoo:NASDAQ'] || [];
  const computed: Record<string, HistoryPoint[]> = {
    REGIME: [],
    PORTFOLIO: [],
    NASDAQ: [],
    GOLD: [],
    SILVER: [],
    COPPER: [],
  };

  // Fix #1+#2(2차 감사): live computeDerived 를 anchor 루프 전에 **한 번만** 호출해 재사용.
  //   내부에서 Yahoo/FRED live fetch 가 많아 per-date 호출은 비용 폭발. 날짜 의존 필드는
  //   recomputeFullDerivedForDate 에서 raw/nasdaqHistory 로 덮어쓴다.
  let cachedLiveDerived: Record<string, DerivedIndicator> = {};
  try {
    const latestRaw = buildRawForDate(
      base.length > 0 ? base[base.length - 1].date : new Date().toISOString().split('T')[0],
      histories,
    );
    cachedLiveDerived = await computeDerived(latestRaw);
  } catch (error) {
    console.warn('[history] computeDerived cache failed, falling back to date-specific only:', error);
    cachedLiveDerived = {};
  }

  // 커버리지 통계 — 최근 1년 평균 재구성 키 수 점검 용도.
  const todayIso = new Date().toISOString().split('T')[0];
  const oneYearAgoMs = Date.now() - 365 * 86400 * 1000;
  let recentCoveragePoints = 0;
  let recentCoverageSum = 0;

  for (const anchor of base) {
    const raw = buildRawForDate(anchor.date, histories);
    const derived = await recomputeFullDerivedForDate(anchor.date, raw, base, cachedLiveDerived, {
      todayIso,
      kospiHistory,
      hygHistory,
      iefHistory,
      m2Wm2nsHistory,
    });
    if (dateToTime(anchor.date) >= oneYearAgoMs) {
      const nonNullKeys = Object.values(derived).filter((d) => d.value !== null).length;
      recentCoverageSum += nonNullKeys;
      recentCoveragePoints += 1;
    }
    // smartMoneyScore: 히스토리에는 smart-money 시계열이 없으므로 0 을 명시적으로 전달.
    //   라이브 경로와 동일 시그니처 유지가 이 Fix 의 핵심.
    const regime = classifyRegime({
      raw,
      derived,
      manualInputs: DEFAULT_PROFILE.manualInputs,
      smartMoneyScore: 0,
    });
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

  if (recentCoveragePoints > 0) {
    const avg = recentCoverageSum / recentCoveragePoints;
    console.log(
      `[history] 재구성 커버리지: 최근 1년 평균 ${avg.toFixed(1)} non-null derived keys/일자 (${recentCoveragePoints}일 기준). ` +
      `단발 snapshot 지표 (STAGFLATION/MTF/PSYCH/RRP_DIRECTION/SECTOR_*/KRX_* 등) 는 정책상 null 유지.`
    );
  }
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
