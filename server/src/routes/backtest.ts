import { Router, Request, Response } from 'express';
import { readHistory, recomputeFullDerivedForDate } from '../state/history-store';
import { classifyRegime } from '../engines/regime';
import { computeSignals } from '../engines/signals';
import { computeAllocation } from '../engines/allocation';
import { DEFAULT_PROFILE } from '../state/cache';
import { MarketDataPoint, DerivedIndicator } from '../types/indicators';
import { inferAutoManualInputsFromState, mergeEffectiveManualInputs } from '../services/policy-inputs';

const router = Router();

// 2026-04 개선: /portfolio?years=N 은 N년 × 252일 루프 (recomputeDerived + regime +
// signals + allocation) — 무거운 연산. KST 07:00 append 외에 결과가 바뀔 일 없으므로
// in-memory TTL 캐시 적용. 기본 6h (append cycle 2배), years 별 독립.
interface PortfolioCacheEntry {
  value: unknown;
  at: number;
}
const portfolioCache = new Map<number, PortfolioCacheEntry>();
const PORTFOLIO_TTL_MS = 6 * 60 * 60 * 1000;

function regimeBandFromValue(value: number): number {
  if (value >= 100) return 5;
  if (value >= 80) return 4;
  if (value >= 60) return 3;
  if (value >= 40) return 2;
  if (value >= 20) return 1;
  return 0;
}

