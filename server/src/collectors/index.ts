import { fetchAllFred } from './fred';
import { fetchAllYahoo } from './yahoo';
import { fetchFearAndGreed } from './cnn';
import { fetchAllSentiment } from './sentiment';
import { fetchStablecoinMcap } from './stablecoin';
import { MarketDataPoint } from '../types/indicators';
import { withSpan, currentSpan } from '../observability/trace';
import { childLogger, serializeError } from '../services/logger';

const log = childLogger({ module: 'collector.index' });

export async function collectAll(fredApiKey: string): Promise<Record<string, MarketDataPoint>> {
  return withSpan('macrosquare.collector.collectAll', async (span) => {
    const startedAt = Date.now();
    const [fred, yahoo, fng, sentiment, stablecoin] = await Promise.allSettled([
      withSpan('macrosquare.collector.fred.fetchAll', async (s) => {
        const v = await fetchAllFred(fredApiKey);
        const keys = Object.keys(v);
        s.setAttribute('fred.returned_keys', keys.length);
        return v;
      }),
      withSpan('macrosquare.collector.yahoo.fetchAll', async (s) => {
        const v = await fetchAllYahoo();
        s.setAttribute('yahoo.returned_keys', Object.keys(v).length);
        return v;
      }),
      withSpan('macrosquare.collector.cnn.fetchFearAndGreed', async (s) => {
        const v = await fetchFearAndGreed();
        s.setAttribute('cnn.fng.present', v !== null);
        return v;
      }),
      withSpan('macrosquare.collector.sentiment.fetchAll', async (s) => {
        const v = await fetchAllSentiment();
        s.setAttribute('sentiment.returned_keys', Object.keys(v).length);
        return v;
      }),
      // 15차 Phase 2-C (2026-04): 스테이블코인 시가총액 — video4 §달러 패권
      withSpan('macrosquare.collector.stablecoin.fetch', async (s) => {
        const v = await fetchStablecoinMcap();
        s.setAttribute('stablecoin.present', v !== null);
        return v;
      }),
    ]);

    const result: Record<string, MarketDataPoint> = {};

    if (fred.status === 'fulfilled') Object.assign(result, fred.value);
    if (yahoo.status === 'fulfilled') Object.assign(result, yahoo.value);
    if (fng.status === 'fulfilled' && fng.value) result.FEAR_GREED = fng.value;
    if (sentiment.status === 'fulfilled') Object.assign(result, sentiment.value);
    if (stablecoin.status === 'fulfilled' && stablecoin.value) result.STABLECOIN_MCAP = stablecoin.value;

    // 실패한 소스 집계를 부모 span 에 남긴다 — Jaeger 에서 한눈에 확인 가능.
    const failures: string[] = [];
    if (fred.status === 'rejected') failures.push('fred');
    if (yahoo.status === 'rejected') failures.push('yahoo');
    if (fng.status === 'rejected') failures.push('cnn');
    if (sentiment.status === 'rejected') failures.push('sentiment');
    if (stablecoin.status === 'rejected') failures.push('stablecoin');
    span.setAttribute('collector.failed_sources', failures.join(',') || 'none');
    span.setAttribute('collector.result_keys', Object.keys(result).length);
    span.setAttribute('collector.duration_ms', Date.now() - startedAt);

    if (fred.status === 'rejected') log.error({ source: 'fred', error: serializeError(fred.reason) }, 'collector source failed');
    if (yahoo.status === 'rejected') log.error({ source: 'yahoo', error: serializeError(yahoo.reason) }, 'collector source failed');
    if (fng.status === 'rejected') log.error({ source: 'cnn', error: serializeError(fng.reason) }, 'collector source failed');
    if (sentiment.status === 'rejected') log.error({ source: 'sentiment', error: serializeError(sentiment.reason) }, 'collector source failed');
    if (stablecoin.status === 'rejected') log.error({ source: 'stablecoin', error: serializeError(stablecoin.reason) }, 'collector source failed');

    log.info({
      durationMs: Date.now() - startedAt,
      resultKeys: Object.keys(result).length,
      failedSources: failures,
      sourceResults: {
        fred: fred.status === 'fulfilled' ? Object.keys(fred.value).length : 'failed',
        yahoo: yahoo.status === 'fulfilled' ? Object.keys(yahoo.value).length : 'failed',
        cnn: fng.status === 'fulfilled' ? (fng.value ? 'ok' : 'null') : 'failed',
        sentiment: sentiment.status === 'fulfilled' ? Object.keys(sentiment.value).length : 'failed',
      },
    }, 'collector cycle completed');

    return result;
  });
}

// 개별 수집기가 fallback 경로를 탈 때 현재 활성 span 에 경보를 기록할 수 있도록 export.
export function markCollectorFallback(source: string, key: string, reason: string) {
  const span = currentSpan();
  if (!span) return;
  span.addEvent('collector.fallback', { source, key, reason });
}
