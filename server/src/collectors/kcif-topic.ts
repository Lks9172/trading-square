/**
 * KCIF 국제금융센터 부정기 보고서 최신 토픽 (19차 P3#16).
 *
 * 노션 §"KCIF: 채권 금리 동향". 부정기 발행 패턴 자체가 위기 신호 (스태그/사모대출 등 트리거).
 * 페이지 HTML 에서 첫 번째 보고서 제목 + 발행일만 추출. fresh 6h / stale 7d.
 */

import axios from 'axios';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const log = childLogger({ module: 'collector.kcif-topic' });
const CACHE_KEY = 'kcif-latest-topic';
const FRESH_MS = 6 * 60 * 60 * 1000;
const STALE_MS = 7 * 24 * 60 * 60 * 1000;

export interface KcifTopic {
  title: string;
  publishedDate: string;
  daysAgo: number;
  fetchedAt: string;
}

export async function fetchKcifLatestTopic(): Promise<KcifTopic | null> {
  const cached = await readSourceCacheWithin<KcifTopic>(CACHE_KEY, FRESH_MS);
  if (cached) return cached.value;
  try {
    const { data: html } = await axios.get<string>(
      'https://www.kcif.or.kr/finance/bondList',
      {
        headers: {
          'User-Agent': 'Mozilla/5.0',
          'Accept-Language': 'ko-KR,ko;q=0.9',
        },
        timeout: 10000,
      },
    );
    // 보고서 리스트의 첫 번째 항목 추출 — KCIF 는 클래스명/구조가 자주 바뀌므로 광범위 정규식.
    // 날짜 패턴 YYYY-MM-DD 또는 YYYY.MM.DD
    const dateMatch = html.match(/(\d{4})[.\-](\d{2})[.\-](\d{2})/);
    if (!dateMatch) return null;
    const isoDate = `${dateMatch[1]}-${dateMatch[2]}-${dateMatch[3]}`;
    // 제목: 첫 번째 a 태그 텍스트 또는 strong/h3
    const titleMatch =
      html.match(/<a[^>]*class="[^"]*tit[^"]*"[^>]*>([\s\S]*?)<\/a>/i) ||
      html.match(/<strong[^>]*>([\s\S]*?)<\/strong>/i) ||
      html.match(/<h3[^>]*>([\s\S]*?)<\/h3>/i);
    const title = titleMatch ? titleMatch[1].replace(/<[^>]+>/g, '').trim() : '(제목 미상)';
    const daysAgo = Math.floor((Date.now() - new Date(isoDate).getTime()) / 86400000);
    const result: KcifTopic = {
      title: title.slice(0, 120),
      publishedDate: isoDate,
      daysAgo,
      fetchedAt: new Date().toISOString(),
    };
    await writeSourceCache(CACHE_KEY, result);
    log.info({ title: result.title, daysAgo }, 'KCIF live');
    return result;
  } catch (err) {
    log.warn({ error: serializeError(err) }, 'KCIF fetch failed');
    const stale = await readSourceCacheWithin<KcifTopic>(CACHE_KEY, STALE_MS);
    return stale?.value ?? null;
  }
}
