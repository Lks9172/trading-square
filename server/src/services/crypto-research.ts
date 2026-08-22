import { fetchYahooHistory, fetchYahooQuote } from '../collectors/yahoo';
import { fetchCryptoCoinMarkets, fetchCryptoGlobalMarket } from '../collectors/crypto-market';
import { fetchCryptoEtfFlowHistory } from '../collectors/crypto-etf';
import { fetchCryptoChainMetrics, fetchCryptoCoinDetail } from '../collectors/crypto-fundamentals';
import { fetchStablecoinMcapHistory } from '../collectors/stablecoin';
import { DEFAULT_PROFILE, getSnapshot } from '../state/cache';
import { analyzeBottomPattern } from '../engines/bottom/pattern';
import { CryptoAssetDefinition, CryptoBottomSignal, CryptoBottomUpView, CryptoBuyScore, CryptoExecutionBridge, CryptoFlowView, CryptoMacroView, CryptoMarketRegimeView, CryptoMarketStats, CryptoNarrativeView, CryptoPositionSizingPlan, CryptoResearchResponse, CryptoScenarioView, CryptoVerdictView } from '../types/crypto';
import { BottomMetricStatus, BottomSignalChartMarker, BottomSignalChartPoint, BottomSignalMetric, DeepBottomSignal } from '../types/indicators';

const CRYPTO_ASSETS: CryptoAssetDefinition[] = [
  {
    symbol: 'BTC',
    yahooSymbol: 'BTC-USD',
    coingeckoId: 'bitcoin',
    llamaChainSlug: 'bitcoin',
    name: 'Bitcoin',
    category: '디지털 금 / 거시 민감 자산',
    narrativeTheme: '디지털 금',
    linkedAsset: 'GOLD',
    foundationalScore: 88,
    networkScore: 82,
    tokenomicsScore: 90,
    adoptionScore: 89,
    macroSensitivity: ['유동성', '달러', '실질금리', 'ETF 자금'],
    strengths: ['가장 강한 기관 수요 프록시', '희소성 서사가 가장 선명', '리스크오프/유동성 완화 국면에서 중심축'],
    risks: ['단기 급등 시 추격 과열이 빠르게 붙음', '달러 강세·실질금리 상승엔 역풍', '알트 강세장에선 상대 수익률이 밀릴 수 있음'],
  },
  {
    symbol: 'ETH',
    yahooSymbol: 'ETH-USD',
    coingeckoId: 'ethereum',
    llamaChainSlug: 'ethereum',
    name: 'Ethereum',
    category: '스마트컨트랙트 기준 자산',
    narrativeTheme: 'L1 / 수수료 / 스테이킹',
    linkedAsset: 'NASDAQ',
    foundationalScore: 80,
    networkScore: 84,
    tokenomicsScore: 78,
    adoptionScore: 82,
    macroSensitivity: ['유동성', '나스닥 베타', 'ETF 기대', '온체인 활동'],
    strengths: ['스테이킹과 L2 생태계가 구조 수요를 형성', '기관 자금이 들어오면 BTC 다음 순서로 해석되기 쉬움', '수수료/사용량이 살아나면 서사가 강해짐'],
    risks: ['BTC 대비 서사가 약해지는 구간 존재', 'L2 확장과 수수료 구조 변화가 해석을 어렵게 함', '고베타로 과열 시 되돌림도 빠름'],
  },
  {
    symbol: 'SOL',
    yahooSymbol: 'SOL-USD',
    coingeckoId: 'solana',
    llamaChainSlug: 'solana',
    name: 'Solana',
    category: '고베타 L1 성장',
    narrativeTheme: '고성장 체인 / 소비자 앱',
    linkedAsset: 'NASDAQ',
    foundationalScore: 72,
    networkScore: 78,
    tokenomicsScore: 66,
    adoptionScore: 76,
    macroSensitivity: ['유동성', '리스크온', '온체인 활동', '개인투자자 심리'],
    strengths: ['리스크온 국면에서 알트 리더 역할 가능', 'DEX/소비자앱 활동이 붙으면 내러티브가 강함', 'ETH보다 고베타라 모멘텀 확장이 빠름'],
    risks: ['과열과 되돌림이 매우 빠름', '개인투자자 심리 악화 시 타격이 큼', '네트워크/집중도 이슈가 재부각될 수 있음'],
  },
  {
    symbol: 'XRP',
    yahooSymbol: 'XRP-USD',
    coingeckoId: 'ripple',
    llamaChainSlug: 'ripple',
    name: 'XRP',
    category: '결제 / 규제 이벤트 자산',
    narrativeTheme: '결제·규제',
    linkedAsset: 'CASH',
    foundationalScore: 60,
    networkScore: 58,
    tokenomicsScore: 62,
    adoptionScore: 57,
    macroSensitivity: ['규제 이슈', '거래소 유동성', '개인투자자 관심'],
    strengths: ['규제 이벤트 시 서사가 강해짐', '결제/송금 프레임으로 대중 인지도가 높음', '단기 순환매에 자주 포함됨'],
    risks: ['구조적 펀더멘털보다 이벤트 의존도가 큼', '실체적 네트워크 성장 해석이 약할 수 있음', '급등 이후 되돌림이 빠름'],
  },
  {
    symbol: 'BNB',
    yahooSymbol: 'BNB-USD',
    coingeckoId: 'binancecoin',
    llamaChainSlug: 'bsc',
    name: 'BNB',
    category: '거래소 / 생태계 토큰',
    narrativeTheme: '거래소 생태계',
    linkedAsset: 'NASDAQ',
    foundationalScore: 68,
    networkScore: 71,
    tokenomicsScore: 74,
    adoptionScore: 69,
    macroSensitivity: ['거래량', '거래소 건전성', '리스크온', '규제'],
    strengths: ['거래소/체인 생태계와 직접 연결', '소각/유틸리티 서사가 비교적 명확', '알트 시장 활황 시 수혜 가능'],
    risks: ['거래소 규제 이슈에 직접 노출', '내러티브가 거래량에 크게 의존', '알트 시장 약세 시 함께 약해지기 쉬움'],
  },
];

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

function round(value: number) {
  return Math.round(value);
}

function actionFromScore(score: number): CryptoBuyScore['action'] {
  if (score >= 80) return 'STRONG BUY';
  if (score >= 70) return 'BUY';
  if (score >= 55) return 'HOLD';
  if (score >= 40) return 'REDUCE';
  return 'SELL';
}

function actionLabel(action: CryptoBuyScore['action']) {
  switch (action) {
    case 'STRONG BUY': return '적극 매수';
    case 'BUY': return '매수 가능';
    case 'HOLD': return '보유/관찰';
    case 'REDUCE': return '축소';
    case 'SELL': return '매도/회피';
  }
}

function mean(values: number[]) {
  if (!values.length) return null;
  return values.reduce((sum, v) => sum + v, 0) / values.length;
}

function std(values: number[]) {
  if (values.length < 2) return null;
  const m = mean(values);
  if (m === null) return null;
  const variance = values.reduce((sum, v) => sum + (v - m) ** 2, 0) / values.length;
  return Math.sqrt(variance);
}

function pctChange(current: number, prev: number) {
  if (!prev) return null;
  return Number((((current - prev) / prev) * 100).toFixed(1));
}

function getDefinition(symbol: string) {
  const found = CRYPTO_ASSETS.find((asset) => asset.symbol === symbol.toUpperCase());
  if (!found) throw new Error(`crypto symbol not found: ${symbol}`);
  return found;
}

type SharedCryptoContext = {
  snapshot: Awaited<ReturnType<typeof getSnapshot>>;
  histories: Record<string, Awaited<ReturnType<typeof fetchYahooHistory>>>;
  quotes: Record<string, Awaited<ReturnType<typeof fetchYahooQuote>>>;
  globalMarket: Awaited<ReturnType<typeof fetchCryptoGlobalMarket>>;
  coinMarkets: Awaited<ReturnType<typeof fetchCryptoCoinMarkets>>;
  coinDetails: Record<string, Awaited<ReturnType<typeof fetchCryptoCoinDetail>>>;
  chainMetrics: Record<string, Awaited<ReturnType<typeof fetchCryptoChainMetrics>>>;
  stablecoinHistory: Awaited<ReturnType<typeof fetchStablecoinMcapHistory>>;
  btcEtfHistory: Awaited<ReturnType<typeof fetchCryptoEtfFlowHistory>>;
  ethEtfHistory: Awaited<ReturnType<typeof fetchCryptoEtfFlowHistory>>;
};

function narrativeFromHeat(heatScore: number): CryptoNarrativeView['stage'] {
  if (heatScore >= 70) return 'OVERHEATED';
  if (heatScore >= 40) return 'MID';
  return 'EARLY';
}

function buildMarketStats(history: Awaited<ReturnType<typeof fetchYahooHistory>>, price: number | null, asOf: string | null): CryptoMarketStats {
  const closes = history.map((item) => item.close);
  const volumes = history.map((item) => item.volume ?? 0);
  const latest = closes.length ? closes[closes.length - 1] : null;
  const ret7 = closes.length > 7 ? pctChange(closes[closes.length - 1] ?? 0, closes[closes.length - 8] ?? 0) : null;
  const ret30 = closes.length > 30 ? pctChange(closes[closes.length - 1] ?? 0, closes[closes.length - 31] ?? 0) : null;
  const ret90 = closes.length > 90 ? pctChange(closes[closes.length - 1] ?? 0, closes[closes.length - 91] ?? 0) : null;
  const dailyReturns = closes.slice(1).map((close, idx) => ((close - closes[idx]) / closes[idx]) * 100).slice(-30);
  const vol30 = std(dailyReturns);
  const latest30Vol = mean(volumes.slice(-30).filter((item) => item > 0));
  const prev30Vol = mean(volumes.slice(-60, -30).filter((item) => item > 0));
  const volumeTrend30d = latest30Vol !== null && prev30Vol !== null && prev30Vol > 0 ? Number((((latest30Vol - prev30Vol) / prev30Vol) * 100).toFixed(1)) : null;
  const high52 = closes.length ? Math.max(...closes) : null;
  const low52 = closes.length ? Math.min(...closes) : null;
  const distanceFrom52wHigh = latest !== null && high52 ? Number((((latest - high52) / high52) * 100).toFixed(1)) : null;
  const distanceFrom52wLow = latest !== null && low52 ? Number((((latest - low52) / low52) * 100).toFixed(1)) : null;
  return {
    asOf,
    price,
    return7d: ret7,
    return30d: ret30,
    return90d: ret90,
    volumeTrend30d,
    volatility30d: vol30 === null ? null : Number(vol30.toFixed(1)),
    distanceFrom52wHigh,
    distanceFrom52wLow,
  };
}

