/**
 * DART 주요공시 경량 수집 (18차 P2#12).
 *
 * 노션 "기업의 중대 이벤트" 정합. DART Open API 는 인증키 필요 — 키 없을 경우 null 반환.
 * 환경변수 DART_API_KEY 에 키 설정 시 활성화.
 *
 * 수집 범위: 최근 24시간 "주요사항보고서"(임시공시 A 유형) 건수.
 *   - 기준일은 오늘 + 어제 2일치
 *   - major 분류 = 부도/법정관리/유상증자/감자/합병/영업양수도 등
 *
 * 캐시: fresh 2h / stale 24h.
 */

import axios from 'axios';
import { childLogger, serializeError } from '../services/logger';
import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';

const log = childLogger({ module: 'collector.dart-major' });
const CACHE_KEY = 'dart-major-disclosures';
const FRESH_MS = 2 * 60 * 60 * 1000;
const STALE_MS = 24 * 60 * 60 * 1000;

export interface DartMajorSnapshot {
  count: number;                    // 24h 건수
  topCorps: Array<{ corpName: string; reportNm: string; rceptDt: string }>;
  fetchedAt: string;
  source: 'dart-api' | 'missing-key';
}

function fmtDate(d: Date): string {
  return d.toISOString().slice(0, 10).replace(/-/g, '');
}

async function fetchLive(): Promise<DartMajorSnapshot | null> {
  const key = process.env.DART_API_KEY;
  if (!key) {
    return {
      count: 0,
      topCorps: [],
      fetchedAt: new Date().toISOString(),
      source: 'missing-key',
    };
  }
  try {
    const now = new Date();
    const yesterday = new Date(now.getTime() - 86400000);
    const bgn = fmtDate(yesterday);
    const end = fmtDate(now);
    // pblntf_ty=A (주요사항보고서) — 중대 이벤트만
    const url = `https://opendart.fss.or.kr/api/list.json?crtfc_key=${encodeURIComponent(key)}&bgn_de=${bgn}&end_de=${end}&pblntf_ty=A&page_count=50`;
    const { data } = await axios.get<{ status: string; message: string; list?: Array<{ corp_name: string; report_nm: string; rcept_dt: string }> }>(url, { timeout: 10000 });
    if (data.status !== '000') {
      log.warn({ status: data.status, message: data.message }, 'DART api returned non-success');
      return null;
    }
    const list = data.list ?? [];
    return {
      count: list.length,
      topCorps: list.slice(0, 10).map((r) => ({
        corpName: r.corp_name,
        reportNm: r.report_nm,
        rceptDt: r.rcept_dt,
      })),
      fetchedAt: new Date().toISOString(),
      source: 'dart-api',
    };
  } catch (err) {
    log.warn({ error: serializeError(err) }, 'DART fetch failed');
    return null;
  }
}

export async function fetchDartMajorDisclosures(): Promise<DartMajorSnapshot | null> {
  const cached = await readSourceCacheWithin<DartMajorSnapshot>(CACHE_KEY, FRESH_MS);
  if (cached) return cached.value;
  const live = await fetchLive();
  if (live) {
    await writeSourceCache(CACHE_KEY, live);
    return live;
  }
  const stale = await readSourceCacheWithin<DartMajorSnapshot>(CACHE_KEY, STALE_MS);
  return stale?.value ?? null;
}
