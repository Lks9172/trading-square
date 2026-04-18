/**
 * 기관 투자자 13F-HR 공시 수집기 (MVP Phase 1).
 *
 * 영상4 §기관리포트 정합: "말은 거짓말 할 수 있지만 돈은 거짓말을 하지 않거든요".
 *
 * 주요 헤지펀드 10곳의 최근 13F-HR 공시를 SEC EDGAR 에서 가져와 포지션
 * 리스트를 파싱한다. 분기별 배치이므로 TTL 7일로 가볍게 유지.
 *
 * MVP 범위 (현 시점 단일 스냅샷):
 *   - 최근 13F-HR 1건만 파싱
 *   - 이전 분기 비교 / 포지션 변화 추적은 Phase 2
 *   - 파생 지표: INSTITUTIONAL_NASDAQ_EXPOSURE_PCT (메가캡 7종목 합 평균)
 *
 * SEC 정책:
 *   - User-Agent 필수 (연락처 포함 형식 권장)
 *   - rate limit: 초당 10 req 상한 → 펀드 간 150ms 간격
 *   - data.sec.gov 무료 공개, 인증 불필요
 */

import { readSourceCacheWithin, writeSourceCache } from '../services/source-cache';
import { childLogger, serializeError } from '../services/logger';

const log = childLogger({ module: 'collector.institutional-13f' });

const USER_AGENT = 'MacroSquare Research noreply@macrosquare.local';

/** 추적 대상 헤지펀드 (CIK 10자리 padded). 분기별 13F-HR 의무 공시 주체. */
export const TRACKED_FUNDS: Array<{ cik: string; name: string }> = [
  { cik: '0001067983', name: 'Berkshire Hathaway' },
  { cik: '0001350694', name: 'Bridgewater Associates' },
  { cik: '0001037389', name: 'Renaissance Technologies' },
  { cik: '0001423053', name: 'Citadel Advisors' },
  { cik: '0001009207', name: 'D.E. Shaw' },
  { cik: '0001273087', name: 'Millennium Management' },
  { cik: '0001179392', name: 'Two Sigma Investments' },
  { cik: '0001167557', name: 'AQR Capital Management' },
  { cik: '0001603466', name: 'Point72 Asset Management' },
  { cik: '0001167483', name: 'Tiger Global Management' },
];

/** NASDAQ 메가캡 CUSIP (주요 7종목) — 13F 는 티커가 아닌 CUSIP 기준 식별. */
export const NASDAQ_MEGACAP_CUSIPS: Record<string, string> = {
  AAPL: '037833100',
  MSFT: '594918104',
  GOOGL: '02079K305',
  AMZN: '023135106',
  NVDA: '67066G104',
  META: '30303M102',
  TSLA: '88160R101',
};

export interface Position {
  cusip: string;
  value: number; // USD thousand
  shares: number;
}

export interface FundPositions {
  cik: string;
  fundName: string;
  filingDate: string;
  quarter: string;
  positions: Position[];
  totalValue: number;
}

const CACHE_KEY = 'institutional-13f-latest';
const CACHE_TTL_MS = 7 * 24 * 60 * 60 * 1000; // 7d
const STALE_TTL_MS = 30 * 24 * 60 * 60 * 1000; // 30d

async function httpJson(url: string): Promise<unknown> {
  const res = await fetch(url, {
    headers: { 'User-Agent': USER_AGENT, Accept: 'application/json' },
  });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  return res.json();
}

async function httpText(url: string): Promise<string> {
  const res = await fetch(url, { headers: { 'User-Agent': USER_AGENT } });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  return res.text();
}

async function findLatest13FHR(cik: string): Promise<{ accession: string; filingDate: string } | null> {
  const url = `https://data.sec.gov/submissions/CIK${cik}.json`;
  const sub = (await httpJson(url)) as {
    filings?: { recent?: { form?: string[]; accessionNumber?: string[]; filingDate?: string[] } };
  };
  const recent = sub?.filings?.recent;
  if (!recent) return null;
  const forms = recent.form || [];
  const accs = recent.accessionNumber || [];
  const dates = recent.filingDate || [];
  for (let i = 0; i < forms.length; i++) {
    if (forms[i] === '13F-HR') {
      return { accession: accs[i], filingDate: dates[i] };
    }
  }
  return null;
}

async function fetchInfotable(cik: string, accession: string): Promise<string> {
  const rawCik = cik.replace(/^0+/, '');
  const rawAcc = accession.replace(/-/g, '');
  const indexUrl = `https://www.sec.gov/Archives/edgar/data/${rawCik}/${rawAcc}/index.json`;
  const idx = (await httpJson(indexUrl)) as { directory?: { item?: Array<{ name: string }> } };
  const files = idx?.directory?.item || [];
  const xmlFile = files.find(
    (f) => typeof f.name === 'string' && f.name.toLowerCase().includes('infotable') && f.name.endsWith('.xml'),
  );
  if (!xmlFile) throw new Error('infotable.xml not found in filing index');
  const xmlUrl = `https://www.sec.gov/Archives/edgar/data/${rawCik}/${rawAcc}/${xmlFile.name}`;
  return httpText(xmlUrl);
}