function buildMacroView(
  definition: CryptoAssetDefinition,
  snapshot: Awaited<ReturnType<typeof getSnapshot>>,
  market: CryptoMarketStats,
): CryptoMacroView {
  const liquidity = snapshot.derived.LIQUIDITY_DIRECTION?.value ?? null;
  const dxyTrend = snapshot.derived.DXY_TREND?.value ?? null;
  const regime = snapshot.regime.regime;
  const riskOnScore = ['RISK_ON', 'NEUTRAL'].includes(regime) ? 75 : ['CORRECTION', 'CAUTION'].includes(regime) ? 45 : 30;
  const liquidityScore = liquidity === null ? null : liquidity > 0 ? 72 : liquidity < 0 ? 35 : 55;
  const dollarScore = dxyTrend === null ? null : dxyTrend < -0.5 ? 72 : dxyTrend > 0.5 ? 35 : 55;
  const drivers: string[] = [];
  if (liquidity !== null) drivers.push(liquidity > 0 ? '유동성 방향이 우호적' : liquidity < 0 ? '유동성 방향이 역풍' : '유동성은 중립');
  if (dxyTrend !== null) drivers.push(dxyTrend < -0.5 ? '달러 약세가 크립토에 우호적' : dxyTrend > 0.5 ? '달러 강세가 크립토에 부담' : '달러 흐름은 중립');
  if (definition.linkedAsset === 'GOLD') drivers.push('BTC는 디지털 금 프레임으로 금과 함께 해석');
  if (definition.linkedAsset === 'NASDAQ') drivers.push('알트/L1 성격이 강해 나스닥 위험선호와 함께 해석');
  if (market.return30d !== null && market.return30d > 25) drivers.push('최근 30일 상승폭이 커 과열 관리 필요');
  const scoreParts = [liquidityScore, dollarScore, riskOnScore].filter((v): v is number => typeof v === 'number');
  const avg = scoreParts.length ? round(scoreParts.reduce((a, b) => a + b, 0) / scoreParts.length) : 55;
  const stance: CryptoMacroView['stance'] = avg >= 68 ? '우호' : avg >= 50 ? '중립' : '주의';
  const summary = avg >= 68
    ? `${definition.symbol}에 우호적인 거시 조합입니다. 유동성과 달러 흐름이 받쳐주면 상단 테스트가 쉬워집니다.`
    : avg >= 50
      ? `${definition.symbol} 거시 환경은 중립입니다. 종목 고유 서사와 과열 관리를 같이 봐야 합니다.`
      : `${definition.symbol} 거시 배경은 보수적입니다. 좋은 코인이어도 추격보다 현금/분할 접근이 낫습니다.`;
  return { liquidityScore, dollarScore, riskOnScore, stance, summary, drivers: drivers.slice(0, 4) };
}

function buildNarrativeView(definition: CryptoAssetDefinition, market: CryptoMarketStats): CryptoNarrativeView {
  let heat = 28;
  if (market.return7d !== null) heat += clamp(market.return7d * 1.2, -8, 18);
  if (market.return30d !== null) heat += clamp(market.return30d * 0.8, -10, 24);
  if (market.volumeTrend30d !== null) heat += clamp(market.volumeTrend30d * 0.15, -6, 16);
  if (market.distanceFrom52wHigh !== null) heat += market.distanceFrom52wHigh >= -8 ? 14 : market.distanceFrom52wHigh >= -20 ? 8 : 0;
  const heatScore = round(clamp(heat, 0, 100));
  const stage = narrativeFromHeat(heatScore);
  const summary = stage === 'EARLY'
    ? `${definition.narrativeTheme} 서사가 아직 과열 전 단계입니다. 시장 관심이 더 붙을 여지가 남아 있습니다.`
    : stage === 'MID'
      ? `${definition.narrativeTheme} 서사가 확산 중입니다. 방향성은 좋지만 분할 접근이 더 적절합니다.`
      : `${definition.narrativeTheme} 서사가 많이 알려진 구간입니다. 좋은 코인이어도 추격은 신중해야 합니다.`;
  return { theme: definition.narrativeTheme, stage, heatScore, summary };
}

function buildBottomUpView(definition: CryptoAssetDefinition): CryptoBottomUpView {
  const summaryScore = round((definition.networkScore + definition.tokenomicsScore + definition.adoptionScore) / 3);
  const summary = summaryScore >= 78
    ? '네트워크·토크노믹스·채택축이 모두 강한 편입니다.'
    : summaryScore >= 65
      ? '기본 체력은 양호하지만 내러티브/매크로의 영향도 큽니다.'
      : '온체인·채택보다는 이벤트/심리 의존도가 큰 편입니다.';
  return {
    networkScore: definition.networkScore,
    tokenomicsScore: definition.tokenomicsScore,
    adoptionScore: definition.adoptionScore,
    summary,
    strengths: definition.strengths,
    risks: definition.risks,
  };
}

function buildMoatView(definition: CryptoAssetDefinition) {
  const moatType =
    definition.symbol === 'BTC' ? '기관/디지털 금 해자'
      : definition.symbol === 'ETH' ? '정산 레이어·스테이킹 해자'
        : definition.symbol === 'SOL' ? '소비자앱·속도 해자'
          : definition.symbol === 'BNB' ? '거래소·생태계 락인 해자'
            : '규제/결제 네트워크 해자';
  const moatScore =
    definition.symbol === 'BTC' ? 88
      : definition.symbol === 'ETH' ? 82
        : definition.symbol === 'SOL' ? 72
          : definition.symbol === 'BNB' ? 74
            : 60;
  const reasons =
    definition.symbol === 'BTC'
      ? ['ETF/기관 접근성이 가장 강함', '디지털 금 내러티브가 가장 선명', '거시 유동성 축의 중심 자산']
      : definition.symbol === 'ETH'
        ? ['스테이킹과 L2 정산 중심성', '수수료/활동 기반 사용성', '기관 자금 2순위 가능성']
        : definition.symbol === 'SOL'
          ? ['고속 체인/소비자앱 서사', '알트 시즌 시 자금 탄력 큼', 'DEX/앱 활동 확장 가능']
          : definition.symbol === 'BNB'
            ? ['거래소 사용자 기반 락인', '체인·거래소 유틸리티 결합', '소각 서사']
            : ['규제/결제 이벤트 민감', '대중 인지도 높음', '송금/결제 프레임'];
  const summary =
    moatScore >= 80 ? '코인 특유의 해자가 비교적 선명한 편입니다.'
      : moatScore >= 70 ? '해자는 있으나 내러티브와 시장 심리 영향도 큽니다.'
        : '구조적 해자보다 이벤트/심리 의존도가 더 큽니다.';
  return { moatType, moatScore, summary, reasons };
}

function buildSupplyPressureView(definition: CryptoAssetDefinition, detail: Awaited<ReturnType<typeof fetchCryptoCoinDetail>>) {
  const circulating = detail?.circulatingSupply ?? null;
  const supplyDenominator = detail?.maxSupply ?? detail?.totalSupply ?? null;
  const circulatingRatioPct = circulating !== null && supplyDenominator && supplyDenominator > 0
    ? Number(((circulating / supplyDenominator) * 100).toFixed(1))
    : null;
  const fdvPremiumPct = detail?.fdvUsd && detail?.marketCapUsd && detail.marketCapUsd > 0
    ? Number((((detail.fdvUsd - detail.marketCapUsd) / detail.marketCapUsd) * 100).toFixed(1))
    : null;
  const unlockRisk: '낮음' | '보통' | '높음' =
    circulatingRatioPct === null ? '보통'
      : circulatingRatioPct >= 85 ? '낮음'
        : circulatingRatioPct >= 65 ? '보통'
          : '높음';
  const dilutionRisk: '낮음' | '보통' | '높음' =
    fdvPremiumPct === null ? '보통'
      : fdvPremiumPct <= 15 ? '낮음'
        : fdvPremiumPct <= 45 ? '보통'
          : '높음';
  const floatScore = round(
    clamp(
      70
      + (circulatingRatioPct === null ? 0 : (circulatingRatioPct - 70) * 0.6)
      - (fdvPremiumPct === null ? 10 : Math.max(0, fdvPremiumPct - 20) * 0.35),
      10,
      95,
    ),
  );
  const reasons = [
    circulatingRatioPct !== null ? `유통 비율 ${circulatingRatioPct}%` : '유통 비율 데이터 부족',
    fdvPremiumPct !== null ? `FDV 프리미엄 ${fdvPremiumPct >= 0 ? '+' : ''}${fdvPremiumPct}%` : 'FDV 프리미엄 데이터 부족',
    unlockRisk === '높음' ? '향후 공급 부담을 더 강하게 봐야 합니다.' : unlockRisk === '보통' ? '공급 압력을 중립적으로 봅니다.' : '추가 공급 부담은 비교적 제한적입니다.',
  ];
  const summary =
    dilutionRisk === '높음' || unlockRisk === '높음'
      ? '좋은 코인이어도 향후 공급 압력을 같이 경계해야 합니다.'
      : '공급 압력은 비교적 관리 가능한 편입니다.';
  return { unlockRisk, dilutionRisk, floatScore, fdvPremiumPct, circulatingRatioPct, summary, reasons };
}

function buildOnchainView(
  detail: Awaited<ReturnType<typeof fetchCryptoCoinDetail>>,
  chain: Awaited<ReturnType<typeof fetchCryptoChainMetrics>>,
) {
  const developerScore = detail?.developerScore ?? null;
  const communityScore = detail?.communityScore ?? null;
  const tvlTrend = chain?.tvlTrend30dPct ?? null;
  const feesTrend = chain?.feesTrend30dPct ?? null;
  const activityScore = round(clamp(
    50
    + (developerScore ?? 50) * 0.15
    + (communityScore ?? 50) * 0.08
    + (tvlTrend ?? 0) * 0.2
    + (feesTrend ?? 0) * 0.15,
    5,
    95,
  ));
  const reasons = [
    chain?.tvlUsd !== null && chain?.tvlUsd !== undefined ? `TVL $${(chain.tvlUsd / 1_000_000_000).toFixed(2)}B` : 'TVL 데이터 부족',
    chain?.fees30dAvgUsd !== null && chain?.fees30dAvgUsd !== undefined ? `30일 평균 수수료 $${(chain.fees30dAvgUsd / 1_000_000).toFixed(2)}M` : '수수료 데이터 부족',
    developerScore !== null ? `개발자 점수 ${developerScore.toFixed(1)}` : '개발자 점수 데이터 부족',
    communityScore !== null ? `커뮤니티 점수 ${communityScore.toFixed(1)}` : '커뮤니티 점수 데이터 부족',
  ];
  const summary =
    activityScore >= 75
      ? '실사용/개발/생태계 지표가 비교적 강한 편입니다.'
      : activityScore >= 60
        ? '온체인·생태계 지표는 양호하지만 서사 의존도도 남아 있습니다.'
        : '실사용보다 내러티브/가격 흐름 의존도가 더 큰 편입니다.';
  return {
    tvlUsd: chain?.tvlUsd ?? null,
    tvlTrend30dPct: tvlTrend,
    fees30dAvgUsd: chain?.fees30dAvgUsd ?? null,
    feesTrend30dPct: feesTrend,
    developerScore,
    communityScore,
    activityScore,
    summary,
    reasons,
  };
}

