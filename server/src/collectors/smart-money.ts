import axios from 'axios';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const USER_AGENT =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const log = childLogger({ module: 'collector.smart-money' });
const HTML_HEADERS = {
  'User-Agent': USER_AGENT,
  Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
};
const SEC_HEADERS = {
  'User-Agent': 'MacroSquare/1.0 contact:lks@example.com',
  Accept: 'application/atom+xml,application/xml,text/xml,text/html;q=0.9,*/*;q=0.8',
};

interface InsiderTrade {
  date: string;
  ticker: string;
  insiderName: string;
  type: 'buy' | 'sell';
  shares: number;
  value: number;
}

interface SmartMoneySnapshot {
  insiderBuyRatio: number;
  recentInsiderBuys: number;
  recentInsiderSells: number;
  lastUpdated: string;
}

interface InsiderSourceResult extends SmartMoneySnapshot {
  primarySource: 'openinsider-latest' | 'openinsider-screener' | 'sec-form4';
  openInsider: boolean;
  // 18차 P1#5: cluster buy 메타 (optional — openinsider 경로에서만 채워짐)
  clusterTickerCount?: number;
  maxClusterSize?: number;
  topClusters?: Array<{ ticker: string; count: number }>;
  // 20차 노션 A3: 단일 큰거래 (≥$500k) 24h 카운트
  largeBuyCount?: number;
  largeBuyTopAmounts?: number[];
}

interface SmartMoneySources {
  openInsider: boolean;
  dataroma: boolean;
  insiderPrimarySource?: 'openinsider-latest' | 'openinsider-screener' | 'sec-form4' | 'none';
}

interface SmartMoneyCacheMeta {
  status: 'fresh' | 'cached' | 'stale';
  fetchedAt: string;
  ageMs: number;
  ttlMs: number;
  staleTtlMs: number;
}

export interface SmartMoneyData extends SmartMoneySnapshot {
  score: number;
  dataromaBuyCount?: number;
  dataromaSellCount?: number;
  dataromaScore?: number;
  /** Add 액션 평균 % (기존 포지션 대비 증가율) */
  dataromaAvgAddPct?: number;
  /** Reduce 액션 평균 % (절댓값, 기존 포지션 대비 감소율) */
  dataromaAvgReducePct?: number;
  /** 포트폴리오 비중 기여 순합계 (매수 +, 매도 -) */
  dataromaNetPortfolioFlow?: number;
  sources?: SmartMoneySources;
  cache?: SmartMoneyCacheMeta;
  // 18차 P1#5: cluster buy
  clusterTickerCount?: number;
  maxClusterSize?: number;
  topClusters?: Array<{ ticker: string; count: number }>;
  // 20차 노션 A3: 단일 큰거래
  largeBuyCount?: number;
  largeBuyTopAmounts?: number[];
}

export function scoreSmartMoney(insiderBuyRatio: number): number {
  if (insiderBuyRatio >= 65) return 2;
  if (insiderBuyRatio >= 55) return 1;
  if (insiderBuyRatio >= 45) return 0;
  if (insiderBuyRatio >= 35) return -1;
  return -2;
}

// OpenInsider 는 intraday 성격이지만 15분마다 재요청할 정도의 체결성 소스는 아니다.
// Dataroma 는 분기성 13F 기반이라 더더욱 자주 볼 이유가 없다.
// 소스 구조에 맞춰 fresh 6시간 / stale 48시간으로 완화해 timeout 재발생을 줄인다.
const SMART_MONEY_CACHE_TTL_MS = 6 * 60 * 60 * 1000;
const SMART_MONEY_STALE_TTL_MS = 48 * 60 * 60 * 1000;
const SMART_MONEY_CACHE_KEY = 'smart-money-summary';

let cachedSmartMoney:
  | {
      value: SmartMoneyData;
      fetchedAt: number;
    }
  | null = null;
let inflightSmartMoney: Promise<SmartMoneyData | null> | null = null;

