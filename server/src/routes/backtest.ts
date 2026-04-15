import { Router, Request, Response } from 'express';
import { readHistory } from '../state/history-store';
import { classifyRegime } from '../engines/regime';
import { computeSignals } from '../engines/signals';
import { computeAllocation } from '../engines/allocation';
import { DEFAULT_PROFILE } from '../state/cache';
import { MarketDataPoint, DerivedIndicator } from '../types/indicators';

const router = Router();

router.get('/summary', async (_req: Request, res: Response) => {
  try {
    const nasdaqHist = await readHistory('yahoo', 'NASDAQ');
    const regimeHist = await readHistory('signal', 'REGIME');
    const nasdaqSignalHist = await readHistory('signal', 'NASDAQ');

    if (!nasdaqHist.length || !regimeHist.length) {
      res.json({ error: 'Insufficient history data for backtest' });
      return;
    }

    const periods = [
      { label: '1Y', days: 252 },
      { label: '3Y', days: 756 },
      { label: '5Y', days: 1260 },
    ];

    const results = periods.map(({ label, days }) => {
      const slice = nasdaqHist.slice(-Math.min(days, nasdaqHist.length));
      if (slice.length < 2) return { label, return_pct: 0, max_drawdown: 0, regime_changes: 0 };

      const startPrice = slice[0].value;
      const endPrice = slice[slice.length - 1].value;
      const returnPct = ((endPrice - startPrice) / startPrice) * 100;

      let peak = startPrice;
      let maxDrawdown = 0;
      for (const p of slice) {
        if (p.value > peak) peak = p.value;
        const dd = ((p.value - peak) / peak) * 100;
        if (dd < maxDrawdown) maxDrawdown = dd;
      }

      const regimeSlice = regimeHist.slice(-Math.min(days, regimeHist.length));
      let regimeChanges = 0;
      for (let i = 1; i < regimeSlice.length; i++) {
        if (regimeSlice[i].value !== regimeSlice[i - 1].value) regimeChanges++;
      }

      return {
        label,
        return_pct: parseFloat(returnPct.toFixed(2)),
        max_drawdown: parseFloat(maxDrawdown.toFixed(2)),
        regime_changes: regimeChanges,
        data_points: slice.length,
      };
    });

    const buySignalDays = nasdaqSignalHist.filter((p) => p.value >= 75).length;
    const totalDays = nasdaqSignalHist.length;

    res.json({
      periods: results,
      signal_stats: {
        total_days: totalDays,
        buy_signal_days: buySignalDays,
        buy_signal_ratio: totalDays > 0 ? parseFloat((buySignalDays / totalDays * 100).toFixed(1)) : 0,
      },
    });
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Backtest failed' });
  }
});

