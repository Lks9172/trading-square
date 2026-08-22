import axios from 'axios';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const log = childLogger({ module: 'collector.crypto-fundamentals' });
const TTL_MS = 6 * 60 * 60 * 1000;
const STALE_MS = 7 * 24 * 60 * 60 * 1000;

export interface CryptoCoinDetail {
  marketCapUsd: number | null;
  fdvUsd: number | null;
  circulatingSupply: number | null;
  totalSupply: number | null;
  maxSupply: number | null;
  developerScore: number | null;
  communityScore: number | null;
}

function clampScore(value: number) {
  return Math.max(0, Math.min(100, Number(value.toFixed(1))));
}

function deriveDeveloperScore(data: any): number | null {
  const stars = Number(data?.developer_data?.stars ?? 0);
  const forks = Number(data?.developer_data?.forks ?? 0);
  const subscribers = Number(data?.developer_data?.subscribers ?? 0);
  const commits = Number(data?.developer_data?.commit_count_4_weeks ?? 0);
  const score = Math.log10(1 + stars) * 10 + Math.log10(1 + forks) * 9 + Math.log10(1 + subscribers) * 7 + Math.log10(1 + commits) * 14;
  return score > 0 ? clampScore(score) : null;
}

function deriveCommunityScore(data: any): number | null {
  const twitter = Number(data?.community_data?.twitter_followers ?? 0);
  const reddit = Number(data?.community_data?.reddit_subscribers ?? 0);
  const telegram = Number(data?.community_data?.telegram_channel_user_count ?? 0);
  const score = Math.log10(1 + twitter) * 12 + Math.log10(1 + reddit) * 10 + Math.log10(1 + telegram) * 8;
  return score > 0 ? clampScore(score) : null;
}

export interface CryptoChainMetrics {
  tvlUsd: number | null;
  tvlTrend30dPct: number | null;
  fees30dAvgUsd: number | null;
  feesTrend30dPct: number | null;
}

async function readFresh<T>(key: string) {
  return readSourceCacheWithin<T>(key, TTL_MS);
}

export async function fetchCryptoCoinDetail(coingeckoId: string): Promise<CryptoCoinDetail | null> {
  const key = `crypto-coin-detail-${coingeckoId}`;
  const fresh = await readFresh<CryptoCoinDetail>(key);
  if (fresh?.value) return fresh.value;
  try {
    const { data } = await axios.get(`https://api.coingecko.com/api/v3/coins/${encodeURIComponent(coingeckoId)}`, {
      params: {
        localization: false,
        tickers: false,
        market_data: true,
        community_data: true,
        developer_data: true,
        sparkline: false,
      },
      timeout: 15000,
      headers: { Accept: 'application/json', 'User-Agent': 'Mozilla/5.0' },
    });
    const payload: CryptoCoinDetail = {
      marketCapUsd: typeof data?.market_data?.market_cap?.usd === 'number' ? data.market_data.market_cap.usd : null,
      fdvUsd: typeof data?.market_data?.fully_diluted_valuation?.usd === 'number' ? data.market_data.fully_diluted_valuation.usd : null,
      circulatingSupply: typeof data?.market_data?.circulating_supply === 'number' ? data.market_data.circulating_supply : null,
      totalSupply: typeof data?.market_data?.total_supply === 'number' ? data.market_data.total_supply : null,
      maxSupply: typeof data?.market_data?.max_supply === 'number' ? data.market_data.max_supply : null,
      developerScore: typeof data?.developer_score === 'number' && Number.isFinite(data.developer_score)
        ? data.developer_score
        : deriveDeveloperScore(data),
      communityScore: typeof data?.community_score === 'number' && Number.isFinite(data.community_score)
        ? data.community_score
        : deriveCommunityScore(data),
    };
    await writeSourceCache(key, payload, { source: 'coingecko', id: coingeckoId });
    return payload;
  } catch (error) {
    log.warn({ coingeckoId, err: serializeError(error) }, 'crypto coin detail fetch failed');
    const stale = await readSourceCacheWithin<CryptoCoinDetail>(key, STALE_MS);
    return stale?.value ?? null;
  }
}

