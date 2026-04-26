/**
 * OpenInsider collector — 29차 P2-E #35.
 *
 * URL: http://openinsider.com/insider-cluster-purchases (HTML scrape)
 *
 * 기능:
 *  - cluster purchase (2+ insiders 동일 ticker 매수) 종목 수집
 *  - $50K 미만 거래 필터링
 *  - 30일 윈도우
 *
 * fetch 실패 시:
 *  - cache fallback (24h fresh / 7d stale)
 *  - 모든 fallback 실패 시 null 반환 (호출부에서 silent 처리)
 */
import axios from 'axios';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const log = childLogger({ module: 'collector.openinsider' });
const CACHE_KEY = 'openinsider-cluster-purchases';
const FRESH_MS = 24 * 60 * 60 * 1000;       // 24h fresh
const STALE_MS = 7 * 24 * 60 * 60 * 1000;   // 7d stale fallback

const URL = 'http://openinsider.com/insider-cluster-purchases';
const MIN_TX_USD = 50_000;                  // $50K 컷오프

export interface OpenInsiderClusterRecord {
  ticker: string;
  insiderCount: number;
  totalTxUsd: number;
  lastDate: string;
}

export interface OpenInsiderSnapshot {
  records: OpenInsiderClusterRecord[];
  totalTickers: number;
  totalUsd: number;
  fetchedAt: string;
}

/**
 * HTML 테이블 파싱 — openinsider.com 구조: tr 별 ticker + transaction value.
 * 형식 변경 가능성 있어 best-effort. 실패 시 빈 배열.
 */
function parseHtml(html: string): OpenInsiderClusterRecord[] {
  const records: Map<string, OpenInsiderClusterRecord> = new Map();
  // 정규식 기반 — `<tr>` 안에 `<td>` 셀들. ticker, txValue, date 추출.
  const trRegex = /<tr[^>]*>([\s\S]*?)<\/tr>/g;
  const tdRegex = /<td[^>]*>([\s\S]*?)<\/td>/g;
  let trMatch: RegExpExecArray | null;
  while ((trMatch = trRegex.exec(html)) !== null) {
    const trInner = trMatch[1];
    const cells: string[] = [];
    let tdMatch: RegExpExecArray | null;
    while ((tdMatch = tdRegex.exec(trInner)) !== null) {
      // 태그 제거 + 트림
      const text = tdMatch[1].replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim();
      cells.push(text);
    }
    if (cells.length < 8) continue;
    // 통상: [filing-date, trade-date, ticker, insider-name, title, trade-type, price, qty, owned, owned_delta, value]
    // openinsider 의 cluster-purchases 구조는 cells[2] = ticker, cells 마지막 근방 = value ($).
    const ticker = cells[2]?.toUpperCase().replace(/[^A-Z]/g, '');
    if (!ticker || ticker.length < 1 || ticker.length > 6) continue;
    // value: '$' 접두 + ',' 분리. 마지막 셀들에서 검색
    let valueUsd = 0;
    for (let ci = cells.length - 1; ci >= 0; ci--) {
      const cell = cells[ci];
      const m = cell.match(/^\$?\s*(-?[\d,]+(?:\.\d+)?)/);
      if (m) {
        const n = parseFloat(m[1].replace(/,/g, ''));
        if (Number.isFinite(n) && n > 1000) {
          valueUsd = n;
          break;
        }
      }
    }
    if (valueUsd < MIN_TX_USD) continue;
    const tradeDate = cells[1] || cells[0] || '';
    const existing = records.get(ticker);
    if (existing) {
      existing.insiderCount += 1;
      existing.totalTxUsd += valueUsd;
      if (tradeDate > existing.lastDate) existing.lastDate = tradeDate;
    } else {
      records.set(ticker, { ticker, insiderCount: 1, totalTxUsd: valueUsd, lastDate: tradeDate });
    }
  }
  // cluster ≥ 2 만 유지
  return Array.from(records.values()).filter((r) => r.insiderCount >= 2);
}

export async function fetchOpenInsiderClusterPurchases(): Promise<OpenInsiderSnapshot | null> {
  const cached = await readSourceCacheWithin<OpenInsiderSnapshot>(CACHE_KEY, FRESH_MS);
  if (cached) {
    log.info({ ageMs: cached.ageMs, totalTickers: cached.value.totalTickers }, 'openinsider cache hit');
    return cached.value;
  }
  try {
    const { data } = await axios.get<string>(URL, {
      headers: { 'User-Agent': 'Mozilla/5.0' },
      timeout: 15000,
      responseType: 'text',
    });
    if (typeof data !== 'string' || data.length < 1000) {
      throw new Error('openinsider response too small');
    }
    const records = parseHtml(data);
    const totalUsd = records.reduce((s, r) => s + r.totalTxUsd, 0);
    const snapshot: OpenInsiderSnapshot = {
      records,
      totalTickers: records.length,
      totalUsd,
      fetchedAt: new Date().toISOString(),
    };
    await writeSourceCache(CACHE_KEY, snapshot);
    log.info({ totalTickers: snapshot.totalTickers, totalUsd: snapshot.totalUsd }, 'openinsider fetched');
    return snapshot;
  } catch (error) {
    log.warn({ error: serializeError(error) }, 'openinsider fetch failed, trying stale cache');
    const stale = await readSourceCacheWithin<OpenInsiderSnapshot>(CACHE_KEY, STALE_MS);
    if (stale) {
      log.info({ ageMs: stale.ageMs }, 'openinsider stale fallback');
      return stale.value;
    }
    return null;
  }
}