function buildFlowView(
  definition: CryptoAssetDefinition,
  snapshot: Awaited<ReturnType<typeof getSnapshot>>,
  market: CryptoMarketStats,
  histories: Record<string, Awaited<ReturnType<typeof fetchYahooHistory>>>,
  globalMarket: Awaited<ReturnType<typeof fetchCryptoGlobalMarket>>,
  coinMarkets: Awaited<ReturnType<typeof fetchCryptoCoinMarkets>>,
  btcEtfHistory: Awaited<ReturnType<typeof fetchCryptoEtfFlowHistory>>,
  ethEtfHistory: Awaited<ReturnType<typeof fetchCryptoEtfFlowHistory>>,
): CryptoFlowView {
  const coinMarket = coinMarkets[definition.coingeckoId];
  const stablecoin = snapshot.derived.STABLECOIN_TBILL_DEMAND?.value ?? null;
  const stablecoinDemandScore = stablecoin === null ? null : clamp(55 + stablecoin * 18, 20, 85);
  const stablecoinDominancePct = globalMarket.totalMarketCapUsd && snapshot.raw.STABLECOIN_MCAP?.value
    ? Number((((snapshot.raw.STABLECOIN_MCAP.value * 1_000_000_000) / globalMarket.totalMarketCapUsd) * 100).toFixed(1))
    : null;
  const stablecoinDemandLabel: CryptoFlowView['stablecoinDemandLabel'] =
    stablecoinDemandScore === null ? '판단불가'
      : stablecoinDemandScore >= 65 ? '확장'
        : stablecoinDemandScore >= 48 ? '중립'
          : '둔화';

  const btc30 = (() => {
    const hist = histories['BTC'];
    if (!hist || hist.length <= 30) return null;
    return pctChange(hist[hist.length - 1].close, hist[hist.length - 31].close);
  })();
  const altReturns = ['ETH', 'SOL', 'XRP', 'BNB']
    .map((symbol) => {
      const hist = histories[symbol];
      if (!hist || hist.length <= 30) return null;
      return pctChange(hist[hist.length - 1].close, hist[hist.length - 31].close);
    })
    .filter((value): value is number => typeof value === 'number');
  const altAvg = altReturns.length ? altReturns.reduce((a, b) => a + b, 0) / altReturns.length : null;
  const dominanceDelta = btc30 !== null && altAvg !== null ? Number((btc30 - altAvg).toFixed(1)) : 0;
  const altSeasonScore = clamp(
    50
    + (altAvg !== null && btc30 !== null ? (altAvg - btc30) * 0.9 : 0)
    + (stablecoinDemandScore !== null ? (stablecoinDemandScore - 55) * 0.35 : 0),
    5,
    95,
  );
  const altSeasonLabel: CryptoFlowView['altSeasonLabel'] =
    altSeasonScore >= 62 ? '알트 시즌'
      : altSeasonScore <= 38 ? 'BTC 시즌'
        : '중립';
  const altSeasonInsight =
    altSeasonLabel === '알트 시즌'
      ? 'BTC보다 알트 쪽으로 자금이 퍼지는 구간입니다. 다만 추격 과열은 따로 관리해야 합니다.'
      : altSeasonLabel === 'BTC 시즌'
        ? '지금은 BTC 중심 장세에 가깝습니다. 알트는 강한 것만 선별하는 편이 낫습니다.'
        : 'BTC와 알트가 혼조입니다. 특정 서사가 붙는 코인만 선택적으로 보는 편이 좋습니다.';
  const btcDominancePct = typeof globalMarket.btcDominancePct === 'number' ? Number(globalMarket.btcDominancePct.toFixed(1)) : null;
  const btcDominanceScore = clamp(
    50
    + dominanceDelta
    + (btcDominancePct === null ? 0 : clamp((btcDominancePct - 55) * 0.8, -10, 12)),
    5,
    95,
  );
  const btcDominanceLabel: CryptoFlowView['btcDominanceLabel'] =
    dominanceDelta >= 8 ? 'BTC 주도'
      : dominanceDelta <= -8 ? '알트 확산'
        : '균형';

  const etfHistory = definition.symbol === 'ETH' ? ethEtfHistory : definition.symbol === 'BTC' ? btcEtfHistory : [];
  const etfDailyNetFlowUsd = etfHistory.length ? etfHistory[etfHistory.length - 1].totalNetInflowUsd : null;
  const etfWeeklyNetFlowUsd = etfHistory.length
    ? etfHistory.slice(-5).reduce((sum, row) => sum + row.totalNetInflowUsd, 0)
    : null;
  const etfFlowProxy: CryptoFlowView['etfFlowProxy'] =
    definition.symbol === 'BTC' && (stablecoinDemandScore ?? 0) >= 60 ? '강함'
      : definition.symbol === 'ETH' && (stablecoinDemandScore ?? 0) >= 58 ? '보통'
        : definition.symbol === 'BTC' || definition.symbol === 'ETH' ? '보통'
          : '약함';

  const volumeToMarketCapPct = coinMarket?.marketCapUsd && coinMarket.totalVolumeUsd
    ? Number(((coinMarket.totalVolumeUsd / coinMarket.marketCapUsd) * 100).toFixed(1))
    : null;

  const exchangeFlowRisk: CryptoFlowView['exchangeFlowRisk'] =
    (
      market.volumeTrend30d !== null
      && market.volumeTrend30d >= 25
      && market.return30d !== null
      && market.return30d >= 30
    ) || (volumeToMarketCapPct !== null && volumeToMarketCapPct >= 16)
      ? '높음'
      : (market.volatility30d !== null && market.volatility30d >= 6) || (volumeToMarketCapPct !== null && volumeToMarketCapPct >= 9)
        ? '보통'
        : '낮음';

  const exchangeNetflowProxy: CryptoFlowView['exchangeNetflowProxy'] =
    market.return7d !== null && market.return7d <= -6 && market.volumeTrend30d !== null && market.volumeTrend30d >= 12
      ? '유입 우세'
      : market.return7d !== null && market.return7d >= 8 && market.volumeTrend30d !== null && market.volumeTrend30d <= 5
        ? '유출 우세'
        : volumeToMarketCapPct !== null && volumeToMarketCapPct >= 15 && (market.return7d ?? 0) < 0
          ? '유입 우세'
          : volumeToMarketCapPct !== null && volumeToMarketCapPct <= 8 && (market.return30d ?? 0) > 0
            ? '유출 우세'
            : '중립';
  const exchangeNetflowInsight =
    exchangeNetflowProxy === '유입 우세'
      ? '거래소 쪽으로 물량이 들어오는 프록시가 강합니다. 단기 매도 압력을 먼저 경계해야 합니다.'
      : exchangeNetflowProxy === '유출 우세'
        ? '거래소 밖 보유/축적 쪽으로 해석할 여지가 있습니다. 다만 가격 과열이면 추격은 별개입니다.'
        : '거래소 순유입/순유출 방향은 아직 중립입니다.';

  const derivativesHeat: CryptoFlowView['derivativesHeat'] =
    (volumeToMarketCapPct !== null && volumeToMarketCapPct >= 18) || (market.volatility30d !== null && market.volatility30d >= 8)
      ? '높음'
      : (volumeToMarketCapPct !== null && volumeToMarketCapPct >= 10) || (market.volatility30d !== null && market.volatility30d >= 5)
        ? '보통'
        : '낮음';

  const reasons = [
    stablecoinDemandScore !== null ? `스테이블코인 수요 ${stablecoinDemandLabel}` : '스테이블코인 수요 데이터 부족',
    `알트 시즌 프록시 ${altSeasonLabel}`,
    btcDominancePct !== null
      ? `BTC dominance ${btcDominancePct}% / 상대강도 ${dominanceDelta >= 0 ? '+' : ''}${dominanceDelta}%p`
      : `BTC dominance proxy ${dominanceDelta >= 0 ? '+' : ''}${dominanceDelta}%p`,
    definition.symbol === 'BTC'
      ? `BTC 현물 ETF 일간 순유입 ${etfDailyNetFlowUsd === null ? '데이터 부족' : `${(etfDailyNetFlowUsd / 1_000_000).toFixed(1)}M USD`}`
      : definition.symbol === 'ETH'
        ? `ETH 현물 ETF 일간 순유입 ${etfDailyNetFlowUsd === null ? '데이터 부족' : `${(etfDailyNetFlowUsd / 1_000_000).toFixed(1)}M USD`}`
        : '개인/알트 유동성 의존도가 큼',
    volumeToMarketCapPct !== null ? `거래대금/시총 ${volumeToMarketCapPct}%` : null,
    `거래소 순유입/유출 프록시 ${exchangeNetflowProxy}`,
    exchangeFlowRisk === '높음' ? '거래량 급증과 급등이 겹쳐 과열 주의' : exchangeFlowRisk === '보통' ? '거래량과 변동성이 같이 살아나는 구간' : '거래소 과열 신호는 아직 제한적',
  ].slice(0, 4);

  const summary = altSeasonLabel === '알트 시즌'
    ? '알트 확산 구간이 일부 열리고 있습니다. 다만 과열도와 거래소 위험을 함께 봐야 합니다.'
    : altSeasonLabel === 'BTC 시즌'
      ? '현재는 BTC 중심 자금 흐름이 강한 편입니다. 알트는 선택적으로 접근하는 편이 낫습니다.'
      : 'BTC와 알트 사이 자금 흐름은 아직 균형에 가깝습니다.';

  return {
    stablecoinDemandScore,
    stablecoinDemandLabel,
    stablecoinDominancePct,
    altSeasonScore: round(altSeasonScore),
    altSeasonLabel,
    altSeasonInsight,
    btcDominanceScore: round(btcDominanceScore),
    btcDominanceLabel,
    btcDominancePct,
    etfFlowProxy,
    etfDailyNetFlowUsd,
    etfWeeklyNetFlowUsd,
    exchangeNetflowProxy,
    exchangeNetflowInsight,
    exchangeFlowRisk,
    derivativesHeat,
    volumeToMarketCapPct,
    summary,
    reasons: reasons.filter((item): item is string => Boolean(item)),
  };
}