function withCacheMeta(
  value: SmartMoneyData,
  status: SmartMoneyCacheMeta['status'],
  fetchedAt: number,
): SmartMoneyData {
  return {
    ...value,
    cache: {
      status,
      fetchedAt: new Date(fetchedAt).toISOString(),
      ageMs: Math.max(0, Date.now() - fetchedAt),
      ttlMs: SMART_MONEY_CACHE_TTL_MS,
      staleTtlMs: SMART_MONEY_STALE_TTL_MS,
    },
  };
}

function getCachedSmartMoney(kind: 'fresh' | 'stale'): SmartMoneyData | null {
  if (!cachedSmartMoney) return null;
  const ageMs = Date.now() - cachedSmartMoney.fetchedAt;
  if (kind === 'fresh' && ageMs > SMART_MONEY_CACHE_TTL_MS) return null;
  if (kind === 'stale' && ageMs > SMART_MONEY_STALE_TTL_MS) return null;
  return withCacheMeta(cachedSmartMoney.value, kind === 'fresh' ? 'cached' : 'stale', cachedSmartMoney.fetchedAt);
}

async function hydrateSmartMoneyCache() {
  if (cachedSmartMoney) return;
  const cached = await readSourceCacheWithin<SmartMoneyData>(SMART_MONEY_CACHE_KEY, SMART_MONEY_STALE_TTL_MS);
  if (!cached) return;
  cachedSmartMoney = {
    value: {
      ...cached.value,
      cache: undefined,
    },
    fetchedAt: new Date(cached.updatedAt).getTime(),
  };
}

async function getHtmlWithRetry(
  url: string,
  source: string,
  extraHeaders?: Record<string, string>,
  options?: { retries?: number; timeoutMs?: number },
): Promise<string> {
  const retries = options?.retries ?? 1;
  const timeoutMs = options?.timeoutMs ?? 12000;
  let lastError: unknown = null;
  for (let attempt = 1; attempt <= retries + 1; attempt += 1) {
    try {
      const { data } = await axios.get<string>(url, {
        headers: { ...HTML_HEADERS, ...extraHeaders },
        timeout: timeoutMs,
        responseType: 'text',
      });
      return data;
    } catch (error) {
      lastError = error;
      log.warn({ source, attempt, timeoutMs, error: serializeError(error) }, 'smart money source request failed');
      if (attempt <= retries) {
        await new Promise((resolve) => setTimeout(resolve, 400 * attempt));
      }
    }
  }
  throw lastError instanceof Error ? lastError : new Error(`${source} request failed`);
}

function parseOpenInsiderSummary(html: string): SmartMoneySnapshot | null {
  const buyMatches = (html.match(/\bP - Purchase\b/gi) || []).length;
  const sellMatches = (html.match(/\bS - Sale(?:\+OE)?\b/gi) || []).length;
  const total = buyMatches + sellMatches;
  if (total === 0) {
    return null;
  }
  return {
    insiderBuyRatio: parseFloat(((buyMatches / total) * 100).toFixed(1)),
    recentInsiderBuys: buyMatches,
    recentInsiderSells: sellMatches,
    lastUpdated: new Date().toISOString().split('T')[0],
  };
}

// 20차 노션 A3: 단일 큰거래 (≥$500k) 24h 카운트 — cluster 와 보완.
export function parseOpenInsiderLargeSingleBuys(html: string): { largeBuyCount: number; topAmounts: number[] } {
  const rowRe = /<tr[\s\S]*?<\/tr>/gi;
  const rows = html.match(rowRe) || [];
  const amounts: number[] = [];
  for (const row of rows) {
    if (!/P - Purchase/i.test(row)) continue;
    // 거래액 추출 — $1,234,567 형식
    const m = row.match(/\$([\d,]+(?:\.\d+)?)/);
    if (!m) continue;
    const v = parseFloat(m[1].replace(/,/g, ''));
    if (Number.isFinite(v) && v >= 500000) amounts.push(v);
  }
  amounts.sort((a, b) => b - a);
  return { largeBuyCount: amounts.length, topAmounts: amounts.slice(0, 5) };
}

