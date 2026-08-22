import { Router, Request, Response } from 'express';
import { UserProfile } from '../types/indicators';
import { DEFAULT_PROFILE, getSnapshot, readCache, writeCache, buildSnapshot, CACHE_TTL } from '../state/cache';
import { coverage, readHistory } from '../state/history-store';
import { getHistorySeries } from '../state/history-series';
import { fetchInsiderSummary } from '../collectors/smart-money';
import { fetchUpcomingEarnings } from '../collectors/earnings';
import { computeCorrelationMatrix } from '../engines/correlation';
import {
  appendTranche,
  clearAssetTranches,
  listTranches,
  summarizeByAsset,
  TrancheEntry,
} from '../services/trancheStore';
import { DEFAULT_TRANCHE_WEIGHTS } from '../engines/execution_plan';
import { readInvestmentPlan, writeInvestmentPlan, readRecentTradeLog, appendTradeLog, InvestmentPlan } from '../services/investment-plan';
import { buildWeeklyReport, detectRuleViolations, formatWeeklyReportText } from '../services/weekly-report';
import { fetchDomesticReportsLatest } from '../collectors/domestic-reports';
import { buildCompanyResearch, buildCompanyResearchLite } from '../services/company-research';
import { computeSectorQuality } from '../services/sector-quality';
import { getSectorDefinition } from '../engines/sector-classification';
import { searchSecCompanies } from '../collectors/sec/ticker-map';
import { getResearchSectorById, getResearchStandardSectors, getResearchThemeById, getResearchThemeForSectorKey, getResearchThemes, getResearchThemesForSectorKey, inferCompanyPeerGroup, ResearchThemeGroup } from '../services/company-peer-map';
import { buildBottleneckTheme, listBottleneckThemes } from '../services/bottleneck-research';
import { buildAllNarrativeThemes, buildNarrativeTheme, listNarrativeThemes } from '../services/narrative-research';
import { findBottleneckCandidateByTicker } from '../domain/bottleneck/candidate-map';
import { buildAllCryptoResearch, buildCryptoMarketRegime, buildCryptoResearch, listCryptoAssets } from '../services/crypto-research';
import { readSourceCache, readSourceCacheWithin, writeSourceCache } from '../services/source-cache';
import { buildSectorRotationBacktest } from '../services/sector-rotation-backtest';

const router = Router();
const RESEARCH_COMPANY_LIST_CACHE_MS = 60 * 60 * 1000;
const RESEARCH_HIGHLIGHTS_CACHE_MS = 15 * 60 * 1000;
const COMPANY_DETAIL_ROUTE_CACHE_MS = 15 * 60 * 1000;
const COMPANY_SEARCH_ROUTE_CACHE_MS = 10 * 60 * 1000;
const RESEARCH_THEME_ROUTE_CACHE_MS = 15 * 60 * 1000;
const RESEARCH_SECTOR_DETAIL_ROUTE_CACHE_MS = 15 * 60 * 1000;
const RESEARCH_CRYPTO_ROUTE_CACHE_MS = 10 * 60 * 1000;
const inflightRouteCaches = new Map<string, Promise<any>>();

const COMPANY_FALLBACK_TICKERS = new Set([
  'NVDA','MSFT','GOOGL','META','AMZN','ORCL','ASML','TSM','VRT','ETN','AAPL','JPM','XOM','CAT','PG','NEE','LIN','HD','UNH',
  'TSLA','BKNG','MCD','CVX','COP','SLB','PLD','EQIX','DLR','AMT','NFLX','DIS','CMCSA','KO','PEP','COST','WMT','PM','LLY','JNJ','ABBV','ISRG',
  'CRM','NOW','PANW','CRWD','SNOW','QCOM','TXN','ANET','AMAT','KLAC',
  'V','MA','ICE','CME','PGR','CB',
  'EOG','MPC','PSX','WMB','TRGP',
  'SHW','ECL','NUE','DOW','CTVA',
  'SO','DUK','AEP','SRE',
  'SPG','WELL','PSA','CCI','CBRE','IRM',
]);

const COMPANY_NARRATIVE_TICKERS = new Set([
  'NVDA','AMD','AVGO','TSM','ASML','MRVL','AMZN','META','ORCL',
  'VRT','ETN','HUBB','NVT','GEV','PWR','TT','JCI','NEE','CEG','VST',
  'LMT','NOC','RTX','GD','LHX','GE',
  'JPM','BAC','GS','MS','BLK','V','MA','ICE','CME',
  'XOM','CVX','COP','EOG','SLB','BKR','HAL','MPC','PSX',
  'GOOGL','NFLX','TMUS','CMCSA','DIS','SPOT','ROKU',
  'AMZN','TSLA','HD','MCD','NKE','SBUX','BKNG','LOW','TJX','CMG',
  'PG','KO','PEP','WMT','COST','PM','MO','MDLZ',
  'LIN','APD','FCX','NEM','NUE','ALB','CF','MOS','MLM','VMC',
  'PLD','AMT','EQIX','DLR','CCI','O','WELL','VICI',
  'SO','DUK','AEP','SRE','EXC','AWK','WEC',
  'UNH','JNJ','MRK','ABBV','LLY','NVO','ISRG','TMO','DHR',
]);

function buildSectorDensitySummary(tickers: string[]) {
  const recognizedCapitalFlowGroups = new Set([
    'AI_SEMIS','MEGACAP_PLATFORM','SEMI_EQUIPMENT','POWER_INFRA','DEFENSE_AERO','HEALTHCARE_BIO','FINANCIALS','ENERGY_SUPPLY',
    'COMMUNICATION_MEDIA','CONSUMER_FRANCHISE','CONSUMER_STAPLES','MATERIALS_RESOURCES','UTILITIES_DEFENSIVE','REAL_ASSETS','INDUSTRIALS_CYCLICAL',
  ]);
  const list = [...new Set(tickers.map((item) => item.toUpperCase()))];
  const peer = list.filter((ticker) => Boolean(inferCompanyPeerGroup({ ticker }))).length;
  const fallback = list.filter((ticker) => COMPANY_FALLBACK_TICKERS.has(ticker)).length;
  const bottleneck = list.filter((ticker) => Boolean(findBottleneckCandidateByTicker(ticker))).length;
  const narrative = list.filter((ticker) => {
    const group = inferCompanyPeerGroup({ ticker });
    return COMPANY_NARRATIVE_TICKERS.has(ticker) || (group ? recognizedCapitalFlowGroups.has(group) : false);
  }).length;
  const capitalFlow = list.filter((ticker) => {
    const group = inferCompanyPeerGroup({ ticker });
    return group ? recognizedCapitalFlowGroups.has(group) : false;
  }).length;
  const pct = (value: number) => Math.round((value / Math.max(list.length, 1)) * 100);
  return {
    peer,
    peerPct: pct(peer),
    narrative,
    narrativePct: pct(narrative),
    fallback,
    fallbackPct: pct(fallback),
    bottleneck,
    bottleneckPct: pct(bottleneck),
    capitalFlow,
    capitalFlowPct: pct(capitalFlow),
  };
}


type ThemeSectorScore = {
  key: string;
  label: string;
  classification: string;
  momentumScore: number | null;
  qualityScore: number | null;
  policySupport: number | null;
  structuralDemand: number | null;
  supplyTightness: number | null;
  marketConcentration: number | null;
  appealScore: number | null;
  crowdingScore: number | null;
  buyScore: number | null;
  buyLabel: '매수 우호' | '선별 접근' | '추격 주의' | null;
  stance: 'favored' | 'avoided' | 'neutral';
  rotationScore?: number | null;
  rotationState?: 'LEADING' | 'IMPROVING' | 'WEAKENING' | 'LAGGING' | null;
  rotationLabel?: 'Rotation In' | 'Leader' | 'Late Leader' | 'Rotation Out' | 'Defensive Hold' | null;
  rotationReasons?: string[];
  bottomState?: '바닥 아님' | '바닥 시도' | '재시험 구간' | '1차 확인' | '구조적 바닥 가능' | null;
  bottomScore?: number | null;
  bottomFailureRiskScore?: number | null;
  actionLabel?: '대기' | '관찰 매수' | '1차 소액 진입' | '분할 매수' | '축소' | '회피';
  failureSummary?: string | null;
  buyScoreDelta7d?: number | null;
  buyScoreDelta30d?: number | null;
  buyScoreTrend?: Array<number | null>;
  avgVolumeConfirmationScore?: number | null;
};

