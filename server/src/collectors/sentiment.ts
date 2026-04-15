import axios from 'axios';
import { MarketDataPoint } from '../types/indicators';
import { fetchYahooHistory } from './yahoo';

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

// ---- CBOE Put/Call Ratio ------------------------------------------------
// 1차: CBOE 공식 CSV 엔드포인트, 실패 시 Yahoo ^CPC / ^CPCE 로 폴백.
// 최신값 + 10일 MA 를 함께 반환.
export async function fetchCBOEPutCall(): Promise<SentimentPoint> {
  // 1) CBOE 공식 CSV
  try {
    const url = 'https://cdn.cboe.com/api/global/us_indices/daily_prices/PCR_ALL.csv';
    const { data } = await axios.get<string>(url, {
      headers: { ...HEADERS, Accept: 'text/csv' },
      timeout: TIMEOUT_MS,
      responseType: 'text',
    });
    const lines = data.split(/\r?\n/).filter((l) => l.trim().length > 0);
    // 헤더가 있는 CSV 가정 → 마지막 행이 최신
    const rows: { date: string; value: number }[] = [];
    for (const line of lines.slice(1)) {
      const cols = line.split(',');
      if (cols.length < 2) continue;
      const d = cols[0].trim();
      const v = parseFloat(cols[cols.length - 1]);
      if (!Number.isFinite(v)) continue;
      if (!/^\d{4}-\d{2}-\d{2}/.test(d)) continue;
      rows.push({ date: d.slice(0, 10), value: v });
    }
    if (rows.length >= 1) {
      const latest = rows[rows.length - 1];
      const last10 = rows.slice(-10).map((r) => r.value);
      const ma10 = last10.reduce((a, b) => a + b, 0) / last10.length;
      return {
        value: parseFloat(latest.value.toFixed(3)),
        asOf: latest.date,
        source: 'CBOE',
        extra: { ma10: parseFloat(ma10.toFixed(3)) },
      };
    }
  } catch {
    /* fallback to yahoo */
  }

  // 2) Yahoo ^CPC (Total P/C) 폴백
  try {
    const hist = await fetchYahooHistory('^CPC', 20);
    if (hist.length >= 1) {
      // yahoo history 는 ASC 이므로 마지막이 최신
      const closes = hist.map((h) => h.close).filter((x) => x > 0);
      if (closes.length >= 1) {
        const latest = closes[closes.length - 1];
        const last10 = closes.slice(-10);
        const ma10 = last10.reduce((a, b) => a + b, 0) / last10.length;
        return {
          value: parseFloat(latest.toFixed(3)),
          asOf: hist[hist.length - 1].date,
          source: 'YAHOO:^CPC',
          extra: { ma10: parseFloat(ma10.toFixed(3)) },
        };
      }
    }
  } catch {
    /* ignore */
  }

  return {
    value: null,
    asOf: null,
    source: 'CBOE',
    error: 'CBOE CSV + Yahoo ^CPC 모두 실패',
  };
}

// ---- AAII Bull/Bear Spread ---------------------------------------------
// AAII 공식 CSV 는 로그인/제한이 있어 불안정 → stooq 미러 후보 포함.
// 실패 시 null + error.
export async function fetchAAIIBullBear(): Promise<SentimentPoint> {
  const candidates = [
    'https://www.aaii.com/files/surveys/sentiment.xls', // 원본(바이너리, 파싱 까다로움)
    'https://stooq.com/q/d/l/?s=aaiibull&i=w', // stooq 주간 bull
  ];

  // stooq bull / bear 각각 가져와 spread 계산
  try {
    const bullUrl = 'https://stooq.com/q/d/l/?s=aaiibull&i=w';
    const bearUrl = 'https://stooq.com/q/d/l/?s=aaiibear&i=w';
    const [bullRes, bearRes] = await Promise.all([
      axios.get<string>(bullUrl, { headers: HEADERS, timeout: TIMEOUT_MS, responseType: 'text' }),
      axios.get<string>(bearUrl, { headers: HEADERS, timeout: TIMEOUT_MS, responseType: 'text' }),
    ]);
    const parseLast = (csv: string): { date: string; value: number } | null => {
      const lines = csv.split(/\r?\n/).filter((l) => l.trim().length > 0);
      for (let i = lines.length - 1; i >= 1; i -= 1) {
        const cols = lines[i].split(',');
        if (cols.length < 5) continue;
        const d = cols[0];
        const close = parseFloat(cols[4]);
        if (Number.isFinite(close) && /^\d{4}-\d{2}-\d{2}$/.test(d)) {
          return { date: d, value: close };
        }
      }
      return null;
    };
    const bull = parseLast(bullRes.data);
    const bear = parseLast(bearRes.data);
    if (bull && bear) {
      const spread = bull.value - bear.value;
      return {
        value: parseFloat(spread.toFixed(2)),
        asOf: bull.date >= bear.date ? bull.date : bear.date,
        source: 'STOOQ:AAII',
        extra: {
          bull: parseFloat(bull.value.toFixed(2)),
          bear: parseFloat(bear.value.toFixed(2)),
        },
      };
    }
  } catch {
    /* ignore */
  }

  return {
    value: null,
    asOf: null,
    source: 'AAII',
    error: `AAII 접근 실패 (후보 ${candidates.length}개 모두 소스 제한)`,
  };
}

// ---- NAAIM Exposure Index ----------------------------------------------
// NAAIM 은 공식 페이지의 표/CSV 가 제공되며, 실패 시 null.
export async function fetchNAAIM(): Promise<SentimentPoint> {
  const urls = [
    'https://www.naaim.org/wp-content/uploads/2023/11/NAAIM-Exposure-Index-Data.csv',
    'https://www.naaim.org/programs/naaim-exposure-index/',
  ];

  for (const url of urls) {
    try {
      const { data } = await axios.get<string>(url, {
        headers: { ...HEADERS, Accept: 'text/csv,text/html' },
        timeout: TIMEOUT_MS,
        responseType: 'text',
      });
      // CSV 경로
      if (url.endsWith('.csv')) {
        const lines = data.split(/\r?\n/).filter((l) => l.trim().length > 0);
        for (let i = lines.length - 1; i >= 1; i -= 1) {
          const cols = lines[i].split(',');
          if (cols.length < 2) continue;
          const d = cols[0].trim();
          const mean = parseFloat(cols[cols.length - 1]);
          if (Number.isFinite(mean)) {
            const normalized = /^\d{4}-\d{2}-\d{2}/.test(d) ? d.slice(0, 10) : d;
            return {
              value: parseFloat(mean.toFixed(2)),
              asOf: normalized,
              source: 'NAAIM',
            };
          }
        }
      } else {
        // HTML 폴백: 본문에서 "Mean Value" 근처의 숫자 하나 긁는다
        const match = data.match(/Mean[^0-9\-]*(-?\d+(?:\.\d+)?)/i);
        if (match) {
          return {
            value: parseFloat(parseFloat(match[1]).toFixed(2)),
            asOf: null,
            source: 'NAAIM:HTML',
          };
        }
      }
    } catch {
      /* try next */
    }
  }

  return {
    value: null,
    asOf: null,
    source: 'NAAIM',
    error: 'NAAIM CSV/HTML 모두 실패',
  };
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
