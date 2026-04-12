import { MarketDataPoint, DerivedIndicator } from '../types/indicators';
import { fetchYahooHistory } from '../collectors/yahoo';

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
    const nasdaqHistory = await fetchYahooHistory('^IXIC', 250);
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

  return d;
}