type ResearchCompanyListItem = {
  ticker: string;
  name: string;
  marketCap: number | null;
  totalScore: number | null;
  buyScore: number | null;
  buyLabel: string | null;
  appealScore: number | null;
  crowdingScore: number | null;
  revenueGrowthYoY: number | null;
  operatingMargin: number | null;
  evToSales: number | null;
  sectorKey: string | null;
  bottomScore: number | null;
  priceBottomScore: number | null;
  volumeConfirmationScore: number | null;
  failureRiskScore: number | null;
  bottomState: ThemeSectorScore['bottomState'] | null;
  confirmedBottomScore: number | null;
  confirmedBottomState: '미충족' | '후보' | '확신' | null;
  error?: string;
};

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function averageNumbers(values: Array<number | null | undefined>): number | null {
  const list = values.filter((value): value is number => typeof value === 'number' && Number.isFinite(value));
  if (!list.length) return null;
  return Math.round(list.reduce((sum, value) => sum + value, 0) / list.length);
}

function deriveSectorBottomState(
  bottomScore: number | null,
  failureRiskScore: number | null,
  volumeConfirmationScore: number | null,
): NonNullable<ThemeSectorScore['bottomState']> {
  if ((volumeConfirmationScore ?? 0) >= 72 && (bottomScore ?? 0) >= 74 && (failureRiskScore ?? 100) < 45) return '구조적 바닥 가능';
  if ((volumeConfirmationScore ?? 0) >= 64 && (bottomScore ?? 0) >= 64) return '1차 확인';
  if ((volumeConfirmationScore ?? 0) >= 56 && (bottomScore ?? 0) >= 54) return '재시험 구간';
  if ((volumeConfirmationScore ?? 0) >= 48 || (bottomScore ?? 0) >= 46) return '바닥 시도';
  return '바닥 아님';
}

function deriveSectorBottomAction(
  failureRiskScore: number | null,
  state: NonNullable<ThemeSectorScore['bottomState']>,
): NonNullable<ThemeSectorScore['actionLabel']> {
  if ((failureRiskScore ?? 0) >= 78) return '회피';
  if ((failureRiskScore ?? 0) >= 64) return '축소';
  if (state === '구조적 바닥 가능') return '분할 매수';
  if (state === '1차 확인') return '1차 소액 진입';
  if (state === '재시험 구간') return '관찰 매수';
  if (state === '바닥 시도') return '대기';
  return '회피';
}

function buildSectorFailureSummary(
  state: NonNullable<ThemeSectorScore['bottomState']>,
  failureRiskScore: number | null,
  volumeConfirmationScore: number | null,
  coverageRatio: number,
): string {
  if ((failureRiskScore ?? 0) >= 78) return '거래량 확인이 부족하고 실패 위험이 높아 지금은 회피가 우선입니다.';
  if ((failureRiskScore ?? 0) >= 64) return '바닥 시도는 있어도 재차 꺾일 수 있어 비중 축소·대기가 우선입니다.';
  if ((volumeConfirmationScore ?? 0) < 55) return '반등 대비 거래량이 약해 아직 진짜 바닥보다 기술적 반등 가능성을 더 열어둬야 합니다.';
  if (coverageRatio < 0.45) return '거래량 바닥 신호가 붙은 대표 종목 수가 아직 적어 섹터 확산 여부를 더 확인해야 합니다.';
  if (state === '구조적 바닥 가능') return '대표 종목 다수에서 거래량이 붙은 확인이 나와 섹터 바닥 신뢰도가 높은 편입니다.';
  if (state === '1차 확인') return '대표 종목 전반에 거래량이 붙기 시작해 섹터 바닥 확인 신뢰도가 올라오는 구간입니다.';
  if (state === '재시험 구간') return '가격은 버티지만 거래량 확산이 아직 충분치 않아 재시험 통과를 더 봐야 합니다.';
  return '반등은 나왔지만 거래량 확산이 충분치 않아 아직 초기 바닥 시도로 보는 편이 안전합니다.';
}

function buildThemeSectorScores(theme: ResearchThemeGroup, snapshot: Awaited<ReturnType<typeof getSnapshot>>): ThemeSectorScore[] {
  const topdownSectors = [
    ...(snapshot.meta.topdown?.favoredSectors ?? []),
    ...(snapshot.meta.topdown?.avoidedSectors ?? []),
  ];
  const rotationSectors = snapshot.meta.topdown?.rotation?.sectors ?? [];

  return theme.sectorKeys.map((key) => {
    const definition = getSectorDefinition(key);
    if (!definition) {
      return {
        key,
        label: key.replace('SECTOR_', ''),
        classification: 'neutral',
        momentumScore: snapshot.derived[key]?.value ?? null,
        qualityScore: null,
        policySupport: null,
        structuralDemand: null,
        supplyTightness: null,
        marketConcentration: null,
        appealScore: null,
        crowdingScore: null,
        buyScore: null,
        buyLabel: null,
        stance: 'neutral' as const,
      };
    }
    const topdown = topdownSectors.find((item) => item.key === key);
    const rotation = rotationSectors.find((item) => item.key === key);
    const momentumScore = snapshot.derived[key]?.value ?? null;
    const quality = computeSectorQuality(definition, snapshot.raw, snapshot.derived, snapshot.regime);
    const appealScore = topdown?.appealScore ?? Math.round(clamp(quality.totalScore * 0.72 + (momentumScore === null ? 45 : clamp(50 + momentumScore * 4, 10, 90)) * 0.28, 0, 100));
    const crowdingBase = topdown?.crowdingScore ?? (() => {
      let base = 18;
      if (momentumScore !== null) base += clamp((momentumScore - 4) * 4.5, 0, 28);
      if ((snapshot.derived.OVERHEATED?.value ?? 0) === 1) base += 14;
      return Math.round(clamp(base, 0, 100));
    })();
    const buyScore = topdown?.buyScore ?? Math.round(clamp(appealScore * 0.68 + (100 - crowdingBase) * 0.32, 0, 100));
    const buyLabel = topdown?.buyLabel ?? (buyScore >= 68 ? '매수 우호' : buyScore >= 52 ? '선별 접근' : '추격 주의');
    const bottomScore = Math.round(clamp(
      (quality.totalScore * 0.34)
      + (appealScore * 0.24)
      + ((100 - crowdingBase) * 0.18)
      + ((momentumScore === null ? 50 : clamp(50 + momentumScore * 4, 10, 90)) * 0.24),
      0,
      100,
    ));
    const bottomFailureRiskScore = Math.round(clamp(
      24
      + (crowdingBase >= 70 ? 22 : crowdingBase >= 55 ? 10 : 0)
      + (momentumScore !== null && momentumScore <= -4 ? 18 : 0)
      + (buyScore < 45 ? 16 : buyScore < 55 ? 8 : 0),
      0,
      100,
    ));
    const bottomState: ThemeSectorScore['bottomState'] =
      bottomScore >= 76 && bottomFailureRiskScore < 45 ? '구조적 바닥 가능'
        : bottomScore >= 66 ? '1차 확인'
          : bottomScore >= 54 ? '재시험 구간'
            : bottomScore >= 46 ? '바닥 시도'
              : '바닥 아님';
    const actionLabel: ThemeSectorScore['actionLabel'] =
      bottomFailureRiskScore >= 78 ? '회피'
        : bottomFailureRiskScore >= 64 ? '축소'
          : bottomState === '구조적 바닥 가능' ? '분할 매수'
            : bottomState === '1차 확인' ? '1차 소액 진입'
              : bottomState === '재시험 구간' ? '관찰 매수'
                : bottomState === '바닥 시도' ? '대기'
                  : '회피';
    const failureSummary =
      bottomFailureRiskScore >= 78 ? '과열/약세가 겹쳐 지금은 회피가 우선입니다.'
        : bottomFailureRiskScore >= 64 ? '재시험 실패나 모멘텀 훼손 가능성이 있어 비중 축소가 우선입니다.'
          : crowdingBase >= 70 ? '구조는 나쁘지 않아도 과열이 높아 추격보다 눌림 확인이 우선입니다.'
            : momentumScore !== null && momentumScore <= -4 ? '구조는 있어도 단기 모멘텀이 약해 재시험 가능성을 열어둬야 합니다.'
              : '구조와 타이밍이 크게 충돌하지 않습니다.';
    return {
      key,
      label: definition?.label ?? key.replace('SECTOR_', ''),
      classification: definition?.classification ?? 'neutral',
      momentumScore,
      qualityScore: quality.totalScore,
      policySupport: quality.policySupport,
      structuralDemand: quality.structuralDemand,
      supplyTightness: quality.supplyTightness,
      marketConcentration: quality.marketConcentration,
      appealScore,
      crowdingScore: crowdingBase,
      buyScore,
      buyLabel,
      stance: topdown?.stance ?? 'neutral',
      rotationScore: rotation?.rotationScore ?? null,
      rotationState: rotation?.state ?? null,
      rotationLabel: rotation?.rotationLabel ?? null,
      rotationReasons: rotation?.reasons ?? [],
      bottomState,
      bottomScore,
      bottomFailureRiskScore,
      actionLabel,
      failureSummary,
    };
  }).sort((a, b) => (b.buyScore ?? -1) - (a.buyScore ?? -1));
}

