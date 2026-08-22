import axios from 'axios';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const log = childLogger({ module: 'collector.crypto-etf' });

const CACHE_TTL_MS = 60 * 60 * 1000;
const STALE_MS = 7 * 24 * 60 * 60 * 1000;

export interface CryptoEtfFlowPoint {
  date: string;
  totalNetInflowUsd: number;
  totalValueTradedUsd: number | null;
  totalNetAssetsUsd: number | null;
  cumulativeNetInflowUsd: number | null;
}

function flowType(symbol: 'BTC' | 'ETH') {
  return symbol === 'BTC' ? 'us-btc-spot' : 'us-eth-spot';
}

async function fetchEtfFlowHistoryLive(symbol: 'BTC' | 'ETH'): Promise<CryptoEtfFlowPoint[]> {
  try {
    const { data } = await axios.post<{ data?: Array<Record<string, unknown>> }>(
      'https://open.sosovalue.xyz/openapi/v2/etf/historicalInflowChart',
      { type: flowType(symbol) },
      {
        timeout: 15000,
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
          'User-Agent': 'Mozilla/5.0',
        },
      },
    );
    const rows = Array.isArray(data?.data) ? data.data : [];
    return rows
      .map((row) => ({
        date: String(row.date || ''),
        totalNetInflowUsd: Number(row.totalNetInflow ?? 0),
        totalValueTradedUsd: row.totalValueTraded == null ? null : Number(row.totalValueTraded),
        totalNetAssetsUsd: row.totalNetAssets == null ? null : Number(row.totalNetAssets),
        cumulativeNetInflowUsd: row.cumNetInflow == null ? null : Number(row.cumNetInflow),
      }))
      .filter((row) => row.date && Number.isFinite(row.totalNetInflowUsd))
      .sort((a, b) => a.date.localeCompare(b.date));
  } catch (error) {
    log.warn({ symbol, err: serializeError(error) }, 'crypto etf flow history fetch failed');
    return [];
  }
}

export async function fetchCryptoEtfFlowHistory(symbol: 'BTC' | 'ETH'): Promise<CryptoEtfFlowPoint[]> {
  const key = `crypto-etf-history-${symbol.toLowerCase()}`;
  const fresh = await readSourceCacheWithin<CryptoEtfFlowPoint[]>(key, CACHE_TTL_MS);
  if (fresh?.value?.length) return fresh.value;

  const live = await fetchEtfFlowHistoryLive(symbol);
  if (live.length) {
    await writeSourceCache(key, live, { source: 'sosovalue', symbol });
    return live;
  }

  const stale = await readSourceCacheWithin<CryptoEtfFlowPoint[]>(key, STALE_MS);
  return stale?.value ?? [];
}
