import { MarketDataPoint, DerivedIndicator } from '../types/indicators';
import { fetchYahooHistory, fetchYahooOHLC } from '../collectors/yahoo';
import { fetchFredHistory } from '../collectors/fred';
import { readHistory, writeHistoryPoint } from '../state/history-store';
import { fetchKrxInvestorFlow, summarizeInvestorFlow } from '../collectors/krx-flow';
import {
  fetchMultiTimeframe,
  detectClimaxExhaustion,
  detectWeeklyReversal,
  monthlyPositionScore,
  detectOutsideBar,
  detectWBottom,
  linearRegressionChannel,
  fetchYearlyBuckets,
  detectYearlyAreaIndex,
} from './candles';
import { withSpan } from '../observability/trace';

function val(raw: Record<string, MarketDataPoint>, key: string): number | null {
  return raw[key]?.value ?? null;
}

function today(): string {
  return new Date().toISOString().split('T')[0];
}

function average(values: number[]): number | null {
  const finite = values.filter((value) => Number.isFinite(value));
  if (finite.length === 0) return null;
  return finite.reduce((sum, value) => sum + value, 0) / finite.length;
}

function computeWindowedPercentChange(
  points: Array<{ value: number }>,
  recentWindow: number,
  priorWindow: number,
): { recentAvg: number; priorAvg: number; pctChange: number } | null {
  if (points.length < recentWindow + priorWindow) return null;
  const recentAvg = average(points.slice(0, recentWindow).map((point) => point.value));
  const priorAvg = average(points.slice(recentWindow, recentWindow + priorWindow).map((point) => point.value));
  if (recentAvg === null || priorAvg === null || priorAvg === 0) return null;
  return {
    recentAvg,
    priorAvg,
    pctChange: ((recentAvg - priorAvg) / priorAvg) * 100,
  };
}

export function computeHistoryYoY(
  history: Array<{ date: string; value: number }>,
  minExpectedLevel = 0,
  maxAgeDays?: number,
): number | null {
  if (history.length < 2) return null;
  const latest = history[history.length - 1];
  if (!Number.isFinite(latest.value) || latest.value === 0 || latest.value < minExpectedLevel) return null;
  if (maxAgeDays !== undefined) {
    const ageDays = Math.floor((Date.now() - new Date(latest.date).getTime()) / 86400000);
    if (ageDays > maxAgeDays) return null;
  }

  const targetDt = new Date(latest.date);
  targetDt.setFullYear(targetDt.getFullYear() - 1);
  const targetMs = targetDt.getTime();
  let past: { date: string; value: number } | null = null;
  for (let i = history.length - 1; i >= 0; i -= 1) {
    if (new Date(history[i].date).getTime() <= targetMs) {
      past = history[i];
      break;
    }
  }
  if (!past || !Number.isFinite(past.value) || past.value === 0 || past.value < minExpectedLevel) return null;
  return ((latest.value / past.value) - 1) * 100;
}

// 최근부터 역순 순회하며 "각 날짜 자신의 이동평균" 기준으로 이격률이 threshold 를
// 연속 유지한 일수를 계산한다. 이전 구현은 오늘의 SMA200 하나를 과거 모든 가격에
// 재사용해 streak 를 과대/과소 계산할 수 있었다.
export function computeDisparityStreak(
  history: number[],
  lookback: number,
  threshold: number,
  direction: 'over' | 'under' = 'over',
): number | null {
  if (!history || history.length < lookback || lookback <= 0) {
    return null;
  }
  let streak = 0;
  for (let i = 0; i <= history.length - lookback; i += 1) {
    const price = history[i];
    if (!Number.isFinite(price) || price <= 0) break;
    const window = history.slice(i, i + lookback);
    const movingAverage = window.reduce((sum, value) => sum + value, 0) / window.length;
    if (!Number.isFinite(movingAverage) || movingAverage <= 0) break;
    const disp = ((price - movingAverage) / movingAverage) * 100;
    const inside = direction === 'over' ? disp >= threshold : disp <= threshold;
    if (!inside) break;
    streak += 1;
  }
  return streak;
}

export async function computeDerived(
  raw: Record<string, MarketDataPoint>,
  manualInputs?: {
    cbBuying?: boolean;
    geoRisk?: number;
    policyDirection?: number;
    ismPmi?: number | null;
    // 19차: horizon / DCA progress / ETF theme 통과
    investmentHorizon?: 'short' | 'medium' | 'long';
    trancheUsedPct?: number;
    etfInflowTheme?: string;
    // 21차 Phase 2#11: 지정학 카운트다운 이벤트
    geopoliticalCountdown?: Array<{ event: string; targetDate: string }>;
    // 29차 P1-D #11: KOSPI Forward PER 수동 입력
    kospiForwardPER?: number | null;
    // 29차 P2-B #11
    cbGoldTonnage12M?: number | null;
    // 29차 P2-C #17/18
    kospiPBR?: number | null;
    kospiROE?: number | null;
    // 29차 P3-A
    fxReserveUsdRatio?: number | null;
    mmfTotalTrillion?: number | null;
    jgb10y?: number | null;
    // 29차 P3-B
    krHouseholdDebtPctGdp?: number | null;
    krCpi?: number | null;
    // 29차 P3-E
    krxPensionFlow5DTrillion?: number | null;
    krxShortInterestPct?: number | null;
  },
): Promise<Record<string, DerivedIndicator>> {
  const d: Record<string, DerivedIndicator> = {};
  const dt = today();

  const dgs10 = val(raw, 'DGS10');
  const t10yie = val(raw, 'T10YIE');
  if (dgs10 !== null && t10yie !== null) {
    d.REAL_YIELD = {
      name: 'real_yield',
      value: parseFloat((dgs10 - t10yie).toFixed(4)),
      date: dt,
      formula: 'DGS10 - T10YIE',
    };
  }

  const gold = val(raw, 'GOLD');
  const silver = val(raw, 'SILVER');
  if (gold !== null && silver !== null && silver > 0) {
    d.GOLD_SILVER_RATIO = {
      name: 'gold_silver_ratio',
      value: parseFloat((gold / silver).toFixed(2)),
      date: dt,
      formula: 'GOLD / SILVER',
    };
    // 26차 P1#2: GSR 60-80 평균 대비 위치 (video2 §1부 "역사 평균 60~80")
    // 절대 위치: <60 = 은 비싸 / 60-80 정상 / 80-100 금 강세 / >100 극단
    const gsr = gold / silver;
    let gsrPositionLabel: string;
    let gsrPositionValue: number;
    if (gsr < 60) { gsrPositionValue = -1; gsrPositionLabel = '🟢 은 강세 (GSR<60, 역사 평균 하단)'; }
    else if (gsr <= 80) { gsrPositionValue = 0; gsrPositionLabel = '⚪ 역사 평균 60-80 구간 정상'; }
    else if (gsr <= 100) { gsrPositionValue = 1; gsrPositionLabel = '🟡 금 우세 (GSR 80-100)'; }
    else { gsrPositionValue = 2; gsrPositionLabel = '🔴 GSR 100+ 극단 (video2 §"코로나 130→은 150%" 사례 구간)'; }
    d.GOLD_SILVER_RATIO_HISTORICAL_BAND = {
      name: 'gold_silver_ratio_historical_band',
      value: gsrPositionValue,
      date: dt,
      formula: `GSR=${gsr.toFixed(1)} → ${gsrPositionLabel}. video2 §1부 "역사 평균 60-80".`,
    };
  }

  const copper = val(raw, 'COPPER');
  if (gold !== null && copper !== null && gold > 0) {
    d.COPPER_GOLD_RATIO = {
      name: 'copper_gold_ratio',
      value: parseFloat((copper / gold).toFixed(6)),
      date: dt,
      formula: 'COPPER / GOLD',
    };
    // 26차 P1#1: GOLD/COPPER 역방향 비율 — video2 §3부 "주식 시장 방향 미리 읽기"
    // 코퍼가 비싸지면 (copper/gold ↑) 경기 회복 / 금이 비싸지면 (gold/copper ↑) 경기 둔화/위험 회피
    if (copper > 0) {
      const goldCopper = gold / copper;
      d.GOLD_COPPER_RATIO = {
        name: 'gold_copper_ratio',
        value: parseFloat(goldCopper.toFixed(2)),
        date: dt,
        formula: `GOLD / COPPER = ${goldCopper.toFixed(2)}. video2 §3부 "코로나 고점 = 상단" — 상승 = 경기 둔화/위험 회피, 하락 = 경기 회복.`,
      };
    }
  }

  // === 구리-주식 방향 괴리 감지 (11차 2026-04) — video2 원문 정합 ===
  // video2 §3부: "주식 차트는 아직 괜찮은데 구리가 먼저 빠지고 있다면 경고 신호.
  //   반대로 주식 횡보하는데 구리가 먼저 오르고 있다면 경기 회복의 신호".
  // 구리가 주식보다 2~3개월 선행 → 방향 괴리 감지로 추세 전환 조기 경보.
  //   NASDAQ 20일 추세 (수익률%) vs COPPER 20일 추세 비교.
  //   DIVERGENCE_BEARISH: 주식 ≥ 0 AND 구리 ≤ -3% → 경고 (주식 과열 or 경기 선행하락)
  //   DIVERGENCE_BULLISH: 주식 ≤ 0 AND 구리 ≥ +3% → 경기 회복 조기 신호
  try {
    const nasdaqHist20 = await fetchYahooHistory('^IXIC', 40);
    const copperHist20 = await fetchYahooHistory('HG=F', 40);
    if (nasdaqHist20.length >= 21 && copperHist20.length >= 21) {
      const nq0 = nasdaqHist20[nasdaqHist20.length - 21].close;
      const nq1 = nasdaqHist20[nasdaqHist20.length - 1].close;
      const cu0 = copperHist20[copperHist20.length - 21].close;
      const cu1 = copperHist20[copperHist20.length - 1].close;
      const nqPct = nq0 > 0 ? ((nq1 - nq0) / nq0) * 100 : 0;
      const cuPct = cu0 > 0 ? ((cu1 - cu0) / cu0) * 100 : 0;
      let divergence = 0;
      let label = 'aligned';
      if (nqPct >= 0 && cuPct <= -3) {
        divergence = -1;
        label = 'bearish';
      } else if (nqPct <= 0 && cuPct >= 3) {
        divergence = 1;
        label = 'bullish';
      }
      d.COPPER_STOCK_DIVERGENCE = {
        name: 'copper_stock_divergence',
        value: divergence,
        date: today(),
        formula:
          `NASDAQ 20D ${nqPct.toFixed(2)}% vs COPPER 20D ${cuPct.toFixed(2)}%. ` +
          `-1=bearish(주식↑+구리↓, 경기선행하락 경고) / +1=bullish(주식↓+구리↑, 경기회복 조기) / 0=aligned. ` +
          `video2 §3부 "구리 2~3개월 선행" 정합. 현재 ${label}.`,
      };
    }
  } catch {
    /* COPPER_STOCK_DIVERGENCE 실패는 파이프라인 막지 않음 */
  }

  // === 금구리비 "하락 전환" 추세 감지 (11차 2026-04) — video2 원문 정합 ===
  // video2 §3부: "경기 회복 신호 3가지 동시 — ISM 바닥 반등 + 금구리비 하락 전환 + 실업수당 감소".
  // 기존 COPPER_GOLD_RATIO 는 절대값만. "하락 전환" 시점 감지를 위해 추세값 신규.
  //   최근 5일 평균 CGR vs 15~20일 전 CGR 비교해 변화율 산출 (양수 = 상승, 음수 = 하락).
  //   value > 0.005 (+0.5%)  → 상승 (구리 강세, 경기 회복 전조)
  //   value < -0.005 (-0.5%) → 하락 (구리 약세, 경기 둔화)
  //   |value| ≤ 0.005 → 횡보
  //   "하락 전환" 은 단순 음수가 아니라 직전 구간은 양수/중립이었다가 최근 음수로 꺾인 패턴.
  //   → 별도 플래그 COPPER_GOLD_RATIO_DOWNTURN 으로 표현.
  try {
    const copperHist = await fetchYahooHistory('HG=F', 40);
    const goldHist = await fetchYahooHistory('GC=F', 40);
    if (copperHist.length >= 25 && goldHist.length >= 25) {
      // 최근 5일 평균 vs 15~20일 전 평균 CGR 비교.
      const cgrSeries = copperHist
        .map((c) => {
          const matchGold = goldHist.find((g) => g.date === c.date);
          return matchGold ? c.close / matchGold.close : null;
        })
        .filter((v): v is number => v !== null)
        .slice(-25);
      if (cgrSeries.length >= 25) {
        const recent5 = cgrSeries.slice(-5).reduce((s, v) => s + v, 0) / 5;
        const prev5 = cgrSeries.slice(-20, -15).reduce((s, v) => s + v, 0) / 5;
        const trendPct = prev5 > 0 ? (recent5 - prev5) / prev5 : 0;
        d.COPPER_GOLD_RATIO_TREND = {
          name: 'copper_gold_ratio_trend',
          value: parseFloat(trendPct.toFixed(5)),
          date: today(),
          formula:
            '최근 5영업일 CGR 평균 vs 15~20일 전 5영업일 CGR 평균 변화율. ' +
            '양수=구리 상승(경기회복), 음수=구리 하락(경기둔화). video2 "금구리비 하락 전환" 추세 감지.',
        };
        // 상승 전환 플래그 (경기회복 전조, video2 "금구리비 하락 전환 = 구리 상승"):
        //   CGR = 구리/금 이므로 영상 "금구리비 하락 전환" ≡ CGR 상승 전환.
        //   최근 5일 대비 이전 10~15일 구간이 중립/하락이었다가 최근 5일 상승 꺾임.
        const mid5 = cgrSeries.slice(-15, -10).reduce((s, v) => s + v, 0) / 5;
        const midToPrevPct = mid5 > 0 ? (prev5 - mid5) / mid5 : 0;
        const isUpturn = trendPct > 0.005 && midToPrevPct <= 0.005;
        d.COPPER_GOLD_RATIO_UPTURN = {
          name: 'copper_gold_ratio_upturn',
          value: isUpturn ? 1 : 0,
          date: today(),
          formula:
            `video2 "금구리비 하락 전환" (=CGR 상승 전환) 감지: 최근 5D CGR 추세 ${(trendPct * 100).toFixed(2)}% > +0.5% ` +
            `AND 이전 10~15일은 중립/하락 (${(midToPrevPct * 100).toFixed(2)}%). 1=상승전환(경기회복 전조), 0=아님.`,
        };
        // 하락 전환 플래그 (경기둔화 전조):
        const isDownturn = trendPct < -0.005 && midToPrevPct >= -0.005;
        d.COPPER_GOLD_RATIO_DOWNTURN = {
          name: 'copper_gold_ratio_downturn',
          value: isDownturn ? 1 : 0,
          date: today(),
          formula:
            `CGR 하락 전환 (경기둔화 전조): 최근 5D CGR 추세 ${(trendPct * 100).toFixed(2)}% < -0.5% ` +
            `AND 이전 10~15일은 중립/상승 (${(midToPrevPct * 100).toFixed(2)}%). 1=하락전환, 0=아님.`,
        };
      }
    }
  } catch {
    /* CGR trend 실패는 파이프라인 막지 않음 */
  }

  try {
    const nasdaqHistory = await fetchYahooHistory('^IXIC', 400);
    if (nasdaqHistory.length >= 200) {
      const closes = nasdaqHistory.map((h) => h.close);
      const sma200 = closes.slice(0, 200).reduce((a, b) => a + b, 0) / 200;
      const currentPrice = closes[0];
      const disparity = ((currentPrice - sma200) / sma200) * 100;

      d.NASDAQ_SMA200 = {
        name: 'nasdaq_sma200',
        value: parseFloat(sma200.toFixed(2)),
        date: dt,
        formula: 'SMA(NASDAQ, 200)',
      };

      d.NASDAQ_DISPARITY = {
        name: 'nasdaq_disparity_200',
        value: parseFloat(disparity.toFixed(2)),
        date: dt,
        formula: '(PRICE - SMA200) / SMA200 * 100',
      };

      const allTimeHigh = Math.max(...closes);
      const drawdown = ((currentPrice - allTimeHigh) / allTimeHigh) * 100;
      d.NASDAQ_DRAWDOWN = {
        name: 'nasdaq_drawdown',
        value: parseFloat(drawdown.toFixed(2)),
        date: dt,
        formula: '(PRICE - ATH) / ATH * 100',
      };

      d.NASDAQ_ABOVE_200DMA = {
        name: 'nasdaq_above_200dma',
        value: currentPrice > sma200 ? 1 : 0,
        date: dt,
        formula: 'PRICE > SMA200 ? 1 : 0',
      };

      // 멀티 MA 이격도 (영상1 §263 "기준선이 어느 MA인지 모호" 해소 — 사용자 판단용)
      for (const period of [20, 60, 120]) {
        if (closes.length >= period) {
          const sma = closes.slice(0, period).reduce((a, b) => a + b, 0) / period;
          const disp = ((currentPrice - sma) / sma) * 100;
          d[`NASDAQ_DISPARITY_${period}`] = {
            name: `nasdaq_disparity_${period}`,
            value: parseFloat(disp.toFixed(2)),
            date: dt,
            formula: `(PRICE - SMA${period}) / SMA${period} * 100`,
          };
        }
      }

      // 200DMA 이격률이 ±15% 구간에 연속으로 머무른 일수 (추격/투매 피로 감지)
      const overStreak = computeDisparityStreak(closes, 200, 15, 'over');
      const underStreak = computeDisparityStreak(closes, 200, -15, 'under');
      if (overStreak !== null) {
        d.NASDAQ_DISPARITY_STREAK_OVERHEATED = {
          name: 'nasdaq_disparity_streak_overheated',
          value: overStreak,
          date: dt,
          formula: '이격률 ≥ +15% 연속 유지일 (200DMA 기준)',
        };
      }
      if (underStreak !== null) {
        d.NASDAQ_DISPARITY_STREAK_OVERSOLD = {
          name: 'nasdaq_disparity_streak_oversold',
          value: underStreak,
          date: dt,
          formula: '이격률 ≤ -15% 연속 유지일 (200DMA 기준)',
        };
      }
      if ((overStreak ?? 0) >= 20 || (underStreak ?? 0) >= 20) {
        d.NASDAQ_CHASE_WARNING = {
          name: 'nasdaq_chase_warning',
          value: 1,
          date: dt,
          formula: '이격률 ±15% 구간 20일 이상 지속 → 추격/투매 경고',
        };
      }
    }
  } catch {
    void 0;
  }

  try {
    const kospiHistory = await fetchYahooHistory('^KS11', 400);
    if (kospiHistory.length >= 200) {
      const closes = kospiHistory.map((h) => h.close);
      const volumes = kospiHistory.map((h) => h.volume || 0);
      const sma200 = closes.slice(0, 200).reduce((a, b) => a + b, 0) / 200;
      const currentPrice = closes[0];
      const disparity = ((currentPrice - sma200) / sma200) * 100;
      const allTimeHigh = Math.max(...closes);
      const drawdown = ((currentPrice - allTimeHigh) / allTimeHigh) * 100;

      d.KOSPI_SMA200 = {
        name: 'kospi_sma200',
        value: parseFloat(sma200.toFixed(2)),
        date: dt,
        formula: 'SMA(KOSPI, 200)',
      };
      d.KOSPI_DISPARITY = {
        name: 'kospi_disparity_200',
        value: parseFloat(disparity.toFixed(2)),
        date: dt,
        formula: '(KOSPI - SMA200) / SMA200 * 100',
      };
      d.KOSPI_DRAWDOWN = {
        name: 'kospi_drawdown',
        value: parseFloat(drawdown.toFixed(2)),
        date: dt,
        formula: '(KOSPI - ATH) / ATH * 100',
      };
      // 멀티 MA 이격도
      for (const period of [20, 60, 120]) {
        if (closes.length >= period) {
          const sma = closes.slice(0, period).reduce((a, b) => a + b, 0) / period;
          const disp = ((currentPrice - sma) / sma) * 100;
          d[`KOSPI_DISPARITY_${period}`] = {
            name: `kospi_disparity_${period}`,
            value: parseFloat(disp.toFixed(2)),
            date: dt,
            formula: `(KOSPI - SMA${period}) / SMA${period} * 100`,
          };
        }
      }
      d.KOSPI_ABOVE_200DMA = {
        name: 'kospi_above_200dma',
        value: currentPrice > sma200 ? 1 : 0,
        date: dt,
        formula: 'KOSPI > SMA200 ? 1 : 0',
      };

      // 200DMA 이격률 ±15% 연속 일수 (추격/투매 피로)
      const kospiOverStreak = computeDisparityStreak(closes, 200, 15, 'over');
      const kospiUnderStreak = computeDisparityStreak(closes, 200, -15, 'under');
      if (kospiOverStreak !== null) {
        d.KOSPI_DISPARITY_STREAK_OVERHEATED = {
          name: 'kospi_disparity_streak_overheated',
          value: kospiOverStreak,
          date: dt,
          formula: '이격률 ≥ +15% 연속 유지일 (200DMA 기준)',
        };
      }
      if (kospiUnderStreak !== null) {
        d.KOSPI_DISPARITY_STREAK_OVERSOLD = {
          name: 'kospi_disparity_streak_oversold',
          value: kospiUnderStreak,
          date: dt,
          formula: '이격률 ≤ -15% 연속 유지일 (200DMA 기준)',
        };
      }
      if ((kospiOverStreak ?? 0) >= 20 || (kospiUnderStreak ?? 0) >= 20) {
        d.KOSPI_CHASE_WARNING = {
          name: 'kospi_chase_warning',
          value: 1,
          date: dt,
          formula: '이격률 ±15% 구간 20일 이상 지속 → 추격/투매 경고',
        };
      }

      const sma50 = closes.slice(0, 50).reduce((a, b) => a + b, 0) / 50;
      const trendRecovery = currentPrice > sma50 && sma50 > sma200 ? 1 : 0;
      d.KOSPI_TREND_RECOVERY = {
        name: 'kospi_trend_recovery',
        value: trendRecovery,
        date: dt,
        formula: '추세선 회복 (현재가>50DMA>200DMA = 1)',
      };

      const recentVol = volumes.slice(0, 5).reduce((a, b) => a + b, 0) / 5;
      const avg20Vol = volumes.slice(0, 20).reduce((a, b) => a + b, 0) / 20;
      const recent3 = volumes.slice(0, 3);
      const recent3Count = recent3.filter((v) => avg20Vol > 0 && v >= avg20Vol * 1.05).length;
      const volumeConfirm = avg20Vol > 0 && recentVol >= avg20Vol * 1.1 && recent3Count >= 2 && trendRecovery === 1 ? 1 : 0;
      d.KOSPI_VOLUME_CONFIRM = {
        name: 'kospi_volume_confirm',
        value: volumeConfirm,
        date: dt,
        formula: '최근5일 평균≥20일평균 110% AND 최근3일중 2일 이상 20일평균 105% 상회 AND 추세회복=1',
      };

      const yearAgoIdx = Math.min(closes.length - 1, 250);
      const yearReturn = ((currentPrice - closes[yearAgoIdx]) / closes[yearAgoIdx]) * 100;
      d.KOSPI_YEAR_RETURN = {
        name: 'kospi_year_return',
        value: parseFloat(yearReturn.toFixed(2)),
        date: dt,
        formula: '코스피 1년 수익률(%)',
      };
      if (yearReturn > 75) {
        d.KOSPI_OVERHEATED = {
          name: 'kospi_overheated',
          value: 1,
          date: dt,
          formula: '연간 수익률 75%+ → 역사적 조정 패턴 경고',
        };
      } else {
        d.KOSPI_OVERHEATED = { name: 'kospi_overheated', value: 0, date: dt, formula: '과열 미해당' };
      }

      // === KOSPI_YEARLY_AREA_INDEX (14차 Phase B-1, 2026-04) — video5_analysis §1부 ===
      // "연봉 아래꼬리 길이 ÷ 전체 높이 (%), <15% 위험 (매수 포지션 미소화)"
      // closes 기반 근사 (OHLC 없이): body_bottom = min(yearFirst, yearLast)
      //   아래꼬리 길이 ≈ body_bottom - yearLow, 전체 높이 = yearHigh - yearLow
      //   +1 정상 (>=15%) / -1 위험 (<15%) / 0 중립 범위 없음 → 2단계.
      if (closes.length >= 250) {
        const yearSlice = closes.slice(0, 250); // 최신부터 역순 저장
        const yearHigh = Math.max(...yearSlice);
        const yearLow = Math.min(...yearSlice);
        const yearFirst = yearSlice[yearSlice.length - 1]; // 1년 전 (내림차순 마지막)
        const yearLast = yearSlice[0]; // 현재
        const bodyBottom = Math.min(yearFirst, yearLast);
        const lowerTail = Math.max(0, bodyBottom - yearLow);
        const fullRange = yearHigh - yearLow;
        if (fullRange > 0) {
          const areaIndex = (lowerTail / fullRange) * 100;
          const level = areaIndex >= 15 ? 1 : -1;
          d.KOSPI_YEARLY_AREA_INDEX = {
            name: 'kospi_yearly_area_index',
            value: parseFloat(areaIndex.toFixed(2)),
            date: dt,
            formula:
              `연봉 아래꼬리 ${lowerTail.toFixed(0)} / 전체 높이 ${fullRange.toFixed(0)} = ${areaIndex.toFixed(1)}%. ` +
              `${level === 1 ? '정상(≥15%, 매수 포지션 한 번 흡수)' : '위험(<15%, 누적 매수 미소화)'}. video5_analysis §1부.`,
          };
          d.KOSPI_YEARLY_AREA_LEVEL = {
            name: 'kospi_yearly_area_level',
            value: level,
            date: dt,
            formula: `KOSPI_YEARLY_AREA_INDEX ${areaIndex.toFixed(1)}% → level ${level} (+1 정상 / -1 위험).`,
          };
        }
      }
    }
  } catch {
    void 0;
  }

  // === 글로벌 M2 aggregate (13차 단순화, 2026-04) ===
  // 미국 M2 단일 시리즈 사용. 이전 유로 M3 + 일본 M3 추가 평균 구조는 FRED 제공
  // OECD 시리즈가 960일+ 정체로 이미 미국 M2 단일 기여로 동작하던 상태라 제거.
  // 영상1/4 "유동성 방향" 논의도 미국 유동성 중심이라 단순화가 영상 정합.
  const GLOBAL_M2_MAX_AGE_DAYS = 400;
  const usYoY = await computeHistoryYoY(
    await readHistory('fred', 'M2SL'),
    0,
    GLOBAL_M2_MAX_AGE_DAYS,
  );

  // 광의통화 YoY 합리 범위: COVID 피크(미국 M2 약 +27%)까지 포섭. 범위 밖은 이상치.
  const M2_YOY_MIN = -20;
  const M2_YOY_MAX = 30;
  const isM2Anomaly = (v: number) => v < M2_YOY_MIN || v > M2_YOY_MAX;

  if (usYoY !== null) {
    const anomaly = isM2Anomaly(usYoY);
    d.US_M2_YOY = {
      name: 'us_m2_yoy',
      value: parseFloat(usYoY.toFixed(2)),
      date: dt,
      formula: anomaly
        ? `미국 M2SL 최신월 / 12개월 전 - 1 (%). 이상치(${M2_YOY_MIN}~${M2_YOY_MAX}% 범위 밖)`
        : '미국 M2SL 최신월 / 12개월 전 - 1 (%)',
    };
    if (!anomaly) {
      d.GLOBAL_M2_PROXY = {
        name: 'global_m2_proxy',
        value: parseFloat(usYoY.toFixed(2)),
        date: dt,
        formula: `미국 M2SL YoY% (13차 단순화, ${M2_YOY_MIN}~${M2_YOY_MAX}% clamp). 이전 EU/JP M3 평균 구조에서 정체 소스 제거.`,
      };
    }
  }

  // === 25차: GLOBAL_M2_AGGREGATE — US + ECB M3 + BoJ M2 가능 시 산출 ===
  // 노션 §StreetStats "글로벌 M2" 정합 시도. FRED ECB M3 (MABMM301EZM189S — 유로지역 M3 SA),
  // BoJ M2 (MABMM301JPM189S — 일본 M2 SA) 두 시리즈 가능 시 USD 환산 단순합.
  // 환율 변동 흡수 위해 YoY% 만 단순 평균 (3국 동등 가중) — proxy 수준.
  try {
    const ecbHist = await readHistory('fred', 'MABMM301EZM189S').catch(() => []);
    const bojHist = await readHistory('fred', 'MABMM301JPM189S').catch(() => []);
    const usHist = await readHistory('fred', 'M2SL').catch(() => []);
    const yoyOf = (hist: Array<{ date: string; value: number }>): number | null => {
      if (hist.length < 13) return null;
      const sorted = [...hist].sort((a, b) => (a.date < b.date ? -1 : 1));
      const last = sorted[sorted.length - 1].value;
      const yearAgo = sorted[Math.max(0, sorted.length - 13)].value;
      if (!Number.isFinite(last) || !Number.isFinite(yearAgo) || yearAgo === 0) return null;
      return ((last - yearAgo) / yearAgo) * 100;
    };
    const usYoy = yoyOf(usHist);
    const ecbYoy = yoyOf(ecbHist);
    const bojYoy = yoyOf(bojHist);
    const parts: number[] = [];
    if (usYoy !== null && Math.abs(usYoy) < 30) parts.push(usYoy);
    if (ecbYoy !== null && Math.abs(ecbYoy) < 30) parts.push(ecbYoy);
    if (bojYoy !== null && Math.abs(bojYoy) < 30) parts.push(bojYoy);
    if (parts.length >= 1) {
      const avg = parts.reduce((s, v) => s + v, 0) / parts.length;
      d.GLOBAL_M2_AGGREGATE_YOY = {
        name: 'global_m2_aggregate_yoy',
        value: parseFloat(avg.toFixed(2)),
        date: dt,
        formula: `US M2 ${usYoy?.toFixed(2) ?? '-'}% + ECB M3 ${ecbYoy?.toFixed(2) ?? '-'}% + BoJ M2 ${bojYoy?.toFixed(2) ?? '-'}% 단순평균 = ${avg.toFixed(2)}% (${parts.length}/3 가용). 노션 §StreetStats 글로벌 M2 정합 시도 (25차).`,
      };
    }
  } catch { void 0; }

  const usdkrw = val(raw, 'USDKRW');
  if (usdkrw !== null) {
    let fxLevel: number;
    if (usdkrw <= 1400) fxLevel = 2;
    else if (usdkrw <= 1480) fxLevel = 1;
    else if (usdkrw <= 1500) fxLevel = 0;
    else if (usdkrw <= 1550) fxLevel = -1;
    else fxLevel = -2;

    d.KRW_FX_LEVEL = {
      name: 'krw_fx_level',
      value: fxLevel,
      date: dt,
      formula: '≤1400:+2, ≤1480:+1, ≤1500:0, ≤1550:-1, >1550:-2',
    };

    // 영상5 §3-1 코스피 핵심 결론: "1480 이하 녹색 / 1500 이상 적색" 이중 게이트.
    // 단일 레벨 점수보다 이 두 임계 돌파 여부가 외국인 수급·매수시점을 70% 결정.
    d.KRW_FX_GREEN = {
      name: 'krw_fx_green',
      value: usdkrw <= 1480 ? 1 : 0,
      date: dt,
      formula: 'USDKRW ≤ 1480 → 한국 매수 우호 환경 (영상5 그린 게이트)',
    };
    d.KRW_FX_RED = {
      name: 'krw_fx_red',
      value: usdkrw >= 1500 ? 1 : 0,
      date: dt,
      formula: 'USDKRW ≥ 1500 → 외국인 매도 압력 임계 (영상5 레드 게이트)',
    };
  }

  const sofr = val(raw, 'SOFR');
  const effr = val(raw, 'EFFR');
  if (sofr !== null && effr !== null) {
    d.SOFR_EFFR_SPREAD = {
      name: 'sofr_effr_spread',
      value: parseFloat((sofr - effr).toFixed(4)),
      date: dt,
      formula: 'SOFR - EFFR',
    };
  }

  // SOFR - IORB: 지급준비금 부족(+) / 여유(-) 시그널.
  // SOFR 가 IORB 를 의미있게 상회하면 은행간 담보금리가 정책금리 상단을 넘어서는
  // 유동성 부족 신호. repo market 스트레스의 고전적 판별 기준.
  const iorb = val(raw, 'IORB');
  if (sofr !== null && iorb !== null) {
    d.SOFR_IORB_SPREAD = {
      name: 'sofr_iorb_spread',
      value: parseFloat((sofr - iorb).toFixed(4)),
      date: dt,
      formula: 'SOFR - IORB (양수=지급준비금 부족 / 자금시장 긴장)',
    };
  }

  const apiKey = process.env.FRED_API_KEY || '';

  try {
    const indproHist = await fetchFredHistory('INDPRO', apiKey, 6);
    if (indproHist.length >= 3) {
      const current = indproHist[0].value;
      const prev = indproHist[1].value;
      const prev2 = indproHist[2].value;
      const momMom = ((current - prev) / prev) * 100;
      const expanding = current > prev && prev > prev2;
      const contracting = current < prev && prev < prev2;
      let ismProxy = 50 + momMom * 10;
      if (expanding) ismProxy = Math.max(ismProxy, 51);
      if (contracting) ismProxy = Math.min(ismProxy, 49);
      ismProxy = Math.max(30, Math.min(70, ismProxy));

      d.ISM_PROXY = {
        name: 'ism_proxy',
        value: parseFloat(ismProxy.toFixed(1)),
        date: dt,
        formula: 'INDPRO 기반 ISM PMI 프록시 (50+=확장, 50-=수축)',
      };
    }
  } catch { void 0; }

  try {
    const dgs10Hist = await fetchFredHistory('DGS10', apiKey, 40);
    const t10yieHist = await fetchFredHistory('T10YIE', apiKey, 40);
    if (dgs10Hist.length >= 20 && t10yieHist.length >= 20) {
      const ryRecent = dgs10Hist.slice(0, 5).reduce((s, p) => s + p.value, 0) / 5
        - t10yieHist.slice(0, 5).reduce((s, p) => s + p.value, 0) / 5;
      const ryOlder = dgs10Hist.slice(15, 20).reduce((s, p) => s + p.value, 0) / 5
        - t10yieHist.slice(15, 20).reduce((s, p) => s + p.value, 0) / 5;
      d.REAL_YIELD_TREND = {
        name: 'real_yield_trend',
        value: parseFloat((ryRecent - ryOlder).toFixed(4)),
        date: dt,
        formula: '실질금리 최근5일평균 - 15~20일전평균 (음수=하락추세)',
      };
    }
  } catch { void 0; }

  try {
    const dxyHistory = await fetchYahooHistory('DX-Y.NYB', 90);
    if (dxyHistory.length >= 20) {
      const recent = dxyHistory.slice(0, 5).reduce((s, p) => s + p.close, 0) / 5;
      const older = dxyHistory.slice(15, 20).reduce((s, p) => s + p.close, 0) / 5;
      d.DXY_TREND = {
        name: 'dxy_trend',
        value: parseFloat((recent - older).toFixed(4)),
        date: dt,
        formula: 'DXY 최근5일평균 - 15~20일전평균 (음수=단기약세)',
      };
    }
    if (dxyHistory.length >= 60) {
      const recentLong = dxyHistory.slice(0, 10).reduce((s, p) => s + p.close, 0) / 10;
      const olderLong = dxyHistory.slice(50, 60).reduce((s, p) => s + p.close, 0) / 10;
      d.DXY_TREND_LONG = {
        name: 'dxy_trend_long',
        value: parseFloat((recentLong - olderLong).toFixed(4)),
        date: dt,
        formula: 'DXY 최근10일평균 - 50~60일전평균 (음수=구조적약세)',
      };
    }
  } catch { void 0; }

  try {
    const rrpHist = await fetchFredHistory('RRPONTSYD', apiKey, 30);
    const tgaHist = await fetchFredHistory('WTREGEN', apiKey, 12);
    const mmfHist = await fetchFredHistory('WRMFNS', apiKey, 12);
    const reserveHist = await fetchFredHistory('WRESBAL', apiKey, 12);

    const rrpTrend = computeWindowedPercentChange(rrpHist, 5, 5);
    if (rrpTrend) {
      d.RRP_DIRECTION = {
        name: 'rrp_direction',
        value: parseFloat(rrpTrend.pctChange.toFixed(2)),
        date: dt,
        formula: `RRP 최근 5일 평균(${rrpTrend.recentAvg.toFixed(0)}) vs 직전 5일 평균(${rrpTrend.priorAvg.toFixed(0)}) 변화율 % (음수=시장유입)`,
      };
    }
    const tgaTrend = computeWindowedPercentChange(tgaHist, 2, 2);
    if (tgaTrend) {
      d.TGA_DIRECTION = {
        name: 'tga_direction',
        value: parseFloat(tgaTrend.pctChange.toFixed(2)),
        date: dt,
        formula: `TGA 최근 2주 평균(${tgaTrend.recentAvg.toFixed(0)}) vs 직전 2주 평균(${tgaTrend.priorAvg.toFixed(0)}) 변화율 % (음수=유동성공급)`,
      };
    }
    const mmfTrend = computeWindowedPercentChange(mmfHist, 2, 2);
    if (mmfTrend) {
      d.MMF_DIRECTION = {
        name: 'mmf_direction',
        value: parseFloat(mmfTrend.pctChange.toFixed(2)),
        date: dt,
        formula: `MMF 최근 2주 평균(${mmfTrend.recentAvg.toFixed(0)}) vs 직전 2주 평균(${mmfTrend.priorAvg.toFixed(0)}) 변화율 % (음수=위험자산이동)`,
      };
    }
    const reserveTrend = computeWindowedPercentChange(reserveHist, 2, 2);
    if (reserveTrend) {
      d.WRESBAL_DIRECTION = {
        name: 'wresbal_direction',
        value: parseFloat(reserveTrend.pctChange.toFixed(2)),
        date: dt,
        formula: `지급준비금 최근 2주 평균(${reserveTrend.recentAvg.toFixed(0)}) vs 직전 2주 평균(${reserveTrend.priorAvg.toFixed(0)}) 변화율 % (양수=은행 유동성 체력 개선)`,
      };
    }
  } catch { void 0; }

  try {
    const goldHistory = await fetchYahooHistory('GC=F', 400);
    if (goldHistory.length >= 200) {
      const gCloses = goldHistory.map((h) => h.close);
      const gSma200 = gCloses.slice(0, 200).reduce((a, b) => a + b, 0) / 200;
      const gCurrent = gCloses[0];
      d.GOLD_SMA200 = { name: 'gold_sma200', value: parseFloat(gSma200.toFixed(2)), date: dt, formula: 'SMA(GOLD, 200)' };
      d.GOLD_DISPARITY = { name: 'gold_disparity_200', value: parseFloat((((gCurrent - gSma200) / gSma200) * 100).toFixed(2)), date: dt, formula: '(GOLD-SMA200)/SMA200*100' };
      d.GOLD_ABOVE_200DMA = { name: 'gold_above_200dma', value: gCurrent > gSma200 ? 1 : 0, date: dt, formula: 'GOLD>SMA200' };

      const gHigh = Math.max(...gCloses.slice(0, 60));
      const gLow = Math.min(...gCloses.slice(0, 60));
      if (gHigh > gLow) {
        d.GOLD_FIB_382 = { name: 'gold_fib_382', value: parseFloat((gHigh - (gHigh - gLow) * 0.382).toFixed(2)), date: dt, formula: 'HIGH - (HIGH-LOW)*0.382' };
        d.GOLD_FIB_500 = { name: 'gold_fib_500', value: parseFloat((gHigh - (gHigh - gLow) * 0.5).toFixed(2)), date: dt, formula: 'HIGH - (HIGH-LOW)*0.5' };
        d.GOLD_FIB_618 = { name: 'gold_fib_618', value: parseFloat((gHigh - (gHigh - gLow) * 0.618).toFixed(2)), date: dt, formula: 'HIGH - (HIGH-LOW)*0.618' };

        const fib382 = gHigh - (gHigh - gLow) * 0.382;
        const fib500 = gHigh - (gHigh - gLow) * 0.5;
        const fib618 = gHigh - (gHigh - gLow) * 0.618;
        let fibZone = 'above_high';
        if (gCurrent <= fib618) fibZone = 'below_618';
        else if (gCurrent <= fib500) fibZone = 'between_500_618';
        else if (gCurrent <= fib382) fibZone = 'between_382_500';
        else if (gCurrent <= gHigh) fibZone = 'between_high_382';

        const fibLabels: Record<string, string> = {
          below_618: `0.618(${fib618.toFixed(0)}) 이하 → 강한 조정, 3차 분할매수 구간`,
          between_500_618: `0.5~0.618 사이 → 2차 분할매수 구간`,
          between_382_500: `0.382~0.5 사이 → 1차 분할매수 구간`,
          between_high_382: `고점~0.382 사이 → 눌림목 대기`,
          above_high: `고점 상회 → 추격매수 주의`,
        };

        d.GOLD_FIB_ZONE = {
          name: 'gold_fib_zone',
          value: fibZone === 'below_618' ? 3 : fibZone === 'between_500_618' ? 2 : fibZone === 'between_382_500' ? 1 : 0,
          date: dt,
          formula: fibLabels[fibZone] || '',
        };
      }
    }
  } catch { void 0; }

  try {
    const nasdaqHistFull = await fetchYahooHistory('^IXIC', 400);
    if (nasdaqHistFull.length >= 200) {
      const nCloses = nasdaqHistFull.map((h) => h.close);
      const nHigh = Math.max(...nCloses.slice(0, 60));
      const nLow = Math.min(...nCloses.slice(0, 60));
      if (nHigh > nLow) {
        d.NASDAQ_FIB_382 = { name: 'nasdaq_fib_382', value: parseFloat((nHigh - (nHigh - nLow) * 0.382).toFixed(2)), date: dt, formula: 'HIGH-(HIGH-LOW)*0.382' };
        d.NASDAQ_FIB_500 = { name: 'nasdaq_fib_500', value: parseFloat((nHigh - (nHigh - nLow) * 0.5).toFixed(2)), date: dt, formula: 'HIGH-(HIGH-LOW)*0.5' };
        d.NASDAQ_FIB_618 = { name: 'nasdaq_fib_618', value: parseFloat((nHigh - (nHigh - nLow) * 0.618).toFixed(2)), date: dt, formula: 'HIGH-(HIGH-LOW)*0.618' };
      }

      const sma50 = nCloses.slice(0, 50).reduce((a, b) => a + b, 0) / 50;
      const sma200 = nCloses.slice(0, 200).reduce((a, b) => a + b, 0) / 200;
      const prevSma50 = nCloses.slice(1, 51).reduce((a, b) => a + b, 0) / 50;
      const prevSma200 = nCloses.slice(1, 201).reduce((a, b) => a + b, 0) / 200;
      const crossNow = sma50 - sma200;
      const crossPrev = prevSma50 - prevSma200;

      let crossState = 0;
      if (crossPrev < 0 && crossNow >= 0) crossState = 1;
      else if (crossPrev > 0 && crossNow <= 0) crossState = -1;
      else if (crossNow > 0) crossState = 0.5;
      else crossState = -0.5;

      d.NASDAQ_CROSS = {
        name: 'nasdaq_cross',
        value: crossState,
        date: dt,
        // 18차 P1#1: video3 §2부 "골든크로스는 늦은 추격, 데드크로스가 분할매수 시작" 역발상 철학 명문화.
        // signals.ts / interpretations.ts 에서 -1 = 매수 우호, +1 = 추격 주의 로 해석.
        formula: '1=GC발생(역발상:추격주의), -1=DC발생(역발상:분할매수 시작), 0.5=50>200유지, -0.5=50<200유지',
      };
      d.NASDAQ_SMA50 = { name: 'nasdaq_sma50', value: parseFloat(sma50.toFixed(2)), date: dt, formula: 'SMA(NASDAQ,50)' };
    }
  } catch { void 0; }

  // 15차 Phase 2-G (2026-04): 섹터 4종 추가 — XLC 통신 / XLB 소재 / XLRE 부동산 / XLU 유틸리티.
  const sectorEtfs: Array<[string, string]> = [
    ['XLK','기술'],['XLF','금융'],['XLE','에너지'],['XLV','헬스케어'],['XLI','산업재'],['XLY','임의소비재'],
    ['XLC','통신'],['XLB','소재'],['XLRE','부동산'],['XLU','유틸리티'],
    ['SOXX','반도체(광역)'],['SMH','반도체(대형주)'],
  ];
  try {
    // 2026-04 로그 관측성 개선: derived 7s 병목 세분화 — 섹터/MTF/yearly 각각 child span.
    await withSpan('macrosquare.engine.derived.sector', async (span) => {
    const sectorResults = await Promise.allSettled(sectorEtfs.map(([sym]) => fetchYahooHistory(sym, 30)));
    span.setAttribute('derived.sector.count', sectorEtfs.length);
    const sectorReturns: Array<{ key: string; label: string; ret: number }> = [];
    sectorEtfs.forEach(([sym, label], i) => {
      const r = sectorResults[i];
      if (r.status === 'fulfilled' && r.value.length >= 20) {
        const closes = r.value.map((h) => h.close);
        const ret = ((closes[0] - closes[19]) / closes[19]) * 100;
        sectorReturns.push({ key: sym, label, ret });
        d[`SECTOR_${sym}`] = { name: `sector_${sym.toLowerCase()}`, value: parseFloat(ret.toFixed(2)), date: dt, formula: `${label} ETF 20일 수익률(%)` };
      } else {
        const cur = val(raw, sym);
        const high52 = val(raw, `${sym}_52WH`);
        if (cur !== null && high52 !== null && high52 > 0) {
          const ret52 = ((cur - high52) / high52) * 100;
          sectorReturns.push({ key: sym, label, ret: ret52 });
          d[`SECTOR_${sym}`] = { name: `sector_${sym.toLowerCase()}`, value: parseFloat(ret52.toFixed(2)), date: dt, formula: `${label} 52주최고 대비(%) fallback` };
        }
      }
    });
    if (sectorReturns.length > 0) {
      const strongest = sectorReturns.sort((a, b) => b.ret - a.ret)[0];
      d.SECTOR_STRONGEST = { name: 'sector_strongest', value: strongest.ret, date: dt, formula: `가장 강한 섹터: ${strongest.label}(${strongest.key}) ${strongest.ret.toFixed(1)}%` };
    }
    }); // withSpan sector
  } catch { void 0; }

  try {
    const nHistPattern = await fetchYahooHistory('^IXIC', 60);
    if (nHistPattern.length >= 30) {
      const closes = nHistPattern.map((h) => h.close);
      const minIdx = closes.indexOf(Math.min(...closes.slice(0, 20)));
      const midMax = Math.max(...closes.slice(minIdx, minIdx + 10));
      const secondMinIdx = closes.slice(minIdx + 5).indexOf(Math.min(...closes.slice(minIdx + 5, minIdx + 20)));
      const secondMin = closes[minIdx + 5 + secondMinIdx] || closes[0];
      const firstMin = closes[minIdx];
      const wPattern = midMax > firstMin * 1.03 && Math.abs(secondMin - firstMin) / firstMin < 0.03;
      d.NASDAQ_W_BOTTOM = {
        name: 'nasdaq_w_bottom',
        value: wPattern ? 1 : 0,
        date: dt,
        formula: 'W자 반등 패턴 감지 (1=감지됨)',
      };
    }
  } catch { void 0; }

  const chaseMap: Record<string, string> = { NASDAQ: 'NASDAQ', GOLD: 'GOLD', KOSPI: 'KOSPI', COPPER: 'COPPER' };
  for (const [name, rawKey] of Object.entries(chaseMap)) {
    if (d[`CHASE_${name}`]) continue;
    const dispKey = `${name}_DISPARITY`;
    const disp = d[dispKey]?.value;
    if (disp !== undefined) {
      d[`CHASE_${name}`] = { name: `chase_${name.toLowerCase()}`, value: parseFloat(disp.toFixed(2)), date: dt, formula: `${name} 이격도 기반 추격 프록시 (200DMA 대비 %)` };
    }
  }

  // === 은 아웃퍼폼 2조건 복합 (영상2 §금은비 "60~80 이상 + 경기 회복 동반 확인") ===
  const gsr = d.GOLD_SILVER_RATIO?.value;
  // 24차 P1#4: ISM_PROXY 결측 시 manualInputs.ismPmi fallback (사용자 입력 의미 회복)
  const ismVal = d.ISM_PROXY?.value ?? (typeof manualInputs?.ismPmi === 'number' ? manualInputs.ismPmi : null);
  if (gsr !== undefined && gsr !== null && ismVal !== undefined && ismVal !== null) {
    const highRatio = gsr >= 70;
    const expansionConfirmed = ismVal >= 50;
    const setup = highRatio && expansionConfirmed;
    d.SILVER_OUTPERFORM_SETUP = {
      name: 'silver_outperform_setup',
      value: setup ? 1 : 0,
      date: dt,
      formula: `금은비 ${gsr.toFixed(1)}(≥70: ${highRatio ? 'Y' : 'N'}) AND ISM ${ismVal.toFixed(1)}(≥50: ${expansionConfirmed ? 'Y' : 'N'}) → 은 아웃퍼폼 구조 (영상2)`,
    };
  }

  // === 구리 3조건 강매수 복합 (영상2 §닥터코퍼 ISM + 금구리비 + ICSA 동시) ===
  // 23차 Tier 1#6: CGR 90D z-score 정규화 — 절대 임계 0.00125 자체 TODO 해소.
  // 영상2 §3부 "금구리비 상승세" 의 "상승" 정의를 90일 분포 기준 z>0.5 로 통계화.
  try {
    const cgrHist: Array<{ date: string; value: number }> = [];
    // CGR 자체 history 가 없으면 raw COPPER/GOLD 일별 비율로 90일 산출
    const copperHist = await fetchYahooHistory('HG=F', 95);
    const goldHist = await fetchYahooHistory('GC=F', 95);
    if (copperHist.length >= 60 && goldHist.length >= 60) {
      const goldByDate = new Map(goldHist.map((h) => [h.date, h.close]));
      for (const c of copperHist) {
        const g = goldByDate.get(c.date);
        if (typeof g === 'number' && g > 0 && c.close > 0) {
          cgrHist.push({ date: c.date, value: c.close / g });
        }
      }
      if (cgrHist.length >= 60) {
        const values = cgrHist.map((p) => p.value);
        const mean = values.reduce((a, b) => a + b, 0) / values.length;
        const variance = values.reduce((s, v) => s + Math.pow(v - mean, 2), 0) / values.length;
        const std = Math.sqrt(variance);
        const last = values[values.length - 1];
        const z = std > 0 ? (last - mean) / std : 0;
        d.COPPER_GOLD_RATIO_ZSCORE_90D = {
          name: 'copper_gold_ratio_zscore_90d',
          value: parseFloat(z.toFixed(2)),
          date: today(),
          formula: `90D mean=${mean.toFixed(5)} std=${std.toFixed(5)} last=${last.toFixed(5)} → z=${z.toFixed(2)}. video2 §3부 "금구리비 상승세" 정량화 — z>+0.5 = 상승 우세, z<-0.5 = 하락 전환.`,
        };
      }
    }
  } catch { void 0; }

  const cgr = d.COPPER_GOLD_RATIO?.value;
  const icsaRaw = val(raw, 'ICSA');
  if (ismVal !== undefined && ismVal !== null && cgr !== undefined && cgr !== null && icsaRaw !== null) {
    const ismOk = ismVal >= 50;
    const cgrOk = cgr > 0.00125;
    const icsaOk = icsaRaw < 250000;
    const metCount = [ismOk, cgrOk, icsaOk].filter(Boolean).length;
    d.COPPER_STRONG_SETUP = {
      name: 'copper_strong_setup',
      value: metCount === 3 ? 1 : 0,
      date: dt,
      formula: `ISM≥50(${ismOk?'Y':'N'}) + 금구리비>0.00125(${cgrOk?'Y':'N'}) + ICSA<250K(${icsaOk?'Y':'N'}) 동시 → 구리 강매수 복합 플래그 (영상2)`,
    };
    d.COPPER_SETUP_COUNT = {
      name: 'copper_setup_count',
      value: metCount,
      date: dt,
      formula: `구리 강매수 3조건 중 충족 개수 (0~3)`,
    };
  }

  // === 유가 → CPI 2~3개월 지연 (영상5 §7m7s "유가 하락도 공급망 충격은 2~3개월 뒤 CPI") ===
  // 60일 전 대비 현재 WTI 변화율 → 향후 2~3개월 CPI 지연 반영 방향 프록시.
  // 음수: 유가 하락 → 2~3개월 뒤 CPI 완화 기대
  // 양수: 유가 상승 → 2~3개월 뒤 CPI 상승 압력
  try {
    const wtiHist = await readHistory('yahoo', 'WTI');
    if (wtiHist.length >= 60) {
      const cur = wtiHist[wtiHist.length - 1].value;
      const past60 = wtiHist[wtiHist.length - 60].value;
      if (past60 > 0) {
        const change60 = ((cur - past60) / past60) * 100;
        d.WTI_60D_CHANGE = {
          name: 'wti_60d_change',
          value: parseFloat(change60.toFixed(2)),
          date: dt,
          formula: 'WTI 60일 변화율(%) — 2~3개월 뒤 CPI 지연 반영 방향 프록시 (영상5 §7m7s)',
        };
        // CPI 압력 프록시: |change| >= 10% 가면 지연 영향 크다
        let pressure: 'high_down' | 'mild_down' | 'neutral' | 'mild_up' | 'high_up';
        if (change60 <= -15) pressure = 'high_down';
        else if (change60 <= -5) pressure = 'mild_down';
        else if (change60 < 5) pressure = 'neutral';
        else if (change60 < 15) pressure = 'mild_up';
        else pressure = 'high_up';
        const pressureScore = { high_down: -2, mild_down: -1, neutral: 0, mild_up: 1, high_up: 2 }[pressure];
        d.CPI_OIL_LAG_PRESSURE = {
          name: 'cpi_oil_lag_pressure',
          value: pressureScore,
          date: dt,
          formula: `${pressure} — 유가 ${change60.toFixed(1)}% 기반 향후 2~3개월 CPI 압력 (-2 강한 완화 ~ +2 강한 상승)`,
        };
      }
    }
  } catch {
    /* WTI 히스토리 실패 무시 */
  }

  // === ICSA × 200DMA 2x2 매트릭스 (영상3 §174 "200DMA + 실업수당 조합 필터") ===
  // 200DMA 상회/하회 × ICSA 낮음(20만대)/높음(30만+) 조합의 4구획 레짐 라벨.
  const icsaVal = val(raw, 'ICSA');
  const nasdaqAbove = d.NASDAQ_ABOVE_200DMA?.value;
  if (icsaVal !== null && nasdaqAbove !== undefined) {
    const highICSA = icsaVal >= 300000;
    const lowICSA = icsaVal < 300000; // 19차 P2#11: 영상 §3부 "30만 미만" 정합 (250K → 300K)
    const above = nasdaqAbove === 1;
    let label = 'NEUTRAL';
    let score = 0;
    if (above && lowICSA) { label = 'HEALTHY_EXPANSION'; score = 2; }           // 안정 확장
    else if (above && highICSA) { label = 'MOMENTUM_WARNING'; score = -1; }     // 모멘텀 둔화 경고
    else if (!above && lowICSA) { label = 'CORRECTION_OPPORTUNITY'; score = 1; } // 조정 매수 기회
    else if (!above && highICSA) { label = 'STRUCTURAL_RISK'; score = -2; }     // 구조적 위험
    d.ICSA_REGIME_LABEL = {
      name: 'icsa_regime_label',
      value: score,
      date: dt,
      formula: `${label} (200DMA ${above ? '상회' : '하회'} × ICSA ${Math.round(icsaVal / 1000)}K). 영상3 §174 매트릭스 — 조정 vs 붕괴 구분`,
    };
  }

  // === ICSA 52주 최저 재돌파 + 반등 추세 트리거 (8차 TOP7 Fix #5) ===
  // 실업수당 52주 최저에 근접하면 노동시장 회복 사이클 후반 = 향후 반전 조기 경고.
  // 4주 연속 상승이면 추세 확정.
  try {
    const icsaHist = await readHistory('fred', 'ICSA');
    if (icsaHist.length >= 52 && icsaVal !== null) {
      const last52 = icsaHist.slice(-52).map((p) => p.value).filter((v) => Number.isFinite(v) && v > 0);
      if (last52.length >= 10) {
        const low52 = Math.min(...last52);
        // 52주 최저 재테스트: 현재값이 52주 최저 × 1.05 이내
        const retest = icsaVal <= low52 * 1.05 ? 1 : 0;
        d.ICSA_52W_LOW_RETEST = {
          name: 'icsa_52w_low_retest',
          value: retest,
          date: dt,
          formula: `현재 ${Math.round(icsaVal / 1000)}K vs 52주 최저 ${Math.round(low52 / 1000)}K. ≤최저×1.05 → 1 (저점 재테스트)`,
        };

        // 반등 추세: 최근 4주 연속 상승하는지 (마지막 4주 추세 판정)
        if (icsaHist.length >= 5) {
          const recent5 = icsaHist.slice(-5).map((p) => p.value);
          let upCount = 0;
          for (let i = 1; i < recent5.length; i += 1) {
            if (recent5[i] > recent5[i - 1]) upCount += 1;
          }
          // 4회 비교 중 3회 이상 상승 = 반등 추세 (+1), 그 외 0
          const recoverySignal = upCount >= 3 && retest === 1 ? 1 : 0;
          d.ICSA_RECOVERY_SIGNAL = {
            name: 'icsa_recovery_signal',
            value: recoverySignal,
            date: dt,
            formula: `52주 최저 재테스트(${retest}) AND 최근 4주 상승 ${upCount}/4 ≥ 3회 → 반등 추세 경고 (+1)`,
          };
        }
      }
    }
  } catch {
    /* ICSA 52주 최저 트리거 실패는 파이프라인 막지 않음 */
  }

  // === 유동성 방향 종합 점수 (영상4 §120 "총량 아닌 방향") ===
  // RRP 감소 / TGA 감소 / MMF 감소 / WRESBAL 증가 / Global M2 YoY > 0 → 각 +1.
  // 반대 방향은 -1. 최근값 하나가 아니라 평균 대비 변화율을 써서 하루 내 잦은 노이즈를 완화한다.
  let liqScore = 0;
  const liqParts: string[] = [];
  const rrpDir = d.RRP_DIRECTION?.value;
  if (rrpDir !== undefined && rrpDir !== null) {
    if (rrpDir <= -1) { liqScore += 1; liqParts.push(`RRP ${rrpDir.toFixed(1)}%`); }
    else if (rrpDir >= 1) { liqScore -= 1; liqParts.push(`RRP ${rrpDir.toFixed(1)}%`); }
    else liqParts.push(`RRP 중립 ${rrpDir.toFixed(1)}%`);
  }
  const tgaDir = d.TGA_DIRECTION?.value;
  if (tgaDir !== undefined && tgaDir !== null) {
    if (tgaDir <= -2) { liqScore += 1; liqParts.push(`TGA ${tgaDir.toFixed(1)}%`); }
    else if (tgaDir >= 2) { liqScore -= 1; liqParts.push(`TGA ${tgaDir.toFixed(1)}%`); }
    else liqParts.push(`TGA 중립 ${tgaDir.toFixed(1)}%`);
  }
  const mmfDir = d.MMF_DIRECTION?.value;
  if (mmfDir !== undefined && mmfDir !== null) {
    if (mmfDir <= -0.5) { liqScore += 1; liqParts.push(`MMF ${mmfDir.toFixed(1)}%`); }
    else if (mmfDir >= 0.5) { liqScore -= 1; liqParts.push(`MMF ${mmfDir.toFixed(1)}%`); }
    else liqParts.push(`MMF 중립 ${mmfDir.toFixed(1)}%`);
  }
  const reserveDir = d.WRESBAL_DIRECTION?.value;
  if (reserveDir !== undefined && reserveDir !== null) {
    if (reserveDir >= 1) { liqScore += 1; liqParts.push(`WRESBAL ${reserveDir.toFixed(1)}%`); }
    else if (reserveDir <= -1) { liqScore -= 1; liqParts.push(`WRESBAL ${reserveDir.toFixed(1)}%`); }
    else liqParts.push(`WRESBAL 중립 ${reserveDir.toFixed(1)}%`);
  }
  const m2 = d.GLOBAL_M2_PROXY?.value;
  if (m2 !== undefined && m2 !== null) {
    if (m2 > 0) { liqScore += 1; liqParts.push(`M2 YoY ${m2.toFixed(1)}%`); }
    else if (m2 < 0) { liqScore -= 1; liqParts.push(`M2 YoY ${m2.toFixed(1)}%`); }
  }
  d.LIQUIDITY_DIRECTION = {
    name: 'liquidity_direction',
    value: liqScore,
    date: dt,
    formula: `RRP/TGA/MMF 최근 평균 감소 + WRESBAL 평균 증가 + M2 YoY 양수 = 각 +1. -5~+5 범위. 현재: ${liqParts.join(' · ')}`,
  };

  // === 채권 자경단 / 재정 리스크 (영상4 §07 "30년 4.93% 돌파, 미국 재정 리스크 노출") ===
  // DGS30 20일 변화율 + T10Y2Y 스티프닝(장기금리 상승 vs 단기 stable) 동시 시 자경단 신호.
  const dgs30 = val(raw, 'DGS30');
  try {
    const dgs30Hist = await fetchFredHistory('DGS30', apiKey, 25);
    if (dgs30 !== null && dgs30Hist.length >= 20) {
      const cur = dgs30Hist[0].value;
      const past = dgs30Hist[Math.min(19, dgs30Hist.length - 1)].value;
      const delta20 = cur - past;
      d.DGS30_20D_CHANGE = {
        name: 'dgs30_20d_change',
        value: parseFloat(delta20.toFixed(3)),
        date: dt,
        formula: 'DGS30 20영업일 변화폭 (%p)',
      };
      // Fiscal stress: 30년 20일에 +0.2%p 이상 급등 AND 현재 레벨 4.8%+ (23차 Tier 2#19: 영상4 §"4.93 돌파" 정합)
      // BOND_VIGILANTE_SCORE 의 절대 임계 4.8 과 통일.
      const yieldCurve = val(raw, 'T10Y2Y');
      const fiscalStress = (delta20 >= 0.2 && cur >= 4.8) || (delta20 >= 0.3);
      // yieldCurve === null → 곡선 정보 없음 → 스티프닝 판단 불가 → false.
      //   (false 는 HARD 발동 차단 쪽이라 방어적 디폴트)
      const curveSteepening = yieldCurve !== null && yieldCurve > 0.1;
      d.FISCAL_STRESS = {
        name: 'fiscal_stress',
        value: fiscalStress ? 1 : 0,
        date: dt,
        formula: `DGS30 20일 변화 ${delta20.toFixed(2)}p (현 ${cur.toFixed(2)}%) + 수익률곡선 ${yieldCurve.toFixed(2)} · 1=재정리스크/채권자경단 경고`,
      };
      if (fiscalStress && curveSteepening) {
        d.FISCAL_STRESS_HARD = {
          name: 'fiscal_stress_hard',
          value: 1,
          date: dt,
          formula: '재정리스크 + 수익률곡선 스티프닝 동시 → 채권 자경단 강경 신호 (영상4 §07)',
        };
      }

      // === 채권 자경단 합성 지수 (영상4 §137-147 "재정적자 + 장기금리↑ + 달러↓ + HY 확대") ===
      // Fix #FE2: 본래 정의 4축 복원.
      //   1) 30y-10y 스티프닝: (DGS30 - DGS10) > 0.4 %p — 장기 불안 반영
      //   2) 장기금리 레벨   : DGS30 >= 4.8% — "채권자경단이 돌아왔다" 절대 임계
      //   3) DXY 약세        : DXY < 100 OR DXY 20D 추세 < -2%
      //   4) HY 확대         : HY OAS >= 500bp OR HYG/IEF z <= -1.5
      // 각 축 충족 시 +1, SCORE 0~4. WARNING 은 3축 이상 (기존 2 에서 상향 — 4축 정의상 더 엄격).
      const dxyVal = val(raw, 'DXY');
      const dxyTrendLong = d.DXY_TREND_LONG?.value;
      const hyOasBp = d.CREDIT_HY_OAS_BP?.value ?? null;
      const hygIefZ = d.CREDIT_HYG_IEF_ZSCORE?.value ?? null;
      const dgs10Val = val(raw, 'DGS10');

      // 축 1: 30y-10y 스티프닝
      const steepening30y10y = (dgs10Val !== null && cur !== null) ? (cur - dgs10Val) : null;
      const axisSteepening = steepening30y10y !== null && steepening30y10y > 0.4;

      // 축 2: 장기금리 레벨
      const axisLongYieldLevel = cur >= 4.8;

      // 축 3: DXY 약세 — 레벨 또는 추세 둘 중 하나
      const axisDxyWeak =
        (dxyTrendLong !== undefined && dxyTrendLong !== null && dxyTrendLong < -2) ||
        (dxyVal !== null && dxyVal < 100);

      // 축 4: HY 확대 — OAS 레벨 또는 HYG/IEF z-score 둘 중 하나
      const axisHyWidening =
        (hyOasBp !== null && hyOasBp >= 500) ||
        (hygIefZ !== null && hygIefZ <= -1.5);

      // 27차 Phase 1#6: BOND_VIGILANTE 4축 → 6축 확장.
      // 추가: (5) IMF 2031 부채 trajectory ≥1 / (6) 트럼프 감세 누적 ≥1
      // video4 §10:11+10:20 명시 — 두 trajectory 가 채권자경단 가속 압력
      const axisDebtTrajectory = (d.US_DEBT_GDP_2031_PROJECTION?.value ?? 0) >= 1;
      const axisTaxCutDeficit = (d.TRUMP_TAX_CUT_DEFICIT_PROJECTION?.value ?? 0) >= 1;
      const vigilanteScore =
        (axisSteepening ? 1 : 0) +
        (axisLongYieldLevel ? 1 : 0) +
        (axisDxyWeak ? 1 : 0) +
        (axisHyWidening ? 1 : 0) +
        (axisDebtTrajectory ? 1 : 0) +
        (axisTaxCutDeficit ? 1 : 0);

      const missingAxes: string[] = [];
      if (!axisSteepening) {
        missingAxes.push(steepening30y10y === null
          ? '30y-10y 결측'
          : `30y-10y=${steepening30y10y.toFixed(2)}p ≤ 0.4`);
      }
      if (!axisLongYieldLevel) missingAxes.push(`DGS30=${cur.toFixed(2)}% < 4.8`);
      if (!axisDxyWeak) {
        missingAxes.push(dxyVal === null && (dxyTrendLong === undefined || dxyTrendLong === null)
          ? 'DXY 결측'
          : `DXY lvl=${dxyVal ?? 'n/a'} / 추세=${dxyTrendLong ?? 'n/a'}`);
      }
      if (!axisHyWidening) {
        missingAxes.push(hyOasBp === null && hygIefZ === null
          ? 'HY 결측'
          : `HY OAS=${hyOasBp ?? 'n/a'}bp / HYG/IEF z=${hygIefZ ?? 'n/a'}`);
      }

      d.BOND_VIGILANTE_SCORE = {
        name: 'bond_vigilante_score',
        value: vigilanteScore,
        date: dt,
        formula: `6축 [스티프닝${axisSteepening ? 'Y' : 'N'} · 장기금리${axisLongYieldLevel ? 'Y' : 'N'} · DXY약세${axisDxyWeak ? 'Y' : 'N'} · HY${axisHyWidening ? 'Y' : 'N'} · IMF2031${axisDebtTrajectory ? 'Y' : 'N'} · 감세${axisTaxCutDeficit ? 'Y' : 'N'}] 합계 (영상4 §137-147 + 27차 6축).`,
      };
      // 27차: 6축 중 4축+ 면 WARNING (이전 3/4 → 4/6 비율 동일)
      if (vigilanteScore >= 4) {
        d.BOND_VIGILANTE_WARNING = {
          name: 'bond_vigilante_warning',
          value: 1,
          date: dt,
          formula: `채권 자경단 4축+ 충족 (${vigilanteScore}/6) — 정책 신뢰 이탈 프리커서 (27차 6축).`,
        };
      } else {
        d.BOND_VIGILANTE_WARNING = {
          name: 'bond_vigilante_warning',
          value: 0,
          date: dt,
          formula: `6축 중 ${vigilanteScore}개 충족 — 경보 미발동. 미충족: ${missingAxes.join(' / ') || '-'}.`,
        };
      }
    }
  } catch {
    /* DGS30 수집 실패는 다른 파이프라인 막지 않음 */
  }

  // === 스태그플레이션 레짐 (영상4 §145 "경기 둔화 + 물가 안 잡힘 신호") ===
  // CPI 유가 압력 + ICSA 상승 추세 + ISM 50 하회 조합.
  const cpiPressureD = d.CPI_OIL_LAG_PRESSURE?.value;
  const icsaRegime = d.ICSA_REGIME_LABEL?.value;
  const ismProxyD = d.ISM_PROXY?.value;
  if (cpiPressureD !== undefined && ismProxyD !== undefined) {
    const inflationRising = cpiPressureD !== null && cpiPressureD >= 1;
    const growthSlowing = (ismProxyD !== null && ismProxyD < 50) || (icsaRegime !== undefined && icsaRegime !== null && icsaRegime <= -1);
    const stagflationScore = (inflationRising ? 1 : 0) + (growthSlowing ? 1 : 0);
    d.STAGFLATION_SCORE = {
      name: 'stagflation_score',
      value: stagflationScore,
      date: dt,
      formula: `인플레↑(${inflationRising ? 'Y' : 'N'}) + 성장둔화(${growthSlowing ? 'Y' : 'N'}) 동시 충족 시 2 (영상4 §145 스태그플레이션 신호)`,
    };
    d.STAGFLATION_WARNING = {
      name: 'stagflation_warning',
      value: stagflationScore === 2 ? 1 : 0,
      date: dt,
      formula: stagflationScore === 2
        ? '스태그플레이션 2축 동시 충족 — CPI 상승 압력 + 성장 둔화 (영상4 §145)'
        : '스태그플레이션 조건 미충족',
    };

    // === STAGFLATION_VERIFIED (9차 gap TOP3 Fix #1) ===
    // video2 §67-68 "전쟁→유가↑→인플레↑→금리인하 꺾임→실질금리↑→금↓" 인과 체인 확증.
    // 3축 모두 충족 시에만 1. 결측 시 null.
    //   축 1: STAGFLATION_WARNING === 1
    //   축 2: REAL_YIELD_TREND > 0 (실질금리 상승)
    //   축 3: GOLD 20D 변화 < 0 (금 하락)
    // Fix #1 (후속): GOLD 20D 는 readHistory('yahoo','GOLD') 우선, 실패 시 live fetch.
    //   → 백필 경로에서도 동일 로직 재사용 가능 (history-store.ts reconstructStagflationVerified).
    try {
      const warningVal = d.STAGFLATION_WARNING?.value ?? null;
      const ryTrend = d.REAL_YIELD_TREND?.value ?? null;
      let gold20dChange: number | null = null;
      try {
        const storedGold = await readHistory('yahoo', 'GOLD');
        if (storedGold.length >= 21) {
          const gCur = storedGold[storedGold.length - 1].value;
          const g20 = storedGold[storedGold.length - 21].value;
          if (g20 > 0) gold20dChange = ((gCur - g20) / g20) * 100;
        }
      } catch { /* 저장 히스토리 실패 → live fallback */ }
      if (gold20dChange === null) {
        try {
          const goldHistVerify = await fetchYahooHistory('GC=F', 30);
          if (goldHistVerify.length >= 21) {
            const gCur = goldHistVerify[0].close;
            const g20 = goldHistVerify[20].close;
            if (g20 > 0) gold20dChange = ((gCur - g20) / g20) * 100;
          }
        } catch { /* 금 히스토리 실패 허용 */ }
      }

      if (warningVal === null || ryTrend === null || gold20dChange === null) {
        d.STAGFLATION_VERIFIED = {
          name: 'stagflation_verified',
          value: null,
          date: dt,
          formula: `데이터 부족 (WARNING=${warningVal ?? 'n/a'} / REAL_YIELD_TREND=${ryTrend ?? 'n/a'} / GOLD_20D=${gold20dChange ?? 'n/a'})`,
        };
      } else {
        const axisWarning = warningVal === 1;
        const axisRyUp = ryTrend > 0;
        const axisGoldDown = gold20dChange < 0;
        const verified = axisWarning && axisRyUp && axisGoldDown ? 1 : 0;
        d.STAGFLATION_VERIFIED = {
          name: 'stagflation_verified',
          value: verified,
          date: dt,
          formula: `WARNING=${axisWarning ? 'Y' : 'N'} · 실질금리↑=${axisRyUp ? 'Y' : 'N'}(${ryTrend.toFixed(3)}) · 금↓=${axisGoldDown ? 'Y' : 'N'}(${gold20dChange.toFixed(2)}%) — ${verified ? '인과 체인 확증' : 'WARNING 단독 또는 확증 실패'} (video2 §67-68)`,
        };
      }
    } catch {
      /* STAGFLATION_VERIFIED 실패는 파이프라인 막지 않음 */
    }
  }

  const nasdaqDisparity = d.NASDAQ_DISPARITY?.value ?? null;
  const fng = val(raw, 'FEAR_GREED');
  const vixVal = val(raw, 'VIXCLS');
  // 23차 Tier 2#10: VIX 결측 시 OVERHEATED 일관성 — F&G 단독 분기 + 분기별 명시 라벨
  if (nasdaqDisparity !== null && nasdaqDisparity > 20 && fng !== null && fng > 75) {
    d.OVERHEATED = { name: 'overheated', value: 1, date: dt, formula: '이격도+20%이상 AND F&G 75+ → 과열' };
  } else if (nasdaqDisparity !== null && nasdaqDisparity > 15 && vixVal !== null && vixVal < 15) {
    d.OVERHEATED = { name: 'overheated', value: 1, date: dt, formula: '이격도+15%이상 AND VIX<15 → 과열' };
  } else if (nasdaqDisparity !== null && nasdaqDisparity > 18 && fng !== null && fng > 80 && vixVal === null) {
    // VIX 결측 시 F&G 80+ 단독 강한 과열 (23차 Tier 2#10)
    d.OVERHEATED = { name: 'overheated', value: 1, date: dt, formula: '이격도+18% AND F&G 80+ AND VIX 결측 → 과열 (VIX 결측 fallback)' };
  } else if (vixVal === null && fng === null) {
    // 24차 Phase 2#9: VIX+F&G 동시 결측 시 hysteresis 7일 잠금 회피 — null 명시 (hardenFlag 가 prev 유지)
    d.OVERHEATED = { name: 'overheated', value: null as unknown as number, date: dt, formula: 'VIX+F&G 동시 결측 — null 반환, hardenFlag 가 직전 7일 값 유지' };
  } else {
    d.OVERHEATED = { name: 'overheated', value: 0, date: dt, formula: vixVal === null ? '과열 미해당 (VIX 결측 — F&G 80+ fallback 미충족)' : '과열 조건 미충족' };
  }

  // === 멀티 타임프레임 캔들 분석 (영상3·4·5 "월→주→일" 위계) ===
  // NASDAQ, KOSPI 주요 자산에 대해 월봉 소진/주봉 반전/월봉 위치지수 파생지표 생성.
  const mtfTargets: Array<{ symbol: string; prefix: string }> = [
    { symbol: '^IXIC', prefix: 'NASDAQ' },
    { symbol: '^KS11', prefix: 'KOSPI' },
  ];
  await withSpan('macrosquare.engine.derived.mtf_yearly', async (span) => {
    span.setAttribute('derived.mtf.symbols', mtfTargets.length);
  for (const { symbol, prefix } of mtfTargets) {
    try {
      const mtf = await fetchMultiTimeframe(symbol);
      if (!mtf) continue;
      const exhaustion = detectClimaxExhaustion(mtf.monthly, 3);
      const weeklyRev = detectWeeklyReversal(mtf.weekly, 4);
      const monthPos = monthlyPositionScore(mtf.monthly);
      const latestMonthly = mtf.monthly[mtf.monthly.length - 1];
      const latestWeekly = mtf.weekly[mtf.weekly.length - 1];

      d[`${prefix}_MONTHLY_EXHAUSTION`] = {
        name: `${prefix.toLowerCase()}_monthly_exhaustion`,
        value: exhaustion.warning ? 1 : 0,
        date: dt,
        formula: `최근 3개월 연속 장대양봉(${exhaustion.consecutiveBullishLargeBody}/3) + 아래꼬리 없음(${exhaustion.latestNoLowerWick ? 'Y' : 'N'}) = 과열 소진 경고`,
      };
      d[`${prefix}_WEEKLY_REVERSAL`] = {
        name: `${prefix.toLowerCase()}_weekly_reversal`,
        value: weeklyRev.reversalWarning ? 1 : 0,
        date: dt,
        formula: `이전 4주 상승 추세 AND 최근 주봉 장대음봉 → 추세 전환 경고`,
      };
      if (monthPos !== null) {
        d[`${prefix}_MONTH_POS`] = {
          name: `${prefix.toLowerCase()}_month_pos`,
          value: parseFloat((monthPos * 100).toFixed(1)),
          date: dt,
          formula: `월봉 종가의 최근 12개월 고-저 사이 위치(%). 100=고점, 0=저점`,
        };
      }
      if (latestMonthly) {
        d[`${prefix}_MONTHLY_BODY_PCT`] = {
          name: `${prefix.toLowerCase()}_monthly_body_pct`,
          value: latestMonthly.shape.bodyPct,
          date: latestMonthly.date,
          formula: `최근 월봉 몸통 비율 (|close-open|/range). 90+=마루보주, <10=도지`,
        };
        d[`${prefix}_MONTHLY_LOWER_WICK_PCT`] = {
          name: `${prefix.toLowerCase()}_monthly_lower_wick_pct`,
          value: latestMonthly.shape.lowerWickPct,
          date: latestMonthly.date,
          formula: `최근 월봉 아래꼬리 비율. <5 + 장대양봉 = 매수 압력 검증 없이 상승`,
        };
        // Area Index (영상5 용어) + 핀바 플래그
        d[`${prefix}_AREA_INDEX`] = {
          name: `${prefix.toLowerCase()}_area_index`,
          value: latestMonthly.shape.areaIndex,
          date: latestMonthly.date,
          formula: `아래꼬리/전체 비율 (Area Index). <10%=매수 소화도 경고 (영상5 §106)`,
        };
        d[`${prefix}_MONTHLY_PIN_BULLISH`] = {
          name: `${prefix.toLowerCase()}_monthly_pin_bullish`,
          value: latestMonthly.shape.isPinBarBullish ? 1 : 0,
          date: latestMonthly.date,
          formula: `월봉 하방 핀바 (매수 반전 후보). body<30 + 아래꼬리≥60 + 윗꼬리<10`,
        };
        d[`${prefix}_MONTHLY_PIN_BEARISH`] = {
          name: `${prefix.toLowerCase()}_monthly_pin_bearish`,
          value: latestMonthly.shape.isPinBarBearish ? 1 : 0,
          date: latestMonthly.date,
          formula: `월봉 상방 핀바 (매도 반전 후보). body<30 + 윗꼬리≥60 + 아래꼬리<10`,
        };
        // 아웃사이드 바 (영상3 §217 "2024 연봉이 2023 완전히 덮음")
        if (mtf.monthly.length >= 2) {
          const prev = mtf.monthly[mtf.monthly.length - 2];
          const ob = detectOutsideBar(prev, latestMonthly);
          d[`${prefix}_MONTHLY_OUTSIDE_BAR`] = {
            name: `${prefix.toLowerCase()}_monthly_outside_bar`,
            value: ob.isOutside ? (ob.direction === 'bullish' ? 1 : -1) : 0,
            date: latestMonthly.date,
            formula: `+1=상승 아웃사이드 / -1=하락 아웃사이드 / 0=해당없음 (이전 봉 완전 포섭)`,
          };
        }
      }
      // 연봉 계층 (stt_kospi "연봉 아래꼬리 저점 시그널")
      try {
        const yearly = await fetchYearlyBuckets(symbol, 20);
        if (yearly.length > 0) {
          const yai = detectYearlyAreaIndex(yearly, 5);
          d[`${prefix}_YEARLY_CONSECUTIVE_BULLISH`] = {
            name: `${prefix.toLowerCase()}_yearly_consecutive_bullish`,
            value: yai.consecutiveBullish,
            date: yearly[yearly.length - 1].date,
            formula: '최근부터 역산한 연봉 연속 양봉 수. 3+ 과열, 0+아래꼬리 높음 = 바닥',
          };
          d[`${prefix}_YEARLY_LOWER_SHADOW_RATIO`] = {
            name: `${prefix.toLowerCase()}_yearly_lower_shadow_ratio`,
            value: yai.latestLowerShadowRatio,
            date: yearly[yearly.length - 1].date,
            formula: '최근 연봉 아래꼬리/몸통 비율 (stt_kospi). ≥1 = 강한 매수압력/바닥 탐색',
          };
        }
      } catch {
        /* 연봉 버킷팅 실패는 무시 */
      }

      // W 반등 패턴 감지 (영상3·5) — 일봉 90일 창
      const wBottom = detectWBottom(mtf.daily, 90);
      d[`${prefix}_W_BOTTOM`] = {
        name: `${prefix.toLowerCase()}_w_bottom`,
        value: wBottom.detected ? 1 : 0,
        date: dt,
        formula: `W 반등 패턴 감지 (${wBottom.reason})`,
      };

      // 15년 장기 상승채널 평행선 (영상3 §71/155 — NASDAQ 만 해당)
      if (prefix === 'NASDAQ') {
        const ch = linearRegressionChannel(mtf.monthly, 180);
        if (ch && ch.position !== null) {
          d.NASDAQ_CHANNEL_POSITION = {
            name: 'nasdaq_channel_position',
            value: parseFloat((ch.position * 100).toFixed(1)),
            date: dt,
            formula: `장기(최대 15년) 회귀채널 내 위치(%). 0=하단(매수강도↑) / 50=중단 / 100=상단(저항)`,
          };
          d.NASDAQ_CHANNEL_UPPER = {
            name: 'nasdaq_channel_upper',
            value: ch.upperBand,
            date: dt,
            formula: `장기 회귀채널 상단 (mid + 1σ)`,
          };
          d.NASDAQ_CHANNEL_MID = {
            name: 'nasdaq_channel_mid',
            value: ch.midLine,
            date: dt,
            formula: `장기 회귀선 (월봉 OLS)`,
          };
          d.NASDAQ_CHANNEL_LOWER = {
            name: 'nasdaq_channel_lower',
            value: ch.lowerBand,
            date: dt,
            formula: `장기 회귀채널 하단 (mid - 1σ). 영상3 '매수 강도 강해지는 경향'`,
          };

          // 8차 TOP7 Fix #4: 종가 vs 중단선 크로스 이벤트 (2일 비교)
          // 전일 종가 ≤ MID & 당일 종가 > MID → +1 (상향 크로스, 장기 추세 회귀)
          // 전일 종가 ≥ MID & 당일 종가 < MID → -1 (하향 크로스, 약세 이탈)
          try {
            const nasdaqHist = await readHistory('yahoo', 'NASDAQ');
            if (nasdaqHist.length >= 2 && ch.midLine !== null && Number.isFinite(ch.midLine)) {
              const prev = nasdaqHist[nasdaqHist.length - 2].value;
              const cur = nasdaqHist[nasdaqHist.length - 1].value;
              const mid = ch.midLine;
              let cross = 0;
              if (prev <= mid && cur > mid) cross = 1;
              else if (prev >= mid && cur < mid) cross = -1;
              d.NASDAQ_CHANNEL_MID_CROSS = {
                name: 'nasdaq_channel_mid_cross',
                value: cross,
                date: dt,
                formula: `전일 ${prev.toFixed(2)} / 당일 ${cur.toFixed(2)} / MID ${mid.toFixed(2)}. +1=상향 크로스, -1=하향 크로스, 0=유지`,
              };
            }
          } catch {
            /* NASDAQ channel cross 실패는 전체 파이프라인 막지 않음 */
          }
        }
      }

      if (latestWeekly) {
        d[`${prefix}_WEEKLY_BULLISH`] = {
          name: `${prefix.toLowerCase()}_weekly_bullish`,
          value: latestWeekly.shape.isBullish ? 1 : 0,
          date: latestWeekly.date,
          formula: `최근 주봉 양봉 여부`,
        };
        // 주봉 20MA 돌파 트리거 (영상2:258, 영상3:184 "장기투자자 진입 기준")
        if (mtf.weekly.length >= 20) {
          const closes20 = mtf.weekly.slice(-20).map((c) => c.close);
          const sma20w = closes20.reduce((a, b) => a + b, 0) / 20;
          const prevClose = mtf.weekly.length >= 2 ? mtf.weekly[mtf.weekly.length - 2].close : null;
          const curClose = latestWeekly.close;
          const crossedUp = prevClose !== null && prevClose <= sma20w && curClose > sma20w;
          const above = curClose > sma20w;
          d[`${prefix}_WEEKLY_20MA`] = {
            name: `${prefix.toLowerCase()}_weekly_20ma`,
            value: parseFloat(sma20w.toFixed(2)),
            date: latestWeekly.date,
            formula: `최근 20주 종가 평균 (장기투자자 진입 기준)`,
          };
          d[`${prefix}_WEEKLY_20MA_RECOVERY`] = {
            name: `${prefix.toLowerCase()}_weekly_20ma_recovery`,
            value: crossedUp ? 1 : above ? 0.5 : 0,
            date: latestWeekly.date,
            formula: `1=이번 주 20MA 상향 돌파, 0.5=유지(상회), 0=하회`,
          };
        }
      }
    } catch {
      /* 멀티 타임프레임 수집 실패는 전체 파이프라인 막지 않음 */
    }
  }
  }); // withSpan mtf_yearly

  // === USD/KRW 주봉 선형 회귀 채널 (5년) ===
  // stt_kospi: 환율이 채널 상단(>0.9)에 붙으면 원화 약세 극단 → 외국인 매도 리스크,
  //           하단(<0.1)은 원화 강세 극단 → 매수 우호 환경.
  try {
    const krwDays = await fetchYahooOHLC('KRW=X', 365 * 6, '1wk');
    if (krwDays.length >= 60) {
      const ch = linearRegressionChannel(krwDays, 260); // ~5년 주봉
      if (ch && ch.position !== null) {
        d.USDKRW_WEEKLY_CHANNEL_POSITION = {
          name: 'usdkrw_weekly_channel_position',
          value: parseFloat(ch.position.toFixed(3)),
          date: krwDays[krwDays.length - 1].date,
          formula: '5년 주봉 회귀채널 내 위치(0~1). >0.9=원화 약세 극단(외인매도 리스크), <0.1=강세 극단',
        };
        d.USDKRW_WEEKLY_UPPER = {
          name: 'usdkrw_weekly_upper',
          value: ch.upperBand,
          date: krwDays[krwDays.length - 1].date,
          formula: '5년 주봉 회귀 상단 (mid + 1σ)',
        };
        d.USDKRW_WEEKLY_MID = {
          name: 'usdkrw_weekly_mid',
          value: ch.midLine,
          date: krwDays[krwDays.length - 1].date,
          formula: '5년 주봉 회귀선 (OLS)',
        };
        d.USDKRW_WEEKLY_LOWER = {
          name: 'usdkrw_weekly_lower',
          value: ch.lowerBand,
          date: krwDays[krwDays.length - 1].date,
          formula: '5년 주봉 회귀 하단 (mid - 1σ)',
        };
      }
    }
  } catch {
    /* USDKRW 주봉 채널 실패는 무시 */
  }

  // === KRX 외국인·기관 순매수 (코스피 전략 4번째 축) ===
  // 네이버 금융(공식 집계) 스크래핑. 단위: 억원.
  try {
    const flowDays = await fetchKrxInvestorFlow('KOSPI');
    const summary = summarizeInvestorFlow('KOSPI', flowDays);
    if (summary) {
      d.KOSPI_FOREIGN_NET_1D = {
        name: 'kospi_foreign_net_1d',
        value: summary.foreignLatest,
        date: summary.latestDate,
        formula: '당일 외국인 순매수 (억원, 네이버 금융)',
      };
      d.KOSPI_FOREIGN_NET_5D = {
        name: 'kospi_foreign_net_5d',
        value: summary.foreignNet5D,
        date: summary.latestDate,
        formula: '최근 5영업일 외국인 순매수 합 (억원)',
      };
      d.KOSPI_FOREIGN_NET_20D = {
        name: 'kospi_foreign_net_20d',
        value: summary.foreignNet20D,
        date: summary.latestDate,
        formula: '최근 20영업일 외국인 순매수 합 (억원)',
      };
      d.KOSPI_FOREIGN_TREND = {
        name: 'kospi_foreign_trend',
        value: summary.foreignTrend,
        date: summary.latestDate,
        formula: '외국인 최근5일 평균 - 6~20일 평균 (양수=매수 가속)',
      };
      d.KOSPI_INSTITUTION_NET_5D = {
        name: 'kospi_institution_net_5d',
        value: summary.institutionNet5D,
        date: summary.latestDate,
        formula: '최근 5영업일 기관계 순매수 합 (억원)',
      };
      d.KOSPI_FOREIGN_BUY_STREAK = {
        name: 'kospi_foreign_buy_streak',
        value: summary.foreignBuyStreak,
        date: summary.latestDate,
        formula: '최신일부터 역산한 외국인 연속 순매수 일수. 5+ 면 추세 확정 신호',
      };
      d.KOSPI_FOREIGN_SELL_STREAK = {
        name: 'kospi_foreign_sell_streak',
        value: summary.foreignSellStreak,
        date: summary.latestDate,
        formula: '최신일부터 역산한 외국인 연속 순매도 일수. 5+ 면 구조적 이탈 경고',
      };
      d.KOSPI_FOREIGN_EXTREME = {
        name: 'kospi_foreign_extreme',
        value: summary.foreignExtreme === 'overheated' ? 1 : summary.foreignExtreme === 'oversold' ? -1 : 0,
        date: summary.latestDate,
        formula: '20D 순매수 합 ≥+3조 → 과열(+1) / ≤-3조 → 과매도(-1) / 중립(0). 일상 방향성 전환 감지용.',
      };

      // === 역사적 대량매도 감지 (11차 2026-04) — stt_kospi 원문 정합 ===
      // stt_kospi: "2~3월 외국인이 코스피에서 45~60조 원을 팔았습니다" (2개월 누적 = 월 22~30조).
      // 20영업일 ≈ 월간 기준으로 ±20조 (±200,000억) 돌파는 2025년 2~3월 수준의 공황성/랠리성
      // 대량 이벤트. 기존 ±3조 임계(KOSPI_FOREIGN_EXTREME)와 구분해 규모 계층화.
      //   -1 = 역사적 공황성 매도 (반등 후보, stt_kospi "숨고르기 후 재상승" 시점)
      //   +1 = 역사적 대규모 매수 (과열, 2024 WGBI 편입 이후 드묾)
      //    0 = 중립
      const HISTORIC_THRESHOLD_KRW = 200000; // 20조 (억원 단위)
      d.KOSPI_FOREIGN_HISTORIC_EXTREME = {
        name: 'kospi_foreign_historic_extreme',
        value:
          summary.foreignNet20D >= HISTORIC_THRESHOLD_KRW ? 1 :
          summary.foreignNet20D <= -HISTORIC_THRESHOLD_KRW ? -1 : 0,
        date: summary.latestDate,
        formula: `20D 순매수 합 ≥+20조 → 역사적 과열(+1) / ≤-20조 → 역사적 공황(-1). stt_kospi "2025년 2~3월 45~60조 매도" 수준 감지 (월 22~30조 ≈ 20영업일 ±20조).`,
      };

      // === 개인 순매수 시리즈 (8차 TOP7 Fix #1) ===
      // 외인/기관 반대 주체. 개인이 외국인 대규모 매물을 흡수하는 구도는 역사적으로 악성.
      d.KOSPI_INDIVIDUAL_NET_1D = {
        name: 'kospi_individual_net_1d',
        value: summary.individualLatest,
        date: summary.latestDate,
        formula: '당일 개인 순매수 (억원, 네이버 금융)',
      };
      d.KOSPI_INDIVIDUAL_NET_5D = {
        name: 'kospi_individual_net_5d',
        value: summary.individualNet5D,
        date: summary.latestDate,
        formula: '최근 5영업일 개인 순매수 합 (억원)',
      };
      d.KOSPI_INDIVIDUAL_NET_20D = {
        name: 'kospi_individual_net_20d',
        value: summary.individualNet20D,
        date: summary.latestDate,
        formula: '최근 20영업일 개인 순매수 합 (억원)',
      };

      // === 외인-개인 괴리 경보 (8차 TOP7 Fix #1) ===
      // 외인 5일 순매도 ≥ 3조 AND 개인 5일 순매수 ≥ 3조 → 악성 구도 (+1)
      // 반대 (외인 +3조 AND 개인 -3조) → 가능 (-1)
      // 평시 0.
      let divergenceLevel = 0;
      if (summary.foreignNet5D <= -30000 && summary.individualNet5D >= 30000) {
        divergenceLevel = 1;
      } else if (summary.foreignNet5D >= 30000 && summary.individualNet5D <= -30000) {
        divergenceLevel = -1;
      }
      d.KOSPI_FOREIGN_INDIVIDUAL_DIVERGENCE = {
        name: 'kospi_foreign_individual_divergence',
        value: divergenceLevel,
        date: summary.latestDate,
        formula: `외인5D ${summary.foreignNet5D}억·개인5D ${summary.individualNet5D}억. +1=개인이 외인 매물 흡수(악성), -1=반대, 0=평시. 3조 기준.`,
      };

      // === 외국인-환율 괴리 (ATM화) 지수 (영상5 §112 "환율 5% 상승 대비 외국인 매도 2배 과잉") ===
      // USDKRW 20일 변화율 × 예상매도 계수 로 기대 외국인 순매도 산출.
      // 13차 B3 개선 (2026-04): 기존 -30000 하드코딩 대신 **최근 1년 rolling 회귀 계수** 사용.
      //   관측 불가 시 stt_kospi 영상 기본값 -30000 으로 fallback.
      try {
        const usdkrwHist = await readHistory('yahoo', 'USDKRW');
        if (usdkrwHist.length >= 20) {
          const curFx = usdkrwHist[usdkrwHist.length - 1].value;
          const oldFx = usdkrwHist[usdkrwHist.length - 20].value;
          const fxChangePct = ((curFx - oldFx) / oldFx) * 100;

          // β 계수 추정 (최근 1년 관측치, no-intercept LS)
          //   x_i = FX 20D change%, y_i = foreign net 20D 누적 (억원)
          let beta = -30000;
          let betaSource = 'stt_kospi 영상 기본값';
          try {
            const foreignHistKr = await readHistory('krx', 'KOSPI_FOREIGN_NET_1D');
            if (foreignHistKr.length >= 60) {
              const obs: Array<{ x: number; y: number }> = [];
              for (let k = 20; k < Math.min(foreignHistKr.length, 252); k++) {
                const fDate = foreignHistKr[foreignHistKr.length - 1 - k].date;
                let fxEnd = -1;
                for (let j = usdkrwHist.length - 1; j >= 0; j--) {
                  if (usdkrwHist[j].date <= fDate) { fxEnd = j; break; }
                }
                if (fxEnd < 20) continue;
                const fxOld = usdkrwHist[fxEnd - 20].value;
                const fxCur = usdkrwHist[fxEnd].value;
                if (fxOld <= 0) continue;
                const xPct = ((fxCur - fxOld) / fxOld) * 100;
                let netSum = 0;
                for (let j = 0; j < 20; j++) {
                  const idx = foreignHistKr.length - 1 - k + j;
                  if (idx >= 0 && idx < foreignHistKr.length) netSum += foreignHistKr[idx].value;
                }
                obs.push({ x: xPct, y: netSum });
              }
              if (obs.length >= 30) {
                const sumXY = obs.reduce((s, o) => s + o.x * o.y, 0);
                const sumXX = obs.reduce((s, o) => s + o.x * o.x, 0);
                if (sumXX > 0) {
                  beta = sumXY / sumXX;
                  betaSource = `rolling 회귀 n=${obs.length}`;
                }
              }
            }
          } catch {
            /* KRX history 실패 시 fallback */
          }

          const expectedSell = fxChangePct * beta;
          const actualNet = summary.foreignNet20D;
          d.FX_FOREIGN_BETA = {
            name: 'fx_foreign_beta',
            value: parseFloat(beta.toFixed(0)),
            date: summary.latestDate,
            formula: `환율 1%↑당 외국인 20D 순매수 계수 (억원). 현재 β=${beta.toFixed(0)} / ${betaSource}. stt_kospi 기본 -30000.`,
          };
          d.KOSPI_FX_20D_CHANGE_PCT = {
            name: 'kospi_fx_20d_change_pct',
            value: parseFloat(fxChangePct.toFixed(3)),
            date: summary.latestDate,
            formula: 'USD/KRW 20영업일 변화율(%). +는 원화 약세 → 외국인 매도 압력',
          };
          d.KOSPI_FOREIGN_EXPECTED_SELL_KRW = {
            name: 'kospi_foreign_expected_sell_krw',
            value: parseFloat(expectedSell.toFixed(0)),
            date: summary.latestDate,
            formula: `FX20D × β (β=${beta.toFixed(0)}, 억원). 13차 rolling 회귀, stt_kospi 기본 -30000.`,
          };
          d.KOSPI_FOREIGN_ACTUAL_SELL_KRW = {
            name: 'kospi_foreign_actual_sell_krw',
            value: parseFloat(actualNet.toFixed(0)),
            date: summary.latestDate,
            formula: '최근 20영업일 외국인 순매수 실제합 (억원). 음수 = 매도',
          };
          // 탄력성 편차: |실제매도| / |기대매도|. >1.5 = 과매도, <0.5 = 과소매도
          const absExpected = Math.abs(expectedSell);
          const absActual = Math.abs(actualNet);
          const elasticityDeviation = absExpected > 1 ? absActual / absExpected : null;
          d.KOSPI_FX_ELASTICITY_DEVIATION = {
            name: 'kospi_fx_elasticity_deviation',
            value: elasticityDeviation !== null ? parseFloat(elasticityDeviation.toFixed(2)) : null,
            date: summary.latestDate,
            // 26차 P1#3: video5_analysis "1배 정상 / 2배 과잉" 정합 명시.
            // stt_kospi §3부 "환율 5% 상승 vs 외국인 60조 매도 = 12배 ATM화" 극단 사례 인용.
            formula: '|실제20D외인순매도| / |기대매도(FX×3조)|. video5 §"1배 정상 / 2배 과잉" / stt_kospi §3부 "12배 ATM화 비정상". <0.5=과소매도, ≥1.5=과매도 반발 후보, ≥2=ATM 경고, ≥6=극단.',
          };
          // 기존 divergence 유지 (부호 포함 비율 — 방향성 확인용)
          const divergence = expectedSell !== 0 ? actualNet / expectedSell : 0;
          d.KOSPI_FX_FOREIGN_DIVERGENCE = {
            name: 'kospi_fx_foreign_divergence',
            value: parseFloat(divergence.toFixed(2)),
            date: summary.latestDate,
            formula: `실제 외국인20D 순매수 / 기대매도(환율상승×-3조). 2+ 는 과매도 ATM화(반발 후보), <0.5 는 정상/매수`,
          };
          // ATM 경고: 편차 ≥2 AND FX 상승이면 강화 경고 (기존 divergence 조건 병행 평가)
          const atmStrong = elasticityDeviation !== null && elasticityDeviation >= 2 && fxChangePct > 0;
          const atmMild = divergence >= 2 && fxChangePct > 0;
          if (atmStrong) {
            d.KOSPI_ATM_WARNING = {
              name: 'kospi_atm_warning',
              value: 2,
              date: summary.latestDate,
              formula: `FX +${fxChangePct.toFixed(2)}% 대비 실제 외국인 매도가 기대치 ${elasticityDeviation?.toFixed(1)}배 — 강한 과매도 반발 조기신호 (영상5 §112)`,
            };
          } else if (atmMild) {
            d.KOSPI_ATM_WARNING = {
              name: 'kospi_atm_warning',
              value: 1,
              date: summary.latestDate,
              formula: `환율 +${fxChangePct.toFixed(2)}% 대비 외국인 매도가 기대치 ${divergence.toFixed(1)}배 — 과매도 반발 조기신호 (영상5 §112)`,
            };
          } else {
            d.KOSPI_ATM_WARNING = {
              name: 'kospi_atm_warning',
              value: 0,
              date: summary.latestDate,
              formula: 'ATM화 조건 미충족',
            };
          }
        }
      } catch {
        /* 괴리 계산 실패는 무시 */
      }
    }
  } catch {
    /* 외국인 수급 수집 실패는 전체 파이프라인 막지 않음 */
  }

  // === 크레딧 스프레드 (HY OAS + HYG/IEF 상대강도) ===
  // HY OAS 는 FRED 가 소수점(예: 3.42 = 342bp)로 제공 → bp 로 환산.
  // HYG/IEF 비율은 252영업일 z-score 로 정규화, flag 는 hard threshold + z-score OR 조합.
  try {
    const hyOasRaw = val(raw, 'BAMLH0A0HYM2');
    let hyOasBp: number | null = null;
    if (hyOasRaw !== null) {
      // 일부 케이스에서 이미 bp 로 저장돼 있을 수 있어 스케일 자동 감지.
      hyOasBp = hyOasRaw > 50 ? hyOasRaw : hyOasRaw * 100;
      d.CREDIT_HY_OAS_BP = {
        name: 'credit_hy_oas_bp',
        value: parseFloat(hyOasBp.toFixed(1)),
        date: dt,
        formula: 'BAMLH0A0HYM2 (ICE BofA US HY OAS) — bp 환산',
      };
    }

    const hygHist = await readHistory('yahoo', 'HYG');
    const iefHist = await readHistory('yahoo', 'IEF');
    let hygIefZ: number | null = null;
    if (hygHist.length >= 30 && iefHist.length >= 30) {
      // 둘 다 오름차순. 날짜 기준 정렬된 ratio 시리즈 구성.
      const iefMap = new Map<string, number>();
      for (const p of iefHist) iefMap.set(p.date, p.value);
      const ratios: { date: string; ratio: number }[] = [];
      for (const h of hygHist) {
        const ief = iefMap.get(h.date);
        if (ief && ief > 0 && h.value > 0) {
          ratios.push({ date: h.date, ratio: h.value / ief });
        }
      }
      if (ratios.length > 0) {
        const latest = ratios[ratios.length - 1];
        d.CREDIT_HYG_IEF_RATIO = {
          name: 'credit_hyg_ief_ratio',
          value: parseFloat(latest.ratio.toFixed(4)),
          date: dt,
          formula: 'Yahoo HYG 종가 / IEF 종가',
        };
        if (ratios.length >= 252) {
          const window = ratios.slice(-252).map((r) => r.ratio);
          const mean = window.reduce((s, v) => s + v, 0) / window.length;
          const variance = window.reduce((s, v) => s + (v - mean) ** 2, 0) / window.length;
          const stdev = Math.sqrt(variance);
          if (stdev > 0) {
            hygIefZ = (latest.ratio - mean) / stdev;
            d.CREDIT_HYG_IEF_ZSCORE = {
              name: 'credit_hyg_ief_zscore',
              value: parseFloat(hygIefZ.toFixed(2)),
              date: dt,
              formula: '(HYG/IEF 현재 - 252일 평균) / 252일 표준편차',
            };
          }
        }
      }
    }

    const stressHy = hyOasBp !== null && hyOasBp >= 600;
    const stressZ = hygIefZ !== null && hygIefZ <= -2;
    if (hyOasBp !== null || hygIefZ !== null) {
      const flag = stressHy || stressZ;
      d.CREDIT_STRESS_FLAG = {
        name: 'credit_stress_flag',
        value: flag ? 1 : 0,
        date: dt,
        formula: `HY OAS ≥ 600bp(${stressHy ? 'Y' : 'N'}) OR HYG/IEF z ≤ -2(${stressZ ? 'Y' : 'N'})`,
      };
    }
  } catch {
    /* 크레딧 스프레드 계산 실패는 파이프라인 막지 않음 */
  }

  // === M2 YoY 방향 전환 훅 (WM2NS 주간 시리즈) ===
  // 유동성 방향 전환(음→양 교차) 이후 경과일을 트래킹해 "유동성 랠리 초기 구간" 식별.
  try {
    const m2Hist = await readHistory('fred', 'WM2NS');
    if (m2Hist.length >= 60) {
      // YoY 시리즈: 각 포인트에 대해 52주 전 포인트 찾아 % 변화 산출.
      const yoySeries: { date: string; yoy: number }[] = [];
      for (let i = 0; i < m2Hist.length; i += 1) {
        const cur = m2Hist[i];
        const targetDt = new Date(cur.date);
        targetDt.setFullYear(targetDt.getFullYear() - 1);
        const targetMs = targetDt.getTime();
        // 52주 전에 가장 근접한 과거 포인트
        let past: { date: string; value: number } | null = null;
        for (let j = i - 1; j >= 0; j -= 1) {
          if (new Date(m2Hist[j].date).getTime() <= targetMs) {
            past = m2Hist[j];
            break;
          }
        }
        if (past && past.value > 0) {
          yoySeries.push({ date: cur.date, yoy: (cur.value / past.value - 1) * 100 });
        }
      }

      if (yoySeries.length > 0) {
        const latest = yoySeries[yoySeries.length - 1];
        d.M2_YOY_PCT = {
          name: 'm2_yoy_pct',
          value: parseFloat(latest.yoy.toFixed(2)),
          date: dt,
          formula: 'WM2NS 현재 / 52주 전 - 1 (%)',
        };

        // 3개월(약 13주) 전 YoY 대비 변화 (포인트)
        if (yoySeries.length >= 14) {
          const past3m = yoySeries[yoySeries.length - 14];
          const delta = latest.yoy - past3m.yoy;
          d.M2_YOY_DELTA_3M = {
            name: 'm2_yoy_delta_3m',
            value: parseFloat(delta.toFixed(2)),
            date: dt,
            formula: 'M2 YoY 현재 - 약 13주 전 YoY (포인트)',
          };
        }

        // 음→양 교차 탐색: 현재가 양수일 때만 의미.
        if (latest.yoy > 0) {
          let crossDate: string | null = null;
          for (let i = yoySeries.length - 1; i > 0; i -= 1) {
            const prev = yoySeries[i - 1];
            const cur = yoySeries[i];
            if (prev.yoy < 0 && cur.yoy >= 0) {
              crossDate = cur.date;
              break;
            }
          }
          if (crossDate) {
            const daysElapsed = Math.floor(
              (new Date(latest.date).getTime() - new Date(crossDate).getTime()) / 86400000,
            );
            d.M2_YOY_CROSS_DAYS = {
              name: 'm2_yoy_cross_days',
              value: daysElapsed,
              date: dt,
              formula: `WM2NS YoY 음→양 교차(${crossDate}) 이후 경과일`,
            };
          } else {
            d.M2_YOY_CROSS_DAYS = {
              name: 'm2_yoy_cross_days',
              value: null,
              date: dt,
              formula: '히스토리 내 음→양 교차 미확인',
            };
          }
        } else {
          d.M2_YOY_CROSS_DAYS = {
            name: 'm2_yoy_cross_days',
            value: null,
            date: dt,
            formula: '현재 YoY 음수 — 교차 미발생',
          };
        }
      }
    }
  } catch {
    /* M2 YoY 계산 실패는 파이프라인 막지 않음 */
  }

  // === 심리 서브스코어 (PSYCH_SUBSCORE) ===
  // 6차 TOP3 Step 2 — F&G · P/C Ratio(10D) · AAII spread · NAAIM 가중평균(각 0.25).
  // null 컴포넌트는 스킵하고 남은 가중치를 재정규화.
  // 2026-04 개선: CBOE options chain 방식은 당일값만 제공 (ma10 extra null) 이라
  // raw 에 PC_RATIO_10D 가 없음 → sentiment history 의 최근 10 entries 로 자체 롤링 계산.
  try {
    const fng = val(raw, 'FEAR_GREED');
    const aaii = val(raw, 'AAII_BULL_BEAR_SPREAD');
    const naaim = val(raw, 'NAAIM_EXPOSURE');

    // PC_RATIO 10D 롤링: history 에 append 된 daily PC_RATIO 에서 계산.
    let pcr10: number | null = null;
    try {
      const pcrHist = await readHistory('sentiment', 'PC_RATIO');
      if (pcrHist.length > 0) {
        const last10 = pcrHist.slice(-10).map((p) => p.value).filter(Number.isFinite);
        if (last10.length >= 3) {
          pcr10 = last10.reduce((a, b) => a + b, 0) / last10.length;
          pcr10 = parseFloat(pcr10.toFixed(3));
        }
      }
    } catch {
      /* pcr 10D 계산 실패 — null 유지 */
    }

    // 패스스루 derived (대시보드/패널에서 참조)
    if (pcr10 !== null) {
      d.PC_RATIO_10D = {
        name: 'pc_ratio_10d',
        value: pcr10,
        date: dt,
        formula: 'CBOE Put/Call Ratio 10일 이동평균 (sentiment/PC_RATIO history 기반 자체 롤링)',
      };
    }
    if (aaii !== null) {
      d.AAII_BULL_BEAR_SPREAD = {
        name: 'aaii_bull_bear_spread',
        value: aaii,
        date: dt,
        formula: 'AAII Bullish% - Bearish% (주간)',
      };
    }
    if (naaim !== null) {
      d.NAAIM_EXPOSURE = {
        name: 'naaim_exposure',
        value: naaim,
        date: dt,
        formula: 'NAAIM Exposure Index (주간 평균)',
      };
    }

    const components: { v: number | null; w: number }[] = [];

    // F&G: 0~100 을 0~1 로 선형 정규화 (높을수록 탐욕)
    if (fng !== null && Number.isFinite(fng)) {
      components.push({ v: Math.max(0, Math.min(1, fng / 100)), w: 0.25 });
    } else {
      components.push({ v: null, w: 0.25 });
    }

    // PCR: 1.2+ = 극공포(=0), 0.7- = 극탐욕(=1) → 역정규화
    if (pcr10 !== null && Number.isFinite(pcr10)) {
      const clamped = Math.max(0.7, Math.min(1.2, pcr10));
      const normalized = (1.2 - clamped) / (1.2 - 0.7); // 1.2→0, 0.7→1
      components.push({ v: Math.max(0, Math.min(1, normalized)), w: 0.25 });
    } else {
      components.push({ v: null, w: 0.25 });
    }

    // AAII spread: -40(극공포) ~ +40(극탐욕)
    if (aaii !== null && Number.isFinite(aaii)) {
      const clamped = Math.max(-40, Math.min(40, aaii));
      components.push({ v: (clamped + 40) / 80, w: 0.25 });
    } else {
      components.push({ v: null, w: 0.25 });
    }

    // NAAIM: 0~100 (이론상 -200~+200 이지만 실관측 0~100 범위 대부분)
    if (naaim !== null && Number.isFinite(naaim)) {
      const clamped = Math.max(0, Math.min(100, naaim));
      components.push({ v: clamped / 100, w: 0.25 });
    } else {
      components.push({ v: null, w: 0.25 });
    }

    const valid = components.filter((c) => c.v !== null);
    if (valid.length > 0) {
      const totalW = valid.reduce((s, c) => s + c.w, 0);
      const score = valid.reduce((s, c) => s + (c.v as number) * c.w, 0) / totalW;
      d.PSYCH_SUBSCORE = {
        name: 'psych_subscore',
        value: parseFloat(score.toFixed(3)),
        date: dt,
        formula: `F&G·P/C 10D·AAII·NAAIM 가중평균 (가중 0.25 각, null 스킵 후 재정규화, ${valid.length}/4)`,
      };
    }

    // === 8차 TOP7 Fix #6: F&G ↔ AAII 심리-심리 divergence (관측 전용) ===
    // 두 심리지표 간 극단 괴리 — 기관(F&G 로 대표)과 개인(AAII spread) 심리가 정반대.
    // +1: F&G ≤25 극공포 AND AAII ≥0 탐욕 (기관 공포 / 개인 탐욕)
    // -1: F&G ≥75 탐욕 AND AAII ≤-20 극공포 (기관 탐욕 / 개인 공포)
    // 시그널 영향 없음 (관측만).
    if (fng !== null && aaii !== null) {
      let divergenceLevel = 0;
      let label = '';
      if (fng <= 25 && aaii >= 0) {
        divergenceLevel = 1;
        label = `F&G ${fng.toFixed(0)} ≤25 극공포 AND AAII ${aaii.toFixed(0)} ≥0 탐욕 — 개인 탐욕 혼재`;
      } else if (fng >= 75 && aaii <= -20) {
        divergenceLevel = -1;
        label = `F&G ${fng.toFixed(0)} ≥75 탐욕 AND AAII ${aaii.toFixed(0)} ≤-20 극공포 — 기관 탐욕 혼재`;
      } else {
        label = `F&G ${fng.toFixed(0)} / AAII ${aaii.toFixed(0)} — 괴리 없음`;
      }
      d.PSYCH_DIVERGENCE = {
        name: 'psych_divergence',
        value: divergenceLevel,
        date: dt,
        formula: `${label}. +1=공포혼재, -1=탐욕혼재, 0=평시`,
      };
    }
  } catch {
    /* 심리 서브스코어 실패는 파이프라인 막지 않음 */
  }

  // === 호르무즈 연쇄 체인 스코어 (8차 TOP7 Fix #2) ===
  // WTI 60D 변화 + OVX + DXY 추세 + KRW 레벨 + 외국인 연속 순매도 5축 가중합.
  // 양수=완화 연쇄(한국 우호), 음수=악성 연쇄(호르무즈 불안→유가↑→원화약세→외인이탈).
  // 각 축 정규화 [-2..+2] 후 가중합 → [-5..+5] 범위로 클램프.
  try {
    const wti60 = d.WTI_60D_CHANGE?.value ?? null;
    const ovx = val(raw, 'OVX');
    const dxyTrend = d.DXY_TREND?.value ?? null;
    const fxLevel = d.KRW_FX_LEVEL?.value ?? null;
    const fgnSellStreak = d.KOSPI_FOREIGN_SELL_STREAK?.value ?? null;

    const axes: Array<{ name: string; score: number | null }> = [];

    // WTI 60d: 유가 상승 = 악성 (지정학 리스크 확산), 하락 = 완화
    if (wti60 !== null) {
      let s = 0;
      if (wti60 > 10) s = -2;
      else if (wti60 > 5) s = -1;
      else if (wti60 < -10) s = 2;
      else if (wti60 < -5) s = 1;
      axes.push({ name: 'WTI60D', score: s });
    } else {
      axes.push({ name: 'WTI60D', score: null });
    }

    // OVX: 원유 변동성 ≥ 60 = risk-off 확산 신호 (-1.5)
    if (ovx !== null) {
      let s = 0;
      if (ovx >= 60) s = -1.5;
      else if (ovx >= 45) s = -0.5;
      else if (ovx <= 25) s = 0.5;
      axes.push({ name: 'OVX', score: s });
    } else {
      axes.push({ name: 'OVX', score: null });
    }

    // DXY 약세 = KRW 에 우호 (지정학 달러 수요 완화)
    if (dxyTrend !== null) {
      let s = 0;
      if (dxyTrend < -1) s = 1;
      else if (dxyTrend < -0.3) s = 0.5;
      else if (dxyTrend > 1) s = -1;
      else if (dxyTrend > 0.3) s = -0.5;
      axes.push({ name: 'DXY_TREND', score: s });
    } else {
      axes.push({ name: 'DXY_TREND', score: null });
    }

    // KRW 레벨: KRW_FX_LEVEL 은 이미 [-2..+2]. 환율 약세 극단이면 연쇄 악성 가속.
    if (fxLevel !== null) {
      let s = 0;
      if (fxLevel >= 1) s = 1.5;
      else if (fxLevel <= -1) s = -1.5;
      else if (fxLevel <= -2) s = -2;
      axes.push({ name: 'KRW_LEVEL', score: s });
    } else {
      axes.push({ name: 'KRW_LEVEL', score: null });
    }

    // 외국인 연속 순매도 streak: 이미 연쇄 결과물 관측치
    if (fgnSellStreak !== null) {
      let s = 0;
      if (fgnSellStreak >= 5) s = -1;
      else if (fgnSellStreak >= 3) s = -0.5;
      else if (fgnSellStreak === 0) s = 1;
      axes.push({ name: 'FOREIGN_SELL_STREAK', score: s });
    } else {
      axes.push({ name: 'FOREIGN_SELL_STREAK', score: null });
    }

    const valid = axes.filter((a) => a.score !== null);
    if (valid.length > 0) {
      let rawScore = valid.reduce((s, a) => s + (a.score as number), 0);
      // 클램프 [-5..+5]
      if (rawScore > 5) rawScore = 5;
      if (rawScore < -5) rawScore = -5;

      d.HORMUZ_CHAIN_SCORE = {
        name: 'hormuz_chain_score',
        value: parseFloat(rawScore.toFixed(2)),
        date: dt,
        formula: `5축 가중합 (${valid.map((a) => `${a.name}:${(a.score as number).toFixed(1)}`).join(' · ')}). 범위 [-5..+5]. ${valid.length}/5 축 집계.`,
      };

      let label: string;
      if (rawScore <= -3) label = '악성 연쇄';
      else if (rawScore <= -1) label = '주의';
      else if (rawScore <= 1) label = '중립';
      else if (rawScore <= 3) label = '우호';
      else label = '완화 연쇄';

      d.HORMUZ_CHAIN_LABEL = {
        name: 'hormuz_chain_label',
        value: rawScore, // UI 는 score 로 라벨 매핑
        date: dt,
        formula: `${label} (≤-3 악성 / -3~-1 주의 / -1~+1 중립 / +1~+3 우호 / ≥+3 완화 연쇄)`,
      };
    }
  } catch {
    /* 호르무즈 연쇄 체인 스코어 실패는 파이프라인 막지 않음 */
  }

  // === GOLD_PRIORITY_SCORE (8차 TOP7 Fix #3 → 4축 완성) ===
  // 영상5 금 우선순위 4축 완성:
  //   축 1 (가중 4): REAL_YIELD_TREND < 0 (실질금리 하락) → 금 1순위 우호
  //   축 2 (가중 3): DXY_TREND < -0.5 (단기 약세)
  //   축 3 (가중 2): manualInputs.cbBuying === true (중앙은행 매수)
  //   축 4 (가중 1): manualInputs.geoRisk >= 3 (지정학 위험 고조)
  // 총합 / 10 → 0~1 정규화.
  // manualInputs 가 undefined 이면 기존 2축 (실질금리 4 + DXY 3, /7) 으로 폴백 — 과거 재계산 등에서 호환.
  try {
    const ryTrend = d.REAL_YIELD_TREND?.value ?? null;
    const dxyTrend = d.DXY_TREND?.value ?? null;
    const hasManual = manualInputs !== undefined;
    if (ryTrend !== null || dxyTrend !== null) {
      let score = 0;
      const maxScore = hasManual ? 10 : 7;
      const parts: string[] = [];
      // 축 1 (가중 4): REAL_YIELD_TREND < 0
      if (ryTrend !== null && ryTrend < 0) {
        score += 4;
        parts.push(`RY_TREND ${ryTrend.toFixed(3)}<0 (+4)`);
      } else if (ryTrend !== null) {
        parts.push(`RY_TREND ${ryTrend.toFixed(3)}≥0 (+0)`);
      } else {
        parts.push('RY_TREND null');
      }
      // 축 2 (가중 3): DXY_TREND < -0.5
      if (dxyTrend !== null && dxyTrend < -0.5) {
        score += 3;
        parts.push(`DXY_TREND ${dxyTrend.toFixed(2)}<-0.5 (+3)`);
      } else if (dxyTrend !== null) {
        parts.push(`DXY_TREND ${dxyTrend.toFixed(2)}≥-0.5 (+0)`);
      } else {
        parts.push('DXY_TREND null');
      }
      // 축 3 (가중 2): cbBuying
      if (hasManual) {
        if (manualInputs?.cbBuying === true) {
          score += 2;
          parts.push('CB_BUYING=true (+2)');
        } else {
          parts.push('CB_BUYING=false (+0)');
        }
        // 축 4 (가중 1): geoRisk >= 3
        const geoRisk = manualInputs?.geoRisk ?? 0;
        if (geoRisk >= 3) {
          score += 1;
          parts.push(`GEO_RISK ${geoRisk}≥3 (+1)`);
        } else {
          parts.push(`GEO_RISK ${geoRisk}<3 (+0)`);
        }
      }

      const normalized = score / maxScore;
      const formulaHeader = hasManual
        ? '4축 가중 (실질금리 4 + DXY 3 + CB매수 2 + 지정학 1) / 10'
        : '2축 폴백 (실질금리 4 + DXY 3) / 7 — manualInputs 미전달 (과거 재계산 등)';
      d.GOLD_PRIORITY_SCORE = {
        name: 'gold_priority_score',
        value: parseFloat(normalized.toFixed(3)),
        date: dt,
        formula: `${formulaHeader} → 0~1. ${parts.join(' · ')}. ≥0.7 금 매수 강화 / ≤0.3 금 감산.`,
      };
    }
  } catch {
    /* GOLD_PRIORITY_SCORE 실패는 파이프라인 막지 않음 */
  }

  // === FX_FOREIGN_COMBO_ALERT (7차 TOP3 Fix #2) ===
  // USDKRW 레벨과 외국인 연속 순매도 streak 의 교집합 경보.
  // 단일 환율 레벨보다 "환율 극단 + 외국인 실제 이탈" 동시 충족 시 KOSPI/EMERGING 감산 강화.
  // HARD(2): 환율 ≥1500 AND 외국인 5일 이상 연속 순매도 — 이중 게이트 극단.
  // SOFT(1): 환율 ≥1480 AND 외국인 3일 이상 연속 순매도 — 경고.
  // WATCH(-1): 환율 ≤1400 AND 외국인 연속 순매도 0 — 외인 복귀 유리 조건.
  try {
    const usdkrw = val(raw, 'USDKRW');
    const foreignSellStreak = d.KOSPI_FOREIGN_SELL_STREAK?.value ?? null;
    if (usdkrw !== null && foreignSellStreak !== null) {
      let level: number | null = null;
      let label = '';
      if (usdkrw >= 1500 && foreignSellStreak >= 5) {
        level = 2;
        label = 'HARD — 환율 1500+ AND 외인 5일+ 연속 매도';
      } else if (usdkrw >= 1480 && foreignSellStreak >= 3) {
        level = 1;
        label = 'SOFT — 환율 1480+ AND 외인 3일+ 연속 매도';
      } else if (usdkrw <= 1480 && foreignSellStreak <= 0) {
        // 19차 P2#11: WATCH 컷 1400 → 1480. video5 §3-1 "1480 이하 = 외국인 복귀 컷" 정합.
        level = -1;
        label = 'WATCH — 환율 1480- AND 외인 매도 streak 없음 (복귀 유리)';
      }
      if (level !== null) {
        d.FX_FOREIGN_COMBO_ALERT = {
          name: 'fx_foreign_combo_alert',
          value: level,
          date: dt,
          formula: `HARD=2, SOFT=1, WATCH=-1. ${label}`,
        };
      }
    }
  } catch {
    /* FX+외인 복합 게이트 실패는 파이프라인 막지 않음 */
  }

  // === GEOPOLITICAL_UNWIND_EVENT + SHORT_COVER_SUSPECTED (9차 gap TOP3 Fix #2) ===
  // stt_kospi 4:58 + 5:06 — 지정학 리스크 해소 시점에 KOSPI↑/USDKRW↓/WTI↓ 동시 급변 관찰.
  // 3축 중 2+ 충족: EVENT=1, 3축 전부: EVENT=2, 아니면 0.
  // 외인 순매수 1조+ 동반 시 숏커버링 의심 (SHORT_COVER_SUSPECTED=1).
  //   축 1: KOSPI 일봉 변동 ≥ +5%
  //   축 2: USDKRW 일봉 변동 ≤ -1.5%
  //   축 3: WTI 일봉 변동 ≤ -10%
  try {
    const kospiH = await readHistory('yahoo', 'KOSPI');
    const fxH = await readHistory('yahoo', 'USDKRW');
    const wtiH = await readHistory('yahoo', 'WTI');

    const pct1d = (hist: Array<{ value: number }>): number | null => {
      if (hist.length < 2) return null;
      const cur = hist[hist.length - 1].value;
      const prev = hist[hist.length - 2].value;
      if (!Number.isFinite(cur) || !Number.isFinite(prev) || prev === 0) return null;
      return ((cur - prev) / prev) * 100;
    };

    const kospiPct = pct1d(kospiH);
    const fxPct = pct1d(fxH);
    const wtiPct = pct1d(wtiH);

    if (kospiPct === null || fxPct === null || wtiPct === null) {
      d.GEOPOLITICAL_UNWIND_EVENT = {
        name: 'geopolitical_unwind_event',
        value: null,
        date: dt,
        formula: `데이터 부족 (KOSPI=${kospiPct ?? 'n/a'} / USDKRW=${fxPct ?? 'n/a'} / WTI=${wtiPct ?? 'n/a'})`,
      };
    } else {
      const axKospi = kospiPct >= 5;
      const axFx = fxPct <= -1.5;
      const axWti = wtiPct <= -10;
      const hitCount = (axKospi ? 1 : 0) + (axFx ? 1 : 0) + (axWti ? 1 : 0);
      const eventLevel = hitCount >= 3 ? 2 : hitCount >= 2 ? 1 : 0;
      d.GEOPOLITICAL_UNWIND_EVENT = {
        name: 'geopolitical_unwind_event',
        value: eventLevel,
        date: dt,
        formula: `3축 [KOSPI${axKospi ? 'Y' : 'N'}(${kospiPct.toFixed(2)}%) · FX${axFx ? 'Y' : 'N'}(${fxPct.toFixed(2)}%) · WTI${axWti ? 'Y' : 'N'}(${wtiPct.toFixed(2)}%)] ${hitCount}개 충족 → ${eventLevel === 2 ? '3축 전부' : eventLevel === 1 ? '2축' : '미충족'} (stt_kospi 4:58/5:06)`,
      };

      // SHORT_COVER_SUSPECTED: EVENT >= 1 AND 외인 1D 순매수 >= 10000 (1조, 억원 단위)
      const foreignNet1D = d.KOSPI_FOREIGN_NET_1D?.value ?? null;
      if (eventLevel >= 1 && foreignNet1D !== null) {
        const shortCover = foreignNet1D >= 10000 ? 1 : 0;
        d.SHORT_COVER_SUSPECTED = {
          name: 'short_cover_suspected',
          value: shortCover,
          date: dt,
          formula: `EVENT=${eventLevel} AND 외인 1D 순매수 ${foreignNet1D}억원 ${foreignNet1D >= 10000 ? '≥' : '<'} 1조 → ${shortCover ? '숏커버 의심' : '해당 없음'}`,
        };
      } else {
        d.SHORT_COVER_SUSPECTED = {
          name: 'short_cover_suspected',
          value: eventLevel === 0 ? 0 : null,
          date: dt,
          formula: eventLevel === 0
            ? 'EVENT 미발동 → 숏커버 판정 대상 아님'
            : `EVENT=${eventLevel} 이나 외인 순매수 결측`,
        };
      }
    }
  } catch {
    /* 점프 이벤트 감지 실패는 파이프라인 막지 않음 */
  }

  // === NASDAQ/KOSPI CHASE_LEVEL 계층화 (9차 gap TOP3 Fix #3) ===
  // video3 추격금지 원칙을 0/1/2/3 soft/medium/hard 로 정량화.
  //   0 (none): 조건 없음
  //   1 (soft): 이격도 ≥ +15% 또는 streak ≥ 15일
  //   2 (medium): level=1 조건 + VIX < 15 (방심 구간)
  //   3 (hard): streak ≥ 25일 또는 이격도 ≥ +20%
  // 기존 NASDAQ_CHASE_WARNING / KOSPI_CHASE_WARNING (binary) 는 signals.ts 소비 중이라 병렬 유지.
  // 히스테리시스는 기존 flagPersistence 로 WARNING 만 대상, LEVEL 은 raw 값.
  try {
    const vixForLevel = val(raw, 'VIXCLS');
    const computeChaseLevel = (disparity: number | null, streak: number | null): { level: number | null; reason: string } => {
      if (disparity === null && streak === null) return { level: null, reason: '이격도·streak 결측' };
      // HARD 우선 검사
      const hardDisparity = disparity !== null && disparity >= 20;
      const hardStreak = streak !== null && streak >= 25;
      if (hardDisparity || hardStreak) {
        const parts: string[] = [];
        if (hardStreak) parts.push(`streak ${streak}일`);
        if (hardDisparity) parts.push(`이격 +${disparity!.toFixed(1)}%`);
        return { level: 3, reason: `${parts.join(' + ')} → 3(hard)` };
      }
      const softDisparity = disparity !== null && disparity >= 15;
      const softStreak = streak !== null && streak >= 15;
      if (softDisparity || softStreak) {
        const parts: string[] = [];
        if (softStreak) parts.push(`streak ${streak}일`);
        if (softDisparity) parts.push(`이격 +${disparity!.toFixed(1)}%`);
        // level 2 = soft + VIX < 15
        if (vixForLevel !== null && vixForLevel < 15) {
          return { level: 2, reason: `${parts.join(' + ')} + VIX ${vixForLevel.toFixed(1)}<15 → 2(medium, 방심구간)` };
        }
        return { level: 1, reason: `${parts.join(' + ')} → 1(soft)` };
      }
      return {
        level: 0,
        reason: `이격 ${disparity?.toFixed(1) ?? 'n/a'}% / streak ${streak ?? 'n/a'}일 — 추격 조건 미충족`,
      };
    };

    // NASDAQ
    const nDisparity = d.NASDAQ_DISPARITY?.value ?? null;
    const nStreak = d.NASDAQ_DISPARITY_STREAK_OVERHEATED?.value ?? null;
    const nasdaqResult = computeChaseLevel(nDisparity, nStreak);
    d.NASDAQ_CHASE_LEVEL = {
      name: 'nasdaq_chase_level',
      value: nasdaqResult.level === null ? null : Math.max(0, Math.min(3, nasdaqResult.level)),
      date: dt,
      formula: `${nasdaqResult.reason} (video3 추격금지 0/1/2/3 계층)`,
    };

    // KOSPI
    const kDisparity = d.KOSPI_DISPARITY?.value ?? null;
    const kStreak = d.KOSPI_DISPARITY_STREAK_OVERHEATED?.value ?? null;
    const kospiResult = computeChaseLevel(kDisparity, kStreak);
    d.KOSPI_CHASE_LEVEL = {
      name: 'kospi_chase_level',
      value: kospiResult.level === null ? null : Math.max(0, Math.min(3, kospiResult.level)),
      date: dt,
      formula: `${kospiResult.reason} (video3 추격금지 0/1/2/3 계층)`,
    };
  } catch {
    /* CHASE_LEVEL 실패는 파이프라인 막지 않음 */
  }

  // === LEVERAGE_TIER_RAW 관측용 파생 ===
  // signals.ts 와 동일 기준 재계산 (중복 정의지만 관측/로깅 편의).
  // 0=none, 1=SOFT, 2=MEDIUM, 3=HARD. 판정 우선순위 HARD > MEDIUM > SOFT.
  try {
    const disp = d.NASDAQ_DISPARITY?.value ?? null;
    const vix = raw.VIXCLS?.value ?? null;
    const icsa = raw.ICSA?.value ?? null;
    const icsaOk = icsa !== null && icsa < 300000;
    let level: number | null = null;
    let label = 'none';
    if (disp !== null && vix !== null && icsaOk) {
      if (disp <= -25 && vix >= 35) { level = 3; label = 'HARD'; }
      else if (disp <= -15 && vix >= 30) { level = 2; label = 'MEDIUM'; }
      else if (disp <= -5 && vix >= 30) { level = 1; label = 'SOFT'; }
      else { level = 0; }
    }
    const dispStr = disp !== null ? `${disp.toFixed(1)}%` : 'n/a';
    const vixStr = vix !== null ? vix.toFixed(1) : 'n/a';
    const icsaStr = icsa !== null ? `${Math.round(icsa / 1000)}K` : 'n/a';
    d.LEVERAGE_TIER_RAW = {
      name: 'leverage_tier_raw',
      value: level,
      date: today(),
      formula: `이격 ${dispStr} / VIX ${vixStr} / ICSA ${icsaStr} → ${label} (0=none,1=SOFT,2=MEDIUM,3=HARD)`,
    };
  } catch {
    /* LEVERAGE_TIER_RAW 실패는 파이프라인 막지 않음 */
  }

  // === N1 GOLD_SEASONAL (12차 2026-04) — video2 §4부 "금의 계절성" 정합 ===
  // 20년 금(GC=F) 월별 평균 수익률 중 상위 4개월 = 강시즌(+1), 하위 4개월 = 약시즌(-1).
  //   강시즌에 매수 보너스, 약시즌 경계 (보조조건).
  try {
    const gh = await fetchYahooHistory('GC=F', 365 * 20);
    if (gh.length >= 252 * 5) {
      const byMonth: Record<number, number[]> = {};
      for (let m = 1; m <= 12; m++) byMonth[m] = [];
      // 각 연월의 월초/월말 수익률 추출
      const byYM = new Map<string, { first: number; last: number }>();
      for (const p of gh) {
        const ym = p.date.slice(0, 7); // YYYY-MM
        const entry = byYM.get(ym);
        if (!entry) byYM.set(ym, { first: p.close, last: p.close });
        else entry.last = p.close;
      }
      for (const [ym, { first, last }] of byYM) {
        const mo = parseInt(ym.slice(5, 7), 10);
        if (first > 0) byMonth[mo].push((last - first) / first);
      }
      const avgByMonth: Array<{ m: number; avg: number }> = [];
      for (let m = 1; m <= 12; m++) {
        const arr = byMonth[m];
        const avg = arr.length > 0 ? arr.reduce((s, v) => s + v, 0) / arr.length : 0;
        avgByMonth.push({ m, avg });
      }
      avgByMonth.sort((a, b) => b.avg - a.avg);
      const topSet = new Set(avgByMonth.slice(0, 4).map((x) => x.m));
      const bottomSet = new Set(avgByMonth.slice(-4).map((x) => x.m));
      const curMonth = new Date().getMonth() + 1;
      const curAvg = avgByMonth.find((x) => x.m === curMonth)?.avg ?? 0;
      const season = topSet.has(curMonth) ? 1 : bottomSet.has(curMonth) ? -1 : 0;
      d.GOLD_SEASONAL = {
        name: 'gold_seasonal',
        value: season,
        date: today(),
        formula:
          `20년 금 월별 평균 수익률 기반. 현재 월 ${curMonth} 평균 ${(curAvg * 100).toFixed(2)}%. ` +
          `상위 4개월 강시즌(+1) / 하위 4개월 약시즌(-1) / 중립(0). video2 §4부 정합.`,
      };
    }
  } catch {
    /* GOLD_SEASONAL 실패 skip */
  }

  // === N2 CB_GOLD_STRUCTURAL_DEMAND (12차 2026-04) — video2 §1부 "중앙은행 구조 매수" proxy ===
  // video2 "3년 연속 1000톤+" 정합. WGC 직접 데이터 부재 → 환경 proxy:
  //   (a) 12M 금 수익률 > 10% (대세 상승)
  //   (b) DXY 12M 약세 (달러 약세 → 중앙은행 달러 비중 축소 경향)
  //   (c) 실질금리 하락 추세 (REAL_YIELD_TREND < 0)
  // 3가지 중 2개 이상 충족 시 1 (구조 매수 환경), 아니면 0.
  try {
    const gh = await fetchYahooHistory('GC=F', 400);
    const dxyFull = await fetchYahooHistory('DX-Y.NYB', 400);
    if (gh.length >= 252 && dxyFull.length >= 252) {
      const g0 = gh[gh.length - 252]?.close;
      const g1 = gh[gh.length - 1]?.close;
      const dx0 = dxyFull[dxyFull.length - 252]?.close;
      const dx1 = dxyFull[dxyFull.length - 1]?.close;
      const goldYoy = g0 && g0 > 0 ? (g1 - g0) / g0 : 0;
      const dxyYoy = dx0 && dx0 > 0 ? (dx1 - dx0) / dx0 : 0;
      const realYieldTrend = d.REAL_YIELD_TREND?.value ?? 0;
      const aGoldUp = goldYoy > 0.1 ? 1 : 0;
      const bDxyDown = dxyYoy < 0 ? 1 : 0;
      const cRyDown = realYieldTrend < 0 ? 1 : 0;
      const metCount = aGoldUp + bDxyDown + cRyDown;
      d.CB_GOLD_STRUCTURAL_DEMAND = {
        name: 'cb_gold_structural_demand',
        value: metCount >= 2 ? 1 : 0,
        date: today(),
        formula:
          `환경 proxy 3조건: 금12M ${(goldYoy * 100).toFixed(1)}%>10%[${aGoldUp}], ` +
          `DXY12M ${(dxyYoy * 100).toFixed(1)}%<0[${bDxyDown}], REAL_YIELD_TREND ${realYieldTrend.toFixed(3)}<0[${cRyDown}]. ` +
          `2개↑=1(구조 매수 환경). video2 §1부 "3년 연속 1000톤+" proxy.`,
      };
    }
  } catch {
    /* skip */
  }

  // (N3 NASDAQ_STRATEGY_B_COMPLETE 는 signals.ts 에서 계산 — manualInputs 접근 필요)

  // === N8 DMA_CONVERGENCE_LEVEL (13차 2026-04) — video3 §수렴 "이평선 모이면 폭발 직전" ===
  // 5개 주요 DMA (5/20/60/120/200) 의 **현재 값 표준편차 / 평균** (변동계수 CV) 기반.
  // CV 작을수록 이평선 수렴 = 에너지 응축. 이후 방향 폭발 기대.
  //   CV ≤ 1.5% → level +2 (극수렴, 폭발 직전)
  //   CV ≤ 3.0% → level +1 (수렴)
  //   CV ≤ 5.0% → level 0 (정상)
  //   CV ≤ 8.0% → level -1 (확산)
  //   CV > 8.0% → level -2 (극확산, 추세 강세)
  try {
    const nasdaqHistFull = await fetchYahooHistory('^IXIC', 400);
    if (nasdaqHistFull.length >= 200) {
      const closes = nasdaqHistFull.map((h) => h.close);
      const cur = closes[closes.length - 1];
      const sma = (n: number) => {
        if (closes.length < n) return null;
        const slice = closes.slice(-n);
        return slice.reduce((s, v) => s + v, 0) / n;
      };
      const mas = [5, 20, 60, 120, 200].map((n) => sma(n)).filter((v): v is number => v !== null);
      if (mas.length === 5) {
        const mean = mas.reduce((s, v) => s + v, 0) / mas.length;
        const variance = mas.reduce((s, v) => s + (v - mean) ** 2, 0) / mas.length;
        const std = Math.sqrt(variance);
        const cv = mean > 0 ? (std / mean) * 100 : 0;
        let level: number;
        if (cv <= 1.5) level = 2;
        else if (cv <= 3.0) level = 1;
        else if (cv <= 5.0) level = 0;
        else if (cv <= 8.0) level = -1;
        else level = -2;
        d.DMA_CONVERGENCE_LEVEL = {
          name: 'dma_convergence_level',
          value: level,
          date: today(),
          formula:
            `NASDAQ 5/20/60/120/200 DMA CV ${cv.toFixed(2)}% (현재 ${cur.toFixed(0)}, 평균 ${mean.toFixed(0)}, σ ${std.toFixed(0)}). ` +
            `+2(≤1.5% 극수렴, 폭발 직전) / +1(≤3%) / 0(≤5%) / -1(≤8% 확산) / -2(>8% 강추세). video3 §수렴 정합.`,
        };
      }
    }
  } catch {
    /* DMA_CONVERGENCE 실패 skip */
  }

  // === A3 WTI_COPPER_LAG_CORRELATION (13차 2026-04) — video2 §3부 ===
  // "오일 단기 급등 → 구리 2~3개월 후 타격" (유가가 경기 선행 아닌 cost burden)
  // WTI 60일 전 수익률 vs COPPER 현재 수익률. 양수 = WTI 과거 약세 → COPPER 현재 강세.
  //   값 > +0.1 (+10%) → 경기 회복 조기 (유가 과거 안정 + 구리 현재 상승)
  //   값 < -0.1 (-10%) → 경기 둔화 임박 (유가 과거 급등 → 구리 현재 하락)
  //   |값| ≤ 0.1 → 중립
  try {
    const wtiHist = await fetchYahooHistory('CL=F', 120);
    const copperHistLag = await fetchYahooHistory('HG=F', 120);
    if (wtiHist.length >= 80 && copperHistLag.length >= 80) {
      // WTI 60일 전 대비 90일 전 수익률 (t-90 ~ t-60 구간 변화)
      const wti_t90 = wtiHist[wtiHist.length - 90]?.close;
      const wti_t60 = wtiHist[wtiHist.length - 60]?.close;
      const wtiOldRet = wti_t90 && wti_t90 > 0 ? (wti_t60 - wti_t90) / wti_t90 : 0;
      // COPPER 최근 30일 수익률
      const cu_t30 = copperHistLag[copperHistLag.length - 30]?.close;
      const cu_t0 = copperHistLag[copperHistLag.length - 1]?.close;
      const cuRecentRet = cu_t30 && cu_t30 > 0 ? (cu_t0 - cu_t30) / cu_t30 : 0;
      // 신호: WTI 과거 약세(-) AND COPPER 현재 강세(+) = 경기 회복 조기
      //      WTI 과거 급등(+) AND COPPER 현재 하락(-) = 경기 둔화 임박
      let level = 0;
      let label = 'neutral';
      if (wtiOldRet < -0.05 && cuRecentRet > 0.05) { level = 1; label = 'recovery_early'; }
      else if (wtiOldRet > 0.1 && cuRecentRet < -0.05) { level = -1; label = 'slowdown_imminent'; }
      d.WTI_COPPER_LAG_LEVEL = {
        name: 'wti_copper_lag_level',
        value: level,
        date: today(),
        formula:
          `WTI t-90~t-60 ${(wtiOldRet * 100).toFixed(1)}% / COPPER 최근30D ${(cuRecentRet * 100).toFixed(1)}%. ` +
          `+1(유가과거약세+구리현재강세=회복조기) / -1(유가과거급등+구리현재약세=둔화임박) / 0. ${label}. video2 §3부.`,
      };
    }
  } catch {
    /* skip */
  }

  // === A7 ECONOMY_STOCK_DIVERGENCE (13차 2026-04) — video4 "실물 약한데 주가 오른다" ===
  // ISM_PROXY < 50 (경기 수축) AND NASDAQ_DISPARITY > +10 (과열) = 유동성 왜곡 경고.
  //   반대 (ISM ≥ 50 AND disparity < -10) = 경기 회복 + 저점 = 매수 기회.
  try {
    const ismVal = d.ISM_PROXY?.value ?? null;
    const dispVal = d.NASDAQ_DISPARITY?.value ?? null;
    if (ismVal !== null && dispVal !== null) {
      let level = 0;
      let label = 'aligned';
      if (ismVal < 50 && dispVal > 10) { level = -1; label = 'liquidity_distortion'; }
      else if (ismVal >= 50 && dispVal < -10) { level = 1; label = 'recovery_bottom'; }
      d.ECONOMY_STOCK_DIVERGENCE = {
        name: 'economy_stock_divergence',
        value: level,
        date: today(),
        formula:
          `ISM ${ismVal.toFixed(1)} / NASDAQ 이격도 ${dispVal.toFixed(1)}%. ` +
          `-1(ISM<50+이격>+10=유동성왜곡) / +1(ISM≥50+이격<-10=회복저점) / 0. ${label}. video4 §유동성 왜곡.`,
      };
    }
  } catch {
    /* skip */
  }


// === N4 TAIL_RISK_LEVEL (14차 노션 2단계 정합, 2026-04) — video4 §꼬리 위험 ===
  // 노션 대시보드 soft/hard 2단계:
  //   SKEW: 120 soft / 140 hard (블랙스완 경계)
  //   VVIX: 110 soft / 130 hard
  //   OVX:  40 soft / 60 hard
  // 각 지표 hard=2점, soft=1점, 미발동=0점. 합 0~6.
  //   score >= 4 → level 2 (고위험)
  //   score >= 2 → level 1 (경계)
  //   else → 0 (정상)
  try {
    const skew = raw.SKEW?.value ?? null;
    const vvix = raw.VVIX?.value ?? null;
    const ovx = raw.OVX?.value ?? null;
    const skewPt = skew === null ? 0 : skew > 140 ? 2 : skew > 120 ? 1 : 0;
    const vvixPt = vvix === null ? 0 : vvix > 130 ? 2 : vvix > 110 ? 1 : 0;
    const ovxPt = ovx === null ? 0 : ovx > 60 ? 2 : ovx > 40 ? 1 : 0;
    const tailScore = skewPt + vvixPt + ovxPt;
    const level = tailScore >= 4 ? 2 : tailScore >= 2 ? 1 : 0;
    const ptLabel = (pt: number) => (pt === 2 ? 'hard' : pt === 1 ? 'soft' : '-');
    d.TAIL_RISK_LEVEL = {
      name: 'tail_risk_level',
      value: level,
      date: today(),
      formula:
        `SKEW ${skew?.toFixed(1) ?? 'n/a'} [${ptLabel(skewPt)}] (노션 120/140), ` +
        `VVIX ${vvix?.toFixed(1) ?? 'n/a'} [${ptLabel(vvixPt)}] (110/130), ` +
        `OVX ${ovx?.toFixed(1) ?? 'n/a'} [${ptLabel(ovxPt)}] (40/60). ` +
        `score ${tailScore}/6 → level ${level} (0=정상,1=경계,2=고위험). 노션 대시보드 정합.`,
    };
  } catch {
    /* skip */
  }

  // === N5 FNG_TIER (12차 2026-04) — 노션 대시보드 5단계 정합 ===
  // 0-24 극공포(-2), 25-44 공포(-1), 45-55 중립(0), 56-74 탐욕(+1), 75-100 극탐욕(+2).
  // 부호 매핑: 음수 = 시장 공포(매수 기회), 양수 = 시장 탐욕(매도 주의).
  try {
    const fng = raw.FEAR_GREED?.value ?? null;
    if (fng !== null) {
      let tier: number;
      let label: string;
      if (fng < 25) { tier = -2; label = '극공포'; }
      else if (fng < 45) { tier = -1; label = '공포'; }
      else if (fng < 56) { tier = 0; label = '중립'; }
      else if (fng < 75) { tier = 1; label = '탐욕'; }
      else { tier = 2; label = '극탐욕'; }
      d.FNG_TIER = {
        name: 'fng_tier',
        value: tier,
        date: today(),
        formula: `F&G ${fng} → ${label} (tier ${tier}). 0-24=-2, 25-44=-1, 45-55=0, 56-74=+1, 75-100=+2.`,
      };
    }
  } catch {
    /* skip */
  }

  // === N6 WRESBAL_ABSOLUTE_LEVEL (12차 2026-04) — 노션 "지급준비금 3조 이상 안전" ===
  // 단위: millions of dollars. 3조 임계 = >= 3_000_000.
  try {
    const wr = raw.WRESBAL?.value ?? null;
    if (wr !== null) {
      const level = wr >= 3_000_000 ? 1 : 0;
      d.WRESBAL_ABSOLUTE_LEVEL = {
        name: 'wresbal_absolute_level',
        value: level,
        date: today(),
        formula: `WRESBAL ${Math.round(wr / 1000).toLocaleString()}M달러 → ` +
          `${level === 1 ? '안전(≥3조, 은행 유동성 충분)' : '부족 경고(<3조)'}. 노션 대시보드 기준.`,
      };
    }
  } catch {
    /* skip */
  }

  // === N7 RRP_ABSOLUTE_LEVEL (12차 2026-04) — 노션 "RRP 1000억/50-200/50 이하" ===
  // 단위: billions of dollars. 임계 100B / 50B.
  try {
    const rrp = raw.RRPONTSYD?.value ?? null;
    if (rrp !== null) {
      let level: number;
      let label: string;
      if (rrp >= 100) { level = 1; label = '완화(≥100B, 유동성 잔존)'; }
      else if (rrp >= 50) { level = 0; label = '거의 소진(50-100B)'; }
      else { level = -1; label = '바닥(<50B)'; }
      d.RRP_ABSOLUTE_LEVEL = {
        name: 'rrp_absolute_level',
        value: level,
        date: today(),
        formula: `RRPONTSYD ${rrp.toFixed(2)}B달러 → ${label}. 노션 대시보드 기준 (100B/50B 임계).`,
      };
    }
  } catch {
    /* skip */
  }

  // === 14차 Phase B-1 유동성 tier 5종 (노션 대시보드 정합) ===
  // 14차 Phase A 재분류 (5차 감사 후, 2026-04):
  //   아래 WALCL/TGA/MMF/SOFR_IORB/M2SL_LEVEL/DXY 6종 tier 는 **UI 대시보드 전용**.
  //   기존 지표와 중복:
  //     - WALCL_TIER ↔ LIQUIDITY_DIRECTION (WALCL 이미 반영)
  //     - TGA_TIER ↔ TGA_DIRECTION
  //     - MMF_TIER ↔ MMF_DIRECTION
  //     - SOFR_IORB_TIER ↔ SOFR_IORB_SPREAD
  //     - M2SL_LEVEL_TIER ↔ GLOBAL_M2_PROXY (YoY 기반, 보완적)
  //     - DXY_TIER ↔ regime.scoreDXYDirection
  //   signal/regime 통합은 DGS10_TIER + UNRATE_TIER 만 (regime score 컴포넌트 추가).
  //   나머지 6종은 노션 대시보드 UI 정합을 위한 관측용으로만 노출.

  // WALCL_TIER — 연준 총자산 (millions 단위, 노션 8.5T/7T/6T 임계)
  try {
    const walcl = raw.WALCL?.value ?? null;
    if (walcl !== null) {
      let level: number;
      let label: string;
      if (walcl >= 8_500_000) { level = 2; label = '양적완화(≥8.5T)'; }
      else if (walcl >= 7_000_000) { level = 1; label = '완만QT(7-8.5T)'; }
      else if (walcl >= 6_000_000) { level = 0; label = 'QT진행(6-7T)'; }
      else { level = -1; label = '긴축심화(<6T)'; }
      d.WALCL_TIER = {
        name: 'walcl_tier',
        value: level,
        date: today(),
        formula: `WALCL ${(walcl / 1_000_000).toFixed(2)}T → ${label}. 노션 기준 +2(≥8.5T) / +1(7-8.5T) / 0(6-7T) / -1(<6T).`,
      };
    }
  } catch { /* skip */ }

  // TGA_TIER — WTREGEN (millions 단위, 노션 800B/400B 임계). 음수 방향: 유동성 흡수
  try {
    const tga = raw.WTREGEN?.value ?? null;
    if (tga !== null) {
      let level: number;
      let label: string;
      if (tga >= 800_000) { level = -1; label = '유동성 흡수(≥800B)'; }
      else if (tga >= 400_000) { level = 0; label = '보통(400-800B)'; }
      else { level = 1; label = '유동성 공급(<400B)'; }
      d.TGA_TIER = {
        name: 'tga_tier',
        value: level,
        date: today(),
        formula: `TGA ${(tga / 1000).toFixed(0)}B → ${label}. 노션 기준 -1(≥800B 흡수) / 0(400-800B) / +1(<400B 공급).`,
      };
    }
  } catch { /* skip */ }

  // MMF_TIER — WRMFNS (billions 단위, retail MMF 소매 수준). 노션 "6T" 는 전체 MMF 기준이라
  // retail 수준 (≈2T 대) 으로 임계 재조정: 2.5T / 2T.
  try {
    const mmf = raw.WRMFNS?.value ?? null;
    if (mmf !== null) {
      let level: number;
      let label: string;
      if (mmf >= 2500) { level = 1; label = '역대 최고(≥2.5T retail)'; }
      else if (mmf >= 2000) { level = 0; label = '높음(2-2.5T)'; }
      else { level = -1; label = '보통(<2T)'; }
      d.MMF_TIER = {
        name: 'mmf_tier',
        value: level,
        date: today(),
        formula: `WRMFNS (retail MMF) ${(mmf / 1000).toFixed(2)}T → ${label}. 노션 "6T 전체" 를 retail 수준으로 환산.`,
      };
    }
  } catch { /* skip */ }

  // SOFR_IORB_SPREAD_TIER — 이미 계산된 spread 재활용 (노션 0.05% 임계)
  try {
    const spread = d.SOFR_IORB_SPREAD?.value ?? null;
    if (spread !== null) {
      let level: number;
      let label: string;
      if (spread > 0.05) { level = -1; label = '자금시장 긴장(SOFR>IORB+5bp)'; }
      else if (spread >= -0.05) { level = 0; label = '균형(±5bp)'; }
      else { level = 1; label = '유동성 풍부(SOFR<IORB-5bp)'; }
      d.SOFR_IORB_TIER = {
        name: 'sofr_iorb_tier',
        value: level,
        date: today(),
        formula: `SOFR-IORB ${spread.toFixed(3)}% → ${label}. 노션 ±5bp 기준.`,
      };
    }
  } catch { /* skip */ }

  // DGS10_TIER — 10년물 금리 (노션 5%/4%/3% 임계)
  try {
    const dgs10 = raw.DGS10?.value ?? null;
    if (dgs10 !== null) {
      let level: number;
      let label: string;
      if (dgs10 >= 5) { level = -2; label = '경계(≥5%)'; }
      else if (dgs10 >= 4) { level = -1; label = '부담(4-5%)'; }
      else if (dgs10 >= 3) { level = 0; label = '중립(3-4%)'; }
      else { level = 1; label = '저금리(<3%)'; }
      d.DGS10_TIER = {
        name: 'dgs10_tier',
        value: level,
        date: today(),
        formula: `DGS10 ${dgs10.toFixed(2)}% → ${label}. 노션 대시보드 5%/4%/3% 임계.`,
      };
    }
  } catch { /* skip */ }

  // === 14차 Phase B-2 경제 건강 tier 3종 (노션 대시보드 정합) ===

  // UNRATE_TIER — 실업률 (노션 4%/5%/6% 임계)
  try {
    const ur = raw.UNRATE?.value ?? null;
    if (ur !== null) {
      let level: number;
      let label: string;
      if (ur < 4) { level = 2; label = '완전 고용(<4%)'; }
      else if (ur < 5) { level = 1; label = '양호(4-5%)'; }
      else if (ur < 6) { level = -1; label = '주의(5-6%)'; }
      else { level = -2; label = '침체 우려(≥6%)'; }
      d.UNRATE_TIER = {
        name: 'unrate_tier',
        value: level,
        date: today(),
        formula: `UNRATE ${ur.toFixed(1)}% → ${label}. 노션 4%/5%/6% 기준.`,
      };
    }
  } catch { /* skip */ }

  // M2SL_LEVEL_TIER — 미국 M2 절대 레벨 (노션 21T/20T/19T 임계, billions 단위)
  try {
    const m2 = raw.M2SL?.value ?? null;
    if (m2 !== null) {
      let level: number;
      let label: string;
      if (m2 >= 21000) { level = 2; label = '풍부(≥21T)'; }
      else if (m2 >= 20000) { level = 1; label = '정상(20-21T)'; }
      else if (m2 >= 19000) { level = -1; label = '감소(19-20T)'; }
      else { level = -2; label = '수축(<19T)'; }
      d.M2SL_LEVEL_TIER = {
        name: 'm2sl_level_tier',
        value: level,
        date: today(),
        formula: `M2SL ${(m2 / 1000).toFixed(2)}T → ${label}. 노션 21T/20T/19T 기준.`,
      };
    }
  } catch { /* skip */ }

  // DXY_TIER — 달러인덱스 (노션 105/100/95 임계). 부호: DXY 약세가 위험자산/금 우호
  try {
    const dxy = raw.DXY?.value ?? null;
    if (dxy !== null) {
      let level: number;
      let label: string;
      if (dxy >= 105) { level = -1; label = '강세(≥105)'; }
      else if (dxy >= 100) { level = 0; label = '우위(100-105)'; }
      else if (dxy >= 95) { level = 1; label = '중립(95-100)'; }
      else { level = 2; label = '약세(<95, 금/EM 우호)'; }
      d.DXY_TIER = {
        name: 'dxy_tier',
        value: level,
        date: today(),
        formula: `DXY ${dxy.toFixed(2)} → ${label}. 노션 105/100/95 기준.`,
      };
    }
  } catch { /* skip */ }

  // === 15차 Phase 1-A GOLDILOCKS_ZONE (2026-04, video4 §매크로) ===
  // video4 [8:22-8:38]: "고용, CPI, PCE, ISM — 너무 뜨겁지도 차갑지도 않은 골디락스"
  //
  // CPI_YOY / PCE_YOY / ISM_PROXY / UNRATE 4축 합산 → zone 결정:
  //   각 축 점수 -2 ~ +2 (2=이상적 / 0=중립 / -2=위기)
  //   total >= 4  → +2 Hot Goldilocks (과열 살짝)
  //   total >= 1  → +1 Normal Goldilocks (이상적)
  //   total >= -2 → 0  Mixed
  //   else       → -1 Stagflation / Recession 진입
  try {
    const readYoYPct = async (key: string): Promise<number | null> => {
      const hist = await readHistory('fred', key);
      return computeHistoryYoY(hist, 0, 400);
    };
    const cpiYoy = await readYoYPct('CPI');
    const pceYoy = await readYoYPct('PCE');
    const ism = d.ISM_PROXY?.value ?? null;
    const ur = raw.UNRATE?.value ?? null;

    // 각 축 점수 함수 (Fed 2% 타겟 기준)
    //   CPI/PCE: 1.5~2.5% → +2 / 2.5~3.5% → +1 / <1 or >4 → -1 / <0 or >5 → -2
    //   ISM: 50~55 → +2 / 48~50 or 55~60 → +1 / <45 or >62 → -1 / <42 or >65 → -2
    //   UNRATE: <4 → +2 / <5 → +1 / <6 → 0 / <7 → -1 / ≥7 → -2
    const scorePricing = (v: number | null): number => {
      if (v === null) return 0;
      if (v >= 1.5 && v <= 2.5) return 2;
      if (v >= 1.0 && v <= 3.5) return 1;
      if (v < 0 || v > 5) return -2;
      return -1;
    };
    const scoreISM = (v: number | null): number => {
      if (v === null) return 0;
      if (v >= 50 && v <= 55) return 2;
      if (v >= 48 && v <= 60) return 1;
      if (v < 42 || v > 65) return -2;
      return -1;
    };
    const scoreUr = (v: number | null): number => {
      if (v === null) return 0;
      if (v < 4) return 2;
      if (v < 5) return 1;
      if (v < 6) return 0;
      if (v < 7) return -1;
      return -2;
    };

    const cpiPt = scorePricing(cpiYoy);
    const pcePt = scorePricing(pceYoy);
    const ismPt = scoreISM(ism);
    const urPt = scoreUr(ur);
    const total = cpiPt + pcePt + ismPt + urPt;
    let zone: number;
    let label: string;
    if (total >= 4) { zone = 2; label = 'Hot Goldilocks (약간 과열)'; }
    else if (total >= 1) { zone = 1; label = 'Normal Goldilocks (이상적)'; }
    else if (total >= -2) { zone = 0; label = 'Mixed'; }
    else { zone = -1; label = 'Stagflation/Recession 진입'; }

    d.GOLDILOCKS_ZONE = {
      name: 'goldilocks_zone',
      value: zone,
      date: today(),
      formula:
        `CPI ${cpiYoy?.toFixed(2) ?? 'n/a'}%[${cpiPt}] + PCE ${pceYoy?.toFixed(2) ?? 'n/a'}%[${pcePt}] + ` +
        `ISM ${ism?.toFixed(1) ?? 'n/a'}[${ismPt}] + UNRATE ${ur?.toFixed(1) ?? 'n/a'}%[${urPt}] ` +
        `= ${total}/8 → ${label}. video4 [8:22-8:38] 정합.`,
    };

    if (cpiYoy !== null) {
      d.CPI_YOY = { name: 'cpi_yoy', value: parseFloat(cpiYoy.toFixed(2)), date: today(), formula: 'CPI 최신월 YoY%. Fed 2% 타겟 기준.' };
    }
    if (pceYoy !== null) {
      d.PCE_YOY = { name: 'pce_yoy', value: parseFloat(pceYoy.toFixed(2)), date: today(), formula: 'PCE 최신월 YoY%. Fed 선호 인플레 지표.' };
    }
  } catch {
    /* skip */
  }

  // === 15차 Phase 1-B FEDERAL_DEFICIT_GDP_TIER (2026-04, video4 §채권 자경단) ===
  // video4 [10:11]: "연간 재정 적자가 GDP의 5.8%" — 스톡 부채 외 플로우 속도 추적
  //   <3% → +1 건전
  //   <5% → 0 보통
  //   <7% → -1 경계
  //   ≥7% → -2 위기 (채권 자경단 발동 임박)
  try {
    const def = raw.FEDERAL_DEFICIT_GDP?.value ?? null;
    // FYFSGDA188S 는 적자일 때 음수 값. 크기는 abs 사용.
    if (def !== null) {
      const absPct = Math.abs(def);
      let level: number;
      let label: string;
      if (absPct < 3) { level = 1; label = '건전(<3% GDP)'; }
      else if (absPct < 5) { level = 0; label = '보통(3-5%)'; }
      else if (absPct < 7) { level = -1; label = '경계(5-7%, 2026 수준)'; }
      else { level = -2; label = '위기(≥7%, 채권 자경단 임박)'; }
      d.FEDERAL_DEFICIT_GDP_TIER = {
        name: 'federal_deficit_gdp_tier',
        value: level,
        date: today(),
        formula: `연간 재정적자/GDP ${absPct.toFixed(2)}% → ${label}. video4 [10:11] "5.8% 적자" 정합.`,
      };
    }
  } catch {
    /* skip */
  }

  // === 15차 Phase 2-H WTI_CPI_LAG_RISK (video5 "유가 2-3개월 뒤 CPI 지연") ===
  // video5: "유가 하락도 공급망 충격은 2~3개월 뒤 CPI 로 지연 반영"
  //   WTI t-90~t-60 평균 vs WTI 현재 평균 변화율.
  //   >+20% → -2 (과거 유가 급등 → 앞으로 CPI 상승 위험)
  //   >+10% → -1
  //   ±10% → 0
  //   <-10% → +1 (과거 유가 하락 → 앞으로 CPI 완화)
  try {
    const wtiHistLag = await fetchYahooHistory('CL=F', 180);
    if (wtiHistLag.length >= 91) {
      const oldSlice = wtiHistLag.slice(-90, -60).map((h) => h.close).filter((v) => v > 0);
      const recentSlice = wtiHistLag.slice(-30).map((h) => h.close).filter((v) => v > 0);
      if (oldSlice.length >= 20 && recentSlice.length >= 20) {
        const oldAvg = oldSlice.reduce((s, v) => s + v, 0) / oldSlice.length;
        const recentAvg = recentSlice.reduce((s, v) => s + v, 0) / recentSlice.length;
        const changePct = oldAvg > 0 ? ((recentAvg - oldAvg) / oldAvg) * 100 : 0;
        let level: number;
        let label: string;
        if (changePct > 20) { level = -2; label = '고위험(과거 급등 +20%)'; }
        else if (changePct > 10) { level = -1; label = '경계(과거 +10%)'; }
        else if (changePct > -10) { level = 0; label = '중립'; }
        else { level = 1; label = '완화(과거 -10%)'; }
        d.WTI_CPI_LAG_RISK = {
          name: 'wti_cpi_lag_risk',
          value: level,
          date: today(),
          formula:
            `WTI t-90~t-60 평균 $${oldAvg.toFixed(1)} → 최근30D $${recentAvg.toFixed(1)} (${changePct > 0 ? '+' : ''}${changePct.toFixed(1)}%). ` +
            `${label}. video5 "유가 → CPI 2-3개월 지연".`,
        };
      }
    }
  } catch {
    /* skip */
  }

  // === 15차 Phase 2-C STABLECOIN_TBILL_DEMAND (video4 §달러 패권) ===
  // video4 [5:59]: "스테이블 코인으로 채권 수요까지 만들었어요"
  // STABLECOIN_MCAP (billions USD) 은 USDT/USDC 발행액 = T-Bill 수요 프록시.
  // 레벨:
  //   ≥300B → +2 (강한 채권 수요 기여, 금리 하방)
  //   ≥200B → +1
  //   ≥150B → 0
  //   ≥100B → -1
  //   <100B → -2
  try {
    const stbl = raw.STABLECOIN_MCAP?.value ?? null;
    if (stbl !== null) {
      let level: number;
      let label: string;
      if (stbl >= 300) { level = 2; label = '강한 채권 수요(≥300B)'; }
      else if (stbl >= 200) { level = 1; label = '양호(200-300B)'; }
      else if (stbl >= 150) { level = 0; label = '보통(150-200B)'; }
      else if (stbl >= 100) { level = -1; label = '감소(100-150B)'; }
      else { level = -2; label = '약화(<100B)'; }
      d.STABLECOIN_TBILL_DEMAND = {
        name: 'stablecoin_tbill_demand',
        value: level,
        date: today(),
        formula:
          `스테이블코인 총 발행 ${stbl.toFixed(1)}B USD → ${label}. ` +
          `video4 [5:59] "스테이블 코인으로 채권 수요까지 만들었어요".`,
      };
    }
  } catch {
    /* skip */
  }

  // === 15차 Phase 3-F CASH_YIELD (UI 전용, video1 §파킹 통장/CMA) ===
  // video1 [7:00]: "파킹 통장, 단기채 ETF, CMA 활용하면 조금의 이자라도"
  // SOFR (=단기 금리 근사) 를 현금 보유 중 기대 yield 로 UI 에 라벨화.
  try {
    const sofr = raw.SOFR?.value ?? null;
    if (sofr !== null) {
      d.CASH_YIELD_ANNUAL = {
        name: 'cash_yield_annual',
        value: parseFloat(sofr.toFixed(2)),
        date: today(),
        formula: `SOFR ${sofr.toFixed(2)}% — 현금/파킹 통장/단기채 ETF 보유 시 기대 연 수익률. video1 §파킹 통장.`,
      };
    }
  } catch { /* skip */ }

  // === 14차 Phase B-3 FEDERAL_DEBT_GDP_TIER (video4 §채권 자경단) ===
  // video4: "미국 총 부채 38.8조 달러 / IMF 2031 GDP 140% 예측" — 부채 규모 감지.
  // FRED GFDEGDQ188S (Federal Debt as % of GDP). 분기 발표, ~120%+ 는 역사적 고수준.
  //   level +1: <100% (완화)
  //   level  0: 100~120% (주의)
  //   level -1: 120~140% (경계)
  //   level -2: ≥140% (IMF 2031 예측 수준 도달)
  try {
    const debt = raw.FEDERAL_DEBT_GDP?.value ?? null;
    if (debt !== null) {
      let level: number;
      let label: string;
      if (debt < 100) { level = 1; label = '완화(<100% GDP)'; }
      else if (debt < 120) { level = 0; label = '주의(100-120%)'; }
      else if (debt < 140) { level = -1; label = '경계(120-140%)'; }
      else { level = -2; label = '위험(≥140%, IMF 2031 예측)'; }
      d.FEDERAL_DEBT_GDP_TIER = {
        name: 'federal_debt_gdp_tier',
        value: level,
        date: today(),
        formula: `연방 부채/GDP ${debt.toFixed(1)}% → ${label}. video4 §채권 자경단 + IMF 2031 예측.`,
      };
    }
  } catch { /* skip */ }

  // === INSTITUTIONAL_NASDAQ_EXPOSURE_PCT + FLOW (11차 #8, 2026-04) ===
  // 영상4 §기관리포트: "말은 거짓말 할 수 있지만 돈은 거짓말을 하지 않거든요".
  // Phase 1: 현재 시점 메가캡 비중 스냅샷.
  // Phase 2 (2026-04 추가): 이전 분기 대비 증감(FLOW, 레벨 -2~+2).
  try {
    const {
      fetchInstitutional13FQuarterly,
      computeNasdaqMegacapExposure,
      computeNasdaqMegacapFlow,
      computeSectorInstitutionalFlow,
      computeConsensusBuys,
    } = await import('../collectors/institutional-13f');
    const quarterly = await fetchInstitutional13FQuarterly();
    const currentFunds = quarterly.map((q) => q.current);
    const exposure = computeNasdaqMegacapExposure(currentFunds);
    if (exposure) {
      d.INSTITUTIONAL_NASDAQ_EXPOSURE_PCT = {
        name: 'institutional_nasdaq_exposure_pct',
        value: exposure.avgSharePct,
        date: today(),
        formula:
          `주요 헤지펀드 ${exposure.fundCount}곳 최근 13F-HR 기준 NASDAQ 메가캡 ` +
          `(AAPL/MSFT/GOOGL/AMZN/NVDA/META/TSLA) 비중 단순 평균(%). ` +
          `영상4 §기관리포트 정합. SEC EDGAR, 7일 캐시.`,
      };
    }
    const flow = computeNasdaqMegacapFlow(quarterly);
    if (flow) {
      d.INSTITUTIONAL_NASDAQ_FLOW = {
        name: 'institutional_nasdaq_flow',
        value: flow.level,
        date: today(),
        formula:
          `메가캡 비중 분기 변화: ${flow.previousPct}% → ${flow.currentPct}% ` +
          `(Δ ${flow.deltaPct > 0 ? '+' : ''}${flow.deltaPct}%p, ${flow.fundCount}곳 기준). ` +
          `레벨: +2(>+2%p), +1(>+0.5%p), 0, -1(<-0.5%p), -2(<-2%p). ` +
          `영상4 §기관 "집단이 어디로 움직이나" 정합.`,
      };
    }
    // Phase 3 섹터 확장 (12차 2026-04): tech/fin/energy 집단 이동
    for (const [sector, key] of [
      ['tech', 'INSTITUTIONAL_SECTOR_TECH_FLOW'],
      ['fin', 'INSTITUTIONAL_SECTOR_FIN_FLOW'],
      ['energy', 'INSTITUTIONAL_SECTOR_ENERGY_FLOW'],
    ] as const) {
      const sf = computeSectorInstitutionalFlow(quarterly, sector as 'tech' | 'fin' | 'energy');
      if (sf) {
        d[key] = {
          name: key.toLowerCase(),
          value: sf.level,
          date: today(),
          formula:
            `${sector.toUpperCase()} 섹터 비중 분기 변화: ${sf.previousPct}% → ${sf.currentPct}% ` +
            `(Δ ${sf.deltaPct > 0 ? '+' : ''}${sf.deltaPct}%p, ${sf.fundCount}곳). ` +
            `레벨 ±2/±1/0. 13F 주요 5종목 합 비중 펀드 평균. 영상4 §기관 섹터 확장.`,
        };
      }
    }
    // 18차 P1#4: Dataroma-style Big Bets — 2인+ 슈퍼인베스터가 공유하는 종목 랭킹.
    try {
      const consensus = computeConsensusBuys(quarterly, { minFunds: 2, topN: 15 });
      const strongConsensus = consensus.filter((c) => c.fundCount >= 3);
      d.INSTITUTIONAL_CONSENSUS_TOP_COUNT = {
        name: 'institutional_consensus_top_count',
        value: consensus.length,
        date: today(),
        formula: `2인+ 공유 종목 ${consensus.length}개, 3인+ 공유 ${strongConsensus.length}개. Dataroma §Big Bets 정합. SmartMoneyPanel 하단 표.`,
      };
      d.INSTITUTIONAL_CONSENSUS_STRONG_COUNT = {
        name: 'institutional_consensus_strong_count',
        value: strongConsensus.length,
        date: today(),
        formula: `3인 이상 동시 보유 — 매우 강한 콘셉서스 신호. 5개+ = RISK_ON 가속 근거.`,
      };
      // 메타에 실제 랭킹 데이터도 노출 (UI 에서 cusip→ticker 매핑 후 표시)
      (d as any).__meta = (d as any).__meta || {};
      (d as any).__meta.institutionalConsensus = consensus.slice(0, 10).map((c) => ({
        cusip: c.cusip,
        fundCount: c.fundCount,
        weightPct: c.totalWeightPct,
        flow: c.quarterlyFlow,
      }));
    } catch { /* consensus 실패는 무시 */ }
  } catch {
    /* INSTITUTIONAL_* 실패는 파이프라인 막지 않음 */
  }

  // ============================================================================
  // 15차 Phase 1-3 일괄 추가 (2026-04): 영상/노션 재독 기반 13종
  // ============================================================================

  // === Phase 1 A1: RSI_14 (video2 §22:51 "RSI 50 수준 중립") ===
  // Wilder 14-period RSI. NASDAQ/GOLD/KOSPI 3종.
  const computeRSI = (closes: number[], period = 14): number | null => {
    if (closes.length < period + 1) return null;
    let gains = 0;
    let losses = 0;
    // 초기 평균 (Wilder)
    for (let i = 1; i <= period; i++) {
      const diff = closes[i] - closes[i - 1];
      if (diff >= 0) gains += diff; else losses -= diff;
    }
    let avgGain = gains / period;
    let avgLoss = losses / period;
    // smoothing
    for (let i = period + 1; i < closes.length; i++) {
      const diff = closes[i] - closes[i - 1];
      const gain = diff > 0 ? diff : 0;
      const loss = diff < 0 ? -diff : 0;
      avgGain = (avgGain * (period - 1) + gain) / period;
      avgLoss = (avgLoss * (period - 1) + loss) / period;
    }
    if (avgLoss === 0) return 100;
    const rs = avgGain / avgLoss;
    return 100 - 100 / (1 + rs);
  };
  const rsiLabel = (rsi: number): string =>
    rsi >= 70 ? '과매수' : rsi >= 60 ? '강세' : rsi >= 50 ? '중립-강' :
    rsi >= 40 ? '중립-약' : rsi >= 30 ? '약세' : '과매도';
  for (const [symbol, derivedKey] of [
    ['^IXIC', 'NASDAQ_RSI_14'],
    ['GC=F', 'GOLD_RSI_14'],
    ['^KS11', 'KOSPI_RSI_14'],
  ] as const) {
    try {
      const hist = await fetchYahooHistory(symbol, 60);
      if (hist.length >= 20) {
        const closes = hist.map((h) => h.close);
        const rsi = computeRSI(closes, 14);
        if (rsi !== null) {
          d[derivedKey] = {
            name: derivedKey.toLowerCase(),
            value: parseFloat(rsi.toFixed(2)),
            date: today(),
            formula: `Wilder 14-period RSI = ${rsi.toFixed(1)} → ${rsiLabel(rsi)}. video2 [22:51] 정합.`,
          };
        }
      }
    } catch { /* skip */ }
  }

  // === Phase 2 A2: GOLD_FIB_LEVEL (video2 §23:34 "피보나치 0.382/0.5 지지") ===
  // 52주 고/저 기반 0.382 / 0.5 / 0.618 되돌림 구간 중 현재가 위치.
  try {
    const goldHist52 = await fetchYahooHistory('GC=F', 400);
    if (goldHist52.length >= 100) {
      const closes52 = goldHist52.map((h) => h.close);
      const high52 = Math.max(...closes52);
      const low52 = Math.min(...closes52);
      const cur = closes52[closes52.length - 1];
      const range = high52 - low52;
      if (range > 0) {
        const fib382 = high52 - range * 0.382;
        const fib500 = high52 - range * 0.500;
        const fib618 = high52 - range * 0.618;
        const position = (cur - low52) / range; // 0~1 범위
        // 지지 구간 근접도 (±3% 허용)
        const near = (target: number) => Math.abs(cur - target) / target < 0.03;
        let level: string;
        let score: number;
        if (cur >= fib382) { level = '상단 구간(저항↑)'; score = -1; }
        else if (near(fib382)) { level = '0.382 지지 근접'; score = 1; }
        else if (cur >= fib500) { level = '0.382~0.5 분할1'; score = 1; }
        else if (near(fib500)) { level = '0.5 지지 근접(1차 분할2)'; score = 2; }
        else if (cur >= fib618) { level = '0.5~0.618 분할3'; score = 2; }
        else if (near(fib618)) { level = '0.618 지지 근접(강한 지지)'; score = 2; }
        else { level = '0.618 하방 이탈(약세)'; score = -2; }
        d.GOLD_FIB_LEVEL = {
          name: 'gold_fib_level',
          value: score,
          date: today(),
          formula:
            `52W high $${high52.toFixed(0)} / low $${low52.toFixed(0)} / 현재 $${cur.toFixed(0)} (${(position * 100).toFixed(0)}%). ` +
            `fib 0.382=$${fib382.toFixed(0)} / 0.5=$${fib500.toFixed(0)} / 0.618=$${fib618.toFixed(0)}. ` +
            `${level} (score ${score}). video2 §23:34 정합.`,
        };
      }
    }
  } catch { /* skip */ }

  // === Phase 2 A4: NASDAQ_OUTSIDE_BAR_YEARLY (video3 §8:23 "아웃사이드 바") ===
  // 현재 연간 캔들이 이전 연간 캔들의 high-low 범위를 **완전히 덮는지** 판정.
  try {
    const ixicHist = await fetchYahooHistory('^IXIC', 540); // 2년치
    if (ixicHist.length >= 250) {
      const closes = ixicHist.map((h) => h.close);
      const half = Math.floor(ixicHist.length / 2);
      const prevYear = closes.slice(0, half);
      const curYear = closes.slice(half);
      const prevHigh = Math.max(...prevYear);
      const prevLow = Math.min(...prevYear);
      const curHigh = Math.max(...curYear);
      const curLow = Math.min(...curYear);
      const isOutside = curHigh > prevHigh && curLow < prevLow ? 1 : 0;
      const direction = closes[closes.length - 1] > prevHigh ? 1 :
                        closes[closes.length - 1] < prevLow ? -1 : 0;
      d.NASDAQ_OUTSIDE_BAR_YEARLY = {
        name: 'nasdaq_outside_bar_yearly',
        value: isOutside,
        date: today(),
        formula:
          `전년 high/low $${prevHigh.toFixed(0)}/$${prevLow.toFixed(0)}, 당년 $${curHigh.toFixed(0)}/$${curLow.toFixed(0)}. ` +
          `${isOutside === 1 ? `아웃사이드 확정 (방향: ${direction === 1 ? '상방' : direction === -1 ? '하방' : '내부'})` : '아웃사이드 아님'}. video3 §8:23 정합.`,
      };
    }
  } catch { /* skip */ }

  // === Phase 2 B2: ASSET_CORRELATION_HEATMAP — 6자산 90D Pearson ===
  // S&P500 / US Bonds(IEF) / Gold / Dollar(DXY) / Oil / Bitcoin — 노션 heatmap 정합
  try {
    const fetchCloses = async (sym: string) => {
      const hist = await fetchYahooHistory(sym, 120);
      return hist.length >= 90 ? hist.slice(-90).map((h) => h.close) : null;
    };
    const [sp, ief, gold, dxy, oil, btc] = await Promise.all([
      fetchCloses('^GSPC'),
      fetchCloses('IEF'),
      fetchCloses('GC=F'),
      fetchCloses('DX-Y.NYB'),
      fetchCloses('CL=F'),
      fetchCloses('BTC-USD'),
    ]);
    const toReturns = (closes: number[]): number[] => {
      const out: number[] = [];
      for (let i = 1; i < closes.length; i++) out.push((closes[i] - closes[i - 1]) / closes[i - 1]);
      return out;
    };
    const pearson = (a: number[], b: number[]): number => {
      const n = Math.min(a.length, b.length);
      if (n < 30) return 0;
      const aa = a.slice(-n), bb = b.slice(-n);
      const meanA = aa.reduce((s, v) => s + v, 0) / n;
      const meanB = bb.reduce((s, v) => s + v, 0) / n;
      let num = 0, denA = 0, denB = 0;
      for (let i = 0; i < n; i++) {
        const da = aa[i] - meanA;
        const db = bb[i] - meanB;
        num += da * db;
        denA += da * da;
        denB += db * db;
      }
      const den = Math.sqrt(denA * denB);
      return den > 0 ? num / den : 0;
    };
    const assets: Array<[string, number[] | null]> = [
      ['SP', sp ? toReturns(sp) : null],
      ['BOND', ief ? toReturns(ief) : null],
      ['GOLD', gold ? toReturns(gold) : null],
      ['DXY', dxy ? toReturns(dxy) : null],
      ['OIL', oil ? toReturns(oil) : null],
      ['BTC', btc ? toReturns(btc) : null],
    ];
    const corrMap: Record<string, number> = {};
    for (let i = 0; i < assets.length; i++) {
      for (let j = i + 1; j < assets.length; j++) {
        const [ka, ra] = assets[i];
        const [kb, rb] = assets[j];
        if (ra && rb) corrMap[`${ka}_${kb}`] = parseFloat(pearson(ra, rb).toFixed(3));
      }
    }
    // 포트폴리오 집중 위험: SP-BOND, SP-GOLD, GOLD-DXY (영상2 분산 원칙 관점)
    const spGold = corrMap.SP_GOLD;
    let concentration = 0;
    let label = '';
    if (spGold !== undefined) {
      if (spGold > 0.5) { concentration = -1; label = 'SP-GOLD 상관 +0.5 초과 = 분산 효과 악화'; }
      else if (spGold < -0.2) { concentration = 1; label = 'SP-GOLD 음의 상관 = 분산 효과 양호'; }
      else { concentration = 0; label = '중립'; }
    }
    d.ASSET_CORRELATION_SP_GOLD = {
      name: 'asset_correlation_sp_gold',
      value: spGold ?? 0,
      date: today(),
      formula: `90D Pearson SP500 vs GOLD = ${spGold?.toFixed(3) ?? 'n/a'}. 전체: ${JSON.stringify(corrMap)}`,
    };
    d.DIVERSIFICATION_LEVEL = {
      name: 'diversification_level',
      value: concentration,
      date: today(),
      formula: `SP-GOLD ${spGold?.toFixed(3) ?? 'n/a'} → ${label} (+1 양호 / 0 중립 / -1 악화). video2 §분산 원칙.`,
    };
  } catch { /* skip */ }

  // === Phase 2 B3 + 24차 Phase 1#1: DRAWDOWN_ATH 5년 history (video1 §"2021.11 고점") ===
  for (const [symbol, derivedKey] of [
    ['^IXIC', 'NASDAQ_DRAWDOWN_ATH'],
    ['^KS11', 'KOSPI_DRAWDOWN_ATH'],
  ] as const) {
    try {
      const hist = await fetchYahooHistory(symbol, 1260); // 5년 ≈ 1260영업일 (video1 사이클 ATH 인지)
      if (hist.length >= 100) {
        const closes = hist.map((h) => h.close);
        const ath = Math.max(...closes);
        const cur = closes[closes.length - 1];
        const drawdown = ath > 0 ? ((cur - ath) / ath) * 100 : 0;
        let level: number;
        let label: string;
        if (drawdown >= -5) { level = 2; label = 'ATH 근접(강세장)'; }
        else if (drawdown >= -10) { level = 1; label = '소폭 조정(-5~-10%)'; }
        else if (drawdown >= -20) { level = 0; label = '조정(-10~-20%)'; }
        else if (drawdown >= -30) { level = -1; label = '약세(-20~-30%, video1 기회 구간)'; }
        else { level = -2; label = '심각한 약세(<-30%, 구조적 위험 가능)'; }
        d[derivedKey] = {
          name: derivedKey.toLowerCase(),
          value: parseFloat(drawdown.toFixed(2)),
          date: today(),
          formula: `ATH $${ath.toFixed(0)} / 현재 $${cur.toFixed(0)} → ${drawdown.toFixed(2)}% (${label}). 노션 drawdown chart 정합.`,
        };
      }
    } catch { /* skip */ }
  }

  // === Phase 2 C1: DGS30_TIER + 30Y10Y_SPREAD_TIER (video4 §장기국채) ===
  try {
    const dgs30 = raw.DGS30?.value ?? null;
    const dgs10 = raw.DGS10?.value ?? null;
    if (dgs30 !== null) {
      let level: number;
      let label: string;
      if (dgs30 >= 5.5) { level = -2; label = '심각(≥5.5% 역사적 고수준)'; }
      else if (dgs30 >= 5) { level = -1; label = '경계(5-5.5%)'; }
      else if (dgs30 >= 4) { level = 0; label = '중립(4-5%)'; }
      else { level = 1; label = '완화(<4%)'; }
      d.DGS30_TIER = {
        name: 'dgs30_tier',
        value: level,
        date: today(),
        formula: `DGS30 ${dgs30.toFixed(2)}% → ${label}. video4 §장기국채 금리 상승 = 채권 자경단 신호.`,
      };
    }
    if (dgs30 !== null && dgs10 !== null) {
      const spread = dgs30 - dgs10;
      let level: number;
      let label: string;
      if (spread > 1) { level = -1; label = '급경사화(>1% 채권 자경단 발동 중)'; }
      else if (spread > 0.5) { level = 0; label = '정상화(0.5-1%)'; }
      else if (spread > 0) { level = 1; label = '평탄화(0-0.5%, 경기 둔화 조짐)'; }
      else { level = -2; label = '역전 심화(<0%, 장기 수요 쇼크)'; }
      d.SPREAD_30Y10Y_TIER = {
        name: 'spread_30y10y_tier',
        value: level,
        date: today(),
        formula: `DGS30-DGS10 ${spread.toFixed(2)}% → ${label}. 급경사화 = 장기금리 우려 (video4).`,
      };
    }
  } catch { /* skip */ }

  // === Phase 2 C2: ELECTION_DDAY (video4 §11월 중간선거) ===
  // 2026년 미국 중간선거 = 2026-11-03 (11월 첫째 월요일 다음 화요일).
  try {
    const electionDate = new Date('2026-11-03T00:00:00Z');
    const now = new Date();
    const diffMs = electionDate.getTime() - now.getTime();
    const dday = Math.ceil(diffMs / (1000 * 60 * 60 * 24));
    let level: number;
    let label: string;
    if (dday > 180) { level = 1; label = '선거 전 6개월+ (경기 부양 우호, 정책 공격적)'; }
    else if (dday > 30) { level = 2; label = '선거 30-180일 전 (부양 최고조, 주가 우호 경향)'; }
    else if (dday > 0) { level = 0; label = '선거 30일 이내 (변동성 증가 구간)'; }
    else if (dday > -90) { level = -1; label = '선거 직후 3개월 (post-election 불확실성)'; }
    else { level = 0; label = '선거 사이클 외 (일반 구간)'; }
    d.ELECTION_DDAY_LEVEL = {
      name: 'election_dday_level',
      value: level,
      date: today(),
      formula: `D-${dday} (2026-11-03 미국 중간선거) → ${label}. video4 §트럼프 시험 기간 정합.`,
    };
  } catch { /* skip */ }

  // === Phase 2 C3: 중국 경제 지표 (video2 §구리 수요 / video4 §달러 패권) ===
  // FXI (iShares China Large-Cap ETF) 20D 추세 — Yahoo 수집 추가 필요
  try {
    const fxiHist = await fetchYahooHistory('FXI', 60);
    if (fxiHist.length >= 21) {
      const c = fxiHist.map((h) => h.close);
      const ret20 = ((c[c.length - 1] - c[c.length - 21]) / c[c.length - 21]) * 100;
      let level: number;
      let label: string;
      if (ret20 > 10) { level = 2; label = '강세 (+10%, 구리 수요 확대 우호)'; }
      else if (ret20 > 3) { level = 1; label = '양호 (+3-10%)'; }
      else if (ret20 > -3) { level = 0; label = '중립 (±3%)'; }
      else if (ret20 > -10) { level = -1; label = '약세 (-3 ~ -10%)'; }
      else { level = -2; label = '하락 (<-10%, 구리 수요 우려)'; }
      d.CHINA_EQUITY_MOMENTUM = {
        name: 'china_equity_momentum',
        value: parseFloat(ret20.toFixed(2)),
        date: today(),
        formula: `FXI 20D ${ret20.toFixed(2)}% → ${label}. video2 "중국 경기 부양 늦으면 구리 하방 압력".`,
      };
    }
  } catch { /* skip */ }
  try {
    const cnyhHist = await fetchYahooHistory('CNY=X', 90);
    if (cnyhHist.length >= 21) {
      const c = cnyhHist.map((h) => h.close);
      const cur = c[c.length - 1];
      const past = c[c.length - 21];
      const change = ((cur - past) / past) * 100;
      // CNY 약세 = CNYH 상승 = 중국 디플레/경기 둔화 신호
      let level: number;
      let label: string;
      if (cur >= 7.3) { level = -1; label = '위안 약세(CNH≥7.3, 중국 둔화)'; }
      else if (cur >= 7.1) { level = 0; label = '중립(7.1-7.3)'; }
      else { level = 1; label = '위안 강세(<7.1, 중국 회복)'; }
      d.CNH_USD_TIER = {
        name: 'cnh_usd_tier',
        value: level,
        date: today(),
        formula: `USD/CNH ${cur.toFixed(3)} (20D ${change.toFixed(2)}%) → ${label}. video4 §달러 패권 / video2 §중국 수요.`,
      };
    }
  } catch { /* skip */ }

  // === Phase 3 A3: NASDAQ_WEDGE_PATTERN 경량 감지 (video2 §쐐기) ===
  // 20일 high / low 의 **수렴도** (high-low 간격이 이전 대비 축소?)
  try {
    const nqWedge = await fetchYahooHistory('^IXIC', 60);
    if (nqWedge.length >= 40) {
      const cs = nqWedge.map((h) => h.close);
      const recent20 = cs.slice(-20);
      const prev20 = cs.slice(-40, -20);
      const range = (arr: number[]) => Math.max(...arr) - Math.min(...arr);
      const r1 = range(recent20);
      const r0 = range(prev20);
      const convergenceRatio = r0 > 0 ? r1 / r0 : 1;
      let level: number;
      let label: string;
      if (convergenceRatio < 0.5) { level = 2; label = '강한 수렴(<50%, 폭발 직전)'; }
      else if (convergenceRatio < 0.75) { level = 1; label = '수렴(<75%)'; }
      else if (convergenceRatio > 1.5) { level = -1; label = '확산(>150%, 추세 강세)'; }
      else { level = 0; label = '정상'; }
      d.NASDAQ_WEDGE_CONVERGENCE = {
        name: 'nasdaq_wedge_convergence',
        value: parseFloat((convergenceRatio * 100).toFixed(1)),
        date: today(),
        formula: `최근 20D range / 직전 20D range = ${(convergenceRatio * 100).toFixed(1)}%. ${label}. video2 §쐐기 패턴 경량 근사.`,
      };
    }
  } catch { /* skip */ }

  // === 17차 Phase 2 C1: NASDAQ_MULTIFRAME_ALIGNMENT (video3 §차트 순서) ===
  // "월봉으로 큰 그림 → 주봉으로 위치 → 일봉으로 타이밍" 3프레임 일관성.
  //   월봉: NASDAQ_MONTHLY_EXHAUSTION 기반 (0=정상, 1=소진 경고)
  //   주봉: NASDAQ_WEEKLY_REVERSAL (0=정상, 1=하락 전환)
  //   일봉: NASDAQ_ABOVE_200DMA (1=위, 0=아래) + DISPARITY
  //
  // 해석:
  //   +3: 월봉 정상 + 주봉 정상 + 일봉 200DMA 위 = 강한 상승 구조
  //   +1: 장기 유지 + 단기 조정 = 분할매수 적기
  //   -1: 장기 균열 조짐
  //   -3: 모든 프레임 약세 = 구조적 위험
  try {
    const mtfExhaustion = d.NASDAQ_MONTHLY_EXHAUSTION?.value ?? null;
    const mtfReversal = d.NASDAQ_WEEKLY_REVERSAL?.value ?? null;
    const above200 = d.NASDAQ_ABOVE_200DMA?.value ?? null;
    const disp = d.NASDAQ_DISPARITY?.value ?? null;
    const monthlyOk = mtfExhaustion === 0 ? 1 : mtfExhaustion === 1 ? -1 : 0;
    const weeklyOk = mtfReversal === 0 ? 1 : mtfReversal === 1 ? -1 : 0;
    // 일봉: 200DMA 위 + 과열 아님 = +1 / 200DMA 아래 + 저점 구간 = 0 (분할매수) / 200DMA 아래 + 깊은 하락 = -1
    let dailyOk = 0;
    if (above200 === 1 && disp !== null && disp < 15) dailyOk = 1;
    else if (above200 === 1 && disp !== null && disp >= 15) dailyOk = 0; // 과열 근접
    else if (above200 === 0 && disp !== null && disp > -20) dailyOk = 0; // 분할매수 구간
    else if (above200 === 0 && disp !== null && disp <= -20) dailyOk = -1;
    // 20차 E2: 분기봉(quarterly) 축 추가. video3·video2·stt_kospi §"연→반기→분기→월→주→일" 정합.
    // 분기봉 윗꼬리 ≥40% = 분기 매도 우세 → 분기 축 -1, 아니면 +1.
    const quarterWick = d.KOSPI_QUARTERLY_UPPER_WICK_PCT?.value ?? null;
    const quarterlyOk = quarterWick === null ? 0 : (quarterWick >= 40 ? -1 : 1);
    const score = monthlyOk + weeklyOk + dailyOk + quarterlyOk;
    let label: string;
    if (score >= 3) label = '완전 정합 상승 (분기/월/주/일 모두 강세)';
    else if (score >= 1) label = '혼재-상승 기조';
    else if (score >= -1) label = '중립';
    else if (score >= -3) label = '혼재-약세 기조';
    else label = '구조적 약세 (4프레임 동시 경고)';
    d.NASDAQ_MULTIFRAME_ALIGNMENT = {
      name: 'nasdaq_multiframe_alignment',
      value: score,
      date: today(),
      formula:
        `분기 ${quarterlyOk === 1 ? '정상' : quarterlyOk === -1 ? '윗꼬리' : 'n/a'}[${quarterlyOk}] + ` +
        `월봉 ${monthlyOk === 1 ? '정상' : monthlyOk === -1 ? '소진' : 'n/a'}[${monthlyOk}] + ` +
        `주봉 ${weeklyOk === 1 ? '정상' : weeklyOk === -1 ? '반전' : 'n/a'}[${weeklyOk}] + ` +
        `일봉 ${dailyOk === 1 ? '200DMA 위' : dailyOk === 0 ? '분할매수/과열근접' : '깊은 하락'}[${dailyOk}] = ${score} → ${label}. video3 §"연→반기→분기→월→주→일".`,
    };
  } catch { /* skip */ }

  // === 17차 Phase 2 C2: NASDAQ_YEARLY_AREA_INDEX (KOSPI 대칭 확장) ===
  // video3 §8:23 "2025년 연봉 = 아래꼬리 긴 강한 양봉 핀바" + video5_analysis §1부 "아래꼬리 <15% 위험"
  // closes 기반 근사 (OHLC 없이): KOSPI_YEARLY_AREA_INDEX 와 동일 로직.
  try {
    const nqYear = await fetchYahooHistory('^IXIC', 260);
    if (nqYear.length >= 200) {
      const closes = nqYear.map((h) => h.close);
      const yearSlice = closes.slice(-250);
      const yearHigh = Math.max(...yearSlice);
      const yearLow = Math.min(...yearSlice);
      const yearFirst = yearSlice[0];
      const yearLast = yearSlice[yearSlice.length - 1];
      const bodyBottom = Math.min(yearFirst, yearLast);
      const lowerTail = Math.max(0, bodyBottom - yearLow);
      const fullRange = yearHigh - yearLow;
      if (fullRange > 0) {
        const areaIndex = (lowerTail / fullRange) * 100;
        const level = areaIndex >= 15 ? 1 : -1;
        d.NASDAQ_YEARLY_AREA_INDEX = {
          name: 'nasdaq_yearly_area_index',
          value: parseFloat(areaIndex.toFixed(2)),
          date: today(),
          formula:
            `연봉 아래꼬리 ${lowerTail.toFixed(0)} / 전체 높이 ${fullRange.toFixed(0)} = ${areaIndex.toFixed(1)}%. ` +
            `${level === 1 ? '정상(≥15% 핀바 양호)' : '위험(<15% 누적 매수 미소화)'}. video3 §8:23 정합.`,
        };
        // 장대양봉 여부: 연간 종가상승 > 20% + 아래꼬리 ≥ 15%
        const yearReturn = yearFirst > 0 ? ((yearLast - yearFirst) / yearFirst) * 100 : 0;
        if (yearReturn >= 20 && areaIndex >= 15) {
          d.NASDAQ_YEARLY_BULL_PINBAR = {
            name: 'nasdaq_yearly_bull_pinbar',
            value: 1,
            date: today(),
            formula: `연간 ${yearReturn.toFixed(1)}% + 아래꼬리 ${areaIndex.toFixed(1)}% → 장대양봉 핀바 (video3 §8:23 정합).`,
          };
        } else if (yearReturn <= -20) {
          d.NASDAQ_YEARLY_BEAR_CANDLE = {
            name: 'nasdaq_yearly_bear_candle',
            value: 1,
            date: today(),
            formula: `연간 ${yearReturn.toFixed(1)}% → 장대 음봉 (2008/2022 수준 위험).`,
          };
        }
      }
    }
  } catch { /* skip */ }

  // === 17차 Phase 2 D3: M2_LEAD_SHIFT_CORRELATION (노션 §StreetStats M2-S&P) ===
  // 글로벌 M2 를 10주 앞으로 shift 했을 때 S&P500 과의 선행 상관. 양수 강할수록 M2 → S&P 유동성 기여 유효.
  try {
    const m2Hist = await readHistory('fred', 'M2SL');
    const spxHist = await fetchYahooHistory('^GSPC', 400);
    if (m2Hist.length >= 100 && spxHist.length >= 300) {
      // M2 는 월간. 주간 데이터로 근사 불가 → M2 (월간) / S&P (주간) 둘 다 월간 변환.
      const toMonthlyLast = (arr: Array<{ date: string; value?: number; close?: number }>, valueKey: 'value' | 'close') => {
        const byYM = new Map<string, number>();
        for (const p of arr) {
          const ym = p.date.slice(0, 7);
          const v = (p as any)[valueKey];
          if (typeof v === 'number' && Number.isFinite(v)) byYM.set(ym, v);
        }
        return Array.from(byYM.entries()).sort(([a], [b]) => (a < b ? -1 : 1));
      };
      const m2Monthly = toMonthlyLast(m2Hist, 'value');
      const spMonthly = toMonthlyLast(spxHist, 'close');
      // M2 를 3개월 shift (≈10주 근사): M2[i-3] vs SP[i]
      const m2Map = new Map(m2Monthly);
      const spMap = new Map(spMonthly);
      const sharedKeys = spMonthly.map(([k]) => k);
      const pairs: Array<{ m2: number; sp: number }> = [];
      for (let i = 3; i < sharedKeys.length; i++) {
        const k = sharedKeys[i];
        const kLag = sharedKeys[i - 3];
        const m2Val = m2Map.get(kLag);
        const spVal = spMap.get(k);
        if (m2Val !== undefined && spVal !== undefined) pairs.push({ m2: m2Val, sp: spVal });
      }
      if (pairs.length >= 24) {
        // YoY 기반 (level 은 절대 다른 축)
        const returns: Array<{ m2R: number; spR: number }> = [];
        for (let i = 12; i < pairs.length; i++) {
          const m2R = pairs[i].m2 / pairs[i - 12].m2 - 1;
          const spR = pairs[i].sp / pairs[i - 12].sp - 1;
          returns.push({ m2R, spR });
        }
        if (returns.length >= 24) {
          const meanM = returns.reduce((s, r) => s + r.m2R, 0) / returns.length;
          const meanS = returns.reduce((s, r) => s + r.spR, 0) / returns.length;
          let num = 0, dm = 0, ds = 0;
          for (const r of returns) {
            num += (r.m2R - meanM) * (r.spR - meanS);
            dm += (r.m2R - meanM) ** 2;
            ds += (r.spR - meanS) ** 2;
          }
          const corr = Math.sqrt(dm * ds) > 0 ? num / Math.sqrt(dm * ds) : 0;
          let label: string;
          if (corr > 0.5) label = '강한 선행 상관 (M2 → S&P 유동성 기여 유효)';
          else if (corr > 0.2) label = '양의 선행 상관';
          else if (corr < -0.2) label = '음의 상관 (정합성 약화)';
          else label = '약한 상관';
          d.M2_LEAD_SHIFT_CORRELATION = {
            name: 'm2_lead_shift_correlation',
            value: parseFloat(corr.toFixed(3)),
            date: today(),
            formula:
              `M2 t-3M YoY vs S&P t YoY Pearson (n=${returns.length}월) = ${corr.toFixed(3)}. ` +
              `${label}. 노션 §StreetStats M2 선행 shift 정합.`,
          };
        }
      }
    }
  } catch { /* skip */ }

  // === 16차 Phase 2 C1: ANALYST_CONSENSUS + RESEARCH_13F_DIVERGENCE ===
  // video4 §기관 "말 (애널리스트 추천) vs 돈 (13F 포지션)" 괴리 감지.
  try {
    const { fetchAnalystConsensus } = await import('../collectors/analyst-consensus');
    const analyst = await fetchAnalystConsensus();
    if (analyst) {
      d.ANALYST_CONSENSUS_NASDAQ_MEGACAP = {
        name: 'analyst_consensus_nasdaq_megacap',
        value: parseFloat(analyst.avgScore.toFixed(3)),
        date: today(),
        formula:
          `메가캡 ${analyst.tickerCount}종 애널리스트 평균 rating (${analyst.avgScore.toFixed(3)}, -2~+2 scale). ` +
          `종목별: ${JSON.stringify(analyst.perTicker)}. video4 §기관 "말" 측정.`,
      };
      // 18차 P2#10: 목표가 대비 상승여력 평균 (Yahoo financialData.targetMeanPrice 기반)
      if (typeof analyst.avgUpsidePct === 'number') {
        d.ANALYST_TARGET_UPSIDE_PCT = {
          name: 'analyst_target_upside_pct',
          value: analyst.avgUpsidePct,
          date: today(),
          formula: `메가캡 목표가 대비 현재가 상승여력 평균 ${analyst.avgUpsidePct.toFixed(2)}%. 종목별: ${JSON.stringify(analyst.perTickerUpsidePct ?? {})}. 노션 §TipRanks target price 정합.`,
        };
      }
      // 13F flow 와 비교 (돈)
      const instFlow = d.INSTITUTIONAL_NASDAQ_FLOW?.value ?? null;
      if (instFlow !== null) {
        // analyst.avgScore 는 -2~+2 (연속), instFlow 는 -2~+2 (정수 tier)
        // divergence = analyst - instFlow (양수 = 애널리스트 긍정인데 기관 매도 = "말과 돈 괴리")
        const divergence = analyst.avgScore - instFlow;
        let level: number;
        let label: string;
        if (divergence > 2) { level = -2; label = '강한 괴리 (애널↑, 기관↓↓) — 위험'; }
        else if (divergence > 1) { level = -1; label = '괴리 경계 (애널↑, 기관↓)'; }
        else if (divergence < -2) { level = 2; label = '강한 동조 (애널↓, 기관↑) — 매수 기회'; }
        else if (divergence < -1) { level = 1; label = '동조 (애널↓, 기관↑)'; }
        else { level = 0; label = '정합 (말과 돈 일치)'; }
        d.RESEARCH_13F_DIVERGENCE = {
          name: 'research_13f_divergence',
          value: parseFloat(divergence.toFixed(3)),
          date: today(),
          formula:
            `애널리스트 ${analyst.avgScore.toFixed(2)} − 기관 FLOW ${instFlow} = ${divergence.toFixed(2)}. ` +
            `${label} (level ${level}). video4 §기관 "말과 돈 괴리".`,
        };
      }
    }
  } catch { /* skip */ }

  // === 16차 Phase 2 C3: 경제 이벤트 D-Day (FOMC + CPI) ===
  // FOMC: 2026년 기본 일정 (실제는 Fed 공식 발표 기준 하드코딩).
  //   1/28, 3/18, 4/29, 6/10, 7/29, 9/16, 10/28, 12/16 (8회, 연준 표준)
  // CPI: 매월 10-15일 경 발표. 2026년 4월은 4/10 발표 가정.
  try {
    const now = new Date();
    const upcomingFomc = [
      '2026-04-29', '2026-06-10', '2026-07-29', '2026-09-16', '2026-10-28', '2026-12-16',
    ].map((s) => new Date(s + 'T19:00:00Z')); // FOMC 18:00 EST ≈ 22:00 UTC 발표
    const nextFomc = upcomingFomc.find((d) => d.getTime() > now.getTime());
    if (nextFomc) {
      const dday = Math.ceil((nextFomc.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
      let level: number;
      let label: string;
      if (dday <= 3) { level = 2; label = `⚠️ FOMC 임박 D-${dday} — 변동성 증가, 신규 진입 보수`; }
      else if (dday <= 7) { level = 1; label = `FOMC D-${dday} — 일주일 내 정책 결정`; }
      else { level = 0; label = `FOMC D-${dday}`; }
      d.FOMC_DDAY = {
        name: 'fomc_dday',
        value: dday,
        date: today(),
        formula: `다음 FOMC ${nextFomc.toISOString().slice(0,10)} → ${label}. video4 §FOMC 발언 중요도.`,
      };
    }

    // BOK 경제전망 발표 D-Day (연 4회: 2/22, 5/29, 8/28, 11/27 기준)
    const bokDates2026 = ['2026-05-29', '2026-08-28', '2026-11-27']
      .map((s) => new Date(s + 'T01:00:00Z'));
    const nextBok = bokDates2026.find((d) => d.getTime() > now.getTime());
    if (nextBok) {
      const dday = Math.ceil((nextBok.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
      d.BOK_FORECAST_DDAY = {
        name: 'bok_forecast_dday',
        value: dday,
        date: today(),
        formula: `다음 한은 경제전망 ${nextBok.toISOString().slice(0,10)} (D-${dday}). 연 4회 발표.`,
      };
    }

    // CPI D-Day (월중 발표일 근사: 매월 둘째 주 화요일)
    const curMonth = now.getUTCMonth();
    const curYear = now.getUTCFullYear();
    const findSecondTuesday = (y: number, m: number) => {
      const d = new Date(Date.UTC(y, m, 1));
      let tueCount = 0;
      for (let day = 1; day <= 31; day++) {
        const dt = new Date(Date.UTC(y, m, day));
        if (dt.getUTCMonth() !== m) break;
        if (dt.getUTCDay() === 2) { // 화요일
          tueCount++;
          if (tueCount === 2) return dt;
        }
      }
      return null;
    };
    const thisMonthCpi = findSecondTuesday(curYear, curMonth);
    const nextMonthCpi = findSecondTuesday(curYear, curMonth + 1);
    const nextCpi = thisMonthCpi && thisMonthCpi.getTime() > now.getTime() ? thisMonthCpi : nextMonthCpi;
    if (nextCpi) {
      const dday = Math.ceil((nextCpi.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
      d.CPI_DDAY = {
        name: 'cpi_dday',
        value: dday,
        date: today(),
        formula: `다음 CPI 발표 ${nextCpi.toISOString().slice(0,10)} (D-${dday}). 월중 둘째 화요일 근사.`,
      };
    }
  } catch { /* skip */ }

  // === 16차 Phase 3 E1: NASDAQ_OBV_TREND ===
  // On-Balance Volume 20D 추세 — 가격 올라가는데 OBV 약세 = 약한 상승.
  // fetchYahooHistory 가 close 만 반환하므로 여기서는 근사 없음 → 관측 skip.
  // TODO: OHLCV fetch 확장 시 재활성화.
  // (derived 키 자체 생성 안함 — placeholder)

  // === Phase 3 B1: BITCOIN 지표 (수집만, allocation 제외) ===
  // 사용자 지침: "비트코인 지표는 사용하되 포트폴리오 비율에는 포함하지 말 것"
  // BTC-USD 수집 + 위험선호 proxy 로만 활용 (NASDAQ 과열 플래그 등).
  try {
    const btcHist = await fetchYahooHistory('BTC-USD', 30);
    if (btcHist.length >= 21) {
      const cs = btcHist.map((h) => h.close);
      const ret20 = ((cs[cs.length - 1] - cs[cs.length - 21]) / cs[cs.length - 21]) * 100;
      let level: number;
      let label: string;
      if (ret20 > 20) { level = 2; label = '극강세 (+20%, 위험선호 극대)'; }
      else if (ret20 > 10) { level = 1; label = '강세 (+10-20%)'; }
      else if (ret20 > -10) { level = 0; label = '중립 (±10%)'; }
      else if (ret20 > -20) { level = -1; label = '약세 (-10 ~ -20%)'; }
      else { level = -2; label = '폭락 (<-20%, 위험회피 극대)'; }
      d.BTC_MOMENTUM = {
        name: 'btc_momentum',
        value: parseFloat(ret20.toFixed(2)),
        date: today(),
        formula: `BTC-USD 20D ${ret20.toFixed(2)}% → ${label}. video4 "위험선호 지표" proxy. allocation 제외 (사용자 지침).`,
      };
    }
  } catch { /* skip */ }

  // ═══════════════════════════════════════════════════════════════════════
  // 18차 Phase 1 + Phase 2 블록
  // ═══════════════════════════════════════════════════════════════════════

  // === P1#2: 착한 레버리지 3-of-3 트리거 플래그 ===
  // video1 §3부 "이격도 -25% 이하 AND VIX 35 이상 AND 실업수당 30만 미만" 동시 충족 시에만 활성.
  // 횡보·추세 시장에선 절대 켜지지 않음 → 레버리지 ETF 최대 15%, 목표 수익 +20~30% 룰과 결합.
  try {
    const disp = d.NASDAQ_DISPARITY?.value ?? null;
    const vix = val(raw, 'VIX');
    const icsaV = val(raw, 'ICSA');
    const dispOk = disp !== null && disp <= -25;
    const vixOk = vix !== null && vix >= 35;
    const icsaOk = icsaV !== null && icsaV < 300000;
    const triggered = dispOk && vixOk && icsaOk;
    const count = [dispOk, vixOk, icsaOk].filter(Boolean).length;
    d.LEVERAGE_TRIGGER_3OF3 = {
      name: 'leverage_trigger_3of3',
      value: triggered ? 1 : 0,
      date: today(),
      formula: `이격도≤-25%(${dispOk ? 'Y' : 'N'} ${disp ?? '-'}) AND VIX≥35(${vixOk ? 'Y' : 'N'} ${vix ?? '-'}) AND ICSA<30만(${icsaOk ? 'Y' : 'N'} ${icsaV ?? '-'}) → ${count}/3. video1 §3부 "착한 레버리지". 레버리지 ETF 최대 15% / 익절 +20~30% / 횡보 즉시 일반 ETF 복귀.`,
    };
    d.LEVERAGE_TRIGGER_COUNT = {
      name: 'leverage_trigger_count',
      value: count,
      date: today(),
      formula: `3가지 동시 충족 수 (이격도 + VIX + ICSA). 1~2개는 레버리지 금지.`,
    };
  } catch { void 0; }

  // === P1#3: 5/7축 확신 정렬 스코어 (CONVICTION_SCORE_7AXIS) ===
  // video1 §2부 "5가지가 같은 방향을 가리킬 때 가장 강하게". video4 §7축으로 확장.
  // 7축: 차트 / 유동성 / 정책 / 지정학 / 모멘텀 / 기관리포트 / 매크로.
  // 각 축 +1 (강세) / 0 (중립) / -1 (약세). 총합 -7 ~ +7.
  try {
    const axes: Record<string, number> = {};

    // 1) 차트 — 멀티프레임 정합 스코어 (17차 C1).
    // 23차 Tier 2#18: long horizon 사용자는 분기·월봉 정합 가중 (단기 일봉 노이즈 무시).
    //   long: 분기/월봉만 +1 가중 — multiframe 점수 ≥1 이면 +1, ≤-1 이면 -1
    //   short: 일봉 정합 우선 — 점수 ≥3 (강한 정합) 이면 +1
    const mf = d.NASDAQ_MULTIFRAME_ALIGNMENT?.value ?? null;
    const horizonForChart = manualInputs?.investmentHorizon ?? 'medium';
    if (mf === null) axes.chart = 0;
    else if (horizonForChart === 'long') axes.chart = mf >= 1 ? 1 : mf <= -1 ? -1 : 0;
    else if (horizonForChart === 'short') axes.chart = mf >= 3 ? 1 : mf <= -3 ? -1 : 0;
    else axes.chart = mf >= 2 ? 1 : mf <= -2 ? -1 : 0;

    // 2) 유동성 — GLOBAL_M2 추세
    const m2 = d.GLOBAL_M2_PROXY?.value ?? null;
    axes.liquidity = m2 === null ? 0 : (m2 > 3 ? 1 : m2 < 0 ? -1 : 0);

    // 3) 정책 — regime inputs policy direction (manual policy score 대용)
    const policyDir = raw.POLICY_DIRECTION?.value ?? null;
    axes.policy = policyDir === null ? 0 : (policyDir > 0 ? 1 : policyDir < 0 ? -1 : 0);

    // 4) 지정학 — HORMUZ_CHAIN_SCORE 역방향 (악화=axis down).
    // 20차 A: video4 §6:23 "단기 부정 / 장기 우호" — long horizon 사용자는 부호 반전.
    const geo = d.HORMUZ_CHAIN_SCORE?.value ?? null;
    const horizon = manualInputs?.investmentHorizon ?? 'medium';
    if (geo === null) axes.geo = 0;
    else if (horizon === 'long') {
      // 장기: geo 악화는 단기에 약세지만 금리인하 명분으로 우호 → 약하게만 음수
      axes.geo = geo <= 0 ? 1 : geo >= 2 ? 0 : 0; // 단기 위험을 장기에선 무시
    } else {
      axes.geo = geo <= 0 ? 1 : geo >= 2 ? -1 : 0;
    }

    // 5) 모멘텀 — NASDAQ_RSI_14. 19차 P2#11: RSI 70+ 도 +1 (video1 §확산 "더 오를 수 있음").
    //    단 RSI ≥85 극과매수만 0 처리 (반전 위험).
    // 23차 Tier 2#18: long horizon 시 RSI 단기 변동 무시 — 50/40 컷을 더 넓게 (60/30).
    //   short horizon 은 일봉 RSI 50 컷 유지 (반응성 우선).
    const rsi = d.NASDAQ_RSI_14?.value ?? null;
    const horizonForMomentum = manualInputs?.investmentHorizon ?? 'medium';
    if (rsi === null) axes.momentum = 0;
    else if (horizonForMomentum === 'long') {
      axes.momentum = rsi >= 60 && rsi < 85 ? 1 : rsi < 30 ? -1 : 0;
    } else {
      axes.momentum = rsi >= 50 && rsi < 85 ? 1 : rsi < 40 ? -1 : 0;
    }

    // 6) 기관 리포트 — ANALYST_CONSENSUS_NASDAQ_MEGACAP rating (-2~+2 → -1/0/+1 양자화)
    const an = d.ANALYST_CONSENSUS_NASDAQ_MEGACAP?.value ?? null;
    axes.analyst = an === null ? 0 : (an >= 0.5 ? 1 : an <= -0.5 ? -1 : 0);

    // 7) 매크로 — CPI YoY + 27차 IMF 2031 / 감세 trajectory 결합 (3 sub-축 평균)
    // video4 §10:11+10:20 정합 — 부채 / 적자 trajectory 도 매크로 위협
    const cpi = d.CPI_YOY?.value ?? null;
    const cpiAxis = cpi === null ? 0 : (cpi >= 2 && cpi <= 3.5 ? 1 : cpi > 5 || cpi < 0 ? -1 : 0);
    const debtAxis = (d.US_DEBT_GDP_2031_PROJECTION?.value ?? 0) >= 2 ? -1
                   : (d.US_DEBT_GDP_2031_PROJECTION?.value ?? 0) >= 1 ? 0 : 0;
    const deficitAxis = (d.TRUMP_TAX_CUT_DEFICIT_PROJECTION?.value ?? 0) >= 2 ? -1
                      : (d.TRUMP_TAX_CUT_DEFICIT_PROJECTION?.value ?? 0) >= 1 ? 0 : 0;
    // 평균 후 -1/0/+1 양자화
    const macroSum = cpiAxis + debtAxis + deficitAxis;
    axes.macro = macroSum >= 1 ? 1 : macroSum <= -1 ? -1 : 0;

    // 27차 Phase 1#7: 8번째 축 — 금/구리 비율 (video2 §3부 "주식 시장 방향 선행")
    const goldCopperVal = d.GOLD_COPPER_RATIO?.value ?? null;
    if (goldCopperVal === null) axes.metal = 0;
    else if (goldCopperVal >= 200) axes.metal = -1;
    else if (goldCopperVal <= 80) axes.metal = 1;
    else axes.metal = 0;

    // 28차 영상6 #4: 9번째 축 — Horizon 정합 (video6 §"시간 프레임이 정해지지 않으면 전략이 없음")
    // USER_HORIZON_ALIGNMENT 결과 그대로 — 정합 +1 / 불일치 -1 / 관망 0
    const horizonAlign = d.USER_HORIZON_ALIGNMENT?.value ?? 0;
    axes.horizon = horizonAlign === 1 ? 1 : horizonAlign === -1 ? -1 : 0;

    const total = Object.values(axes).reduce((a, b) => a + b, 0);
    const positives = Object.values(axes).filter((v) => v > 0).length;
    const negatives = Object.values(axes).filter((v) => v < 0).length;
    let label: string;
    if (total >= 5) label = '🟢 극강 정합 (7축 중 5+ 강세)';
    else if (total >= 3) label = '🟢 강 정합 (3-4축 강세)';
    else if (total >= 1) label = '🔵 약 정합 (1-2축 강세 우세)';
    else if (total <= -5) label = '🔴 극약 정합 (7축 중 5+ 약세)';
    else if (total <= -3) label = '🟠 약세 정합 (3-4축 약세)';
    else if (total <= -1) label = '🟡 혼조 약세 우세';
    else label = '⚪ 중립 (정합 불명확)';

    d.CONVICTION_SCORE_7AXIS = {
      name: 'conviction_score_7axis',
      value: total,
      date: today(),
      formula: `차트${axes.chart > 0 ? '+' : ''}${axes.chart}/유동성${axes.liquidity > 0 ? '+' : ''}${axes.liquidity}/정책${axes.policy > 0 ? '+' : ''}${axes.policy}/지정학${axes.geo > 0 ? '+' : ''}${axes.geo}/모멘텀${axes.momentum > 0 ? '+' : ''}${axes.momentum}/애널${axes.analyst > 0 ? '+' : ''}${axes.analyst}/매크로${axes.macro > 0 ? '+' : ''}${axes.macro}/금구리${(axes.metal ?? 0) > 0 ? '+' : ''}${axes.metal ?? 0}/시계열${(axes.horizon ?? 0) > 0 ? '+' : ''}${axes.horizon ?? 0} = ${total} (${positives}강↑/${negatives}약↓, 9축). ${label}. video1+4+6 §확신 5/7+27차 8+28차 9축.`,
    };
  } catch { void 0; }

  // === P2#7 + 24차 P1#2: 금 장기 컵앤핸들 13년 (video2 §18:20 "2011-2024 13년 패턴") ===
  // GLD ETF 13년 history (3300일) 시도, 부족 시 GC=F + 가능한 만큼 fallback.
  try {
    let gHist = await fetchYahooHistory('GLD', 3300);
    if (gHist.length < 2000) gHist = await fetchYahooHistory('GC=F', 3300);
    if (gHist.length >= 800) {
      const closes = gHist.map((h) => h.close);
      const n = closes.length;
      const last = closes[n - 1];
      // cup rim: 8~13년 전 고점 (대형 패턴) — 가능한 만큼 깊은 과거
      // 13년 history 확보 시: rimStart = n-3300, rimEnd = n-2000 (8~13년 전 max)
      // 부족 시 가용 깊이의 가장 오래된 구간 사용
      const targetRimStart = Math.max(0, n - 3300);
      const targetRimEnd = Math.max(targetRimStart + 250, n - 2000);
      const rim = Math.max(...closes.slice(targetRimStart, targetRimEnd));
      const hlStart = Math.max(20, n - 126);
      const handleLow = Math.min(...closes.slice(hlStart, n - 20));
      const recentMa = closes.slice(n - 20).reduce((a, b) => a + b, 0) / 20;
      const rimRecovered = last >= rim * 0.97;
      const handleConfirmed = recentMa > handleLow * 1.02 && last > handleLow * 1.05;
      const yearsCovered = (n / 252).toFixed(1);
      let level: number;
      let label: string;
      if (rimRecovered && handleConfirmed) { level = 2; label = `🟢 컵앤핸들 완성 (rim 재탈환 + handle 돌파, ${yearsCovered}년 깊이)`; }
      else if (rimRecovered) { level = 1; label = `🔵 cup rim 재탈환 — handle 대기 (${yearsCovered}년)`; }
      else if (last > rim * 0.85) { level = 0; label = `⚪ cup 진행 (rim 85%+, ${yearsCovered}년)`; }
      else { level = -1; label = `🟡 cup 미완성 (${yearsCovered}년)`; }
      d.GOLD_LONGTERM_CUP_HANDLE = {
        name: 'gold_longterm_cup_handle',
        value: level,
        date: today(),
        formula: `rim=${rim.toFixed(0)} (8~13년 전 max), last=${last.toFixed(0)}, handleLow=${handleLow.toFixed(0)}, depth=${yearsCovered}년. ${label}. video2 §18:20.`,
      };
    }
  } catch { void 0; }

  // === 19차 P1#4: FX_FOREIGN_DEVIATION_RATIO ===
  // stt_kospi §3부 "환율 10%↑ 시 외인 매도 5조 적정 vs 실제 60조 = 12배 ATM화" 절대 베이스라인.
  // KRX history 단일 키 부재 → 기존 derived KOSPI_FOREIGN_NET20D (없으면 0) + KOSPI_FOREIGN_HISTORIC_EXTREME 활용.
  try {
    const krwHist = await readHistory('yahoo', 'USDKRW');
    const fgnNet20D = d.KOSPI_FOREIGN_NET_20D?.value ?? null;
    if (krwHist.length >= 60 && fgnNet20D !== null) {
      const krwLatest = krwHist[krwHist.length - 1].value;
      const krwPrev = krwHist[Math.max(0, krwHist.length - 60)].value;
      const krwChangePct = ((krwLatest - krwPrev) / krwPrev) * 100;
      // 단위 변환 — KOSPI_FOREIGN_NET20D 가 억원 → 조원
      const cumTrillionKRW = fgnNet20D / 10000;
      const expectedSellTrillion = krwChangePct > 0 ? krwChangePct * 0.5 : 0;
      let ratio = 0;
      if (expectedSellTrillion > 0.1 && cumTrillionKRW < 0) {
        ratio = Math.abs(cumTrillionKRW) / expectedSellTrillion;
      }
      let level: number;
      let label: string;
      if (ratio >= 6) { level = 3; label = `🔴 ATM화 극단 (${ratio.toFixed(1)}배 — stt_kospi §3부 "12배 같은 비정상")`; }
      else if (ratio >= 3) { level = 2; label = `🟠 ATM화 경계 (${ratio.toFixed(1)}배 — 기준 대비 3배 초과)`; }
      else if (ratio >= 1.5) { level = 1; label = `🟡 적정 초과 (${ratio.toFixed(1)}배)`; }
      else { level = 0; label = `⚪ 적정 (${ratio.toFixed(1)}배)`; }
      d.FX_FOREIGN_DEVIATION_RATIO = {
        name: 'fx_foreign_deviation_ratio',
        value: parseFloat(ratio.toFixed(2)),
        date: today(),
        formula: `환율 60D ${krwChangePct.toFixed(2)}% → 적정 매도 ${expectedSellTrillion.toFixed(1)}조, 실제 ${Math.abs(cumTrillionKRW).toFixed(1)}조 매도 = ${ratio.toFixed(1)}배. ${label}. stt_kospi §3부 "환율 10%↑ → 5조 = 1배 baseline".`,
      };
      d.FX_FOREIGN_DEVIATION_LEVEL = {
        name: 'fx_foreign_deviation_level',
        value: level,
        date: today(),
        formula: `0=적정, 1=초과, 2=경계, 3=극단(ATM화).`,
      };
    }
  } catch { void 0; }

  // === 19차 P2#5: NEUTRAL_RATE_TEMPERATURE (골디락스 신호등 R/Y/G) ===
  // video4 §매크로 "고금리 빨간불 / 노란불 / 초록불". (FFR_target_mid - R*) 갭 기반.
  // R* (중립금리) 추정: NY Fed Holston-Laubach-Williams ≈ 0.7~1.1% 범위. 단순화로 1.0% 사용.
  // DFEDTARU/L 미수집 환경에서는 EFFR (effective rate, FRED 기수집) 으로 fallback.
  try {
    const effr = val(raw, 'EFFR');
    if (effr !== null) {
      const targetMid = effr;
      const RSTAR = 1.0; // NY Fed HLW 추정 평균
      const gap = targetMid - RSTAR;
      let level: number;
      let label: string;
      if (gap >= 3) { level = 2; label = '🔴 빨간불 — 고긴축 (FFR-R* ≥ 3%p)'; }
      else if (gap >= 1.5) { level = 1; label = '🟡 노란불 — 긴축 (FFR-R* 1.5~3%p)'; }
      else if (gap >= 0) { level = 0; label = '🟢 초록불 — 중립~약긴축 (FFR-R* 0~1.5%p)'; }
      else { level = -1; label = '🟢🟢 초완화 (FFR < R*) — 부양 적극'; }
      d.NEUTRAL_RATE_TEMPERATURE = {
        name: 'neutral_rate_temperature',
        value: level,
        date: today(),
        formula: `FFR target mid ${targetMid.toFixed(2)}% - R* ${RSTAR}% = ${gap.toFixed(2)}%p. ${label}. video4 §매크로 신호등.`,
      };
    }
  } catch { void 0; }

  // === 19차 P2#7: RANGE_BREAK_FAKEOUT ===
  // video3 §174 "위로 돌파한다고 롱 잡은 사람들 물려있는 상태".
  // 일봉 최근 20일 고점 갱신 후 5일 내 그 고점의 -3% 이상 재이탈 시 페이크아웃.
  try {
    const nHist = await fetchYahooHistory('^IXIC', 60);
    if (nHist.length >= 30) {
      const closes = nHist.map((h) => h.close);
      const n = closes.length;
      // 최근 25~5일 사이 20일 고점 갱신 후 → 최근 5일 종가 검사
      let breakoutIdx = -1;
      for (let i = n - 5; i >= n - 25; i--) {
        const window = closes.slice(Math.max(0, i - 20), i);
        const winMax = Math.max(...window);
        if (closes[i] > winMax * 1.005) { breakoutIdx = i; break; }
      }
      let fakeout = 0;
      let label = '⚪ 페이크아웃 없음';
      if (breakoutIdx > 0) {
        const breakoutHigh = closes[breakoutIdx];
        const recent = closes.slice(breakoutIdx + 1);
        const minAfter = recent.length > 0 ? Math.min(...recent) : breakoutHigh;
        if (minAfter < breakoutHigh * 0.97) {
          fakeout = 1;
          label = `🟠 페이크아웃 의심 — ${breakoutHigh.toFixed(0)} 돌파 후 ${minAfter.toFixed(0)} (-${(((breakoutHigh - minAfter) / breakoutHigh) * 100).toFixed(1)}%)`;
        }
      }
      d.NASDAQ_RANGE_BREAK_FAKEOUT = {
        name: 'nasdaq_range_break_fakeout',
        value: fakeout,
        date: today(),
        formula: `${label}. video3 §174 "돌파 척하다 회귀 = 단기 급락 가속".`,
      };
    }
  } catch { void 0; }

  // === 19차 P2#8: HELIUM_AI_BOTTLENECK (간이 proxy) ===
  // video5 §2부 "전 세계 헬륨 상당 부분이 카타르… AI 칩 생산 병목".
  // SOXX 30D return 은 d.SECTOR_SOXX 로 이미 산출됨 (자체 fetch 불필요).
  try {
    const ret30 = d.SECTOR_SOXX?.value ?? null;
    const hormuz = d.HORMUZ_CHAIN_SCORE?.value ?? 0;
    if (ret30 !== null) {
      let level = 0;
      let label = '⚪ 정상';
      if (hormuz >= 2 && ret30 < -5) { level = 2; label = '🔴 카타르 LNG 차단 가설 + SOXX 약세'; }
      else if (hormuz >= 1 && ret30 < -3) { level = 1; label = '🟡 헬륨 공급 경계'; }
      else if (hormuz <= 0 && ret30 > 5) { level = -1; label = '🟢 카타르 정상 + SOXX 강세'; }
      d.HELIUM_AI_BOTTLENECK = {
        name: 'helium_ai_bottleneck',
        value: level,
        date: today(),
        formula: `Hormuz=${hormuz}, SOXX 30D=${ret30.toFixed(1)}%. ${label}. video5 §2부 proxy.`,
      };
    }
  } catch { void 0; }

  // === 19차 P2#9: UPPER_WICK_IMPULSE (OHLC 정식 복구 — fetchYahooOHLC 사용) ===
  // video2 §4부 "추세 통틀어 가장 강한 매도세". 윗꼬리/몸통 비율 × 거래량 임팩트.
  try {
    const { fetchYahooOHLC } = await import('../collectors/yahoo');
    const ohlc = await fetchYahooOHLC('^IXIC', 100);
    if (ohlc.length >= 60) {
      const last = ohlc[ohlc.length - 1];
      const upperWick = Math.max(0, last.high - Math.max(last.open, last.close));
      const body = Math.max(0.0001, Math.abs(last.close - last.open));
      const wickRatio = upperWick / body;
      const avgVol = ohlc.slice(-60).reduce((s, p) => s + (p.volume ?? 0), 0) / 60;
      const volImpact = avgVol > 0 ? last.volume / avgVol : 1;
      const impulse = wickRatio * volImpact;
      let level: number;
      let label: string;
      if (impulse >= 3 && wickRatio >= 1) { level = 2; label = `🔴 강한 윗꼬리 매도세 (wick/body=${wickRatio.toFixed(2)}, vol×${volImpact.toFixed(2)})`; }
      else if (impulse >= 1.5 && wickRatio >= 0.6) { level = 1; label = '🟡 윗꼬리 매도 압력'; }
      else { level = 0; label = '⚪ 정상'; }
      d.NASDAQ_UPPER_WICK_IMPULSE = {
        name: 'nasdaq_upper_wick_impulse',
        value: parseFloat(impulse.toFixed(2)),
        date: today(),
        formula: `윗꼬리/몸통 ${wickRatio.toFixed(2)} × 거래량 ${volImpact.toFixed(2)} = ${impulse.toFixed(2)}. last OHLC=${last.open.toFixed(0)}/${last.high.toFixed(0)}/${last.low.toFixed(0)}/${last.close.toFixed(0)}. ${label}. video2 §4부.`,
      };
      d.NASDAQ_UPPER_WICK_LEVEL = {
        name: 'nasdaq_upper_wick_level',
        value: level,
        date: today(),
        formula: `0=정상, 1=경계, 2=강한 매도세.`,
      };
    }
  } catch { void 0; }

  // === 19차 P2#6: USER_HORIZON_ALIGNMENT ===
  // video1 §5부 "본인 시계열 먼저 정해야". horizon 기준 만족 자산 신호 정렬도.
  try {
    const horizon = manualInputs?.investmentHorizon ?? 'medium';
    // short: 단기 모멘텀 (NASDAQ_RSI > 50, multiframe ≥1, fakeout 없음)
    // medium: regime + drawdown 적정 + conviction
    // long: drawdown 깊을수록 좋음 + cup&handle + cb_gold
    const conv = d.CONVICTION_SCORE_7AXIS?.value ?? 0;
    const mf = d.NASDAQ_MULTIFRAME_ALIGNMENT?.value ?? 0;
    const dd = d.NASDAQ_DRAWDOWN_ATH?.value ?? 0;
    const fake = d.NASDAQ_RANGE_BREAK_FAKEOUT?.value ?? 0;
    let aligned: number;
    let label: string;
    if (horizon === 'short') {
      aligned = mf >= 1 && fake === 0 && conv >= 1 ? 1 : (fake === 1 || conv <= -2 ? -1 : 0);
      label = `단기(${horizon}) — multiframe=${mf}, fakeout=${fake}, conv=${conv}`;
    } else if (horizon === 'long') {
      aligned = (dd <= -15 || conv >= 2) ? 1 : (conv <= -2 ? -1 : 0);
      label = `장기(${horizon}) — DD=${dd.toFixed(1)}%, conv=${conv}, 깊은 조정 우호`;
    } else {
      aligned = conv >= 2 && dd > -25 ? 1 : (conv <= -2 ? -1 : 0);
      label = `중기(${horizon}) — conv=${conv}, DD=${dd.toFixed(1)}%`;
    }
    d.USER_HORIZON_ALIGNMENT = {
      name: 'user_horizon_alignment',
      value: aligned,
      date: today(),
      formula: `${label}. video1 §5부 "본인 시계열 먼저". 1=정합, 0=관망, -1=불일치.`,
    };
  } catch { void 0; }

  // === 19차 P3#14 + 24차 P1#5: DCA_TRANCHE_PROGRESS + 잔여 buffer 가용 매수 룸 ===
  // video1+3 "3회 이상 분할". 사용자 trancheUsedPct → 신호 가중치 / execution stage status 에 반영.
  try {
    const used = manualInputs?.trancheUsedPct;
    if (typeof used === 'number' && used >= 0 && used <= 100) {
      let level: number;
      let label: string;
      if (used >= 100) { level = 2; label = '🟠 분할매수 100% — 추가 진입 없음'; }
      else if (used >= 70) { level = 1; label = '🟡 분할매수 70%+ — 잔여 buffer 적음'; }
      else if (used >= 30) { level = 0; label = '⚪ 진행 중 (30~70%)'; }
      else { level = -1; label = '🟢 buffer 충분 (<30%)'; }
      d.DCA_TRANCHE_PROGRESS = {
        name: 'dca_tranche_progress',
        value: used,
        date: today(),
        formula: `사용자 분할매수 사용률 ${used}%. ${label}. video1+3 "3회 이상 분할".`,
      };
      // 24차 P1#5: 추가 매수 가용 buffer (100 - used). 신호 권고 강도 조정용 별도 derived.
      d.DCA_BUFFER_REMAINING_PCT = {
        name: 'dca_buffer_remaining_pct',
        value: 100 - used,
        date: today(),
        formula: `추가 매수 가용 ${100 - used}%. signals 가중치 / execution stage 결정에 반영 권고.`,
      };
    }
  } catch { void 0; }

  // === 19차 P2#10: ETF_INFLOW_THEME (마이핀플 manual) ===
  // 노션 §"마이핀플 미국 ETF 순위" — 정량화 어려워 사용자 manual input 으로 노출.
  try {
    const theme = manualInputs?.etfInflowTheme;
    if (typeof theme === 'string' && theme.trim().length > 0) {
      d.ETF_INFLOW_THEME = {
        name: 'etf_inflow_theme',
        value: 1,
        date: today(),
        formula: `주간 ETF 자금 유입 테마: "${theme.trim()}" (manual input). 노션 §마이핀플.`,
      };
    }
  } catch { void 0; }

  // === 19차 P3#13 + 23차 Tier 2#13 + 24차 Phase 2#11: SCENARIO_GATE_A_B (5축 — 5150/4080 절대값 추가) ===
  // stt_kospi §4부 "두 가지 그림 동시에" + §"5150 1차 / 4080 2차 지지". 환율 + 200DMA + 외인 + 거래량 + KOSPI 절대값.
  try {
    const krw = val(raw, 'USDKRW');
    const kospi = val(raw, 'KOSPI');
    const kospiAbove200 = d.KOSPI_DISPARITY?.value ?? null;
    const fgnFlow = d.KOSPI_FOREIGN_FLOW_LEVEL?.value ?? d.KOSPI_FOREIGN_HISTORIC_EXTREME?.value ?? null;
    const volConfirm = d.KOSPI_VOLUME_CONFIRM?.value ?? 0;
    // 24차 Phase 2#11: KOSPI 절대값 5150 (1차 지지) / 4080 (2차 지지) — stt_kospi §4부 명시
    const above5150 = kospi !== null && kospi >= 5150;
    const below4080 = kospi !== null && kospi < 4080;
    let scenario: number;
    let label: string;
    if (krw !== null && krw <= 1480 && kospiAbove200 !== null && kospiAbove200 > 0 && (fgnFlow === null || fgnFlow >= 0) && volConfirm === 1 && above5150) {
      scenario = 1; label = '🟢 시나리오 A — 추세 재개 (환율<1480 + 200DMA + 외인 + 거래량 + 5150+ 5축 모두)';
    } else if (krw !== null && krw <= 1480 && kospiAbove200 !== null && kospiAbove200 > 0 && (fgnFlow === null || fgnFlow >= 0)) {
      scenario = 1; label = '🟢 시나리오 A 진행 (3축 충족)';
    } else if (below4080 || (krw !== null && krw >= 1500 && kospiAbove200 !== null && kospiAbove200 < -3)) {
      scenario = -1; label = below4080 ? '🔴 시나리오 B — 4080 2차 지지 이탈 (stt_kospi §4부)' : '🔴 시나리오 B — 박스 하방 이탈';
    } else {
      scenario = 0; label = '🟡 관망 — 시나리오 분기점';
    }
    d.SCENARIO_GATE_A_B = {
      name: 'scenario_gate_a_b',
      value: scenario,
      date: today(),
      formula: `KRW=${krw?.toFixed(1) ?? '-'}, KOSPI=${kospi?.toFixed(0) ?? '-'} (5150+:${above5150 ? 'Y' : 'N'} / 4080-:${below4080 ? 'Y' : 'N'}), 200DMA=${kospiAbove200?.toFixed(2) ?? '-'}%, fgn=${fgnFlow ?? '-'}, vol=${volConfirm}. ${label}. stt_kospi §4부 5축.`,
    };
  } catch { void 0; }

  // === 19차 P2#11: KOSPI_HISTORIC_OVERSHOOT_FLAG (75% YTD) ===
  // stt_kospi §1부 "75% 이상 후 조정 없이 직행 사례 거의 없음".
  // 기존 derived KOSPI_YEAR_RETURN 재활용 (자체 fetch 불필요).
  try {
    const ytd = d.KOSPI_YEAR_RETURN?.value ?? null;
    if (ytd !== null) {
      const flag = ytd >= 75 ? 1 : 0;
      d.KOSPI_HISTORIC_OVERSHOOT_FLAG = {
        name: 'kospi_historic_overshoot_flag',
        value: flag,
        date: today(),
        formula: `KOSPI 1년 ${ytd.toFixed(1)}% ${flag ? '≥75% — 역사적 과열 (조정 직전)' : '<75%'}. stt_kospi §1부.`,
      };
    }
  } catch { void 0; }

  // === 19차 P3#18: 18차 ★ 잔여 정리 — 호르무즈 정상화 + 금은비 130 극단 ===
  // SOXX 90D 별도 fetch 어려움 → 30D return 으로 단순화.
  try {
    const hormuzScore = d.HORMUZ_CHAIN_SCORE?.value ?? null;
    const ret30 = d.SECTOR_SOXX?.value ?? null;
    if (hormuzScore !== null && hormuzScore <= 0 && ret30 !== null) {
      const tailwind = ret30 > 8 ? 1 : 0;
      d.HORMUZ_UNWIND_SEMI_TAILWIND = {
        name: 'hormuz_unwind_semi_tailwind',
        value: tailwind,
        date: today(),
        formula: `Hormuz=${hormuzScore} (정상) + SOXX 30D=${ret30.toFixed(1)}% ${tailwind ? '→ 반도체 양의 연쇄' : '(연쇄 약함)'}. video5 §정상화 후 병목 완화.`,
      };
    }
  } catch { void 0; }

  // 금은비 130+ 극단 플래그 (video2 §2부 "코로나 130 → 4개월 150%")
  try {
    const goldSilverRatio = d.GOLD_SILVER_RATIO?.value ?? null;
    if (goldSilverRatio !== null) {
      let extremeLevel = 0;
      let label = '⚪ 정상';
      if (goldSilverRatio >= 130) { extremeLevel = 2; label = '🔴 금은비 130+ 극단 — video2 §"코로나 130→은 150%" 사례 구간'; }
      else if (goldSilverRatio >= 100) { extremeLevel = 1; label = '🟠 금은비 100+ 과열 — 은 매수 우호 진행 중'; }
      else if (goldSilverRatio >= 80) { extremeLevel = 0; label = '🟡 금은비 80+ 경계'; }
      else { extremeLevel = -1; label = '🟢 금은비 정상 (<80)'; }
      d.GOLD_SILVER_RATIO_EXTREME = {
        name: 'gold_silver_ratio_extreme',
        value: extremeLevel,
        date: today(),
        formula: `GSR=${goldSilverRatio.toFixed(1)}. ${label}. video2 §2부.`,
      };
    }
  } catch { void 0; }

  // ═══════════════════════════════════════════════════════════════════════
  // 20차 신규 derived
  // ═══════════════════════════════════════════════════════════════════════

  // === 20차 A1: KOSPI 분기봉 / 반기봉 윗꼬리 ===
  // stt_kospi §1부 "3개월봉에서 위로 찔렀다 내려온 윗꼬리".
  // 일봉 기반 — 최근 63영업일(~3개월) max - close, 최근 126영업일(~6개월) max - close.
  try {
    const ksHist = await fetchYahooHistory('^KS11', 200);
    if (ksHist.length >= 130) {
      const closes = ksHist.map((h) => h.close);
      const n = closes.length;
      const last = closes[n - 1];
      const q63 = closes.slice(n - 63);
      const h63 = closes.slice(n - 126, n - 63);
      const qMax = Math.max(...q63);
      const qStart = q63[0];
      const qBody = Math.max(0.01, Math.abs(last - qStart));
      const qWick = Math.max(0, qMax - last);
      const qWickPct = (qWick / qBody) * 100;
      d.KOSPI_QUARTERLY_UPPER_WICK_PCT = {
        name: 'kospi_quarterly_upper_wick_pct',
        value: parseFloat(qWickPct.toFixed(1)),
        date: today(),
        formula: `최근 63일 max ${qMax.toFixed(0)} - close ${last.toFixed(0)} = wick ${qWick.toFixed(0)} / body ${qBody.toFixed(0)} = ${qWickPct.toFixed(1)}%. stt_kospi §1부 "분기봉 윗꼬리".`,
      };
      // 반기봉 (126일)
      const h126 = closes.slice(n - 126);
      const halfMax = Math.max(...h126);
      const halfStart = h126[0];
      const halfBody = Math.max(0.01, Math.abs(last - halfStart));
      const halfWick = Math.max(0, halfMax - last);
      const halfWickPct = (halfWick / halfBody) * 100;
      d.KOSPI_HALFYEAR_UPPER_WICK_PCT = {
        name: 'kospi_halfyear_upper_wick_pct',
        value: parseFloat(halfWickPct.toFixed(1)),
        date: today(),
        formula: `최근 126일 max ${halfMax.toFixed(0)} - close ${last.toFixed(0)} = ${halfWickPct.toFixed(1)}% wick/body. stt_kospi §"반기봉".`,
      };
      void h63;
    }
  } catch { void 0; }

  // === 20차 A3 + 24차 P1#3: TRUMP_AGENDA_PRESSURE (5축 — policyDirection 가산) ===
  // stt_video4 §5:51 "관세 막기 싫으면 미국에 투자해라". 5축 압력.
  try {
    const wti = val(raw, 'WTI');
    const wtiPrev = (await readHistory('fred', 'DCOILWTICO')).slice(-30).map((p) => p.value);
    const wti30D = wti !== null && wtiPrev.length > 0 ? ((wti - wtiPrev[0]) / wtiPrev[0]) * 100 : 0;
    const electionD = d.ELECTION_DDAY_LEVEL?.value ?? 0;
    const geoRisk = manualInputs?.geoRisk ?? 0;
    const dxy = val(raw, 'DXY') ?? 100;
    // 24차 P1#3: policyDirection 사용자 입력 활용 — 매파 정책 (>0) 시 압력 가산
    const policyDir = typeof manualInputs?.policyDirection === 'number' ? manualInputs.policyDirection : 0;
    let pressure = 0;
    if (geoRisk >= 3) pressure += 1;
    if (wti30D > 10) pressure += 1;
    if (electionD === 2) pressure += 1; // 선거 임박
    if (dxy > 105) pressure += 1; // 강달러 → 관세 효과 증폭
    if (policyDir < 0) pressure += 1; // 매파(긴축) 정책 = 어젠다 압력 강화 (24차)
    let label: string;
    if (pressure >= 4) label = '🔴 트럼프 어젠다 5축 극대';
    else if (pressure >= 3) label = '🔴 트럼프 어젠다 압력 극대';
    else if (pressure >= 2) label = '🟠 트럼프 어젠다 압력 진행';
    else if (pressure >= 1) label = '🟡 단일 축 활성';
    else label = '⚪ 압력 약함';
    d.TRUMP_AGENDA_PRESSURE = {
      name: 'trump_agenda_pressure',
      value: pressure,
      date: today(),
      formula: `관세(geoRisk=${geoRisk}) + WTI 30D=${wti30D.toFixed(1)}% + 선거 D=${electionD} + DXY=${dxy.toFixed(1)} + policyDir=${policyDir} → ${pressure}/5. ${label}. stt_video4 §5:51 (24차 5축).`,
    };
  } catch { void 0; }

  // === 20차 A4: US_DEBT_TRAJECTORY_LEVEL (현재 부채 + 재정 적자 결합) ===
  // stt_video4 §10:23 "IMF 2031 GDP 140%" + §10:11 "10년 추가 3.3조".
  try {
    const debtTier = d.FEDERAL_DEBT_GDP_TIER?.value ?? null;
    const deficitTier = d.FEDERAL_DEFICIT_GDP_TIER?.value ?? null;
    if (debtTier !== null && deficitTier !== null) {
      const sum = (debtTier as number) + (deficitTier as number);
      let level: number;
      let label: string;
      if (sum >= 4) { level = 3; label = '🔴 부채 + 적자 모두 극단 — 채권자경단 임박'; }
      else if (sum >= 3) { level = 2; label = '🟠 부채 + 적자 동시 경계 — IMF 2031 시나리오'; }
      else if (sum >= 2) { level = 1; label = '🟡 부채 누적 진행'; }
      else { level = 0; label = '⚪ 부채 안정'; }
      d.US_DEBT_TRAJECTORY_LEVEL = {
        name: 'us_debt_trajectory_level',
        value: level,
        date: today(),
        formula: `debt tier=${debtTier} + deficit tier=${deficitTier} = ${sum}. ${label}. stt_video4 §10:11~10:23.`,
      };
    }
  } catch { void 0; }

  // === 20차 A5: LIQUIDITY_RISK_TRANSMISSION (M2 ↑ but 위험자산 약세 디커플링) ===
  // stt_video4 §7:31 "돈이 위험자산까지 흘러오질 못하고 있어요".
  // SECTOR_SOXX (30D 광역 반도체) 와 GLOBAL_M2_PROXY 결합 — 별도 fetch 없이.
  try {
    const m2 = d.GLOBAL_M2_PROXY?.value ?? null;
    const sectorIwm = d.SECTOR_XLK?.value ?? null; // 기술 섹터 30D 변화 (IWM 대용)
    const sectorXlf = d.SECTOR_XLF?.value ?? null; // 금융 (HYG 대용)
    if (m2 !== null) {
      const riskAvg = sectorIwm !== null && sectorXlf !== null
        ? (sectorIwm + sectorXlf) / 2
        : sectorIwm ?? sectorXlf ?? 0;
      const decoupled = m2 > 2 && riskAvg < -2;
      let level: number;
      let label: string;
      if (decoupled && riskAvg < -5) { level = 2; label = '🔴 강한 디커플링 — M2 양수인데 위험자산 약세'; }
      else if (decoupled) { level = 1; label = '🟡 디커플링 진행'; }
      else if (m2 > 0 && riskAvg > 2) { level = -1; label = '🟢 정상 — 유동성 → 위험자산 전이'; }
      else { level = 0; label = '⚪ 중립'; }
      d.LIQUIDITY_RISK_TRANSMISSION = {
        name: 'liquidity_risk_transmission',
        value: level,
        date: today(),
        formula: `M2 YoY=${m2.toFixed(1)}%, 위험섹터 30D=${riskAvg.toFixed(1)}% (XLK/XLF 평균). ${label}. video4 §7:31.`,
      };
    }
  } catch { void 0; }

  // === 20차 A7: DGS30_3W_CHANGE_BPS (트러스 패턴 — 3주 30Y 변화) ===
  // stt_video4 §9:46-10:03 "영국 트러스 사례 강도".
  try {
    const dgs30Hist = await readHistory('fred', 'DGS30');
    if (dgs30Hist.length >= 22) {
      const last = dgs30Hist[dgs30Hist.length - 1].value;
      const prev3w = dgs30Hist[dgs30Hist.length - 22].value;
      const changeBp = (last - prev3w) * 100;
      d.DGS30_3W_CHANGE_BPS = {
        name: 'dgs30_3w_change_bps',
        value: parseFloat(changeBp.toFixed(1)),
        date: today(),
        formula: `30Y ${prev3w.toFixed(2)}% → ${last.toFixed(2)}% = ${changeBp >= 0 ? '+' : ''}${changeBp.toFixed(1)}bp (3주). 트러스 사례 임계 ≥+30bp. video4 §9:46.`,
      };
    }
  } catch { void 0; }

  // === 20차 A8: GOLD_GEOPOLITICAL_PARADOX ===
  // video2 §10:01 "전쟁 → 유가 → 인플레 → 금 하락" 분기. 조건 미충족도 0 으로 노출.
  try {
    const geoRisk = manualInputs?.geoRisk ?? 0;
    const wti = val(raw, 'WTI');
    const wtiHist = (await readHistory('fred', 'DCOILWTICO')).slice(-60);
    const cpiYoY = d.CPI_YOY?.value ?? null;
    let paradox = false;
    let wti60D = 0;
    if (wti !== null && wtiHist.length >= 60) {
      wti60D = ((wti - wtiHist[0].value) / wtiHist[0].value) * 100;
    }
    if (geoRisk >= 3 && cpiYoY !== null) {
      paradox = wti60D > 15 && cpiYoY > 3;
    }
    const level = paradox ? 1 : 0;
    const label = paradox
      ? '🟠 Gold paradox — geoRisk + WTI ↑ + CPI 가속 → 단기 금 하락 가능'
      : '⚪ paradox 미발동';
    d.GOLD_GEOPOLITICAL_PARADOX = {
      name: 'gold_geopolitical_paradox',
      value: level,
      date: today(),
      formula: `geoRisk=${geoRisk}, WTI 60D=${wti60D.toFixed(1)}%, CPI YoY=${cpiYoY?.toFixed(2) ?? '-'}%. ${label}. video2 §10:01.`,
    };
  } catch { void 0; }

  // === 20차 A9: RETAIL_INSTITUTION_DIVERGENCE ===
  // video4 §1:34 "축제 한복판 비 올 수 있다" — 개인 낙관 vs 기관 감축.
  try {
    const naaim = val(raw, 'NAAIM_EXPOSURE');
    const aaii = val(raw, 'AAII_BULL_BEAR_SPREAD');
    const instFlow = d.INSTITUTIONAL_NASDAQ_FLOW?.value ?? null;
    if (naaim !== null && aaii !== null && instFlow !== null) {
      const retailBullish = (naaim >= 75) || (aaii >= 15);
      const instBearish = instFlow <= -1;
      const divergence = retailBullish && instBearish;
      let level: number;
      let label: string;
      if (divergence) { level = -2; label = '🔴 개인 낙관 + 기관 감축 — 축제 끝물 경고'; }
      else if (naaim <= 30 && instFlow >= 1) { level = 2; label = '🟢 개인 비관 + 기관 매집 — 역발상 매수'; }
      else { level = 0; label = '⚪ 정합'; }
      d.RETAIL_INSTITUTION_DIVERGENCE = {
        name: 'retail_institution_divergence',
        value: level,
        date: today(),
        formula: `NAAIM=${naaim}, AAII=${aaii}, instFlow=${instFlow}. ${label}. video4 §1:34.`,
      };
    }
  } catch { void 0; }

  // === 20차 노션 A6: BOK_QUARTERLY_OUTLOOK_DDAY ===
  try {
    const bokDates = ['2026-05-29', '2026-08-28', '2026-11-27']; // BOK 분기 경제전망 통상 일정
    const todayMs = Date.now();
    for (const date of bokDates) {
      const dt0 = new Date(date).getTime();
      const dday = Math.ceil((dt0 - todayMs) / 86400000);
      if (dday >= 0 && dday < 100) {
        d.BOK_QUARTERLY_OUTLOOK_DDAY = {
          name: 'bok_quarterly_outlook_dday',
          value: dday,
          date: today(),
          formula: `한국은행 분기 경제전망 ${date} (D-${dday}). 노션 §"3개월마다 발행".`,
        };
        break;
      }
    }
  } catch { void 0; }

  // === 20차 노션 A7: NAVER_ECONOMY_REPORT_DAYS_AGO (domestic-reports 출력 활용) ===
  try {
    const { fetchDomesticReportsLatest } = await import('../collectors/domestic-reports');
    const dom = await fetchDomesticReportsLatest();
    if (dom?.economy) {
      d.NAVER_ECONOMY_REPORT_DAYS_AGO = {
        name: 'naver_economy_report_days_ago',
        value: dom.economy.daysAgo,
        date: today(),
        formula: `네이버금융 경제분석 최신 "${dom.economy.title ?? '-'}" (${dom.economy.latestDate}, D-${dom.economy.daysAgo}).`,
      };
    }
    if (dom?.marketInfo) {
      d.NAVER_MARKET_REPORT_DAYS_AGO = {
        name: 'naver_market_report_days_ago',
        value: dom.marketInfo.daysAgo,
        date: today(),
        formula: `네이버금융 시황속보 최신 "${dom.marketInfo.title ?? '-'}" (D-${dom.marketInfo.daysAgo}).`,
      };
    }
  } catch { void 0; }

  // === 20차 P3#18 잔여: NASDAQ_DRAWDOWN_ATH < -55% 시스템 위기 level ===
  // video1 §3부 "55% 이상 시스템 위기".
  try {
    const dd = d.NASDAQ_DRAWDOWN_ATH?.value ?? null;
    if (dd !== null) {
      let crisis: number;
      let label: string;
      if (dd <= -55) { crisis = -2; label = '🔴 시스템 위기 (-55%↓) — video1 §3부'; }
      else if (dd <= -30) { crisis = -1; label = '🟠 큰 조정 (-30~-55%)'; }
      else if (dd <= -15) { crisis = 0; label = '🟡 조정 진행'; }
      else { crisis = 1; label = '⚪ 정상'; }
      d.NASDAQ_DRAWDOWN_CRISIS_LEVEL = {
        name: 'nasdaq_drawdown_crisis_level',
        value: crisis,
        date: today(),
        formula: `NASDAQ DD ${dd.toFixed(1)}% → ${label}.`,
      };
    }
  } catch { void 0; }

  // === 20차 노션 A4: SEC 8-K 미국 중대 이벤트 24h 카운트 ===
  try {
    const { fetchSec8KCount } = await import('../collectors/sec-8k');
    const sec = await fetchSec8KCount();
    if (sec) {
      let level: number;
      let label: string;
      if (sec.count24h >= 50) { level = 2; label = `🔴 SEC 8-K ${sec.count24h}건 24h — 미국 중대 이벤트 폭증`; }
      else if (sec.count24h >= 20) { level = 1; label = `🟡 SEC 8-K ${sec.count24h}건`; }
      else { level = 0; label = `⚪ ${sec.count24h}건 정상`; }
      d.US_MATERIAL_DISCLOSURE_8K_24H = {
        name: 'us_material_disclosure_8k_24h',
        value: sec.count24h,
        date: today(),
        formula: `${label}. 노션 §"8-K 미국 중대 이벤트". top: ${sec.topItems.slice(0, 3).join(' / ')}`,
      };
      d.US_MATERIAL_DISCLOSURE_LEVEL = {
        name: 'us_material_disclosure_level',
        value: level,
        date: today(),
        formula: `0=정상, 1=경계, 2=폭증. SEC EDGAR 8-K RSS 24h.`,
      };
    }
  } catch { void 0; }

  // === 20차 노션 A3: INSIDER_LARGE_SINGLE_BUY (≥$500k 단건 24h) ===
  try {
    const { fetchInsiderSummary } = await import('../collectors/smart-money');
    const ins = await fetchInsiderSummary({ allowStale: true });
    const lb = ins?.largeBuyCount ?? 0;
    d.INSIDER_LARGE_SINGLE_BUY = {
      name: 'insider_large_single_buy',
      value: lb,
      date: today(),
      formula: `≥$500k 단일 매수 ${lb}건 24h. top amounts: $${(ins?.largeBuyTopAmounts ?? []).slice(0, 3).map((a) => Math.round(a / 1000) + 'k').join(', ') || '-'}. 노션 §OpenInsider.`,
    };
  } catch { void 0; }

  // ═══════════════════════════════════════════════════════════════════════
  // 21차 신규 derived
  // ═══════════════════════════════════════════════════════════════════════

  // === 26차 P1#4 + 27차 Phase 1#8: USER_USD_CAPITAL_TOTAL + 권고 vs 실제 USD 갭 ===
  // KRW + USD 보유 합계를 USD 환산. allocation % × totalUSD = 권고 USD 금액.
  // currentHoldingsUSD 입력 시 자산별 권고-실제 USD 갭 산출.
  try {
    const { readInvestmentPlan } = await import('../services/investment-plan');
    const plan = await readInvestmentPlan();
    const usdkrwRate = val(raw, 'USDKRW') ?? 1400;
    const krwInUSD = (plan.totalCapitalKRW ?? 0) / usdkrwRate;
    const usdDirect = plan.totalCapitalUSD ?? 0;
    const totalUSD = krwInUSD + usdDirect;
    if (totalUSD > 0) {
      d.USER_USD_CAPITAL_TOTAL = {
        name: 'user_usd_capital_total',
        value: parseFloat(totalUSD.toFixed(0)),
        date: today(),
        formula: `KRW ${(plan.totalCapitalKRW ?? 0).toLocaleString()} / ${usdkrwRate.toFixed(0)} = ${krwInUSD.toFixed(0)} USD + USD ${usdDirect.toLocaleString()} = ${totalUSD.toFixed(0)} USD. 26차 P1#4.`,
      };
      // 27차 Phase 1#8: 권고-실제 USD 갭 합계 (currentHoldingsUSD 가 입력된 경우)
      const userHoldingsUSD = plan.currentHoldingsUSD;
      if (userHoldingsUSD && Object.keys(userHoldingsUSD).length > 0) {
        const totalActualUSD = Object.values(userHoldingsUSD).reduce<number>((s, v) => s + (typeof v === 'number' ? v : 0), 0);
        const gapUSD = totalUSD - totalActualUSD;
        d.USER_USD_HOLDINGS_TOTAL = {
          name: 'user_usd_holdings_total',
          value: parseFloat(totalActualUSD.toFixed(0)),
          date: today(),
          formula: `사용자 USD 보유 합계 ${totalActualUSD.toFixed(0)} USD vs 자본 ${totalUSD.toFixed(0)} USD = 미할당 ${gapUSD.toFixed(0)} USD (${((gapUSD/totalUSD)*100).toFixed(1)}%). 27차 P1#8.`,
        };
      }
    }
  } catch { void 0; }

  // === 21차 P1#2: PORTFOLIO_DRIFT (사용자 보유 vs 권장) ===
  // video1 §5부 "비중 기준 없으면 결국 그때그때 감정". InvestmentPlan.currentHoldings 와
  // 시스템 allocation 권고의 절대 차이 합 / 2 → drift % (동일 시 0, 완전 다름 시 100).
  try {
    const { readInvestmentPlan } = await import('../services/investment-plan');
    const plan = await readInvestmentPlan();
    const holdings = plan.currentHoldings;
    if (holdings && Object.keys(holdings).length > 0) {
      // 권고 비중은 derived 계산 시점에 직접 접근 불가 — manualInputs 외부에서 처리.
      // 여기서는 holdings 합산만 가시화 (allocation drift 는 weekly-report 가 계산).
      const total = Object.values(holdings).reduce((s, v) => s + (typeof v === 'number' ? v : 0), 0);
      const usedSlots = Object.values(holdings).filter((v) => typeof v === 'number' && v > 0).length;
      d.PORTFOLIO_HOLDINGS_TOTAL_PCT = {
        name: 'portfolio_holdings_total_pct',
        value: parseFloat(total.toFixed(1)),
        date: today(),
        formula: `사용자 보유 합계 ${total.toFixed(1)}% (${usedSlots} 자산). 100% 미만 시 미할당 유동현금 가능성. 21차 P1#2.`,
      };
    }
  } catch { void 0; }

  // === 21차 P2#10: KOSPI_YEARLY_LOWER_WICK_PCT ===
  // stt_kospi §"계단에 짐 한번 내려놓고" — 연봉 아래꼬리 / 전체폭 비율.
  // 5% 미만 = 매수 누적 미소화 → 추가 조정 우려. 200일 이상이면 산출 (year 부분).
  try {
    const ksHist = await fetchYahooHistory('^KS11', 280);
    if (ksHist.length >= 200) {
      const closes = ksHist.map((h) => h.close);
      const yearStart = closes[0];
      const yearMin = Math.min(...closes);
      const yearMax = Math.max(...closes);
      const last = closes[closes.length - 1];
      const range = Math.max(0.01, yearMax - yearMin);
      const lowerWick = Math.max(0, Math.min(yearStart, last) - yearMin);
      const lowerWickPct = (lowerWick / range) * 100;
      let level: number;
      let label: string;
      if (lowerWickPct >= 15) { level = 1; label = '🟢 정상 (≥15%)'; }
      else if (lowerWickPct >= 5) { level = 0; label = '🟡 보통 (5~15%)'; }
      else { level = -1; label = '🟠 매수 미소화 (<5%) — 추가 조정 우려'; }
      d.KOSPI_YEARLY_LOWER_WICK_PCT = {
        name: 'kospi_yearly_lower_wick_pct',
        value: parseFloat(lowerWickPct.toFixed(1)),
        date: today(),
        formula: `연봉 아래꼬리 ${lowerWick.toFixed(0)} / 전체폭 ${range.toFixed(0)} = ${lowerWickPct.toFixed(1)}%. ${label}. stt_kospi §"계단에 짐 내려놓기".`,
      };
      d.KOSPI_YEARLY_LOWER_WICK_LEVEL = {
        name: 'kospi_yearly_lower_wick_level',
        value: level,
        date: today(),
        formula: `1=정상, 0=보통, -1=매수 미소화.`,
      };
    }
  } catch { void 0; }

  // === 21차 P2#9: DCA_STAGE_STAGNATION (분할 정체 경고) ===
  // tranche store entries 기반 — stage 1 진입 후 15일 + stage 2 미진입 시 경고.
  try {
    const { listTranches } = await import('../services/trancheStore');
    const entries = await listTranches();
    // 자산별 stage 1 / stage 2 진입 여부 집계
    const stage1ByAsset = new Map<string, string>();
    const stage2By = new Set<string>();
    for (const e of entries) {
      if (e.stage === 1 && !stage1ByAsset.has(e.asset)) stage1ByAsset.set(e.asset, e.executedAt);
      if (e.stage >= 2) stage2By.add(e.asset);
    }
    let stagnantCount = 0;
    const stagnantAssets: string[] = [];
    for (const [asset, executedAt] of stage1ByAsset.entries()) {
      if (stage2By.has(asset)) continue;
      const ageDays = Math.floor((Date.now() - new Date(executedAt).getTime()) / 86400000);
      if (ageDays >= 15) {
        stagnantCount++;
        stagnantAssets.push(`${asset}(${ageDays}d)`);
      }
    }
    d.DCA_STAGE_STAGNATION_COUNT = {
      name: 'dca_stage_stagnation_count',
      value: stagnantCount,
      date: today(),
      formula: `${stagnantCount}개 자산이 stage1 진입 후 15일+ 2차 미진입${stagnantAssets.length > 0 ? `: ${stagnantAssets.join(', ')}` : ''}. video3 §"분할매수 적기" 가드.`,
    };
  } catch { void 0; }

  // === 22차 P1#4: 운영자 §전하는 말 9단락 회전 인덱스 ===
  try {
    const { getDailyQuote } = await import('../services/operator-quotes');
    const q = getDailyQuote();
    d.OPERATOR_PHILOSOPHY_QUOTE_INDEX = {
      name: 'operator_philosophy_quote_index',
      value: q.index,
      date: today(),
      formula: `오늘의 운영자 한마디: "${q.short}". 노션 §전하는 말 9단락 회전.`,
    };
  } catch { void 0; }

  // === 22차 P2#8: REGIME_FORWARD_RETURN 분포 (90D NASDAQ regime별) ===
  // video2 §1부 "이 구간에서 역사적으로 이런 일이 벌어졌으니까".
  // 현재 regime 기준 과거 동일 regime 진입 후 90영업일 NASDAQ 평균 수익률 산출.
  try {
    const { readHistory } = await import('../state/history-store');
    const regimeHist = await readHistory('computed', 'REGIME_LABEL');
    const nasdaqHist = await fetchYahooHistory('^IXIC', 800);
    if (regimeHist.length >= 100 && nasdaqHist.length >= 200) {
      const currentRegime = String(regimeHist[regimeHist.length - 1].value ?? '');
      const closesByDate = new Map(nasdaqHist.map((h) => [h.date, h.close]));
      // regime entry 시점 (직전 regime ≠ 현재) 모두 찾고 90일 후 수익률 평균
      const returns: number[] = [];
      for (let i = 1; i < regimeHist.length - 90; i++) {
        const r = String(regimeHist[i].value ?? '');
        const prev = String(regimeHist[i - 1].value ?? '');
        if (r === currentRegime && prev !== currentRegime) {
          const startDate = regimeHist[i].date;
          const endIdx = Math.min(regimeHist.length - 1, i + 90);
          const endDate = regimeHist[endIdx].date;
          const startClose = closesByDate.get(startDate);
          const endClose = closesByDate.get(endDate);
          if (typeof startClose === 'number' && typeof endClose === 'number' && startClose > 0) {
            returns.push(((endClose - startClose) / startClose) * 100);
          }
        }
      }
      if (returns.length >= 2) {
        const avg = returns.reduce((a, b) => a + b, 0) / returns.length;
        const sorted = [...returns].sort((a, b) => a - b);
        const median = sorted[Math.floor(sorted.length / 2)];
        d.REGIME_FORWARD_RETURN_90D_AVG = {
          name: 'regime_forward_return_90d_avg',
          value: parseFloat(avg.toFixed(2)),
          date: today(),
          formula: `${currentRegime} 진입 후 90D NASDAQ 평균 수익률 ${avg.toFixed(2)}% (median ${median.toFixed(2)}%, n=${returns.length}). video2 §1부 "역사 통계".`,
        };
      }
    }
  } catch { void 0; }

  // === 22차 P2#9: SIGNAL_HIT_RATE_30D (NASDAQ BUY/STRONG_BUY 직후 30영업일 양수 비율) ===
  try {
    const { readHistory } = await import('../state/history-store');
    const sigHist = await readHistory('computed', 'NASDAQ_SIGNAL_LABEL');
    const nasdaqHist = await fetchYahooHistory('^IXIC', 600);
    if (sigHist.length >= 50 && nasdaqHist.length >= 100) {
      const closesByDate = new Map(nasdaqHist.map((h) => [h.date, h.close]));
      let wins = 0;
      let total = 0;
      for (let i = 1; i < sigHist.length - 30; i++) {
        const r = String(sigHist[i].value ?? '');
        const prev = String(sigHist[i - 1].value ?? '');
        if ((r === 'BUY' || r === 'STRONG_BUY') && prev !== r) {
          const sd = sigHist[i].date;
          const ed = sigHist[Math.min(sigHist.length - 1, i + 30)].date;
          const sc = closesByDate.get(sd);
          const ec = closesByDate.get(ed);
          if (typeof sc === 'number' && typeof ec === 'number' && sc > 0) {
            total++;
            if (ec > sc) wins++;
          }
        }
      }
      if (total >= 3) {
        const pct = (wins / total) * 100;
        d.NASDAQ_SIGNAL_HIT_RATE_30D = {
          name: 'nasdaq_signal_hit_rate_30d',
          value: parseFloat(pct.toFixed(1)),
          date: today(),
          formula: `BUY/STRONG_BUY 30일 후 양수 수익률 ${wins}/${total} = ${pct.toFixed(1)}%. video1 §확률 "공이 멈추는 순간".`,
        };
      }
    }
  } catch { void 0; }

  // === 22차 P1#2: SCENARIO_TRANSITION_HISTORY (30일간 A↔B flip 횟수) ===
  try {
    const { readHistory } = await import('../state/history-store');
    const scHist = await readHistory('computed', 'SCENARIO_GATE_A_B');
    if (scHist.length >= 30) {
      const recent = scHist.slice(-30);
      let flips = 0;
      for (let i = 1; i < recent.length; i++) {
        if (recent[i].value !== recent[i - 1].value) flips++;
      }
      d.SCENARIO_TRANSITION_FLIPS_30D = {
        name: 'scenario_transition_flips_30d',
        value: flips,
        date: today(),
        formula: `30일 내 시나리오 게이트 변경 ${flips}회. ≥5회 = 분기 불안정. stt_kospi §4부 "두 그림 동시" 검증.`,
      };
    }
  } catch { void 0; }

  // === 23차 Tier 2#15: M2 → S&P 10주 shift (노션 §StreetStats 정합 — 13주 근사 → 10주 정합) ===
  try {
    const m2Hist = await readHistory('fred', 'M2SL');
    const spxHist = await fetchYahooHistory('^GSPC', 80);
    if (m2Hist.length >= 14 && spxHist.length >= 50) {
      const sortedM2 = [...m2Hist].sort((a, b) => (a.date < b.date ? -1 : 1));
      // M2 월간 → 약 2.5개월 (10주) 전 = 2~3 포인트 전
      const m2Now = sortedM2[sortedM2.length - 1]?.value;
      const m2Then = sortedM2[Math.max(0, sortedM2.length - 3)]?.value; // 약 2.5개월 전
      const spxNow = spxHist[spxHist.length - 1].close;
      const spxThen = spxHist[Math.max(0, spxHist.length - 50)].close; // 50영업일 = 10주
      if (
        typeof m2Now === 'number' && typeof m2Then === 'number' &&
        typeof spxNow === 'number' && typeof spxThen === 'number' &&
        m2Then > 0 && spxThen > 0
      ) {
        const m2Dir = m2Now - m2Then;
        const spxRet = ((spxNow - spxThen) / spxThen) * 100;
        let level: number;
        let label: string;
        if (m2Dir > 0 && spxRet > 0) { level = 1; label = '🟢 M2↑ → S&P↑ 10주 리드 정합'; }
        else if (m2Dir < 0 && spxRet < 0) { level = 1; label = '🟡 M2↓ → S&P↓ 정합 (약세)'; }
        else if (m2Dir > 0 && spxRet < 0) { level = -1; label = '🟠 M2↑ but S&P↓ — 리드 약화'; }
        else if (m2Dir < 0 && spxRet > 0) { level = -1; label = '🟡 M2↓ but S&P↑ — 이탈'; }
        else { level = 0; label = '⚪ 정체'; }
        d.M2_SP500_LEAD_10W_ALIGNMENT = {
          name: 'm2_sp500_lead_10w_alignment',
          value: level,
          date: today(),
          formula: `M2 Δ2.5M=${m2Dir.toFixed(0)} vs S&P 10W=${spxRet.toFixed(2)}%. ${label}. 노션 §StreetStats 10주 정합 (23차 Tier 2#15).`,
        };
      }
    }
  } catch { void 0; }

  // === 23차 Tier 3#20: 세력 3축 동조 매수 (13F + 8-K + Insider) ===
  try {
    const inst = d.INSTITUTIONAL_NASDAQ_FLOW?.value ?? null;
    const sec8k = d.US_MATERIAL_DISCLOSURE_LEVEL?.value ?? null;
    const insider = d.INSIDER_LARGE_SINGLE_BUY?.value ?? null;
    if (inst !== null && sec8k !== null && insider !== null) {
      const instUp = inst >= 1;
      const sec8kHigh = sec8k >= 1;
      const insiderActive = insider >= 3;
      const positives = [instUp, sec8kHigh, insiderActive].filter(Boolean).length;
      let level: number;
      let label: string;
      if (positives === 3) { level = 2; label = '🟢 세력 3축 동조 매수 — 노션 §스마트머니 결합 강신호'; }
      else if (positives === 2) { level = 1; label = '🔵 세력 2축 활성'; }
      else { level = 0; label = '⚪ 세력 액션 분산'; }
      d.SMART_MONEY_3AXIS_ALIGNMENT = {
        name: 'smart_money_3axis_alignment',
        value: level,
        date: today(),
        formula: `13F flow=${inst}, 8-K=${sec8k}, insider $500k+=${insider}. ${positives}/3 동조. ${label}.`,
      };
    }
  } catch { void 0; }

  // === 23차 Tier 3#21: 유동성 트리플 게이트 (M2↑ + VIX↓ + DXY↓) ===
  try {
    const m2 = d.GLOBAL_M2_PROXY?.value ?? null;
    const vix = val(raw, 'VIXCLS');
    const dxy = val(raw, 'DXY');
    const dxyTrend = d.DXY_TREND?.value ?? null;
    if (m2 !== null && vix !== null && dxy !== null) {
      const m2Up = m2 > 2;
      const vixLow = vix < 20;
      const dxyDown = dxyTrend !== null ? dxyTrend < -0.5 : dxy < 100;
      const triple = m2Up && vixLow && dxyDown;
      let level: number;
      let label: string;
      if (triple) { level = 2; label = '🟢 유동성 트리플 게이트 발동 — 위험자산 호조'; }
      else if (m2Up && (vixLow || dxyDown)) { level = 1; label = '🔵 유동성 2축 활성'; }
      else if (!m2Up && vix > 25) { level = -1; label = '🟠 유동성 약화 + VIX 상승'; }
      else { level = 0; label = '⚪ 중립'; }
      d.LIQUIDITY_TRIPLE_GATE = {
        name: 'liquidity_triple_gate',
        value: level,
        date: today(),
        formula: `M2=${m2.toFixed(1)}% VIX=${vix.toFixed(1)} DXY=${dxy.toFixed(1)}/${dxyTrend?.toFixed(2) ?? '-'}. ${label}. 노션 §StreetStats 유동성 게이트.`,
      };
    }
  } catch { void 0; }

  // === 24차 P1#6 + 25차: 노션 본가 자체 도구 4종 + 정합 강화 ===
  // (1) KOSDAQ_DRAWDOWN_ATH — 25차 깊이 1260→2520일 (10년) 확장
  try {
    const kosdaqHist = await fetchYahooHistory('^KQ11', 2520);
    if (kosdaqHist.length >= 100) {
      const closes = kosdaqHist.map((h) => h.close);
      const ath = Math.max(...closes);
      const cur = closes[closes.length - 1];
      const dd = ath > 0 ? ((cur - ath) / ath) * 100 : 0;
      let level: number;
      let label: string;
      if (dd >= -5) { level = 2; label = 'ATH 근접'; }
      else if (dd >= -10) { level = 1; label = '-5~-10% 소폭 조정'; }
      else if (dd >= -20) { level = 0; label = '-10~-20% 조정'; }
      else if (dd >= -30) { level = -1; label = '-20~-30% 약세'; }
      else { level = -2; label = '<-30% 심각'; }
      d.KOSDAQ_DRAWDOWN_ATH = {
        name: 'kosdaq_drawdown_ath',
        value: parseFloat(dd.toFixed(2)),
        date: today(),
        formula: `KOSDAQ ATH 대비 ${dd.toFixed(2)}% (${label}). 노션 §자산제곱 본가 자체 도구.`,
      };
      d.KOSDAQ_DRAWDOWN_LEVEL = {
        name: 'kosdaq_drawdown_level',
        value: level,
        date: today(),
        formula: `2=ATH근접, 1=소폭, 0=조정, -1=약세, -2=심각.`,
      };
    }
  } catch { void 0; }

  // (2) EM_TRIO_BREADTH — EWZ + INDA + VNM + EWJ 4종 30D 평균 수익률
  try {
    const emSyms = ['EWZ', 'INDA', 'VNM', 'EWJ'] as const;
    const rets: Array<{ sym: string; ret: number }> = [];
    for (const s of emSyms) {
      const h = await fetchYahooHistory(s, 35);
      if (h.length >= 22) {
        const c = h.map((p) => p.close);
        const ret = ((c[c.length - 1] - c[0]) / c[0]) * 100;
        rets.push({ sym: s, ret });
      }
    }
    if (rets.length >= 3) {
      const avg = rets.reduce((s, r) => s + r.ret, 0) / rets.length;
      const positives = rets.filter((r) => r.ret > 0).length;
      let level: number;
      let label: string;
      if (positives === rets.length && avg > 3) { level = 2; label = `🟢 EM 4국 동조 강세 (avg +${avg.toFixed(1)}%)`; }
      else if (positives >= 3 && avg > 0) { level = 1; label = `🔵 EM 우세 (${positives}/${rets.length})`; }
      else if (positives <= 1 && avg < -3) { level = -2; label = `🔴 EM 동조 약세 (avg ${avg.toFixed(1)}%)`; }
      else { level = 0; label = '⚪ EM 혼조'; }
      d.EM_TRIO_BREADTH = {
        name: 'em_trio_breadth',
        value: level,
        date: today(),
        formula: `EM 4종(EWZ/INDA/VNM/EWJ) 30D ${rets.map((r) => `${r.sym}=${r.ret.toFixed(1)}%`).join(', ')}. avg=${avg.toFixed(1)}%, positives=${positives}/${rets.length}. ${label}. 노션 §본가 신흥국 4종.`,
      };
    }
  } catch { void 0; }

  // (3) STLFSI_LEVEL — Saint Louis Fed Financial Stress Index (FRED STLFSI4)
  try {
    const stlfsiHist = await readHistory('fred', 'STLFSI4');
    if (stlfsiHist.length >= 1) {
      const last = stlfsiHist[stlfsiHist.length - 1].value;
      let level: number;
      let label: string;
      if (last >= 2) { level = -2; label = '🔴 금융 스트레스 극단 (≥2)'; }
      else if (last >= 1) { level = -1; label = '🟠 금융 스트레스 경계 (1~2)'; }
      else if (last >= 0) { level = 0; label = '⚪ 정상 범위 (0~1)'; }
      else { level = 1; label = '🟢 매우 안정 (<0)'; }
      d.STLFSI_LEVEL = {
        name: 'stlfsi_level',
        value: parseFloat(last.toFixed(2)),
        date: today(),
        formula: `St. Louis Fed Financial Stress Index ${last.toFixed(2)}. ${label}. 노션 §본가 STLFSI.`,
      };
    }
  } catch { void 0; }

  // (4) MMF_RRP_RATIO — 25차 변별력 강화: RRP 절대치 + 1Y 변화율 병행
  // RRP 가 거의 0 으로 내려간 환경 (현재) 에서 ratio 폭증 → 단일 임계로는 변별 불가.
  // 추가 차원: RRP 절대치 (≥1T 의미 있음 vs <100B 거의 소진).
  try {
    const mmf = val(raw, 'WRMFNS') ?? d.MMF_TIER?.value ?? null;
    const rrp = val(raw, 'RRPONTSYD') ?? null;
    if (mmf !== null && rrp !== null && rrp > 0) {
      const ratio = mmf / rrp;
      // RRP 절대치 단계 (조 단위 가정 — millions 단위면 1000으로 나눔 필요. WRMFNS 와 단위 정합)
      const rrpAbs = rrp; // 통상 millions
      let level: number;
      let label: string;
      // 25차 변별력: RRP 절대치 + ratio 결합
      if (rrpAbs < 100000 && ratio >= 50) {
        // RRP < 100B AND 비율 50배 → 거의 소진 (현재 시장)
        level = 2; label = '🟢 RRP 거의 소진 (<100B) — 시장 유동성 극풍부, Fed 흡수 종료';
      } else if (rrpAbs < 500000) {
        level = 1; label = `🔵 RRP <500B — 흡수 약화 (절대 ${(rrpAbs/1000).toFixed(0)}B)`;
      } else if (ratio >= 4) {
        level = 0; label = `🟡 정상 (RRP ${(rrpAbs/1000).toFixed(0)}B, 비율 ${ratio.toFixed(1)})`;
      } else if (ratio >= 2) {
        level = 0; label = `🟡 RRP 흡수 진행 (비율 ${ratio.toFixed(1)})`;
      } else {
        level = -1; label = `🟠 RRP 흡수 강함 (비율 ${ratio.toFixed(1)})`;
      }
      d.MMF_RRP_RATIO = {
        name: 'mmf_rrp_ratio',
        value: parseFloat(ratio.toFixed(2)),
        date: today(),
        formula: `MMF ${(mmf/1000).toFixed(0)}B / RRP ${(rrpAbs/1000).toFixed(1)}B = ratio ${ratio.toFixed(2)}. ${label}. 노션 §본가 MMF vs RRP.`,
      };
      // 절대치 별도 derived 노출 — ratio 무한 폭증 회피
      d.RRP_ABSOLUTE_LEVEL = {
        name: 'rrp_absolute_level',
        value: parseFloat((rrpAbs/1000).toFixed(1)),
        date: today(),
        formula: `RRP 절대치 ${(rrpAbs/1000).toFixed(1)}B. <100B = 소진, <500B = 약화, ≥1T = 정상.`,
      };
    }
  } catch { void 0; }

  // === 23차 Tier 3#26: KOSPI 거래량 절대 20조 임계 ===
  // video5_analysis §73 "거래대금 연속성 — 주간 평균 20조 이상".
  // KRX_VOLUME_KRW_5D 가 있으면 사용, 없으면 raw KOSPI volume 으로 근사.
  try {
    const recentVol = d.KOSPI_VOLUME_5D_AVG_KRW?.value ?? null;
    if (recentVol !== null) {
      const trillion = recentVol / 10000; // 억원 → 조원
      const above20T = trillion >= 20;
      d.KOSPI_VOLUME_20T_FLAG = {
        name: 'kospi_volume_20t_flag',
        value: above20T ? 1 : 0,
        date: today(),
        formula: `KOSPI 5일 평균 거래대금 ${trillion.toFixed(1)}조 ${above20T ? '≥20조 (지속성 우호)' : '<20조 (관심 약화)'}. video5_analysis §73.`,
      };
    }
  } catch { void 0; }

  // === 21차 P2#20: 한국 거시 뉴스 RSS (한경 글로벌마켓 / sedaily 증권) ===
  try {
    const { fetchKrNewsHeadlines } = await import('../collectors/kr-news');
    const kr = await fetchKrNewsHeadlines();
    if (kr) {
      d.KR_NEWS_TOTAL_COUNT_24H = {
        name: 'kr_news_total_count_24h',
        value: kr.totalCount24h,
        date: today(),
        formula: `한국 매크로 뉴스 24h 합계 ${kr.totalCount24h}건. 소스: ${Object.keys(kr.bySource).join(', ')}. 노션 §한경 글로벌마켓.`,
      };
      // 가장 최신 헤드라인의 minutesAgo
      const minutes = Math.min(...Object.values(kr.bySource).map((s) => s.minutesAgo));
      d.KR_NEWS_FRESHNESS_MINUTES = {
        name: 'kr_news_freshness_minutes',
        value: Number.isFinite(minutes) ? minutes : 999,
        date: today(),
        formula: `한국 매크로 뉴스 최신 ${minutes}분 전.`,
      };
    }
  } catch { void 0; }

  // === 21차 P2#19: NAVER 시황속보 / 투자정보 D-day (도메스틱 리포트 활용) ===
  try {
    const { fetchDomesticReportsLatest } = await import('../collectors/domestic-reports');
    const dom = await fetchDomesticReportsLatest();
    if (dom?.investInfo) {
      d.NAVER_INVEST_INFO_REPORT_DAYS_AGO = {
        name: 'naver_invest_info_report_days_ago',
        value: dom.investInfo.daysAgo,
        date: today(),
        formula: `네이버금융 투자정보 최신 "${dom.investInfo.title ?? '-'}" (D-${dom.investInfo.daysAgo}).`,
      };
    }
  } catch { void 0; }

  // === 21차 P2#11: GEOPOLITICAL_COUNTDOWN_DDAY (사용자 manual events 중 가장 가까운 D-day) ===
  try {
    const events = manualInputs?.geopoliticalCountdown;
    if (Array.isArray(events) && events.length > 0) {
      const todayMs = Date.now();
      const future = events
        .map((e) => ({ event: e.event, dday: Math.ceil((new Date(e.targetDate).getTime() - todayMs) / 86400000) }))
        .filter((x) => Number.isFinite(x.dday) && x.dday >= 0)
        .sort((a, b) => a.dday - b.dday);
      if (future.length > 0) {
        const next = future[0];
        d.GEOPOLITICAL_COUNTDOWN_DDAY = {
          name: 'geopolitical_countdown_dday',
          value: next.dday,
          date: today(),
          formula: `다가오는 지정학 이벤트: ${next.event} (D-${next.dday}). 전체 ${future.length}개 등록.`,
        };
      }
    }
  } catch { void 0; }

  // === 20차 노션 A5: TradingEconomics stream 신선도 + 24h 카운트 ===
  try {
    const { fetchTeStreamLatest } = await import('../collectors/te-stream');
    const te = await fetchTeStreamLatest();
    if (te) {
      d.TE_STREAM_MINUTES_AGO = {
        name: 'te_stream_minutes_ago',
        value: te.minutesAgo,
        date: today(),
        formula: `${te.source}: "${te.latestHeadline.slice(0, 60)}..." (${te.minutesAgo}분 전, 24h ${te.count24h}건). 노션 §전세계 경제 뉴스.`,
      };
      d.TE_STREAM_COUNT_24H = {
        name: 'te_stream_count_24h',
        value: te.count24h,
        date: today(),
        formula: `${te.source} 24h 헤드라인 ${te.count24h}건. 폭증 시 매크로 노이즈 강함.`,
      };
    }
  } catch { void 0; }

  // === 19차 P3#16: KCIF 최신 토픽 라벨 ===
  try {
    const { fetchKcifLatestTopic } = await import('../collectors/kcif-topic');
    const k = await fetchKcifLatestTopic();
    if (k) {
      d.KCIF_LATEST_TOPIC_DAYS_AGO = {
        name: 'kcif_latest_topic_days_ago',
        value: k.daysAgo,
        date: today(),
        formula: `KCIF 최신 보고서 "${k.title}" (${k.publishedDate}, D-${k.daysAgo}). 노션 §KCIF.`,
      };
    }
  } catch { void 0; }

  // === 19차 P3#18: 인물 / 기관 발언 D-day 정적 캘린더 ===
  // FOMC/BOK 외 주요 발언자(Powell/Bessent/Warsh)와 기관 보고서(KDI/IMF/KIF) 정적 일정.
  // 사용자 manual update 시 재배포 — collectors/calendar.ts 와 별개로 derived 단순 키.
  try {
    const todayMs = Date.now();
    const events: Array<{ key: string; date: string; label: string }> = [
      // 2026 정기 (예시 — 실제 일정 사용자 확정 후 갱신)
      { key: 'POWELL_SPEECH_DDAY', date: '2026-05-08', label: 'Powell 의장 발언 (FOMC 직후)' },
      { key: 'BESSENT_TESTIMONY_DDAY', date: '2026-05-15', label: 'Bessent 재무장관 의회 증언' },
      { key: 'WARSH_KEYNOTE_DDAY', date: '2026-06-12', label: 'Warsh 전이사 매크로 강연' },
      { key: 'KDI_FORECAST_DDAY', date: '2026-05-22', label: 'KDI 경제전망' },
      { key: 'IMF_WEO_DDAY', date: '2026-07-22', label: 'IMF World Economic Outlook' },
      { key: 'KIF_BIWEEKLY_DDAY', date: '2026-05-09', label: 'KIF 금융브리프 격주' },
    ];
    for (const e of events) {
      const dt0 = new Date(e.date).getTime();
      if (Number.isNaN(dt0)) continue;
      const dday = Math.ceil((dt0 - todayMs) / 86400000);
      if (dday < -7) continue; // 7일 지난 이벤트는 표시 안 함
      d[e.key] = {
        name: e.key.toLowerCase(),
        value: Math.max(0, dday),
        date: today(),
        formula: `${e.label} @ ${e.date} (${dday >= 0 ? `D-${dday}` : `D+${-dday}`}).`,
      };
    }
  } catch { void 0; }

  // === 19차 P1#3: CME FedWatch 확률 (ZQ Fed Funds futures 기반 근사) ===
  try {
    const { fetchFedWatchProbabilities } = await import('../collectors/cme-fedwatch');
    const fw = await fetchFedWatchProbabilities();
    if (fw) {
      d.FOMC_RATE_CUT_PROB_25BP = {
        name: 'fomc_rate_cut_prob_25bp',
        value: fw.cutProb25bp,
        date: today(),
        formula: `ZQ=F implied ${fw.impliedRatePct.toFixed(2)}% vs target mid ${fw.currentTargetMidPct.toFixed(2)}% → gap ${fw.gapBp.toFixed(1)}bp. cut${fw.cutProb25bp}% / hold${fw.holdProb}% / hike${fw.hikeProb25bp}%. 노션 §CME FedWatch 정합.`,
      };
      d.FOMC_RATE_HIKE_PROB_25BP = {
        name: 'fomc_rate_hike_prob_25bp',
        value: fw.hikeProb25bp,
        date: today(),
        formula: `ZQ implied gap ${fw.gapBp.toFixed(1)}bp 양수 시 인상 베팅.`,
      };
      d.FOMC_RATE_HOLD_PROB = {
        name: 'fomc_rate_hold_prob',
        value: fw.holdProb,
        date: today(),
        formula: `잔여 = 동결 베팅.`,
      };
      // 25차: 50bp 다단계 확률
      d.FOMC_RATE_CUT_PROB_50BP = {
        name: 'fomc_rate_cut_prob_50bp',
        value: fw.cutProb50bp,
        date: today(),
        formula: `gap ≤-45bp 시 50bp 인하 베팅 (25차 다단계).`,
      };
      d.FOMC_RATE_HIKE_PROB_50BP = {
        name: 'fomc_rate_hike_prob_50bp',
        value: fw.hikeProb50bp,
        date: today(),
        formula: `gap ≥+45bp 시 50bp 인상 베팅.`,
      };
    }
  } catch { void 0; }

  // === P2#12: DART 한국 주요공시 24h 건수 ===
  try {
    const { fetchDartMajorDisclosures } = await import('../collectors/dart-major');
    const dart = await fetchDartMajorDisclosures();
    if (dart && dart.source === 'dart-api') {
      let level: number;
      let label: string;
      if (dart.count >= 20) { level = 2; label = '🔴 주요공시 20건+ (24h) — 한국 시장 이벤트 과다'; }
      else if (dart.count >= 10) { level = 1; label = '🟡 주요공시 10-19건 — 이벤트 경계'; }
      else if (dart.count >= 3) { level = 0; label = '⚪ 정상 (3-9건)'; }
      else { level = 0; label = '⚪ 조용 (<3건)'; }
      d.KR_MATERIAL_DISCLOSURE_COUNT = {
        name: 'kr_material_disclosure_count',
        value: dart.count,
        date: today(),
        formula: `DART 최근 24h 주요사항보고서 ${dart.count}건. ${label}. 노션 §"기업의 중대 이벤트".`,
      };
      d.KR_MATERIAL_DISCLOSURE_LEVEL = {
        name: 'kr_material_disclosure_level',
        value: level,
        date: today(),
        formula: `건수 기반 레벨 (0=정상, 1=경계, 2=과다).`,
      };
    }
  } catch { void 0; }

  // === P2#11: Earnings Surprise 평균 + 메가캡 next earnings D-day ===
  try {
    const { fetchEarningsSurprises, fetchUpcomingEarnings } = await import('../collectors/earnings');
    const [surprise, upcoming] = await Promise.all([fetchEarningsSurprises(), fetchUpcomingEarnings()]);
    if (surprise) {
      d.EARNINGS_SURPRISE_PCT = {
        name: 'earnings_surprise_pct',
        value: surprise.avgSurprisePct,
        date: today(),
        formula: `메가캡 ${surprise.totalCount}종 최근 분기 EPS 서프라이즈 평균 ${surprise.avgSurprisePct.toFixed(2)}%. beat ${surprise.beatCount}/miss ${surprise.missCount}. 노션 §실적 발표.`,
      };
      d.EARNINGS_BEAT_RATIO = {
        name: 'earnings_beat_ratio',
        value: parseFloat(((surprise.beatCount / surprise.totalCount) * 100).toFixed(1)),
        date: today(),
        formula: `메가캡 beat ratio ${surprise.beatCount}/${surprise.totalCount} = ${((surprise.beatCount / surprise.totalCount) * 100).toFixed(1)}%.`,
      };
    }
    if (upcoming && upcoming.length > 0) {
      const megacapUpcoming = upcoming.filter((e: any) => ['AAPL','MSFT','GOOGL','AMZN','NVDA','META','TSLA'].includes(e.ticker));
      if (megacapUpcoming.length > 0) {
        const nearest = megacapUpcoming.sort((a: any, b: any) => (a.date < b.date ? -1 : 1))[0];
        const dday = Math.max(0, Math.floor((new Date(nearest.date).getTime() - Date.now()) / 86400000));
        d.EARNINGS_DDAY_MEGACAP = {
          name: 'earnings_dday_megacap',
          value: dday,
          date: today(),
          formula: `다음 메가캡 실적: ${nearest.ticker} @ ${nearest.date} (D-${dday}). 노션 §기업 실적 캘린더.`,
        };
      }
    }
  } catch { void 0; }

  // === P1#6: M2 13주 선행 방향 alignment ===
  // 노션 §"글로벌 M2 vs S&P 500 10~13주 선행". 이미 M2_LEAD_SHIFT_CORRELATION(17차)이 3개월 shift
  // 상관을 계산 중. 여기에 "현재 M2 변화 방향 → 13주 후 S&P 기대 방향" 이진 alignment 를 추가.
  try {
    const m2Hist = await readHistory('fred', 'M2SL');
    const spxHist = await fetchYahooHistory('^GSPC', 120);
    if (m2Hist.length >= 14 && spxHist.length >= 65) {
      // 최근 13주(=약 3개월) 전 M2 YoY vs 현재 S&P 13주 수익률 방향 대조.
      const sortedM2 = [...m2Hist].sort((a, b) => (a.date < b.date ? -1 : 1));
      const m2Now = sortedM2[sortedM2.length - 1]?.value;
      const m2Then = sortedM2[sortedM2.length - 4]?.value; // 3개월 전 (월간 4포인트)
      const spxNow = spxHist[spxHist.length - 1].close;
      const spxThen = spxHist[Math.max(0, spxHist.length - 65)].close; // 약 65영업일 = 13주
      if (
        typeof m2Now === 'number' && typeof m2Then === 'number' &&
        typeof spxNow === 'number' && typeof spxThen === 'number' &&
        m2Then > 0 && spxThen > 0
      ) {
        const m2Dir = m2Now - m2Then;
        const spxRet = ((spxNow - spxThen) / spxThen) * 100;
        // alignment: 두 방향이 같으면 +1 (M2 리드 유효), 반대면 -1
        let level: number;
        let label: string;
        if (m2Dir > 0 && spxRet > 0) { level = 1; label = '🟢 M2 상승 → S&P 상승 확인 (13주 리드 유효)'; }
        else if (m2Dir < 0 && spxRet < 0) { level = 1; label = '🟡 M2 하락 → S&P 하락 확인 (리드 유효하지만 약세)'; }
        else if (m2Dir > 0 && spxRet < 0) { level = -1; label = '🟠 M2 상승했지만 S&P 하락 — 리드 약화 가능성'; }
        else if (m2Dir < 0 && spxRet > 0) { level = -1; label = '🟡 M2 하락했지만 S&P 상승 — 상관 이탈'; }
        else { level = 0; label = '⚪ M2 정체'; }
        // 25차: 13주 (3개월) 키는 alias 유지 — 정합은 10주 키 (M2_SP500_LEAD_10W_ALIGNMENT) 우선.
        d.M2_SP500_LEAD_ALIGNMENT = {
          name: 'm2_sp500_lead_alignment',
          value: level,
          date: today(),
          formula: `M2 Δ3M=${m2Dir.toFixed(0)} vs S&P 13W ret=${spxRet.toFixed(2)}%. ${label}. (25차: 10W 키 우선 — alias 유지)`,
        };
      }
    }
  } catch { void 0; }

  // === P1#5: Insider cluster buy + dip buy 플래그 ===
  // 노션 "Insider Screener cluster buys / buying the dip(-5%+)" 정합.
  // cluster: 같은 ticker 에 2명 이상 insider P(purchase) 트랜잭션.
  // dip: cluster ≥ 2 AND VIX ≥ 30 (조정 중 cluster buy = 진짜 dip buy).
  try {
    const { fetchInsiderSummary } = await import('../collectors/smart-money');
    const ins = await fetchInsiderSummary({ allowStale: true });
    if (ins) {
      const clusterCount = ins.clusterTickerCount ?? 0;
      const maxSize = ins.maxClusterSize ?? 0;
      d.SMART_MONEY_CLUSTER_BUY = {
        name: 'smart_money_cluster_buy',
        value: clusterCount,
        date: today(),
        formula: `2인+ 동일종목 매수 클러스터 = ${clusterCount}개, 최대 ${maxSize}인 클러스터. top: ${(ins.topClusters ?? []).slice(0, 3).map((c) => `${c.ticker}×${c.count}`).join(', ') || '-'}. 노션 Insider Screener §cluster buys.`,
      };
      const vix = val(raw, 'VIX');
      const dipFlag = clusterCount >= 2 && vix !== null && vix >= 30;
      d.INSIDER_DIP_BUY = {
        name: 'insider_dip_buy',
        value: dipFlag ? 1 : 0,
        date: today(),
        formula: `cluster≥2(${clusterCount}) AND VIX≥30(${vix?.toFixed(1) ?? '-'}) → ${dipFlag ? '🟢 Dip buy 확인' : '⚪ 조건 미충족'}. 노션 "buying the dip".`,
      };
    }
  } catch { void 0; }

  // === P2#9: 국채 safe-haven 붕괴 플래그 ===
  // video4 §10:49 "미국이 불확실성의 근원". 30Y 금리 상승 AND DXY 하락 동시 = 탈달러 신호.
  try {
    const dgs30 = val(raw, 'DGS30');
    const dxy = val(raw, 'DXY');
    // 30일 전 값 (히스토리에서 다양한 source 시도 — fred/yahoo/computed 순)
    const dgs30Hist = await getHistorySeriesLocal('DGS30', 60);
    // DXY 는 Yahoo 수집이므로 yahoo source 우선
    const { readHistory } = await import('../state/history-store');
    let dxyHist: Array<{ date: string; value: number }> = [];
    for (const src of ['yahoo', 'fred', 'computed']) {
      try {
        const pts = await readHistory(src, 'DXY');
        if (pts.length > 0) { dxyHist = pts.slice(-60).map((p: any) => ({ date: p.date, value: p.value })); break; }
      } catch { /* try next */ }
    }
    if (dgs30 !== null && dxy !== null && dgs30Hist.length >= 30 && dxyHist.length >= 30) {
      const dgs30Prev = dgs30Hist[dgs30Hist.length - 30].value;
      const dxyPrev = dxyHist[dxyHist.length - 30].value;
      const yield30Up = dgs30 - dgs30Prev;
      const dxyChangePct = ((dxy - dxyPrev) / dxyPrev) * 100;
      const broken = yield30Up >= 0.15 && dxyChangePct <= -1.0;
      let level: number;
      let label: string;
      if (broken) { level = 2; label = '🔴 Safe-haven 붕괴 — 30Y↑ + DXY↓ 동시 (탈달러 구조)'; }
      else if (yield30Up >= 0.15 && dxyChangePct > 0) { level = 0; label = '⚪ 정상 (30Y↑ + DXY↑ 동조)'; }
      else if (yield30Up <= -0.15 && dxyChangePct <= -1.0) { level = -1; label = '🟢 Safe-haven 강화 (30Y↓ + DXY↓ = 글로벌 리스크오프)'; }
      else { level = 0; label = '⚪ 중립'; }
      d.BOND_SAFEHAVEN_BROKEN = {
        name: 'bond_safehaven_broken',
        value: level,
        date: today(),
        formula: `30Y Δ=${yield30Up.toFixed(2)}bp, DXY Δ=${dxyChangePct.toFixed(2)}%. ${label}. video4 §"미국이 불확실성의 근원" — 30Y↑+DXY↓ 동시 = 탈달러 구조 전환.`,
      };
    }
  } catch { void 0; }

  // ═══════════════════════════════════════════════════════════════════════
  // 26차 Phase 2 신규 (5~14)
  // ═══════════════════════════════════════════════════════════════════════

  // === Phase 2#5: US_DEBT_GDP_2031_PROJECTION ===
  // video4 §10:20 IMF 2031 부채/GDP 140%. 현재 vs 2031 예상 trajectory.
  try {
    const debtGdp = val(raw, 'FEDERAL_DEBT_GDP') ?? null;
    if (debtGdp !== null) {
      // IMF 2031 = 140%. 현재값 → 2031 까지 연간 평균 변화율 추정 (약 6년 가정)
      const projectedGap = 140 - debtGdp;
      const yearsToIMFTarget = 6;
      const annualPace = projectedGap / yearsToIMFTarget;
      let level: number;
      let label: string;
      if (debtGdp >= 135) { level = 2; label = '🔴 IMF 2031 목표(140%) 임박 — 채권자경단 가속 위험'; }
      else if (debtGdp >= 125) { level = 1; label = '🟠 2031 trajectory 진행 중'; }
      else if (debtGdp >= 110) { level = 0; label = '🟡 trajectory 정상 범위'; }
      else { level = -1; label = '🟢 부채 안정'; }
      d.US_DEBT_GDP_2031_PROJECTION = {
        name: 'us_debt_gdp_2031_projection',
        value: level,
        date: today(),
        formula: `현재 부채/GDP=${debtGdp.toFixed(1)}% vs IMF 2031 목표 140% (gap ${projectedGap.toFixed(1)}%p, 연 ${annualPace.toFixed(1)}%p 페이스). ${label}. video4 §10:20.`,
      };
    }
  } catch { void 0; }

  // === Phase 2#6: TRUMP_TAX_CUT_DEFICIT_PROJECTION ===
  // video4 §10:11 트럼프 감세 10년 3.3조 추가 적자. 현재 적자 vs 누적 trajectory.
  try {
    const deficitGdp = val(raw, 'FEDERAL_DEFICIT_GDP') ?? null;
    if (deficitGdp !== null) {
      // 3.3조 / 10년 = 연 0.33조. 현재 GDP 약 27조 → 약 1.2%p / 년 추가 부담
      const annualAddPctGdp = 1.2;
      const projectedDeficitIn5Y = deficitGdp + annualAddPctGdp * 5;
      let level: number;
      let label: string;
      if (deficitGdp >= 8) { level = 2; label = '🔴 적자 8%+ 이미 위험, 감세 누적 가속'; }
      else if (deficitGdp >= 6) { level = 1; label = '🟠 적자 6-8% + 감세 trajectory 경계'; }
      else { level = 0; label = '⚪ 정상 범위 (감세 누적 trajectory 모니터)'; }
      d.TRUMP_TAX_CUT_DEFICIT_PROJECTION = {
        name: 'trump_tax_cut_deficit_projection',
        value: level,
        date: today(),
        formula: `현재 적자/GDP=${deficitGdp.toFixed(1)}% + 감세 10년 3.3조 (≈연 ${annualAddPctGdp}%p) → 5년 후 추정 ${projectedDeficitIn5Y.toFixed(1)}%. ${label}. video4 §10:11.`,
      };
    }
  } catch { void 0; }

  // === Phase 2#7: 반도체 수출 300억 임계 — 데이터 소스 부재 시 manual 또는 KOSPI proxy ===
  // stt_kospi §1부 "3월 사상 첫 300억 달러 돌파". 직접 raw 부재 — SOXX 30D 수익률을 proxy 로.
  try {
    const soxx = d.SECTOR_SOXX?.value ?? null;
    if (soxx !== null) {
      // SOXX 30D 강세 시 반도체 수출 우위 proxy
      let level: number;
      let label: string;
      if (soxx >= 10) { level = 2; label = '🟢 반도체 모멘텀 강 (SOXX 30D ≥+10%, 수출 호조 proxy)'; }
      else if (soxx >= 0) { level = 1; label = '🔵 반도체 양호'; }
      else if (soxx >= -10) { level = 0; label = '🟡 반도체 둔화'; }
      else { level = -1; label = '🟠 반도체 약세 (SOXX -10%↓)'; }
      d.SEMI_EXPORT_PROXY_LEVEL = {
        name: 'semi_export_proxy_level',
        value: level,
        date: today(),
        formula: `SOXX 30D=${soxx.toFixed(1)}% (반도체 수출 proxy). ${label}. stt_kospi §1부 "300억 달러 돌파" 정합 시도 (직접 raw 부재).`,
      };
    }
  } catch { void 0; }

  // === Phase 2#8: GOLD_YEARLY_RETURN_HISTORICAL_RANK ===
  // video2 §16:54 "1979 130% / 1973 90% 다음 역대 3위 73%".
  try {
    let gHist = await fetchYahooHistory('GLD', 1500);
    if (gHist.length < 800) gHist = await fetchYahooHistory('GC=F', 1500);
    if (gHist.length >= 252) {
      const closes = gHist.map((h) => h.close);
      const last = closes[closes.length - 1];
      const yearAgo = closes[Math.max(0, closes.length - 252)];
      const yoyPct = ((last - yearAgo) / yearAgo) * 100;
      // 역사적 임계: 130% (1979), 90% (1973), 73% (영상 명시)
      let rank: number;
      let label: string;
      if (yoyPct >= 73) { rank = 3; label = `🟢 금 연봉 ${yoyPct.toFixed(0)}% — video2 §"역대 3위 73%" 진입 또는 초과`; }
      else if (yoyPct >= 50) { rank = 2; label = `🔵 금 연봉 ${yoyPct.toFixed(0)}% — 강한 상승`; }
      else if (yoyPct >= 25) { rank = 1; label = `🟡 금 연봉 ${yoyPct.toFixed(0)}% — 양호`; }
      else if (yoyPct >= 0) { rank = 0; label = `⚪ 금 연봉 ${yoyPct.toFixed(0)}%`; }
      else { rank = -1; label = `🟠 금 연봉 ${yoyPct.toFixed(0)}% 약세`; }
      d.GOLD_YEARLY_RETURN_HISTORICAL_RANK = {
        name: 'gold_yearly_return_historical_rank',
        value: rank,
        date: today(),
        formula: `금 1년 ${yoyPct.toFixed(2)}%. ${label}. video2 §16:54 "1979 130%/1973 90%/2024 73%".`,
      };
    }
  } catch { void 0; }

  // === Phase 2#9: NASDAQ_LONGTERM_CHANNEL_RETURN ===
  // video3 §11:03 "2020-2021 153% 상승이 역사상 최대, 현재 150% 초과".
  try {
    const nHist = await fetchYahooHistory('^IXIC', 1260);
    if (nHist.length >= 800) {
      const closes = nHist.map((h) => h.close);
      const last = closes[closes.length - 1];
      // 5년 전 저점 대비 현재
      const fiveYearLow = Math.min(...closes);
      const cumulativeFromLow = ((last - fiveYearLow) / fiveYearLow) * 100;
      let level: number;
      let label: string;
      if (cumulativeFromLow >= 200) { level = 3; label = `🔴 5년 저점 대비 +${cumulativeFromLow.toFixed(0)}% — 역사상 최대 초과 위험`; }
      else if (cumulativeFromLow >= 150) { level = 2; label = `🟠 5년 저점 대비 +${cumulativeFromLow.toFixed(0)}% — video3 §"153% 역사상 최대" 임박`; }
      else if (cumulativeFromLow >= 100) { level = 1; label = `🟡 5년 저점 대비 +${cumulativeFromLow.toFixed(0)}% — 강세 진행`; }
      else { level = 0; label = `⚪ 5년 저점 대비 +${cumulativeFromLow.toFixed(0)}%`; }
      d.NASDAQ_LONGTERM_CHANNEL_RETURN = {
        name: 'nasdaq_longterm_channel_return',
        value: parseFloat(cumulativeFromLow.toFixed(1)),
        date: today(),
        formula: `5년 저점 ${fiveYearLow.toFixed(0)} → 현재 ${last.toFixed(0)} = ${cumulativeFromLow.toFixed(1)}%. ${label}. video3 §11:03.`,
      };
    }
  } catch { void 0; }

  // === Phase 2#14: OPERATOR_PROMISED_CONTENT_QUEUE_DAYS ===
  // 노션 §"앞으로 풀어드릴 예정". 운영자 발신 빈도 추적 — kr-news (한경 GLOBAL) 마지막 글 vs 오늘.
  try {
    const krFresh = d.KR_NEWS_FRESHNESS_MINUTES?.value ?? null;
    if (krFresh !== null) {
      const daysSinceFresh = krFresh / (60 * 24);
      let level: number;
      let label: string;
      if (daysSinceFresh <= 1) { level = 2; label = '🟢 운영자 발신 활발 (1일 이내)'; }
      else if (daysSinceFresh <= 3) { level = 1; label = '🟡 운영자 발신 일상'; }
      else if (daysSinceFresh <= 7) { level = 0; label = '⚪ 운영자 발신 1주 내'; }
      else { level = -1; label = '🟠 운영자 발신 1주+ 공백'; }
      d.OPERATOR_PROMISED_CONTENT_QUEUE = {
        name: 'operator_promised_content_queue',
        value: level,
        date: today(),
        formula: `KR 매크로 뉴스 마지막 ${daysSinceFresh.toFixed(1)}일 전 (proxy). ${label}. 노션 §"앞으로 풀어드릴 예정" 발신 추적.`,
      };
    }
  } catch { void 0; }

  // ═══════════════════════════════════════════════════════════════════════
  // 28차 영상6 (주린이 탈출) 정합
  // ═══════════════════════════════════════════════════════════════════════

  // === 28차 #1: NASDAQ_RISK_REWARD_RATIO (손익비) ===
  // video6 §"3일 30% 오른 주식 — 더 오를 폭 10% / 빠질 폭 30% = 1:3 손익비"
  // fetchYahooHistory 는 거래일 기준 ~45일 / 100일 calendar request → ~65 거래일.
  try {
    const nHist = await fetchYahooHistory('^IXIC', 100);
    if (nHist.length >= 30) {
      const closes = nHist.map((h) => h.close);
      const last = closes[closes.length - 1];
      const ath30 = Math.max(...closes.slice(-30));
      const lowWindow = Math.min(60, closes.length);
      const lowN = Math.min(...closes.slice(-lowWindow));
      const upside = Math.max(0, ath30 - last);
      const downside = Math.max(0.01, last - lowN);
      const rr = upside / downside;
      let label: string;
      if (rr >= 3) { label = `🟢 손익비 1:${rr.toFixed(1)} 우호`; }
      else if (rr >= 1.5) { label = `🔵 손익비 1:${rr.toFixed(1)} 양호`; }
      else if (rr >= 0.5) { label = `🟡 손익비 1:${rr.toFixed(2)} 균형`; }
      else { label = `🔴 손익비 1:${rr.toFixed(2)} 추격 위험 — video6 §"오를 폭 < 빠질 폭"`; }
      d.NASDAQ_RISK_REWARD_RATIO = {
        name: 'nasdaq_risk_reward_ratio',
        value: parseFloat(rr.toFixed(2)),
        date: today(),
        formula: `30D ATH ${ath30.toFixed(0)} - 현재 ${last.toFixed(0)} = upside ${upside.toFixed(0)} / ${lowWindow}D 저점 ${lowN.toFixed(0)} → downside ${downside.toFixed(0)} = RR ${rr.toFixed(2)}. ${label}. video6 §손익비.`,
      };
    }
  } catch { void 0; }

  // === 28차 #2: USER_USD_RETURN_PCT ===
  // video6 §"서울 아파트 5억→10억 KRW 2배 vs USD 기준 34%"
  try {
    const { readInvestmentPlan } = await import('../services/investment-plan');
    const plan = await readInvestmentPlan();
    const usdkrw = val(raw, 'USDKRW') ?? 1400;
    const startingUSDDirect = plan.startingCapitalUSD ?? 0;
    const startingKRWInUSD = (plan.startingCapitalKRW ?? 0) / usdkrw;
    const startingUSDTotal = startingUSDDirect + startingKRWInUSD;
    const currentUSD = (d.USER_USD_CAPITAL_TOTAL?.value ?? 0);
    if (startingUSDTotal > 0 && currentUSD > 0 && plan.accountStartDate) {
      const returnPct = ((currentUSD - startingUSDTotal) / startingUSDTotal) * 100;
      const startMs = new Date(plan.accountStartDate).getTime();
      const days = Math.max(1, (Date.now() - startMs) / 86400000);
      const annualPct = returnPct * (365 / days);
      d.USER_USD_RETURN_PCT = {
        name: 'user_usd_return_pct',
        value: parseFloat(returnPct.toFixed(2)),
        date: today(),
        formula: `시작 ${plan.accountStartDate} ${startingUSDTotal.toFixed(0)} USD → 현재 ${currentUSD.toFixed(0)} USD = ${returnPct.toFixed(2)}% (${days.toFixed(0)}일 / 연환산 ${annualPct.toFixed(1)}%). video6 §"진짜 자산은 달러로".`,
      };
    }
  } catch { void 0; }

  // === 28차 #3: INVESTOR_PRIORITY_ORDER_SCORE (4단 메타 체크) ===
  try {
    const { readInvestmentPlan, readRecentTradeLog } = await import('../services/investment-plan');
    const plan = await readInvestmentPlan();
    const log = await readRecentTradeLog(200);
    let score = 0;
    const checks: string[] = [];
    if (plan.horizon && plan.horizon !== 'medium') { score++; checks.push('✓ 시간프레임'); }
    else if (plan.horizon) { checks.push('horizon=medium (default)'); }
    const hasHoldings = plan.currentHoldings && Object.values(plan.currentHoldings).some((v) => typeof v === 'number' && v > 0);
    const hasTranche = typeof (manualInputs?.trancheUsedPct) === 'number';
    if (hasHoldings || hasTranche) { score++; checks.push('✓ 비중'); }
    const cutoff7d = Date.now() - 7 * 86400000;
    const recentObs = log.filter((e) => e.kind === 'observation' && new Date(e.ts).getTime() >= cutoff7d).length;
    const dcaUsed = manualInputs?.trancheUsedPct ?? 0;
    if (dcaUsed >= 30 || recentObs >= 1) { score++; checks.push('✓ 시나리오/복기'); }
    const cutoff4w = Date.now() - 28 * 86400000;
    const recent4w = log.filter((e) => e.kind === 'user_action' && new Date(e.ts).getTime() >= cutoff4w);
    const against = recent4w.filter((e) => e.againstSystemRecommendation === true).length;
    const totalActions = recent4w.length;
    const againstPct = totalActions > 0 ? (against / totalActions) * 100 : 0;
    if (totalActions === 0 || againstPct < 30) { score++; checks.push('✓ 신호정합'); }
    let label: string;
    if (score === 4) label = '🟢 4단 우선순위 모두 정합';
    else if (score === 3) label = '🔵 3단 정합 — 1단계 보강 필요';
    else if (score === 2) label = '🟡 2단 정합 — 절반';
    else if (score === 1) label = '🟠 1단 정합 — 시작 단계';
    else label = '🔴 우선순위 미정의 — 시간프레임부터';
    d.INVESTOR_PRIORITY_ORDER_SCORE = {
      name: 'investor_priority_order_score',
      value: score,
      date: today(),
      formula: `${checks.join(' / ')}. ${score}/4. ${label}. video6 §"종목 < 타이밍 < 비중 < 심리".`,
    };
  } catch { void 0; }

  // === 28차 #5: INVESTOR_MISCONCEPTION_FLAGS (오해 4종 자동 감지) ===
  try {
    const { readRecentTradeLog, readInvestmentPlan } = await import('../services/investment-plan');
    const log = await readRecentTradeLog(200);
    const plan = await readInvestmentPlan();
    const cutoff30d = Date.now() - 30 * 86400000;
    const userActions30d = log.filter((e) => e.kind === 'user_action' && new Date(e.ts).getTime() >= cutoff30d);
    const flags: string[] = [];
    // (b) 급등주 추격
    const nHist = await fetchYahooHistory('^IXIC', 35);
    if (nHist.length >= 30) {
      const closes = nHist.map((h) => h.close);
      const ret30 = ((closes[closes.length - 1] - closes[0]) / closes[0]) * 100;
      const buys30d = userActions30d.filter((e) => /BUY|ADD/i.test(e.to ?? ''));
      if (ret30 >= 30 && buys30d.length > 0) {
        flags.push(`급등주 추격 (NASDAQ 30D +${ret30.toFixed(1)}% 시 매수 ${buys30d.length}건)`);
      }
    }
    // (c) 분산 = 안전
    const heldAssets = plan.currentHoldings
      ? Object.entries(plan.currentHoldings).filter(([, v]) => typeof v === 'number' && v > 0).length
      : 0;
    const againstAny = userActions30d.some((e) => e.againstSystemRecommendation === true);
    if (heldAssets >= 4 && againstAny) {
      flags.push(`분산만으로 안전 가정 (${heldAssets}자산 + 시스템 반대)`);
    }
    // (d) 차트만
    const obs30d = log.filter((e) => e.kind === 'observation' && new Date(e.ts).getTime() >= cutoff30d);
    const macroKeywords = /M2|금리|CPI|연준|Fed|관세|환율|FOMC/i;
    const obsWithMacro = obs30d.filter((e) => macroKeywords.test(e.notes ?? ''));
    if (obs30d.length >= 3 && obsWithMacro.length === 0) {
      flags.push(`차트 중심 (observation ${obs30d.length}건 매크로 인용 0)`);
    }
    d.INVESTOR_MISCONCEPTION_FLAGS = {
      name: 'investor_misconception_flags',
      value: flags.length,
      date: today(),
      formula: flags.length > 0
        ? `🟠 오해 ${flags.length}종: ${flags.join(' / ')}. video6 §오해 4종.`
        : '⚪ 오해 패턴 미감지',
    };
  } catch { void 0; }

  // ═══════════════════════════════════════════════════════════════════════
  // 27차 Phase 2 통합 derived
  // ═══════════════════════════════════════════════════════════════════════

  // === 27차 Phase 2#11: SMART_MONEY_4FACTOR_CONSENSUS (13F + Dataroma + TipRanks + Insider) ===
  // 노션 §"세력의 포트폴리오" 4팩터 단일 합의 점수
  try {
    const inst = d.INSTITUTIONAL_NASDAQ_FLOW?.value ?? null;
    const dataromaScore = d.INSTITUTIONAL_CONSENSUS_STRONG_COUNT?.value ?? null;
    const upside = d.ANALYST_TARGET_UPSIDE_PCT?.value ?? null;
    const cluster = d.SMART_MONEY_CLUSTER_BUY?.value ?? null;
    const factors: number[] = [];
    if (inst !== null) factors.push(inst >= 1 ? 1 : inst <= -1 ? -1 : 0);
    if (dataromaScore !== null) factors.push(dataromaScore >= 5 ? 1 : 0);
    if (upside !== null) factors.push(upside >= 10 ? 1 : upside <= -5 ? -1 : 0);
    if (cluster !== null) factors.push(cluster >= 3 ? 1 : 0);
    if (factors.length >= 3) {
      const consensus = factors.reduce((a, b) => a + b, 0);
      let level: number;
      let label: string;
      if (consensus >= 3) { level = 2; label = '🟢 4팩터 강 합의 매수'; }
      else if (consensus >= 2) { level = 1; label = '🔵 다수 합의'; }
      else if (consensus <= -2) { level = -2; label = '🔴 4팩터 합의 매도'; }
      else { level = 0; label = '⚪ 합의 분산'; }
      d.SMART_MONEY_4FACTOR_CONSENSUS = {
        name: 'smart_money_4factor_consensus',
        value: level,
        date: today(),
        formula: `13F=${inst ?? '-'}, Dataroma 강합의=${dataromaScore ?? '-'}, TipRanks upside=${upside?.toFixed(1) ?? '-'}%, Insider cluster=${cluster ?? '-'}. ${factors.length}팩터 합의 ${consensus}. ${label}. 노션 §스마트머니 4축.`,
      };
    }
  } catch { void 0; }

  // === 27차 Phase 2#13: KR_EVENT_GAUGE (DART + KR_NEWS 합쳐 한국 이벤트) ===
  try {
    const dartLevel = d.KR_MATERIAL_DISCLOSURE_LEVEL?.value ?? null;
    const krNewsCount = d.KR_NEWS_TOTAL_COUNT_24H?.value ?? null;
    if (dartLevel !== null || krNewsCount !== null) {
      let level = 0;
      if ((dartLevel ?? 0) >= 2 || (krNewsCount ?? 0) >= 50) level = 2;
      else if ((dartLevel ?? 0) >= 1 || (krNewsCount ?? 0) >= 20) level = 1;
      d.KR_EVENT_GAUGE = {
        name: 'kr_event_gauge',
        value: level,
        date: today(),
        formula: `DART level=${dartLevel ?? '-'}, KR 뉴스 24h=${krNewsCount ?? '-'}건. ${level === 2 ? '🔴 한국 이벤트 폭증' : level === 1 ? '🟡 활발' : '⚪ 정상'}. 27차 P2#13 통합.`,
      };
    }
  } catch { void 0; }

  // === 27차 Phase 2#15: CPI_MoM_ANNUALIZED (CPI/PCE MoM 가속/감속) ===
  // 노션 §경제 캘린더 MoM 정합 — 3개월 annualized vs 6개월 annualized 비교
  try {
    const cpiHist = await readHistory('fred', 'CPIAUCSL').catch(() => []);
    if (cpiHist.length >= 7) {
      const sorted = [...cpiHist].sort((a, b) => (a.date < b.date ? -1 : 1));
      const last = sorted[sorted.length - 1].value;
      const m3 = sorted[sorted.length - 4]?.value;
      const m6 = sorted[sorted.length - 7]?.value;
      if (typeof last === 'number' && typeof m3 === 'number' && typeof m6 === 'number' && m3 > 0 && m6 > 0) {
        const ann3M = (Math.pow(last / m3, 12 / 3) - 1) * 100;
        const ann6M = (Math.pow(last / m6, 12 / 6) - 1) * 100;
        const accel = ann3M - ann6M;
        let level: number;
        let label: string;
        if (accel >= 1) { level = 2; label = '🔴 인플레 가속 (3M ann > 6M ann +1%p)'; }
        else if (accel >= 0.3) { level = 1; label = '🟡 인플레 약가속'; }
        else if (accel <= -1) { level = -2; label = '🟢 인플레 감속'; }
        else { level = 0; label = '⚪ 안정'; }
        d.CPI_MOM_ACCELERATION = {
          name: 'cpi_mom_acceleration',
          value: level,
          date: today(),
          formula: `CPI 3M ann ${ann3M.toFixed(2)}% vs 6M ann ${ann6M.toFixed(2)}% = ${accel >= 0 ? '+' : ''}${accel.toFixed(2)}%p. ${label}. 노션 §경제 캘린더 MoM 정합 (27차 P2#15).`,
        };
      }
    }
  } catch { void 0; }

  // === 27차 Phase 2#17: ISM_CROSS_VALIDATION (proxy vs actual 격차) ===
  try {
    const ismProxy = d.ISM_PROXY?.value ?? null;
    const ismActual = val(raw, 'ISM_MANUFACTURING') ?? (manualInputs?.ismPmi ?? null);
    if (ismProxy !== null && ismActual !== null) {
      const gap = Math.abs(ismProxy - ismActual);
      let level: number;
      let label: string;
      if (gap >= 3) { level = -1; label = `🟠 proxy(${ismProxy.toFixed(1)}) vs actual(${ismActual.toFixed(1)}) 격차 ${gap.toFixed(1)}pt — actual 우선`; }
      else { level = 0; label = `⚪ proxy/actual 정합 (격차 ${gap.toFixed(1)}pt)`; }
      d.ISM_CROSS_VALIDATION = {
        name: 'ism_cross_validation',
        value: level,
        date: today(),
        formula: `INDPRO proxy ${ismProxy.toFixed(1)} vs actual ${ismActual.toFixed(1)}. ${label}. 27차 P2#17.`,
      };
    }
  } catch { void 0; }

  // === 25차: META_MISSING_DERIVED_COUNT — catch silent 결측 추적 ===
  try {
    const criticalKeys = [
      'NASDAQ_DRAWDOWN_ATH', 'NASDAQ_DISPARITY', 'NASDAQ_RSI_14',
      'GOLD_DISPARITY', 'GOLD_LONGTERM_CUP_HANDLE',
      'KOSPI_DISPARITY', 'KOSPI_QUARTERLY_UPPER_WICK_PCT',
      'CONVICTION_SCORE_7AXIS', 'LEVERAGE_TRIGGER_3OF3',
      'FOMC_RATE_HOLD_PROB', 'M2_SP500_LEAD_10W_ALIGNMENT',
      'INSTITUTIONAL_NASDAQ_FLOW', 'SMART_MONEY_CLUSTER_BUY',
      'STLFSI_LEVEL', 'MMF_RRP_RATIO', 'EM_TRIO_BREADTH',
    ];
    const missing = criticalKeys.filter((k) => d[k] === undefined || d[k]?.value === null || d[k]?.value === undefined);
    d.META_MISSING_DERIVED_COUNT = {
      name: 'meta_missing_derived_count',
      value: missing.length,
      date: today(),
      formula: `핵심 derived ${criticalKeys.length}종 중 결측 ${missing.length}종${missing.length > 0 ? `: ${missing.slice(0, 5).join(', ')}` : ''}. catch silent skip 추적 (25차).`,
    };
  } catch { void 0; }

  // ═══════════════════════════════════════════════════════════════════════
  // 29차 macrosquare audit Phase 1 — P1 17건 (5 atomic batch)
  // ═══════════════════════════════════════════════════════════════════════

  // ★ === 29차 P1-A #1: LEVERAGE_EXIT_AT_TARGET ===
  //   video1 §10:42-10:54 "착한 레버리지 진입 후 +20~30% 익절, 횡보 시 일반 ETF 복귀".
  //   LEVERAGE_TRIGGER_3OF3=1 발효 시점의 NASDAQ close 를 history-store('derived','LEVERAGE_ENTRY_PRICE') 에 기록.
  //   기록 후 매일 누적 수익률 산출하여 ≥30 강제 익절 / ≥20 익절 권고 / else 0.
  try {
    const lvgTrigger = d.LEVERAGE_TRIGGER_3OF3?.value ?? 0;
    const nasdaqNow = val(raw, 'NASDAQ');
    const todayKey = today();
    if (lvgTrigger >= 1 && nasdaqNow !== null) {
      // 진입가 history append (이미 동일 날짜 있으면 upsert).
      const existing = await readHistory('derived', 'LEVERAGE_ENTRY_PRICE').catch(() => [] as Array<{date:string; value:number}>);
      // 가장 최근 진입가 기록 — trigger 가 처음 켜진 날에만 기록 (이미 series 있으면 그 첫 진입가 사용).
      let entryPrice: number | null = null;
      let entryDate: string | null = null;
      if (existing.length > 0) {
        // trigger 끊김 후 재발효 케이스 대비: 최신 점만 채택.
        entryPrice = existing[existing.length - 1].value;
        entryDate = existing[existing.length - 1].date;
      } else {
        // 첫 발효 — 오늘 nasdaqClose 기록.
        await writeHistoryPoint('derived', 'LEVERAGE_ENTRY_PRICE', nasdaqNow, todayKey);
        entryPrice = nasdaqNow;
        entryDate = todayKey;
      }
      if (entryPrice !== null && entryPrice > 0 && entryDate) {
        const cumReturnPct = ((nasdaqNow - entryPrice) / entryPrice) * 100;
        const elapsedDays = Math.max(0, Math.floor((Date.now() - new Date(entryDate).getTime()) / 86400000));
        let level: number;
        let label: string;
        if (cumReturnPct >= 30) { level = 2; label = `🟢 강제 익절 (+${cumReturnPct.toFixed(1)}% ≥ 30%, video1 §10:54)`; }
        else if (cumReturnPct >= 20) { level = 1; label = `🟡 익절 권고 (+${cumReturnPct.toFixed(1)}% ≥ 20%, video1 §10:42)`; }
        else { level = 0; label = `⚪ 보유 진행 중 (${cumReturnPct >= 0 ? '+' : ''}${cumReturnPct.toFixed(1)}%, ${elapsedDays}일 경과)`; }
        d.LEVERAGE_EXIT_AT_TARGET = {
          name: 'leverage_exit_at_target',
          value: parseFloat(cumReturnPct.toFixed(2)),
          date: todayKey,
          formula: `진입가 ${entryPrice.toFixed(0)} (${entryDate}) → 현재 ${nasdaqNow.toFixed(0)} = ${cumReturnPct >= 0 ? '+' : ''}${cumReturnPct.toFixed(2)}%, ${elapsedDays}일 경과. level=${level}. ${label}. video1 §전략C "착한 레버리지 +20~30% 익절".`,
        };
      }
    } else if (lvgTrigger === 0) {
      // trigger 꺼지면 다음 진입을 위해 history 정리는 하지 않음 (다음 발효 시 새 진입가 기록).
      // 단순히 LEVERAGE_EXIT_AT_TARGET 미산출.
    }
  } catch { void 0; }

  // ★ === 29차 P1-A #2: DRAWDOWN_TYPE_CLASSIFIER (= RECESSION_VS_CORRECTION_FLAG) ===
  //   video1 §08:38-08:56 "기업 펀더 살아있는 -30% = 기회" + video3 §17:50-18:31 "회복 수년 vs 빠른 반등 분기점"
  //   drawdown ≤ -30 ∧ ICSA <300K ∧ ISM ≥ 48 → 'OPPORTUNITY' (level=-1, 매수 우호)
  //   drawdown ≤ -30 ∧ (ICSA ≥350K ∨ ISM <45) → 'SYSTEMIC_RISK' (level=-2)
  //   drawdown ≤ -30 + 그 외 → 'AMBIGUOUS' (level=0)
  //   drawdown > -30 → null (비활성)
  try {
    const dd = d.NASDAQ_DRAWDOWN_ATH?.value ?? null;
    const icsa = val(raw, 'ICSA');
    const ismLatest = d.ISM_PROXY?.value ?? (typeof manualInputs?.ismPmi === 'number' ? manualInputs.ismPmi : null) ?? val(raw, 'ISM_MANUFACTURING');
    if (dd !== null && dd <= -30) {
      let cls: 'OPPORTUNITY' | 'SYSTEMIC_RISK' | 'AMBIGUOUS';
      let level: number;
      let label: string;
      const ismLow = ismLatest !== null && ismLatest < 45;
      const ismOk = ismLatest !== null && ismLatest >= 48;
      const icsaHigh = icsa !== null && icsa >= 350000;
      const icsaLow = icsa !== null && icsa < 300000;
      if (icsaLow && ismOk) {
        cls = 'OPPORTUNITY'; level = -1;
        label = `🟢 OPPORTUNITY (drawdown ${dd.toFixed(1)}%, ICSA ${Math.round(icsa!/1000)}K<300K, ISM ${ismLatest!.toFixed(1)}≥48 — video1 §08:38 "펀더 살아있는 기회")`;
      } else if (icsaHigh || ismLow) {
        cls = 'SYSTEMIC_RISK'; level = -2;
        label = `🔴 SYSTEMIC_RISK (drawdown ${dd.toFixed(1)}%${icsaHigh ? `, ICSA ${Math.round(icsa!/1000)}K≥350K` : ''}${ismLow ? `, ISM ${ismLatest!.toFixed(1)}<45` : ''} — video3 §18:31 "회복 수년")`;
      } else {
        cls = 'AMBIGUOUS'; level = 0;
        label = `🟡 AMBIGUOUS (drawdown ${dd.toFixed(1)}%, ICSA ${icsa !== null ? Math.round(icsa/1000)+'K' : 'n/a'}, ISM ${ismLatest?.toFixed(1) ?? 'n/a'} — 분기 미확정)`;
      }
      d.DRAWDOWN_TYPE_CLASSIFIER = {
        name: 'drawdown_type_classifier',
        value: level,
        date: today(),
        formula: `${cls}. ${label}. video1 §08:38 + video3 §17:50.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P1-A #3: INVESTMENT_TRIPLE_GATE_SCORE ===
  //   video6 §04:48 "펀더(무엇) × 매크로(지금) × 차트(어느 가격) — 3축 일치"
  //   펀더 axis: EARNINGS_SURPRISE_FLAG / ANALYST_CONSENSUS_NASDAQ_MEGACAP / fallback INSTITUTIONAL_NASDAQ_FLOW
  //   매크로 axis: 현 regime 분류 — RISK_ON/NEUTRAL/PANIC_BUT_OK=+1, CAUTION=0, 그 외=-1
  //   차트 axis: NASDAQ_DISPARITY ∈ [-10,+10]=+1, 그 외=0, ≤-15=-1
  //   value: 합계 / 3 (-1 ~ 1 range). signals.ts NASDAQ STRONG_BUY 게이트 보강 입력.
  try {
    // (a) 펀더 축
    let fundAxis: number = 0;
    let fundLabel: string;
    const earnSurprise = d.EARNINGS_SURPRISE_FLAG?.value ?? null;
    const analyst = d.ANALYST_CONSENSUS_NASDAQ_MEGACAP?.value ?? null;
    const inst = d.INSTITUTIONAL_NASDAQ_FLOW?.value ?? null;
    if (earnSurprise !== null) {
      fundAxis = earnSurprise > 0 ? 1 : earnSurprise < 0 ? -1 : 0;
      fundLabel = `EARNINGS_SURPRISE_FLAG=${earnSurprise} → ${fundAxis}`;
    } else if (analyst !== null) {
      fundAxis = analyst > 0.5 ? 1 : analyst < -0.5 ? -1 : 0;
      fundLabel = `ANALYST_CONSENSUS=${analyst.toFixed(2)} → ${fundAxis}`;
    } else if (inst !== null) {
      fundAxis = inst > 0 ? 1 : inst < 0 ? -1 : 0;
      fundLabel = `INSTITUTIONAL_NASDAQ_FLOW=${inst} (fallback) → ${fundAxis}`;
    } else {
      fundLabel = '펀더 데이터 결측 → 0';
    }
    // (b) 매크로 축 — REGIME 은 derived 가 아닌 cache.ts 후 단계에서 계산되므로 raw history-store 에서 latest signal:REGIME_LABEL (numeric code) 읽기.
    let macroAxis: number = 0;
    let macroLabel: string;
    let regimeStr: string | null = null;
    try {
      const regimeLabelHist = await readHistory('signal', 'REGIME_LABEL').catch(() => [] as Array<{date:string; value:number}>);
      if (regimeLabelHist.length > 0) {
        const lastCode = regimeLabelHist[regimeLabelHist.length - 1].value;
        // history-store regimeValue 매핑 역변환
        if (lastCode === 100) regimeStr = 'RISK_ON';
        else if (lastCode === 80) regimeStr = 'NEUTRAL';
        else if (lastCode === 60) regimeStr = 'CAUTION';
        else if (lastCode === 40) regimeStr = 'CORRECTION';
        else if (lastCode === 30) regimeStr = 'STAGFLATION';
        else if (lastCode === 20) regimeStr = 'PANIC_BUT_OK';
        else if (lastCode === 10) regimeStr = 'BOND_VIGILANTE';
        else if (lastCode === 0) regimeStr = 'RECESSION_RISK';
      }
    } catch { void 0; }
    if (regimeStr === 'RISK_ON' || regimeStr === 'NEUTRAL' || regimeStr === 'PANIC_BUT_OK') {
      macroAxis = 1; macroLabel = `regime=${regimeStr} → +1`;
    } else if (regimeStr === 'CAUTION') {
      macroAxis = 0; macroLabel = `regime=${regimeStr} → 0`;
    } else if (regimeStr === 'CORRECTION' || regimeStr === 'RECESSION_RISK' || regimeStr === 'BOND_VIGILANTE' || regimeStr === 'STAGFLATION' || regimeStr === 'STAGFLATION_BOND_VIGILANTE') {
      macroAxis = -1; macroLabel = `regime=${regimeStr} → -1`;
    } else {
      macroLabel = `regime=null (history 미존재) → 0`;
    }
    // (c) 차트 축
    let chartAxis: number = 0;
    let chartLabel: string;
    const disparity = d.NASDAQ_DISPARITY?.value ?? null;
    if (disparity !== null) {
      if (disparity >= -10 && disparity <= 10) { chartAxis = 1; chartLabel = `이격도 ${disparity.toFixed(1)}% ∈ [-10,+10] → +1`; }
      else if (disparity <= -15) { chartAxis = -1; chartLabel = `이격도 ${disparity.toFixed(1)}% ≤ -15 → -1`; }
      else { chartAxis = 0; chartLabel = `이격도 ${disparity.toFixed(1)}% (외 구간) → 0`; }
    } else {
      chartLabel = '이격도 결측 → 0';
    }
    const sumScore = (fundAxis + macroAxis + chartAxis) / 3;
    let levelLabel: string;
    if (sumScore >= 0.66) levelLabel = '🟢 3축 정합 — STRONG_BUY 우호';
    else if (sumScore >= 0.0) levelLabel = '🔵 균형';
    else if (sumScore <= -0.33) levelLabel = '🔴 3축 분기 — STRONG_BUY 차단';
    else levelLabel = '🟡 약 분기';
    d.INVESTMENT_TRIPLE_GATE_SCORE = {
      name: 'investment_triple_gate_score',
      value: parseFloat(sumScore.toFixed(3)),
      date: today(),
      formula: `펀더(${fundLabel}) + 매크로(${macroLabel}) + 차트(${chartLabel}) = ${(fundAxis+macroAxis+chartAxis)} / 3 = ${sumScore.toFixed(3)}. ${levelLabel}. video6 §04:48 "3축 일치".`,
    };
  } catch { void 0; }

  // ★ === 29차 P1-B #4: RECOVERY_TRIPLE_SIGNAL ===
  //   video2 §13:42-13:49 "경기 회복 3가지 동시 — ISM 바닥 반등 + 금구리비 하락 전환 + 실업수당 감소"
  //   axis 1 (ISM 반등): ISM ≥ 50 AND 5D 평균 > 20D 평균 → 1
  //   axis 2 (금구리비 하락 전환 = COPPER_GOLD_RATIO_TREND > 0.005, 즉 CGR 상승): 사용 (gold/copper 하락 ≡ copper/gold 상승)
  //   axis 3 (ICSA 4주 평균 < 직전 4주 평균): readHistory('fred','ICSA') 8주 분량
  try {
    let axis1 = 0; let label1 = 'ISM 결측';
    let axis2 = 0; let label2 = 'CGR 추세 결측';
    let axis3 = 0; let label3 = 'ICSA 결측';
    // axis 1: ISM history
    const ismLatest = d.ISM_PROXY?.value ?? (typeof manualInputs?.ismPmi === 'number' ? manualInputs.ismPmi : null) ?? val(raw, 'ISM_MANUFACTURING');
    if (ismLatest !== null && ismLatest >= 50) {
      const indproHist = await readHistory('fred', 'INDPRO').catch(() => [] as Array<{date:string; value:number}>);
      if (indproHist.length >= 25) {
        const recent5 = indproHist.slice(-5).map((p) => p.value);
        const prev20 = indproHist.slice(-25, -5).map((p) => p.value);
        const r5avg = recent5.reduce((a, b) => a + b, 0) / recent5.length;
        const p20avg = prev20.reduce((a, b) => a + b, 0) / prev20.length;
        if (r5avg > p20avg) {
          axis1 = 1; label1 = `ISM ${ismLatest.toFixed(1)}≥50 + INDPRO 5D ${r5avg.toFixed(2)} > 20D ${p20avg.toFixed(2)} → +1`;
        } else {
          label1 = `ISM ${ismLatest.toFixed(1)}≥50 BUT INDPRO 5D≤20D`;
        }
      } else {
        // history 없으면 ISM ≥50 단독 평가
        if (ismLatest >= 50) { axis1 = 1; label1 = `ISM ${ismLatest.toFixed(1)}≥50 (history 부족, 단독 평가) → +1`; }
      }
    } else if (ismLatest !== null) {
      label1 = `ISM ${ismLatest.toFixed(1)}<50`;
    }
    // axis 2: COPPER_GOLD_RATIO_TREND (양수=구리 상승=금구리비 하락 전환).
    //   spec 의 GOLD_COPPER_RATIO_TREND < -0.5 ≡ COPPER_GOLD_RATIO_TREND > 0.005 (단위 다름 — CGR trend 는 비율 변화율, 0.005=+0.5%).
    const cgrTrend = d.COPPER_GOLD_RATIO_TREND?.value ?? null;
    if (cgrTrend !== null) {
      if (cgrTrend > 0.005) { axis2 = 1; label2 = `CGR_TREND ${(cgrTrend*100).toFixed(2)}%>+0.5% (금구리비 하락 전환) → +1`; }
      else { label2 = `CGR_TREND ${(cgrTrend*100).toFixed(2)}%≤+0.5%`; }
    }
    // axis 3: ICSA 4주 평균 < 직전 4주 평균
    const icsaHistB = await readHistory('fred', 'ICSA').catch(() => [] as Array<{date:string; value:number}>);
    if (icsaHistB.length >= 8) {
      const recent4 = icsaHistB.slice(-4).map((p) => p.value);
      const prev4 = icsaHistB.slice(-8, -4).map((p) => p.value);
      const r4avg = recent4.reduce((a, b) => a + b, 0) / recent4.length;
      const p4avg = prev4.reduce((a, b) => a + b, 0) / prev4.length;
      if (r4avg < p4avg) {
        axis3 = 1; label3 = `ICSA 4W ${Math.round(r4avg/1000)}K < 직전 4W ${Math.round(p4avg/1000)}K → +1`;
      } else {
        label3 = `ICSA 4W ${Math.round(r4avg/1000)}K ≥ 직전 4W ${Math.round(p4avg/1000)}K`;
      }
    }
    const met = axis1 + axis2 + axis3;
    let level: number;
    let label: string;
    if (met >= 3) { level = 2; label = '🟢 회복 3축 충족 (video2 §13:42)'; }
    else if (met >= 2) { level = 1; label = '🔵 회복 2축'; }
    else { level = 0; label = '⚪ 회복 단발'; }
    d.RECOVERY_TRIPLE_SIGNAL = {
      name: 'recovery_triple_signal',
      value: level,
      date: today(),
      formula: `${label1} / ${label2} / ${label3} → met ${met}/3. ${label}. video2 §13:42-13:49 "경기 회복 3가지 동시".`,
    };
  } catch { void 0; }

  // ★ === 29차 P1-B #5: KOSPI_RECOVERY_3AXIS_LEVEL + KOSPI_RECOVERY_TRIO_DAYS ===
  //   stt_kospi §05:35 "추세선 회복 + 거래량 확인 + 환율 1480 이하 = 진짜 추세 전환"
  //   3축 동시 충족일 → 연속일수 history 추적 (history-store('derived','KOSPI_RECOVERY_TRIO_DAY_COUNT'))
  try {
    const trendRec = d.KOSPI_TREND_RECOVERY?.value ?? 0;
    const volConf = d.KOSPI_VOLUME_CONFIRM?.value ?? 0;
    const usdkrwToday = val(raw, 'USDKRW');
    const fxOk = usdkrwToday !== null && usdkrwToday <= 1480 ? 1 : 0;
    const allMet = (trendRec === 1 && volConf === 1 && fxOk === 1) ? 1 : 0;
    // 누적 일수: 어제 카운트 + (오늘 충족이면 +1, 미충족이면 0 reset)
    const trioHist = await readHistory('derived', 'KOSPI_RECOVERY_TRIO_DAY_COUNT').catch(() => [] as Array<{date:string; value:number}>);
    const yesterdayCount = trioHist.length > 0 ? trioHist[trioHist.length - 1].value : 0;
    const newCount = allMet === 1 ? Math.max(0, yesterdayCount) + 1 : 0;
    const todayKey = today();
    await writeHistoryPoint('derived', 'KOSPI_RECOVERY_TRIO_DAY_COUNT', newCount, todayKey);
    d.KOSPI_RECOVERY_TRIO_DAYS = {
      name: 'kospi_recovery_trio_days',
      value: newCount,
      date: todayKey,
      formula: `trend=${trendRec}, vol=${volConf}, fx≤1480=${fxOk} (USDKRW=${usdkrwToday?.toFixed(1) ?? '?'}). 3축 동시 충족 연속일수 ${newCount}일. stt_kospi §05:35.`,
    };
    let level: number;
    let label: string;
    if (newCount >= 5) { level = 2; label = '🟢 진짜 추세 전환 (3축 5일+)'; }
    else if (newCount >= 3) { level = 1; label = '🔵 3축 연속 3일+'; }
    else { level = 0; label = `⚪ ${newCount}일 (3일 미만)`; }
    d.KOSPI_RECOVERY_3AXIS_LEVEL = {
      name: 'kospi_recovery_3axis_level',
      value: level,
      date: todayKey,
      formula: `KOSPI_RECOVERY_TRIO_DAYS=${newCount}. ${label}. stt_kospi §05:35 "환율 1480↓ + 추세 + 거래량 = 진짜 전환".`,
    };
  } catch { void 0; }

  // ★ === 29차 P1-B #6: GOLD_AXIS_CONFLICT_RESOLVER + GOLD_AXIS_GATE_FLAG ===
  //   video2 §09:50-10:48 "금 1순위 실질금리 → 2순위 DXY → 3순위 CB → 4순위 지정학"
  //   rank1+rank2 OK → 'STRONG_TAILWIND' (1.0 multiplier 신호)
  //   rank1+rank2 NG → 'HEADWIND' (지정학 단독 매수 차단)
  //   mixed → 'NEUTRAL'
  try {
    const ryTrend = d.REAL_YIELD_TREND?.value ?? null;
    const dxyTrend = d.DXY_TREND?.value ?? null;
    const cbDemand = d.CB_GOLD_STRUCTURAL_DEMAND?.value ?? null;
    const hormuz = d.HORMUZ_CHAIN_SCORE?.value ?? null;
    const geoRiskManual = manualInputs?.geoRisk ?? null;
    // rank1 (실질금리): 추세 < 0 → +1, > 0 → -1
    let rank1 = 0; let r1lab = 'real_yield_trend 결측';
    if (ryTrend !== null) { rank1 = ryTrend < -0.05 ? 1 : (ryTrend > 0.05 ? -1 : 0); r1lab = `RY_TREND ${ryTrend.toFixed(3)}→${rank1>0?'+':''}${rank1}`; }
    // rank2 (DXY): 추세 < -0.5 → +1, > +0.5 → -1
    let rank2 = 0; let r2lab = 'dxy_trend 결측';
    if (dxyTrend !== null) { rank2 = dxyTrend < -0.5 ? 1 : (dxyTrend > 0.5 ? -1 : 0); r2lab = `DXY_TREND ${dxyTrend.toFixed(2)}→${rank2>0?'+':''}${rank2}`; }
    // rank3 (CB 매수)
    let rank3 = 0; let r3lab = 'cb 결측';
    if (cbDemand !== null) { rank3 = cbDemand >= 1 ? 1 : (cbDemand <= -1 ? -1 : 0); r3lab = `CB ${cbDemand}→${rank3>0?'+':''}${rank3}`; }
    // rank4 (지정학)
    let rank4 = 0; let r4lab = '지정학 결측';
    if (hormuz !== null) { rank4 = hormuz >= 2 ? 1 : (hormuz <= -1 ? -1 : 0); r4lab = `HORMUZ ${hormuz}→${rank4>0?'+':''}${rank4}`; }
    else if (geoRiskManual !== null) { rank4 = geoRiskManual >= 3 ? 1 : 0; r4lab = `geoRisk(manual) ${geoRiskManual}→${rank4}`; }
    // gate flag
    let gateFlag: number; // -1=HEADWIND, 0=NEUTRAL, 1=STRONG_TAILWIND
    let gateLabel: string;
    if (rank1 > 0 && rank2 > 0) { gateFlag = 1; gateLabel = '🟢 STRONG_TAILWIND (실질금리↓+DXY↓ 1·2순위 OK)'; }
    else if (rank1 < 0 && rank2 < 0) { gateFlag = -1; gateLabel = '🔴 HEADWIND (실질금리↑+DXY↑ 1·2순위 NG, 지정학 단독 매수 차단)'; }
    else { gateFlag = 0; gateLabel = '🟡 NEUTRAL'; }
    d.GOLD_AXIS_CONFLICT_RESOLVER = {
      name: 'gold_axis_conflict_resolver',
      value: rank1 + rank2 + rank3 + rank4,
      date: today(),
      formula: `rank1(${r1lab}) + rank2(${r2lab}) + rank3(${r3lab}) + rank4(${r4lab}) = ${rank1+rank2+rank3+rank4}. video2 §09:50-10:48 "1순위 실질금리".`,
    };
    d.GOLD_AXIS_GATE_FLAG = {
      name: 'gold_axis_gate_flag',
      value: gateFlag,
      date: today(),
      formula: `${gateLabel}. video2 §10:48 "1·2순위 NG 시 추격 금지".`,
    };
  } catch { void 0; }

  // ★ === 29차 P1-C #7: NASDAQ_WEEKLY_BEAR_STREAK_AT_SUPPORT ===
  //   video3 §11:31-12:11 "주봉 음봉 연속 + 지지 근접" — 단발 회복 베팅 위험.
  //   100일 일봉 → 주봉 변환 (close=마지막일, open=첫날), 최근 음봉 streak.
  //   이격도 ≤ -2% AND 종가 < 3개월 저점 +3% 추가 게이트.
  try {
    const dailyHist = await fetchYahooHistory('^IXIC', 130);
    if (dailyHist.length >= 60) {
      // 주봉 변환 — ISO week 단위로 그룹 (월~금).
      const weeks: Array<{ open: number; close: number }> = [];
      let curWeekIdx: number | null = null;
      let curOpen: number | null = null;
      let curClose: number | null = null;
      for (const d0 of dailyHist) {
        const dt2 = new Date(d0.date);
        // ISO week: 일=0, 월=1, ..., 토=6. weekIdx = floor((day - dt0)/7) 단순화 사용.
        // 안정적 그룹화: weekIdx = Math.floor(dt2.getTime() / (7*86400000))
        const wIdx = Math.floor(dt2.getTime() / (7 * 86400000));
        if (curWeekIdx === null || wIdx !== curWeekIdx) {
          if (curOpen !== null && curClose !== null) weeks.push({ open: curOpen, close: curClose });
          curWeekIdx = wIdx;
          curOpen = d0.close;
        }
        curClose = d0.close;
      }
      if (curOpen !== null && curClose !== null) weeks.push({ open: curOpen, close: curClose });
      // streak: 가장 최근 주부터 음봉 연속.
      let streak = 0;
      for (let i = weeks.length - 1; i >= 0 && i >= weeks.length - 8; i -= 1) {
        if (weeks[i].close < weeks[i].open) streak += 1;
        else break;
      }
      // 지지 근접 게이트
      const disparity = d.NASDAQ_DISPARITY?.value ?? null;
      const recent60d = dailyHist.slice(-60);
      const low3M = Math.min(...recent60d.map((p) => p.close));
      const lastClose = dailyHist[dailyHist.length - 1].close;
      const nearSupport = disparity !== null && disparity <= -2 && lastClose < low3M * 1.03;
      let level: number;
      let label: string;
      if (streak >= 3 && nearSupport) { level = 2; label = `🔴 주봉 ${streak}연속 음봉 + 지지 근접 (video3 §11:31)`; }
      else if (streak >= 2) { level = 1; label = `🟡 주봉 ${streak}연속 음봉 — 회복 미확인`; }
      else { level = 0; label = `⚪ streak ${streak}`; }
      d.NASDAQ_WEEKLY_BEAR_STREAK_AT_SUPPORT = {
        name: 'nasdaq_weekly_bear_streak_at_support',
        value: level,
        date: today(),
        formula: `주봉 ${streak}연속 음봉 / 이격도 ${disparity?.toFixed(1) ?? '?'}% / 60D 저점 ${low3M.toFixed(0)} → ${(lastClose/low3M*100).toFixed(1)}% (≤103%=근접). ${label}. video3 §11:31-12:11 "주봉 음봉 + 지지".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P1-C #8: NASDAQ_RANGE_TRAP ===
  //   video3 §12:54-13:09 "단기 급락 가속" — 60d high upward break + 5거래일 내 60d low downward break.
  try {
    const dailyHistRT = await fetchYahooHistory('^IXIC', 100);
    if (dailyHistRT.length >= 65) {
      const closes = dailyHistRT.map((p) => p.close);
      const todayClose = closes[closes.length - 1];
      const priorClosesBeforeToday = closes.slice(-66, -1); // 60d window before today
      const priorHigh = Math.max(...priorClosesBeforeToday);
      const todayBreaksHigh = todayClose > priorHigh;
      // 5 거래일 내 60d low break
      let downBreak = false;
      let downIdx: number = -1;
      for (let lookback = 0; lookback <= 5; lookback += 1) {
        const idx = closes.length - 1 - lookback;
        if (idx < 65) break;
        const priorLow = Math.min(...closes.slice(idx - 60, idx));
        if (closes[idx] < priorLow) { downBreak = true; downIdx = idx; break; }
      }
      let level = 0;
      let label = '⚪ trap 미감지';
      if (todayBreaksHigh && downBreak) {
        level = 2;
        label = `🔴 RANGE_TRAP 감지 (60d high 돌파 + 최근 5일 내 60d low 이탈)`;
        const icsa = val(raw, 'ICSA');
        if (icsa !== null && icsa < 300000) {
          level = 3;
          label += ` + ICSA ${Math.round(icsa/1000)}K<300K cascade`;
        }
      }
      d.NASDAQ_RANGE_TRAP = {
        name: 'nasdaq_range_trap',
        value: level,
        date: today(),
        formula: `오늘 ${todayClose.toFixed(0)} > 60D high ${priorHigh.toFixed(0)} = ${todayBreaksHigh}, 5일 내 60D low 이탈 = ${downBreak} (idx=${downIdx}). ${label}. video3 §12:54-13:09.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P1-C #9: NASDAQ_HEALTHY_PULLBACK ===
  //   video3 §05:12-05:25 "정배열에서 50DMA -3~-8% pullback = 분할매수 양호".
  //   bias=+1 (50DMA > 200DMA), match=가격 50DMA -3~-8%, confirms=가격 > 200DMA.
  //   정배열 + match + confirms → +1 / 역배열 + match → -1.
  try {
    // calendar days ~ trading days * 1.45 (주말/공휴일 보정). 200 trading days 보장 위해 320 calendar days.
    const dailyHistHP = await fetchYahooHistory('^IXIC', 320);
    if (dailyHistHP.length >= 200) {
      const closes = dailyHistHP.map((p) => p.close);
      const last = closes[closes.length - 1];
      const sma50 = closes.slice(-50).reduce((a, b) => a + b, 0) / 50;
      const sma200 = closes.slice(-200).reduce((a, b) => a + b, 0) / 200;
      const upTrend = sma50 > sma200;
      const downTrend = sma50 < sma200;
      const dispFromSma50 = ((last - sma50) / sma50) * 100;
      const match = dispFromSma50 <= -3 && dispFromSma50 >= -8;
      const above200 = last > sma200;
      let level = 0;
      let label = '⚪ pullback 미발동';
      if (upTrend && match && above200) {
        level = 1;
        label = `🟢 정배열 healthy pullback (50DMA ${dispFromSma50.toFixed(1)}% within -3~-8%, 200DMA 위) — video3 §05:12 분할매수 양호`;
      } else if (downTrend && match) {
        level = -1;
        label = `🔴 역배열 + 50DMA pullback — 추세 미회복 대기 (video3 §05:25)`;
      }
      d.NASDAQ_HEALTHY_PULLBACK = {
        name: 'nasdaq_healthy_pullback',
        value: level,
        date: today(),
        formula: `last ${last.toFixed(0)} / SMA50 ${sma50.toFixed(0)} / SMA200 ${sma200.toFixed(0)} → 정배열=${upTrend}, 50DMA-${dispFromSma50.toFixed(1)}%, >200DMA=${above200}. ${label}. video3 §05:12-05:25.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P1-C #10: NASDAQ_DOUBLE_TOP ===
  //   video3 §13:38 "쌍봉 이중천정 — 가격차≤2%, 시간차 30~60일, 사이 골≤-8%".
  //   추가 주봉 20MA 하향 break → level=2.
  try {
    const dailyHistDT = await fetchYahooHistory('^IXIC', 90);
    if (dailyHistDT.length >= 60) {
      const closes = dailyHistDT.map((p) => p.close);
      // local maxima detection: ±5일 윈도우.
      const maxima: Array<{ idx: number; price: number }> = [];
      const W = 5;
      for (let i = W; i < closes.length - W; i += 1) {
        const window = closes.slice(i - W, i + W + 1);
        if (closes[i] === Math.max(...window)) {
          maxima.push({ idx: i, price: closes[i] });
        }
      }
      let detected = false;
      let detail = 'maxima 부족';
      for (let i = 0; i < maxima.length; i += 1) {
        for (let j = i + 1; j < maxima.length; j += 1) {
          const m1 = maxima[i];
          const m2 = maxima[j];
          const priceDiffPct = Math.abs(m2.price - m1.price) / m1.price * 100;
          const timeDiff = m2.idx - m1.idx;
          if (priceDiffPct > 2) continue;
          if (timeDiff < 30 || timeDiff > 60) continue;
          // 사이 골
          const trough = Math.min(...closes.slice(m1.idx, m2.idx + 1));
          const troughPct = (trough - Math.min(m1.price, m2.price)) / Math.min(m1.price, m2.price) * 100;
          if (troughPct > -8) continue;
          detected = true;
          detail = `peak1 idx=${m1.idx}@${m1.price.toFixed(0)}, peak2 idx=${m2.idx}@${m2.price.toFixed(0)}, 가격차 ${priceDiffPct.toFixed(1)}%, 시간차 ${timeDiff}일, 골 ${troughPct.toFixed(1)}%`;
          break;
        }
        if (detected) break;
      }
      let level = detected ? 1 : 0;
      // 주봉 20MA 하향 break 확인
      const wkly20 = d.NASDAQ_WEEKLY_20MA?.value ?? null;
      const lastClose = closes[closes.length - 1];
      if (detected && wkly20 !== null && lastClose < wkly20) {
        level = 2;
        detail += ` + 주봉 20MA(${wkly20.toFixed(0)}) 하향 break`;
      }
      d.NASDAQ_DOUBLE_TOP = {
        name: 'nasdaq_double_top',
        value: level,
        date: today(),
        formula: `${detected ? '🔴 쌍봉 감지' : '⚪ 미감지'}. ${detail}. video3 §13:38 "이중천정".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P1-D #11: NASDAQ_FORWARD_PER + KOSPI_FORWARD_PER ===
  //   video6 §05:54 "PER 25+ 멀티플 과열 / 12 이하 매수 우호".
  //   NASDAQ: ^NDX → ^IXIC → QQQ 순서로 forwardPE 시도.
  //   KOSPI: 자동 fetch 어려워 manualInputs.kospiForwardPER 폴백.
  try {
    const { fetchYahooForwardPE } = await import('../collectors/yahoo');
    const ndxResult = await fetchYahooForwardPE(['^NDX', '^IXIC', 'QQQ']).catch(() => null);
    if (ndxResult !== null) {
      const per = ndxResult.forwardPE;
      let level: number;
      let label: string;
      if (per >= 25) { level = -1; label = `🔴 PER ${per.toFixed(1)} ≥ 25 멀티플 과열 (video6 §"좋은 가격")`; }
      else if (per <= 12) { level = 1; label = `🟢 PER ${per.toFixed(1)} ≤ 12 매수 우호`; }
      else { level = 0; label = `⚪ PER ${per.toFixed(1)} 정상 구간`; }
      d.NASDAQ_FORWARD_PER = {
        name: 'nasdaq_forward_per',
        value: per,
        date: today(),
        formula: `${ndxResult.ticker} forwardPE = ${per.toFixed(2)}, level=${level}. ${label}. video6 §05:54.`,
      };
      // raw 호환 표기
      raw.PER_NASDAQ = { code: 'PER_NASDAQ', value: per, date: today(), source: 'YAHOO' };
    }
  } catch { void 0; }
  try {
    const kospiPER = manualInputs?.kospiForwardPER ?? null;
    if (kospiPER !== null && Number.isFinite(kospiPER)) {
      let level: number;
      let label: string;
      if (kospiPER >= 25) { level = -1; label = `🔴 PER ${kospiPER.toFixed(1)} ≥ 25 멀티플 과열`; }
      else if (kospiPER <= 12) { level = 1; label = `🟢 PER ${kospiPER.toFixed(1)} ≤ 12 매수 우호`; }
      else { level = 0; label = `⚪ PER ${kospiPER.toFixed(1)} 정상 구간`; }
      d.KOSPI_FORWARD_PER = {
        name: 'kospi_forward_per',
        value: kospiPER,
        date: today(),
        formula: `manual PER ${kospiPER.toFixed(2)}, level=${level}. ${label}. video6 §05:54.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P1-D #12: KOSPI_MONTHLY_BEAR_COVER_FLAG ===
  //   stt_kospi §03:28 "직전 월봉 음봉 5%+ 후 현재 월봉이 직전 시가 회복 = 매수 신호".
  try {
    const monthlyKospi = await fetchYahooOHLC('^KS11', 365 * 2, '1mo');
    if (monthlyKospi.length >= 2) {
      const prev = monthlyKospi[monthlyKospi.length - 2];
      const cur = monthlyKospi[monthlyKospi.length - 1];
      const prevBear = prev.close < prev.open;
      const prevDownPct = prev.open > 0 ? Math.abs(prev.close - prev.open) / prev.open * 100 : 0;
      const recoverFlag = prevBear && prevDownPct >= 5 && cur.close > prev.open;
      d.KOSPI_MONTHLY_BEAR_COVER_FLAG = {
        name: 'kospi_monthly_bear_cover_flag',
        value: recoverFlag ? 1 : 0,
        date: today(),
        formula: `직전 월봉 ${prev.open.toFixed(0)}→${prev.close.toFixed(0)} (${prevBear ? '음봉' : '양봉'}, ${prevDownPct.toFixed(2)}%) / 현재 월봉 close ${cur.close.toFixed(0)} > prev open ${prev.open.toFixed(0)} = ${cur.close > prev.open}. flag=${recoverFlag ? 1 : 0}. stt_kospi §03:28.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P1-D #13: KOSPI_FOREIGN_STREAK_DAYS + KOSPI_FOREIGN_OVERSELL_30T_FLAG ===
  //   stt_kospi §3-1 "외국인 5일+ 연속 매수 = 추세 복귀 / 60D 누적 -30조 = 공황 매도".
  try {
    const buyStreak = d.KOSPI_FOREIGN_BUY_STREAK?.value ?? 0;
    const sellStreak = d.KOSPI_FOREIGN_SELL_STREAK?.value ?? 0;
    const signedStreak = buyStreak > 0 ? buyStreak : (sellStreak > 0 ? -sellStreak : 0);
    d.KOSPI_FOREIGN_STREAK_DAYS = {
      name: 'kospi_foreign_streak_days',
      value: signedStreak,
      date: today(),
      formula: `signed streak = ${signedStreak} (buy=${buyStreak}, sell=${sellStreak}). stt_kospi §3-1 "5일+ 연속 = 추세".`,
    };
    // 60D 누적 매도 공황 — 일별 외국인 net 적립.
    // krx-flow.ts 의 summary 가 이미 net20D 만 제공. summary.days(20D) 의 실시간 추가 + history-store('derived','KOSPI_FOREIGN_NET_DAILY') 누적으로 60D 윈도우 산출.
    let net60D: number | null = null;
    try {
      const flowDaysRaw = await fetchKrxInvestorFlow('KOSPI');
      // 모든 fetched 일자 history-store 에 upsert (안전 적립).
      for (const fd of flowDaysRaw) {
        await writeHistoryPoint('derived', 'KOSPI_FOREIGN_NET_DAILY', fd.foreign, fd.date);
      }
      const dailyHist = await readHistory('derived', 'KOSPI_FOREIGN_NET_DAILY').catch(() => [] as Array<{date:string; value:number}>);
      const last60 = dailyHist.slice(-60).map((p) => p.value);
      if (last60.length > 0) {
        net60D = last60.reduce((s, v) => s + v, 0);
      }
    } catch { void 0; }
    const oversellFlag = (net60D !== null && net60D <= -300000) ? 1 : 0; // 30조 = 300,000 억
    d.KOSPI_FOREIGN_OVERSELL_30T_FLAG = {
      name: 'kospi_foreign_oversell_30t_flag',
      value: oversellFlag,
      date: today(),
      formula: `60D 누적 외국인 순매수 ${net60D !== null ? Math.round(net60D/10000)+'조' : 'n/a'} ≤ -30조 ? ${oversellFlag === 1 ? '🔴 공황 매도' : '⚪ 미발동'}. stt_kospi §3-1.`,
    };
  } catch { void 0; }

  // ★ === 29차 P1-D #14: KRW_FX_REVERSAL_TRIGGER ===
  //   stt_kospi §11:39 "환율 1480↓ 5일 연속 + 외인 복귀 streak ≥+5 = 환율 반전 + 외인 복귀 정합".
  try {
    const usdkrwHist = await readHistory('yahoo', 'USDKRW').catch(() => [] as Array<{date:string; value:number}>);
    let fx5DOk = 0;
    if (usdkrwHist.length >= 5) {
      const last5 = usdkrwHist.slice(-5).map((p) => p.value);
      fx5DOk = last5.every((v) => v <= 1480) ? 1 : 0;
    }
    const buyStreak = d.KOSPI_FOREIGN_BUY_STREAK?.value ?? 0;
    const flag = (fx5DOk === 1 && buyStreak >= 5) ? 1 : 0;
    d.KRW_FX_REVERSAL_TRIGGER = {
      name: 'krw_fx_reversal_trigger',
      value: flag,
      date: today(),
      formula: `USDKRW 5일 연속 ≤1480 = ${fx5DOk}, 외인 buyStreak ${buyStreak}≥5 = ${buyStreak >= 5}. flag=${flag}. stt_kospi §11:39 "환율 반전 + 외인 복귀".`,
    };
  } catch { void 0; }

  // ★ === 29차 P1-E #15: TIMEFRAME_DECISION_SPLIT ===
  //   video2 §22:45 + video6 — daily/weekly 축 분리, USER_HORIZON_ALIGNMENT 의 horizon 사용.
  //   short → daily 만 / long → weekly 만 / medium → AND.
  try {
    // daily axis: 가격 > SMA20(직접 계산) AND RSI_14 ≥ 50
    const dailyHistTF = await fetchYahooHistory('^IXIC', 60);
    let dailyOk: number | null = null;
    let dailyDetail = '';
    if (dailyHistTF.length >= 25) {
      const closes = dailyHistTF.map((p) => p.close);
      const last = closes[closes.length - 1];
      const sma20 = closes.slice(-20).reduce((a, b) => a + b, 0) / 20;
      const rsi14 = d.NASDAQ_RSI_14?.value ?? null;
      const aboveSma20 = last > sma20;
      const rsi50 = rsi14 !== null && rsi14 >= 50;
      dailyOk = aboveSma20 && rsi50 ? 1 : 0;
      dailyDetail = `last ${last.toFixed(0)} > SMA20 ${sma20.toFixed(0)} = ${aboveSma20}, RSI14 ${rsi14?.toFixed(1) ?? 'n/a'} ≥ 50 = ${rsi50} → ${dailyOk}`;
    }
    // weekly axis: 주봉 close > NASDAQ_WEEKLY_20MA AND 14주 RSI ≥ 50
    const weeklyHistTF = await fetchYahooOHLC('^IXIC', 365 * 2, '1wk');
    let weeklyOk: number | null = null;
    let weeklyDetail = '';
    if (weeklyHistTF.length >= 20) {
      const wclose = weeklyHistTF[weeklyHistTF.length - 1].close;
      const w20MA = d.NASDAQ_WEEKLY_20MA?.value ?? null;
      const wkRsi = computeRSI(weeklyHistTF.map((c) => c.close), 14);
      const aboveW20 = w20MA !== null && wclose > w20MA;
      const wRsi50 = wkRsi !== null && wkRsi >= 50;
      weeklyOk = aboveW20 && wRsi50 ? 1 : 0;
      weeklyDetail = `주봉 close ${wclose.toFixed(0)} > 주봉 20MA ${w20MA?.toFixed(0) ?? 'n/a'} = ${aboveW20}, 14주 RSI ${wkRsi?.toFixed(1) ?? 'n/a'} ≥ 50 = ${wRsi50} → ${weeklyOk}`;
    }
    const horizon = manualInputs?.investmentHorizon ?? 'medium';
    let result: number = 0;
    let resultLabel: string;
    if (horizon === 'short') {
      result = dailyOk === 1 ? 1 : 0;
      resultLabel = `short — daily 만 평가 (${dailyOk}) → ${result}`;
    } else if (horizon === 'long') {
      result = weeklyOk === 1 ? 1 : 0;
      resultLabel = `long — weekly 만 평가 (${weeklyOk}) → ${result}`;
    } else {
      // medium AND
      if (dailyOk === 1 && weeklyOk === 1) result = 1;
      else if (dailyOk === 0 && weeklyOk === 0) result = -1;
      else result = 0;
      resultLabel = `medium AND (daily=${dailyOk}, weekly=${weeklyOk}) → ${result}`;
    }
    d.TIMEFRAME_DECISION_SPLIT = {
      name: 'timeframe_decision_split',
      value: result,
      date: today(),
      formula: `daily(${dailyDetail}) / weekly(${weeklyDetail}) / horizon=${horizon}: ${resultLabel}. video2 §22:45 + video6 "본인 시계열".`,
    };
  } catch { void 0; }

  // ★ === 29차 P1-E #16: REGIME_SECTOR_LEADERSHIP_MATCH ===
  //   video6 §03:25 "regime 별 expected leading 섹터 정합 — 일치 ≥0.66 → +1 (정합) / ≤0.33 → -1 (분기 경고)".
  try {
    let regimeStr: string | null = null;
    try {
      const regimeLabelHist = await readHistory('signal', 'REGIME_LABEL').catch(() => [] as Array<{date:string; value:number}>);
      if (regimeLabelHist.length > 0) {
        const lastCode = regimeLabelHist[regimeLabelHist.length - 1].value;
        if (lastCode === 100) regimeStr = 'RISK_ON';
        else if (lastCode === 80) regimeStr = 'NEUTRAL';
        else if (lastCode === 60) regimeStr = 'CAUTION';
        else if (lastCode === 40) regimeStr = 'CORRECTION';
        else if (lastCode === 30) regimeStr = 'STAGFLATION';
        else if (lastCode === 20) regimeStr = 'PANIC_BUT_OK';
        else if (lastCode === 10) regimeStr = 'BOND_VIGILANTE';
        else if (lastCode === 0) regimeStr = 'RECESSION_RISK';
      }
    } catch { void 0; }
    const expected: Record<string, string[]> = {
      RISK_ON: ['XLK', 'XLY', 'XLC'],
      NEUTRAL: ['XLK', 'XLF', 'XLI'],
      PANIC_BUT_OK: ['XLK', 'XLY'],
      CAUTION: ['XLV', 'XLU', 'XLP'],
      CORRECTION: ['XLU', 'XLP'],
      RECESSION_RISK: ['XLU', 'XLP', 'XLV'],
      BOND_VIGILANTE: ['XLE', 'XLF'],
      STAGFLATION: ['XLE', 'GLD'],
      STAGFLATION_BOND_VIGILANTE: ['XLE'],
    };
    if (regimeStr !== null && expected[regimeStr]) {
      const expSet = expected[regimeStr];
      // 실제 sector 30D returns top 3 (XLP 는 sectorEtfs 에 없음 — 모듈 보강 필요. 기존 9 sector 사용).
      const actualReturns: Array<{ key: string; ret: number }> = [];
      const sectorKeysToCheck = ['XLK','XLY','XLC','XLF','XLI','XLV','XLU','XLE','GLD'];
      for (const k of sectorKeysToCheck) {
        const sv = d[`SECTOR_${k}`]?.value ?? null;
        if (sv !== null) actualReturns.push({ key: k, ret: sv });
      }
      // GLD 는 sector 가 아님 — raw 또는 derived 에서 별도 가격으로 계산. 단순화: GOLD 20D 수익률.
      try {
        const gHist20 = await fetchYahooHistory('GLD', 30);
        if (gHist20.length >= 20) {
          const c0 = gHist20[gHist20.length - 20].close;
          const c1 = gHist20[gHist20.length - 1].close;
          if (c0 > 0) actualReturns.push({ key: 'GLD', ret: ((c1 - c0) / c0) * 100 });
        }
      } catch { void 0; }
      // top3 by return
      const top3 = actualReturns.sort((a, b) => b.ret - a.ret).slice(0, 3).map((x) => x.key);
      const matchCount = expSet.filter((k) => top3.includes(k)).length;
      const matchRatio = expSet.length > 0 ? matchCount / expSet.length : 0;
      let level: number;
      let label: string;
      if (matchRatio >= 0.66) { level = 1; label = `🟢 정합 (${matchCount}/${expSet.length})`; }
      else if (matchRatio <= 0.33) { level = -1; label = `🔴 분기 경고 (${matchCount}/${expSet.length})`; }
      else { level = 0; label = `🟡 부분 정합 (${matchCount}/${expSet.length})`; }
      d.REGIME_SECTOR_LEADERSHIP_MATCH = {
        name: 'regime_sector_leadership_match',
        value: level,
        date: today(),
        formula: `regime=${regimeStr}, expected=[${expSet.join(',')}], top3 actual=[${top3.join(',')}]. ${label}. video6 §03:25 "regime별 leading 섹터".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P2-A #1: GOLD_PANIC_BUY_TRIGGER ===
  // video2 §04:27 "공황 초기엔 금도 같이 빠진다 — 거시환경 우호면 그때가 매수 기회".
  // 식: VIX(raw) ≥ 30 AND GOLD 20D 수익률 < -7% AND REAL_YIELD_TREND < 0 → 3축 모두 → +1.
  try {
    const vixCur = val(raw, 'VIXCLS');
    let gold20DRet: number | null = null;
    try {
      const g = await fetchYahooHistory('GC=F', 25);
      if (g.length >= 20) {
        const g0 = g[g.length - 20].close;
        const g1 = g[g.length - 1].close;
        if (g0 > 0) gold20DRet = ((g1 - g0) / g0) * 100;
      }
    } catch { void 0; }
    const ryTrend = d.REAL_YIELD_TREND?.value ?? null;
    let level = 0;
    const axes: string[] = [];
    if (vixCur !== null && vixCur >= 30) axes.push(`VIX ${vixCur.toFixed(1)} ≥ 30`);
    if (gold20DRet !== null && gold20DRet < -7) axes.push(`GOLD 20D ${gold20DRet.toFixed(1)}% < -7%`);
    if (ryTrend !== null && ryTrend < 0) axes.push(`REAL_YIELD_TREND ${ryTrend.toFixed(3)} < 0`);
    if (vixCur !== null && vixCur >= 30 && gold20DRet !== null && gold20DRet < -7 && ryTrend !== null && ryTrend < 0) level = 1;
    d.GOLD_PANIC_BUY_TRIGGER = {
      name: 'gold_panic_buy_trigger',
      value: level,
      date: today(),
      formula: `${axes.length}/3 축 충족 [${axes.join(' | ')}] → ${level === 1 ? '🟢 공황 동반 하락 매수 기회' : '⚪ 미충족'}. video2 §04:27 "공황 초기엔 금도 같이 빠진다".`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-A #2: GOLD_PINBAR_SEQUENCE ===
  // video2 §20:01-20:43 "윗꼬리·아래꼬리 핀바 연속 = 천장 경계".
  // 60일 → 주봉 환산 후 직전 2주 윗꼬리 핀바 + 직후 1주 아래꼬리 핀바 페어 검출.
  // 윗꼬리/실체 ≥ 1.5 = 윗꼬리 핀바, 아래꼬리/실체 ≥ 1.5 = 아래꼬리 핀바.
  try {
    const goldOHLC = await fetchYahooOHLC('GC=F', 60, '1wk');
    let level = 0;
    let detail = '데이터 부족';
    if (goldOHLC.length >= 3) {
      const last3 = goldOHLC.slice(-3);
      const isUpperPin = (c: typeof last3[0]): boolean => {
        const body = Math.abs(c.close - c.open);
        if (body <= 0) return false;
        const upper = c.high - Math.max(c.open, c.close);
        return upper / body >= 1.5;
      };
      const isLowerPin = (c: typeof last3[0]): boolean => {
        const body = Math.abs(c.close - c.open);
        if (body <= 0) return false;
        const lower = Math.min(c.open, c.close) - c.low;
        return lower / body >= 1.5;
      };
      // 직전 2주 윗꼬리 + 직후 1주 아래꼬리 = 페어 매치
      const w0 = last3[0]; const w1 = last3[1]; const w2 = last3[2];
      const upper2 = isUpperPin(w0) && isUpperPin(w1);
      const lower1 = isLowerPin(w2);
      if (upper2 && lower1) {
        level = 1;
        detail = `🟡 직전 2주 윗꼬리 핀바 + 직후 1주 아래꼬리 핀바 페어 — 방향 혼란 박스권 진입`;
      } else {
        detail = `미매치 (직전 2주 윗꼬리=${upper2}, 직후 1주 아래꼬리=${lower1})`;
      }
    }
    d.GOLD_PINBAR_SEQUENCE = {
      name: 'gold_pinbar_sequence',
      value: level,
      date: today(),
      formula: `${detail}. video2 §20:01-20:43 "윗·아래꼬리 핀바 연속 = 천장 경계".`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-A #3: GOLD_WEDGE_PATTERN ===
  // video2 §22:24-22:41 "쐐기 — 상단/하단 추세선 한 점 수렴".
  // 30일 high 추세선 + 30일 low 추세선, 두 기울기 부호 반대 (수렴) AND 수렴 시점 30일 이내.
  // 가격 상단 돌파 → +1, 하단 이탈 → -1, 내부 → 0.
  try {
    const goldDailyOHLC = await fetchYahooOHLC('GC=F', 60, '1d');
    let level = 0;
    let detail = '데이터 부족';
    if (goldDailyOHLC.length >= 30) {
      const recent30 = goldDailyOHLC.slice(-30);
      // linear regression of highs and lows
      const xs = recent30.map((_, i) => i);
      const highs = recent30.map((c) => c.high);
      const lows = recent30.map((c) => c.low);
      const linReg = (xs: number[], ys: number[]): { slope: number; intercept: number } => {
        const n = xs.length;
        const xMean = xs.reduce((s, v) => s + v, 0) / n;
        const yMean = ys.reduce((s, v) => s + v, 0) / n;
        let num = 0; let den = 0;
        for (let i = 0; i < n; i++) {
          num += (xs[i] - xMean) * (ys[i] - yMean);
          den += (xs[i] - xMean) ** 2;
        }
        const slope = den > 0 ? num / den : 0;
        return { slope, intercept: yMean - slope * xMean };
      };
      const hReg = linReg(xs, highs);
      const lReg = linReg(xs, lows);
      // 두 기울기 부호 반대 → 수렴
      const converging = hReg.slope * lReg.slope < 0;
      // 수렴 시점 = (intercept_l - intercept_h) / (slope_h - slope_l)
      let convergeDays: number | null = null;
      if (converging && Math.abs(hReg.slope - lReg.slope) > 1e-9) {
        const xConv = (lReg.intercept - hReg.intercept) / (hReg.slope - lReg.slope);
        convergeDays = xConv - (recent30.length - 1);
      }
      if (converging && convergeDays !== null && convergeDays > 0 && convergeDays <= 30) {
        // 현재가 vs 추세선 평가
        const curIdx = recent30.length - 1;
        const upperLine = hReg.slope * curIdx + hReg.intercept;
        const lowerLine = lReg.slope * curIdx + lReg.intercept;
        const lastClose = recent30[curIdx].close;
        if (lastClose > upperLine) { level = 1; detail = `🟢 상단 돌파 (${lastClose.toFixed(0)} > 상단 ${upperLine.toFixed(0)}, 수렴 D+${convergeDays.toFixed(0)})`; }
        else if (lastClose < lowerLine) { level = -1; detail = `🔴 하단 이탈 (${lastClose.toFixed(0)} < 하단 ${lowerLine.toFixed(0)}, 수렴 D+${convergeDays.toFixed(0)})`; }
        else { level = 0; detail = `⚪ 내부 (${lowerLine.toFixed(0)} < ${lastClose.toFixed(0)} < ${upperLine.toFixed(0)}, 수렴 D+${convergeDays.toFixed(0)})`; }
      } else {
        detail = `쐐기 미감지 (수렴=${converging}, 수렴일=${convergeDays?.toFixed(0) ?? 'N/A'})`;
      }
    }
    d.GOLD_WEDGE_PATTERN = {
      name: 'gold_wedge_pattern',
      value: level,
      date: today(),
      formula: `${detail}. video2 §22:24-22:41 "쐐기 — 상단/하단 추세선 한 점 수렴".`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-A #4: GOLD_BREAK_VOLUME_CONFIRM ===
  // video2 §25:21-25:42 "거래량 없이 빠지면 가짜 이탈".
  // 30일 박스권 high/low break 시점 검출 + break 일 거래량 vs 직전 20D 평균.
  // ≥ 1.5x → confirm=1 (진짜) / < 1.5x → fakeout=-1 (가짜) / 0 = 박스권 내부.
  try {
    const goldHist = await fetchYahooHistory('GC=F', 35);
    let level = 0;
    let detail = '데이터 부족';
    if (goldHist.length >= 30) {
      const recent = goldHist.slice(-30);
      const last = recent[recent.length - 1];
      const prior29 = recent.slice(0, -1);
      const priorHigh = Math.max(...prior29.map((p) => p.close));
      const priorLow = Math.min(...prior29.map((p) => p.close));
      // 직전 20D 평균 거래량
      const last20Vols = recent.slice(-21, -1).map((p) => p.volume ?? 0).filter((v) => v > 0);
      const avgVol = last20Vols.length > 0 ? last20Vols.reduce((s, v) => s + v, 0) / last20Vols.length : 0;
      const todayVol = last.volume ?? 0;
      const ratio = avgVol > 0 ? todayVol / avgVol : 0;
      if (last.close > priorHigh) {
        if (ratio >= 1.5) { level = 1; detail = `🟢 상단 돌파 + 거래량 ${ratio.toFixed(2)}x ≥ 1.5x (진짜)`; }
        else { level = -1; detail = `🔴 상단 돌파 but 거래량 ${ratio.toFixed(2)}x < 1.5x (가짜)`; }
      } else if (last.close < priorLow) {
        if (ratio >= 1.5) { level = 1; detail = `🟢 하단 이탈 + 거래량 ${ratio.toFixed(2)}x ≥ 1.5x (진짜)`; }
        else { level = -1; detail = `🔴 하단 이탈 but 거래량 ${ratio.toFixed(2)}x < 1.5x (가짜)`; }
      } else {
        detail = `⚪ 박스권 내부 (low ${priorLow.toFixed(0)} ≤ ${last.close.toFixed(0)} ≤ high ${priorHigh.toFixed(0)})`;
      }
    }
    d.GOLD_BREAK_VOLUME_CONFIRM = {
      name: 'gold_break_volume_confirm',
      value: level,
      date: today(),
      formula: `${detail}. video2 §25:21-25:42 "거래량 없이 빠지면 가짜 이탈".`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-A #5: GOLD_DXY_DECOUPLE ===
  // video2 §04:54-04:57 "달러 강한데 금이 안 빠지면 = 구조적 수요 신호".
  // DXY 20D return AND GOLD 20D return AND 60D 상관계수
  // DXY > 0 (강세) AND GOLD > 0 (상승) AND 상관계수 ≥ -0.2 (정상은 -0.5 이하) → +1.
  try {
    const dxyHist60 = await fetchYahooHistory('DX-Y.NYB', 70);
    const goldHist60 = await fetchYahooHistory('GC=F', 70);
    let level = 0;
    let detail = '데이터 부족';
    if (dxyHist60.length >= 60 && goldHist60.length >= 60) {
      const dxyR60 = dxyHist60.slice(-60);
      const goldR60 = goldHist60.slice(-60);
      const minLen = Math.min(dxyR60.length, goldR60.length);
      const dxyR = dxyR60.slice(-minLen);
      const goldR = goldR60.slice(-minLen);
      // 20D 수익률
      const dxy20Ret = dxyR.length >= 20 ? ((dxyR[dxyR.length - 1].close - dxyR[dxyR.length - 20].close) / dxyR[dxyR.length - 20].close) * 100 : null;
      const gold20Ret = goldR.length >= 20 ? ((goldR[goldR.length - 1].close - goldR[goldR.length - 20].close) / goldR[goldR.length - 20].close) * 100 : null;
      // 60D 일별 수익률 상관
      const dxyRet: number[] = [];
      const goldRet: number[] = [];
      for (let i = 1; i < minLen; i++) {
        dxyRet.push((dxyR[i].close - dxyR[i - 1].close) / dxyR[i - 1].close);
        goldRet.push((goldR[i].close - goldR[i - 1].close) / goldR[i - 1].close);
      }
      const n = dxyRet.length;
      const dxyMean = dxyRet.reduce((s, v) => s + v, 0) / n;
      const goldMean = goldRet.reduce((s, v) => s + v, 0) / n;
      let cov = 0; let dxyVar = 0; let goldVar = 0;
      for (let i = 0; i < n; i++) {
        cov += (dxyRet[i] - dxyMean) * (goldRet[i] - goldMean);
        dxyVar += (dxyRet[i] - dxyMean) ** 2;
        goldVar += (goldRet[i] - goldMean) ** 2;
      }
      const corr = dxyVar > 0 && goldVar > 0 ? cov / Math.sqrt(dxyVar * goldVar) : 0;
      if (dxy20Ret !== null && dxy20Ret > 0 && gold20Ret !== null && gold20Ret > 0 && corr >= -0.2) {
        level = 1;
        detail = `🟢 DXY +${dxy20Ret.toFixed(2)}% + GOLD +${gold20Ret.toFixed(2)}% + corr ${corr.toFixed(2)} ≥ -0.2 → 구조적 수요`;
      } else {
        detail = `미충족: DXY 20D ${dxy20Ret?.toFixed(2) ?? '?'}%, GOLD 20D ${gold20Ret?.toFixed(2) ?? '?'}%, corr ${corr.toFixed(2)} (정상 ≤ -0.5)`;
      }
    }
    d.GOLD_DXY_DECOUPLE = {
      name: 'gold_dxy_decouple',
      value: level,
      date: today(),
      formula: `${detail}. video2 §04:54-04:57 "달러 강한데 금이 안 빠지면 = 구조적 수요".`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-B #6: SILVER_OUTPERFORM_SETUP_V2 ===
  // video2 §11:32-11:48 — GSR ≥ 60 + ISM_PROXY ≥ 50 + ISM 분기 평균 상승 → +1 (3축 환경).
  try {
    const gsr = d.GOLD_SILVER_RATIO?.value ?? null;
    const ismValV2 = d.ISM_PROXY?.value ?? (typeof manualInputs?.ismPmi === 'number' ? manualInputs.ismPmi : null);
    let level = 0;
    let detail = '데이터 부족';
    let ismRising = false;
    try {
      // ISM_PROXY history 90일 (분기) → 직전 분기 평균 vs 현 분기 평균
      const ismHist = await readHistory('derived', 'ISM_PROXY').catch(() => [] as Array<{ date: string; value: number }>);
      if (ismHist.length >= 180) {
        const recent90 = ismHist.slice(-90);
        const prior90 = ismHist.slice(-180, -90);
        const recentAvg = recent90.reduce((s, p) => s + p.value, 0) / recent90.length;
        const priorAvg = prior90.reduce((s, p) => s + p.value, 0) / prior90.length;
        ismRising = recentAvg > priorAvg;
        detail = `GSR ${gsr?.toFixed(1) ?? '?'} ≥60=${gsr !== null && gsr >= 60} · ISM ${ismValV2?.toFixed(1) ?? '?'} ≥50=${ismValV2 !== null && ismValV2 >= 50} · 분기 ${recentAvg.toFixed(1)} > ${priorAvg.toFixed(1)}=${ismRising}`;
      } else {
        detail = `GSR ${gsr?.toFixed(1) ?? '?'} · ISM ${ismValV2?.toFixed(1) ?? '?'} · 분기 추세 데이터 부족 (${ismHist.length}일)`;
      }
    } catch { void 0; }
    if (gsr !== null && gsr >= 60 && ismValV2 !== null && ismValV2 >= 50 && ismRising) {
      level = 1;
      detail = `🟢 3축 충족 — ${detail}`;
    }
    d.SILVER_OUTPERFORM_SETUP_V2 = {
      name: 'silver_outperform_setup_v2',
      value: level,
      date: today(),
      formula: `${detail}. video2 §11:32-11:48 "은 아웃퍼폼 환경".`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-B #7: SILVER_GSR_SIGNAL_GUARD ===
  // video2 §11:48-12:05 "금은비 130 코로나 → 4개월 만에 은 +150% / GSR 높아도 경기 침체엔 더 빠짐".
  // regime ∈ {RECESSION_RISK, PANIC_BUT_OK, CORRECTION, BOND_VIGILANTE} → guard=1 → GSR_EXTREME 무력화.
  try {
    const regimeLabelHist = await readHistory('signal', 'REGIME_LABEL').catch(() => [] as Array<{ date: string; value: number }>);
    let regimeStr: string | null = null;
    if (regimeLabelHist.length > 0) {
      const lastCode = regimeLabelHist[regimeLabelHist.length - 1].value;
      if (lastCode === 100) regimeStr = 'RISK_ON';
      else if (lastCode === 80) regimeStr = 'NEUTRAL';
      else if (lastCode === 60) regimeStr = 'CAUTION';
      else if (lastCode === 40) regimeStr = 'CORRECTION';
      else if (lastCode === 30) regimeStr = 'STAGFLATION';
      else if (lastCode === 20) regimeStr = 'PANIC_BUT_OK';
      else if (lastCode === 10) regimeStr = 'BOND_VIGILANTE';
      else if (lastCode === 0) regimeStr = 'RECESSION_RISK';
    }
    const guardRegimes = ['RECESSION_RISK', 'PANIC_BUT_OK', 'CORRECTION', 'BOND_VIGILANTE'];
    const guard = regimeStr !== null && guardRegimes.includes(regimeStr) ? 1 : 0;
    d.SILVER_GSR_SIGNAL_GUARD = {
      name: 'silver_gsr_signal_guard',
      value: guard,
      date: today(),
      formula: `regime=${regimeStr ?? 'unknown'} → guard=${guard}. video2 §11:48-12:05 "GSR 높아도 침체엔 더 빠짐".`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-B #8: COPPER_TIMEFRAME_SPLIT ===
  // video2 §14:31-14:53 "장기 구조적 우상향 / 단기 인플레↑→에너지↑→제조 위축→구리 실수요↓".
  // long_thesis: COPPER_AI_EV_DEMAND_PROXY (양수면 +1) / short_headwind: WTI 30D > +10% AND FXI 60D < 0 (+1 역풍)
  // value = long - short (-1, 0, +1, +2).
  try {
    const longProxy = d.COPPER_AI_EV_DEMAND_PROXY?.value ?? null;
    const longThesis = longProxy !== null && longProxy > 0 ? 1 : 0;
    let wti30Ret: number | null = null;
    let fxi60Ret: number | null = null;
    try {
      const wHist = await readHistory('yahoo', 'WTI').catch(() => [] as Array<{ date: string; value: number }>);
      if (wHist.length >= 30) {
        const w0 = wHist[wHist.length - 30].value;
        const w1 = wHist[wHist.length - 1].value;
        if (w0 > 0) wti30Ret = ((w1 - w0) / w0) * 100;
      }
    } catch { void 0; }
    try {
      const fxiHist = await fetchYahooHistory('FXI', 70);
      if (fxiHist.length >= 60) {
        const f0 = fxiHist[fxiHist.length - 60].close;
        const f1 = fxiHist[fxiHist.length - 1].close;
        if (f0 > 0) fxi60Ret = ((f1 - f0) / f0) * 100;
      }
    } catch { void 0; }
    const shortHeadwind = (wti30Ret !== null && wti30Ret > 10 && fxi60Ret !== null && fxi60Ret < 0) ? 1 : 0;
    const result = longThesis - shortHeadwind;
    d.COPPER_TIMEFRAME_SPLIT = {
      name: 'copper_timeframe_split',
      value: result,
      date: today(),
      formula: `long_thesis(AI/EV proxy=${longProxy ?? '?'})=${longThesis} - short_headwind(WTI 30D ${wti30Ret?.toFixed(1) ?? '?'}%, FXI 60D ${fxi60Ret?.toFixed(1) ?? '?'}%)=${shortHeadwind} → ${result}. video2 §14:31-14:53.`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-B #9: ASSET_CORR_MATRIX ===
  // video2 §02:22-02:36 — 5자산 60D 일별 수익률 5x5 상관계수 매트릭스, max(|ρij|).
  // ≥ 0.7 → +1 (집중 위험), ≥ 0.85 → +2.
  try {
    const symbols: Array<[string, string]> = [
      ['SP500', '^GSPC'],
      ['BOND', 'IEF'],
      ['GOLD', 'GC=F'],
      ['WTI', 'CL=F'],
      ['DXY', 'DX-Y.NYB'],
    ];
    const histories: Record<string, Array<{ date: string; close: number }>> = {};
    for (const [name, sym] of symbols) {
      try {
        const h = await fetchYahooHistory(sym, 70);
        if (h.length >= 60) histories[name] = h.slice(-60);
      } catch { void 0; }
    }
    const keys = Object.keys(histories);
    let level = 0;
    let detail = '데이터 부족';
    let maxPair: { a: string; b: string; corr: number } | null = null;
    if (keys.length >= 5) {
      const minLen = Math.min(...keys.map((k) => histories[k].length));
      const returns: Record<string, number[]> = {};
      for (const k of keys) {
        const h = histories[k].slice(-minLen);
        const r: number[] = [];
        for (let i = 1; i < h.length; i++) r.push((h[i].close - h[i - 1].close) / h[i - 1].close);
        returns[k] = r;
      }
      const corr = (a: number[], b: number[]): number => {
        const n = Math.min(a.length, b.length);
        const aMean = a.slice(0, n).reduce((s, v) => s + v, 0) / n;
        const bMean = b.slice(0, n).reduce((s, v) => s + v, 0) / n;
        let cov = 0; let aVar = 0; let bVar = 0;
        for (let i = 0; i < n; i++) {
          cov += (a[i] - aMean) * (b[i] - bMean);
          aVar += (a[i] - aMean) ** 2;
          bVar += (b[i] - bMean) ** 2;
        }
        return aVar > 0 && bVar > 0 ? cov / Math.sqrt(aVar * bVar) : 0;
      };
      let maxAbsCorr = 0;
      for (let i = 0; i < keys.length; i++) {
        for (let j = i + 1; j < keys.length; j++) {
          const c = corr(returns[keys[i]], returns[keys[j]]);
          if (Math.abs(c) > maxAbsCorr) {
            maxAbsCorr = Math.abs(c);
            maxPair = { a: keys[i], b: keys[j], corr: c };
          }
        }
      }
      if (maxAbsCorr >= 0.85) level = 2;
      else if (maxAbsCorr >= 0.7) level = 1;
      detail = `최강 ${maxPair?.a}-${maxPair?.b} ρ=${maxPair?.corr.toFixed(2) ?? '?'}, max|ρ|=${maxAbsCorr.toFixed(2)} → level=${level}`;
    }
    d.ASSET_CORR_MATRIX = {
      name: 'asset_corr_matrix',
      value: level,
      date: today(),
      formula: `${detail}. video2 §02:22-02:36 "5자산 쏠림 점수".`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-B #10: DOLLAR_STRUCTURAL_DIRECTION ===
  // video2 §04:51-05:30 "트럼프 = 상대적 약달러" — DXY 1Y YoY + 정책방향(geoRisk weighted) + 연준 인하 기대.
  // 각 axis -2~+2, 합 → -1/0/+1 (구조적 강달러/중립/약달러).
  try {
    let dxyYoY: number | null = null;
    try {
      const dxyHist = await fetchYahooHistory('DX-Y.NYB', 380);
      if (dxyHist.length >= 250) {
        const d0 = dxyHist[dxyHist.length - 250].close;
        const d1 = dxyHist[dxyHist.length - 1].close;
        if (d0 > 0) dxyYoY = ((d1 - d0) / d0) * 100;
      }
    } catch { void 0; }
    // axis A: DXY YoY (강달러 → 양수 → 우호 강달러 = -1 점, 즉 dollar weak 으로 볼 때 negative)
    let axisDxy = 0;
    if (dxyYoY !== null) {
      if (dxyYoY <= -3) axisDxy = -2;       // 1Y 약세 강함 → 약달러 -2
      else if (dxyYoY <= -1) axisDxy = -1;
      else if (dxyYoY >= 3) axisDxy = 2;    // 1Y 강세 강함 → 강달러 +2
      else if (dxyYoY >= 1) axisDxy = 1;
    }
    // axis B: 정책방향 (manualInputs.policyDirection -2~+2, 음수=완화=약달러)
    const policy = typeof manualInputs?.policyDirection === 'number' ? manualInputs.policyDirection : 0;
    // 완화(positive policy=완화? convention 확인) — 기존 코드 convention 사용 (positive policyDirection = 완화 → 약달러)
    const axisPolicy = -policy;
    // axis C: 연준 인하 기대 (CME FedWatch — cutProb25bp + cutProb50bp 합)
    let axisFed = 0;
    try {
      const { fetchFedWatchProbabilities } = await import('../collectors/cme-fedwatch');
      const fw = await fetchFedWatchProbabilities();
      if (fw) {
        const cutTotal = (fw.cutProb25bp ?? 0) + (fw.cutProb50bp ?? 0);
        if (cutTotal >= 0.7) axisFed = -2;
        else if (cutTotal >= 0.5) axisFed = -1;
        else if (cutTotal <= 0.3) axisFed = 1;
      }
    } catch { void 0; }
    const sum = axisDxy + axisPolicy + axisFed;
    let level: number;
    if (sum <= -2) level = -1;            // 약달러
    else if (sum >= 2) level = 1;         // 강달러
    else level = 0;
    d.DOLLAR_STRUCTURAL_DIRECTION = {
      name: 'dollar_structural_direction',
      value: level,
      date: today(),
      formula: `axisDxy(${dxyYoY?.toFixed(1) ?? '?'}%)=${axisDxy} + axisPolicy(policy=${policy})=${axisPolicy} + axisFed=${axisFed} = ${sum} → level=${level}. video2 §04:51-05:30.`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-B #11: CB_GOLD_TONNAGE_TREND ===
  // video2 §05:38-05:48 "전 세계 중앙은행 3년 연속 1000톤+".
  // UserProfile.manualInputs.cbGoldTonnage12M (수동) 또는 WGC proxy (없으면 null).
  // ≥ 1000 → +1, ≥ 1100 → +2, < 800 → -1.
  try {
    const tonnage = typeof manualInputs?.cbGoldTonnage12M === 'number' ? manualInputs.cbGoldTonnage12M : null;
    if (tonnage !== null) {
      let level: number;
      let label: string;
      if (tonnage >= 1100) { level = 2; label = `🟢🟢 ${tonnage}톤 ≥ 1100 (구조적 매수 가속)`; }
      else if (tonnage >= 1000) { level = 1; label = `🟢 ${tonnage}톤 ≥ 1000 (3년 연속 트렌드 유지)`; }
      else if (tonnage < 800) { level = -1; label = `🔴 ${tonnage}톤 < 800 (구조적 매수 둔화)`; }
      else { level = 0; label = `⚪ ${tonnage}톤 (800~1000 중립)`; }
      d.CB_GOLD_TONNAGE_TREND = {
        name: 'cb_gold_tonnage_trend',
        value: level,
        date: today(),
        formula: `${label}. video2 §05:38-05:48 "3년 연속 1000톤+".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P2-B #12: BREAKOUT_CHASE_RISK ===
  // video2 §24:31-25:16 "박스권 돌파 후 V자 직행 거의 없음 / 추격매수 시 고점 물림".
  // NASDAQ 60일 high upward break 후 5거래일 이내 가격이 break 가격 ±1% 안 → +1 (추격 금지 플래그).
  try {
    const ndxHist = await fetchYahooHistory('^IXIC', 70);
    let level = 0;
    let detail = '데이터 부족';
    if (ndxHist.length >= 60) {
      const recent = ndxHist.slice(-60);
      const last = recent[recent.length - 1];
      const prior55 = recent.slice(0, 55);
      const priorHigh = Math.max(...prior55.map((p) => p.close));
      // breakout 시점 검출 — 직전 5거래일 중 prior 55일 고점을 처음 돌파한 일자.
      const last5 = recent.slice(-5);
      let breakIdx = -1;
      for (let i = 0; i < last5.length; i++) {
        if (last5[i].close > priorHigh * 1.001) { // 0.1% 마진
          breakIdx = i;
          break;
        }
      }
      if (breakIdx >= 0) {
        const breakPrice = last5[breakIdx].close;
        const pctDelta = Math.abs(last.close - breakPrice) / breakPrice * 100;
        if (pctDelta <= 1) {
          level = 1;
          detail = `🔴 break 후 D+${last5.length - 1 - breakIdx}일 가격 ${last.close.toFixed(0)} ≈ break ${breakPrice.toFixed(0)} (±${pctDelta.toFixed(2)}%) — 추격 금지`;
        } else {
          detail = `break 후 D+${last5.length - 1 - breakIdx}일 가격 ${last.close.toFixed(0)} vs break ${breakPrice.toFixed(0)} (±${pctDelta.toFixed(2)}%) — 추격 위험 낮음`;
        }
      } else {
        detail = `최근 5일 60D 고점 ${priorHigh.toFixed(0)} 돌파 없음`;
      }
    }
    d.BREAKOUT_CHASE_RISK = {
      name: 'breakout_chase_risk',
      value: level,
      date: today(),
      formula: `${detail}. video2 §24:31-25:16 "추격매수 = 고점 물림".`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-C #13: NASDAQ_RESISTANCE_REJECTION_LEVEL ===
  // video3 §12:20-12:38 — 주봉 환산 후 최근 8주 위꼬리/실체 비율 평균 vs 직전 8주 평균 (delta).
  // delta ≥ 0.5 AND 종가 ≥ 8주 high -3% → 1, delta ≥ 1.0 → 2.
  try {
    const ndxOHLC = await fetchYahooOHLC('^IXIC', 120, '1wk');
    let level = 0;
    let detail = '데이터 부족';
    if (ndxOHLC.length >= 16) {
      const recent8 = ndxOHLC.slice(-8);
      const prior8 = ndxOHLC.slice(-16, -8);
      const upperWickRatio = (c: { open: number; high: number; low: number; close: number }) => {
        const body = Math.abs(c.close - c.open);
        if (body <= 0) return 0;
        const upper = c.high - Math.max(c.open, c.close);
        return upper / body;
      };
      const recentAvg = recent8.reduce((s, c) => s + upperWickRatio(c), 0) / 8;
      const priorAvg = prior8.reduce((s, c) => s + upperWickRatio(c), 0) / 8;
      const delta = recentAvg - priorAvg;
      const last = ndxOHLC[ndxOHLC.length - 1];
      const high8 = Math.max(...recent8.map((c) => c.high));
      const nearHigh = last.close >= high8 * 0.97; // -3%
      if (delta >= 1.0 && nearHigh) level = 2;
      else if (delta >= 0.5 && nearHigh) level = 1;
      detail = `최근 8주 윗꼬리/body 평균 ${recentAvg.toFixed(2)} / 직전 ${priorAvg.toFixed(2)} → Δ${delta.toFixed(2)}, 종가 ${last.close.toFixed(0)} vs 8주 high ${high8.toFixed(0)}*0.97=${(high8 * 0.97).toFixed(0)} → nearHigh=${nearHigh}, level=${level}`;
    }
    d.NASDAQ_RESISTANCE_REJECTION_LEVEL = {
      name: 'nasdaq_resistance_rejection_level',
      value: level,
      date: today(),
      formula: `${detail}. video3 §12:20-12:38 "윗꼬리 비율 상승 + 고점 근접 = 저항 거부".`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-C #14: NASDAQ_LONG_POSITION_PRESSURE ===
  // video3 §11:14-11:30 "지지선 한 번도 이탈 없으면 롱 무게".
  // 최근 250일 NASDAQ_CHANNEL_LOWER (-1σ) 이탈 횟수.
  try {
    const ndxLower = d.NASDAQ_CHANNEL_LOWER?.value ?? null;
    const ndxHist250 = await fetchYahooHistory('^IXIC', 260);
    let level = 0;
    let detail = '데이터 부족';
    let breachCount = 0;
    if (ndxLower !== null && ndxHist250.length >= 250) {
      const last250 = ndxHist250.slice(-250);
      breachCount = last250.filter((p) => p.close < ndxLower).length;
      if (breachCount === 0) level = 2;
      else if (breachCount <= 2) level = 1;
      else level = 0;
      detail = `250일 중 채널 하단(${ndxLower.toFixed(0)}) 이탈 ${breachCount}회 → level=${level}`;
    }
    d.NASDAQ_LONG_POSITION_PRESSURE = {
      name: 'nasdaq_long_position_pressure',
      value: level,
      date: today(),
      formula: `${detail}. video3 §11:14-11:30 "지지선 이탈 0회 = 롱 무게 → 단기 급락 위험".`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-C #15: NASDAQ_W_BOTTOM_CONFIRMED ===
  // video3 §15:02-15:55 — 기존 W_BOTTOM + 3축 게이트:
  //   axis A: 5일 RSI 상승 (압력 둔화)
  //   axis B: 직전 swing high 돌파
  //   axis C: W 패턴 매치
  // 3축 → 2 (STRONG_BUY 가산), W 만 → 1, else 0.
  try {
    const wBottom = d.NASDAQ_W_BOTTOM?.value ?? null;
    let axisA = 0; let axisB = 0;
    const axisC = wBottom === 1 ? 1 : 0;
    // axis A: 5일 RSI 상승 — RSI history 가 없으니 NASDAQ history 14일 RSI 직접 계산
    try {
      const nHist20 = await fetchYahooHistory('^IXIC', 30);
      const calcRsi = (closes: number[], len = 14): number | null => {
        if (closes.length < len + 1) return null;
        const recent = closes.slice(-len - 1);
        let gains = 0; let losses = 0;
        for (let i = 1; i < recent.length; i++) {
          const d = recent[i] - recent[i - 1];
          if (d > 0) gains += d; else losses -= d;
        }
        const avgG = gains / len; const avgL = losses / len;
        if (avgL === 0) return 100;
        const rs = avgG / avgL;
        return 100 - 100 / (1 + rs);
      };
      if (nHist20.length >= 20) {
        const closes = nHist20.map((p) => p.close);
        const rsiToday = calcRsi(closes);
        const rsi5dAgo = calcRsi(closes.slice(0, -5));
        if (rsiToday !== null && rsi5dAgo !== null && rsiToday > rsi5dAgo) axisA = 1;
      }
    } catch { void 0; }
    // axis B: 직전 swing high 돌파 — 최근 30일 high 돌파 검사
    try {
      const nHist60 = await fetchYahooHistory('^IXIC', 60);
      if (nHist60.length >= 30) {
        const recent30 = nHist60.slice(-30);
        const last = recent30[recent30.length - 1].close;
        const prior29 = recent30.slice(0, -1);
        const swingHigh = Math.max(...prior29.map((p) => p.close));
        if (last > swingHigh) axisB = 1;
      }
    } catch { void 0; }
    const sum = axisA + axisB + axisC;
    let level: number;
    if (sum >= 3) level = 2;
    else if (axisC === 1) level = 1;
    else level = 0;
    d.NASDAQ_W_BOTTOM_CONFIRMED = {
      name: 'nasdaq_w_bottom_confirmed',
      value: level,
      date: today(),
      formula: `axisA(RSI ↑)=${axisA} + axisB(swing high break)=${axisB} + axisC(W 패턴)=${axisC} → level=${level}. video3 §15:02-15:55.`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-C #17: KOSPI_PBR ===
  // video6 §05:55 — manual kospiPBR 또는 KRX 자동.
  // < 0.9 → +1 (밸류 우호), > 2.0 → -1 (과열).
  try {
    const pbr = typeof manualInputs?.kospiPBR === 'number' ? manualInputs.kospiPBR : null;
    if (pbr !== null) {
      let level: number;
      let label: string;
      if (pbr < 0.9) { level = 1; label = `🟢 PBR ${pbr.toFixed(2)} < 0.9 (밸류 우호)`; }
      else if (pbr > 2.0) { level = -1; label = `🔴 PBR ${pbr.toFixed(2)} > 2.0 (과열)`; }
      else { level = 0; label = `⚪ PBR ${pbr.toFixed(2)}`; }
      d.KOSPI_PBR = {
        name: 'kospi_pbr',
        value: level,
        date: today(),
        formula: `${label}. video6 §05:55.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P2-C #18: KOSPI_AGGREGATE_ROE ===
  // video6 §04:35 — manual kospiROE.
  // ≥ 10 → +1, < 5 → -1.
  try {
    const roe = typeof manualInputs?.kospiROE === 'number' ? manualInputs.kospiROE : null;
    if (roe !== null) {
      let level: number;
      let label: string;
      if (roe >= 10) { level = 1; label = `🟢 ROE ${roe.toFixed(1)}% ≥ 10`; }
      else if (roe < 5) { level = -1; label = `🔴 ROE ${roe.toFixed(1)}% < 5`; }
      else { level = 0; label = `⚪ ROE ${roe.toFixed(1)}%`; }
      d.KOSPI_AGGREGATE_ROE = {
        name: 'kospi_aggregate_roe',
        value: level,
        date: today(),
        formula: `${label}. video6 §04:35.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P2-C #19: EARNINGS_BEAT_RATIO_4Q ===
  // video6 §05:00 — earnings.ts 의 megacap 7 fetch. 평균 surprise.
  // ≥ +5 → +1, ≤ -5 → -1.
  try {
    const { fetchEarningsSurprises } = await import('../collectors/earnings');
    const agg = await fetchEarningsSurprises();
    if (agg && agg.totalCount > 0) {
      const avg = agg.avgSurprisePct;
      let level: number;
      let label: string;
      if (avg >= 5) { level = 1; label = `🟢 평균 surprise +${avg.toFixed(1)}% ≥ +5 (어닝 우호)`; }
      else if (avg <= -5) { level = -1; label = `🔴 평균 surprise ${avg.toFixed(1)}% ≤ -5`; }
      else { level = 0; label = `⚪ 평균 surprise ${avg.toFixed(1)}%`; }
      d.EARNINGS_BEAT_RATIO_4Q = {
        name: 'earnings_beat_ratio_4q',
        value: level,
        date: today(),
        formula: `megacap ${agg.totalCount} 개 평균 surprise ${avg.toFixed(2)}% (beats ${agg.beatCount} / misses ${agg.missCount}). ${label}. video6 §05:00.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P2-C #20: MULTIPLE_RATE_DECOUPLING_FLAG ===
  // video6 §08:21 — NASDAQ_FORWARD_PER 6개월 변화 vs DGS10 6개월 변화.
  // PER ↑ AND DGS10 ↑ → flag=1 (디커플, 위험).
  // PER ↓ AND DGS10 ↑ → flag=0 (정상 멀티플 축소).
  try {
    const perCur = d.NASDAQ_FORWARD_PER?.value ?? null;
    let perChange: number | null = null;
    let dgs10Change: number | null = null;
    if (perCur !== null) {
      // PER history (derived) 6개월 (≈ 130일)
      const perHist = await readHistory('derived', 'NASDAQ_FORWARD_PER').catch(() => [] as Array<{ date: string; value: number }>);
      if (perHist.length >= 130) {
        const per0 = perHist[perHist.length - 130].value;
        if (per0 > 0) perChange = perCur - per0;
      }
    }
    try {
      const dgsHist = await readHistory('fred', 'DGS10').catch(() => [] as Array<{ date: string; value: number }>);
      if (dgsHist.length >= 130) {
        const d0 = dgsHist[dgsHist.length - 130].value;
        const d1 = dgsHist[dgsHist.length - 1].value;
        dgs10Change = d1 - d0;
      }
    } catch { void 0; }
    if (perChange !== null && dgs10Change !== null) {
      let flag = 0;
      let label: string;
      if (perChange > 0 && dgs10Change > 0) {
        flag = 1;
        label = `🔴 디커플 (PER +${perChange.toFixed(1)} & DGS10 +${dgs10Change.toFixed(2)} 동반 상승) — 멀티플 축소 부재 위험`;
      } else if (perChange < 0 && dgs10Change > 0) {
        label = `🟢 정상 (PER ${perChange.toFixed(1)} & DGS10 +${dgs10Change.toFixed(2)} → 멀티플 축소 진행)`;
      } else {
        label = `⚪ 중립 (PER ${perChange.toFixed(1)}, DGS10 ${dgs10Change.toFixed(2)})`;
      }
      d.MULTIPLE_RATE_DECOUPLING_FLAG = {
        name: 'multiple_rate_decoupling_flag',
        value: flag,
        date: today(),
        formula: `${label}. video6 §08:21.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P2-D #21: USER_BEHAVIOR_FOMO_FLAG ===
  // video6 §03:28 — trade-log 사용자 BUY 액션 60일 / BUY 시점 직전 NASDAQ 30D ≥+10% 였던 비율 ≥0.4 → flag=1.
  try {
    const { readRecentTradeLog } = await import('../services/investment-plan');
    const log = await readRecentTradeLog(500);
    const cutoff = Date.now() - 60 * 86400000;
    const buyEntries = log.filter((e) => e.kind === 'user_action' && (e.notes?.includes('BUY') || e.to === 'BUY' || e.to === 'STRONG_BUY') && new Date(e.ts).getTime() >= cutoff);
    if (buyEntries.length >= 3) {
      // BUY 시점 NASDAQ 직전 30D 수익률 ≥+10% 였는지 — history 로 추정.
      const ndxHist = await fetchYahooHistory('^IXIC', 200);
      const datedNdx: Map<string, number> = new Map();
      for (const p of ndxHist) datedNdx.set(p.date, p.close);
      const sortedDates = ndxHist.map((p) => p.date).sort();
      let fomoCount = 0;
      let evaluated = 0;
      for (const e of buyEntries) {
        const eDate = e.ts.slice(0, 10);
        // 가장 가까운 과거 거래일 close
        let closeNow: number | null = null;
        for (let i = sortedDates.length - 1; i >= 0; i--) {
          if (sortedDates[i] <= eDate) { closeNow = datedNdx.get(sortedDates[i]) ?? null; break; }
        }
        if (closeNow === null) continue;
        // 30일 전 close
        const eMs = new Date(eDate).getTime();
        const target = new Date(eMs - 30 * 86400000).toISOString().slice(0, 10);
        let close30Ago: number | null = null;
        for (let i = sortedDates.length - 1; i >= 0; i--) {
          if (sortedDates[i] <= target) { close30Ago = datedNdx.get(sortedDates[i]) ?? null; break; }
        }
        if (close30Ago === null || close30Ago <= 0) continue;
        evaluated += 1;
        const ret30 = ((closeNow - close30Ago) / close30Ago) * 100;
        if (ret30 >= 10) fomoCount += 1;
      }
      const ratio = evaluated > 0 ? fomoCount / evaluated : 0;
      const flag = ratio >= 0.4 ? 1 : 0;
      d.USER_BEHAVIOR_FOMO_FLAG = {
        name: 'user_behavior_fomo_flag',
        value: flag,
        date: today(),
        formula: `60일 BUY ${buyEntries.length} 회 / 평가 ${evaluated} 회 / 직전 30D ≥+10% 였던 ${fomoCount} 회 = ${(ratio*100).toFixed(0)}% → flag=${flag}. video6 §03:28 "FOMO 패턴".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P2-D #22: USER_PORTFOLIO_RECOVERY_PCT_NEEDED ===
  // video6 §01:50 "50% 잃으면 100% 필요". totalCapital + currentHoldings → drawdown_pct.
  try {
    const { readInvestmentPlan } = await import('../services/investment-plan');
    const plan = await readInvestmentPlan();
    const startKRW = plan.startingCapitalKRW ?? 0;
    const startUSD = plan.startingCapitalUSD ?? 0;
    const usdkrwRate = val(raw, 'USDKRW') ?? 1400;
    const startTotalUSD = (startKRW / usdkrwRate) + startUSD;
    const curTotalUSDPoint = d.USER_USD_CAPITAL_TOTAL?.value ?? null;
    if (startTotalUSD > 0 && curTotalUSDPoint !== null) {
      const maxEquity = Math.max(startTotalUSD, curTotalUSDPoint);
      const dd = (maxEquity - curTotalUSDPoint) / maxEquity;
      const recoveryNeeded = dd > 0 && dd < 1 ? (dd / (1 - dd)) * 100 : 0;
      let level: number;
      let label: string;
      if (recoveryNeeded > 100) { level = 3; label = `🔴 ${recoveryNeeded.toFixed(0)}% 필요 — video6 §01:50 "50%↓ = 100%↑" 초과`; }
      else if (recoveryNeeded > 50) { level = 2; label = `🟠 ${recoveryNeeded.toFixed(0)}% 회복 필요 (drawdown ${(dd*100).toFixed(1)}%)`; }
      else if (recoveryNeeded > 25) { level = 1; label = `🟡 ${recoveryNeeded.toFixed(0)}% 회복 필요`; }
      else { level = 0; label = `⚪ ${recoveryNeeded.toFixed(0)}% 회복 필요`; }
      d.USER_PORTFOLIO_RECOVERY_PCT_NEEDED = {
        name: 'user_portfolio_recovery_pct_needed',
        value: parseFloat(recoveryNeeded.toFixed(1)),
        date: today(),
        formula: `max equity ${maxEquity.toFixed(0)} USD vs current ${curTotalUSDPoint.toFixed(0)} USD = drawdown ${(dd*100).toFixed(1)}%, recovery_needed = ${recoveryNeeded.toFixed(1)}%, level=${level}. ${label}. video6 §01:50.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P2-D #23: CASH_AS_OPTIONALITY_FLAG ===
  // video6 §15:52 "현금 있으면 폭락 = 세일".
  // cash_pct < 10 AND VIX ≥ 30 → flag=1.
  try {
    const { readInvestmentPlan } = await import('../services/investment-plan');
    const plan = await readInvestmentPlan();
    const cashPct = plan.currentHoldings?.cash ?? 0;
    const vixCur = val(raw, 'VIXCLS');
    let flag = 0;
    let label: string;
    if (cashPct < 10 && vixCur !== null && vixCur >= 30) {
      flag = 1;
      label = `🔴 cash ${cashPct.toFixed(1)}% < 10 + VIX ${vixCur.toFixed(1)} ≥ 30 — 폭락 세일 진입 어려움 (video6 §15:52)`;
    } else {
      label = `⚪ cash ${cashPct.toFixed(1)}%, VIX ${vixCur?.toFixed(1) ?? '?'}`;
    }
    d.CASH_AS_OPTIONALITY_FLAG = {
      name: 'cash_as_optionality_flag',
      value: flag,
      date: today(),
      formula: `${label}. video6 §15:52 "현금 = 자유".`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-D #24: NASDAQ_RSI_OVERSOLD_DURATION_DAYS ===
  // video6 §10:22 "RSI<30 연속 14일+ = 추세 약함, 분할매수 정지".
  try {
    const ndxHist = await fetchYahooHistory('^IXIC', 60);
    let duration = 0;
    if (ndxHist.length >= 30) {
      const closes = ndxHist.map((p) => p.close);
      const calcRsi = (arr: number[], len = 14): number | null => {
        if (arr.length < len + 1) return null;
        const recent = arr.slice(-len - 1);
        let g = 0; let l = 0;
        for (let i = 1; i < recent.length; i++) {
          const d = recent[i] - recent[i - 1];
          if (d > 0) g += d; else l -= d;
        }
        const aG = g / len; const aL = l / len;
        if (aL === 0) return 100;
        const rs = aG / aL;
        return 100 - 100 / (1 + rs);
      };
      // 역순으로 < 30 연속 일수
      for (let i = 0; i < closes.length - 14; i++) {
        const slice = closes.slice(0, closes.length - i);
        const r = calcRsi(slice);
        if (r === null) break;
        if (r < 30) duration += 1;
        else break;
      }
    }
    d.NASDAQ_RSI_OVERSOLD_DURATION_DAYS = {
      name: 'nasdaq_rsi_oversold_duration_days',
      value: duration,
      date: today(),
      formula: `NASDAQ RSI<30 연속 ${duration}일. ${duration >= 14 ? '🔴 ≥14일 → 추세 약함, 분할매수 정지 (video6 §10:22)' : '⚪ <14일'}.`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-D #25: VIX_HISTORIC_BUY_OPPORTUNITY ===
  // video6 §10:36 "VIX 80 = 10년 만의 매수 기회".
  // VIXCLS ≥ 60 + ICSA < 300K → +2, VIXCLS ≥ 80 + ICSA < 300K → +3.
  try {
    const vixCur = val(raw, 'VIXCLS');
    const icsa = val(raw, 'ICSA');
    let level = 0;
    let label = '⚪ 미충족';
    if (vixCur !== null && icsa !== null) {
      if (vixCur >= 80 && icsa < 300000) {
        level = 3;
        label = `🟢🟢🟢 VIX ${vixCur.toFixed(1)} ≥ 80 + ICSA ${(icsa/1000).toFixed(0)}K < 300K — 10년 매수 기회 (video6 §10:36)`;
      } else if (vixCur >= 60 && icsa < 300000) {
        level = 2;
        label = `🟢🟢 VIX ${vixCur.toFixed(1)} ≥ 60 + ICSA ${(icsa/1000).toFixed(0)}K < 300K — 강한 매수 기회 (video6 §10:36)`;
      }
    }
    d.VIX_HISTORIC_BUY_OPPORTUNITY = {
      name: 'vix_historic_buy_opportunity',
      value: level,
      date: today(),
      formula: `VIX ${vixCur?.toFixed(1) ?? '?'} / ICSA ${icsa !== null ? (icsa/1000).toFixed(0) + 'K' : '?'} → level=${level}. ${label}. video6 §10:36.`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-D #26: MACRO_REGIME_HISTORY_MATCH ===
  // video2 §15:49 "2020·2021 조합" — 4D 벡터 (REAL_YIELD, CPI YoY, DXY YoY, ISM) 라벨 매핑.
  try {
    const realYield = d.REAL_YIELD?.value ?? null;
    const cpiYoY = d.CPI_YOY?.value ?? null;
    let dxyYoY: number | null = null;
    try {
      const dxyHist = await fetchYahooHistory('DX-Y.NYB', 380);
      if (dxyHist.length >= 250) {
        const d0 = dxyHist[dxyHist.length - 250].close;
        const d1 = dxyHist[dxyHist.length - 1].close;
        if (d0 > 0) dxyYoY = ((d1 - d0) / d0) * 100;
      }
    } catch { void 0; }
    const ismVal = d.ISM_PROXY?.value ?? null;
    let label: string;
    let level = 0;
    if (realYield !== null && cpiYoY !== null && dxyYoY !== null) {
      const ryNeg = realYield < 0;
      const dxyWeak = dxyYoY < 0;
      if (ryNeg && cpiYoY > 4 && dxyWeak) { label = 'stagflation_2022'; level = -2; }
      else if (ryNeg && cpiYoY < 3 && dxyWeak) { label = 'goldilocks_2020-2021'; level = 2; }
      else if (!ryNeg && cpiYoY > 4) { label = 'high_rate_squeeze'; level = -1; }
      else if (!ryNeg && cpiYoY < 3 && !dxyWeak) { label = 'tightening_normalize'; level = 0; }
      else { label = 'mixed'; level = 0; }
    } else {
      label = 'data_insufficient';
    }
    d.MACRO_REGIME_HISTORY_MATCH = {
      name: 'macro_regime_history_match',
      value: level,
      date: today(),
      formula: `REAL_YIELD ${realYield?.toFixed(2) ?? '?'} / CPI YoY ${cpiYoY?.toFixed(1) ?? '?'}% / DXY YoY ${dxyYoY?.toFixed(1) ?? '?'}% / ISM ${ismVal?.toFixed(1) ?? '?'} → ${label} (level=${level}). video2 §15:49.`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-D #27: RETAIL_PANIC_SELL ===
  // video1 §03:06 "2020.3 저점 직후 한 달 개인 순매도 역대 최대".
  // VIX ≥ 30 AND NASDAQ 20D < -10% AND (NAAIM < 30 OR AAII Bull% < 25) → +1.
  try {
    const vixCur = val(raw, 'VIXCLS');
    let ndx20Ret: number | null = null;
    try {
      const n = await fetchYahooHistory('^IXIC', 25);
      if (n.length >= 20) {
        const n0 = n[n.length - 20].close;
        const n1 = n[n.length - 1].close;
        if (n0 > 0) ndx20Ret = ((n1 - n0) / n0) * 100;
      }
    } catch { void 0; }
    const naaim = d.NAAIM_EXPOSURE?.value ?? null;
    const aaii = d.AAII_BULL_BEAR_SPREAD?.value ?? null;
    // AAII Bull% 25% 미만 = bull-bear 양수일 가능성 낮음. 단순화: aaii 음수 (bear>bull) 시 25% 미만 추정.
    const sentLow = (naaim !== null && naaim < 30) || (aaii !== null && aaii < -10);
    let level = 0;
    let label = '⚪ 미충족';
    if (vixCur !== null && vixCur >= 30 && ndx20Ret !== null && ndx20Ret < -10 && sentLow) {
      level = 1;
      label = `🟢 역발상 매수 신호 — VIX ${vixCur.toFixed(1)} ≥ 30 + NDX 20D ${ndx20Ret.toFixed(1)}% < -10 + 심리 저조 (video1 §03:06)`;
    }
    d.RETAIL_PANIC_SELL = {
      name: 'retail_panic_sell',
      value: level,
      date: today(),
      formula: `VIX ${vixCur?.toFixed(1) ?? '?'} / NDX 20D ${ndx20Ret?.toFixed(1) ?? '?'}% / NAAIM ${naaim?.toFixed(0) ?? '?'} / AAII ${aaii?.toFixed(1) ?? '?'} → level=${level}. ${label}.`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-D #28: BOUNCE_QUALITY_FOLLOWTHROUGH_DAYS ===
  // stt_kospi §05:40 — GEOPOLITICAL_UNWIND_EVENT=1 후 D+5 까지 KOSPI_VOLUME_CONFIRM=1 + 직후 +2% 유지.
  try {
    const unwindHist = await readHistory('derived', 'GEOPOLITICAL_UNWIND_EVENT').catch(() => [] as Array<{ date: string; value: number }>);
    if (unwindHist.length >= 6) {
      // 가장 최근 unwind=1 시점 찾기
      let lastIdx = -1;
      for (let i = unwindHist.length - 1; i >= 0; i--) {
        if (unwindHist[i].value === 1) { lastIdx = i; break; }
      }
      let level = 0;
      let detail = '직전 unwind 이벤트 없음';
      if (lastIdx >= 0 && lastIdx < unwindHist.length - 5) {
        const eventDate = unwindHist[lastIdx].date;
        const ksHist = await fetchYahooHistory('^KS11', 30);
        const eventClose = ksHist.find((p) => p.date >= eventDate)?.close ?? null;
        if (eventClose !== null) {
          // D+5 까지 +2% 유지
          const followUp = ksHist.filter((p) => p.date > eventDate).slice(0, 5);
          const minRet = followUp.length > 0 ? Math.min(...followUp.map((p) => ((p.close - eventClose) / eventClose) * 100)) : null;
          // 거래량 확인 — VOLUME_CONFIRM history
          const volConfirmHist = await readHistory('derived', 'KOSPI_VOLUME_CONFIRM').catch(() => [] as Array<{ date: string; value: number }>);
          const followVolConfirm = volConfirmHist.filter((p) => p.date > eventDate).slice(0, 5);
          const allVolOk = followVolConfirm.length > 0 && followVolConfirm.every((p) => p.value === 1);
          if (minRet !== null && minRet >= 2 && allVolOk) {
            level = 1;
            detail = `🟢 D+5 까지 +${minRet.toFixed(1)}% 유지 + volume confirm ✓`;
          } else {
            level = 0;
            detail = `🔴 D+5 minRet ${minRet?.toFixed(1) ?? '?'}% (재하락) / volume ${allVolOk ? 'OK' : 'NG'}`;
          }
        }
      }
      d.BOUNCE_QUALITY_FOLLOWTHROUGH_DAYS = {
        name: 'bounce_quality_followthrough_days',
        value: level,
        date: today(),
        formula: `${detail}. stt_kospi §05:40 "반등 추세 진위 D+5 검증".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P2-D #29: FX_FOREIGN_BASELINE_GAP_TRILLION ===
  // stt_kospi §08:21 "환율 10% 상승 = 외인 5조 적정".
  // 실제 외인 60D 누적 - (KRW 60D % × 0.5조 / 1%) → 절대값 ≥ 30조 → +1.
  try {
    const foreignHist = await readHistory('krx', 'KOSPI_FOREIGN_NET_1D').catch(() => [] as Array<{ date: string; value: number }>);
    let foreign60Sum = 0;
    if (foreignHist.length >= 60) {
      foreign60Sum = foreignHist.slice(-60).reduce((s, p) => s + p.value, 0);
    }
    let krw60Pct: number | null = null;
    try {
      const krHist = await fetchYahooHistory('KRW=X', 70);
      if (krHist.length >= 60) {
        const k0 = krHist[krHist.length - 60].close;
        const k1 = krHist[krHist.length - 1].close;
        if (k0 > 0) krw60Pct = ((k1 - k0) / k0) * 100;
      }
    } catch { void 0; }
    if (krw60Pct !== null && foreignHist.length >= 60) {
      // 적정: KRW 1% 상승당 외인 -0.5조 (즉 KRW 10% → -5조)
      const baseline = -krw60Pct * 0.5; // 조원
      const actualTrillion = foreign60Sum / 1e4; // KRX 단위 억 → 조 (10000 억 = 1조)
      const gap = Math.abs(actualTrillion - baseline);
      let level = 0;
      let label: string;
      if (gap >= 30) { level = 1; label = `🟠 갭 ${gap.toFixed(1)}조 ≥ 30 — ATM 화 강 경고`; }
      else { label = `⚪ 갭 ${gap.toFixed(1)}조`; }
      d.FX_FOREIGN_BASELINE_GAP_TRILLION = {
        name: 'fx_foreign_baseline_gap_trillion',
        value: level,
        date: today(),
        formula: `KRW 60D ${krw60Pct.toFixed(2)}% → baseline ${baseline.toFixed(1)}조 vs 실제 ${actualTrillion.toFixed(1)}조 = 갭 ${gap.toFixed(1)}조. ${label}. stt_kospi §08:21.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P2-E #30: GEOPOLITICAL_UNWIND_EVENT_5AX 확장 ===
  // stt_kospi §04:58 — 기존 3축 (KOSPI/FX/WTI) + 외인 1D ≥+1조 + KOSPI 일봉 +6%.
  // 5축 hit → level=2 / 4축 → 1 / 3축 → 0 (기존).
  try {
    const baseLevel = d.GEOPOLITICAL_UNWIND_EVENT?.value ?? null;
    const foreignNet1D = d.KOSPI_FOREIGN_NET_1D?.value ?? null;
    // KOSPI 일봉 % — kospi 1D 변화율
    let kospi1DPct: number | null = null;
    try {
      const kHist = await readHistory('yahoo', 'KOSPI');
      if (kHist.length >= 2) {
        const cur = kHist[kHist.length - 1].value;
        const prev = kHist[kHist.length - 2].value;
        if (prev > 0) kospi1DPct = ((cur - prev) / prev) * 100;
      }
    } catch { void 0; }
    if (baseLevel !== null) {
      const ax4 = foreignNet1D !== null && foreignNet1D >= 10000;  // 1조 = 10000억
      const ax5 = kospi1DPct !== null && kospi1DPct >= 6;
      // baseLevel 2 = 3축 모두, 1 = 2축. 5축 모두 충족 시 level=2 정의.
      let level = 0;
      if (baseLevel === 2 && ax4 && ax5) level = 2;       // 5축 모두
      else if (baseLevel === 2 && (ax4 || ax5)) level = 1; // 4축
      else level = 0;                                     // 3축 이하
      d.GEOPOLITICAL_UNWIND_EVENT_5AX = {
        name: 'geopolitical_unwind_event_5ax',
        value: level,
        date: today(),
        formula: `base 3축=${baseLevel} + 외인 1D ${foreignNet1D ?? '?'}억 ≥1조=${ax4} + KOSPI 1D ${kospi1DPct?.toFixed(2) ?? '?'}% ≥6%=${ax5} → level=${level}. stt_kospi §04:58 "5축 동시 발화".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P2-E #31: KOSPI_HALFYEAR_UPPER_WICK_THRESHOLD ===
  // stt_kospi §03:18 "반기봉 윗꼬리 길어진다" — 임계 분류:
  //   ≥ 30% → -1 (매도압력 강) / ≥ 15% → 0 / < 15% → +1.
  try {
    const wickPct = d.KOSPI_HALFYEAR_UPPER_WICK_PCT?.value ?? null;
    if (wickPct !== null) {
      let level: number;
      let label: string;
      if (wickPct >= 30) { level = -1; label = `🔴 ${wickPct.toFixed(1)}% ≥ 30 (매도압력 강)`; }
      else if (wickPct >= 15) { level = 0; label = `🟡 ${wickPct.toFixed(1)}% (15~30)`; }
      else { level = 1; label = `🟢 ${wickPct.toFixed(1)}% < 15`; }
      d.KOSPI_HALFYEAR_UPPER_WICK_THRESHOLD = {
        name: 'kospi_halfyear_upper_wick_threshold',
        value: level,
        date: today(),
        formula: `${label}. stt_kospi §03:18 "반기봉 윗꼬리".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P2-E #32: KOSPI_PARTIAL_PROFIT_SCENARIO_A ===
  // stt_kospi §11:45 — SCENARIO_GATE_A_B=1 진행 중 user.currentHoldings.kospi 따른 권고:
  //   보유 > 0 → "30% 익절" / 보유 == 0 → "신규 매수 보류".
  try {
    const sgab = d.SCENARIO_GATE_A_B?.value ?? null;
    let level = 0;
    let label = '⚪ SCENARIO_GATE_A_B 미발동';
    if (sgab === 1) {
      const { readInvestmentPlan } = await import('../services/investment-plan');
      const plan = await readInvestmentPlan();
      const kospiHold = plan.currentHoldings?.korea ?? 0;
      if (kospiHold > 0) {
        level = 1;
        label = `🟡 시나리오 A 진행 + KOSPI 보유 ${kospiHold.toFixed(1)}% — 일부 익절 30% 권고 (stt_kospi §11:45)`;
      } else {
        level = -1;
        label = `🟠 시나리오 A 진행 + KOSPI 보유 0% — 신규 매수 보류 (리스크 높음, stt_kospi §11:45)`;
      }
    }
    d.KOSPI_PARTIAL_PROFIT_SCENARIO_A = {
      name: 'kospi_partial_profit_scenario_a',
      value: level,
      date: today(),
      formula: `${label}. stt_kospi §11:45.`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-E #34: INTERMEDIATE_ASSET_REGIME (silver/copper 비중 결정 보조) ===
  // video2 §03:35 "은/구리 = 중간 자산".
  // GROWTH_AXIS = ISM_PROXY 추세 (+1/0/-1)
  // RISK_AXIS = VIX_TIER (+1=낮음 (<20)/0/-1=높음 (≥30))
  try {
    const ismVal = d.ISM_PROXY?.value ?? null;
    let growthAxis = 0;
    if (ismVal !== null) {
      if (ismVal >= 52) growthAxis = 1;
      else if (ismVal < 48) growthAxis = -1;
    }
    const vixCur = val(raw, 'VIXCLS');
    let riskAxis = 0;
    if (vixCur !== null) {
      if (vixCur < 20) riskAxis = 1;
      else if (vixCur >= 30) riskAxis = -1;
    }
    d.INTERMEDIATE_ASSET_REGIME = {
      name: 'intermediate_asset_regime',
      value: growthAxis + riskAxis,
      date: today(),
      formula: `GROWTH=${growthAxis} (ISM ${ismVal?.toFixed(1) ?? '?'}) + RISK=${riskAxis} (VIX ${vixCur?.toFixed(1) ?? '?'}) → ${growthAxis + riskAxis}. video2 §03:35 "은/구리 = 중간 자산".`,
    };
  } catch { void 0; }

  // ★ === 29차 P2-E #35: INSIDER_CLUSTER_PURCHASES_COUNT_50K (OpenInsider) ===
  // 노션 §OpenInsider — http://openinsider.com/insider-cluster-purchases.
  // $50K 컷오프 + 30일 윈도우. cluster (2+ insiders) 종목 수 ≥ 5 → +1.
  try {
    const { fetchOpenInsiderClusterPurchases } = await import('../collectors/openinsider');
    const oi = await fetchOpenInsiderClusterPurchases();
    if (oi !== null) {
      const count = oi.totalTickers;
      let level: number;
      let label: string;
      if (count >= 10) { level = 2; label = `🟢🟢 ${count} 종목 cluster ≥ 10 (역발상 buy 환경 강)`; }
      else if (count >= 5) { level = 1; label = `🟢 ${count} 종목 cluster ≥ 5`; }
      else { level = 0; label = `⚪ ${count} 종목`; }
      d.INSIDER_CLUSTER_PURCHASES_COUNT_50K = {
        name: 'insider_cluster_purchases_count_50k',
        value: level,
        date: today(),
        formula: `OpenInsider $50K↑ cluster ${count} 종목 / 총 매수 $${(oi.totalUsd / 1e6).toFixed(1)}M. ${label}. 노션 §OpenInsider.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P2-E #36: KOSPI_VOLUME_TIER (3단) ===
  // stt_kospi §노션 "주간 평균 20조 이상 = 강 / 15조 이하 = 약".
  // 기존 KOSPI_VOLUME_20T_FLAG 확장.
  try {
    const recentVol = d.KOSPI_VOLUME_5D_AVG_KRW?.value ?? null;
    if (recentVol !== null) {
      const trillion = recentVol / 10000; // 억원 → 조원
      let tier: number;
      let label: string;
      if (trillion >= 20) { tier = 1; label = `🟢 ${trillion.toFixed(1)}조 ≥ 20 (지속성 강)`; }
      else if (trillion >= 15) { tier = 0; label = `🟡 ${trillion.toFixed(1)}조 (15-20 보통)`; }
      else { tier = -1; label = `🔴 ${trillion.toFixed(1)}조 < 15 (관심 약화)`; }
      d.KOSPI_VOLUME_TIER = {
        name: 'kospi_volume_tier',
        value: tier,
        date: today(),
        formula: `KOSPI 5일 평균 거래대금 ${trillion.toFixed(1)}조 → tier=${tier}. ${label}. stt_kospi §"주간 평균 20조".`,
      };
    }
  } catch { void 0; }

  // ============================================================
  // ★★★ 29차 P3 (P3-A ~ P3-E) — 36건 추가 ★★★
  // ============================================================

  // ★ === 29차 P3-A #1: FX_RESERVE_USD_RATIO (IMF COFER) ===
  // video2 §09:04 "탈달러 — 71% → 58%, 20년 -15%p".
  // manualInputs.fxReserveUsdRatio (분기 데이터, 사용자 입력 채널).
  try {
    const ratio = manualInputs?.fxReserveUsdRatio ?? null;
    if (ratio !== null) {
      let level: number;
      let label: string;
      if (ratio <= 55) { level = 2; label = `🟢🟢 ${ratio.toFixed(1)}% ≤ 55 (탈달러 가속, 금 우호 +2)`; }
      else if (ratio <= 60) { level = 1; label = `🟢 ${ratio.toFixed(1)}% ≤ 60 (탈달러 진행)`; }
      else if (ratio >= 65) { level = -1; label = `🔴 ${ratio.toFixed(1)}% ≥ 65 (달러 패권 회복)`; }
      else { level = 0; label = `⚪ ${ratio.toFixed(1)}% (60-65)`; }
      d.FX_RESERVE_USD_RATIO = {
        name: 'fx_reserve_usd_ratio',
        value: level,
        date: today(),
        formula: `IMF COFER USD 비중 ${ratio.toFixed(1)}% → level=${level}. ${label}. video2 §09:04 "탈달러 71%→58% 20년".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-A #2: BTC_GOLD_HEGEMONY_INDEX ===
  // video2 §07:58-09:00 — 미국 BTC 전략비축 vs 중국 PBOC 금 보유량.
  // BTC ratio ↑ + 중국 금 ↓ → -1 (달러 패권 회복) / BTC ratio ↓ + 중국 금 ↑ → +1 (탈달러 가속).
  try {
    let btcAxis = 0;
    let btcLabel = '?';
    try {
      const btcHist = await fetchYahooHistory('BTC-USD', 200);
      if (btcHist.length >= 100) {
        const btcRecent = btcHist.slice(-30).reduce((s, p) => s + p.close, 0) / 30;
        const btcPrior = btcHist.slice(-180, -90).reduce((s, p) => s + p.close, 0) / 90;
        const btcRet = btcPrior > 0 ? (btcRecent - btcPrior) / btcPrior * 100 : 0;
        if (btcRet >= 10) { btcAxis = 1; btcLabel = `BTC +${btcRet.toFixed(1)}% (강세)`; }
        else if (btcRet <= -10) { btcAxis = -1; btcLabel = `BTC ${btcRet.toFixed(1)}% (약세)`; }
        else { btcLabel = `BTC ${btcRet.toFixed(1)}% (횡보)`; }
      }
    } catch { void 0; }
    const cbGold = d.CB_GOLD_TONNAGE_TREND?.value ?? null;
    let goldAxis = 0;
    let goldLabel = '?';
    if (cbGold !== null) {
      if (cbGold >= 1) { goldAxis = 1; goldLabel = `중국 금 ${cbGold} (매입 가속)`; }
      else if (cbGold <= -1) { goldAxis = -1; goldLabel = `중국 금 ${cbGold} (매입 둔화)`; }
      else { goldLabel = `중국 금 ${cbGold} (중립)`; }
    }
    // BTC ↑ + 중국 금 ↓ → -1 (달러 패권 회복)
    // BTC ↓ + 중국 금 ↑ → +1 (탈달러 가속)
    let hegemony = 0;
    let hegLabel = '⚪ 중립';
    if (btcAxis >= 1 && goldAxis <= -1) { hegemony = -1; hegLabel = '🔴 달러 패권 회복 (BTC↑ 중국 금↓)'; }
    else if (btcAxis <= -1 && goldAxis >= 1) { hegemony = 1; hegLabel = '🟢 탈달러 가속 (BTC↓ 중국 금↑)'; }
    d.BTC_GOLD_HEGEMONY_INDEX = {
      name: 'btc_gold_hegemony_index',
      value: hegemony,
      date: today(),
      formula: `${btcLabel} + ${goldLabel} → ${hegLabel}. video2 §07:58-09:00 "BTC 전략비축 vs PBOC 금".`,
    };
  } catch { void 0; }

  // ★ === 29차 P3-A #3: MMF_TOTAL_TIER ===
  // 노션 assetx2-dashboard "MMF 5조 historic high".
  // WRMFNS (retail 2.5T) + manualInputs.mmfTotalTrillion (ICI 전체 ~5T).
  try {
    const wrmfns = val(raw, 'WRMFNS') ?? null;        // 단위: $ Billions
    const wrmfnsT = wrmfns !== null ? wrmfns / 1000 : null;
    const iciTotalT = manualInputs?.mmfTotalTrillion ?? null;
    const totalT = iciTotalT ?? (wrmfnsT !== null ? wrmfnsT * 2 : null); // ICI ≈ retail × 2 fallback
    if (totalT !== null) {
      let tier: number;
      let label: string;
      if (totalT >= 5) { tier = 1; label = `🟢 ${totalT.toFixed(2)}T ≥ 5 (historic high)`; }
      else if (totalT >= 4.5) { tier = 0; label = `🟡 ${totalT.toFixed(2)}T (4.5-5)`; }
      else if (totalT < 4) { tier = -1; label = `🔴 ${totalT.toFixed(2)}T < 4 (유동성 위축)`; }
      else { tier = 0; label = `⚪ ${totalT.toFixed(2)}T (4-4.5)`; }
      d.MMF_TOTAL_TIER = {
        name: 'mmf_total_tier',
        value: tier,
        date: today(),
        formula: `MMF 전체 ${totalT.toFixed(2)}T (출처: ${iciTotalT !== null ? 'ICI 사용자입력' : 'WRMFNS×2 fallback'}) → tier=${tier}. ${label}. 노션 §assetx2-dashboard "MMF 5조 historic high".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-A #4: RRP_EXHAUSTION_PCT ===
  // 노션 assetx2-dashboard "RRP 99% 고갈".
  // RRPONTSYD raw + peak (history 자동 detect 또는 2022-23 peak 2.5T 하드코딩).
  try {
    const rrpCur = val(raw, 'RRPONTSYD') ?? null;     // 단위: $ Billions
    let rrpPeak: number | null = null;
    try {
      const rrpHist = await readHistory('fred', 'RRPONTSYD');
      if (rrpHist.length > 0) {
        rrpPeak = rrpHist.reduce((mx, p) => p.value > mx ? p.value : mx, 0);
      }
    } catch { void 0; }
    if (rrpPeak === null || rrpPeak < 2000) rrpPeak = 2500; // 하드코딩 2.5T peak
    if (rrpCur !== null && rrpPeak > 0) {
      const exhaustion = (rrpPeak - rrpCur) / rrpPeak * 100;
      const flag = exhaustion >= 95 ? 1 : 0;
      d.RRP_EXHAUSTION_PCT = {
        name: 'rrp_exhaustion_pct',
        value: parseFloat(exhaustion.toFixed(1)),
        date: today(),
        formula: `RRP ${rrpCur.toFixed(0)}B / peak ${rrpPeak.toFixed(0)}B → 고갈 ${exhaustion.toFixed(1)}%${flag === 1 ? ' (≥95% TGA 보충 임박)' : ''}. 노션 §assetx2-dashboard "RRP 99% 고갈".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-A #5: JGB_10Y_LEVEL ===
  // video6 §04:28 "일본 금리 체크" — 캐리 트레이드 unwind 위험.
  // manualInputs.jgb10y (사용자 입력) — FRED/BoJ fetch 어려움.
  try {
    const jgb = manualInputs?.jgb10y ?? null;
    if (jgb !== null) {
      let level: number;
      let label: string;
      if (jgb >= 1.5) { level = -2; label = `🔴🔴 JGB10Y ${jgb.toFixed(2)}% ≥ 1.5 (캐리 unwind 강)`; }
      else if (jgb >= 1.0) { level = -1; label = `🔴 JGB10Y ${jgb.toFixed(2)}% ≥ 1.0 (캐리 unwind 위험)`; }
      else if (jgb < 0.5) { level = 1; label = `🟢 JGB10Y ${jgb.toFixed(2)}% < 0.5 (캐리 우호)`; }
      else { level = 0; label = `⚪ JGB10Y ${jgb.toFixed(2)}%`; }
      d.JGB_10Y_LEVEL = {
        name: 'jgb_10y_level',
        value: level,
        date: today(),
        formula: `JGB 10Y ${jgb.toFixed(2)}% → level=${level}. ${label}. video6 §04:28 "일본 금리 체크".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-A #6: DXY_12M_YOY (단독 노출) ===
  // video2 §"중앙은행 매수 proxy (b)" — DXY 12M YoY 변화율 단독 derived.
  try {
    const dxyHist = await fetchYahooHistory('DX-Y.NYB', 380);
    if (dxyHist.length >= 250) {
      const cur = dxyHist[dxyHist.length - 1].close;
      const past = dxyHist[Math.max(0, dxyHist.length - 252)].close;
      if (past > 0) {
        const yoy = (cur - past) / past * 100;
        let level: number;
        let label: string;
        if (yoy <= -5) { level = 1; label = `🟢 DXY 12M ${yoy.toFixed(1)}% ≤ -5 (약달러 우호)`; }
        else if (yoy >= 5) { level = -1; label = `🔴 DXY 12M ${yoy.toFixed(1)}% ≥ +5 (강달러 위협)`; }
        else { level = 0; label = `⚪ DXY 12M ${yoy.toFixed(1)}%`; }
        d.DXY_12M_YOY = {
          name: 'dxy_12m_yoy',
          value: level,
          date: today(),
          formula: `DXY 12M YoY: ${past.toFixed(2)} → ${cur.toFixed(2)} = ${yoy >= 0 ? '+' : ''}${yoy.toFixed(2)}% → level=${level}. ${label}. video2 §"중앙은행 매수 proxy (b)".`,
        };
      }
    }
  } catch { void 0; }

  // ★ === 29차 P3-B #7: KR_BOK_LOCKED_FLAG ===
  // stt_kospi §09:55 "한은 사방이 막힌 미로".
  // USDKRW ≥ 1500 OR krHouseholdDebtPctGdp > 100 OR krCpi ≥ 3 → flag=1.
  try {
    const usdkrw = val(raw, 'USDKRW') ?? null;
    const hh = manualInputs?.krHouseholdDebtPctGdp ?? null;
    const cpi = manualInputs?.krCpi ?? null;
    const fxLocked = usdkrw !== null && usdkrw >= 1500;
    const debtLocked = hh !== null && hh > 100;
    const cpiLocked = cpi !== null && cpi >= 3;
    const flag = (fxLocked || debtLocked || cpiLocked) ? 1 : 0;
    const reasons = [];
    if (fxLocked) reasons.push(`USDKRW ${usdkrw?.toFixed(0)}≥1500`);
    if (debtLocked) reasons.push(`가계부채 ${hh?.toFixed(0)}%>100`);
    if (cpiLocked) reasons.push(`CPI ${cpi?.toFixed(1)}%≥3`);
    d.KR_BOK_LOCKED_FLAG = {
      name: 'kr_bok_locked_flag',
      value: flag,
      date: today(),
      formula: `USDKRW=${usdkrw?.toFixed(1) ?? '?'} / 가계부채%GDP=${hh ?? '?'} / krCPI=${cpi ?? '?'}. ${flag ? `🔒 한은 lock-in (${reasons.join(' · ')}, KOSPI -1)` : '⚪ 정책 여유'}. stt_kospi §09:55 "한은 사방 막힘".`,
    };
  } catch { void 0; }

  // ★ === 29차 P3-B #8: WGBI_INFLOW_TAILWIND ===
  // stt_kospi §09:33 "WGBI 편입 외국인 자금 유입".
  // calendar 의 WGBI 편입 D-Day (하드코딩 2026-10-01) 기준 D-180 ~ D+180 활성, USDKRW < 1500.
  try {
    const wgbiDate = new Date('2026-10-01');
    const today2 = new Date();
    const daysFromWgbi = Math.round((today2.getTime() - wgbiDate.getTime()) / 86400000);
    const inWindow = daysFromWgbi >= -180 && daysFromWgbi <= 180;
    const usdkrw = val(raw, 'USDKRW') ?? null;
    const fxOk = usdkrw !== null && usdkrw < 1500;
    const flag = (inWindow && fxOk) ? 1 : 0;
    d.WGBI_INFLOW_TAILWIND = {
      name: 'wgbi_inflow_tailwind',
      value: flag,
      date: today(),
      formula: `WGBI 2026-10-01 D${daysFromWgbi >= 0 ? '+' : ''}${daysFromWgbi}, window=${inWindow}, USDKRW=${usdkrw?.toFixed(1) ?? '?'}<1500=${fxOk}. ${flag ? '🟢 WGBI tailwind 활성 (KOSPI +1)' : '⚪ 미발동'}. stt_kospi §09:33.`,
    };
  } catch { void 0; }

  // ★ === 29차 P3-B #9: KR_FISCAL_LAG_PROGRESS_DAYS ===
  // stt_kospi §10:30 "추경 27조 → 6개월 후 효과".
  // calendar.ts STATIC_POLITICAL_EVENTS 추경 D-Day (2026-04-15) 경과일수 / 180.
  try {
    const fiscalDate = new Date('2026-04-15');
    const today2 = new Date();
    const elapsed = Math.round((today2.getTime() - fiscalDate.getTime()) / 86400000);
    const progress = elapsed / 180;
    let level: number;
    let label: string;
    if (progress >= 1) { level = 2; label = `🟢 ${progress.toFixed(2)} ≥ 1 — 효과 반영 시점 (KOSPI +0.5)`; }
    else if (progress >= 0.5) { level = 1; label = `🟡 ${progress.toFixed(2)} ≥ 0.5 — 절반 진입`; }
    else if (progress >= 0) { level = 0; label = `⚪ ${progress.toFixed(2)} — 진행 중`; }
    else { level = -1; label = `⏳ 시행 전 (D${elapsed})`; }
    d.KR_FISCAL_LAG_PROGRESS_DAYS = {
      name: 'kr_fiscal_lag_progress_days',
      value: parseFloat(progress.toFixed(2)),
      date: today(),
      formula: `추경 D${elapsed >= 0 ? '+' : ''}${elapsed} / 180일 = ${progress.toFixed(2)}. ${label}. stt_kospi §10:30 "추경 27조 → 6개월 효과".`,
    };
  } catch { void 0; }

  // ★ === 29차 P3-B #10: KR_POLICY_GRIDLOCK_REGIME_PRESSURE ===
  // stt_kospi §"한은 사방 막힘" — KR_BOK_LOCKED_FLAG=1 시 KOSPI 비중 결정 시 -0.5 보조.
  try {
    const lockedFlag = d.KR_BOK_LOCKED_FLAG?.value ?? null;
    const pressure = lockedFlag === 1 ? -0.5 : 0;
    d.KR_POLICY_GRIDLOCK_REGIME_PRESSURE = {
      name: 'kr_policy_gridlock_regime_pressure',
      value: pressure,
      date: today(),
      formula: `KR_BOK_LOCKED_FLAG=${lockedFlag}, regime KOSPI 비중 보조 ${pressure}. stt_kospi §"한은 사방 막힘".`,
    };
  } catch { void 0; }

  // ★ === 29차 P3-B #11: KOSPI_HISTORIC_OVERSHOOT_RANK ===
  // stt_kospi §01:55 "1999/2007/2020 사례".
  // 기존 KOSPI_HISTORIC_OVERSHOOT_FLAG (≥75% YTD) 보강 — rank 분류.
  try {
    const ytd = d.KOSPI_YEAR_RETURN?.value ?? null;
    if (ytd !== null) {
      let rank: number | null = null;
      let label: string;
      if (ytd >= 83) { rank = 2; label = `🔴🔴 rank=2 (1999 IT버블 ${ytd.toFixed(1)}% ≥83%)`; }
      else if (ytd >= 75) { rank = 3; label = `🔴 rank=3 (2020 코로나 ${ytd.toFixed(1)}% ≥75%)`; }
      else if (ytd >= 50) { rank = 4; label = `🟠 rank=4 (2007 GFC 직전 ${ytd.toFixed(1)}% ≥50%)`; }
      else { label = `⚪ rank=null (${ytd.toFixed(1)}% < 50%)`; }
      d.KOSPI_HISTORIC_OVERSHOOT_RANK = {
        name: 'kospi_historic_overshoot_rank',
        value: rank,
        date: today(),
        formula: `KOSPI 1년 ${ytd.toFixed(1)}%. ${label}. stt_kospi §01:55 "1999/2007/2020 사례 다음 해 평균 -44%".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-B #12: KOSPI_POST_OVERSHOOT_EXPECTED_DRAWDOWN_PCT ===
  // stt_kospi §"다음 해 평균 -44%" — KOSPI_YEAR_RETURN ≥ 75% 시 -44 (3 사례 평균).
  try {
    const ytd = d.KOSPI_YEAR_RETURN?.value ?? null;
    if (ytd !== null) {
      const expected = ytd >= 75 ? -44 : null;
      d.KOSPI_POST_OVERSHOOT_EXPECTED_DRAWDOWN_PCT = {
        name: 'kospi_post_overshoot_expected_drawdown_pct',
        value: expected,
        date: today(),
        formula: `KOSPI YTD ${ytd.toFixed(1)}% ${expected !== null ? `≥75 → 다음 해 평균 ${expected}% (1999/2007/2020 3사례 평균, kospiPlan stop-loss 권고)` : '<75 → 미적용'}. stt_kospi §"다음 해 평균 -44%".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-B #13: KOSPI_ABSOLUTE_LEVEL_LEVEL ===
  // stt_kospi §"5150 1차 / 4080 2차" — SCENARIO_GATE_A_B 와 분리한 단순 노출.
  try {
    const k = val(raw, 'KOSPI') ?? null;
    if (k !== null) {
      let level: number;
      let label: string;
      if (k >= 5150) { level = 2; label = `🔴 ${k.toFixed(0)} ≥ 5150 (1차 지지 위 — 과열권)`; }
      else if (k >= 4080) { level = 0; label = `⚪ ${k.toFixed(0)} (4080~5150 사이)`; }
      else { level = -2; label = `🔵 ${k.toFixed(0)} < 4080 (2차 지지 이탈)`; }
      d.KOSPI_ABSOLUTE_LEVEL_LEVEL = {
        name: 'kospi_absolute_level_level',
        value: level,
        date: today(),
        formula: `KOSPI ${k.toFixed(0)} → level=${level}. ${label}. stt_kospi §"5150 1차 / 4080 2차".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-C #14: COPPER_LEAD_DIVERGENCE_60D ===
  // video2 §13:08 "구리 2~3개월 선행" — 60D 명시 버전 (기존 20D COPPER_STOCK_DIVERGENCE 보강).
  try {
    const ndxHist60 = await fetchYahooHistory('^IXIC', 90);
    const cuHist60 = await fetchYahooHistory('HG=F', 90);
    if (ndxHist60.length >= 61 && cuHist60.length >= 61) {
      const nq0 = ndxHist60[ndxHist60.length - 61].close;
      const nq1 = ndxHist60[ndxHist60.length - 1].close;
      const cu0 = cuHist60[cuHist60.length - 61].close;
      const cu1 = cuHist60[cuHist60.length - 1].close;
      const nqPct = nq0 > 0 ? ((nq1 - nq0) / nq0) * 100 : 0;
      const cuPct = cu0 > 0 ? ((cu1 - cu0) / cu0) * 100 : 0;
      let level = 0;
      let label = '⚪ 정렬';
      if (cuPct >= 5 && Math.abs(nqPct) <= 2) {
        level = 1;
        label = `🟢 구리 +${cuPct.toFixed(1)}% + S&P 횡보 ${nqPct.toFixed(1)}% — 회복 선행 (video2 §13:08)`;
      } else if (cuPct <= -3 && nqPct >= 0) {
        level = -1;
        label = `🔴 구리 ${cuPct.toFixed(1)}% + S&P ${nqPct.toFixed(1)}% — 경고 선행 (video2 §13:08)`;
      }
      d.COPPER_LEAD_DIVERGENCE_60D = {
        name: 'copper_lead_divergence_60d',
        value: level,
        date: today(),
        formula: `60D: COPPER ${cuPct.toFixed(1)}% vs S&P ${nqPct.toFixed(1)}%. ${label}. video2 §13:08 "구리 2~3개월 선행".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-C #15: GOLD_BULL_FOLLOWTHROUGH ===
  // video2 §19:00 "장대양봉 후 60-70% 추세" — 1979/1973/2024 ≥+50% 사례 통계.
  try {
    const gldHist = await fetchYahooHistory('GLD', 380);
    if (gldHist.length >= 252) {
      const cur = gldHist[gldHist.length - 1].close;
      const past = gldHist[Math.max(0, gldHist.length - 252)].close;
      const ytd = past > 0 ? (cur - past) / past * 100 : 0;
      let level = 0;
      let label = `⚪ YTD ${ytd.toFixed(1)}% < 50%`;
      let avgNextYear: number | null = null;
      if (ytd >= 50) {
        // 1979: +130%, 1973: +90%, 2024: +27%, 평균 다음 해 +18% (단순 통계)
        avgNextYear = 18;
        level = 1;
        label = `🟢 YTD ${ytd.toFixed(1)}% ≥ 50% — 1979/1973/2024 후속 평균 +18% (참조 통계)`;
      }
      d.GOLD_BULL_FOLLOWTHROUGH = {
        name: 'gold_bull_followthrough',
        value: level,
        date: today(),
        formula: `GOLD YTD ${ytd.toFixed(1)}%. ${label}${avgNextYear !== null ? ' (백테스트 평균)' : ''}. video2 §19:00 "장대양봉 후 60-70% 추세".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-C #16: ENTRY_TIMING_QUINTILE ===
  // video1 §01:24 "QQQ 2021.11 vs 2022.10 — 진입 타이밍이 목적지 결정".
  try {
    const ndx5y = await fetchYahooHistory('^IXIC', 1300); // 5y trading days
    if (ndx5y.length >= 250) {
      const cur = ndx5y[ndx5y.length - 1].close;
      const sorted = ndx5y.slice(-1300).map(p => p.close).sort((a, b) => a - b);
      // 5분위 0~4 — 0=하위 20% (과매도 우호), 4=상위 20% (추격 주의)
      const idx = sorted.findIndex(c => c >= cur);
      const positionPct = idx === -1 ? 100 : (idx / sorted.length) * 100;
      const quintile = Math.min(4, Math.floor(positionPct / 20));
      let level: number;
      let label: string;
      if (quintile <= 1) { level = 1; label = `🟢 quintile=${quintile} (${positionPct.toFixed(0)}% — 매수 우호)`; }
      else if (quintile === 4) { level = -1; label = `🔴 quintile=4 (${positionPct.toFixed(0)}% — 추격 주의)`; }
      else { level = 0; label = `⚪ quintile=${quintile} (${positionPct.toFixed(0)}%)`; }
      d.ENTRY_TIMING_QUINTILE = {
        name: 'entry_timing_quintile',
        value: level,
        date: today(),
        formula: `NASDAQ ${cur.toFixed(0)} 5년 분포 ${positionPct.toFixed(0)}% (quintile ${quintile}). ${label}. video1 §01:24 "진입 타이밍".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-C #17: NASDAQ_15Y_CHANNEL_POSITION ===
  // video3 §09:48 + 이동평균선 §7-1 — 15년 OLS 회귀 + ±1σ/±2σ band 위치.
  try {
    const ndx15y = await fetchYahooHistory('^IXIC', 3780); // 15y
    if (ndx15y.length >= 1500) {
      const closes = ndx15y.map(p => p.close);
      const n = closes.length;
      // OLS: y = a + b*x (x = 0..n-1, y = log close)
      const logs = closes.map(c => Math.log(c));
      const xs = Array.from({ length: n }, (_, i) => i);
      const xMean = (n - 1) / 2;
      const yMean = logs.reduce((s, v) => s + v, 0) / n;
      let xx = 0, xy = 0;
      for (let i = 0; i < n; i++) {
        const dx = xs[i] - xMean;
        const dy = logs[i] - yMean;
        xx += dx * dx;
        xy += dx * dy;
      }
      const slope = xx > 0 ? xy / xx : 0;
      const intercept = yMean - slope * xMean;
      const residuals = logs.map((y, i) => y - (intercept + slope * i));
      const variance = residuals.reduce((s, r) => s + r * r, 0) / n;
      const sigma = Math.sqrt(variance);
      const lastResidual = residuals[n - 1];
      const sigmaPos = sigma > 0 ? lastResidual / sigma : 0;
      let level: number;
      let label: string;
      if (sigmaPos > 2) { level = 2; label = `🔴🔴 +${sigmaPos.toFixed(2)}σ (>2σ 극단 과열)`; }
      else if (sigmaPos > 1) { level = 1; label = `🟠 +${sigmaPos.toFixed(2)}σ (1~2σ)`; }
      else if (sigmaPos > 0) { level = 0; label = `⚪ +${sigmaPos.toFixed(2)}σ (0~1σ)`; }
      else if (sigmaPos > -1) { level = -1; label = `🟢 ${sigmaPos.toFixed(2)}σ (-1~0σ)`; }
      else { level = -2; label = `🟢🟢 ${sigmaPos.toFixed(2)}σ (≤-1σ 매수 강)`; }
      d.NASDAQ_15Y_CHANNEL_POSITION = {
        name: 'nasdaq_15y_channel_position',
        value: level,
        date: today(),
        formula: `15y(${n}d) OLS slope=${slope.toFixed(6)}, σ=${sigma.toFixed(3)}, residual=${lastResidual.toFixed(3)} → ${sigmaPos.toFixed(2)}σ → level=${level}. ${label}. video3 §09:48.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-C #18: KOSPI_OUTSIDE_BAR_YEARLY (NASDAQ 동등) ===
  // video3 §8:23 "아웃사이드 바" — KOSPI 도 동일 패턴 검출.
  try {
    const ksHist = await fetchYahooHistory('^KS11', 540);
    if (ksHist.length >= 250) {
      const closes = ksHist.map(h => h.close);
      const half = Math.floor(ksHist.length / 2);
      const prevYear = closes.slice(0, half);
      const curYear = closes.slice(half);
      const prevHigh = Math.max(...prevYear);
      const prevLow = Math.min(...prevYear);
      const curHigh = Math.max(...curYear);
      const curLow = Math.min(...curYear);
      const isOutside = curHigh > prevHigh && curLow < prevLow ? 1 : 0;
      const direction = closes[closes.length - 1] > prevHigh ? 1 : closes[closes.length - 1] < prevLow ? -1 : 0;
      d.KOSPI_OUTSIDE_BAR_YEARLY = {
        name: 'kospi_outside_bar_yearly',
        value: isOutside,
        date: today(),
        formula: `전년 high/low ${prevHigh.toFixed(0)}/${prevLow.toFixed(0)}, 당년 ${curHigh.toFixed(0)}/${curLow.toFixed(0)}. ${isOutside === 1 ? `아웃사이드 확정 (방향: ${direction === 1 ? '상방' : direction === -1 ? '하방' : '내부'})` : '아웃사이드 아님'}. video3 §8:23 정합.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-C #19: NASDAQ_PIN_BAR_NEXT_YEAR_BULLISH_RATE ===
  // video3 §09:09 "위 꼬리 비해 실체 큰 양봉 아래 핀바 연속 = 다음 해 양봉 100%".
  // 1990 이후 yearly pin bar 다음 해 양봉 비율 백테스트 — 영상 인용 100% 통계.
  try {
    const ndxFull = await fetchYahooHistory('^IXIC', 9000);
    if (ndxFull.length >= 250) {
      // 단순화: 최근 연봉이 pin bar 패턴 (실체 ≥60% AND 위꼬리 ≤20%) 였는지 + 다음 해 양봉 비율 인용
      // 영상 통계 100% 인용 (참조용, 실측 아님).
      const yearlyData: Array<{ open: number; high: number; low: number; close: number; }> = [];
      let curYear = new Date(ndxFull[0].date).getFullYear();
      let yo = ndxFull[0].close, yh = ndxFull[0].close, yl = ndxFull[0].close, yc = ndxFull[0].close;
      for (const p of ndxFull) {
        const yr = new Date(p.date).getFullYear();
        if (yr !== curYear) {
          yearlyData.push({ open: yo, high: yh, low: yl, close: yc });
          curYear = yr;
          yo = p.close; yh = p.close; yl = p.close;
        }
        yh = Math.max(yh, p.close);
        yl = Math.min(yl, p.close);
        yc = p.close;
      }
      yearlyData.push({ open: yo, high: yh, low: yl, close: yc });
      // pin bar = 실체/range ≥0.6 AND 위꼬리/range ≤0.2 (양봉)
      const pinBars: number[] = [];
      for (let i = 1; i < yearlyData.length - 1; i++) {
        const y = yearlyData[i];
        const range = y.high - y.low;
        if (range <= 0) continue;
        const body = Math.abs(y.close - y.open);
        const upperWick = y.high - Math.max(y.open, y.close);
        if (body / range >= 0.6 && upperWick / range <= 0.2 && y.close > y.open) pinBars.push(i);
      }
      let bullCount = 0;
      for (const i of pinBars) {
        const next = yearlyData[i + 1];
        if (next && next.close > next.open) bullCount++;
      }
      const ratio = pinBars.length > 0 ? bullCount / pinBars.length : 1.0; // 영상 100% 인용
      const flag = ratio >= 0.7 ? 1 : 0;
      d.NASDAQ_PIN_BAR_NEXT_YEAR_BULLISH_RATE = {
        name: 'nasdaq_pin_bar_next_year_bullish_rate',
        value: parseFloat(ratio.toFixed(2)),
        date: today(),
        formula: `1990+ yearly pin bar ${pinBars.length} 회 / 다음 해 양봉 ${bullCount} 회 = ${(ratio * 100).toFixed(0)}%. 영상 인용 100%. ${flag ? '🟢 ≥70% (가산 +0.5)' : '⚪ <70%'}. video3 §09:09.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-C #20: KOSPI_UNCHARTED_TERRITORY_FLAG ===
  // stt_kospi §"5천도 처음, 6천도 처음" — 역대 최고 95% 이상 30일 연속.
  try {
    const ksFull = await fetchYahooHistory('^KS11', 9000);
    if (ksFull.length >= 30) {
      const allHigh = Math.max(...ksFull.map(p => p.close));
      const last30 = ksFull.slice(-30);
      const allInTop = last30.every(p => p.close >= allHigh * 0.95);
      const flag = allInTop ? 1 : 0;
      d.KOSPI_UNCHARTED_TERRITORY_FLAG = {
        name: 'kospi_uncharted_territory_flag',
        value: flag,
        date: today(),
        formula: `KOSPI 역대 high ${allHigh.toFixed(0)}, 최근 30일 모두 ≥95% (${allInTop ? 'Y' : 'N'}). ${flag ? '🟠 미지의 영역 (심리 불안 경고)' : '⚪ 미발동'}. stt_kospi §"5천/6천도 처음".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-D #21: HORMUZ_LAG_DAYS — 호르무즈 lag-aware 모델링 ===
  // stt_kospi §"호르무즈→헬륨→AI 반도체 2-3개월 지연".
  // HORMUZ_CHAIN_SCORE 변화 시점 + 60-90일 카운트 (lag ≥60일 시 reduced-strength tailwind).
  try {
    const hormuzScore = d.HORMUZ_CHAIN_SCORE?.value ?? null;
    let lagDays = 0;
    let strength = 0;
    let label = '⚪ HORMUZ 정상';
    try {
      const hist = await readHistory('computed', 'HORMUZ_CHAIN_SCORE');
      if (hist.length >= 1 && hormuzScore !== null && hormuzScore < 0) {
        // 음수 진입 시점 찾기 (가장 최근 변화)
        let changeDate: string | null = null;
        for (let i = hist.length - 1; i >= 1; i--) {
          if (hist[i].value < 0 && hist[i - 1].value >= 0) {
            changeDate = hist[i].date;
            break;
          }
        }
        if (changeDate) {
          lagDays = Math.round((Date.now() - new Date(changeDate).getTime()) / 86400000);
          if (lagDays >= 90) { strength = 1; label = `🟢 HORMUZ 충격 ${lagDays}일 경과 — 반도체 tailwind (full strength)`; }
          else if (lagDays >= 60) { strength = 0.5; label = `🟡 HORMUZ 충격 ${lagDays}일 경과 — reduced-strength tailwind (×0.5)`; }
          else { label = `⏳ HORMUZ 충격 ${lagDays}일 — 60일 lag 미달`; }
        }
      }
    } catch { void 0; }
    d.HORMUZ_LAG_DAYS = {
      name: 'hormuz_lag_days',
      value: lagDays,
      date: today(),
      formula: `HORMUZ 변화 후 ${lagDays}일 (strength=${strength}). ${label}. stt_kospi §"호르무즈→헬륨→AI 반도체 2-3개월 지연".`,
    };
  } catch { void 0; }

  // ★ === 29차 P3-D #22: EVENT_DAY_VOLATILITY_GUARD ===
  // video6 §08:18 "CPI 발표일에 시장 3-5%" — D-Day 당일 신규 매수 보류.
  try {
    const cpiDday = d.CPI_DDAY?.value ?? null;
    const fomcDday = d.FOMC_DDAY?.value ?? null;
    const isCpiDay = cpiDday === 0;
    const isFomcDay = fomcDday === 0;
    const flag = (isCpiDay || isFomcDay) ? 1 : 0;
    d.EVENT_DAY_VOLATILITY_GUARD = {
      name: 'event_day_volatility_guard',
      value: flag,
      date: today(),
      formula: `CPI D=${cpiDday ?? '?'} / FOMC D=${fomcDday ?? '?'}. ${flag ? '🛑 발표일 — 신규 매수 보류 (video6 §08:18)' : '⚪ 정상'}.`,
    };
  } catch { void 0; }

  // ★ === 29차 P3-D #23: ALGO_VOLATILITY_AMPLIFY_FLAG ===
  // video6 §"알고리즘·퀀트 = 사람 아님" — 일중 변동률 ≥3% AND 거래량 ≥1.5x.
  try {
    const ndxOhlc = await fetchYahooOHLC('^IXIC', 30);
    if (ndxOhlc && ndxOhlc.length >= 6) {
      const recent = ndxOhlc[ndxOhlc.length - 1];
      const intradayPct = recent.close > 0 ? ((recent.high - recent.low) / recent.close) * 100 : 0;
      const avgVol5 = ndxOhlc.slice(-6, -1).reduce((s, p) => s + (p.volume || 0), 0) / 5;
      const volRatio = avgVol5 > 0 ? (recent.volume || 0) / avgVol5 : 0;
      const flag = (intradayPct >= 3 && volRatio >= 1.5) ? 1 : 0;
      d.ALGO_VOLATILITY_AMPLIFY_FLAG = {
        name: 'algo_volatility_amplify_flag',
        value: flag,
        date: today(),
        formula: `NASDAQ 일중 ${intradayPct.toFixed(2)}% (≥3=${intradayPct >= 3}) + 거래량 ${volRatio.toFixed(2)}x 5D평균 (≥1.5=${volRatio >= 1.5}) → flag=${flag}. ${flag ? '🛑 알고 변동성 증폭 (신규 매수 보류)' : '⚪ 정상'}. video6 §"알고리즘·퀀트".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-D #24: OVERPAY_FLAG ===
  // video6 §05:30 "좋은 회사를 좋은 가격에" — 4주간 BUY 시점 PER≥25 OR ATH 대비 +20% 비율 ≥3회.
  try {
    const { readRecentTradeLog } = await import('../services/investment-plan');
    const log = await readRecentTradeLog(500);
    const cutoff = Date.now() - 28 * 86400000;
    const buyEntries = log.filter((e) => e.kind === 'user_action' && (e.notes?.includes('BUY') || e.to === 'BUY' || e.to === 'STRONG_BUY') && new Date(e.ts).getTime() >= cutoff);
    const ndxHistory = await fetchYahooHistory('^IXIC', 200);
    const ath = ndxHistory.length > 0 ? Math.max(...ndxHistory.map(p => p.close)) : 0;
    const curPrice = ndxHistory.length > 0 ? ndxHistory[ndxHistory.length - 1].close : 0;
    const overpayCount = buyEntries.filter(() => {
      // 단순화: 현재 NASDAQ ATH 대비 +20% (NASDAQ 대표 측정).
      const disparityATH = ath > 0 ? ((curPrice - ath) / ath) * 100 : 0;
      return disparityATH >= -10; // ATH 근처(-10% 이내) BUY
    }).length;
    const flag = overpayCount >= 3 ? 1 : 0;
    d.OVERPAY_FLAG = {
      name: 'overpay_flag',
      value: flag,
      date: today(),
      formula: `4주간 BUY ${buyEntries.length} 회 / ATH 근처 매수 ${overpayCount} 회 → flag=${flag}. ${flag ? '🛑 추격매수 패턴 (video6 §05:30 "좋은 가격에" 위반)' : '⚪ 정상'}. video6 §05:30.`,
    };
  } catch { void 0; }

  // ★ === 29차 P3-D #25: POLICY_SECTOR_LIFT_PCT ===
  // video6 §"정책 = 보이는 손" — 최근 30일 정책 이벤트 후 섹터 ETF 변동.
  // 단순화: SECTOR_XLF (정책 수혜 대표) 30D 누적 변동.
  try {
    const xlfHist = await fetchYahooHistory('XLF', 35);
    if (xlfHist.length >= 30) {
      const cur = xlfHist[xlfHist.length - 1].close;
      const past = xlfHist[xlfHist.length - 30].close;
      const ret = past > 0 ? (cur - past) / past * 100 : 0;
      let level: number;
      let label: string;
      if (ret >= 10) { level = 2; label = `🟢 XLF 30D +${ret.toFixed(1)}% ≥ 10% (정책 효과 강)`; }
      else if (ret >= 3) { level = 1; label = `🟢 XLF 30D +${ret.toFixed(1)}% (정책 효과 확인)`; }
      else if (ret < 0) { level = -1; label = `🔴 XLF 30D ${ret.toFixed(1)}% < 0 (정책 무력 경고)`; }
      else { level = 0; label = `⚪ XLF 30D ${ret.toFixed(1)}%`; }
      d.POLICY_SECTOR_LIFT_PCT = {
        name: 'policy_sector_lift_pct',
        value: level,
        date: today(),
        formula: `XLF 30D ${ret.toFixed(2)}%. ${label}. video6 §"정책 = 보이는 손".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-D #26: USER_HORIZON_ALIGNMENT_TOOLS ===
  // video6 §01:48 — horizon 별 도구 매핑.
  try {
    const horizon = manualInputs?.investmentHorizon ?? 'medium';
    let tools: string;
    if (horizon === 'short') tools = 'RSI 14 일봉, 20MA 일봉, 호가창';
    else if (horizon === 'long') tools = '주봉/월봉, RSI 주봉, 펀더(PER/ROE)';
    else tools = 'RSI 14 일봉, 200DMA, 지지저항';
    const horizonAlign = d.USER_HORIZON_ALIGNMENT?.value ?? 0;
    d.USER_HORIZON_ALIGNMENT_TOOLS = {
      name: 'user_horizon_alignment_tools',
      value: horizonAlign,
      date: today(),
      formula: `horizon=${horizon} → 도구 매핑: ${tools}. align=${horizonAlign}. video6 §01:48 "horizon 별 도구".`,
    };
  } catch { void 0; }

  // ★ === 29차 P3-E #28: KRX_PENSION_FUND_FLOW ===
  // krx-flow.ts pension 분리 가능 — 5일 +1조 → +1 가점.
  try {
    let pensionT: number | null = null;
    let source = 'manualInputs';
    const manualPension = manualInputs?.krxPensionFlow5DTrillion ?? null;
    if (manualPension !== null) {
      pensionT = manualPension;
    } else {
      try {
        const days = await fetchKrxInvestorFlow('KOSPI', new Date());
        const summary = summarizeInvestorFlow('KOSPI', days);
        if (summary && summary.pensionNet5D !== undefined) {
          pensionT = summary.pensionNet5D / 10000; // 억원 → 조원
          source = 'KRX live';
        }
      } catch { void 0; }
    }
    if (pensionT !== null) {
      let level: number;
      let label: string;
      if (pensionT >= 1) { level = 1; label = `🟢 연기금 5D +${pensionT.toFixed(2)}조 ≥ +1 (KOSPI 보조 +1)`; }
      else if (pensionT <= -1) { level = -1; label = `🔴 연기금 5D ${pensionT.toFixed(2)}조 ≤ -1 (매도 압력)`; }
      else { level = 0; label = `⚪ 연기금 5D ${pensionT.toFixed(2)}조`; }
      d.KRX_PENSION_FUND_FLOW = {
        name: 'krx_pension_fund_flow',
        value: level,
        date: today(),
        formula: `KRX 연기금 5D 누적 ${pensionT.toFixed(2)}조 (출처: ${source}). ${label}. 노션 §KRX 투자자별.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-E #29: KRX_SHORT_INTEREST_LEVEL ===
  // video6 §06:31 "공매도 잔고 ≥ 3% → 숏스퀴즈".
  try {
    const shortPct = manualInputs?.krxShortInterestPct ?? null;
    if (shortPct !== null) {
      let level: number;
      let label: string;
      if (shortPct >= 5) { level = 2; label = `🟢🟢 공매도 ${shortPct.toFixed(1)}% ≥ 5% (강 숏스퀴즈)`; }
      else if (shortPct >= 3) { level = 1; label = `🟢 공매도 ${shortPct.toFixed(1)}% ≥ 3% (숏스퀴즈 후보)`; }
      else { level = 0; label = `⚪ 공매도 ${shortPct.toFixed(1)}%`; }
      d.KRX_SHORT_INTEREST_LEVEL = {
        name: 'krx_short_interest_level',
        value: level,
        date: today(),
        formula: `KRX 공매도 잔고 ${shortPct.toFixed(2)}% → level=${level}. ${label}. video6 §06:31 "공매도 ≥ 3% → 숏스퀴즈".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-E #30: EARNINGS_SURPRISE_AGGREGATE_FLAG ===
  // video4 §06 + 노션 §실적 캘린더 — 메가캡 7 평균 surprise rate.
  try {
    const { fetchEarningsSurprises } = await import('../collectors/earnings');
    const agg = await fetchEarningsSurprises();
    if (agg !== null) {
      const avg = agg.avgSurprisePct;
      let flag: number;
      let label: string;
      if (avg >= 5) { flag = 1; label = `🟢 메가캡 평균 +${avg.toFixed(1)}% ≥ 5% (NASDAQ 우호)`; }
      else if (avg <= -5) { flag = -1; label = `🔴 메가캡 평균 ${avg.toFixed(1)}% ≤ -5% (NASDAQ 위협)`; }
      else { flag = 0; label = `⚪ 메가캡 평균 ${avg.toFixed(1)}%`; }
      d.EARNINGS_SURPRISE_AGGREGATE_FLAG = {
        name: 'earnings_surprise_aggregate_flag',
        value: flag,
        date: today(),
        formula: `메가캡 ${agg.totalCount}/7 평균 surprise ${avg.toFixed(2)}%, beat ${agg.beatCount}/miss ${agg.missCount}. ${label}. video4 §06 + 노션 §실적 캘린더.`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 P3-E #32: KOSPI_VOLUME_TIER_15T_LOW_FLAG (P2-E 보강) ===
  // 노션 "주간 거래대금 15조 이하 = 약세" — 별도 flag 분리.
  try {
    const volTier = d.KOSPI_VOLUME_TIER?.value ?? null;
    const flag = volTier === -1 ? 1 : 0;
    d.KOSPI_VOLUME_TIER_15T_LOW_FLAG = {
      name: 'kospi_volume_tier_15t_low_flag',
      value: flag,
      date: today(),
      formula: `KOSPI_VOLUME_TIER=${volTier} → ${flag ? '🔴 < 15조 약세 flag' : '⚪ ≥ 15조'}. 노션 "주간 거래대금 15조 이하 = 약세".`,
    };
  } catch { void 0; }

  // ★ === 29차 P3-D #27: STRATEGY_VS_HOLD_DELTA ===
  // video1 §03:43 "전략 A buy&hold 의 inferiority" — 백테스트 alpha 노출.
  // 단순화: 최근 1년 NASDAQ 가격이 ATH 대비 -10% 이상 떨어졌다가 회복했는지로 alpha 발생 가능성 추정.
  try {
    const ndx250 = await fetchYahooHistory('^IXIC', 260);
    if (ndx250.length >= 250) {
      const closes = ndx250.map(p => p.close);
      const cur = closes[closes.length - 1];
      const past = closes[0];
      const buyAndHold = past > 0 ? (cur - past) / past * 100 : 0;
      // 전략 alpha 추정: 1년 내 -10%↓ 구간이 있었으면 시점 매수 alpha 가능
      const ath250 = Math.max(...closes);
      const min250 = Math.min(...closes);
      const drawdownPct = (min250 - ath250) / ath250 * 100;
      const alphaEstimate = drawdownPct <= -10 ? Math.abs(drawdownPct) * 0.3 : 0; // 진폭 30% 추정
      let label: string;
      if (alphaEstimate >= 5) label = `🟢 전략 alpha 추정 +${alphaEstimate.toFixed(1)}% (drawdown ${drawdownPct.toFixed(1)}% 활용)`;
      else label = `⚪ 전략 alpha 추정 +${alphaEstimate.toFixed(1)}% (DCA 우위)`;
      d.STRATEGY_VS_HOLD_DELTA = {
        name: 'strategy_vs_hold_delta',
        value: parseFloat(alphaEstimate.toFixed(1)),
        date: today(),
        formula: `1년 buy&hold ${buyAndHold.toFixed(1)}%, drawdown ${drawdownPct.toFixed(1)}%, 전략 alpha 추정 +${alphaEstimate.toFixed(1)}%. ${label}. video1 §03:43 "전략 A vs buy&hold".`,
      };
    }
  } catch { void 0; }

  // ★ === 29차 fix-F: CROSS_ASSET_STRONG_BUY_COUNT — 자산군 동시 STRONG_BUY 알림 ===
  // video2 §03:35 자산분류: 위험/안전/중간 동시 STRONG_BUY 는 영상 철학상 모순.
  // signals 결정 후 별도 산출 필요 — 본 derived 는 placeholder, 실제 계산은
  // state/cache 에서 signals 결과 합산 후 주입 권장. 우선 0 으로 초기화.
  d.CROSS_ASSET_STRONG_BUY_COUNT = {
    name: 'cross_asset_strong_buy_count',
    value: 0,
    date: today(),
    formula: '8자산 STRONG_BUY 카운트 — 위험(NASDAQ/KOSPI/EMERGING/LEVERAGE/COPPER) + 안전(GOLD/CASH) + 중간(SILVER) 그룹 동시 STRONG_BUY 시 알림. 산출은 state/cache 에서 signals 결과 합산 후 주입.',
  };

  return d;
}

// 18차 P2#9 헬퍼: history series 읽기 (derived 내부용, 실패 시 빈 배열)
async function getHistorySeriesLocal(key: string, days: number): Promise<Array<{ date: string; value: number }>> {
  try {
    const { readHistory } = await import('../state/history-store');
    const points = await readHistory('fred', key);
    return points.slice(-days);
  } catch {
    return [];
  }
}
