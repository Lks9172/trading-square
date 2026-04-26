import axios from 'axios';
import { fetchAAIIBullBear } from '../collectors/sentiment';

jest.mock('axios');

const mockedAxios = axios as jest.Mocked<typeof axios>;

describe('fetchAAIIBullBear', () => {
  beforeEach(() => {
    mockedAxios.get.mockReset();
  });

  it('derives spread from bullish minus bearish even when another percentage appears later in the paragraph', async () => {
    mockedAxios.get.mockResolvedValue({
      data:
        '<rss><channel><item><title><![CDATA[AAII Sentiment Survey: Pessimism Retreats]]></title>' +
        '<link>https://insights.aaii.com/p/aaii-sentiment-survey-pessimism-retreats</link>' +
        '<pubDate>Sat, 11 Apr 2026 15:30:49 GMT</pubDate>' +
        '<content:encoded><![CDATA[' +
        '<p>Bullish: 35.7%</p>' +
        '<p>Neutral: 21.3%</p>' +
        '<p>Bearish: 43.0%</p>' +
        '<p>The bull-bear spread (bullish minus bearish sentiment) increased 10.6 percentage points to –7.2%.' +
        ' The bull-bear spread is below its historical average of 6.5% for the ninth consecutive week.</p>' +
        ']]></content:encoded></item></channel></rss>',
    } as never);

    const point = await fetchAAIIBullBear();

    expect(point.value).toBeCloseTo(-7.3, 1);
    expect(point.extra?.bull).toBeCloseTo(35.7, 1);
    expect(point.extra?.bear).toBeCloseTo(43.0, 1);
    expect(point.extra?.reportedSpread).toBeCloseTo(-7.2, 1);
  });
});