router.get('/portfolio', async (req: Request, res: Response) => {
  try {
    const yearsParam = parseInt(String(req.query.years || '3'), 10);
    const days = Math.min(yearsParam * 252, 1260);

    const assets: Record<string, { key: string; source: string }> = {
      nasdaq: { key: 'NASDAQ', source: 'yahoo' },
      gold: { key: 'GOLD', source: 'yahoo' },
      silver: { key: 'SILVER', source: 'yahoo' },
      copper: { key: 'COPPER', source: 'yahoo' },
      korea: { key: 'KOSPI', source: 'yahoo' },
      emerging: { key: 'EWZ', source: 'yahoo' },
      leverage: { key: 'TQQQ', source: 'yahoo' },  // 3x 나스닥 ETF — leverage 비중 실현 수익률
    };

    const histories: Record<string, Array<{ date: string; value: number }>> = {};
    for (const [name, { key, source }] of Object.entries(assets)) {
      histories[name] = await readHistory(source, key);
    }

    const fredKeys = ['DGS10', 'T10YIE', 'T10Y2Y', 'VIXCLS', 'BAMLH0A0HYM2', 'STLFSI4', 'ICSA', 'UNRATE'];
    const fredHistories: Record<string, Array<{ date: string; value: number }>> = {};
    for (const key of fredKeys) {
      fredHistories[key] = await readHistory('fred', key);
    }
    const dxyHist = await readHistory('yahoo', 'DXY');

    const nasdaqHist = histories.nasdaq;
    if (nasdaqHist.length < 10) {
      res.json({ error: 'Insufficient data' });
      return;
    }

    const baseSlice = nasdaqHist.slice(-Math.min(days, nasdaqHist.length));

    function latestBefore(arr: Array<{ date: string; value: number }>, date: string): number | null {
      for (let i = arr.length - 1; i >= 0; i--) {
        if (arr[i].date <= date) return arr[i].value;
      }
      return null;
    }

    let portfolioValue = 100;
    let buyHoldValue = 100;
    const portfolioSeries: Array<{ date: string; portfolio: number; buyhold: number }> = [];

    for (let i = 1; i < baseSlice.length; i++) {
      const date = baseSlice[i].date;
      const prevDate = baseSlice[i - 1].date;

      const raw: Record<string, MarketDataPoint> = {};
      for (const key of fredKeys) {
        const v = latestBefore(fredHistories[key], prevDate);
        if (v !== null) raw[key] = { code: key, value: v, date: prevDate, source: 'FRED' };
      }
      const dxyVal = latestBefore(dxyHist, prevDate);
      if (dxyVal !== null) raw['DXY'] = { code: 'DXY', value: dxyVal, date: prevDate, source: 'YAHOO' };

      const derived: Record<string, DerivedIndicator> = {};
      const dgs10 = raw.DGS10?.value;
      const t10yie = raw.T10YIE?.value;
      if (dgs10 !== undefined && t10yie !== undefined) {
        derived.REAL_YIELD = { name: 'real_yield', value: dgs10 - t10yie, date: prevDate, formula: '' };
      }

      const nasdaqEligible = nasdaqHist.filter((p) => p.date <= prevDate);
      if (nasdaqEligible.length >= 200) {
        const sma200 = nasdaqEligible.slice(-200).reduce((s, p) => s + p.value, 0) / 200;
        const current = nasdaqEligible[nasdaqEligible.length - 1].value;
        derived.NASDAQ_DISPARITY = { name: 'nasdaq_disparity_200', value: ((current - sma200) / sma200) * 100, date: prevDate, formula: '' };
        derived.NASDAQ_ABOVE_200DMA = { name: 'nasdaq_above_200dma', value: current > sma200 ? 1 : 0, date: prevDate, formula: '' };
      }

      const regime = classifyRegime({ raw, derived, manualInputs: DEFAULT_PROFILE.manualInputs });
      const signals = computeSignals(raw, derived, regime, DEFAULT_PROFILE);
      const allocation = computeAllocation(regime.regime, regime.score, signals, derived, raw, 'long');

      let dailyReturn = 0;
      for (const [assetName, pct] of Object.entries(allocation.allocations)) {
        if (assetName === 'cash') continue; // cash 는 0 수익 처리
        const hist = histories[assetName];
        if (!hist) continue;
        const prevPrice = latestBefore(hist, prevDate);
        const curPrice = latestBefore(hist, date);
        if (prevPrice && curPrice && prevPrice > 0) {
          dailyReturn += (pct / 100) * ((curPrice - prevPrice) / prevPrice);
        }
      }

      portfolioValue *= (1 + dailyReturn);

      const nasdaqPrev = baseSlice[i - 1].value;
      const nasdaqCur = baseSlice[i].value;
      if (nasdaqPrev > 0) {
        buyHoldValue *= (1 + (nasdaqCur - nasdaqPrev) / nasdaqPrev);
      }

      if (i % 5 === 0 || i === baseSlice.length - 1) {
        portfolioSeries.push({
          date,
          portfolio: parseFloat(portfolioValue.toFixed(2)),
          buyhold: parseFloat(buyHoldValue.toFixed(2)),
        });
      }
    }

    let pfPeak = 100;
    let pfMaxDD = 0;
    let bhPeak = 100;
    let bhMaxDD = 0;
    for (const p of portfolioSeries) {
      if (p.portfolio > pfPeak) pfPeak = p.portfolio;
      const pfDD = ((p.portfolio - pfPeak) / pfPeak) * 100;
      if (pfDD < pfMaxDD) pfMaxDD = pfDD;

      if (p.buyhold > bhPeak) bhPeak = p.buyhold;
      const bhDD = ((p.buyhold - bhPeak) / bhPeak) * 100;
      if (bhDD < bhMaxDD) bhMaxDD = bhDD;
    }

    const lastEntry = portfolioSeries[portfolioSeries.length - 1] || { portfolio: 100, buyhold: 100 };

    res.json({
      years: yearsParam,
      data_points: portfolioSeries.length,
      portfolio: {
        final_value: lastEntry.portfolio,
        return_pct: parseFloat((lastEntry.portfolio - 100).toFixed(2)),
        max_drawdown: parseFloat(pfMaxDD.toFixed(2)),
      },
      buyhold: {
        final_value: lastEntry.buyhold,
        return_pct: parseFloat((lastEntry.buyhold - 100).toFixed(2)),
        max_drawdown: parseFloat(bhMaxDD.toFixed(2)),
      },
      alpha: parseFloat(((lastEntry.portfolio - 100) - (lastEntry.buyhold - 100)).toFixed(2)),
      series: portfolioSeries,
    });
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Portfolio backtest failed' });
  }
});

export default router;