function buildThemeSectorSummary(scores: ThemeSectorScore[]) {
  if (!scores.length) return null;
  return {
    averageBuyScore: averageNumbers(scores.map((item) => item.buyScore)),
    averageBottomScore: averageNumbers(scores.map((item) => item.bottomScore ?? null)),
    averageBottomFailureRiskScore: averageNumbers(scores.map((item) => item.bottomFailureRiskScore ?? null)),
    averageVolumeConfirmationScore: averageNumbers(scores.map((item) => item.avgVolumeConfirmationScore ?? null)),
    averageAppealScore: averageNumbers(scores.map((item) => item.appealScore)),
    averageCrowdingScore: averageNumbers(scores.map((item) => item.crowdingScore)),
    averageQualityScore: averageNumbers(scores.map((item) => item.qualityScore)),
    averageRotationScore: averageNumbers(scores.map((item) => item.rotationScore ?? null)),
    topSector: scores[0] ?? null,
  };
}

function enrichThemeSectorScoresWithCompanyBottom(
  scores: ThemeSectorScore[],
  items: ResearchCompanyListItem[],
): ThemeSectorScore[] {
  return scores.map((score) => {
    const sectorItems = items.filter((item) => item.sectorKey === score.key);
    const coverageRatio = sectorItems.length / Math.max(items.length, 1);
    if (!sectorItems.length) return score;

    const avgVolume = averageNumbers(sectorItems.map((item) => item.volumeConfirmationScore));
    const avgPrice = averageNumbers(sectorItems.map((item) => item.priceBottomScore));
    const avgBottom = averageNumbers(sectorItems.map((item) => item.bottomScore));
    const avgFailure = averageNumbers(sectorItems.map((item) => item.failureRiskScore));
    const avgBuy = averageNumbers(sectorItems.map((item) => item.buyScore));
    const companyWeightedParts = [
      avgVolume === null ? null : { value: avgVolume, weight: 0.42 },
      avgPrice === null ? null : { value: avgPrice, weight: 0.24 },
      avgBottom === null ? null : { value: avgBottom, weight: 0.22 },
      avgFailure === null ? null : { value: 100 - avgFailure, weight: 0.12 },
    ].filter((item): item is { value: number; weight: number } => Boolean(item));
    const companyWeightSum = companyWeightedParts.reduce((sum, item) => sum + item.weight, 0);
    const companyDrivenBottom = companyWeightSum > 0
      ? Math.round(clamp(
          companyWeightedParts.reduce((sum, item) => sum + item.value * item.weight, 0) / companyWeightSum,
          0,
          100,
        ))
      : null;

    const blendedBottom = companyDrivenBottom === null
      ? score.bottomScore ?? null
      : Math.round(clamp(
          ((score.bottomScore ?? companyDrivenBottom) * 0.32)
          + (companyDrivenBottom * 0.68),
          0,
          100,
        ));
    const blendedFailure = avgFailure === null
      ? score.bottomFailureRiskScore ?? null
      : Math.round(clamp(
          ((score.bottomFailureRiskScore ?? avgFailure) * 0.35)
          + (avgFailure * 0.65),
          0,
          100,
        ));
    const state = deriveSectorBottomState(blendedBottom, blendedFailure, avgVolume);
    const actionLabel = deriveSectorBottomAction(blendedFailure, state);

    return {
      ...score,
      buyScore: avgBuy ?? score.buyScore,
      bottomScore: blendedBottom,
      bottomFailureRiskScore: blendedFailure,
      avgVolumeConfirmationScore: avgVolume,
      bottomState: state,
      actionLabel,
      failureSummary: buildSectorFailureSummary(state, blendedFailure, avgVolume, coverageRatio),
    };
  });
}

async function readCachedSectorCompanyItems(
  tickers: string[],
  fallbackSectorKey: string,
): Promise<ResearchCompanyListItem[]> {
  return Promise.all(tickers.map(async (ticker) => {
    const normalized = ticker.trim().toUpperCase();
    try {
      const cached = await readSourceCache<any>(`company-research-lite-${normalized}`);
      const research = cached?.value;
      if (!research) {
        return {
          ticker: normalized,
          name: normalized,
          marketCap: null,
          totalScore: null,
          buyScore: null,
          buyLabel: null,
          appealScore: null,
          crowdingScore: null,
          revenueGrowthYoY: null,
          operatingMargin: null,
          evToSales: null,
          sectorKey: fallbackSectorKey,
          bottomScore: null,
          priceBottomScore: null,
          volumeConfirmationScore: null,
          failureRiskScore: null,
          bottomState: null,
          confirmedBottomScore: null,
          confirmedBottomState: null,
        };
      }
      return {
        ticker: research.profile.ticker,
        name: research.profile.name,
        marketCap: research.financials.marketCap,
        totalScore: research.score.totalScore,
        buyScore: research.buyScore.buyScore,
        buyLabel: research.buyScore.label,
        appealScore: research.buyScore.appealScore,
        crowdingScore: research.buyScore.crowdingScore,
        revenueGrowthYoY: research.financials.revenueGrowthYoY,
        operatingMargin: research.financials.operatingMargin,
        evToSales: research.financials.evToSales,
        sectorKey: research.sectorContext?.sectorKey ?? fallbackSectorKey,
        bottomScore: research.bottomSignal?.score ?? null,
        priceBottomScore: research.bottomSignal?.priceBottomScore ?? null,
        volumeConfirmationScore: research.bottomSignal?.volumeConfirmationScore ?? null,
        failureRiskScore: research.bottomSignal?.failureRiskScore ?? null,
        bottomState: research.bottomSignal?.state ?? null,
        confirmedBottomScore: research.bottomSignal?.confirmedBottom?.score ?? null,
        confirmedBottomState: research.bottomSignal?.confirmedBottom?.state ?? null,
      };
    } catch (error: any) {
      return {
        ticker: normalized,
        name: normalized,
        marketCap: null,
        totalScore: null,
        buyScore: null,
        buyLabel: null,
        appealScore: null,
        crowdingScore: null,
        revenueGrowthYoY: null,
        operatingMargin: null,
        evToSales: null,
        sectorKey: fallbackSectorKey,
        bottomScore: null,
        priceBottomScore: null,
        volumeConfirmationScore: null,
        failureRiskScore: null,
        bottomState: null,
        confirmedBottomScore: null,
        confirmedBottomState: null,
        error: error?.message || 'failed',
      };
    }
  }));
}


async function findClosestHistoricalValue(source: string, key: string, daysAgo: number): Promise<number | null> {
  try {
    const history = await readHistory(source, key);
    if (!history.length) return null;
    const target = Date.now() - daysAgo * 24 * 60 * 60 * 1000;
    let best: { value: number; distance: number } | null = null;
    for (const point of history) {
      const time = new Date(point.date).getTime();
      const distance = Math.abs(time - target);
      if (!Number.isFinite(time)) continue;
      if (!best || distance < best.distance) best = { value: point.value, distance };
    }
    return best?.value ?? null;
  } catch {
    return null;
  }
}


function buildResearchCompanyUniverse() {
  const themeEntries = getResearchThemes();
  const sectorEntries = getResearchStandardSectors();
  const byTicker = new Map<string, {
    ticker: string;
    themeIds: string[];
    themeNames: string[];
    sectorIds: string[];
    sectorNames: string[];
  }>();
  for (const theme of themeEntries) {
    for (const ticker of theme.tickers) {
      const key = ticker.toUpperCase();
      const existing = byTicker.get(key);
      if (existing) {
        if (!existing.themeIds.includes(theme.id)) existing.themeIds.push(theme.id);
        if (!existing.themeNames.includes(theme.theme)) existing.themeNames.push(theme.theme);
      } else {
        byTicker.set(key, {
          ticker: key,
          themeIds: [theme.id],
          themeNames: [theme.theme],
          sectorIds: [],
          sectorNames: [],
        });
      }
    }
  }
  for (const sector of sectorEntries) {
    for (const ticker of sector.tickers) {
      const key = ticker.toUpperCase();
      const existing = byTicker.get(key);
      if (existing) {
        if (!existing.sectorIds.includes(sector.id)) existing.sectorIds.push(sector.id);
        if (!existing.sectorNames.includes(sector.label)) existing.sectorNames.push(sector.label);
      } else {
        byTicker.set(key, {
          ticker: key,
          themeIds: [],
          themeNames: [],
          sectorIds: [sector.id],
          sectorNames: [sector.label],
        });
      }
    }
  }
  return [...byTicker.values()];
}

