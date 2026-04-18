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

/**
 * 12차 Phase 3 (2026-04): 섹터별 대표 종목 CUSIP — 집단 이동 감지용.
 *   기술(Tech): XLK 구성 주요
 *   금융(Fin): XLF 구성 주요 (JPM, BAC, WFC, GS, MS)
 *   에너지(Energy): XLE 구성 주요 (XOM, CVX, COP, SLB, EOG)
 */
export const SECTOR_CUSIPS: Record<'tech' | 'fin' | 'energy', string[]> = {
  tech: [
    '037833100', // AAPL
    '594918104', // MSFT
    '02079K305', // GOOGL
    '67066G104', // NVDA
    '30303M102', // META
  ],
  fin: [
    '46625H100', // JPM
    '060505104', // BAC
    '949746101', // WFC
    '38141G104', // GS
    '617446448', // MS
  ],
  energy: [
    '30231G102', // XOM
    '166764100', // CVX
    '20825C104', // COP
    '806857108', // SLB
    '26875P101', // EOG
  ],
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

/** 11차 Phase 2: 최근 2분기 연속 공시 묶음 (이전 분기 비교용). */
export interface FundQuarterlyPositions {
  cik: string;
  fundName: string;
  current: FundPositions;
  previous: FundPositions | null;
}

/** 포지션 변화 분류 — video4 §기관 "어디에 베팅" 추적. */
export type PositionChangeKind = 'NEW' | 'ADDED' | 'REDUCED' | 'CLOSED' | 'UNCHANGED';

export interface PositionChange {
  cusip: string;
  kind: PositionChangeKind;
  prevShares: number;
  curShares: number;
  prevValue: number;
  curValue: number;
  shareChangePct: number; // +/- %
}

// 11차 Phase 2: 캐시 구조 변경 (FundPositions[] → FundQuarterlyPositions[]) 로
// 구버전 캐시와 역직렬화 충돌 방지를 위해 키 신설.
const CACHE_KEY = 'institutional-13f-quarterly-v2';
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

async function findRecent13FHR(
  cik: string,
  limit: number = 2,
): Promise<Array<{ accession: string; filingDate: string }>> {
  const url = `https://data.sec.gov/submissions/CIK${cik}.json`;
  const sub = (await httpJson(url)) as {
    filings?: { recent?: { form?: string[]; accessionNumber?: string[]; filingDate?: string[] } };
  };
  const recent = sub?.filings?.recent;
  if (!recent) return [];
  const forms = recent.form || [];
  const accs = recent.accessionNumber || [];
  const dates = recent.filingDate || [];
  const hits: Array<{ accession: string; filingDate: string }> = [];
  for (let i = 0; i < forms.length && hits.length < limit; i++) {
    if (forms[i] === '13F-HR') {
      hits.push({ accession: accs[i], filingDate: dates[i] });
    }
  }
  return hits;
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

async function fetchOneQuarterPositions(
  cik: string,
  fundName: string,
  filing: { accession: string; filingDate: string },
): Promise<FundPositions> {
  const xml = await fetchInfotable(cik, filing.accession);
  const positions = parseInfotable(xml);
  const totalValue = positions.reduce((s, p) => s + p.value, 0);
  return {
    cik,
    fundName,
    filingDate: filing.filingDate,
    quarter: inferQuarter(filing.filingDate),
    positions,
    totalValue,
  };
}

/**
 * 추적 대상 헤지펀드의 최근 2개 분기 13F-HR 공시 fetch (Phase 2).
 * SEC rate limit 준수 위해 요청 간 150ms 간격.
 * 7일 캐시 + 30일 stale fallback.
 */
export async function fetchInstitutional13FQuarterly(): Promise<FundQuarterlyPositions[]> {
  const cached = await readSourceCacheWithin<FundQuarterlyPositions[]>(CACHE_KEY, CACHE_TTL_MS);
  if (cached) {
    log.info({ ageHours: Math.round(cached.ageMs / 3600000), count: cached.value.length }, '13F cache hit');
    return cached.value;
  }
  log.info({ funds: TRACKED_FUNDS.length }, '13F quarterly fetch starting');
  const results: FundQuarterlyPositions[] = [];
  for (const fund of TRACKED_FUNDS) {
    try {
      const recent = await findRecent13FHR(fund.cik, 2);
      if (recent.length === 0) {
        log.warn({ fund: fund.name }, '13F: no HR filing found');
        continue;
      }
      const current = await fetchOneQuarterPositions(fund.cik, fund.name, recent[0]);
      await new Promise((r) => setTimeout(r, 150));
      let previous: FundPositions | null = null;
      if (recent.length >= 2) {
        try {
          previous = await fetchOneQuarterPositions(fund.cik, fund.name, recent[1]);
          await new Promise((r) => setTimeout(r, 150));
        } catch (error) {
          log.warn({ fund: fund.name, error: serializeError(error) }, '13F previous quarter fetch failed');
        }
      }
      results.push({ cik: fund.cik, fundName: fund.name, current, previous });
      log.info(
        {
          fund: fund.name,
          curQ: current.quarter,
          prevQ: previous?.quarter,
          curPositions: current.positions.length,
          prevPositions: previous?.positions.length,
        },
        '13F quarterly parsed',
      );
    } catch (error) {
      log.warn({ fund: fund.name, error: serializeError(error) }, '13F quarterly fetch failed');
    }
  }
  if (results.length >= 3) {
    await writeSourceCache(CACHE_KEY, results);
  } else {
    const stale = await readSourceCacheWithin<FundQuarterlyPositions[]>(CACHE_KEY, STALE_TTL_MS);
    if (stale) {
      log.warn({ fresh: results.length, stale: stale.value.length }, '13F fresh<3 → using stale');
      return stale.value;
    }
  }
  return results;
}

/** Phase 1 호환성 — current 분기만 추출. */
export async function fetchInstitutional13F(): Promise<FundPositions[]> {
  const quarterly = await fetchInstitutional13FQuarterly();
  return quarterly.map((q) => q.current);
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

/**
 * 한 펀드의 포지션 분기 대비 변화 분류 — video4 §기관 "어디에 베팅" 추적.
 *
 *   NEW:       이전 분기 미보유, 현재 보유
 *   ADDED:     보유 유지 + 주식수 +10% 초과 증가
 *   REDUCED:   보유 유지 + 주식수 -10% 초과 감소
 *   CLOSED:    이전 보유, 현재 미보유
 *   UNCHANGED: |Δ주식수| ≤ 10%
 */
export function computePositionChanges(
  current: FundPositions,
  previous: FundPositions,
  cusipFilter?: Set<string>,
): PositionChange[] {
  const curMap = new Map(current.positions.map((p) => [p.cusip, p]));
  const prevMap = new Map(previous.positions.map((p) => [p.cusip, p]));
  const allCusips = new Set<string>([...curMap.keys(), ...prevMap.keys()]);
  const out: PositionChange[] = [];
  for (const cusip of allCusips) {
    if (cusipFilter && !cusipFilter.has(cusip)) continue;
    const cur = curMap.get(cusip);
    const prev = prevMap.get(cusip);
    const prevShares = prev?.shares ?? 0;
    const curShares = cur?.shares ?? 0;
    const prevValue = prev?.value ?? 0;
    const curValue = cur?.value ?? 0;
    let kind: PositionChangeKind;
    let shareChangePct = 0;
    if (!prev && cur) {
      kind = 'NEW';
      shareChangePct = 100;
    } else if (prev && !cur) {
      kind = 'CLOSED';
      shareChangePct = -100;
    } else if (prev && cur) {
      shareChangePct = prevShares > 0 ? ((curShares - prevShares) / prevShares) * 100 : 0;
      if (shareChangePct > 10) kind = 'ADDED';
      else if (shareChangePct < -10) kind = 'REDUCED';
      else kind = 'UNCHANGED';
    } else {
      continue;
    }
    out.push({ cusip, kind, prevShares, curShares, prevValue, curValue, shareChangePct });
  }
  return out;
}

/**
 * 메가캡 집단 비중 분기 변화 → +2 ~ -2 레벨 반환.
 * 영상4 §기관 "집단이 어디로 움직이나" 정합.
 *
 *   avgCurrentPct - avgPrevPct (%p):
 *     > +2.0  → +2 (집단 강한 매수)
 *     > +0.5  → +1 (매수)
 *     |Δ| ≤ 0.5 → 0 (중립)
 *     < -0.5  → -1 (매도)
 *     < -2.0  → -2 (집단 강한 매도)
 *
 * 이전 분기 데이터 있는 펀드 ≥ 3 곳 필요 (없으면 null).
 */
/**
 * 12차 Phase 3: 섹터별 집단 비중 분기 변화 (tech/fin/energy 동시).
 *   영상4 §기관 "집단이 어디로 움직이나" 의 섹터 버전.
 *   각 섹터 CUSIP 합 비중의 펀드 평균 → 분기 Δ → -2~+2 레벨.
 *   이전 분기 데이터 있는 펀드 ≥ 3 곳 필요.
 */
export function computeSectorInstitutionalFlow(
  quarterly: FundQuarterlyPositions[],
  sector: 'tech' | 'fin' | 'energy',
): { currentPct: number; previousPct: number; deltaPct: number; level: -2 | -1 | 0 | 1 | 2; fundCount: number } | null {
  const cusips = new Set(SECTOR_CUSIPS[sector]);
  const shares: Array<{ curShare: number; prevShare: number }> = [];
  for (const q of quarterly) {
    if (!q.previous) continue;
    if (q.current.totalValue <= 0 || q.previous.totalValue <= 0) continue;
    const curSum = q.current.positions.filter((p) => cusips.has(p.cusip)).reduce((s, p) => s + p.value, 0);
    const prevSum = q.previous.positions.filter((p) => cusips.has(p.cusip)).reduce((s, p) => s + p.value, 0);
    shares.push({ curShare: curSum / q.current.totalValue, prevShare: prevSum / q.previous.totalValue });
  }
  if (shares.length < 3) return null;
  const currentPct = (shares.reduce((s, x) => s + x.curShare, 0) / shares.length) * 100;
  const previousPct = (shares.reduce((s, x) => s + x.prevShare, 0) / shares.length) * 100;
  const deltaPct = currentPct - previousPct;
  let level: -2 | -1 | 0 | 1 | 2;
  if (deltaPct > 2.0) level = 2;
  else if (deltaPct > 0.5) level = 1;
  else if (deltaPct < -2.0) level = -2;
  else if (deltaPct < -0.5) level = -1;
  else level = 0;
  return {
    currentPct: parseFloat(currentPct.toFixed(2)),
    previousPct: parseFloat(previousPct.toFixed(2)),
    deltaPct: parseFloat(deltaPct.toFixed(2)),
    level,
    fundCount: shares.length,
  };
}

export function computeNasdaqMegacapFlow(
  quarterly: FundQuarterlyPositions[],
): { currentPct: number; previousPct: number; deltaPct: number; level: -2 | -1 | 0 | 1 | 2; fundCount: number } | null {
  const megacapCusips = new Set(Object.values(NASDAQ_MEGACAP_CUSIPS));
  const shares: Array<{ curShare: number; prevShare: number }> = [];
  for (const q of quarterly) {
    if (!q.previous) continue;
    if (q.current.totalValue <= 0 || q.previous.totalValue <= 0) continue;
    const curMegacap = q.current.positions
      .filter((p) => megacapCusips.has(p.cusip))
      .reduce((s, p) => s + p.value, 0);
    const prevMegacap = q.previous.positions
      .filter((p) => megacapCusips.has(p.cusip))
      .reduce((s, p) => s + p.value, 0);
    shares.push({
      curShare: curMegacap / q.current.totalValue,
      prevShare: prevMegacap / q.previous.totalValue,
    });
  }
  if (shares.length < 3) return null;
  const currentPct = (shares.reduce((s, x) => s + x.curShare, 0) / shares.length) * 100;
  const previousPct = (shares.reduce((s, x) => s + x.prevShare, 0) / shares.length) * 100;
  const deltaPct = currentPct - previousPct;
  let level: -2 | -1 | 0 | 1 | 2;
  if (deltaPct > 2.0) level = 2;
  else if (deltaPct > 0.5) level = 1;
  else if (deltaPct < -2.0) level = -2;
  else if (deltaPct < -0.5) level = -1;
  else level = 0;
  return {
    currentPct: parseFloat(currentPct.toFixed(2)),
    previousPct: parseFloat(previousPct.toFixed(2)),
    deltaPct: parseFloat(deltaPct.toFixed(2)),
    level,
    fundCount: shares.length,
  };
}
