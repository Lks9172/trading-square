import { MarketDataPoint, DerivedIndicator } from '../types/indicators';
import { fetchYahooHistory } from '../collectors/yahoo';
import { fetchFredHistory } from '../collectors/fred';
import { readHistory } from '../state/history-store';
import { fetchKrxInvestorFlow, summarizeInvestorFlow } from '../collectors/krx-flow';
import {
  fetchMultiTimeframe,
  detectClimaxExhaustion,
  detectWeeklyReversal,
  monthlyPositionScore,
  detectOutsideBar,
  detectWBottom,
  linearRegressionChannel,
} from './candles';

function val(raw: Record<string, MarketDataPoint>, key: string): number | null {
  return raw[key]?.value ?? null;
}

function today(): string {
  return new Date().toISOString().split('T')[0];
}

export async function computeDerived(
  raw: Record<string, MarketDataPoint>
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
  }

  const copper = val(raw, 'COPPER');
  if (gold !== null && copper !== null && gold > 0) {
    d.COPPER_GOLD_RATIO = {
      name: 'copper_gold_ratio',
      value: parseFloat((copper / gold).toFixed(6)),
      date: dt,
      formula: 'COPPER / GOLD',
    };
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
    }
  } catch {
    void 0;
  }

  // === 글로벌 M2 aggregate ===
  // 각 국가 시리즈의 "최신 vs 12개월 전" YoY%를 직접 계산해서 평균.
  // 기존에는 raw index level을 그대로 평균했는데 이는 의미가 없어 재작성.
  const readYoY = async (key: string): Promise<number | null> => {
    const hist = await readHistory('fred', key);
    if (hist.length < 2) return null;
    const latest = hist[hist.length - 1];
    const targetDt = new Date(latest.date);
    targetDt.setFullYear(targetDt.getFullYear() - 1);
    const targetMs = targetDt.getTime();
    let past: { date: string; value: number } | null = null;
    for (let i = hist.length - 1; i >= 0; i -= 1) {
      if (new Date(hist[i].date).getTime() <= targetMs) {
        past = hist[i];
        break;
      }
    }
    if (!past || past.value === 0) return null;
    return ((latest.value / past.value) - 1) * 100;
  };

  const [usYoY, euroYoY, japanYoY] = await Promise.all([
    readYoY('M2SL'),
    readYoY('M3_EURO'),
    readYoY('M3_JAPAN'),
  ]);

  // 광의통화 YoY 합리 범위: COVID 피크(미국 M2 약 +27%)까지 포섭하되,
  // 시리즈 base 변경/스케일 오류로 인한 극단값(예: Japan M3 +96%)은 평균에서 제외.
  const M2_YOY_MIN = -20;
  const M2_YOY_MAX = 30;
  const isM2Anomaly = (v: number) => v < M2_YOY_MIN || v > M2_YOY_MAX;

  const pushYoY = (
    key: 'US_M2_YOY' | 'EURO_M3_YOY' | 'JAPAN_M3_YOY',
    name: string,
    source: string,
    yoy: number | null,
  ) => {
    if (yoy === null) return;
    const anomaly = isM2Anomaly(yoy);
    d[key] = {
      name,
      value: parseFloat(yoy.toFixed(2)),
      date: dt,
      formula: anomaly
        ? `${source} 최신월 / 12개월 전 - 1 (%). 이상치(${M2_YOY_MIN}~${M2_YOY_MAX}% 범위 밖) — GLOBAL 평균 계산에서 제외됨`
        : `${source} 최신월 / 12개월 전 - 1 (%)`,
    };
  };

  pushYoY('US_M2_YOY', 'us_m2_yoy', '미국 M2SL', usYoY);
  pushYoY('EURO_M3_YOY', 'euro_m3_yoy', '유로 M3', euroYoY);
  pushYoY('JAPAN_M3_YOY', 'japan_m3_yoy', '일본 M3', japanYoY);

  const m2All = [usYoY, euroYoY, japanYoY].filter((v): v is number => v !== null);
  const m2Valid = m2All.filter((v) => !isM2Anomaly(v));
  const excludedCount = m2All.length - m2Valid.length;

  if (m2Valid.length > 0) {
    const globalAvg = m2Valid.reduce((s, v) => s + v, 0) / m2Valid.length;
    d.GLOBAL_M2_PROXY = {
      name: 'global_m2_proxy',
      value: parseFloat(globalAvg.toFixed(2)),
      date: dt,
      formula:
        `미국 M2 + 유로 M3 + 일본 M3 의 YoY% 평균 (${m2Valid.length}개 기여` +
        (excludedCount > 0 ? `, ${excludedCount}개 이상치 제외` : '') +
        `, ${M2_YOY_MIN}~${M2_YOY_MAX}% clamp)`,
    };
  }

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
    const tgaHist = await fetchFredHistory('WTREGEN', apiKey, 10);
    const mmfHist = await fetchFredHistory('WRMFNS', apiKey, 10);

    if (rrpHist.length >= 10) {
      d.RRP_DIRECTION = {
        name: 'rrp_direction',
        value: parseFloat((rrpHist[0].value - rrpHist[Math.min(9, rrpHist.length - 1)].value).toFixed(2)),
        date: dt,
        formula: 'RRP 최근값 - 10일전 (음수=시장유입)',
      };
    }
    if (tgaHist.length >= 2) {
      d.TGA_DIRECTION = {
        name: 'tga_direction',
        value: parseFloat((tgaHist[0].value - tgaHist[1].value).toFixed(2)),
        date: dt,
        formula: 'TGA 최근값 - 이전주 (음수=유동성공급)',
      };
    }
    if (mmfHist.length >= 2) {
      d.MMF_DIRECTION = {
        name: 'mmf_direction',
        value: parseFloat((mmfHist[0].value - mmfHist[1].value).toFixed(2)),
        date: dt,
        formula: 'MMF 최근값 - 이전주 (음수=위험자산이동)',
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
        formula: '1=골든크로스, -1=데드크로스, 0.5=50>200유지, -0.5=50<200유지',
      };
      d.NASDAQ_SMA50 = { name: 'nasdaq_sma50', value: parseFloat(sma50.toFixed(2)), date: dt, formula: 'SMA(NASDAQ,50)' };
    }
  } catch { void 0; }

  const sectorEtfs: Array<[string, string]> = [['XLK','기술'],['XLF','금융'],['XLE','에너지'],['XLV','헬스케어'],['XLI','산업재'],['XLY','임의소비재'],['SOXX','반도체(광역)'],['SMH','반도체(대형주)']];
  try {
    const sectorResults = await Promise.allSettled(sectorEtfs.map(([sym]) => fetchYahooHistory(sym, 30)));
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
  const ismVal = d.ISM_PROXY?.value;
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
    const lowICSA = icsaVal < 250000;
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

  // === 유동성 방향 종합 점수 (영상4 §120 "총량 아닌 방향") ===
  // RRP 감소 / TGA 감소 / MMF 감소 / WRESBAL 증가 / Global M2 YoY > 0 → 각 +1.
  // 반대 방향은 -1. 총 -5 ~ +5.
  let liqScore = 0;
  const liqParts: string[] = [];
  const rrpDir = d.RRP_DIRECTION?.value;
  if (rrpDir !== undefined && rrpDir !== null) {
    if (rrpDir < 0) { liqScore += 1; liqParts.push('RRP↓'); }
    else if (rrpDir > 0) { liqScore -= 1; liqParts.push('RRP↑'); }
  }
  const tgaDir = d.TGA_DIRECTION?.value;
  if (tgaDir !== undefined && tgaDir !== null) {
    if (tgaDir < 0) { liqScore += 1; liqParts.push('TGA↓'); }
    else if (tgaDir > 0) { liqScore -= 1; liqParts.push('TGA↑'); }
  }
  const mmfDir = d.MMF_DIRECTION?.value;
  if (mmfDir !== undefined && mmfDir !== null) {
    if (mmfDir < 0) { liqScore += 1; liqParts.push('MMF↓'); }
    else if (mmfDir > 0) { liqScore -= 1; liqParts.push('MMF↑'); }
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
    formula: `RRP/TGA/MMF 감소 + M2 YoY 양수 = 각 +1. -5~+5 범위. 현재: ${liqParts.join(' · ')}`,
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
      // Fiscal stress: 30년 20일에 +0.2%p 이상 급등 AND 현재 레벨 4.5%+
      const yieldCurve = val(raw, 'T10Y2Y') ?? 0;
      const fiscalStress = (delta20 >= 0.2 && cur >= 4.5) || (delta20 >= 0.3);
      const curveSteepening = yieldCurve > 0.1;
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

      // === 채권 자경단 합성 지수 (영상4 §137-147 "재정적자 + 장기금리↑ + 달러↓ 3박자") ===
      // DGS30 상승 + DXY 약세 + HY 스프레드 확대 동시 충족 시 '정책 실패 프리커서' 단계적 경보.
      const dxyVal = val(raw, 'DXY');
      const dxyTrendLong = d.DXY_TREND_LONG?.value;
      const hySpread = val(raw, 'BAMLH0A0HYM2');
      const dxyWeak = (dxyTrendLong !== undefined && dxyTrendLong !== null && dxyTrendLong < -1)
                  || (dxyVal !== null && dxyVal < 100);
      const yieldRising = delta20 >= 0.15;  // 20일 +0.15%p 이상
      const hyWidening = hySpread !== null && hySpread >= 4.5; // 4.5% 이상 위험 스프레드
      const vigilanteScore =
        (yieldRising ? 1 : 0) +
        (dxyWeak ? 1 : 0) +
        (hyWidening ? 1 : 0);
      d.BOND_VIGILANTE_SCORE = {
        name: 'bond_vigilante_score',
        value: vigilanteScore,
        date: dt,
        formula: `장기금리↑${yieldRising ? 'Y' : 'N'} + DXY약세${dxyWeak ? 'Y' : 'N'} + HY확대${hyWidening ? 'Y' : 'N'} → 3축 동시 확인 시 채권자경단 경보 (영상4 §137)`,
      };
      if (vigilanteScore >= 2) {
        d.BOND_VIGILANTE_WARNING = {
          name: 'bond_vigilante_warning',
          value: 1,
          date: dt,
          formula: `채권 자경단 2축+ 충족 (${vigilanteScore}/3) — 정책 신뢰 이탈 프리커서`,
        };
      } else {
        d.BOND_VIGILANTE_WARNING = {
          name: 'bond_vigilante_warning',
          value: 0,
          date: dt,
          formula: `3축 중 ${vigilanteScore}개만 충족 — 경보 미발동`,
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
  }

  const nasdaqDisparity = d.NASDAQ_DISPARITY?.value ?? null;
  const fng = val(raw, 'FEAR_GREED');
  const vixVal = val(raw, 'VIXCLS');
  if (nasdaqDisparity !== null && nasdaqDisparity > 20 && fng !== null && fng > 75) {
    d.OVERHEATED = { name: 'overheated', value: 1, date: dt, formula: '이격도+20%이상 AND F&G 75+ → 과열' };
  } else if (nasdaqDisparity !== null && nasdaqDisparity > 15 && vixVal !== null && vixVal < 15) {
    d.OVERHEATED = { name: 'overheated', value: 1, date: dt, formula: '이격도+15%이상 AND VIX<15 → 과열' };
  } else {
    d.OVERHEATED = { name: 'overheated', value: 0, date: dt, formula: '과열 조건 미충족' };
  }

  // === 멀티 타임프레임 캔들 분석 (영상3·4·5 "월→주→일" 위계) ===
  // NASDAQ, KOSPI 주요 자산에 대해 월봉 소진/주봉 반전/월봉 위치지수 파생지표 생성.
  const mtfTargets: Array<{ symbol: string; prefix: string }> = [
    { symbol: '^IXIC', prefix: 'NASDAQ' },
    { symbol: '^KS11', prefix: 'KOSPI' },
  ];
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
        formula: '20D 순매수 합 ≥+3조 → 과열(+1) / ≤-3조 → 과매도(-1) / 중립(0)',
      };

      // === 외국인-환율 괴리 (ATM화) 지수 (영상5 §112 "환율 5% 상승 대비 외국인 매도 2배 과잉") ===
      // USDKRW 20일 변화율 × 예상매도 계수(3조/1% 상승) 로 기대 외국인 순매도 산출.
      // 실제 순매도가 기대치의 절대값 대비 2배 이상이면 ATM화 (과잉) 경고 → 반발 조기신호.
      try {
        const usdkrwHist = await readHistory('yahoo', 'USDKRW');
        if (usdkrwHist.length >= 20) {
          const curFx = usdkrwHist[usdkrwHist.length - 1].value;
          const oldFx = usdkrwHist[usdkrwHist.length - 20].value;
          const fxChangePct = ((curFx - oldFx) / oldFx) * 100;
          const expectedSell = fxChangePct * -30000; // 환율 1% 상승당 기대 매도 -3조 (음수)
          const actualNet = summary.foreignNet20D;
          const divergence = expectedSell !== 0 ? actualNet / expectedSell : 0;
          d.KOSPI_FX_FOREIGN_DIVERGENCE = {
            name: 'kospi_fx_foreign_divergence',
            value: parseFloat(divergence.toFixed(2)),
            date: summary.latestDate,
            formula: `실제 외국인20D 순매수 / 기대매도(환율상승×-3조). 2+ 는 과매도 ATM화(반발 후보), <0.5 는 정상/매수`,
          };
          if (divergence >= 2 && fxChangePct > 0) {
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

  return d;
}