function buildTrendCharts(
  definition: CryptoAssetDefinition,
  histories: Record<string, Awaited<ReturnType<typeof fetchYahooHistory>>>,
  stablecoinHistory: Awaited<ReturnType<typeof fetchStablecoinMcapHistory>>,
  btcEtfHistory: Awaited<ReturnType<typeof fetchCryptoEtfFlowHistory>>,
  ethEtfHistory: Awaited<ReturnType<typeof fetchCryptoEtfFlowHistory>>,
) {
  const btcHist = histories['BTC'] ?? [];
  const altSymbols = ['ETH', 'SOL', 'XRP', 'BNB'].filter((symbol) => symbol !== definition.symbol);
  const altHistories = altSymbols.map((symbol) => histories[symbol] ?? []);
  const sharedDates = btcHist.slice(-30).map((row) => row.date);
  const btcBase = btcHist.length >= 30 ? btcHist[btcHist.length - 30].close : btcHist[0]?.close ?? 1;
  const btcDominanceProxy30d = sharedDates.map((date, idx) => {
    const btcPoint = btcHist.find((row) => row.date === date);
    const btcNorm = btcPoint ? btcPoint.close / btcBase : 1;
    const altNorms = altHistories.map((series) => {
      const base = series.length >= 30 ? series[series.length - 30].close : series[0]?.close ?? 1;
      const point = series.find((row) => row.date === date);
      return point ? point.close / base : 1;
    }).filter((v) => Number.isFinite(v) && v > 0);
    const altAvg = altNorms.length ? altNorms.reduce((a, b) => a + b, 0) / altNorms.length : 1;
    return {
      date,
      value: Number((100 + (btcNorm - altAvg) * 100).toFixed(1)),
    };
  });

  const stablecoinMcap30d = stablecoinHistory.slice(-30).map((row) => ({
    date: row.date,
    value: row.marketCapBillions,
  }));

  const etfSource = definition.symbol === 'ETH' ? ethEtfHistory : btcEtfHistory;
  const etfNetFlow30d = etfSource.slice(-30).map((row) => ({
    date: row.date,
    value: Number((row.totalNetInflowUsd / 1_000_000).toFixed(1)),
  }));

  const altSeasonProxy30d = sharedDates.map((date) => {
    const btcPoint = btcHist.find((row) => row.date === date);
    const btcBase = btcHist.length >= 30 ? btcHist[btcHist.length - 30].close : btcHist[0]?.close ?? 1;
    const btcNorm = btcPoint ? btcPoint.close / btcBase : 1;
    const altNorms = altHistories.map((series) => {
      const base = series.length >= 30 ? series[series.length - 30].close : series[0]?.close ?? 1;
      const point = series.find((row) => row.date === date);
      return point ? point.close / base : 1;
    }).filter((v) => Number.isFinite(v) && v > 0);
    const altAvg = altNorms.length ? altNorms.reduce((a, b) => a + b, 0) / altNorms.length : 1;
    return {
      date,
      value: Number((50 + (altAvg - btcNorm) * 100).toFixed(1)),
    };
  });

  const exchangeNetflowProxy30d = sharedDates.map((date) => {
    const targetSeries = histories[definition.symbol] ?? [];
    const point = targetSeries.find((row) => row.date === date);
    const idx = targetSeries.findIndex((row) => row.date === date);
    const prev = idx > 0 ? targetSeries[idx - 1] : null;
    const ret = point && prev ? ((point.close - prev.close) / prev.close) * 100 : 0;
    const recentVolumes = targetSeries.slice(Math.max(0, idx - 29), idx + 1).map((row) => row.volume ?? 0).filter((v) => v > 0);
    const olderVolumes = targetSeries.slice(Math.max(0, idx - 59), Math.max(0, idx - 29)).map((row) => row.volume ?? 0).filter((v) => v > 0);
    const recentAvg = recentVolumes.length ? recentVolumes.reduce((a, b) => a + b, 0) / recentVolumes.length : 0;
    const olderAvg = olderVolumes.length ? olderVolumes.reduce((a, b) => a + b, 0) / olderVolumes.length : recentAvg;
    const volTrend = olderAvg > 0 ? ((recentAvg - olderAvg) / olderAvg) * 100 : 0;
    return {
      date,
      value: Number((50 + Math.min(25, Math.max(-25, volTrend * 0.35 - ret * 1.8))).toFixed(1)),
    };
  });

  return { btcDominanceProxy30d, stablecoinMcap30d, etfNetFlow30d, altSeasonProxy30d, exchangeNetflowProxy30d };
}

function buildBuyScore(
  definition: CryptoAssetDefinition,
  macro: CryptoMacroView,
  narrative: CryptoNarrativeView,
  bottomUp: CryptoBottomUpView,
  market: CryptoMarketStats,
  flows: CryptoFlowView,
): CryptoBuyScore {
  const base = round((definition.foundationalScore + bottomUp.networkScore + bottomUp.tokenomicsScore + bottomUp.adoptionScore) / 4);
  const macroScore = round(([macro.liquidityScore, macro.dollarScore, macro.riskOnScore].filter((v): v is number => typeof v === 'number').reduce((a, b) => a + b, 0) || 165) / 3);
  const momentumAppeal = market.return90d === null ? 55 : clamp(55 + market.return90d * 0.45, 25, 85);
  const flowScore = round(((flows.stablecoinDemandScore ?? 55) + (100 - Math.abs(flows.btcDominanceScore - 50))) / 2);
  const appealScore = round(base * 0.4 + macroScore * 0.2 + momentumAppeal * 0.2 + flowScore * 0.2);

  let crowding = narrative.heatScore * 0.55;
  if (market.return7d !== null) crowding += clamp(market.return7d * 0.8, -8, 18);
  if (market.distanceFrom52wHigh !== null) crowding += market.distanceFrom52wHigh >= -5 ? 18 : market.distanceFrom52wHigh >= -12 ? 10 : 0;
  if (market.volatility30d !== null) crowding += clamp((market.volatility30d - 3) * 3.5, 0, 18);
  if (flows.exchangeFlowRisk === '높음') crowding += 10;
  crowding = clamp(crowding, 10, 95);

  const buyScore = round(appealScore * 0.68 + (100 - crowding) * 0.32);
  const action = actionFromScore(buyScore);
  const reasons: string[] = [
    `기초체력 ${base}/100`,
    `거시 정합성 ${macroScore}/100`,
    `자금 흐름 ${flowScore}/100`,
    narrative.stage === 'OVERHEATED' ? '내러티브가 과열 구간' : narrative.stage === 'MID' ? '내러티브가 확산 중' : '내러티브가 초기 구간',
    market.return30d !== null ? `30일 수익률 ${market.return30d >= 0 ? '+' : ''}${market.return30d}%` : null,
    market.distanceFrom52wHigh !== null ? `52주 고점 대비 ${market.distanceFrom52wHigh}%` : null,
  ].filter((item): item is string => Boolean(item)).slice(0, 5);
  return { appealScore, crowdingScore: round(crowding), buyScore, action, actionLabel: actionLabel(action), reasons };
}

function bottomMetricStatus(score: number | null): BottomMetricStatus {
  if (score === null || Number.isNaN(score)) return 'neutral';
  if (score >= 68) return 'positive';
  if (score >= 45) return 'neutral';
  return 'negative';
}

