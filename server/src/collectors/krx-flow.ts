/**
 * KRX 외국인·기관 순매수 수집기 (코스피/코스닥).
 *
 * 공식 KRX Open API 에는 투자자별 데이터가 없고, KRX 정보데이터시스템(data.krx.co.kr)
 * 은 bot-detection 으로 막혀있어, 네이버 금융의 공식 집계 페이지를 파싱.
 *
 *   URL: finance.naver.com/sise/investorDealTrendDay.nhn?bizdate=YYYYMMDD&sosok=01|02
 *   - sosok=01 → KOSPI, sosok=02 → KOSDAQ
 *   - bizdate 는 해당일 이전 약 20 영업일치 일별 데이터가 표로 반환됨
 *   - 인코딩 EUC-KR → UTF-8 변환 필요
 *
 * 열 순서 (row 첫 td 가 날짜, 이후):
 *   개인 / 외국인 / 기관계 / 금융투자 / 보험 / 투신 / 은행 / 기타금융 / 연기금등 / 기타법인
 *
 * 단위: 억원 (네이버 표시 기준)
 */

import axios from 'axios';
import iconv from 'iconv-lite';

const UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

export interface InvestorFlowDay {
  date: string;          // YYYY-MM-DD
  individual: number;    // 개인 순매수 (억원)
  foreign: number;       // 외국인 순매수 (억원)
  institution: number;   // 기관계 순매수 (억원)
  financial: number;     // 금융투자
  insurance: number;     // 보험
  investmentTrust: number; // 투신
  bank: number;          // 은행
  otherFinancial: number;  // 기타금융
  pension: number;       // 연기금등
  otherCorp: number;     // 기타법인
}

export type KrxMarket = 'KOSPI' | 'KOSDAQ';

function formatDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}${m}${day}`;
}

/** "26.04.14" → "2026-04-14" 변환 (2자리 연도 → 20xx 가정) */
function normalizeDate(short: string): string {
  const m = short.match(/(\d{2})\.(\d{2})\.(\d{2})/);
  if (!m) return short;
  return `20${m[1]}-${m[2]}-${m[3]}`;
}

/** "-23,919" → -23919, "8,276" → 8276 */
function parseNum(s: string): number {
  const cleaned = s.replace(/,/g, '').replace(/\s/g, '').trim();
  const n = parseFloat(cleaned);
  return Number.isFinite(n) ? n : 0;
}

/** 지정 시장의 최근 N 영업일(최대 약 20일) 투자자별 순매수 배열 반환. */
export async function fetchKrxInvestorFlow(
  market: KrxMarket = 'KOSPI',
  targetDate: Date = new Date(),
): Promise<InvestorFlowDay[]> {
  const sosok = market === 'KOSPI' ? '01' : '02';
  const bizdate = formatDate(targetDate);
  const url = `https://finance.naver.com/sise/investorDealTrendDay.nhn?bizdate=${bizdate}&sosok=${sosok}`;

  const { data } = await axios.get<ArrayBuffer>(url, {
    headers: { 'User-Agent': UA },
    responseType: 'arraybuffer',
    timeout: 15000,
  });
  const html = iconv.decode(Buffer.from(data), 'EUC-KR');

  const rows: InvestorFlowDay[] = [];
  const rowRegex = /<tr>\s*<td class="date2">([^<]+)<\/td>([\s\S]*?)<\/tr>/g;
  let m: RegExpExecArray | null;
  while ((m = rowRegex.exec(html)) !== null) {
    const dateShort = m[1].trim();
    const body = m[2];
    const tdMatches = [...body.matchAll(/<td[^>]*>([^<]*)<\/td>/g)].map((x) => x[1]);
    if (tdMatches.length < 10) continue;
    const nums = tdMatches.map(parseNum);
    rows.push({
      date: normalizeDate(dateShort),
      individual: nums[0],
      foreign: nums[1],
      institution: nums[2],
      financial: nums[3],
      insurance: nums[4],
      investmentTrust: nums[5],
      bank: nums[6],
      otherFinancial: nums[7],
      pension: nums[8],
      otherCorp: nums[9],
    });
  }

  // 네이버가 최신일부터 아래로 나열 → 최신이 arr[0].
  // 오름차순(오래된 → 최신) 으로 정렬 반환.
  return rows.sort((a, b) => a.date.localeCompare(b.date));
}

export interface InvestorFlowSummary {
  market: KrxMarket;
  latestDate: string;
  foreignLatest: number;           // 최신일 외국인 순매수
  foreignNet5D: number;            // 최근 5영업일 합
  foreignNet20D: number;           // 최근 20영업일 합 (가능한 만큼)
  institutionLatest: number;
  institutionNet5D: number;
  pensionNet5D: number;
  /** 외국인 최근5일 평균 vs 6~20일 평균 차이 — 양수면 추세 가속 */
  foreignTrend: number;
  /** 외국인 연속 순매수 일수 (최신일부터 역산) */
  foreignBuyStreak: number;
  /** 외국인 연속 순매도 일수 */
  foreignSellStreak: number;
  /** 20일 누적 순매수 극단 이벤트: +30000(3조)↑ 과열 / -30000↓ 과매도 */
  foreignExtreme: 'overheated' | 'oversold' | 'neutral';
  days: InvestorFlowDay[];
}

export function summarizeInvestorFlow(
  market: KrxMarket,
  days: InvestorFlowDay[],
): InvestorFlowSummary | null {
  if (days.length === 0) return null;
  const latest = days[days.length - 1];
  const last5 = days.slice(-5);
  const last20 = days.slice(-20);
  const prev15 = days.slice(-20, -5);

  const sum = (arr: InvestorFlowDay[], k: keyof InvestorFlowDay) =>
    arr.reduce((s, d) => s + (Number(d[k]) || 0), 0);
  const avg = (arr: InvestorFlowDay[], k: keyof InvestorFlowDay) =>
    arr.length > 0 ? sum(arr, k) / arr.length : 0;

  // 외국인 연속 일수 (최신일부터 역산)
  let buyStreak = 0;
  let sellStreak = 0;
  for (let i = days.length - 1; i >= 0; i -= 1) {
    const v = days[i].foreign;
    if (v > 0) {
      if (sellStreak > 0) break;
      buyStreak += 1;
    } else if (v < 0) {
      if (buyStreak > 0) break;
      sellStreak += 1;
    } else {
      break;
    }
  }

  // 20일 누적 극단 이벤트 — 영상5 "45~60조 매도" 맥락에서 3조 기준 선제 경고
  const net20 = sum(last20, 'foreign');
  const extreme: InvestorFlowSummary['foreignExtreme'] =
    net20 >= 30000 ? 'overheated' : net20 <= -30000 ? 'oversold' : 'neutral';

  return {
    market,
    latestDate: latest.date,
    foreignLatest: latest.foreign,
    foreignNet5D: parseFloat(sum(last5, 'foreign').toFixed(0)),
    foreignNet20D: parseFloat(sum(last20, 'foreign').toFixed(0)),
    institutionLatest: latest.institution,
    institutionNet5D: parseFloat(sum(last5, 'institution').toFixed(0)),
    pensionNet5D: parseFloat(sum(last5, 'pension').toFixed(0)),
    foreignTrend: parseFloat((avg(last5, 'foreign') - avg(prev15, 'foreign')).toFixed(1)),
    foreignBuyStreak: buyStreak,
    foreignSellStreak: sellStreak,
    foreignExtreme: extreme,
    days: last20,
  };
}