async function mapWithConcurrency<T, R>(
  items: T[],
  limit: number,
  mapper: (item: T, index: number) => Promise<R>,
): Promise<R[]> {
  const results = new Array<R>(items.length);
  let cursor = 0;

  async function worker() {
    while (true) {
      const index = cursor++;
      if (index >= items.length) return;
      results[index] = await mapper(items[index], index);
    }
  }

  const workerCount = Math.max(1, Math.min(limit, items.length));
  await Promise.all(Array.from({ length: workerCount }, () => worker()));
  return results;
}

async function getOrBuildRouteCache<T>(
  key: string,
  freshMs: number,
  builder: () => Promise<T>,
): Promise<T> {
  const fresh = await readSourceCacheWithin<T>(key, freshMs);
  if (fresh?.value) return fresh.value;

  const existing = inflightRouteCaches.get(key) as Promise<T> | undefined;
  if (existing) return existing;

  const task = builder()
    .then(async (value) => {
      await writeSourceCache(key, value);
      return value;
    })
    .catch(async (error) => {
      const stale = await readSourceCache<T>(key);
      if (stale?.value) return stale.value;
      throw error;
    })
    .finally(() => {
      inflightRouteCaches.delete(key);
    });

  inflightRouteCaches.set(key, task);
  return task;
}

async function buildResearchCompanyListItems() {
  const universe = buildResearchCompanyUniverse();
  return mapWithConcurrency(universe, 6, async (entry) => {
    try {
      const research = await buildCompanyResearchLite(entry.ticker);
      return {
        ticker: research.profile.ticker,
        name: research.profile.name,
        peerGroup: research.peerGroup ?? null,
        themeIds: entry.themeIds,
        themeNames: entry.themeNames,
        sectorIds: entry.sectorIds,
        sectorNames: entry.sectorNames,
        totalScore: research.score.totalScore,
        buyScore: research.buyScore.buyScore,
        buyLabel: research.buyScore.label,
        appealScore: research.buyScore.appealScore,
        crowdingScore: research.buyScore.crowdingScore,
        revenueGrowthYoY: research.financials.revenueGrowthYoY,
        operatingMargin: research.financials.operatingMargin,
        evToSales: research.financials.evToSales,
        narrativeStage: research.narrative?.stage ?? null,
        bottleneckConviction: research.bottleneck?.conviction ?? null,
        bottomState: research.bottomSignal?.state ?? null,
        earningsBottomScore: research.bottomSignal?.earningsBottomScore ?? null,
        priceBottomScore: research.bottomSignal?.priceBottomScore ?? null,
        volumeConfirmationScore: research.bottomSignal?.volumeConfirmationScore ?? null,
        bottomFailureRiskScore: research.bottomSignal?.failureRiskScore ?? null,
        confirmedBottomScore: research.bottomSignal?.confirmedBottom?.score ?? null,
        confirmedBottomState: research.bottomSignal?.confirmedBottom?.state ?? null,
      };
    } catch (error: any) {
      return {
        ticker: entry.ticker,
        name: entry.ticker,
        peerGroup: null,
        themeIds: entry.themeIds,
        themeNames: entry.themeNames,
        sectorIds: entry.sectorIds,
        sectorNames: entry.sectorNames,
        totalScore: null,
        buyScore: null,
        buyLabel: null,
        appealScore: null,
        crowdingScore: null,
        revenueGrowthYoY: null,
        operatingMargin: null,
        evToSales: null,
        narrativeStage: null,
        bottleneckConviction: null,
        bottomState: null,
        earningsBottomScore: null,
        priceBottomScore: null,
        volumeConfirmationScore: null,
        bottomFailureRiskScore: null,
        confirmedBottomScore: null,
        confirmedBottomState: null,
        error: error?.message || 'failed',
      };
    }
  });
}

async function buildThemeSectorScoreChanges(scores: ThemeSectorScore[]): Promise<ThemeSectorScore[]> {
  return Promise.all(scores.map(async (item) => {
    const suffix = item.key.replace('SECTOR_', '');
    const [quality7, quality30, momentum7, momentum30] = await Promise.all([
      findClosestHistoricalValue('derived', `SECTOR_QUALITY_TOTAL_${suffix}`, 7),
      findClosestHistoricalValue('derived', `SECTOR_QUALITY_TOTAL_${suffix}`, 30),
      findClosestHistoricalValue('derived', item.key, 7),
      findClosestHistoricalValue('derived', item.key, 30),
    ]);

    const estimateBuy = (quality: number | null, momentum: number | null) => {
      if (quality === null && momentum === null) return null;
      const q = quality ?? item.qualityScore ?? 50;
      const m = momentum ?? item.momentumScore ?? 0;
      const appeal = Math.round(clamp(q * 0.72 + clamp(50 + m * 4, 10, 90) * 0.28, 0, 100));
      const crowding = Math.round(clamp(18 + clamp((m - 4) * 4.5, 0, 28), 0, 100));
      return Math.round(clamp(appeal * 0.68 + (100 - crowding) * 0.32, 0, 100));
    };

    const prev7 = estimateBuy(quality7, momentum7);
    const prev30 = estimateBuy(quality30, momentum30);
    return {
      ...item,
      buyScoreDelta7d: prev7 === null || item.buyScore === null ? null : Math.round((item.buyScore - prev7) * 10) / 10,
      buyScoreDelta30d: prev30 === null || item.buyScore === null ? null : Math.round((item.buyScore - prev30) * 10) / 10,
      buyScoreTrend: [prev30, prev7, item.buyScore],
    };
  }));
}

function sortThemeSectorScores(scores: Array<ThemeSectorScore & { buyScoreDelta7d?: number | null; buyScoreDelta30d?: number | null }>, sort: string | undefined) {
  const key = sort === 'quality' || sort === 'momentum' || sort === 'crowding' || sort === 'delta7' || sort === 'delta30' ? sort : 'buy';
  const sorted = [...scores].sort((a, b) => {
    const va = key === 'quality' ? a.qualityScore : key === 'momentum' ? a.momentumScore : key === 'crowding' ? a.crowdingScore : key === 'delta7' ? a.buyScoreDelta7d : key === 'delta30' ? a.buyScoreDelta30d : a.buyScore;
    const vb = key === 'quality' ? b.qualityScore : key === 'momentum' ? b.momentumScore : key === 'crowding' ? b.crowdingScore : key === 'delta7' ? b.buyScoreDelta7d : key === 'delta30' ? b.buyScoreDelta30d : b.buyScore;
    return (vb ?? -999) - (va ?? -999);
  });
  return { sortKey: key, scores: sorted };
}


function sortThemeCompanyItems(items: ResearchCompanyListItem[], sort: string | undefined) {
  const key = sort === 'buy' || sort === 'growth' || sort === 'margin' || sort === 'valuation' || sort === 'marketcap' ? sort : 'priority';
  const marketCapScore = (marketCap: number | null) => {
    if (marketCap === null || !Number.isFinite(marketCap) || marketCap <= 0) return -9999;
    const trillions = marketCap / 1_000_000_000_000;
    return clamp(trillions * 35, 0, 100);
  };
  const priorityScore = (item: typeof items[number]) => {
    const buy = item.buyScore ?? 0;
    const quality = item.totalScore ?? 0;
    const size = marketCapScore(item.marketCap);
    return buy * 0.45 + quality * 0.35 + Math.max(size, 0) * 0.2;
  };
  const sorted = [...items].sort((a, b) => {
    const va = key === 'buy'
      ? a.buyScore
      : key === 'growth'
        ? a.revenueGrowthYoY
        : key === 'margin'
          ? a.operatingMargin
          : key === 'valuation'
            ? (a.evToSales === null ? null : -a.evToSales)
            : key === 'marketcap'
              ? a.marketCap
              : priorityScore(a);
    const vb = key === 'buy'
      ? b.buyScore
      : key === 'growth'
        ? b.revenueGrowthYoY
        : key === 'margin'
          ? b.operatingMargin
          : key === 'valuation'
            ? (b.evToSales === null ? null : -b.evToSales)
            : key === 'marketcap'
              ? b.marketCap
              : priorityScore(b);
    return (vb ?? -9999) - (va ?? -9999);
  }).map((item, index) => ({ ...item, rank: index + 1 }));
  return { sortKey: key, items: sorted };
}