function buildCryptoBottomSignal(
  definition: CryptoAssetDefinition,
  market: CryptoMarketStats,
  macro: CryptoMacroView,
  narrative: CryptoNarrativeView,
  flows: CryptoFlowView,
  moat: CryptoResearchResponse['moat'],
  supplyPressure: CryptoResearchResponse['supplyPressure'],
  onchain: CryptoResearchResponse['onchain'],
  buyScore: CryptoBuyScore,
  history: Awaited<ReturnType<typeof fetchYahooHistory>>,
): CryptoBottomSignal {
  const pattern = analyzeBottomPattern(history.map((item) => ({ date: item.date, close: item.close, volume: item.volume })));
  const meanBefore = (date: string | null, lookback = 20) => {
    if (!date) return null;
    const index = history.findIndex((item) => item.date === date);
    if (index < 0) return null;
    const values = history.slice(Math.max(0, index - lookback), index).map((item) => item.volume ?? 0).filter((value) => value > 0);
    return mean(values);
  };
  const maxVolumeBefore = (date: string | null, lookback = 3) => {
    if (!date) return null;
    const index = history.findIndex((item) => item.date === date);
    if (index < 0) return null;
    const values = history.slice(Math.max(0, index - lookback), index).map((item) => item.volume ?? 0).filter((value) => value > 0);
    return values.length ? Math.max(...values) : null;
  };
  const dailyCloseDropPct = (date: string | null) => {
    if (!date) return null;
    const index = history.findIndex((item) => item.date === date);
    if (index <= 0) return null;
    const current = history[index]?.close ?? 0;
    const previous = history[index - 1]?.close ?? 0;
    if (!current || !previous) return null;
    return Number((((current - previous) / previous) * 100).toFixed(1));
  };
  const candidateHistory = pattern.candidatePoint ? history.find((item) => item.date === pattern.candidatePoint?.date) ?? null : null;
  const confirmHistory = pattern.confirmPoint ? history.find((item) => item.date === pattern.confirmPoint?.date) ?? null : null;
  const retestHistory = pattern.retestPoint ? history.find((item) => item.date === pattern.retestPoint?.date) ?? null : null;
  const candidateVolumeRatio = candidateHistory?.volume && meanBefore(pattern.candidatePoint?.date ?? null) ? Number((candidateHistory.volume / (meanBefore(pattern.candidatePoint?.date ?? null) as number)).toFixed(2)) : null;
  const confirmVolumeRatio = confirmHistory?.volume && meanBefore(pattern.confirmPoint?.date ?? null) ? Number((confirmHistory.volume / (meanBefore(pattern.confirmPoint?.date ?? null) as number)).toFixed(2)) : null;
  const retestVolumeRatio = retestHistory?.volume && meanBefore(pattern.retestPoint?.date ?? null) ? Number((retestHistory.volume / (meanBefore(pattern.retestPoint?.date ?? null) as number)).toFixed(2)) : null;
  const absorptionDate = pattern.retestPoint?.date ?? pattern.candidatePoint?.date ?? null;
  const absorptionHistory = absorptionDate ? history.find((item) => item.date === absorptionDate) ?? null : null;
  const absorptionVolumeVsRecent2dRatio = absorptionHistory?.volume && maxVolumeBefore(absorptionDate, 2)
    ? Number((absorptionHistory.volume / (maxVolumeBefore(absorptionDate, 2) as number)).toFixed(2))
    : null;
  const absorptionVolumeVsRecent3dRatio = absorptionHistory?.volume && maxVolumeBefore(absorptionDate, 3)
    ? Number((absorptionHistory.volume / (maxVolumeBefore(absorptionDate, 3) as number)).toFixed(2))
    : null;
  const absorptionDropPct = dailyCloseDropPct(absorptionDate);
  const priorDeclineDropPct = dailyCloseDropPct(pattern.candidatePoint?.date ?? null);
  const absorptionContractionRatio = absorptionDropPct !== null
    && priorDeclineDropPct !== null
    && absorptionDropPct < 0
    && priorDeclineDropPct < 0
    && Math.abs(priorDeclineDropPct) > 0
      ? Number((Math.abs(absorptionDropPct) / Math.abs(priorDeclineDropPct)).toFixed(2))
      : null;
  const absorptionIndex = absorptionDate ? history.findIndex((item) => item.date === absorptionDate) : -1;
  const latestIndex = history.length - 1;
  const sma = (endIndexInclusive: number, lookback: number) => {
    const start = endIndexInclusive - lookback + 1;
    if (start < 0) return null;
    const values = history.slice(start, endIndexInclusive + 1).map((item) => item.close).filter((value) => value > 0);
    return mean(values);
  };
  const rollingHigh = (endIndexInclusive: number, lookback: number) => {
    const start = endIndexInclusive - lookback + 1;
    if (start < 0) return null;
    const values = history.slice(start, endIndexInclusive + 1).map((item) => item.close).filter((value) => value > 0);
    return values.length ? Math.max(...values) : null;
  };
  const cumulativeChange = (endIndexInclusive: number, days: number) => {
    if (endIndexInclusive - days < 0) return null;
    const current = history[endIndexInclusive]?.close ?? 0;
    const previous = history[endIndexInclusive - days]?.close ?? 0;
    if (!current || !previous) return null;
    return Number((((current - previous) / previous) * 100).toFixed(1));
  };
  const high120 = rollingHigh(latestIndex, 120);
  const latestClose = history[latestIndex]?.close ?? null;
  const drawdownFrom120dHighPct = latestClose && high120 ? Number((((latestClose - high120) / high120) * 100).toFixed(1)) : null;
  const ma20 = sma(latestIndex, 20);
  const ma50 = sma(latestIndex, 50);
  const ma20GapPct = latestClose && ma20 ? Number((((latestClose - ma20) / ma20) * 100).toFixed(1)) : null;
  const ma20Below50 = ma20 !== null && ma50 !== null ? ma20 < ma50 : false;
  const recentDrop3dPct = absorptionIndex >= 0 ? cumulativeChange(absorptionIndex, 3) : null;
  const daysSinceAbsorption = absorptionIndex >= 0 ? latestIndex - absorptionIndex : null;
  const reboundSinceAbsorptionPct = absorptionHistory?.close && latestClose
    ? Number((((latestClose - absorptionHistory.close) / absorptionHistory.close) * 100).toFixed(1))
    : null;
  const macroSupport = round(clamp(
    ((macro.liquidityScore ?? 55) * 0.4) + ((macro.dollarScore ?? 55) * 0.3) + ((macro.riskOnScore ?? 55) * 0.3),
    15,
    90,
  ));
  const flowReset = round(clamp(
    48
      + (flows.stablecoinDemandLabel === '확장' ? 16 : flows.stablecoinDemandLabel === '둔화' ? -12 : 0)
      + (flows.exchangeNetflowProxy === '유출 우세' ? 12 : flows.exchangeNetflowProxy === '유입 우세' ? -10 : 0)
      + (flows.etfFlowProxy === '강함' ? 10 : flows.etfFlowProxy === '약함' ? -8 : 0)
      + (flows.derivativesHeat === '낮음' ? 8 : flows.derivativesHeat === '높음' ? -12 : 0),
    15,
    90,
  ));
  const structure = round((definition.foundationalScore * 0.25) + (moat.moatScore * 0.25) + (supplyPressure.floatScore * 0.2) + (onchain.activityScore * 0.3));
  const priceReset = round(clamp(
    48
      + (market.distanceFrom52wHigh !== null ? (market.distanceFrom52wHigh <= -35 ? 18 : market.distanceFrom52wHigh <= -15 ? 10 : -8) : 0)
      + (market.distanceFrom52wLow !== null ? (market.distanceFrom52wLow >= 12 && market.distanceFrom52wLow <= 80 ? 14 : market.distanceFrom52wLow > 120 ? -10 : 0) : 0)
      + (market.volumeTrend30d !== null ? clamp(market.volumeTrend30d * 0.3, -8, 12) : 0)
      + (market.return30d !== null ? (market.return30d >= 35 ? -12 : market.return30d >= 8 ? 6 : market.return30d <= -15 ? -8 : 0) : 0),
    15,
    90,
  ));
  const patternScore = round(clamp(
    pattern.phase === 'confirm' ? 84
      : pattern.phase === 'retest' ? 64
        : pattern.phase === 'candidate' ? 56
          : 34,
    15,
    90,
  ));
  const absorptionScore = round(clamp(
    48
      + (absorptionVolumeVsRecent3dRatio !== null ? clamp((absorptionVolumeVsRecent3dRatio - 1) * 26, -12, 20) : 0)
      + (
        absorptionDropPct !== null
        && priorDeclineDropPct !== null
        && absorptionDropPct < 0
        && priorDeclineDropPct < 0
          ? (
            Math.abs(absorptionDropPct) <= Math.abs(priorDeclineDropPct) * 0.7 ? 18
              : Math.abs(absorptionDropPct) <= Math.abs(priorDeclineDropPct) * 0.9 ? 12
                : Math.abs(absorptionDropPct) <= Math.abs(priorDeclineDropPct) ? 6
                  : -10
          )
          : 0
      ),
    15,
    90,
  ));
  const volumeConfirmationScore = round(clamp(
    48
      + (candidateVolumeRatio !== null ? clamp((candidateVolumeRatio - 1) * 20, -10, 16) : 0)
      + (confirmVolumeRatio !== null ? clamp((confirmVolumeRatio - 1) * 22, -8, 18) : 0)
      + (retestVolumeRatio !== null ? clamp((1 - retestVolumeRatio) * 18, -12, 12) : 0)
      + (market.volumeTrend30d !== null ? clamp(market.volumeTrend30d * 0.18, -8, 10) : 0)
      + (absorptionVolumeVsRecent3dRatio !== null ? clamp((absorptionVolumeVsRecent3dRatio - 1) * 18, -8, 14) : 0)
      + (
        absorptionDropPct !== null
        && priorDeclineDropPct !== null
        && absorptionDropPct < 0
        && priorDeclineDropPct < 0
          ? (
            Math.abs(absorptionDropPct) <= Math.abs(priorDeclineDropPct) * 0.8 ? 12
              : Math.abs(absorptionDropPct) <= Math.abs(priorDeclineDropPct) ? 6
                : -8
          )
          : 0
      ),
    15,
    90,
  ));
  const narrativeTemperature = narrative.stage === 'EARLY' ? 74 : narrative.stage === 'MID' ? 56 : 32;
  const metrics: BottomSignalMetric[] = [
    { key: 'macro', label: '거시 받침', score: macroSupport, status: bottomMetricStatus(macroSupport), detail: macro.summary },
    { key: 'flow', label: '자금 이탈/유입', score: flowReset, status: bottomMetricStatus(flowReset), detail: flows.summary },
    { key: 'structure', label: '구조 체력', score: structure, status: bottomMetricStatus(structure), detail: `${moat.summary} · ${onchain.summary}` },
    {
      key: 'price',
      label: '가격 리셋',
      score: priceReset,
      status: bottomMetricStatus(priceReset),
      detail: [
        market.distanceFrom52wHigh !== null ? `고점대비 ${market.distanceFrom52wHigh}%` : null,
        market.distanceFrom52wLow !== null ? `저점대비 +${market.distanceFrom52wLow}%` : null,
        market.volumeTrend30d !== null ? `거래량 추세 ${market.volumeTrend30d >= 0 ? '+' : ''}${market.volumeTrend30d}%` : null,
      ].filter((item): item is string => Boolean(item)).join(' · ') || '가격 데이터 부족',
    },
    {
      key: 'pattern',
      label: '바닥 패턴',
      score: patternScore,
      status: bottomMetricStatus(patternScore),
      detail: pattern.phase === 'confirm'
        ? '급락 이후 재시험을 거쳐 1차 확인 돌파가 나온 패턴입니다.'
        : pattern.phase === 'retest'
          ? `저점 후보 이후 재시험 진행 중입니다${pattern.retestGapPct !== null ? ` (${pattern.retestGapPct >= 0 ? '+' : ''}${pattern.retestGapPct.toFixed(1)}%)` : ''}.`
          : pattern.phase === 'candidate'
            ? '저점 후보 이후 반등은 나왔지만 재시험/돌파 확인이 더 필요합니다.'
            : '아직 낙하 구간에 더 가깝습니다.',
    },
    {
      key: 'volume',
      label: '거래량 동반',
      score: volumeConfirmationScore,
      status: bottomMetricStatus(volumeConfirmationScore),
      detail: [
        candidateVolumeRatio !== null ? `저점후보 거래량 ${candidateVolumeRatio}배` : null,
        retestVolumeRatio !== null ? `재시험 거래량 ${retestVolumeRatio}배` : null,
        confirmVolumeRatio !== null ? `확인돌파 거래량 ${confirmVolumeRatio}배` : null,
      ].filter((item): item is string => Boolean(item)).join(' · ') || '거래량 확인 데이터 부족',
    },
    {
      key: 'absorption',
      label: '하락 흡수',
      score: absorptionScore,
      status: bottomMetricStatus(absorptionScore),
      detail: [
        absorptionVolumeVsRecent3dRatio !== null ? `최근 2~3봉 대비 ${absorptionVolumeVsRecent3dRatio}배` : null,
        absorptionDropPct !== null ? `현재 하락 ${absorptionDropPct}%` : null,
        priorDeclineDropPct !== null ? `이전 하락 ${priorDeclineDropPct}%` : null,
      ].filter((item): item is string => Boolean(item)).join(' · ') || '하락 흡수 비교 데이터 부족',
    },
    { key: 'narrative', label: '서사 온도', score: narrativeTemperature, status: bottomMetricStatus(narrativeTemperature), detail: `${narrative.stage} · heat ${narrative.heatScore} · ${narrative.summary}` },
    { key: 'crowding', label: '추격 부담', score: 100 - buyScore.crowdingScore, status: bottomMetricStatus(100 - buyScore.crowdingScore), detail: `과열도 ${buyScore.crowdingScore}/100` },
  ];
  const score = round(
    macroSupport * 0.16
    + flowReset * 0.16
    + structure * 0.16
    + priceReset * 0.12
    + patternScore * 0.14
    + volumeConfirmationScore * 0.18
    + narrativeTemperature * 0.04
    + (100 - buyScore.crowdingScore) * 0.04
  );
  const state: CryptoBottomSignal['state'] =
    volumeConfirmationScore >= 72 && pattern.phase === 'confirm' ? '구조적 바닥 가능'
      : volumeConfirmationScore >= 64 && (pattern.phase === 'confirm' || pattern.phase === 'retest') ? '1차 확인'
        : pattern.phase === 'retest' ? '재시험 구간'
          : volumeConfirmationScore >= 54 || pattern.phase === 'candidate' ? '바닥 시도'
            : '바닥 아님';
  const actionBias: CryptoBottomSignal['actionBias'] =
    volumeConfirmationScore >= 74 ? '분할 매수'
      : volumeConfirmationScore >= 60 ? '관찰 매수'
        : volumeConfirmationScore >= 48 ? '확인 우선'
          : '대기';
  const summary = state === '구조적 바닥 가능'
    ? `${definition.symbol}은(는) 거래량이 붙은 확인 돌파까지 나와 진짜 바닥일 가능성이 높은 구간입니다.`
    : state === '1차 확인'
      ? `${definition.symbol}은(는) 재시험 이후 거래량이 붙기 시작해 바닥 확인 신뢰도가 올라오는 구간입니다.`
      : state === '재시험 구간'
        ? `${definition.symbol}은(는) 가격은 버티지만 거래량 확인이 아직 충분하지 않아 재시험 통과를 더 봐야 합니다.`
        : state === '바닥 시도'
          ? `${definition.symbol}은(는) 반등은 나왔지만 거래량이 충분히 붙지 않아 진짜 바닥으로 보기엔 이릅니다.`
          : `${definition.symbol}은(는) 아직 바닥으로 단정하기보다 현금/관찰이 우선인 구간입니다.`;
  const points: BottomSignalChartPoint[] = history.slice(-260).map((item) => ({ date: item.date, value: item.close }));
  const markers: BottomSignalChartMarker[] = [];
  if (pattern.peakPoint) markers.push({ kind: 'peak', date: pattern.peakPoint.date, value: pattern.peakPoint.close, label: '하락 시작 고점' });
  if (pattern.candidatePoint) markers.push({ kind: 'candidate', date: pattern.candidatePoint.date, value: pattern.candidatePoint.close, label: '저점 후보' });
  if (pattern.retestPoint) markers.push({ kind: 'retest', date: pattern.retestPoint.date, value: pattern.retestPoint.close, label: '재시험 저점' });
  if (pattern.confirmPoint) markers.push({ kind: 'confirm', date: pattern.confirmPoint.date, value: pattern.confirmPoint.close, label: '거래량 확인 돌파' });
  else if (pattern.currentPoint && pattern.candidatePoint && pattern.currentPoint.date !== pattern.candidatePoint.date) {
    markers.push({
      kind: pattern.phase === 'retest' ? 'retest' : 'current',
      date: pattern.currentPoint.date,
      value: pattern.currentPoint.close,
      label: pattern.phase === 'retest' ? '재시험/관찰 구간' : '반등 진행 구간',
    });
  }
  if (pattern.currentPoint) markers.push({ kind: 'current', date: pattern.currentPoint.date, value: pattern.currentPoint.close, label: '현재' });
  const failureSignals: string[] = [
    pattern.phase === 'decline' ? '아직 하락 단계라 바닥 패턴이 완성되지 않았습니다.' : null,
    volumeConfirmationScore < 55 ? '반등 대비 거래량이 약해 진짜 바닥보다 기술적 반등일 가능성을 열어둬야 합니다.' : null,
    absorptionVolumeVsRecent3dRatio !== null && absorptionVolumeVsRecent3dRatio < 1 ? '최근 2~3개 봉 대비 거래량이 더 붙지 않아 매도 흡수 신호가 약합니다.' : null,
    absorptionDropPct !== null && priorDeclineDropPct !== null
      && absorptionDropPct < 0 && priorDeclineDropPct < 0
      && Math.abs(absorptionDropPct) > Math.abs(priorDeclineDropPct)
      ? '거래량은 붙어도 낙폭이 줄지 않아 아직 진바닥 흡수로 보기 어렵습니다.'
      : null,
    pattern.phase === 'retest' && pattern.retestGapPct !== null && pattern.retestGapPct < -5 ? '재시험이 저점 후보를 크게 하회해 바닥 실패 가능성이 있습니다.' : null,
    confirmVolumeRatio !== null && confirmVolumeRatio < 0.95 ? '확인 돌파 거래량이 약해 가짜 반등일 수 있습니다.' : null,
    flows.exchangeFlowRisk === '높음' ? '거래소 유입 위험이 높아 매도 압력이 다시 커질 수 있습니다.' : null,
    flows.derivativesHeat === '높음' ? '파생 과열이 높아 롱 청산성 변동성을 경계해야 합니다.' : null,
    buyScore.crowdingScore >= 72 ? '과열도가 높아 바닥 확인 뒤에도 추격 리스크가 큽니다.' : null,
  ].filter((item): item is string => Boolean(item));
  const failureRiskScore = round(clamp(
      24
      + (pattern.phase === 'decline' ? 22 : 0)
      + (volumeConfirmationScore < 55 ? 14 : volumeConfirmationScore < 62 ? 8 : 0)
      + (absorptionVolumeVsRecent3dRatio !== null && absorptionVolumeVsRecent3dRatio < 1 ? 10 : 0)
      + (
        absorptionDropPct !== null
        && priorDeclineDropPct !== null
        && absorptionDropPct < 0
        && priorDeclineDropPct < 0
        && Math.abs(absorptionDropPct) > Math.abs(priorDeclineDropPct)
          ? 12
          : 0
      )
      + (pattern.phase === 'retest' && pattern.retestGapPct !== null && pattern.retestGapPct < -5 ? 18 : 0)
      + (confirmVolumeRatio !== null && confirmVolumeRatio < 0.95 ? 12 : 0)
      + (flows.exchangeFlowRisk === '높음' ? 16 : 0)
      + (flows.derivativesHeat === '높음' ? 14 : 0)
      + (buyScore.crowdingScore >= 72 ? 10 : 0),
    0,
    100,
  ));
  const confirmedBottom = buildConfirmedDeepBottomSignal({
    absorptionDate,
    absorptionContractionRatio,
    absorptionVolumeVsRecent2dRatio,
    absorptionVolumeVsRecent3dRatio,
    daysSinceAbsorption,
    drawdownFrom120dHighPct,
    failureRiskScore,
    ma20Below50,
    ma20GapPct,
    recentDrop3dPct,
    reboundSinceAbsorptionPct,
  });
  return {
    score,
    state,
    actionBias,
    summary,
    volumeConfirmationScore,
    failureRiskScore,
    metrics,
    chart: { points, markers },
    confirmedBottom,
    reasons: metrics.filter((item) => item.status === 'positive').map((item) => `${item.label}: ${item.detail}`).slice(0, 4),
    cautions: metrics.filter((item) => item.status === 'negative').map((item) => `${item.label}: ${item.detail}`).slice(0, 4),
    failureSignals,
  };
}

