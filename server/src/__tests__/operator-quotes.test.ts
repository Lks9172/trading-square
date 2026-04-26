import { OPERATOR_QUOTES_KO, getDailyQuote, getDailyQuoteIndex } from '../services/operator-quotes';

describe('operator-quotes', () => {
  it('has exactly 9 quotes', () => {
    expect(OPERATOR_QUOTES_KO.length).toBe(9);
  });

  it('rotates index over 9 days', () => {
    const day0 = new Date('2026-01-01T00:00:00Z');
    const day9 = new Date('2026-01-10T00:00:00Z');
    expect(getDailyQuoteIndex(day0)).toBe(getDailyQuoteIndex(day9));
  });

  it('returns same quote within same UTC day', () => {
    const a = new Date('2026-01-15T00:01:00Z');
    const b = new Date('2026-01-15T23:59:00Z');
    expect(getDailyQuoteIndex(a)).toBe(getDailyQuoteIndex(b));
  });

  it('exposes both short and full text fields', () => {
    const q = getDailyQuote();
    expect(q.short.length).toBeGreaterThan(0);
    expect(q.full.length).toBeGreaterThan(q.short.length - 1);
    expect(q.index).toBeGreaterThanOrEqual(0);
    expect(q.index).toBeLessThan(9);
  });
});