router.get('/summary', async (_req: Request, res: Response) => {
  try {
    const nasdaqHist = await readHistory('yahoo', 'NASDAQ');
    const regimeHist = await readHistory('signal', 'REGIME');
    const regimeLabelHist = await readHistory('signal', 'REGIME_LABEL');
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

      const regimeSlice = (regimeLabelHist.length > 0 ? regimeLabelHist : regimeHist).slice(-Math.min(days, regimeLabelHist.length > 0 ? regimeLabelHist.length : regimeHist.length));
      let regimeChanges = 0;
      for (let i = 1; i < regimeSlice.length; i++) {
        const prev = regimeLabelHist.length > 0
          ? regimeSlice[i - 1].value
          : regimeBandFromValue(regimeSlice[i - 1].value);
        const current = regimeLabelHist.length > 0
          ? regimeSlice[i].value
          : regimeBandFromValue(regimeSlice[i].value);
        if (current !== prev) regimeChanges++;
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

    // 2026-04 개선: TTL 캐시 히트 시 즉시 반환 (연산 0ms).
    const cached = portfolioCache.get(yearsParam);
    if (cached && Date.now() - cached.at < PORTFOLIO_TTL_MS) {
      res.json(cached.value);
      return;
    }

    const assets: Record<string, { key: string; source: string }> = {
      nasdaq: { key: 'NASDAQ', source: 'yahoo' },
      gold: { key: 'GOLD', source: 'yahoo' },
      silver: { key: 'SILVER', source: 'yahoo' },
      copper: { key: 'COPPER', source: 'yahoo' },
      korea: { key: 'KOSPI', source: 'yahoo' },
      emerging: { key: 'EWZ', source: 'yahoo' },
      leverage: { key: 'TQQQ', source: 'yahoo' },  // 3x 나스닥 ETF — leverage 비중 실현 수익률
    };

    // 2026-04 개선: history 읽기 병렬화 (기존 순차 await 으로 16개 file I/O 누적).
    const assetEntries = Object.entries(assets);
    const fredKeys = ['DGS10', 'T10YIE', 'T10Y2Y', 'VIXCLS', 'BAMLH0A0HYM2', 'STLFSI4', 'ICSA', 'UNRATE', 'EFFR'];
    const [
      assetResults,
      fredResults,
      dxyHist,
      wtiHistory,
      hygHistory,
      iefHistory,
      wm2nsHistory,
      dgs30History,
      indproHistory,
      usdkrwHistory,
      fearGreedHistory,
      pcRatioHistory,
      aaiiHistory,
      naaimHistory,
    ] = await Promise.all([
      Promise.all(assetEntries.map(([, { key, source }]) => readHistory(source, key))),
      Promise.all(fredKeys.map((k) => readHistory('fred', k))),
      readHistory('yahoo', 'DXY'),
      readHistory('yahoo', 'WTI'),
      readHistory('yahoo', 'HYG'),
      readHistory('yahoo', 'IEF'),
      readHistory('fred', 'WM2NS'),
      readHistory('fred', 'DGS30'),
      readHistory('fred', 'INDPRO'),
      readHistory('yahoo', 'USDKRW'),
      readHistory('cnn', 'FEAR_GREED'),
      readHistory('sentiment', 'PC_RATIO_10D'),
      readHistory('sentiment', 'AAII_BULL_BEAR_SPREAD'),
      readHistory('sentiment', 'NAAIM_EXPOSURE'),
    ]);
    const histories: Record<string, Array<{ date: string; value: number }>> = {};
    assetEntries.forEach(([name], i) => { histories[name] = assetResults[i]; });
    const fredHistories: Record<string, Array<{ date: string; value: number }>> = {};
    fredKeys.forEach((k, i) => { fredHistories[k] = fredResults[i]; });

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
      const rawDefs: Array<[string, Array<{ date: string; value: number }>, MarketDataPoint['source']]> = [
        ['NASDAQ', nasdaqHist, 'YAHOO'],
        ['GOLD', histories.gold, 'YAHOO'],
        ['SILVER', histories.silver, 'YAHOO'],
        ['COPPER', histories.copper, 'YAHOO'],
        ['KOSPI', histories.korea, 'YAHOO'],
        ['WTI', wtiHistory, 'YAHOO'],
        ['USDKRW', usdkrwHistory, 'YAHOO'],
        ['FEAR_GREED', fearGreedHistory, 'CNN'],
        ['PC_RATIO_10D', pcRatioHistory, 'CBOE'],
        ['AAII_BULL_BEAR_SPREAD', aaiiHistory, 'CALC'],
        ['NAAIM_EXPOSURE', naaimHistory, 'CALC'],
      ];
      for (const [key, hist, source] of rawDefs) {
        const value = latestBefore(hist, prevDate);
        if (value !== null) raw[key] = { code: key, value, date: prevDate, source };
      }

      const derived: Record<string, DerivedIndicator> = await recomputeFullDerivedForDate(
        prevDate,
        raw,
        nasdaqHist,
        {},
        {
          todayIso: '',
          kospiHistory: histories.korea,
          hygHistory,
          iefHistory,
          m2Wm2nsHistory: wm2nsHistory,
          dgs30History,
          dxyHistory: dxyHist,
          wtiHistory,
          indproHistory,
          usdkrwHistory,
        },
      );

      const effectiveInputs = mergeEffectiveManualInputs(
        DEFAULT_PROFILE.manualInputs,
        inferAutoManualInputsFromState({
          raw,
          derived,
          effrHistory: fredHistories.EFFR.filter((p) => p.date <= prevDate).slice(-60).reverse(),
          t10y2yHistory: fredHistories.T10Y2Y.filter((p) => p.date <= prevDate).slice(-10).reverse(),
          icsaHistory: fredHistories.ICSA.filter((p) => p.date <= prevDate).slice(-10).reverse(),
          goldHistory: histories.gold.filter((p) => p.date <= prevDate).slice(-60).reverse(),
          dxyHistory: dxyHist.filter((p) => p.date <= prevDate).slice(-60).reverse(),
        }),
        DEFAULT_PROFILE.manualInputs,
      );
      const effectiveProfile = { ...DEFAULT_PROFILE, manualInputs: effectiveInputs };
      const regime = classifyRegime({ raw, derived, manualInputs: effectiveInputs });
      const signals = computeSignals(raw, derived, regime, effectiveProfile);
      const allocation = computeAllocation(regime.regime, regime.score, signals, derived, raw, 'long', undefined, effectiveProfile);

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

    const payload = {
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
    };
    portfolioCache.set(yearsParam, { value: payload, at: Date.now() });
    res.json(payload);
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Portfolio backtest failed' });
  }
});

export default router;