function buildConfirmedDeepBottomSignal(input: {
  absorptionDate: string | null;
  absorptionContractionRatio: number | null;
  absorptionVolumeVsRecent2dRatio: number | null;
  absorptionVolumeVsRecent3dRatio: number | null;
  daysSinceAbsorption: number | null;
  drawdownFrom120dHighPct: number | null;
  failureRiskScore: number | null;
  ma20Below50: boolean;
  ma20GapPct: number | null;
  recentDrop3dPct: number | null;
  reboundSinceAbsorptionPct: number | null;
}): DeepBottomSignal {
  const recentVolumeRatio = input.absorptionVolumeVsRecent2dRatio !== null
    && input.absorptionVolumeVsRecent3dRatio !== null
    ? Math.min(input.absorptionVolumeVsRecent2dRatio, input.absorptionVolumeVsRecent3dRatio)
    : null;
  const score = round(clamp(
    18
      + (input.drawdownFrom120dHighPct !== null
        ? input.drawdownFrom120dHighPct <= -25 ? 18
          : input.drawdownFrom120dHighPct <= -20 ? 14
            : input.drawdownFrom120dHighPct <= -15 ? 10
              : input.drawdownFrom120dHighPct <= -10 ? 4
                : -6
        : 0)
      + (recentVolumeRatio !== null
        ? recentVolumeRatio >= 1.25 ? 18
          : recentVolumeRatio >= 1.1 ? 14
            : recentVolumeRatio >= 1 ? 8
              : -8
        : 0)
      + (input.absorptionContractionRatio !== null
        ? input.absorptionContractionRatio <= 0.6 ? 18
          : input.absorptionContractionRatio <= 0.8 ? 14
            : input.absorptionContractionRatio <= 1 ? 8
              : -10
        : 0)
      + (input.recentDrop3dPct !== null
        ? input.recentDrop3dPct <= -10 ? 18
          : input.recentDrop3dPct <= -8 ? 15
            : input.recentDrop3dPct <= -5 ? 10
              : input.recentDrop3dPct <= -3 ? 4
                : -6
        : 0)
      + (input.ma20GapPct !== null
        ? input.ma20GapPct <= -10 ? 18
          : input.ma20GapPct <= -8 ? 14
            : input.ma20GapPct <= -6 ? 10
              : input.ma20GapPct <= -2 ? 4
                : -8
        : 0)
      + (input.ma20Below50 ? 10 : -12)
      + (input.daysSinceAbsorption !== null
        ? input.daysSinceAbsorption > 40 ? -18
          : input.daysSinceAbsorption > 25 ? -10
            : input.daysSinceAbsorption > 15 ? -4
              : 4
        : 0)
      + (input.reboundSinceAbsorptionPct !== null
        ? input.reboundSinceAbsorptionPct > 40 ? -18
          : input.reboundSinceAbsorptionPct > 25 ? -12
            : input.reboundSinceAbsorptionPct > 15 ? -6
              : input.reboundSinceAbsorptionPct >= 0 ? 4
                : -4
        : 0)
      + (input.failureRiskScore !== null ? clamp((45 - input.failureRiskScore) * 0.35, -16, 12) : 0),
    0,
    100,
  ));

  const state: DeepBottomSignal['state'] =
    score >= 78
      && (input.daysSinceAbsorption ?? 999) <= 25
      && (input.reboundSinceAbsorptionPct ?? 999) <= 25
      && recentVolumeRatio !== null && recentVolumeRatio >= 1.1
      && input.absorptionContractionRatio !== null
      ? '확신'
      : score >= 62
        ? '후보'
        : '미충족';

  return {
    score,
    state,
    actionBias: state === '확신' ? '분할 매수' : state === '후보' ? '관찰 매수' : '대기',
    signalDate: input.absorptionDate,
    daysSinceSignal: input.daysSinceAbsorption,
    summary: state === '확신'
      ? '미래 반등 확인 없이도 하락장·거래량 급증·낙폭 축소·과매도 조건이 함께 충족된 확신형 바닥 신호입니다.'
      : state === '후보'
        ? '당시 데이터만 봐도 강한 바닥 후보 조건이 일부 충족됐지만, 아직 확신형으로 부르기엔 조건이 약간 부족합니다.'
        : '현재 구간은 확신형 바닥 신호로 보기 어렵습니다. 일반 바닥 후보 정도로 해석하는 편이 안전합니다.',
    recentVolumeRatio,
    contractionRatio: input.absorptionContractionRatio,
    drawdown120dPct: input.drawdownFrom120dHighPct,
    ma20GapPct: input.ma20GapPct,
    recentDrop3dPct: input.recentDrop3dPct,
    reasons: [
      recentVolumeRatio !== null && recentVolumeRatio >= 1.1 ? `직전 3개 거래일 최대 대비 거래량 ${recentVolumeRatio}배로 투매 흡수 흔적` : null,
      input.absorptionContractionRatio !== null && input.absorptionContractionRatio <= 0.8 ? `낙폭이 직전 하락의 ${(input.absorptionContractionRatio * 100).toFixed(0)}% 수준으로 둔화` : null,
      input.recentDrop3dPct !== null && input.recentDrop3dPct <= -5 ? `직전 3일 누적 하락 ${input.recentDrop3dPct}%로 급락 구간 통과` : null,
      input.ma20GapPct !== null && input.ma20GapPct <= -8 ? `20일선 대비 ${input.ma20GapPct}% 이격으로 과매도 구간` : null,
      input.drawdownFrom120dHighPct !== null && input.drawdownFrom120dHighPct <= -15 ? `120일 고점 대비 ${input.drawdownFrom120dHighPct}% 하락` : null,
    ].filter((item): item is string => Boolean(item)).slice(0, 4),
    cautions: [
      recentVolumeRatio === null ? '직전 3개 거래일과 비교할 거래량 근거가 부족합니다.' : null,
      recentVolumeRatio !== null && recentVolumeRatio < 1.1 ? '직전 3개 거래일 최대 거래량 대비 우위가 약합니다.' : null,
      input.absorptionContractionRatio === null ? '하락일 투매 흡수 조건이 확인되지 않았습니다.' : null,
      input.absorptionContractionRatio !== null && input.absorptionContractionRatio > 0.8 ? '낙폭 축소가 충분하지 않아 흡수 신호가 약합니다.' : null,
      input.ma20GapPct !== null && input.ma20GapPct > -8 ? '20일선 이격이 작아 강한 투매성 바닥으로 보기 어렵습니다.' : null,
      input.daysSinceAbsorption !== null && input.daysSinceAbsorption > 25 ? '신호 발생 후 시간이 지나 초기 바닥 초입 매력은 줄었습니다.' : null,
      input.reboundSinceAbsorptionPct !== null && input.reboundSinceAbsorptionPct > 25 ? '신호 이후 이미 많이 반등해 초기 진입 구간은 일부 지나갔습니다.' : null,
      input.failureRiskScore !== null && input.failureRiskScore >= 55 ? '실패 위험 점수가 높아 확신형 신호라도 보수적 비중이 필요합니다.' : null,
    ].filter((item): item is string => Boolean(item)).slice(0, 4),
  };
}

