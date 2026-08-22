import axios from 'axios';
import { NarrativeExternalSignal, NarrativeThemeDefinition } from '../../types/narrative';
import { readSourceCacheWithin, writeSourceCache } from '../../services/source-cache';
import { childLogger, serializeError } from '../../services/logger';

const log = childLogger({ module: 'collector.narrative-external' });
const TTL_MS = 6 * 60 * 60 * 1000;

function cacheKey(themeId: string) {
  return `narrative_external_v2_${themeId}`;
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function scoreNewsCount(value: number | null): number {
  if (value === null) return 4;
  if (value >= 80) return 9;
  if (value >= 35) return 7;
  if (value >= 15) return 5.5;
  return 3.5;
}

function scoreYoutubeCount(value: number | null): number {
  if (value === null) return 4;
  if (value >= 1000) return 9;
  if (value >= 300) return 7;
  if (value >= 80) return 5.5;
  return 3.5;
}

function parseGoogleNewsItems(xml: string): { totalItems: number; last7d: number; last30d: number } {
  const items = [...xml.matchAll(/<item>([\s\S]*?)<\/item>/g)].map((m) => m[1]);
  const now = Date.now();
  let last7d = 0;
  let last30d = 0;
  for (const item of items) {
    const pub = item.match(/<pubDate>(.*?)<\/pubDate>/i)?.[1];
    if (!pub) continue;
    const ts = new Date(pub).getTime();
    if (!Number.isFinite(ts)) continue;
    const ageDays = (now - ts) / 86400000;
    if (ageDays <= 30) last30d += 1;
    if (ageDays <= 7) last7d += 1;
  }
  return { totalItems: items.length, last7d, last30d };
}

async function fetchGoogleNewsSignal(query: string): Promise<NarrativeExternalSignal> {
  const url = `https://news.google.com/rss/search?q=${encodeURIComponent(query)}&hl=en-US&gl=US&ceid=US:en`;
  const response = await axios.get<string>(url, { timeout: 15000, responseType: 'text' });
  const parsed = parseGoogleNewsItems(response.data);
  const score = scoreNewsCount(parsed.last7d);
  return {
    key: 'GOOGLE_NEWS_7D',
    label: 'Google News 7D',
    value: parsed.last7d,
    score,
    detail: `7D ${parsed.last7d}건 / 30D ${parsed.last30d}건 / feed ${parsed.totalItems}건`,
  };
}

function parseYouTubeSearchHtml(html: string): { estimatedResults: number | null; renderedVideos: number } {
  const estimatedRaw = html.match(/"estimatedResults":"(\d+)"/i)?.[1] ?? null;
  const estimatedResults = estimatedRaw ? Number(estimatedRaw) : null;
  const renderedVideos = (html.match(/"videoRenderer"/g) ?? []).length;
  return {
    estimatedResults: Number.isFinite(estimatedResults) ? estimatedResults : null,
    renderedVideos,
  };
}

async function fetchYouTubeSignal(query: string): Promise<NarrativeExternalSignal> {
  const apiKey = process.env.YOUTUBE_API_KEY || process.env.GOOGLE_API_KEY || '';
  if (apiKey) {
    const publishedAfter = new Date(Date.now() - 30 * 86400000).toISOString();
    const response = await axios.get('https://www.googleapis.com/youtube/v3/search', {
      timeout: 15000,
      params: {
        part: 'snippet',
        type: 'video',
        maxResults: 25,
        q: query,
        publishedAfter,
        order: 'date',
        key: apiKey,
      },
    });
    const totalResults = Number(response.data?.pageInfo?.totalResults ?? 0);
    return {
      key: 'YOUTUBE_30D',
      label: 'YouTube 30D',
      value: totalResults,
      score: scoreYoutubeCount(totalResults),
      detail: `30D 검색 결과 ${totalResults}건 (API)`,
    };
  }

  const response = await axios.get<string>('https://www.youtube.com/results', {
    timeout: 15000,
    responseType: 'text',
    headers: {
      'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36',
      'Accept-Language': 'en-US,en;q=0.9',
    },
    params: {
      search_query: query,
      sp: 'EgIQAQ%3D%3D',
    },
  });

  const parsed = parseYouTubeSearchHtml(response.data);
  const proxyValue = parsed.estimatedResults ?? (parsed.renderedVideos > 0 ? parsed.renderedVideos : null);
  return {
    key: 'YOUTUBE_30D',
    label: 'YouTube Search',
    value: proxyValue,
    score: scoreYoutubeCount(proxyValue),
    detail: parsed.estimatedResults !== null
      ? `검색 추정 ${parsed.estimatedResults}건 / page videoRenderer ${parsed.renderedVideos}건 (keyless HTML proxy)`
      : `page videoRenderer ${parsed.renderedVideos}건 (keyless HTML proxy)`,
  };
}

export async function fetchNarrativeExternalSignals(theme: NarrativeThemeDefinition): Promise<NarrativeExternalSignal[]> {
  const cached = await readSourceCacheWithin<NarrativeExternalSignal[]>(cacheKey(theme.id), TTL_MS);
  if (cached?.value?.length) return cached.value;

  const results: NarrativeExternalSignal[] = [];
  try {
    if (theme.externalQueries?.newsQuery) {
      results.push(await fetchGoogleNewsSignal(theme.externalQueries.newsQuery));
    }
  } catch (error) {
    log.warn({ theme: theme.id, error: serializeError(error) }, 'google news signal fetch failed');
    results.push({ key: 'GOOGLE_NEWS_7D', label: 'Google News 7D', value: null, score: 4, detail: '뉴스 프록시 fetch 실패' });
  }

  try {
    if (theme.externalQueries?.youtubeQuery) {
      results.push(await fetchYouTubeSignal(theme.externalQueries.youtubeQuery));
    }
  } catch (error) {
    log.warn({ theme: theme.id, error: serializeError(error) }, 'youtube signal fetch failed');
    results.push({ key: 'YOUTUBE_30D', label: 'YouTube 30D', value: null, score: 4, detail: '유튜브 프록시 fetch 실패' });
  }

  const normalized = results.map((item) => ({ ...item, score: clamp(item.score, 0, 10) }));
  await writeSourceCache(cacheKey(theme.id), normalized, { ttlMs: TTL_MS, themeId: theme.id });
  return normalized;
}
