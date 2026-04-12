import axios from 'axios';
import { MarketDataPoint } from '../types/indicators';

const CNN_FNG_URL = 'https://production.dataviz.cnn.io/index/fearandgreed/graphdata';

interface FearGreedResponse {
  fear_and_greed: { score: number; rating: string; timestamp: string };
  fear_and_greed_historical: {
    data: Array<{ x: number; y: number; rating: string }>;
  };
}

export async function fetchFearAndGreed(): Promise<MarketDataPoint | null> {
  try {
    const { data } = await axios.get<FearGreedResponse>(CNN_FNG_URL, {
      headers: { 'User-Agent': 'Mozilla/5.0' },
      timeout: 10000,
    });

    const fg = data.fear_and_greed;
    if (!fg) return null;

    return {
      code: 'FEAR_GREED',
      value: Math.round(fg.score),
      date: new Date(fg.timestamp).toISOString().split('T')[0],
      source: 'CNN',
    };
  } catch {
    return null;
  }
}
