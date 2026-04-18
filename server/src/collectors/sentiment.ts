import axios from 'axios';
import { MarketDataPoint } from '../types/indicators';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

export interface SentimentPoint {
  value: number | null;
  asOf: string | null;
  source: string;
  error?: string;
  extra?: Record<string, number | null>;
}

const HEADERS = {
  'User-Agent':
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
};

const TIMEOUT_MS = 10000;
const log = childLogger({ module: 'collector.sentiment' });
const SENTIMENT_CACHE_MAX_AGE_MS = {
  PC_RATIO: 7 * 24 * 60 * 60 * 1000,
  AAII_BULL_BEAR_SPREAD: 21 * 24 * 60 * 60 * 1000,
  NAAIM_EXPOSURE: 21 * 24 * 60 * 60 * 1000,
} as const;

async function useSentimentCache(metric: keyof typeof SENTIMENT_CACHE_MAX_AGE_MS): Promise<SentimentPoint | null> {
  const cached = await readSourceCacheWithin<SentimentPoint>(`sentiment-${metric}`, SENTIMENT_CACHE_MAX_AGE_MS[metric]);
  if (!cached) return null;
  log.warn({
    metric,
    ageMs: cached.ageMs,
    updatedAt: cached.updatedAt,
    source: cached.value.source,
  }, 'sentiment metric live fetch failed, serving cached value');
  return cached.value;
}

function ageDaysFromAsOf(asOf: string | null | undefined): number | null {
  if (!asOf) return null;
  return Math.floor((Date.now() - new Date(`${asOf}T00:00:00Z`).getTime()) / 86400000);
}

export function shouldRefreshSentimentMetric(
  metric: keyof typeof SENTIMENT_CACHE_MAX_AGE_MS,
  cached: SentimentPoint | null,
): boolean {
  if (!cached?.asOf) return true;
  const ageDays = ageDaysFromAsOf(cached.asOf);
  if (ageDays === null) return true;
  if (metric === 'AAII_BULL_BEAR_SPREAD') return ageDays > 8;
  if (metric === 'NAAIM_EXPOSURE') return ageDays > 8;
  return true;
}

async function getSentimentCadenceCache(metric: keyof typeof SENTIMENT_CACHE_MAX_AGE_MS): Promise<SentimentPoint | null> {
  const cached = await readSourceCacheWithin<SentimentPoint>(`sentiment-${metric}`, SENTIMENT_CACHE_MAX_AGE_MS[metric]);
  if (!cached) return null;
  if (shouldRefreshSentimentMetric(metric, cached.value)) return null;
  log.info({
    metric,
    ageMs: cached.ageMs,
    asOf: cached.value.asOf,
    source: cached.value.source,
  }, 'sentiment metric source-fresh cache hit');
  return cached.value;
}

// ---- CBOE Put/Call Ratio (자체 집계) -------------------------------------
// 2026-04: CBOE 공식 PCR_ALL.csv 403, Yahoo ^CPC/^CPCE/^CPCI 404 로 모두 막힘.
// 대안: CBOE delayed quotes options chain (_SPX + SPY + QQQ) 의 call/put volume
// 을 직접 집계해 비율 계산. 당일 snapshot 값이고 10D MA 는 history rolling 으로.
const CBOE_OPTION_TICKERS = ['_SPX', 'SPY', 'QQQ'];

