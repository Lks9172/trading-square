/**
 * 스테이블코인 총 시가총액 수집 — video4 §달러 패권 "스테이블 코인으로 채권 수요까지"
 *
 * USDT/USDC 등 스테이블코인 발행액 = T-Bill 수요 프록시.
 * 영상4 "달러 패권의 디지털 버전" 논리상 스테이블 총량 증가 = 국채 수요 증가 = 금리
 * 하방 압력. 채권 자경단 분석에 보조 지표.
 *
 * Source: DeFi Llama stablecoins API (무료, 공개).
 */

import axios from 'axios';
import { MarketDataPoint } from '../types/indicators';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const log = childLogger({ module: 'collector.stablecoin' });
const DEFILLAMA_URL = 'https://stablecoins.llama.fi/stablecoins';
const DEFILLAMA_HISTORY_URL = 'https://stablecoins.llama.fi/stablecoincharts/all';
const CACHE_KEY = 'stablecoin-mcap';
const HISTORY_CACHE_KEY = 'stablecoin-mcap-history';
const FRESH_MS = 6 * 60 * 60 * 1000; // 6시간
const STALE_MS = 7 * 24 * 60 * 60 * 1000; // 7일

interface StablecoinEntry {
  name?: string;
  circulating?: { peggedUSD?: number };
}

export interface StablecoinHistoryPoint {
  date: string;
  marketCapBillions: number;
}

async function fetchLive(): Promise<MarketDataPoint | null> {
  try {
    const { data } = await axios.get<{ peggedAssets?: StablecoinEntry[] }>(DEFILLAMA_URL, {
      timeout: 15000,
      headers: { Accept: 'application/json' },
    });
    const list = data?.peggedAssets;
    if (!Array.isArray(list) || list.length === 0) return null;
    // USD-pegged 스테이블코인 합산 (billions of USD 단위로 환산)
    let totalUsd = 0;
    for (const item of list) {
      const usd = item?.circulating?.peggedUSD;
      if (typeof usd === 'number' && Number.isFinite(usd) && usd > 0) {
        totalUsd += usd;
      }
    }
    if (totalUsd <= 0) return null;
    const billions = totalUsd / 1_000_000_000;
    return {
      code: 'STABLECOIN_MCAP',
      value: parseFloat(billions.toFixed(2)),
      date: new Date().toISOString().split('T')[0],
      source: 'CALC', // DEFILLAMA (타입상 제약으로 CALC 사용)
    };
  } catch (err) {
    log.warn({ err: serializeError(err) }, 'stablecoin live fetch failed');
    return null;
  }
}

export async function fetchStablecoinMcap(): Promise<MarketDataPoint | null> {
  const cached = await readSourceCacheWithin<MarketDataPoint>(CACHE_KEY, FRESH_MS);
  if (cached) {
    log.info({ ageHours: Math.round(cached.ageMs / 3600000), value: cached.value.value }, 'stablecoin cache hit');
    return cached.value;
  }
  const live = await fetchLive();
  if (live) {
    await writeSourceCache(CACHE_KEY, live, { source: 'defillama' });
    log.info({ value: live.value }, 'stablecoin live collected (B USD)');
    return live;
  }
  const stale = await readSourceCacheWithin<MarketDataPoint>(CACHE_KEY, STALE_MS);
  if (stale) {
    log.warn({ ageMs: stale.ageMs }, 'stablecoin live failed, serving stale cache');
    return stale.value;
  }
  return null;
}

export async function fetchStablecoinMcapHistory(days = 30): Promise<StablecoinHistoryPoint[]> {
  const fresh = await readSourceCacheWithin<StablecoinHistoryPoint[]>(HISTORY_CACHE_KEY, FRESH_MS);
  if (fresh?.value?.length) return fresh.value.slice(-days);

  try {
    const { data } = await axios.get<Array<Record<string, unknown>>>(DEFILLAMA_HISTORY_URL, {
      timeout: 15000,
      headers: { Accept: 'application/json' },
    });
    const rows = Array.isArray(data) ? data : [];
    const mapped = rows
      .map((row) => {
        const ts = Number(row.date ?? 0);
        const totalCirculatingUsd = Number((row.totalCirculatingUSD as Record<string, unknown> | undefined)?.peggedUSD ?? 0);
        if (!ts || !Number.isFinite(totalCirculatingUsd) || totalCirculatingUsd <= 0) return null;
        return {
          date: new Date(ts * 1000).toISOString().split('T')[0],
          marketCapBillions: Number((totalCirculatingUsd / 1_000_000_000).toFixed(2)),
        } satisfies StablecoinHistoryPoint;
      })
      .filter((row): row is StablecoinHistoryPoint => Boolean(row));
    if (mapped.length) {
      await writeSourceCache(HISTORY_CACHE_KEY, mapped, { source: 'defillama' });
      return mapped.slice(-days);
    }
  } catch (err) {
    log.warn({ err: serializeError(err) }, 'stablecoin history fetch failed');
  }

  const stale = await readSourceCacheWithin<StablecoinHistoryPoint[]>(HISTORY_CACHE_KEY, STALE_MS);
  return stale?.value?.slice(-days) ?? [];
}