export async function fetchCryptoChainMetrics(llamaChainSlug: string): Promise<CryptoChainMetrics | null> {
  const key = `crypto-chain-metrics-${llamaChainSlug}`;
  const fresh = await readFresh<CryptoChainMetrics>(key);
  if (fresh?.value) return fresh.value;
  try {
    const historicalSlugMap: Record<string, string> = {
      bitcoin: 'Bitcoin',
      ethereum: 'Ethereum',
      solana: 'Solana',
      bsc: 'BSC',
      ripple: 'Ripple',
    };
    const tvlHistoryUrl = `https://api.llama.fi/v2/historicalChainTvl/${encodeURIComponent(historicalSlugMap[llamaChainSlug] ?? llamaChainSlug)}`;
    const [chainsResp, feesResp, tvlResp] = await Promise.all([
      axios.get('https://api.llama.fi/chains', {
        timeout: 15000,
        headers: { Accept: 'application/json', 'User-Agent': 'Mozilla/5.0' },
      }),
      axios.get(`https://api.llama.fi/overview/fees/${encodeURIComponent(llamaChainSlug)}?dataType=dailyFees`, {
        timeout: 15000,
        headers: { Accept: 'application/json', 'User-Agent': 'Mozilla/5.0' },
      }),
      axios.get(tvlHistoryUrl, {
        timeout: 15000,
        headers: { Accept: 'application/json', 'User-Agent': 'Mozilla/5.0' },
      }).catch(() => ({ data: [] })),
    ]);

    const chains = Array.isArray(chainsResp.data) ? chainsResp.data : [];
    const aliases: Record<string, string[]> = {
      bitcoin: ['bitcoin', 'btc'],
      ethereum: ['ethereum', 'eth'],
      solana: ['solana', 'sol'],
      bsc: ['bsc', 'bnb chain', 'binance smart chain', 'binance coin', 'binancecoin'],
      ripple: ['ripple', 'xrp', 'xrpl'],
    };
    const candidates = new Set([llamaChainSlug.toLowerCase(), ...(aliases[llamaChainSlug] ?? [])]);
    const chain = chains.find((row: any) => [row.name, row.tokenSymbol, row.gecko_id].some((value: any) => candidates.has(String(value || '').toLowerCase())));
    const currentTvl = typeof chain?.tvl === 'number' ? chain.tvl : null;

    const feeSeries = Array.isArray(feesResp.data?.totalDataChart) ? feesResp.data.totalDataChart : [];
    const recentFees = feeSeries.slice(-30).map((row: any[]) => Number(row?.[1] ?? 0)).filter((v: number) => Number.isFinite(v) && v >= 0);
    const prevFees = feeSeries.slice(-60, -30).map((row: any[]) => Number(row?.[1] ?? 0)).filter((v: number) => Number.isFinite(v) && v >= 0);
    const recentAvg = recentFees.length ? recentFees.reduce((a: number, b: number) => a + b, 0) / recentFees.length : null;
    const prevAvg = prevFees.length ? prevFees.reduce((a: number, b: number) => a + b, 0) / prevFees.length : null;
    const feesTrend30dPct = recentAvg !== null && prevAvg && prevAvg > 0 ? Number((((recentAvg - prevAvg) / prevAvg) * 100).toFixed(1)) : null;

    const tvlSeries = Array.isArray(tvlResp.data) ? tvlResp.data : [];
    const currentTvlPoint = tvlSeries.length ? Number(tvlSeries[tvlSeries.length - 1]?.tvl ?? 0) : currentTvl;
    const prevTvlPoint = tvlSeries.length > 30 ? Number(tvlSeries[tvlSeries.length - 31]?.tvl ?? 0) : null;
    const tvlTrend30dPct = currentTvlPoint !== null && prevTvlPoint && prevTvlPoint > 0
      ? Number((((currentTvlPoint - prevTvlPoint) / prevTvlPoint) * 100).toFixed(1))
      : null;

    const payload: CryptoChainMetrics = {
      tvlUsd: currentTvl,
      tvlTrend30dPct,
      fees30dAvgUsd: recentAvg === null ? null : Number(recentAvg.toFixed(0)),
      feesTrend30dPct,
    };
    await writeSourceCache(key, payload, { source: 'defillama', slug: llamaChainSlug });
    return payload;
  } catch (error) {
    log.warn({ llamaChainSlug, err: serializeError(error) }, 'crypto chain metrics fetch failed');
    const stale = await readSourceCacheWithin<CryptoChainMetrics>(key, STALE_MS);
    return stale?.value ?? null;
  }
}