// 18차 P1#5: cluster buy 파싱 — openinsider 테이블에서 ticker 별 buy row 카운트.
// 같은 ticker 가 N 행 이상 나오면 cluster. buy row 인지 판단은 "P - Purchase" 키워드로.
export function parseOpenInsiderClusters(html: string): { clusterTickerCount: number; maxClusterSize: number; topClusters: Array<{ ticker: string; count: number }> } {
  const rowRe = /<tr[\s\S]*?<\/tr>/gi;
  const tickerRe = /<a[^>]*\?q=([A-Z0-9.-]{1,6})[^>]*>([A-Z0-9.-]{1,6})<\/a>/;
  const counts = new Map<string, number>();
  const rows = html.match(rowRe) || [];
  for (const row of rows) {
    if (!/P - Purchase/i.test(row)) continue;
    const m = row.match(tickerRe);
    if (!m) continue;
    const t = m[1];
    counts.set(t, (counts.get(t) ?? 0) + 1);
  }
  const entries = Array.from(counts.entries()).map(([ticker, count]) => ({ ticker, count })).sort((a, b) => b.count - a.count);
  const clusters = entries.filter((e) => e.count >= 2);
  return {
    clusterTickerCount: clusters.length,
    maxClusterSize: entries[0]?.count ?? 0,
    topClusters: clusters.slice(0, 10),
  };
}

async function fetchOpenInsiderLatestSummary(): Promise<InsiderSourceResult> {
  const html = await getHtmlWithRetry(
    'http://openinsider.com/latest-insider-trading',
    'openinsider-latest',
    undefined,
    { retries: 1, timeoutMs: 3500 },
  );
  const parsed = parseOpenInsiderSummary(html);
  if (!parsed) {
    throw new Error('openinsider latest returned no purchase/sale rows');
  }
  const clusters = parseOpenInsiderClusters(html);
  const large = parseOpenInsiderLargeSingleBuys(html);
  return {
    ...parsed,
    primarySource: 'openinsider-latest',
    openInsider: true,
    clusterTickerCount: clusters.clusterTickerCount,
    maxClusterSize: clusters.maxClusterSize,
    topClusters: clusters.topClusters,
    largeBuyCount: large.largeBuyCount,
    largeBuyTopAmounts: large.topAmounts,
  };
}

async function fetchOpenInsiderScreenerSummary(): Promise<InsiderSourceResult> {
  const html = await getHtmlWithRetry(
    'http://openinsider.com/screener?s=&o=&pl=&ph=&ll=&lh=&fd=7&fdr=&td=0&tdr=&feession=at&cession=at&xp=1&vl=100000&vh=&ocl=&och=&session=ic1&cnt=100&page=1',
    'openinsider-screener',
    undefined,
    { retries: 1, timeoutMs: 4500 },
  );
  const parsed = parseOpenInsiderSummary(html);
  if (!parsed) {
    throw new Error('openinsider screener returned no purchase/sale rows');
  }
  const clusters = parseOpenInsiderClusters(html);
  return {
    ...parsed,
    primarySource: 'openinsider-screener',
    openInsider: true,
    clusterTickerCount: clusters.clusterTickerCount,
    maxClusterSize: clusters.maxClusterSize,
    topClusters: clusters.topClusters,
  };
}

