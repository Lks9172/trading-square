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
  /** 영상5 §106 "Area Index": 아래꼬리/전체 높이 비율. 10% 미만 경고. */
  areaIndex: number;
  /** 영상3 §217 핀바: 한쪽 꼬리가 60%+ 이면서 반대쪽 꼬리는 <10%, 몸통 <30% */
  isPinBarBullish: boolean;  // 하방 핀바 (매수 반전 후보)
  isPinBarBearish: boolean;  // 상방 핀바 (매도 반전 후보)
}

export function analyzeCandle(c: OHLCCandle): CandleShape {
  const range = Math.max(c.high - c.low, 1e-9);
  const body = Math.abs(c.close - c.open);
  const bodyPct = (body / range) * 100;
  const upperWickPct = ((c.high - Math.max(c.open, c.close)) / range) * 100;
  const lowerWickPct = ((Math.min(c.open, c.close) - c.low) / range) * 100;
  const rangePct = (range / c.close) * 100;
  const isBullish = c.close > c.open;

  const isPinBarBullish = bodyPct < 30 && lowerWickPct >= 60 && upperWickPct < 10;
  const isPinBarBearish = bodyPct < 30 && upperWickPct >= 60 && lowerWickPct < 10;

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
    // Area Index — lowerWickPct 와 같은 값이지만 명시적으로 노출 (영상5 용어 그대로)
    areaIndex: parseFloat(lowerWickPct.toFixed(2)),
    isPinBarBullish,
    isPinBarBearish,
  };
}

/**
 * 아웃사이드 바 (영상3 §217): 현재 봉의 high/low 가 이전 봉을 완전히 감싸는 형태.
 * 상승 돌파 또는 하락 돌파의 강한 관성 시그널.
 */
export function detectOutsideBar(prev: OHLCCandle, cur: OHLCCandle): {
  isOutside: boolean;
  direction: 'bullish' | 'bearish' | 'none';
} {
  const isOutside = cur.high > prev.high && cur.low < prev.low;
  if (!isOutside) return { isOutside: false, direction: 'none' };
  const direction: 'bullish' | 'bearish' = cur.close > cur.open ? 'bullish' : 'bearish';
  return { isOutside: true, direction };
}

/**
 * W 반등 패턴 감지 (영상3 §W자 / 영상5 §101 "3차 W자 반등 저점에서 매수")
 *
 * 알고리즘 (엄격):
 *   1. 최근 lookback 일 내 두 개 local minimum (low1, low2) 찾기
 *   2. 두 저점 사이 local maximum (peak)
 *   3. low2 >= low1 × 0.97  (두번째 저점이 첫 저점 근처, 또는 약간 위)
 *   4. peak >= low1 × 1.05  (중간 반등이 최소 5%)
 *   5. 현재 close > peak × 1.005 (peak 돌파 확인 — neckline)
 *   6. 현재 close > low2 × 1.05  (두번째 저점 대비 최소 5% 반등)
 *
 * 모든 조건 충족 시 강한 W 반등 확인.
 */
export function detectWBottom(
  daily: OHLCCandle[],
  lookback = 90,
): { detected: boolean; low1?: number; low2?: number; peak?: number; breakout?: boolean; reason: string } {
  if (daily.length < lookback) return { detected: false, reason: 'insufficient data' };
  const slice = daily.slice(-lookback);
  const closes = slice.map((c) => c.close);
  const cur = closes[closes.length - 1];

  // 간단한 local min 찾기 (좌우 5일 비교)
  const window = 5;
  const mins: Array<{ idx: number; value: number }> = [];
  for (let i = window; i < closes.length - window; i += 1) {
    const v = closes[i];
    let isMin = true;
    for (let j = i - window; j <= i + window; j += 1) {
      if (j !== i && closes[j] < v) { isMin = false; break; }
    }
    if (isMin) mins.push({ idx: i, value: v });
  }
  if (mins.length < 2) return { detected: false, reason: `local mins ${mins.length}/2` };

  // 마지막 2개 local min 사용
  const low2Info = mins[mins.length - 1];
  const low1Info = mins[mins.length - 2];
  if (low2Info.idx - low1Info.idx < 10) return { detected: false, reason: 'mins too close' };

  // 중간 peak 찾기
  const between = closes.slice(low1Info.idx + 1, low2Info.idx);
  if (between.length === 0) return { detected: false, reason: 'no between' };
  const peak = Math.max(...between);

  const low1 = low1Info.value;
  const low2 = low2Info.value;
  const ratioCond = low2 >= low1 * 0.97;
  const peakCond = peak >= low1 * 1.05;
  const neckBreak = cur > peak * 1.005;
  const reboundCond = cur > low2 * 1.05;

  const detected = ratioCond && peakCond && neckBreak && reboundCond;
  return {
    detected,
    low1, low2, peak,
    breakout: neckBreak,
    reason: detected
      ? `W bottom confirmed (low1=${low1.toFixed(2)}, low2=${low2.toFixed(2)}, peak=${peak.toFixed(2)}, cur=${cur.toFixed(2)})`
      : `pattern incomplete (ratio=${ratioCond}, peak=${peakCond}, neck=${neckBreak}, rebound=${reboundCond})`,
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

/**
 * 장기 선형 회귀 채널 (영상3 §71/155 "나스닥은 15년 이상 장기 평행 채널 안에서 움직임").
 *
 * 월봉 N개 종가에 ordinary least squares 로 회귀선 + 표준편차 밴드 계산.
 * 현재 종가의 채널 내 위치 (0=하단, 0.5=중단, 1=상단) 반환.
 * 영상 원칙: "하단 지지선에서 매수 강도 강해지는 경향".
 */
export function linearRegressionChannel(
  monthly: OHLCCandle[],
  lookback = 180, // 15년 ≈ 180개월
): {
  position: number | null;
  slope: number;
  intercept: number;
  upperBand: number;
  midLine: number;
  lowerBand: number;
  curPrice: number;
} | null {
  if (monthly.length < Math.min(lookback, 60)) return null;
  const slice = monthly.slice(-lookback);
  const n = slice.length;
  const xs = slice.map((_, i) => i);
  const ys = slice.map((c) => c.close);
  const meanX = xs.reduce((a, b) => a + b, 0) / n;
  const meanY = ys.reduce((a, b) => a + b, 0) / n;
  let num = 0;
  let den = 0;
  for (let i = 0; i < n; i += 1) {
    num += (xs[i] - meanX) * (ys[i] - meanY);
    den += (xs[i] - meanX) ** 2;
  }
  const slope = den === 0 ? 0 : num / den;
  const intercept = meanY - slope * meanX;
  // 잔차 표준편차
  let sq = 0;
  for (let i = 0; i < n; i += 1) {
    const pred = slope * xs[i] + intercept;
    sq += (ys[i] - pred) ** 2;
  }
  const std = Math.sqrt(sq / n);
  const curX = n - 1;
  const midLine = slope * curX + intercept;
  const upperBand = midLine + std;
  const lowerBand = midLine - std;
  const curPrice = ys[n - 1];
  let position = (curPrice - lowerBand) / (upperBand - lowerBand);
  position = Math.max(0, Math.min(1, position));
  return {
    position: parseFloat(position.toFixed(3)),
    slope,
    intercept,
    upperBand: parseFloat(upperBand.toFixed(2)),
    midLine: parseFloat(midLine.toFixed(2)),
    lowerBand: parseFloat(lowerBand.toFixed(2)),
    curPrice,
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
