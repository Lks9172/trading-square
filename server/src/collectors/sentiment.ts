import axios from 'axios';
import * as XLSX from 'xlsx';
import { MarketDataPoint } from '../types/indicators';

export interface SentimentPoint {
  value: number | null;
  asOf: string | null;
  source: string;
  error?: string;
  extra?: Record<string, number | null>;
}

const HEADERS = {
  'User-Agent':
    'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
};

const TIMEOUT_MS = 10000;

// ---- CBOE Put/Call Ratio (자체 집계) -------------------------------------
// 2026-04: CBOE 공식 PCR_ALL.csv 403, Yahoo ^CPC/^CPCE/^CPCI 404 로 모두 막힘.
// 대안: CBOE delayed quotes options chain (_SPX + SPY + QQQ) 의 call/put volume
// 을 직접 집계해 비율 계산. 당일 snapshot 값이고 10D MA 는 history rolling 으로.
const CBOE_OPTION_TICKERS = ['_SPX', 'SPY', 'QQQ'];

export async function fetchCBOEPutCall(): Promise<SentimentPoint> {
  const urls = CBOE_OPTION_TICKERS.map(
    (t) => `https://cdn.cboe.com/api/global/delayed_quotes/options/${t}.json`
  );
  const responses = await Promise.allSettled(
    urls.map((url) =>
      axios.get<{
        timestamp?: string;
        data?: { options?: Array<{ option: string; volume?: number }> };
      }>(url, {
        headers: { ...HEADERS, Accept: 'application/json', Referer: 'https://www.cboe.com/' },
        timeout: TIMEOUT_MS,
      })
    )
  );

  let putVol = 0;
  let callVol = 0;
  let timestamp: string | null = null;
  let successCount = 0;

  for (const r of responses) {
    if (r.status !== 'fulfilled') continue;
    const opts = r.value.data?.data?.options;
    if (!Array.isArray(opts)) continue;
    successCount++;
    if (!timestamp && r.value.data?.timestamp) timestamp = r.value.data.timestamp;
    for (const o of opts) {
      const sym = o.option ?? '';
      const vol = o.volume ?? 0;
      if (!Number.isFinite(vol)) continue;
      if (/C\d{8}$/.test(sym)) callVol += vol;
      else if (/P\d{8}$/.test(sym)) putVol += vol;
    }
  }

  if (successCount === 0 || callVol === 0) {
    return {
      value: null,
      asOf: null,
      source: 'CBOE:CHAIN',
      error: 'CBOE delayed_quotes options chain 전부 실패 또는 callVol=0',
    };
  }

  const pcr = putVol / callVol;
  const asOf = timestamp ? timestamp.slice(0, 10) : new Date().toISOString().slice(0, 10);
  return {
    value: parseFloat(pcr.toFixed(3)),
    asOf,
    source: 'CBOE:CHAIN',
    extra: {
      putVol: Math.round(putVol),
      callVol: Math.round(callVol),
      tickers: successCount,
    },
  };
}

// ---- AAII Bull/Bear Spread ---------------------------------------------
// 2026-04 리서치: stooq 유료화 이후, AAII 공식 XLS 가 실제로는 200 OK (기존 코드
// 주석이 403 으로 잘못 판단). SENTIMENT 시트 row 7~ 에서 Date(Excel serial) +
// Bullish/Bearish/Spread(소수) 직접 파싱.
const AAII_XLS_URL = 'https://www.aaii.com/files/surveys/sentiment.xls';

function excelSerialToISO(serial: number): string {
  // Excel date serial: 1900-01-01 = 1, 단 1900 윤년 버그로 -25569 offset → UNIX ms 환산.
  const ms = Math.round((serial - 25569) * 86400 * 1000);
  return new Date(ms).toISOString().slice(0, 10);
}

export async function fetchAAIIBullBear(): Promise<SentimentPoint> {
  try {
    const { data } = await axios.get<ArrayBuffer>(AAII_XLS_URL, {
      headers: { ...HEADERS, Accept: 'application/vnd.ms-excel' },
      timeout: 20000,
      responseType: 'arraybuffer',
    });
    const wb = XLSX.read(Buffer.from(data), { type: 'buffer' });
    const ws = wb.Sheets['SENTIMENT'];
    if (!ws) {
      return { value: null, asOf: null, source: 'AAII', error: 'SENTIMENT 시트 없음' };
    }
    const rows: unknown[][] = XLSX.utils.sheet_to_json(ws, { header: 1, raw: true }) as unknown[][];

    // 최신 유효 행 역방향 탐색. Date serial 범위 40000~55000 (1999~2050) 내만 인정.
    for (let i = rows.length - 1; i >= 7; i -= 1) {
      const row = rows[i];
      if (!Array.isArray(row) || row.length < 7) continue;
      const serial = row[0];
      if (typeof serial !== 'number' || serial < 40000 || serial > 55000) continue;

      const bullish = row[1] as number;
      const bearish = row[3] as number;
      const spread = row[6] as number; // Bullish - Bearish, 소수 (0.0724 = 7.24%)

      if (
        !Number.isFinite(bullish) ||
        !Number.isFinite(bearish) ||
        !Number.isFinite(spread)
      ) {
        continue;
      }

      return {
        value: parseFloat((spread * 100).toFixed(2)), // -7.24 형태 (pp)
        asOf: excelSerialToISO(serial),
        source: 'AAII',
        extra: {
          bull: parseFloat((bullish * 100).toFixed(2)),
          bear: parseFloat((bearish * 100).toFixed(2)),
        },
      };
    }
    return { value: null, asOf: null, source: 'AAII', error: '유효한 최신 행 없음' };
  } catch (e) {
    const msg = (e as { response?: { status?: number }; message?: string })?.response?.status || (e as Error)?.message;
    return {
      value: null,
      asOf: null,
      source: 'AAII',
      error: `AAII XLS fetch 실패: ${msg}`,
    };
  }
}