async function fetchSecForm4Summary(): Promise<InsiderSourceResult | null> {
  try {
    const { data: atom } = await axios.get<string>(
      'https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent&type=4&company=&dateb=&owner=only&start=0&count=20&output=atom',
      {
        headers: SEC_HEADERS,
        timeout: 8000,
        responseType: 'text',
      },
    );

    const indexLinks = Array.from(
      new Set(
        Array.from(
          atom.matchAll(/<link[^>]*href="(https:\/\/www\.sec\.gov\/Archives\/[^"]+-index\.htm)"/gi),
        ).map((match) => match[1]),
      ),
    ).slice(0, 10);

    if (indexLinks.length === 0) {
      return null;
    }

    let buyCount = 0;
    let sellCount = 0;

    const pages = await Promise.allSettled(
      indexLinks.map((link) =>
        axios.get<string>(link, {
          headers: SEC_HEADERS,
          timeout: 8000,
          responseType: 'text',
        }),
      ),
    );

    const ownershipLinks = pages
      .flatMap((result) => {
        if (result.status !== 'fulfilled') return [];
        const matches = Array.from(
          result.value.data.matchAll(/href="([^"]*\/ownership\.xml)"/gi),
        ).map((match) => match[1]);
        const selected = matches.find((href) => !/xsl/i.test(href)) ?? matches[0] ?? null;
        if (!selected) return [];
        return [selected.startsWith('http') ? selected : `https://www.sec.gov${selected}`];
      })
      .filter((value): value is string => Boolean(value))
      .slice(0, 10);

    if (ownershipLinks.length === 0) {
      return null;
    }

    const docs = await Promise.allSettled(
      ownershipLinks.map((link) =>
        axios.get<string>(link, {
          headers: SEC_HEADERS,
          timeout: 8000,
          responseType: 'text',
        }),
      ),
    );

    for (const doc of docs) {
      if (doc.status !== 'fulfilled') continue;
      const xml = doc.value.data;
      buyCount += (xml.match(/<transactionCode>\s*P\s*<\/transactionCode>/gi) || []).length;
      sellCount += (xml.match(/<transactionCode>\s*S\s*<\/transactionCode>/gi) || []).length;
    }

    const total = buyCount + sellCount;
    if (total === 0) {
      return null;
    }

    log.info({
      source: 'sec-form4',
      filings: ownershipLinks.length,
      buyCount,
      sellCount,
    }, 'smart money sec fallback collected');

    return {
      insiderBuyRatio: parseFloat(((buyCount / total) * 100).toFixed(1)),
      recentInsiderBuys: buyCount,
      recentInsiderSells: sellCount,
      lastUpdated: new Date().toISOString().split('T')[0],
      primarySource: 'sec-form4',
      openInsider: false,
    };
  } catch (error) {
    log.warn({ source: 'sec-form4', error: serializeError(error) }, 'smart money sec fallback failed');
    return null;
  }
}

async function fetchPreferredInsiderSummary(): Promise<InsiderSourceResult> {
  // 실측상 OpenInsider 홈은 dedicated latest/screener 보다 느리고 timeout 빈도가 높았다.
  // metric semantics 는 OpenInsider 우선 유지하되, 홈을 제거하고 latest -> screener -> SEC 순으로 축소한다.
  try {
    return await fetchOpenInsiderLatestSummary();
  } catch (error) {
    log.warn({ source: 'openinsider-latest', error: serializeError(error) }, 'smart money source unavailable');
  }

  try {
    return await fetchOpenInsiderScreenerSummary();
  } catch (error) {
    log.warn({ source: 'openinsider-screener', error: serializeError(error) }, 'smart money source unavailable');
  }

  const secSummary = await fetchSecForm4Summary();
  if (secSummary) {
    return secSummary;
  }
  throw new Error('all insider sources failed');
}

