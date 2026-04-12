import { fetchAllFred } from './fred';
import { fetchAllYahoo } from './yahoo';
import { fetchFearAndGreed } from './cnn';
import { MarketDataPoint } from '../types/indicators';

export async function collectAll(fredApiKey: string): Promise<Record<string, MarketDataPoint>> {
  const [fred, yahoo, fng] = await Promise.allSettled([
    fetchAllFred(fredApiKey),
    fetchAllYahoo(),
    fetchFearAndGreed(),
  ]);

  const result: Record<string, MarketDataPoint> = {};

  if (fred.status === 'fulfilled') Object.assign(result, fred.value);
  if (yahoo.status === 'fulfilled') Object.assign(result, yahoo.value);
  if (fng.status === 'fulfilled' && fng.value) result.FEAR_GREED = fng.value;

  return result;
}
