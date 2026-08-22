import axios from 'axios';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const log = childLogger({ module: 'collector.crypto-market' });

const GLOBAL_CACHE_KEY = 'crypto-global-market';
const MARKETS_CACHE_KEY = 'crypto-major-markets';
const TTL_MS = 30 * 60 * 1000;

export interface CryptoGlobalMarketSnapshot {
  totalMarketCapUsd: number | null;
  totalVolumeUsd: number | null;
  btcDominancePct: number | null;
  ethDominancePct: number | null;
  marketCapChange24hPct: number | null;
}

export interface CryptoCoinMarketRow {
  id: string;
  symbol: string;
  marketCapUsd: number | null;
  marketCapRank: number | null;
  totalVolumeUsd: number | null;
  priceChange24hPct: number | null;
  circulatingSupply: number | null;
  totalSupply: number | null;
}

async function readFresh<T>(key: string) {
  return readSourceCacheWithin<T>(key, TTL_MS);
}

export async function fetchCryptoGlobalMarket(): Promise<CryptoGlobalMarketSnapshot> {
  const cached = await readFresh<CryptoGlobalMarketSnapshot>(GLOBAL_CACHE_KEY);
  if (cached?.value) return cached.value;

  try {
    const { data } = await axios.get('https://api.coingecko.com/api/v3/global', {
      headers: { Accept: 'application/json' },
      timeout: 10000,
    });

    const payload: CryptoGlobalMarketSnapshot = {
      totalMarketCapUsd: data?.data?.total_market_cap?.usd ?? null,
      totalVolumeUsd: data?.data?.total_volume?.usd ?? null,
      btcDominancePct: data?.data?.market_cap_percentage?.btc ?? null,
      ethDominancePct: data?.data?.market_cap_percentage?.eth ?? null,
      marketCapChange24hPct: data?.data?.market_cap_change_percentage_24h_usd ?? null,
    };

    await writeSourceCache(GLOBAL_CACHE_KEY, payload, { source: 'coingecko' });
    return payload;
  } catch (error) {
    log.warn({ err: serializeError(error) }, 'crypto global market fetch failed');
    const stale = await readSourceCacheWithin<CryptoGlobalMarketSnapshot>(GLOBAL_CACHE_KEY, 7 * 24 * 60 * 60 * 1000);
    if (stale?.value) return stale.value;
    return {
      totalMarketCapUsd: null,
      totalVolumeUsd: null,
      btcDominancePct: null,
      ethDominancePct: null,
      marketCapChange24hPct: null,
    };
  }
}

export async function fetchCryptoCoinMarkets(ids: string[]): Promise<Record<string, CryptoCoinMarketRow>> {
  const normalizedIds = Array.from(new Set(ids.filter(Boolean))).sort();
  const cacheKey = `${MARKETS_CACHE_KEY}-${normalizedIds.join('-')}`;
  const cached = await readFresh<Record<string, CryptoCoinMarketRow>>(cacheKey);
  if (cached?.value) return cached.value;

  try {
    const { data } = await axios.get('https://api.coingecko.com/api/v3/coins/markets', {
      params: {
        vs_currency: 'usd',
        ids: normalizedIds.join(','),
        order: 'market_cap_desc',
        per_page: normalizedIds.length,
        page: 1,
        sparkline: false,
        price_change_percentage: '24h',
      },
      headers: { Accept: 'application/json' },
      timeout: 12000,
    });

    const rows = Array.isArray(data) ? data : [];
    const mapped = Object.fromEntries(
      rows.map((row: any) => [
        String(row.id),
        {
          id: String(row.id),
          symbol: String(row.symbol || '').toUpperCase(),
          marketCapUsd: typeof row.market_cap === 'number' ? row.market_cap : null,
          marketCapRank: typeof row.market_cap_rank === 'number' ? row.market_cap_rank : null,
          totalVolumeUsd: typeof row.total_volume === 'number' ? row.total_volume : null,
          priceChange24hPct: typeof row.price_change_percentage_24h_in_currency === 'number'
            ? row.price_change_percentage_24h_in_currency
            : typeof row.price_change_percentage_24h === 'number'
              ? row.price_change_percentage_24h
              : null,
          circulatingSupply: typeof row.circulating_supply === 'number' ? row.circulating_supply : null,
          totalSupply: typeof row.total_supply === 'number' ? row.total_supply : null,
        } satisfies CryptoCoinMarketRow,
      ]),
    );

    await writeSourceCache(cacheKey, mapped, { source: 'coingecko', ids: normalizedIds });
    return mapped;
  } catch (error) {
    log.warn({ ids: normalizedIds, err: serializeError(error) }, 'crypto coin markets fetch failed');
    const stale = await readSourceCacheWithin<Record<string, CryptoCoinMarketRow>>(cacheKey, 7 * 24 * 60 * 60 * 1000);
    return stale?.value ?? {};
  }
}
