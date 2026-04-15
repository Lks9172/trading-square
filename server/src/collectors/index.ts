import { fetchAllFred } from './fred';
import { fetchAllYahoo } from './yahoo';
import { fetchFearAndGreed } from './cnn';
import { fetchAllSentiment } from './sentiment';
import { MarketDataPoint } from '../types/indicators';

export async function collectAll(fredApiKey: string): Promise<Record<string, MarketDataPoint>> {
  const [fred, yahoo, fng, sentiment] = await Promise.allSettled([
    fetchAllFred(fredApiKey),
    fetchAllYahoo(),
    fetchFearAndGreed(),
    fetchAllSentiment(),
  ]);

  const result: Record<string, MarketDataPoint> = {};

  if (fred.status === 'fulfilled') Object.assign(result, fred.value);
  if (yahoo.status === 'fulfilled') Object.assign(result, yahoo.value);
  if (fng.status === 'fulfilled' && fng.value) result.FEAR_GREED = fng.value;
  if (sentiment.status === 'fulfilled') Object.assign(result, sentiment.value);

  return result;
}