async function fetchInsiderSummaryFresh(): Promise<SmartMoneyData | null> {
  const startedAt = Date.now();
  let sourceAvailability: SmartMoneySources = { openInsider: false, dataroma: false, insiderPrimarySource: 'none' };
  try {
    const [insiderResult, dataroma] = await Promise.allSettled([
      fetchPreferredInsiderSummary(),
      fetchDataromaActivitySummary(),
    ]);

    if (dataroma.status === 'rejected') {
      log.warn({ source: 'dataroma', error: serializeError(dataroma.reason) }, 'smart money source unavailable');
    }

    if (insiderResult.status === 'rejected' && dataroma.status === 'rejected') {
      throw new Error('insider and dataroma sources both failed');
    }

    const insider = insiderResult.status === 'fulfilled' ? insiderResult.value : null;
    const dataromaSummary = dataroma.status === 'fulfilled' ? dataroma.value : null;
    sourceAvailability = {
      openInsider: insider?.openInsider ?? false,
      dataroma: Boolean(dataromaSummary),
      insiderPrimarySource: insider?.primarySource ?? 'none',
    };
    const combinedScoreBase = insider ? scoreSmartMoney(insider.insiderBuyRatio) : 0;
    const combinedScore = insider && dataromaSummary
      ? Math.max(-2, Math.min(2, Math.round((combinedScoreBase + dataromaSummary.score) / 2)))
      : insider
        ? combinedScoreBase
        : dataromaSummary!.score;

    const result: SmartMoneyData = {
      insiderBuyRatio: insider?.insiderBuyRatio ?? 50,
      recentInsiderBuys: insider?.recentInsiderBuys ?? 0,
      recentInsiderSells: insider?.recentInsiderSells ?? 0,
      score: combinedScore,
      dataromaBuyCount: dataromaSummary?.buyCount ?? 0,
      dataromaSellCount: dataromaSummary?.sellCount ?? 0,
      dataromaScore: dataromaSummary?.score ?? 0,
      dataromaAvgAddPct: dataromaSummary?.avgAddPct ?? 0,
      dataromaAvgReducePct: dataromaSummary?.avgReducePct ?? 0,
      dataromaNetPortfolioFlow: dataromaSummary?.netPortfolioFlow ?? 0,
      lastUpdated: new Date().toISOString().split('T')[0],
      sources: sourceAvailability,
      // 18차 P1#5: cluster buy
      clusterTickerCount: insider?.clusterTickerCount,
      maxClusterSize: insider?.maxClusterSize,
      topClusters: insider?.topClusters,
      largeBuyCount: insider?.largeBuyCount,
      largeBuyTopAmounts: insider?.largeBuyTopAmounts,
    };
    cachedSmartMoney = { value: result, fetchedAt: Date.now() };
    await writeSourceCache(SMART_MONEY_CACHE_KEY, result, {
      sources: sourceAvailability,
      collector: 'smart-money',
    });
    return withCacheMeta(result, 'fresh', cachedSmartMoney.fetchedAt);
  } catch (error) {
    log.error({ error: serializeError(error) }, 'smart money collection failed');
    return null;
  } finally {
    log.info({ durationMs: Date.now() - startedAt, sources: sourceAvailability }, 'smart money collection completed');
  }
}

export async function fetchInsiderSummary(options?: { force?: boolean; allowStale?: boolean }): Promise<SmartMoneyData | null> {
  const force = options?.force ?? false;
  const allowStale = options?.allowStale ?? true;

  await hydrateSmartMoneyCache();

  if (!force) {
    const cached = getCachedSmartMoney('fresh');
    if (cached) {
      log.info({ ageMs: cached.cache?.ageMs, ttlMs: SMART_MONEY_CACHE_TTL_MS }, 'smart money cache hit');
      return cached;
    }
    if (inflightSmartMoney) {
      log.info({}, 'smart money request joined in-flight refresh');
      return inflightSmartMoney;
    }
  }

  inflightSmartMoney = (async () => {
    const fresh = await fetchInsiderSummaryFresh();
    if (fresh) return fresh;

    if (allowStale) {
      await hydrateSmartMoneyCache();
      const stale = getCachedSmartMoney('stale');
      if (stale) {
        log.warn({ ageMs: stale.cache?.ageMs, staleTtlMs: SMART_MONEY_STALE_TTL_MS }, 'smart money serving stale cache after refresh failure');
        return stale;
      }
    }

    return null;
  })();

  try {
    return await inflightSmartMoney;
  } finally {
    inflightSmartMoney = null;
  }
}

interface DataromaActivity {
  buyCount: number;
  sellCount: number;
  avgAddPct: number;
  avgReducePct: number;
  netPortfolioFlow: number;
  score: number;
}