/**
 * 13F infotable XML 파싱 (regex 기반, xml2js 의존성 회피).
 * 네임스페이스 접두사(ns1:, n1:) 유무 모두 수용.
 */
export function parseInfotable(xml: string): Position[] {
  const positions: Position[] = [];
  const blockRe = /<(?:\w+:)?infoTable>([\s\S]*?)<\/(?:\w+:)?infoTable>/g;
  let m: RegExpExecArray | null;
  while ((m = blockRe.exec(xml)) !== null) {
    const block = m[1];
    const cusipMatch = /<(?:\w+:)?cusip>\s*([A-Z0-9]+)\s*<\/(?:\w+:)?cusip>/.exec(block);
    const valueMatch = /<(?:\w+:)?value>\s*([\d.]+)\s*<\/(?:\w+:)?value>/.exec(block);
    const sharesMatch = /<(?:\w+:)?sshPrnamt>\s*(\d+)\s*<\/(?:\w+:)?sshPrnamt>/.exec(block);
    if (cusipMatch && valueMatch) {
      positions.push({
        cusip: cusipMatch[1].toUpperCase(),
        value: parseFloat(valueMatch[1]),
        shares: sharesMatch ? parseInt(sharesMatch[1], 10) : 0,
      });
    }
  }
  return positions;
}

function inferQuarter(filingDate: string): string {
  const d = new Date(filingDate);
  const y = d.getFullYear();
  const m = d.getMonth();
  // 13F-HR 공시 의무 = 분기말 + 45일 이내. 공시일 역산해 보고 분기 추정.
  // 1~2월 공시 → 전년 Q4, 3~5 → Q1, 6~8 → Q2, 9~11 → Q3, 12 → Q4.
  if (m <= 1) return `${y - 1}Q4`;
  if (m <= 4) return `${y}Q1`;
  if (m <= 7) return `${y}Q2`;
  if (m <= 10) return `${y}Q3`;
  return `${y}Q4`;
}

/**
 * 추적 대상 헤지펀드 전체의 최근 13F-HR 공시를 fetch + 파싱.
 * SEC rate limit 준수 위해 펀드 간 150ms 간격.
 * 7일 캐시 + 30일 stale fallback.
 */
export async function fetchInstitutional13F(): Promise<FundPositions[]> {
  const cached = await readSourceCacheWithin<FundPositions[]>(CACHE_KEY, CACHE_TTL_MS);
  if (cached) {
    log.info({ ageHours: Math.round(cached.ageMs / 3600000), count: cached.value.length }, '13F cache hit');
    return cached.value;
  }
  log.info({ funds: TRACKED_FUNDS.length }, '13F fetch starting');
  const results: FundPositions[] = [];
  for (const fund of TRACKED_FUNDS) {
    try {
      const latest = await findLatest13FHR(fund.cik);
      if (!latest) {
        log.warn({ fund: fund.name }, '13F: no HR filing found');
        continue;
      }
      const xml = await fetchInfotable(fund.cik, latest.accession);
      const positions = parseInfotable(xml);
      const totalValue = positions.reduce((s, p) => s + p.value, 0);
      const quarter = inferQuarter(latest.filingDate);
      results.push({
        cik: fund.cik,
        fundName: fund.name,
        filingDate: latest.filingDate,
        quarter,
        positions,
        totalValue,
      });
      log.info(
        { fund: fund.name, quarter, positions: positions.length, totalValue: Math.round(totalValue) },
        '13F parsed',
      );
      await new Promise((r) => setTimeout(r, 150)); // SEC rate limit 여유분
    } catch (error) {
      log.warn({ fund: fund.name, error: serializeError(error) }, '13F fetch failed');
    }
  }
  if (results.length >= 3) {
    await writeSourceCache(CACHE_KEY, results);
  } else {
    // 절반도 못 가져오면 stale cache 재시도
    const stale = await readSourceCacheWithin<FundPositions[]>(CACHE_KEY, STALE_TTL_MS);
    if (stale) {
      log.warn({ fresh: results.length, stale: stale.value.length }, '13F fresh<3 → using stale');
      return stale.value;
    }
  }
  return results;
}

/**
 * 추적 펀드 평균 NASDAQ 메가캡 7종목 비중(%).
 * 영상4 §기관 "어디에 실제로 베팅" — 대형 기술주 집단 노출도 지표.
 *
 * 반환: 0~100, 가중 평균 (각 펀드 개별 비중 단순 평균).
 *       펀드 수 부족(<3) 시 null.
 */
export function computeNasdaqMegacapExposure(
  funds: FundPositions[],
): { avgSharePct: number; fundCount: number } | null {
  if (funds.length < 3) return null;
  const megacapCusips = new Set(Object.values(NASDAQ_MEGACAP_CUSIPS));
  let sumShare = 0;
  let fundCount = 0;
  for (const fund of funds) {
    if (fund.totalValue <= 0) continue;
    const megacapValue = fund.positions
      .filter((p) => megacapCusips.has(p.cusip))
      .reduce((s, p) => s + p.value, 0);
    sumShare += megacapValue / fund.totalValue;
    fundCount++;
  }
  if (fundCount < 3) return null;
  return {
    avgSharePct: parseFloat(((sumShare / fundCount) * 100).toFixed(2)),
    fundCount,
  };
}
