import { shouldRefreshFredSeries } from '../collectors/fred';
import { shouldRefreshSentimentMetric, SentimentPoint } from '../collectors/sentiment';

describe('collector refresh cadence', () => {
  it('skips daily FRED live refresh when cached date already matches probe date', () => {
    expect(shouldRefreshFredSeries('EFFR', '2026-04-16', '2026-04-16')).toBe(false);
    expect(shouldRefreshFredSeries('EFFR', '2026-04-15', '2026-04-16')).toBe(true);
  });

  it('keeps weekly sentiment metrics on cache until next expected update window', () => {
    const recentWeekly: SentimentPoint = {
      value: -7.3,
      asOf: new Date(Date.now() - 3 * 86400000).toISOString().slice(0, 10),
      source: 'AAII:SUBSTACK',
    };
    const staleWeekly: SentimentPoint = {
      value: 69.38,
      asOf: new Date(Date.now() - 10 * 86400000).toISOString().slice(0, 10),
      source: 'NAAIM',
    };

    expect(shouldRefreshSentimentMetric('AAII_BULL_BEAR_SPREAD', recentWeekly)).toBe(false);
    expect(shouldRefreshSentimentMetric('NAAIM_EXPOSURE', staleWeekly)).toBe(true);
  });
});