function buildPositionSizing(buyScore: CryptoBuyScore, narrative: CryptoNarrativeView): CryptoPositionSizingPlan {
  const targetPositionPct = buyScore.action === 'STRONG BUY' ? 10 : buyScore.action === 'BUY' ? 8 : buyScore.action === 'HOLD' ? 5 : buyScore.action === 'REDUCE' ? 3 : 0;
  const initialEntryPctOfTarget = buyScore.action === 'STRONG BUY' ? 35 : buyScore.action === 'BUY' ? 25 : buyScore.action === 'HOLD' ? 15 : 0;
  const reservePctOfTarget = Math.max(0, 100 - initialEntryPctOfTarget);
  const summary = narrative.stage === 'OVERHEATED'
    ? '좋은 코인이어도 서사가 뜨거워 1차만 작게 보고 현금을 많이 남기는 편이 좋습니다.'
    : buyScore.action === 'BUY' || buyScore.action === 'STRONG BUY'
      ? '분할 진입 전제로 접근 가능한 구간입니다. 초기 비중만 먼저 넣고 남은 현금을 유지합니다.'
      : '관찰 또는 축소 구간입니다. 새 진입보다 가격/거시 확인이 우선입니다.';
  return { targetPositionPct, initialEntryPctOfTarget, reservePctOfTarget, summary };
}

function buildVerdicts(
  definition: CryptoAssetDefinition,
  bottomUp: CryptoBottomUpView,
  narrative: CryptoNarrativeView,
  macro: CryptoMacroView,
  flows: CryptoFlowView,
  buyScore: CryptoBuyScore,
): CryptoVerdictView {
  const qualityAvg = round((definition.foundationalScore + bottomUp.networkScore + bottomUp.tokenomicsScore + bottomUp.adoptionScore) / 4);
  const quality: CryptoVerdictView['quality'] =
    qualityAvg >= 82 ? '강함'
      : qualityAvg >= 72 ? '양호'
        : qualityAvg >= 60 ? '보통'
          : '약함';
  const timing: CryptoVerdictView['timing'] =
    macro.stance === '우호' && narrative.stage !== 'OVERHEATED' ? '우호'
      : macro.stance === '주의' || flows.exchangeFlowRisk === '높음' ? '주의'
        : '중립';
  const valuationProxy: CryptoVerdictView['valuationProxy'] =
    buyScore.crowdingScore >= 72 ? '과열 부담'
      : buyScore.crowdingScore >= 55 ? '중립'
        : '부담 낮음';
  return {
    quality,
    timing,
    valuationProxy,
    finalAction: buyScore.action,
    oneLiners: {
      quality: quality === '강함'
        ? `${definition.symbol}은(는) 코인 안에서도 기본 체력이 강한 편입니다.`
        : quality === '양호'
          ? `${definition.symbol}은(는) 구조와 채택이 양호합니다.`
          : quality === '보통'
            ? `${definition.symbol}은(는) 체력은 무난하지만 서사 의존도가 큽니다.`
            : `${definition.symbol}은(는) 구조적 강점보다 이벤트 민감도가 큽니다.`,
      timing: timing === '우호'
        ? '지금은 거시와 내러티브가 크게 충돌하지 않습니다.'
        : timing === '중립'
          ? '방향성은 있지만 추격보다 분할 접근이 더 적절합니다.'
          : '지금은 과열 또는 거시 역풍을 먼저 경계해야 합니다.',
      action: buyScore.action === 'STRONG BUY'
        ? '핵심 코어로 볼 수 있지만 그래도 분할 매수가 우선입니다.'
        : buyScore.action === 'BUY'
          ? '매수 가능 구간이지만 현금 여지를 남기고 들어가는 편이 좋습니다.'
          : buyScore.action === 'HOLD'
            ? '좋은 코인일 수 있어도 지금은 보유·관찰이 더 적절합니다.'
            : buyScore.action === 'REDUCE'
              ? '새 진입보다 비중 축소나 익절을 먼저 보는 편이 낫습니다.'
              : '지금은 회피가 더 유리한 구간입니다.',
    },
  };
}

function buildScenarios(
  definition: CryptoAssetDefinition,
  flows: CryptoFlowView,
  macro: CryptoMacroView,
  narrative: CryptoNarrativeView,
): CryptoScenarioView {
  const bullCase =
    definition.symbol === 'BTC'
      ? '유동성 완화 + 달러 약세 + BTC dominance 유지면 디지털 금/ETF 자금 축이 더 강해질 수 있습니다.'
      : `유동성 완화 + 달러 약세 + ${flows.btcDominanceLabel === '알트 확산' ? '알트 확산' : '나스닥 위험선호 회복'}이 겹치면 ${definition.symbol}의 베타가 더 커질 수 있습니다.`;
  const baseCase =
    narrative.stage === 'OVERHEATED'
      ? '좋은 서사지만 이미 많이 알려진 구간이라 분할 진입·현금 보유가 기본 시나리오입니다.'
      : '거시 중립 구간에선 코인 고유 서사와 수급을 보며 작은 비중으로 대응하는 시나리오가 기본입니다.';
  const bearCase =
    macro.stance === '주의' || flows.exchangeFlowRisk === '높음'
      ? '달러 강세/유동성 둔화/거래소 과열이 겹치면 급락 변동성에 먼저 노출될 수 있습니다.'
      : '내러티브가 꺾이거나 BTC dominance가 급상승하면 알트/고베타 코인부터 약해질 수 있습니다.';
  return { bullCase, baseCase, bearCase };
}

function mapExecutionAction(action: string) {
  const normalized = action.toUpperCase();
  if (normalized.includes('BUY')) return '매수 가능';
  if (normalized.includes('TAKE_PROFIT') || normalized.includes('REDUCE')) return '축소';
  if (normalized.includes('SELL')) return '매도/회피';
  return '보유/관찰';
}