export async function fetchCBOEPutCall(): Promise<SentimentPoint> {
  const urls = CBOE_OPTION_TICKERS.map(
    (t) => `https://cdn.cboe.com/api/global/delayed_quotes/options/${t}.json`
  );
  const responses = await Promise.allSettled(
    urls.map((url) =>
      axios.get<{
        timestamp?: string;
        data?: { options?: Array<{ option: string; volume?: number }> };
      }>(url, {
        headers: { ...HEADERS, Accept: 'application/json', Referer: 'https://www.cboe.com/' },
        timeout: TIMEOUT_MS,
      })
    )
  );

  let putVol = 0;
  let callVol = 0;
  let timestamp: string | null = null;
  let successCount = 0;

  for (const r of responses) {
    if (r.status !== 'fulfilled') continue;
    const opts = r.value.data?.data?.options;
    if (!Array.isArray(opts)) continue;
    successCount++;
    if (!timestamp && r.value.data?.timestamp) timestamp = r.value.data.timestamp;
    for (const o of opts) {
      const sym = o.option ?? '';
      const vol = o.volume ?? 0;
      if (!Number.isFinite(vol)) continue;
      if (/C\d{8}$/.test(sym)) callVol += vol;
      else if (/P\d{8}$/.test(sym)) putVol += vol;
    }
  }

  if (successCount === 0 || callVol === 0) {
    return {
      value: null,
      asOf: null,
      source: 'CBOE:CHAIN',
      error: 'CBOE delayed_quotes options chain 전부 실패 또는 callVol=0',
    };
  }

  const pcr = putVol / callVol;
  const asOf = timestamp ? timestamp.slice(0, 10) : new Date().toISOString().slice(0, 10);
  return {
    value: parseFloat(pcr.toFixed(3)),
    asOf,
    source: 'CBOE:CHAIN',
    extra: {
      putVol: Math.round(putVol),
      callVol: Math.round(callVol),
      tickers: successCount,
    },
  };
}

// ---- AAII Bull/Bear Spread ---------------------------------------------
// 2026-04 운영 로그 기준: aaii.com XLS/page 는 Incapsula 403 이 빈번.
// 공식 Substack feed(insights.aaii.com/feed) 에는 최신 Sentiment Survey 본문이 포함되어
// Bullish / Neutral / Bearish 수치를 직접 파싱 가능하다.
const AAII_FEED_URL = 'https://insights.aaii.com/feed';

function parseSignedPercentToken(token: string | undefined): number | null {
  if (!token) return null;
  const normalized = token.replace(/[−–—]/g, '-').replace(/,/g, '').trim();
  const value = parseFloat(normalized);
  return Number.isFinite(value) ? value : null;
}

export async function fetchAAIIBullBear(): Promise<SentimentPoint> {
  try {
    const { data: xml } = await axios.get<string>(AAII_FEED_URL, {
      headers: { ...HEADERS, Accept: 'application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.8' },
      timeout: 20000,
      responseType: 'text',
    });

    const itemMatch = xml.match(
      /<item><title><!\[CDATA\[(AAII Sentiment Survey:.*?|Sentiment Survey:.*?)\]\]><\/title>.*?<link>(.*?)<\/link>.*?<pubDate>(.*?)<\/pubDate>.*?<content:encoded><!\[CDATA\[(.*?)\]\]><\/content:encoded>/s,
    );
    if (!itemMatch) {
      return { value: null, asOf: null, source: 'AAII:SUBSTACK', error: 'AAII Sentiment Survey item 미발견' };
    }

    const [, , link, pubDate, content] = itemMatch;
    const bullishMatch = content.match(/Bullish:\s*([0-9.]+)%/i);
    const bearishMatch = content.match(/Bearish:\s*([0-9.]+)%/i);
    const neutralMatch = content.match(/Neutral:\s*([0-9.]+)%/i);
    const reportedSpreadMatch = content.match(/bull-bear spread[\s\S]{0,160}?to\s*([−–—-]?[0-9.]+)%/i);

    const bullish = bullishMatch ? parseFloat(bullishMatch[1]) : NaN;
    const bearish = bearishMatch ? parseFloat(bearishMatch[1]) : NaN;
    const neutral = neutralMatch ? parseFloat(neutralMatch[1]) : NaN;
    const reportedSpread = parseSignedPercentToken(reportedSpreadMatch?.[1]);
    const spread = bullish - bearish;

    if (!Number.isFinite(bullish) || !Number.isFinite(bearish) || !Number.isFinite(spread)) {
      return {
        value: null,
        asOf: null,
        source: 'AAII:SUBSTACK',
        error: 'AAII feed item 에서 bullish/bearish/spread 파싱 실패',
      };
    }

    if (reportedSpread !== null && Math.abs(reportedSpread - spread) > 0.25) {
      log.warn({
        source: link,
        bull: bullish,
        bear: bearish,
        computedSpread: parseFloat(spread.toFixed(2)),
        reportedSpread,
      }, 'AAII reported spread mismatched component math, using bullish-bearish');
    }

    return {
      value: parseFloat(spread.toFixed(2)),
      asOf: new Date(pubDate).toISOString().slice(0, 10),
      source: 'AAII:SUBSTACK',
      extra: {
        bull: parseFloat(bullish.toFixed(2)),
        bear: parseFloat(bearish.toFixed(2)),
        neutral: Number.isFinite(neutral) ? parseFloat(neutral.toFixed(2)) : null,
        reportedSpread: reportedSpread !== null ? parseFloat(reportedSpread.toFixed(2)) : null,
      },
    };
  } catch (e) {
    const msg = (e as { response?: { status?: number }; message?: string })?.response?.status || (e as Error)?.message;
    return {
      value: null,
      asOf: null,
      source: 'AAII:SUBSTACK',
      error: `AAII feed fetch 실패: ${msg}`,
    };
  }
}

