import { computeDisparityStreak, computeHistoryYoY } from '../engines/derived';

describe('computeDisparityStreak', () => {
  it('uses each day moving average instead of today average for streaks', () => {
    const newestFirst = [140, 135, 130, 125, 120, 115];

    expect(computeDisparityStreak(newestFirst, 3, 3, 'over')).toBe(4);
  });
});

describe('computeHistoryYoY', () => {
  it('computes YoY from level-like series history', () => {
    const history = [
      { date: '2025-01-01', value: 100 },
      { date: '2025-12-01', value: 108 },
      { date: '2026-01-01', value: 112 },
    ];

    expect(computeHistoryYoY(history)).toBeCloseTo(12, 5);
  });

  it('rejects legacy growth-rate history when a level floor is required', () => {
    const legacyRateHistory = [
      { date: '2025-01-01', value: 0.7 },
      { date: '2025-12-01', value: 0.8 },
      { date: '2026-01-01', value: 0.9 },
    ];

    expect(computeHistoryYoY(legacyRateHistory, 20)).toBeNull();
  });

  it('rejects stale history when the latest point is too old', () => {
    const staleHistory = [
      { date: '2022-09-01', value: 100 },
      { date: '2023-09-01', value: 110 },
    ];

    expect(computeHistoryYoY(staleHistory, 20, 400)).toBeNull();
  });
});
