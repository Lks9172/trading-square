import axios from 'axios';

const USER_AGENT =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

interface InsiderTrade {
  date: string;
  ticker: string;
  insiderName: string;
  type: 'buy' | 'sell';
  shares: number;
  value: number;
}

interface SmartMoneySnapshot {
  insiderBuyRatio: number;
  recentInsiderBuys: number;
  recentInsiderSells: number;
  lastUpdated: string;
}

export interface SmartMoneyData extends SmartMoneySnapshot {
  score: number;
  dataromaBuyCount?: number;
  dataromaSellCount?: number;
  dataromaScore?: number;
  /** Add 액션 평균 % (기존 포지션 대비 증가율) */
  dataromaAvgAddPct?: number;
  /** Reduce 액션 평균 % (절댓값, 기존 포지션 대비 감소율) */
  dataromaAvgReducePct?: number;
  /** 포트폴리오 비중 기여 순합계 (매수 +, 매도 -) */
  dataromaNetPortfolioFlow?: number;
}

export function scoreSmartMoney(insiderBuyRatio: number): number {
  if (insiderBuyRatio >= 65) return 2;
  if (insiderBuyRatio >= 55) return 1;
  if (insiderBuyRatio >= 45) return 0;
  if (insiderBuyRatio >= 35) return -1;
  return -2;
}

export async function fetchInsiderSummary(): Promise<SmartMoneyData | null> {
  try {
    const { data: html } = await axios.get(
      'http://openinsider.com/screener?s=&o=&pl=&ph=&ll=&lh=&fd=7&fdr=&td=0&tdr=&feession=at&cession=at&xp=1&vl=100000&vh=&ocl=&och=&session=ic1&cnt=100&page=1',
      { headers: { 'User-Agent': USER_AGENT }, timeout: 15000 }
    );

    const buyMatches = (html.match(/Purchase/gi) || []).length;
    const sellMatches = (html.match(/Sale/gi) || []).length;
    const total = buyMatches + sellMatches;

    const baseRatio = total > 0 ? ((buyMatches / total) * 100) : 50;
    const dataroma = await fetchDataromaActivitySummary().catch(() => null);
    const combinedScoreBase = scoreSmartMoney(baseRatio);
    const combinedScore = dataroma
      ? Math.max(-2, Math.min(2, Math.round((combinedScoreBase + dataroma.score) / 2)))
      : combinedScoreBase;

    return {
      insiderBuyRatio: total > 0 ? parseFloat(((buyMatches / total) * 100).toFixed(1)) : 50,
      recentInsiderBuys: buyMatches,
      recentInsiderSells: sellMatches,
      score: combinedScore,
      dataromaBuyCount: dataroma?.buyCount ?? 0,
      dataromaSellCount: dataroma?.sellCount ?? 0,
      dataromaScore: dataroma?.score ?? 0,
      dataromaAvgAddPct: dataroma?.avgAddPct ?? 0,
      dataromaAvgReducePct: dataroma?.avgReducePct ?? 0,
      dataromaNetPortfolioFlow: dataroma?.netPortfolioFlow ?? 0,
      lastUpdated: new Date().toISOString().split('T')[0],
    };
  } catch {
    return null;
  }
}

interface DataromaActivity {
  buyCount: number;
  sellCount: number;
  avgAddPct: number;
  avgReducePct: number;
  netPortfolioFlow: number;
  score: number;
}

/**
 * Dataroma /m/allact.php 페이지의 superinvestors 활동을 13F 변화량 관점에서 집계.
 *
 * HTML 예시:
 *   <a class="buy" href="/m/activity.php?sym=CP&typ=a">CP</a>
 *   <div>Canadian Pacific Kansas City<br/>Add 269.87%<br/>Change to portfolio: 2.42%</div>
 *
 * - class="buy"/"sell" 로 액션 방향
 * - Add NN% / Reduce -NN% / Sell -100% 에서 포지션 증감률
 * - "Change to portfolio: N%" 에서 포트폴리오 비중 변화
 */
async function fetchDataromaActivitySummary(): Promise<DataromaActivity> {
  const { data: html } = await axios.get(
    'https://www.dataroma.com/m/allact.php?typ=a',
    { headers: { 'User-Agent': USER_AGENT }, timeout: 15000 }
  );

  const blocks = html.split(/<td class="sym">/).slice(1);

  let buyCount = 0;
  let sellCount = 0;
  const addPcts: number[] = [];
  const reducePcts: number[] = [];
  let netPortfolioFlow = 0;

  for (const block of blocks) {
    const classMatch = block.match(/class="(buy|sell)"/);
    if (!classMatch) continue;
    const isBuy = classMatch[1] === 'buy';

    if (isBuy) buyCount += 1;
    else sellCount += 1;

    const actionMatch = block.match(/<br\s*\/?>\s*(Buy|Sell|Add\s+-?[\d.]+%|Reduce\s+-?[\d.]+%|Sell\s+-?[\d.]+%)\s*<br/);
    if (actionMatch) {
      const action = actionMatch[1];
      const pctMatch = action.match(/(-?[\d.]+)%/);
      if (pctMatch) {
        const pct = Math.abs(parseFloat(pctMatch[1]));
        if (/^Add/i.test(action)) addPcts.push(pct);
        else if (/^Reduce/i.test(action)) reducePcts.push(pct);
        else if (/^Sell/i.test(action)) reducePcts.push(pct);
      }
    }

    const changeMatch = block.match(/Change to portfolio:\s*(-?[\d.]+)%/);
    if (changeMatch) {
      const change = Math.abs(parseFloat(changeMatch[1]));
      netPortfolioFlow += isBuy ? change : -change;
    }
  }

  const total = buyCount + sellCount;
  const ratio = total > 0 ? (buyCount / total) * 100 : 50;
  const avg = (arr: number[]) => (arr.length > 0 ? arr.reduce((s, v) => s + v, 0) / arr.length : 0);
  const avgAddPct = parseFloat(avg(addPcts).toFixed(2));
  const avgReducePct = parseFloat(avg(reducePcts).toFixed(2));
  const netFlow = parseFloat(netPortfolioFlow.toFixed(2));

  let score = scoreSmartMoney(ratio);
  // 포트폴리오 기여 net flow가 강하게 기울어지면 score 한 단계 보정 (max ±2)
  if (netFlow >= 20) score = Math.min(2, score + 1);
  else if (netFlow <= -20) score = Math.max(-2, score - 1);

  return {
    buyCount,
    sellCount,
    avgAddPct,
    avgReducePct,
    netPortfolioFlow: netFlow,
    score,
  };
}

export async function fetchDataromaSuperinvestors(): Promise<Record<string, string> | null> {
  try {
    const { data: html } = await axios.get(
      'https://www.dataroma.com/m/home.php',
      { headers: { 'User-Agent': USER_AGENT }, timeout: 15000 }
    );

    const nameMatches = html.match(/<a[^>]*href="\/m\/holdings\.php\?m=[^"]*"[^>]*>([^<]+)<\/a>/g) || [];
    const result: Record<string, string> = {};
    for (const match of nameMatches.slice(0, 10)) {
      const name = match.replace(/<[^>]+>/g, '').trim();
      if (name) result[name] = 'tracked';
    }

    return Object.keys(result).length > 0 ? result : null;
  } catch {
    return null;
  }
}