// ---- NAAIM Exposure Index ----------------------------------------------
// 2026-04 리서치: 기존 CSV URL 404. naaim.org/programs/naaim-exposure-index/
// 페이지 HTML 테이블에 최신값 포함 — <tr><td>MM/DD/YYYY</td><td>숫자</td>...</tr>.
export async function fetchNAAIM(): Promise<SentimentPoint> {
  const pageUrl = 'https://naaim.org/programs/naaim-exposure-index/';
  try {
    const { data } = await axios.get<string>(pageUrl, {
      headers: { ...HEADERS, Accept: 'text/html' },
      timeout: TIMEOUT_MS,
      responseType: 'text',
    });

    // HTML 테이블 행 순회 → 가장 최신(첫번째 매칭) 행의 date + value 추출.
    const rowRe = /<tr[^>]*>([\s\S]*?)<\/tr>/gi;
    const cellRe = /<td[^>]*>([\s\S]*?)<\/td>/gi;

    for (const rowMatch of data.matchAll(rowRe)) {
      const rowHtml = rowMatch[1];
      const cells: string[] = [];
      for (const cellMatch of rowHtml.matchAll(cellRe)) {
        cells.push(cellMatch[1].replace(/<[^>]+>/g, '').trim());
      }
      if (cells.length < 2) continue;

      const dateMatch = cells[0].match(/^(\d{2})\/(\d{2})\/(\d{4})$/);
      if (!dateMatch) continue;

      const value = parseFloat(cells[1]);
      if (!Number.isFinite(value)) continue;

      const [, mm, dd, yyyy] = dateMatch;
      return {
        value: parseFloat(value.toFixed(2)),
        asOf: `${yyyy}-${mm}-${dd}`,
        source: 'NAAIM',
      };
    }

    return {
      value: null,
      asOf: null,
      source: 'NAAIM',
      error: 'HTML 테이블에서 유효 행 미발견',
    };
  } catch (e) {
    const msg = (e as { response?: { status?: number }; message?: string })?.response?.status || (e as Error)?.message;
    return {
      value: null,
      asOf: null,
      source: 'NAAIM',
      error: `NAAIM 페이지 fetch 실패: ${msg}`,
    };
  }
}

