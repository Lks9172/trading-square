import { MarketDataPoint, DerivedIndicator } from '../types/indicators';
import { fetchYahooHistory } from '../collectors/yahoo';
import { fetchFredHistory } from '../collectors/fred';
import { readHistory } from '../state/history-store';

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

  if (usYoY !== null) {
    d.US_M2_YOY = {
      name: 'us_m2_yoy',
      value: parseFloat(usYoY.toFixed(2)),
      date: dt,
      formula: '미국 M2SL 최신월 / 12개월 전 - 1 (%)',
    };
  }
  if (euroYoY !== null) {
    d.EURO_M3_YOY = {
      name: 'euro_m3_yoy',
      value: parseFloat(euroYoY.toFixed(2)),
      date: dt,
      formula: '유로 M3 최신월 / 12개월 전 - 1 (%)',
    };
  }
  if (japanYoY !== null) {
    d.JAPAN_M3_YOY = {
      name: 'japan_m3_yoy',
      value: parseFloat(japanYoY.toFixed(2)),
      date: dt,
      formula: '일본 M3 최신월 / 12개월 전 - 1 (%)',
    };
  }

  const m2Components = [usYoY, euroYoY, japanYoY].filter((v): v is number => v !== null);
  if (m2Components.length > 0) {
    const globalAvg = m2Components.reduce((s, v) => s + v, 0) / m2Components.length;
    d.GLOBAL_M2_PROXY = {
      name: 'global_m2_proxy',
      value: parseFloat(globalAvg.toFixed(2)),
      date: dt,
      formula: `미국 M2 + 유로 M3 + 일본 M3 의 YoY% 평균 (${m2Components.length}개 기여)`,
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

  const sectorEtfs: Array<[string, string]> = [['XLK','기술'],['XLF','금융'],['XLE','에너지'],['XLV','헬스케어'],['XLI','산업재'],['XLY','임의소비재']];
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

  return d;
}