router.get('/snapshot', async (_req: Request, res: Response) => {
  try {
    const snapshot = await getSnapshot(DEFAULT_PROFILE);
    res.json(snapshot);
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.post('/snapshot', async (req: Request, res: Response) => {
  try {
    const body = req.body || {};
    const profile: UserProfile = {
      ...DEFAULT_PROFILE,
      ...body,
      manualInputs: {
        ...DEFAULT_PROFILE.manualInputs,
        ...(body.manualInputs || {}),
      },
    };

    const snapshot = await getSnapshot(profile, true);
    res.json(snapshot);
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.post('/refresh', async (_req: Request, res: Response) => {
  try {
    const snapshot = await buildSnapshot(DEFAULT_PROFILE);
    writeCache(snapshot);
    res.json(snapshot);
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/history/coverage', async (_req: Request, res: Response) => {
  try {
    res.json(await coverage());
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/history/:source/:key', async (req: Request, res: Response) => {
  try {
    const source = Array.isArray(req.params.source) ? req.params.source[0] : req.params.source;
    const key = Array.isArray(req.params.key) ? req.params.key[0] : req.params.key;
    const points = await readHistory(source, key);
    res.json({ source, key, count: points.length, points });
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/history-series', async (req: Request, res: Response) => {
  try {
    const keysParam = Array.isArray(req.query.keys) ? req.query.keys.join(',') : String(req.query.keys || '');
    const range = String(req.query.range || '1Y') as '1D' | '1W' | '1M' | '1Y' | '5Y';
    const interval = String(req.query.interval || '1D') as '1D' | '1W' | '1M';
    const keys = keysParam.split(',').map((k) => k.trim()).filter(Boolean);
    const series = await getHistorySeries(keys, range, interval);
    res.json({ keys, range, interval, series });
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/smart-money', async (_req: Request, res: Response) => {
  try {
    const insider = await fetchInsiderSummary();
    res.json({ insider });
  } catch (err: any) {
    res.status(500).json({ error: err.message });
  }
});

router.get('/company/:ticker', async (req: Request, res: Response) => {
  try {
    const ticker = String(Array.isArray(req.params.ticker) ? req.params.ticker[0] : req.params.ticker || '').trim().toUpperCase();
    if (!ticker) {
      res.status(400).json({ error: 'ticker is required' });
      return;
    }
    const research = await getOrBuildRouteCache(
      `route:company-detail:v1:${ticker}`,
      COMPANY_DETAIL_ROUTE_CACHE_MS,
      () => buildCompanyResearch(ticker),
    );
    res.json(research);
  } catch (err: any) {
    const message = err?.message || 'Internal server error';
    const status = /not found/i.test(message) ? 404 : 500;
    res.status(status).json({ error: message });
  }
});

router.get('/company-summaries', async (req: Request, res: Response) => {
  try {
    const tickersParam = String(req.query.tickers || '').trim();
    if (!tickersParam) {
      res.json({ items: [] });
      return;
    }
    const tickers = [...new Set(tickersParam.split(',').map((item) => item.trim().toUpperCase()).filter(Boolean))].slice(0, 20);
    const items = await getOrBuildRouteCache(
      `route:company-summaries:v1:${tickers.join(',')}`,
      COMPANY_DETAIL_ROUTE_CACHE_MS,
      async () => {
        const rows = await mapWithConcurrency(tickers, 6, async (ticker) => {
          try {
            const research = await buildCompanyResearchLite(ticker);
            return {
              ticker,
              name: research.profile.name,
              totalScore: research.score.totalScore,
              buyScore: research.buyScore.buyScore,
              buyLabel: research.buyScore.label,
              revenueGrowthYoY: research.financials.revenueGrowthYoY ?? null,
              operatingMargin: research.financials.operatingMargin ?? null,
              evToSales: research.financials.evToSales ?? null,
              crowdingScore: research.buyScore.crowdingScore ?? null,
              appealScore: research.buyScore.appealScore ?? null,
              bottomState: research.bottomSignal?.state ?? null,
              earningsBottomScore: research.bottomSignal?.earningsBottomScore ?? null,
              priceBottomScore: research.bottomSignal?.priceBottomScore ?? null,
              volumeConfirmationScore: research.bottomSignal?.volumeConfirmationScore ?? null,
              failureRiskScore: research.bottomSignal?.failureRiskScore ?? null,
            };
          } catch {
            return null;
          }
        });
        return rows.filter((row): row is NonNullable<typeof row> => row !== null);
      },
    );
    res.json({ items });
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/company-search', async (req: Request, res: Response) => {
  try {
    const q = String(req.query.q || '').trim();
    const limit = Math.max(1, Math.min(12, parseInt(String(req.query.limit || '8'), 10) || 8));
    if (q.length < 1) {
      res.json({ items: [] });
      return;
    }
    const items = await getOrBuildRouteCache(
      `route:company-search:v1:${q.toUpperCase()}:${limit}`,
      COMPANY_SEARCH_ROUTE_CACHE_MS,
      () => searchSecCompanies(q, limit),
    );
    res.json({ items });
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/research/themes', async (_req: Request, res: Response) => {
  try {
    const payload = await getOrBuildRouteCache(
      'route:research-themes:v1',
      RESEARCH_THEME_ROUTE_CACHE_MS,
      async () => {
        const snapshot = await getSnapshot(DEFAULT_PROFILE);
        const themes = getResearchThemes().map((theme) => {
          const sectorScores = buildThemeSectorScores(theme, snapshot);
          return {
            ...theme,
            sectorSummary: buildThemeSectorSummary(sectorScores),
          };
        });
        return { themes };
      },
    );
    res.json(payload);
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/research/sectors/backtest', async (req: Request, res: Response) => {
  try {
    const years = Math.max(3, Math.min(5, parseInt(String(req.query.years || '5'), 10) || 5));
    const payload = await getOrBuildRouteCache(
      `route:research-sectors-backtest:v1:${years}`,
      6 * 60 * 60 * 1000,
      async () => buildSectorRotationBacktest(years),
    );
    res.json(payload);
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/research/sectors', async (_req: Request, res: Response) => {
  try {
    const payload = await getOrBuildRouteCache(
      'route:research-sectors:v6',
      15 * 60 * 1000,
      async () => {
        const snapshot = await getSnapshot(DEFAULT_PROFILE);
        const sectors = await mapWithConcurrency(getResearchStandardSectors(), 3, async (sector) => {
          const pseudoTheme = {
            id: sector.id,
            theme: sector.label,
            description: sector.description,
            tickers: [],
            sectorKeys: [sector.sectorKey],
          };
          const cachedItems = await readCachedSectorCompanyItems(sector.tickers, sector.sectorKey);
          const sectorScores = enrichThemeSectorScoresWithCompanyBottom(
            buildThemeSectorScores(pseudoTheme, snapshot),
            cachedItems,
          );
          const sectorSummary = buildThemeSectorSummary(sectorScores);
          const relatedThemes = getResearchThemesForSectorKey(sector.sectorKey).map((theme) => ({
            id: theme.id,
            theme: theme.theme,
          }));
          const rotation = snapshot.meta.topdown?.rotation?.sectors.find((item) => item.key === sector.sectorKey) ?? null;
          return {
            ...sector,
            tickers: sector.tickers,
            sectorSummary,
            rotation,
            densitySummary: buildSectorDensitySummary(sector.tickers),
            relatedThemes,
          };
        });
        return {
          sectors,
          rotation: snapshot.meta.topdown?.rotation
            ? {
                regime: snapshot.meta.topdown.rotation.regime,
                confidence: snapshot.meta.topdown.rotation.confidence,
                summary: snapshot.meta.topdown.rotation.summary,
                favoredNext: snapshot.meta.topdown.rotation.favoredNext,
                fadingNext: snapshot.meta.topdown.rotation.fadingNext,
                currentLeaders: snapshot.meta.topdown.rotation.currentLeaders,
                nextCandidates: snapshot.meta.topdown.rotation.nextCandidates,
                secondaryCandidates: snapshot.meta.topdown.rotation.secondaryCandidates,
                fadingCandidates: snapshot.meta.topdown.rotation.fadingCandidates,
              }
            : null,
        };
      },
    );
    res.json(payload);
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/research/themes/:id', async (req: Request, res: Response) => {
  try {
    const id = String(Array.isArray(req.params.id) ? req.params.id[0] : req.params.id || '').trim();
    const theme = getResearchThemeById(id);
    if (!theme) {
      res.status(404).json({ error: 'theme not found' });
      return;
    }
    const sortRequested = String(req.query.sort || 'buy');
    const companySort = String(req.query.companySort || 'priority');
    const payload = await getOrBuildRouteCache(
      `route:research-theme-detail:v1:${id}:sort=${sortRequested}:companySort=${companySort}`,
      RESEARCH_THEME_ROUTE_CACHE_MS,
      async () => {

        const items = await mapWithConcurrency(theme.tickers, 6, async (ticker) => {
          try {
            const research = await buildCompanyResearchLite(ticker);
            return {
              ticker: research.profile.ticker,
              name: research.profile.name,
              marketCap: research.financials.marketCap,
              totalScore: research.score.totalScore,
              buyScore: research.buyScore.buyScore,
              buyLabel: research.buyScore.label,
              appealScore: research.buyScore.appealScore,
              crowdingScore: research.buyScore.crowdingScore,
              revenueGrowthYoY: research.financials.revenueGrowthYoY,
              operatingMargin: research.financials.operatingMargin,
              evToSales: research.financials.evToSales,
              sectorKey: research.sectorContext?.sectorKey ?? null,
              bottomScore: research.bottomSignal?.score ?? null,
              priceBottomScore: research.bottomSignal?.priceBottomScore ?? null,
              volumeConfirmationScore: research.bottomSignal?.volumeConfirmationScore ?? null,
              failureRiskScore: research.bottomSignal?.failureRiskScore ?? null,
              bottomState: research.bottomSignal?.state ?? null,
              confirmedBottomScore: research.bottomSignal?.confirmedBottom?.score ?? null,
              confirmedBottomState: research.bottomSignal?.confirmedBottom?.state ?? null,
            };
          } catch (error: any) {
            return {
              ticker,
              name: ticker,
              marketCap: null,
              totalScore: null,
              buyScore: null,
              buyLabel: null,
              appealScore: null,
              crowdingScore: null,
              revenueGrowthYoY: null,
              operatingMargin: null,
              evToSales: null,
              sectorKey: null,
              bottomScore: null,
              priceBottomScore: null,
              volumeConfirmationScore: null,
              failureRiskScore: null,
              bottomState: null,
              confirmedBottomScore: null,
              confirmedBottomState: null,
              error: error?.message || 'failed',
            };
          }
        });

        const { sortKey: companySortKey, items: ranked } = sortThemeCompanyItems(items, companySort);

        const snapshot = await getSnapshot(DEFAULT_PROFILE);
        const sectorScoresBase = buildThemeSectorScores(theme, snapshot);
        const sectorScoresWithBottom = enrichThemeSectorScoresWithCompanyBottom(sectorScoresBase, items);
        const sectorScoresWithChanges = await buildThemeSectorScoreChanges(sectorScoresWithBottom);
        const { sortKey, scores: sectorScores } = sortThemeSectorScores(sectorScoresWithChanges, sortRequested);
        const sectorSummary = buildThemeSectorSummary(sectorScoresWithChanges);

        return { theme, items: ranked, sectorScores, sectorSummary, sortKey, companySortKey };
      },
    );
    res.json(payload);
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/research/sectors/:id', async (req: Request, res: Response) => {
  try {
    const id = String(Array.isArray(req.params.id) ? req.params.id[0] : req.params.id || '').trim();
    const sector = getResearchSectorById(id);
    if (!sector) {
      res.status(404).json({ error: 'sector not found' });
      return;
    }
    const payload = await getOrBuildRouteCache(
      `route:research-sector-detail:v1:${id}`,
      RESEARCH_SECTOR_DETAIL_ROUTE_CACHE_MS,
      async () => {
        const snapshot = await getSnapshot(DEFAULT_PROFILE);
        const pseudoTheme = {
          id: sector.id,
          theme: sector.label,
          description: sector.description,
          tickers: [],
          sectorKeys: [sector.sectorKey],
        };
        const sectorScoresBase = buildThemeSectorScores(pseudoTheme, snapshot);
        const relatedThemes = getResearchThemesForSectorKey(sector.sectorKey);
        const items = await mapWithConcurrency(sector.tickers, 6, async (ticker) => {
          try {
            const research = await buildCompanyResearchLite(ticker);
            return {
              ticker: research.profile.ticker,
              name: research.profile.name,
              marketCap: research.financials.marketCap,
              totalScore: research.score.totalScore,
              buyScore: research.buyScore.buyScore,
              buyLabel: research.buyScore.label,
              appealScore: research.buyScore.appealScore,
              crowdingScore: research.buyScore.crowdingScore,
              revenueGrowthYoY: research.financials.revenueGrowthYoY,
              operatingMargin: research.financials.operatingMargin,
              evToSales: research.financials.evToSales,
              sectorKey: research.sectorContext?.sectorKey ?? null,
              bottomScore: research.bottomSignal?.score ?? null,
              priceBottomScore: research.bottomSignal?.priceBottomScore ?? null,
              volumeConfirmationScore: research.bottomSignal?.volumeConfirmationScore ?? null,
              failureRiskScore: research.bottomSignal?.failureRiskScore ?? null,
              bottomState: research.bottomSignal?.state ?? null,
              confirmedBottomScore: research.bottomSignal?.confirmedBottom?.score ?? null,
              confirmedBottomState: research.bottomSignal?.confirmedBottom?.state ?? null,
            };
          } catch (error: any) {
            return {
              ticker,
              name: ticker,
              marketCap: null,
              totalScore: null,
              buyScore: null,
              buyLabel: null,
              appealScore: null,
              crowdingScore: null,
              revenueGrowthYoY: null,
              operatingMargin: null,
              evToSales: null,
              sectorKey: null,
              bottomScore: null,
              priceBottomScore: null,
              volumeConfirmationScore: null,
              failureRiskScore: null,
              bottomState: null,
              confirmedBottomScore: null,
              confirmedBottomState: null,
              error: error?.message || 'failed',
            };
          }
        });

        const sectorScoresWithBottom = enrichThemeSectorScoresWithCompanyBottom(sectorScoresBase, items);
        const sectorScores = await buildThemeSectorScoreChanges(sectorScoresWithBottom);
        const sectorSummary = buildThemeSectorSummary(sectorScores);

        const { items: ranked, sortKey } = sortThemeCompanyItems(items, 'priority');
        const rotation = snapshot.meta.topdown?.rotation?.sectors.find((item) => item.key === sector.sectorKey) ?? null;
        return {
          sector,
          sortKey,
          relatedThemes: relatedThemes.map((theme) => ({ id: theme.id, theme: theme.theme })),
          sectorScores,
          sectorSummary,
          rotation,
          rotationSummary: snapshot.meta.topdown?.rotation
            ? {
                regime: snapshot.meta.topdown.rotation.regime,
                confidence: snapshot.meta.topdown.rotation.confidence,
                summary: snapshot.meta.topdown.rotation.summary,
                favoredNext: snapshot.meta.topdown.rotation.favoredNext,
                fadingNext: snapshot.meta.topdown.rotation.fadingNext,
                currentLeaders: snapshot.meta.topdown.rotation.currentLeaders,
                nextCandidates: snapshot.meta.topdown.rotation.nextCandidates,
                secondaryCandidates: snapshot.meta.topdown.rotation.secondaryCandidates,
                fadingCandidates: snapshot.meta.topdown.rotation.fadingCandidates,
              }
            : null,
          densitySummary: buildSectorDensitySummary(sector.tickers),
          items: ranked,
        };
      },
    );
    res.json(payload);
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/bottleneck/themes', async (_req: Request, res: Response) => {
  res.json({ themes: listBottleneckThemes() });
});

router.get('/bottleneck/themes/:id', async (req: Request, res: Response) => {
  try {
    const id = String(Array.isArray(req.params.id) ? req.params.id[0] : req.params.id || '').trim();
    const result = await buildBottleneckTheme(id);
    res.json(result);
  } catch (err: any) {
    const message = err?.message || 'Internal server error';
    const status = /not found/i.test(message) ? 404 : 500;
    res.status(status).json({ error: message });
  }
});

router.get('/narrative/themes', async (_req: Request, res: Response) => {
  res.json({ themes: listNarrativeThemes() });
});

router.get('/narrative/themes/:id', async (req: Request, res: Response) => {
  try {
    const id = String(Array.isArray(req.params.id) ? req.params.id[0] : req.params.id || '').trim();
    const result = await buildNarrativeTheme(id);
    res.json(result);
  } catch (err: any) {
    const message = err?.message || 'Internal server error';
    const status = /not found/i.test(message) ? 404 : 500;
    res.status(status).json({ error: message });
  }
});

router.get('/narrative/overview', async (_req: Request, res: Response) => {
  try {
    const themes = await buildAllNarrativeThemes();
    res.json({ themes });
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/research/crypto', async (_req: Request, res: Response) => {
  try {
    const payload = await getOrBuildRouteCache(
      'route:research-crypto:v1',
      RESEARCH_CRYPTO_ROUTE_CACHE_MS,
      async () => {
        const [items, marketRegime] = await Promise.all([buildAllCryptoResearch(), buildCryptoMarketRegime()]);
        return {
          items,
          marketRegime,
          assets: listCryptoAssets().map((asset) => ({
            symbol: asset.symbol,
            name: asset.name,
            category: asset.category,
            narrativeTheme: asset.narrativeTheme,
          })),
        };
      },
    );
    res.json(payload);
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/research/crypto/:symbol', async (req: Request, res: Response) => {
  try {
    const symbol = String(Array.isArray(req.params.symbol) ? req.params.symbol[0] : req.params.symbol || '').trim().toUpperCase();
    const item = await getOrBuildRouteCache(
      `route:research-crypto-detail:v1:${symbol}`,
      RESEARCH_CRYPTO_ROUTE_CACHE_MS,
      () => buildCryptoResearch(symbol),
    );
    res.json(item);
  } catch (err: any) {
    const message = err?.message || 'Internal server error';
    const status = /not found/i.test(message) ? 404 : 500;
    res.status(status).json({ error: message });
  }
});



router.get('/research/companies', async (req: Request, res: Response) => {
  try {
    const sort = String(req.query.sort || 'buy');
    const q = String(req.query.q || '').trim().toUpperCase();
    const themeId = String(req.query.themeId || '').trim();
    const sectorId = String(req.query.sectorId || '').trim();
    const page = Math.max(1, parseInt(String(req.query.page || '1'), 10) || 1);
    const pageSize = Math.max(10, Math.min(100, parseInt(String(req.query.pageSize || '20'), 10) || 20));
    const items = await getOrBuildRouteCache(
      'research-companies-list-v1',
      RESEARCH_COMPANY_LIST_CACHE_MS,
      buildResearchCompanyListItems,
    );
    const filteredItems = items.filter((item) => {
      if (themeId && !item.themeIds.includes(themeId)) return false;
      if (sectorId && !item.sectorIds.includes(sectorId)) return false;
      if (!q) return true;
      return item.ticker.includes(q)
        || item.themeNames.some((name) => name.toUpperCase().includes(q))
        || item.sectorNames.some((name) => name.toUpperCase().includes(q));
    });

    const ranked = [...filteredItems].sort((a, b) => {
      const value = (item: typeof a) => {
        switch (sort) {
          case 'total': return item.totalScore ?? -1;
          case 'growth': return item.revenueGrowthYoY ?? -999;
          case 'margin': return item.operatingMargin ?? -999;
          case 'value': return item.evToSales === null ? -999 : -item.evToSales;
          case 'appeal': return item.appealScore ?? -1;
          default: return item.buyScore ?? -1;
        }
      };
      return value(b) - value(a);
    });

    const total = ranked.length;
    const totalPages = Math.max(1, Math.ceil(total / pageSize));
    const normalizedPage = Math.min(page, totalPages);
    const start = (normalizedPage - 1) * pageSize;
    const pagedItems = ranked.slice(start, start + pageSize);

    res.json({
      items: pagedItems,
      sortKey: sort,
      total,
      page: normalizedPage,
      pageSize,
      totalPages,
      themes: getResearchThemes().map((theme) => ({ id: theme.id, theme: theme.theme })),
      sectors: getResearchStandardSectors().map((sector) => ({ id: sector.id, label: sector.label })),
    });
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/research/highlights', async (_req: Request, res: Response) => {
  try {
    const payload = await getOrBuildRouteCache(
      'research-highlights-v1',
      RESEARCH_HIGHLIGHTS_CACHE_MS,
      async () => {
        const snapshot = await getSnapshot(DEFAULT_PROFILE);
        const sectorBase = [
          ...(snapshot.meta.topdown?.favoredSectors ?? []),
          ...(snapshot.meta.topdown?.avoidedSectors ?? []),
        ]
          .filter((item) => typeof item.buyScore === 'number')
          .sort((a, b) => (b.buyScore ?? -1) - (a.buyScore ?? -1))
          .slice(0, 6)
          .map((item) => ({
            key: item.key,
            label: item.label,
            themeId: getResearchThemeForSectorKey(item.key)?.id ?? null,
            classification: item.classification ?? 'neutral',
            momentumScore: item.score ?? null,
            qualityScore: item.quality?.totalScore ?? null,
            policySupport: item.quality?.policySupport ?? null,
            structuralDemand: item.quality?.structuralDemand ?? null,
            supplyTightness: item.quality?.supplyTightness ?? null,
            marketConcentration: item.quality?.marketConcentration ?? null,
            appealScore: item.appealScore ?? null,
            crowdingScore: item.crowdingScore ?? null,
            buyScore: item.buyScore ?? null,
            buyLabel: item.buyLabel ?? null,
            stance: item.stance,
          }));
        const sectors = await buildThemeSectorScoreChanges(sectorBase as ThemeSectorScore[]);

        const uniqueTickers = [...new Set(getResearchThemes().flatMap((theme) => theme.tickers))].slice(0, 30);
        const companyItems = await mapWithConcurrency(uniqueTickers, 6, async (ticker) => {
          try {
            const research = await buildCompanyResearchLite(ticker);
            return {
              ticker: research.profile.ticker,
              name: research.profile.name,
              buyScore: research.buyScore.buyScore,
              buyLabel: research.buyScore.label,
              totalScore: research.score.totalScore,
              revenueGrowthYoY: research.financials.revenueGrowthYoY,
              estimateRevision7d: research.financials.estimateRevision7d ?? null,
              estimateRevision30d: research.financials.estimateRevision30d ?? null,
              companyAction: research.positionSizing?.action ?? null,
              companyActionLabel: research.executionBridge?.companyActionLabel ?? null,
              linkedAsset: research.executionBridge?.asset ?? null,
              assetAction: research.executionBridge?.actionLabel ?? null,
              executionAlignment: research.executionBridge?.alignment ?? null,
              recommendationSummary: research.bottomSignal?.failureRiskScore !== undefined && research.bottomSignal.failureRiskScore !== null
                ? research.bottomSignal.failureRiskScore >= 70
                  ? '바닥 실패 위험이 높아 지금은 대기/축소가 우선입니다.'
                  : research.bottomSignal.state === '구조적 바닥 가능'
                    ? '실적·주가·거래량이 함께 받쳐 분할 매수 후보에 가깝습니다.'
                    : research.bottomSignal.state === '1차 확인'
                      ? '1차 확인은 나왔지만 추세 연장 확인 전까지는 분할 접근이 적절합니다.'
                      : research.bottomSignal.state === '재시험 구간'
                        ? '재시험 구간이라 성급한 추격보다 확인 후 접근이 낫습니다.'
                        : research.executionBridge?.summary ?? research.verdicts?.oneLiners?.action ?? null
                : research.executionBridge?.summary ?? research.verdicts?.oneLiners?.action ?? null,
          bottomState: research.bottomSignal?.state ?? null,
          earningsBottomScore: research.bottomSignal?.earningsBottomScore ?? null,
          priceBottomScore: research.bottomSignal?.priceBottomScore ?? null,
          volumeConfirmationScore: research.bottomSignal?.volumeConfirmationScore ?? null,
          bottomFailureRiskScore: research.bottomSignal?.failureRiskScore ?? null,
            };
          } catch {
            return null;
          }
        });

        const companies = companyItems
          .filter((item): item is NonNullable<typeof item> => item !== null)
          .sort((a, b) => (b.buyScore ?? -1) - (a.buyScore ?? -1))
          .slice(0, 6);

        return { sectors, companies };
      },
    );

    res.json(payload);
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/earnings', async (_req: Request, res: Response) => {
  try {
    const earnings = await fetchUpcomingEarnings();
    res.json({ earnings, count: earnings.length });
  } catch (err: any) {
    res.status(500).json({ error: err.message });
  }
});

router.get('/correlation', async (req: Request, res: Response) => {
  try {
    const lookback = Math.max(10, Math.min(500, parseInt(String(req.query.lookback || '60'), 10) || 60));
    const keysParam = Array.isArray(req.query.keys) ? req.query.keys.join(',') : String(req.query.keys || '');
    const keys = keysParam ? keysParam.split(',').map((k) => k.trim()).filter(Boolean) : undefined;
    const result = await computeCorrelationMatrix(lookback, keys);
    res.json(result);
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

// === Execution Plan 트랑셰 영속화 ===
// Fix #7(2차 감사): tranche POST 입력 검증 강화.
//   - asset: 허용된 자산 목록 내.
//   - stage: 정수 1~5 (이전 1~3 → execution plan 은 최대 3단계지만 LEVERAGE 등 향후 확장 고려해 5 까지 허용).
//   - priceAtEntry: 제공된 경우에 한해 finite 양수.
const ALLOWED_TRANCHE_ASSETS = new Set([
  'NASDAQ', 'KOSPI', 'GOLD', 'SILVER', 'COPPER', 'LEVERAGE', 'EMERGING',
]);

router.post('/execution-plan/tranche', async (req: Request, res: Response) => {
  try {
    const body = req.body || {};
    const asset = String(body.asset || '').trim();
    const stage = Number(body.stage);
    if (!ALLOWED_TRANCHE_ASSETS.has(asset)) {
      res.status(400).json({
        error: `invalid asset; must be one of ${Array.from(ALLOWED_TRANCHE_ASSETS).join(',')}`,
      });
      return;
    }
    if (!Number.isInteger(stage) || stage < 1 || stage > 5) {
      res.status(400).json({ error: 'invalid stage; integer 1..5 required' });
      return;
    }
    if (body.priceAtEntry !== undefined && body.priceAtEntry !== null) {
      const p = Number(body.priceAtEntry);
      if (!Number.isFinite(p) || p <= 0) {
        res.status(400).json({ error: 'invalid priceAtEntry; finite positive number required' });
        return;
      }
    }

    // 현재 snapshot 에서 regime/price/weightPct 보강
    let regimeAtEntry: string | null = null;
    let priceAtEntry: number | null =
      typeof body.priceAtEntry === 'number' ? body.priceAtEntry : null;
    // 7차 TOP3 Fix #3: 현재 플레이북 stages 의 weightPct 를 우선 사용, 없으면 30/30/40 fallback.
    let weightPct: number | undefined;
    try {
      const snap = await getSnapshot(DEFAULT_PROFILE);
      regimeAtEntry = snap.regime?.regime ?? null;
      if (priceAtEntry === null) {
        const raw = (snap as any).raw as Record<string, { value: number }> | undefined;
        const candidate = raw?.[asset]?.value;
        if (typeof candidate === 'number') priceAtEntry = candidate;
      }
      const plans = (snap as any).meta?.executionPlans as
        | Array<{ asset: string; stages: Array<{ stage: number; weightPct?: number }> }>
        | undefined;
      const plan = plans?.find((p) => p.asset === asset);
      const planStage = plan?.stages?.find((s) => s.stage === stage);
      if (planStage && typeof planStage.weightPct === 'number' && Number.isFinite(planStage.weightPct)) {
        weightPct = planStage.weightPct;
      }
    } catch {
      /* snapshot 읽기 실패 시 regime/price 는 null 로 저장 */
    }
    if (weightPct === undefined) {
      // fallback: stage 1→30, 2→30, 3→40 (1-based index).
      const idx = stage - 1;
      if (idx >= 0 && idx < DEFAULT_TRANCHE_WEIGHTS.length) {
        weightPct = DEFAULT_TRANCHE_WEIGHTS[idx];
      }
    }

    const entry: TrancheEntry = {
      asset,
      stage,
      executedAt: new Date().toISOString(),
      priceAtEntry,
      regimeAtEntry,
      ...(weightPct !== undefined ? { weightPct } : {}),
    };
    const entries = await appendTranche(entry);
    res.status(201).json({ entry, total: entries.length });
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/execution-plan/tranche', async (_req: Request, res: Response) => {
  try {
    const entries = await listTranches();
    const summary = summarizeByAsset(entries);
    res.json({ entries, summary });
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.delete('/execution-plan/tranche/:asset', async (req: Request, res: Response) => {
  try {
    const asset = Array.isArray(req.params.asset) ? req.params.asset[0] : req.params.asset;
    const remaining = await clearAssetTranches(String(asset));
    res.json({ asset, remainingTotal: remaining.length });
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

// 16차 Phase 1 A1 + Phase 2 D1: Investment Plan + Trade Log
router.get('/plan', async (_req: Request, res: Response) => {
  const plan = await readInvestmentPlan();
  res.json({ plan });
});

router.post('/plan', async (req: Request, res: Response) => {
  const patch = (req.body || {}) as Partial<InvestmentPlan>;
  // 21차 P2#15: horizon 변경 시 자동 trade-log 기록 — "흔들림 자체가 보임"
  try {
    const before = await readInvestmentPlan();
    if (patch.horizon && patch.horizon !== before.horizon) {
      await appendTradeLog({
        kind: 'observation',
        notes: `horizon change: ${before.horizon} → ${patch.horizon}`,
        context: { before: before.horizon, after: patch.horizon },
      });
    }
  } catch { /* skip */ }
  const plan = await writeInvestmentPlan(patch);
  res.json({ plan });
});

router.get('/trade-log', async (req: Request, res: Response) => {
  const limit = parseInt((req.query.limit as string) || '200', 10);
  const entries = await readRecentTradeLog(Number.isFinite(limit) ? limit : 200);
  res.json({ entries });
});

router.post('/trade-log', async (req: Request, res: Response) => {
  const { kind, asset, from, to, notes, context } = req.body as {
    kind?: 'signal_change' | 'allocation_change' | 'user_action' | 'observation';
    asset?: string; from?: string; to?: string; notes?: string; context?: Record<string, unknown>;
  };
  if (!kind) { res.status(400).json({ error: 'kind required' }); return; }
  // 21차 P2#14: user_action 일 때 시스템 권고 vs 사용자 행동 자동 비교
  let againstSystemRecommendation: boolean | undefined;
  let regimeAtAction: string | undefined;
  let signalAtAction: string | undefined;
  if (kind === 'user_action' && asset) {
    try {
      const snap = await getSnapshot(DEFAULT_PROFILE);
      regimeAtAction = snap.regime?.regime;
      const sig = snap.signals?.find((s) => s.asset === asset);
      signalAtAction = sig?.signal;
      // 사용자가 SELL 했는데 시스템이 BUY/STRONG_BUY 였다면 against. 또는 BUY 했는데 시스템 SELL/REDUCE.
      const userAction = (to || '').toUpperCase();
      if (signalAtAction && userAction) {
        const userBuy = /BUY|ADD|ENTER/i.test(userAction);
        const userSell = /SELL|EXIT|REDUCE|TRIM/i.test(userAction);
        const sysBuy = signalAtAction === 'BUY' || signalAtAction === 'STRONG_BUY';
        const sysSell = signalAtAction === 'SELL' || signalAtAction === 'REDUCE';
        if ((userSell && sysBuy) || (userBuy && sysSell)) {
          againstSystemRecommendation = true;
        } else if ((userBuy && sysBuy) || (userSell && sysSell)) {
          againstSystemRecommendation = false;
        }
      }
    } catch { /* skip */ }
  }
  await appendTradeLog({
    kind,
    asset,
    from,
    to,
    notes,
    againstSystemRecommendation,
    context: { ...(context ?? {}), regimeAtAction, signalAtAction },
  });
  res.json({ ok: true, againstSystemRecommendation });
});

// 17차 Phase 3 B2/B3/B4: 국내 증권사 리서치 최신 발행일
router.get('/domestic-reports', async (_req: Request, res: Response) => {
  try {
    const data = await fetchDomesticReportsLatest();
    res.json({ data });
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

// 17차 Phase 2 D1 + E2: Weekly report + rule violations
router.get('/weekly-report', async (req: Request, res: Response) => {
  try {
    const snapshot = await getSnapshot(DEFAULT_PROFILE);
    const report = buildWeeklyReport(snapshot);
    const violations = await detectRuleViolations(snapshot);
    report.ruleViolations = violations;
    const format = String(req.query.format || 'json');
    if (format === 'text') {
      res.type('text/plain').send(formatWeeklyReportText(report));
      return;
    }
    res.json({ report, text: formatWeeklyReportText(report) });
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/health', (_req: Request, res: Response) => {
  const { cacheTime } = readCache();
  res.json({
    status: 'ok',
    timestamp: new Date().toISOString(),
    cacheTtlMs: CACHE_TTL,
    lastRefreshAt: cacheTime ? new Date(cacheTime).toISOString() : null,
  });
});

export default router;
