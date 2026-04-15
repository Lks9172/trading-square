/**
 * 캔들 형태 분석 엔진 — 영상 3·4·5 "월봉 → 주봉 → 일봉" 위계적 판단.
 *
 * 캔들 형태 분류:
 * - 장대양봉 / 장대음봉 (body pct 큰 봉)
 * - 아래꼬리 (hammer-like, 매수 압력)
 * - 윗꼬리 (inverted hammer / shooting star, 매도 압력)
 * - 도지 (indecision)
 *
 * 영상 5 코스피 편 핵심 관찰:
 * "2025년 연봉 75% 장대양봉 + 2026-1~2 추가 50% → 아래꼬리 없음 → 2026-03 월봉 음봉 경고"
 * 이것이 우리 시스템이 포착해야 할 전형적 "소화 안 된 과열" 패턴이다.
 */

import { fetchYahooOHLC, OHLCCandle } from '../collectors/yahoo';

export interface CandleShape {
  bodyPct: number;       // |close-open| / (high-low) * 100
  upperWickPct: number;  // (high - max(open,close)) / (high-low) * 100
  lowerWickPct: number;  // (min(open,close) - low) / (high-low) * 100
  rangePct: number;      // (high - low) / close * 100
  isBullish: boolean;
  isLargeBody: boolean;   // body >= 60% of range
  isDoji: boolean;        // body < 10% of range
  isHammer: boolean;      // lower wick >= 60% + small body
  isInvertedHammer: boolean; // upper wick >= 60% + small body
  isMaru: boolean;        // body >= 90% (거의 꼬리 없음, 영상5 "아래꼬리 없는 장대양봉" 핵심)
}

export function analyzeCandle(c: OHLCCandle): CandleShape {
  const range = Math.max(c.high - c.low, 1e-9);
  const body = Math.abs(c.close - c.open);
  const bodyPct = (body / range) * 100;
  const upperWickPct = ((c.high - Math.max(c.open, c.close)) / range) * 100;
  const lowerWickPct = ((Math.min(c.open, c.close) - c.low) / range) * 100;
  const rangePct = (range / c.close) * 100;
  const isBullish = c.close > c.open;

  return {
    bodyPct: parseFloat(bodyPct.toFixed(2)),
    upperWickPct: parseFloat(upperWickPct.toFixed(2)),
    lowerWickPct: parseFloat(lowerWickPct.toFixed(2)),
    rangePct: parseFloat(rangePct.toFixed(2)),
    isBullish,
    isLargeBody: bodyPct >= 60,
    isDoji: bodyPct < 10,
    isHammer: bodyPct < 40 && lowerWickPct >= 60,
    isInvertedHammer: bodyPct < 40 && upperWickPct >= 60,
    isMaru: bodyPct >= 90,
  };
}

export interface MultiTimeframeSnapshot {
  symbol: string;
  daily: Array<OHLCCandle & { shape: CandleShape }>;
  weekly: Array<OHLCCandle & { shape: CandleShape }>;
  monthly: Array<OHLCCandle & { shape: CandleShape }>;
}

/** 최근 ~24개월 기간을 일/주/월봉으로 동시에 받아 shape까지 분석. */
export async function fetchMultiTimeframe(symbol: string): Promise<MultiTimeframeSnapshot | null> {
  const days = 760; // ~ 25개월
  const [daily, weekly, monthly] = await Promise.all([
    fetchYahooOHLC(symbol, days, '1d'),
    fetchYahooOHLC(symbol, days * 2, '1wk'),  // 주봉은 2년치 쯤
    fetchYahooOHLC(symbol, 365 * 10, '1mo'),  // 월봉은 10년치
  ]);
  if (daily.length === 0 && weekly.length === 0 && monthly.length === 0) return null;
  const withShape = (c: OHLCCandle) => ({ ...c, shape: analyzeCandle(c) });
  return {
    symbol,
    daily: daily.map(withShape),
    weekly: weekly.map(withShape),
    monthly: monthly.map(withShape),
  };
}

/** "영상5 패턴": 최근 N개월 연속 장대양봉 + 마지막 봉 아래꼬리 없는지 */
export function detectClimaxExhaustion(monthly: Array<OHLCCandle & { shape: CandleShape }>, n = 3): {
  consecutiveBullishLargeBody: number;
  latestNoLowerWick: boolean;
  warning: boolean;
} {
  const last = monthly.slice(-n);
  let cnt = 0;
  for (const c of last) {
    if (c.shape.isBullish && c.shape.isLargeBody) cnt += 1;
    else break;
  }
  const latest = monthly[monthly.length - 1];
  const latestNoLowerWick = latest ? latest.shape.lowerWickPct < 5 && latest.shape.isBullish : false;
  return {
    consecutiveBullishLargeBody: cnt,
    latestNoLowerWick,
    warning: cnt >= n && latestNoLowerWick,
  };
}

/** 최근 주봉의 반전 신호: 이전 상승 추세 후 장대음봉 */
export function detectWeeklyReversal(weekly: Array<OHLCCandle & { shape: CandleShape }>, lookback = 4): {
  previousUptrend: boolean;
  latestLargeBearish: boolean;
  reversalWarning: boolean;
} {
  if (weekly.length < lookback + 1) {
    return { previousUptrend: false, latestLargeBearish: false, reversalWarning: false };
  }
  const prev = weekly.slice(-(lookback + 1), -1);
  const latest = weekly[weekly.length - 1];
  const bullCnt = prev.filter((c) => c.shape.isBullish).length;
  const previousUptrend = bullCnt >= Math.ceil(lookback * 0.75);
  const latestLargeBearish = !latest.shape.isBullish && latest.shape.isLargeBody;
  return {
    previousUptrend,
    latestLargeBearish,
    reversalWarning: previousUptrend && latestLargeBearish,
  };
}

/** 월봉 기준 위치 지수: 최근 월봉의 종가가 최근 12개월 고점 대비 몇 % 위치 (0~1) */
export function monthlyPositionScore(monthly: Array<OHLCCandle & { shape: CandleShape }>): number | null {
  if (monthly.length < 2) return null;
  const last12 = monthly.slice(-12);
  const high = Math.max(...last12.map((c) => c.high));
  const low = Math.min(...last12.map((c) => c.low));
  const close = last12[last12.length - 1].close;
  if (high === low) return 0.5;
  return parseFloat(((close - low) / (high - low)).toFixed(3));
}
