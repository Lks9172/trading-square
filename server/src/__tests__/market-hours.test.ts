import { isUSMarketOpen, isKRMarketOpen, getUSPriceSource } from '../utils/market-hours';

describe('market-hours', () => {
  it('getUSPriceSource returns spot or futures', () => {
    const source = getUSPriceSource();
    expect(['spot', 'futures']).toContain(source);
  });

  it('isUSMarketOpen returns boolean', () => {
    expect(typeof isUSMarketOpen()).toBe('boolean');
  });

  it('isKRMarketOpen returns boolean', () => {
    expect(typeof isKRMarketOpen()).toBe('boolean');
  });
});
