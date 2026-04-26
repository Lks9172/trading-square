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

      // 20차 노션 A2: Sharpe/CAGR/Calmar 메트릭 추가 (PortfolioVisualizer 정합).
      const dailyReturns: number[] = [];
      for (let i = 1; i < slice.length; i++) {
        const prev = slice[i - 1].value;
        const curr = slice[i].value;
        if (prev > 0) dailyReturns.push((curr - prev) / prev);
      }
      const meanRet = dailyReturns.length > 0 ? dailyReturns.reduce((a, b) => a + b, 0) / dailyReturns.length : 0;
      const variance = dailyReturns.length > 0
        ? dailyReturns.reduce((s, r) => s + Math.pow(r - meanRet, 2), 0) / dailyReturns.length
        : 0;
      const std = Math.sqrt(variance);
      const annualized = meanRet * 252;
      const annualStd = std * Math.sqrt(252);
      const sharpe = annualStd > 0 ? annualized / annualStd : 0;
      const yearsForCagr = slice.length / 252;
      const cagr = yearsForCagr > 0
        ? (Math.pow(1 + returnPct / 100, 1 / yearsForCagr) - 1) * 100
        : returnPct;
      const calmar = maxDrawdown < 0 ? cagr / Math.abs(maxDrawdown) : 0;

      return {
        label,
        return_pct: parseFloat(returnPct.toFixed(2)),
        max_drawdown: parseFloat(maxDrawdown.toFixed(2)),
        regime_changes: regimeChanges,
        data_points: slice.length,
        // 20차: PortfolioVisualizer 정합 메트릭
        sharpe: parseFloat(sharpe.toFixed(2)),
        cagr_pct: parseFloat(cagr.toFixed(2)),
        calmar: parseFloat(calmar.toFixed(2)),
        annual_volatility_pct: parseFloat((annualStd * 100).toFixed(2)),
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

// 22차 P1#1: 사용자 plan walk-forward 백테스트.
// InvestmentPlan 의 horizon / leverageMaxPct / monthlyDCA_KRW / stopLossPct / profitTakeTargetPct
// 을 입력으로 받아, 과거 N년 NASDAQ 일별 데이터에 룰 적용해 가상 포트폴리오 시뮬레이션.
//
// 단순화 가정:
// - 단일 자산 NASDAQ 만 시뮬레이션 (멀티자산은 future)
// - DCA: 매월 첫 영업일 monthlyDCA_KRW 만큼 매수
// - stop-loss: 평균진입가 -stopLossPct% 이탈 시 보유 50% 매도
// - take-profit: 평균진입가 +profitTakeTargetPct% 도달 시 보유 50% 매도 (1회)
// - 시작 자본: totalCapitalKRW (없으면 10_000_000 default)
//
// 라이브 NASDAQ 가격 vs 가상 포트폴리오 가치 시계열 + Sharpe/MDD/CAGR 반환.
router.get('/user-plan', async (req: Request, res: Response) => {
  try {
    const yearsParam = parseInt(String(req.query.years || '3'), 10);
    const years = Math.min(Math.max(1, yearsParam), 5);
    const days = years * 252;
    const { readInvestmentPlan } = await import('../services/investment-plan');
    const plan = await readInvestmentPlan();
    const initialCapital = plan.totalCapitalKRW ?? 10_000_000;
    const monthlyDCA = plan.monthlyDCA_KRW ?? 0;
    const stopLossPct = plan.stopLossPct ?? 15;
    const takePct = plan.profitTakeTargetPct ?? 25;
    const ndaqHist = await (await import('../collectors/yahoo')).fetchYahooHistory('^IXIC', days + 30);
    if (ndaqHist.length < days * 0.7) {
      res.status(503).json({ error: 'Insufficient NASDAQ history' });
      return;
    }
    const slice = ndaqHist.slice(-days);
    let cash = initialCapital;
    let units = 0;          // NASDAQ 가상 보유 단위 (KRW 환산 단순화 — close 가 USD 지수지만 비례 가상화)
    let avgEntry = 0;
    let totalCost = 0;
    let takeProfitDone = false;
    let stopLossDone = false;
    const equityCurve: Array<{ date: string; value: number }> = [];
    let lastMonth = '';
    for (let i = 0; i < slice.length; i++) {
      const { date, close } = slice[i];
      const month = date.slice(0, 7);
      // 1) DCA 매월 첫 영업일
      if (month !== lastMonth && cash >= monthlyDCA && monthlyDCA > 0) {
        const buyAmt = Math.min(cash, monthlyDCA);
        const newUnits = buyAmt / close;
        totalCost += buyAmt;
        units += newUnits;
        avgEntry = units > 0 ? totalCost / units : 0;
        cash -= buyAmt;
        lastMonth = month;
      }
      // 2) stop-loss
      if (units > 0 && avgEntry > 0 && !stopLossDone) {
        if (close < avgEntry * (1 - stopLossPct / 100)) {
          const sellUnits = units * 0.5;
          cash += sellUnits * close;
          units -= sellUnits;
          stopLossDone = true;
        }
      }
      // 3) take-profit
      if (units > 0 && avgEntry > 0 && !takeProfitDone) {
        if (close >= avgEntry * (1 + takePct / 100)) {
          const sellUnits = units * 0.5;
          cash += sellUnits * close;
          units -= sellUnits;
          takeProfitDone = true;
        }
      }
      const equity = cash + units * close;
      equityCurve.push({ date, value: parseFloat(equity.toFixed(0)) });
    }
    // 메트릭 산출
    const startVal = equityCurve[0]?.value ?? initialCapital;
    const endVal = equityCurve[equityCurve.length - 1]?.value ?? initialCapital;
    const returnPct = ((endVal - startVal) / startVal) * 100;
    let peak = startVal;
    let maxDD = 0;
    const dailyRets: number[] = [];
    for (let i = 0; i < equityCurve.length; i++) {
      const v = equityCurve[i].value;
      if (v > peak) peak = v;
      const dd = ((v - peak) / peak) * 100;
      if (dd < maxDD) maxDD = dd;
      if (i > 0 && equityCurve[i - 1].value > 0) {
        dailyRets.push((v - equityCurve[i - 1].value) / equityCurve[i - 1].value);
      }
    }
    const meanRet = dailyRets.length > 0 ? dailyRets.reduce((a, b) => a + b, 0) / dailyRets.length : 0;
    const variance = dailyRets.length > 0
      ? dailyRets.reduce((s, r) => s + Math.pow(r - meanRet, 2), 0) / dailyRets.length : 0;
    const std = Math.sqrt(variance);
    const sharpe = std > 0 ? (meanRet * 252) / (std * Math.sqrt(252)) : 0;
    const cagr = years > 0 ? (Math.pow(1 + returnPct / 100, 1 / years) - 1) * 100 : returnPct;
    res.json({
      years,
      plan: {
        horizon: plan.horizon,
        initialCapitalKRW: initialCapital,
        monthlyDCA_KRW: monthlyDCA,
        stopLossPct,
        profitTakeTargetPct: takePct,
        leverageMaxPct: plan.leverageMaxPct,
      },
      equityCurve: equityCurve.filter((_, i) => i % Math.max(1, Math.floor(equityCurve.length / 200)) === 0),
      metrics: {
        return_pct: parseFloat(returnPct.toFixed(2)),
        max_drawdown_pct: parseFloat(maxDD.toFixed(2)),
        sharpe: parseFloat(sharpe.toFixed(2)),
        cagr_pct: parseFloat(cagr.toFixed(2)),
        annual_volatility_pct: parseFloat((std * Math.sqrt(252) * 100).toFixed(2)),
        final_value_krw: parseFloat(endVal.toFixed(0)),
        stop_loss_triggered: stopLossDone,
        take_profit_triggered: takeProfitDone,
      },
    });
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'User plan backtest failed' });
  }
});

export default router;