// ---- 집계: 5분 스냅샷 cycle 에서 호출 ----------------------------------
export async function fetchAllSentiment(): Promise<Record<string, MarketDataPoint>> {
  const startedAt = Date.now();
  const [aaiiFreshCache, naaimFreshCache] = await Promise.all([
    getSentimentCadenceCache('AAII_BULL_BEAR_SPREAD'),
    getSentimentCadenceCache('NAAIM_EXPOSURE'),
  ]);
  const [pcr, aaii, naaim] = await Promise.allSettled([
    fetchCBOEPutCall(),
    aaiiFreshCache ? Promise.resolve(aaiiFreshCache) : fetchAAIIBullBear(),
    naaimFreshCache ? Promise.resolve(naaimFreshCache) : fetchNAAIM(),
  ]);

  const out: Record<string, MarketDataPoint> = {};
  const today = new Date().toISOString().split('T')[0];

  const pcrLive = pcr.status === 'fulfilled' && pcr.value.value !== null;
  let pcrVal = pcr.status === 'fulfilled' ? pcr.value : null;
  if (!pcrVal || pcrVal.value === null) {
    pcrVal = await useSentimentCache('PC_RATIO');
  }
  if (pcrVal && pcrVal.value !== null) {
    if (pcrLive) {
      await writeSourceCache('sentiment-PC_RATIO', pcrVal, { metric: 'PC_RATIO' });
    }
    out.PC_RATIO = {
      code: 'PC_RATIO',
      value: pcrVal.value,
      date: pcrVal.asOf ?? today,
      source: 'CBOE',
    };
    if (pcrVal.extra?.ma10 != null) {
      out.PC_RATIO_10D = {
        code: 'PC_RATIO_10D',
        value: pcrVal.extra.ma10,
        date: pcrVal.asOf ?? today,
        source: 'CBOE',
      };
    }
    log.info({
      metric: 'PC_RATIO',
      source: pcrVal.source,
      value: pcrVal.value,
      asOf: pcrVal.asOf,
      extra: pcrVal.extra,
    }, 'sentiment metric collected');
  } else {
    log.warn({
      metric: 'PC_RATIO',
      error: pcr.status === 'rejected' ? serializeError(pcr.reason) : pcrVal?.error,
    }, 'sentiment metric unavailable');
  }

  const aaiiLive = !aaiiFreshCache && aaii.status === 'fulfilled' && aaii.value.value !== null;
  let aaiiVal = aaii.status === 'fulfilled' ? aaii.value : null;
  if (!aaiiVal || aaiiVal.value === null) {
    aaiiVal = await useSentimentCache('AAII_BULL_BEAR_SPREAD');
  }
  if (aaiiVal && aaiiVal.value !== null) {
    if (aaiiLive) {
      await writeSourceCache('sentiment-AAII_BULL_BEAR_SPREAD', aaiiVal, { metric: 'AAII_BULL_BEAR_SPREAD' });
    }
    out.AAII_BULL_BEAR_SPREAD = {
      code: 'AAII_BULL_BEAR_SPREAD',
      value: aaiiVal.value,
      date: aaiiVal.asOf ?? today,
      source: 'CALC',
    };
    log.info({
      metric: 'AAII_BULL_BEAR_SPREAD',
      source: aaiiVal.source,
      value: aaiiVal.value,
      asOf: aaiiVal.asOf,
      extra: aaiiVal.extra,
    }, 'sentiment metric collected');
  } else {
    log.warn({
      metric: 'AAII_BULL_BEAR_SPREAD',
      error: aaii.status === 'rejected' ? serializeError(aaii.reason) : aaiiVal?.error,
    }, 'sentiment metric unavailable');
  }

  const naaimLive = !naaimFreshCache && naaim.status === 'fulfilled' && naaim.value.value !== null;
  let naaimVal = naaim.status === 'fulfilled' ? naaim.value : null;
  if (!naaimVal || naaimVal.value === null) {
    naaimVal = await useSentimentCache('NAAIM_EXPOSURE');
  }
  if (naaimVal && naaimVal.value !== null) {
    if (naaimLive) {
      await writeSourceCache('sentiment-NAAIM_EXPOSURE', naaimVal, { metric: 'NAAIM_EXPOSURE' });
    }
    out.NAAIM_EXPOSURE = {
      code: 'NAAIM_EXPOSURE',
      value: naaimVal.value,
      date: naaimVal.asOf ?? today,
      source: 'CALC',
    };
    log.info({
      metric: 'NAAIM_EXPOSURE',
      source: naaimVal.source,
      value: naaimVal.value,
      asOf: naaimVal.asOf,
    }, 'sentiment metric collected');
  } else {
    log.warn({
      metric: 'NAAIM_EXPOSURE',
      error: naaim.status === 'rejected' ? serializeError(naaim.reason) : naaimVal?.error,
    }, 'sentiment metric unavailable');
  }

  log.info({
    durationMs: Date.now() - startedAt,
    returnedKeys: Object.keys(out),
  }, 'sentiment collection completed');

  return out;
}
