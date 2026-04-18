import axios from 'axios';
import { MarketDataPoint } from '../types/indicators';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const CNN_FNG_URL = 'https://production.dataviz.cnn.io/index/fearandgreed/graphdata';
const ALT_FNG_URL = 'https://api.alternative.me/fng/?limit=1';
const log = childLogger({ module: 'collector.cnn' });
const FEAR_GREED_CACHE_KEY = 'fear-greed-latest';
const FEAR_GREED_STALE_MS = 14 * 24 * 60 * 60 * 1000;

async function fetchFromCNN(): Promise<MarketDataPoint | null> {
  const { data } = await axios.get(CNN_FNG_URL, {
    headers: {
      'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
      'Referer': 'https://edition.cnn.com/markets/fear-and-greed',
      'Accept': 'application/json',
    },
    timeout: 10000,
  });

  const fg = data?.fear_and_greed;
  if (!fg?.score) return null;

  return {
    code: 'FEAR_GREED',
    value: Math.round(fg.score),
    date: new Date(fg.timestamp).toISOString().split('T')[0],
    source: 'CNN',
  };
}

async function fetchFromAlternative(): Promise<MarketDataPoint | null> {
  const { data } = await axios.get(ALT_FNG_URL, { timeout: 10000 });
  const entry = data?.data?.[0];
  if (!entry?.value) return null;

  return {
    code: 'FEAR_GREED',
    value: parseInt(entry.value, 10),
    date: new Date(parseInt(entry.timestamp, 10) * 1000).toISOString().split('T')[0],
    source: 'CNN',
  };
}

export async function fetchFearAndGreed(): Promise<MarketDataPoint | null> {
  // Primary: CNN
  let primary: MarketDataPoint | null = null;
  let cnnError: unknown = null;
  try {
    primary = await fetchFromCNN();
  } catch (error) {
    cnnError = error;
    log.warn({ source: 'cnn', error: serializeError(error) }, 'cnn fear and greed fetch threw');
  }
  if (primary) {
    await writeSourceCache(FEAR_GREED_CACHE_KEY, primary, { source: 'cnn' });
    log.info({ source: 'cnn', value: primary.value, date: primary.date }, 'fear and greed collected');
    return primary;
  }
  // 12차 잔여 이슈 fix (2026-04): CNN 이 에러 없이 200 + 빈 데이터 반환하는 케이스에서도
  //   fallback 으로 넘어가도록 분기 재구성 (기존은 throw 시에만 fallback 진입).
  log.warn({ source: 'cnn' }, 'cnn fear and greed returned null — trying alternative.me fallback');

  // Fallback: alternative.me
  let altError: unknown = null;
  try {
    const alt = await fetchFromAlternative();
    if (alt) {
      await writeSourceCache(FEAR_GREED_CACHE_KEY, alt, { source: 'alternative' });
      log.info({ source: 'alternative', value: alt.value, date: alt.date }, 'fear and greed fallback collected');
      return alt;
    }
    log.warn({ source: 'alternative' }, 'alternative.me returned null');
  } catch (error) {
    altError = error;
    log.warn({ source: 'alternative', error: serializeError(error) }, 'alternative.me fetch threw');
  }

  // Stale cache (14d)
  const cached = await readSourceCacheWithin<MarketDataPoint>(FEAR_GREED_CACHE_KEY, FEAR_GREED_STALE_MS);
  if (cached) {
    log.warn({
      ageMs: cached.ageMs,
      updatedAt: cached.updatedAt,
      cnnError: cnnError ? serializeError(cnnError) : 'returned null',
      altError: altError ? serializeError(altError) : 'returned null',
    }, 'fear and greed live fetch failed, serving stale cache');
    return cached.value;
  }
  log.error({
    cnnError: cnnError ? serializeError(cnnError) : 'returned null',
    altError: altError ? serializeError(altError) : 'returned null',
  }, 'fear and greed fetch failed on all sources');
  return null;
}