// ---- NAAIM Exposure Index ----------------------------------------------
// 2026-04 리서치: 기존 CSV URL 404. naaim.org/programs/naaim-exposure-index/
// 페이지 HTML 테이블에 최신값 포함 — <tr><td>MM/DD/YYYY</td><td>숫자</td>...</tr>.
export async function fetchNAAIM(): Promise<SentimentPoint> {
  const pageUrl = 'https://naaim.org/programs/naaim-exposure-index/';
  try {
    const { data } = await axios.get<string>(pageUrl, {
      headers: { ...HEADERS, Accept: 'text/html' },
      timeout: TIMEOUT_MS,
      responseType: 'text',
    });

    // HTML 테이블 행 순회 → 가장 최신(첫번째 매칭) 행의 date + value 추출.
    const rowRe = /<tr[^>]*>([\s\S]*?)<\/tr>/gi;
    const cellRe = /<td[^>]*>([\s\S]*?)<\/td>/gi;

    for (const rowMatch of data.matchAll(rowRe)) {
      const rowHtml = rowMatch[1];
      const cells: string[] = [];
      for (const cellMatch of rowHtml.matchAll(cellRe)) {
        cells.push(cellMatch[1].replace(/<[^>]+>/g, '').trim());
      }
      if (cells.length < 2) continue;

      const dateMatch = cells[0].match(/^(\d{2})\/(\d{2})\/(\d{4})$/);
      if (!dateMatch) continue;

      const value = parseFloat(cells[1]);
      if (!Number.isFinite(value)) continue;

      const [, mm, dd, yyyy] = dateMatch;
      return {
        value: parseFloat(value.toFixed(2)),
        asOf: `${yyyy}-${mm}-${dd}`,
        source: 'NAAIM',
      };
    }

    return {
      value: null,
      asOf: null,
      source: 'NAAIM',
      error: 'HTML 테이블에서 유효 행 미발견',
    };
  } catch (e) {
    const msg = (e as { response?: { status?: number }; message?: string })?.response?.status || (e as Error)?.message;
    return {
      value: null,
      asOf: null,
      source: 'NAAIM',
      error: `NAAIM 페이지 fetch 실패: ${msg}`,
    };
  }
}

// ---- 집계: 5분 스냅샷 cycle 에서 호출 ----------------------------------
export async function fetchAllSentiment(): Promise<Record<string, MarketDataPoint>> {
  const [pcr, aaii, naaim] = await Promise.allSettled([
    fetchCBOEPutCall(),
    fetchAAIIBullBear(),
    fetchNAAIM(),
  ]);

  const out: Record<string, MarketDataPoint> = {};
  const today = new Date().toISOString().split('T')[0];

  const pcrVal = pcr.status === 'fulfilled' ? pcr.value : null;
  if (pcrVal && pcrVal.value !== null) {
    out.PC_RATIO = {
      code: 'PC_RATIO',
      value: pcrVal.value,
      date: pcrVal.asOf ?? today,
      source: 'CBOE',
    };
    if (pcrVal.extra?.ma10 != null) {
      out.PC_RATIO_10D = {
        code: 'PC_RATIO_10D',
        value: pcrVal.extra.ma10,
        date: pcrVal.asOf ?? today,
        source: 'CBOE',
      };
    }
  }

  const aaiiVal = aaii.status === 'fulfilled' ? aaii.value : null;
  if (aaiiVal && aaiiVal.value !== null) {
    out.AAII_BULL_BEAR_SPREAD = {
      code: 'AAII_BULL_BEAR_SPREAD',
      value: aaiiVal.value,
      date: aaiiVal.asOf ?? today,
      source: 'CALC',
    };
  }

  const naaimVal = naaim.status === 'fulfilled' ? naaim.value : null;
  if (naaimVal && naaimVal.value !== null) {
    out.NAAIM_EXPOSURE = {
      code: 'NAAIM_EXPOSURE',
      value: naaimVal.value,
      date: naaimVal.asOf ?? today,
      source: 'CALC',
    };
  }

  return out;
}