function buildExecutionBridge(
  definition: CryptoAssetDefinition,
  snapshot: Awaited<ReturnType<typeof getSnapshot>>,
  buyScore: CryptoBuyScore,
  flows: CryptoFlowView,
): CryptoExecutionBridge | null {
  const plan = snapshot.meta.executionPlans?.find((item) => item.asset === definition.linkedAsset);
  if (!plan) return null;
  const coinSide = buyScore.action === 'STRONG BUY' || buyScore.action === 'BUY' ? 'risk-on' : buyScore.action === 'SELL' || buyScore.action === 'REDUCE' ? 'risk-off' : 'neutral';
  const assetSide = /BUY/i.test(plan.action) ? 'risk-on' : /SELL|TAKE_PROFIT|REDUCE/i.test(plan.action) ? 'risk-off' : 'neutral';
  const alignment: CryptoExecutionBridge['alignment'] =
    coinSide === assetSide ? 'aligned'
      : coinSide === 'neutral' || assetSide === 'neutral' ? 'mixed'
        : 'conflicted';
  const entryMode: CryptoExecutionBridge['entryMode'] =
    buyScore.action === 'STRONG BUY' ? '현물 코어'
      : buyScore.action === 'BUY' ? '분할 현물'
        : buyScore.action === 'HOLD' ? '관찰 대기'
          : '축소/익절';
  const riskBox = flows.derivativesHeat === '높음' || flows.exchangeFlowRisk === '높음'
    ? '단기 쏠림이 강해 추격 진입보다 분할과 현금 보유가 우선입니다.'
    : alignment === 'conflicted'
      ? '코인 자체 평가는 양호해도 연결 자산 실행계획과 충돌합니다. 공격 진입은 한 템포 늦추는 편이 좋습니다.'
      : '코인 평가와 연결 자산 방향이 크게 충돌하지 않습니다.';
  const summary = alignment === 'aligned'
    ? `${definition.symbol} 판단과 ${definition.linkedAsset} 실행계획이 대체로 같은 방향입니다.`
    : alignment === 'mixed'
      ? `${definition.symbol} 자체 평가는 나쁘지 않지만 연결 자산 플랜은 중립입니다. 분할 접근이 적절합니다.`
      : `${definition.symbol} 자체 평가는 있어도 연결 자산 플랜은 보수적입니다. 공격 진입보다 관찰/축소가 낫습니다.`;
  return {
    asset: plan.asset,
    action: plan.action,
    actionLabel: mapExecutionAction(plan.action),
    targetAllocationPct: plan.targetAllocationPct,
    alignment,
    entryMode,
    riskBox,
    summary,
    timingNotes: plan.timing?.notes?.slice(0, 3) ?? [],
  };
}

export function listCryptoAssets() {
  return CRYPTO_ASSETS;
}

export async function buildCryptoMarketRegime(): Promise<CryptoMarketRegimeView> {
  const shared = await buildSharedCryptoContext();
  const snapshot = shared.snapshot;
  const stablecoin = snapshot.derived.STABLECOIN_TBILL_DEMAND?.value ?? 0;
  const dxy = snapshot.derived.DXY_TREND?.value ?? 0;
  const btcHist = shared.histories.BTC ?? [];
  const ethHist = shared.histories.ETH ?? [];
  const solHist = shared.histories.SOL ?? [];
  const ret = (series: { close: number }[], days: number) =>
    series.length > days ? pctChange(series[series.length - 1].close, series[series.length - 1 - days].close) : null;
  const btc30 = ret(btcHist, 30) ?? 0;
  const altAvg = mean([ret(ethHist, 30), ret(solHist, 30)].filter((v): v is number => typeof v === 'number')) ?? 0;
  const altDelta = altAvg - btc30;
  const globalBtcDom = shared.globalMarket.btcDominancePct ?? 0;
  const btcEtf5d = shared.btcEtfHistory.slice(-5).reduce((sum, row) => sum + row.totalNetInflowUsd, 0);
  const ethEtf5d = shared.ethEtfHistory.slice(-5).reduce((sum, row) => sum + row.totalNetInflowUsd, 0);
  const riskScore = clamp(
    50
    + stablecoin * 14
    - dxy * 10
    + (btcEtf5d / 1_000_000_000) * 12
    + (ethEtf5d / 1_000_000_000) * 5
    - Math.max(0, globalBtcDom - 62) * 0.9,
    0,
    100,
  );
  const regime: CryptoMarketRegimeView['regime'] =
    riskScore >= 68 ? 'RISK_ON'
      : riskScore >= 55 ? 'SELECTIVE'
        : riskScore >= 40 ? 'DEFENSIVE'
          : 'STAY_OUT';
  const action: CryptoMarketRegimeView['action'] =
    regime === 'RISK_ON' ? '공격 가능'
      : regime === 'SELECTIVE' ? '선별 접근'
        : regime === 'DEFENSIVE' ? '현금 우선'
          : '관망';
  const altRegime: CryptoMarketRegimeView['altRegime'] =
    altDelta >= 8 ? '알트 확산장'
      : altDelta <= -8 || globalBtcDom >= 62 ? 'BTC 중심장'
        : '혼조장';
  const targetTotalExposurePct =
    regime === 'RISK_ON' ? (altRegime === '알트 확산장' ? 20 : 15)
      : regime === 'SELECTIVE' ? 10
        : regime === 'DEFENSIVE' ? 5
          : 0;
  const summary =
    regime === 'RISK_ON'
      ? `${altRegime}에 가깝습니다. 코인장 자체는 열려 있지만 과열 관리는 계속 필요합니다.`
      : regime === 'SELECTIVE'
        ? `${altRegime}이지만 전체 공격보단 강한 코인만 선별 접근하는 구간입니다.`
        : regime === 'DEFENSIVE'
          ? `코인장 자체는 방어적으로 봐야 합니다. 현금 비중을 우선하고 새 진입은 작게 보는 편이 낫습니다.`
          : `지금은 코인장 자체를 쉬는 편이 더 낫습니다. 개별 코인 우열보다 시장 허용 여부가 먼저입니다.`;
  const reasons = [
    `스테이블 수요 프록시 ${stablecoin >= 0.5 ? '확장' : stablecoin <= -0.5 ? '둔화' : '중립'}`,
    `달러 흐름 ${dxy <= -0.5 ? '우호' : dxy >= 0.5 ? '역풍' : '중립'}`,
    `BTC ETF 5일 ${btcEtf5d >= 0 ? '+' : ''}${(btcEtf5d / 1_000_000).toFixed(1)}M USD`,
    `알트 상대강도 ${altDelta >= 0 ? '+' : ''}${altDelta.toFixed(1)}%p`,
    `BTC dominance ${globalBtcDom.toFixed(1)}%`,
  ];
  return { regime, action, altRegime, targetTotalExposurePct, summary, reasons };
}

async function buildSharedCryptoContext(): Promise<SharedCryptoContext> {
  const snapshotPromise = getSnapshot(DEFAULT_PROFILE);
  const globalMarketPromise = fetchCryptoGlobalMarket();
  const coinMarketsPromise = fetchCryptoCoinMarkets(CRYPTO_ASSETS.map((asset) => asset.coingeckoId));
  const coinDetailEntriesPromise = Promise.all(CRYPTO_ASSETS.map(async (asset) => [asset.symbol, await fetchCryptoCoinDetail(asset.coingeckoId)] as const));
  const chainMetricEntriesPromise = Promise.all(CRYPTO_ASSETS.map(async (asset) => [asset.symbol, await fetchCryptoChainMetrics(asset.llamaChainSlug)] as const));
  const stablecoinHistoryPromise = fetchStablecoinMcapHistory(30);
  const btcEtfHistoryPromise = fetchCryptoEtfFlowHistory('BTC');
  const ethEtfHistoryPromise = fetchCryptoEtfFlowHistory('ETH');
  const quoteEntries = await Promise.all(CRYPTO_ASSETS.map(async (asset) => [asset.symbol, await fetchYahooQuote(asset.yahooSymbol)] as const));
  const historyEntries = await Promise.all(CRYPTO_ASSETS.map(async (asset) => [asset.symbol, await fetchYahooHistory(asset.yahooSymbol, 400)] as const));
  return {
    snapshot: await snapshotPromise,
    quotes: Object.fromEntries(quoteEntries),
    histories: Object.fromEntries(historyEntries),
    globalMarket: await globalMarketPromise,
    coinMarkets: await coinMarketsPromise,
    coinDetails: Object.fromEntries(await coinDetailEntriesPromise),
    chainMetrics: Object.fromEntries(await chainMetricEntriesPromise),
    stablecoinHistory: await stablecoinHistoryPromise,
    btcEtfHistory: await btcEtfHistoryPromise,
    ethEtfHistory: await ethEtfHistoryPromise,
  };
}

function buildCryptoResearchFromContext(symbol: string, shared: SharedCryptoContext): CryptoResearchResponse {
  const definition = getDefinition(symbol);
  const quote = shared.quotes[definition.symbol] ?? null;
  const history = shared.histories[definition.symbol] ?? [];
  const snapshot = shared.snapshot;
  const market = buildMarketStats(history, quote?.price ?? null, quote?.date ?? null);
  const macro = buildMacroView(definition, snapshot, market);
  const narrative = buildNarrativeView(definition, market);
  const bottomUp = buildBottomUpView(definition);
  const moat = buildMoatView(definition);
  const supplyPressure = buildSupplyPressureView(definition, shared.coinDetails[definition.symbol]);
  const onchain = buildOnchainView(shared.coinDetails[definition.symbol], shared.chainMetrics[definition.symbol]);
  const flows = buildFlowView(definition, snapshot, market, shared.histories, shared.globalMarket, shared.coinMarkets, shared.btcEtfHistory, shared.ethEtfHistory);
  const trendCharts = buildTrendCharts(definition, shared.histories, shared.stablecoinHistory, shared.btcEtfHistory, shared.ethEtfHistory);
  const buyScore = buildBuyScore(definition, macro, narrative, {
    ...bottomUp,
    networkScore: round((bottomUp.networkScore + moat.moatScore + onchain.activityScore + supplyPressure.floatScore) / 4),
  }, market, flows);
  const bottomSignal = buildCryptoBottomSignal(definition, market, macro, narrative, flows, moat, supplyPressure, onchain, buyScore, history);
  const positionSizing = buildPositionSizing(buyScore, narrative);
  const verdicts = buildVerdicts(definition, bottomUp, narrative, macro, flows, buyScore);
  const scenarios = buildScenarios(definition, flows, macro, narrative);
  const executionBridge = buildExecutionBridge(definition, snapshot, buyScore, flows);
  return {
    profile: definition,
    market,
    macro,
    narrative,
    bottomUp,
    moat,
    supplyPressure,
    onchain,
    flows,
    trendCharts,
    buyScore,
    bottomSignal,
    positionSizing,
    verdicts,
    scenarios,
    executionBridge,
  };
}

export async function buildCryptoResearch(symbol: string): Promise<CryptoResearchResponse> {
  const shared = await buildSharedCryptoContext();
  return buildCryptoResearchFromContext(symbol, shared);
}

export async function buildAllCryptoResearch() {
  const shared = await buildSharedCryptoContext();
  const items = CRYPTO_ASSETS.map((asset) => buildCryptoResearchFromContext(asset.symbol, shared));
  return items.sort((a, b) => b.buyScore.buyScore - a.buyScore.buyScore);
}