/**
 * Dataroma /m/allact.php 페이지의 superinvestors 활동을 13F 변화량 관점에서 집계.
 *
 * HTML 예시:
 *   <a class="buy" href="/m/activity.php?sym=CP&typ=a">CP</a>
 *   <div>Canadian Pacific Kansas City<br/>Add 269.87%<br/>Change to portfolio: 2.42%</div>
 *
 * - class="buy"/"sell" 로 액션 방향
 * - Add NN% / Reduce -NN% / Sell -100% 에서 포지션 증감률
 * - "Change to portfolio: N%" 에서 포트폴리오 비중 변화
 */
async function fetchDataromaActivitySummary(): Promise<DataromaActivity> {
  const html = await getHtmlWithRetry(
    'https://www.dataroma.com/m/allact.php?typ=a',
    'dataroma',
    { Referer: 'https://www.dataroma.com/' },
    { retries: 1, timeoutMs: 12000 },
  );

  const blocks = html.split(/<td class="sym">/).slice(1);

  let buyCount = 0;
  let sellCount = 0;
  const addPcts: number[] = [];
  const reducePcts: number[] = [];
  let netPortfolioFlow = 0;

  for (const block of blocks) {
    const classMatch = block.match(/class="(buy|sell)"/);
    if (!classMatch) continue;
    const isBuy = classMatch[1] === 'buy';

    if (isBuy) buyCount += 1;
    else sellCount += 1;

    const actionMatch = block.match(/<br\s*\/?>\s*(Buy|Sell|Add\s+-?[\d.]+%|Reduce\s+-?[\d.]+%|Sell\s+-?[\d.]+%)\s*<br/);
    if (actionMatch) {
      const action = actionMatch[1];
      const pctMatch = action.match(/(-?[\d.]+)%/);
      if (pctMatch) {
        const pct = Math.abs(parseFloat(pctMatch[1]));
        if (/^Add/i.test(action)) addPcts.push(pct);
        else if (/^Reduce/i.test(action)) reducePcts.push(pct);
        else if (/^Sell/i.test(action)) reducePcts.push(pct);
      }
    }

    const changeMatch = block.match(/Change to portfolio:\s*(-?[\d.]+)%/);
    if (changeMatch) {
      const change = Math.abs(parseFloat(changeMatch[1]));
      netPortfolioFlow += isBuy ? change : -change;
    }
  }

  const total = buyCount + sellCount;
  const ratio = total > 0 ? (buyCount / total) * 100 : 50;
  const avg = (arr: number[]) => (arr.length > 0 ? arr.reduce((s, v) => s + v, 0) / arr.length : 0);
  const avgAddPct = parseFloat(avg(addPcts).toFixed(2));
  const avgReducePct = parseFloat(avg(reducePcts).toFixed(2));
  const netFlow = parseFloat(netPortfolioFlow.toFixed(2));

  let score = scoreSmartMoney(ratio);
  // 포트폴리오 기여 net flow가 강하게 기울어지면 score 한 단계 보정 (max ±2)
  if (netFlow >= 20) score = Math.min(2, score + 1);
  else if (netFlow <= -20) score = Math.max(-2, score - 1);

  return {
    buyCount,
    sellCount,
    avgAddPct,
    avgReducePct,
    netPortfolioFlow: netFlow,
    score,
  };
}

export async function fetchDataromaSuperinvestors(): Promise<Record<string, string> | null> {
  try {
    const html = await getHtmlWithRetry(
      'https://www.dataroma.com/m/home.php',
      'dataroma-home',
      { Referer: 'https://www.dataroma.com/' },
      { retries: 1, timeoutMs: 12000 },
    );

    const nameMatches = html.match(/<a[^>]*href="\/m\/holdings\.php\?m=[^"]*"[^>]*>([^<]+)<\/a>/g) || [];
    const result: Record<string, string> = {};
    for (const match of nameMatches.slice(0, 10)) {
      const name = match.replace(/<[^>]+>/g, '').trim();
      if (name) result[name] = 'tracked';
    }

    return Object.keys(result).length > 0 ? result : null;
  } catch {
    return null;
  }
}
