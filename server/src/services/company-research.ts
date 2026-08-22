import { fetchAnalystConsensus } from '../collectors/analyst-consensus';
import { extractSegmentGeoMixFromFacts, fetchSecCompanyFacts } from '../collectors/sec/companyfacts';
import { fetchSecFilingIndex, fetchSecFilingText, extractSegmentGeoMix, inferSegmentGeoEntriesFromText, parseIrMaterialsFromIndex, summarizeIrMaterialText } from '../collectors/sec/filing-detail';
import { fetchRecentCompanyFilings } from '../collectors/sec/filings';
import { fetchSecSubmissions } from '../collectors/sec/submissions';
import { lookupSecCompanyByTicker } from '../collectors/sec/ticker-map';
import { fetchYahooHistory, fetchYahooQuote } from '../collectors/yahoo';
import { DEFAULT_PROFILE, getSnapshot } from '../state/cache';
import { computeCompanyScore } from '../engines/fundamentals/company-score';
import { normalizeCompanyFinancials } from '../engines/fundamentals/normalize-financials';
import { analyzeBottomPattern } from '../engines/bottom/pattern';
import { CompanyBottomSignal, CompanyBuyScore, CompanyCapitalFlowInsight, CompanyCashFlowQualityInsight, CompanyCorrectionAssessment, CompanyExecutionBridge, CompanyGuidanceInsight, CompanyIrMaterial, CompanyMultipleInsight, CompanyNarrativeInsight, CompanyPeerSummary, CompanyResearchResponse, CompanyFilingEvent, CompanySectorContext, CompanySegmentMixEntry, CompanyThesisMonitor, CompanyTimeframeView, CompanyVerdicts, CompanyBottleneckInsight, CompanyPositionSizingPlan, CompanyReversalConfirmation } from '../types/fundamentals';
import { BottomMetricStatus, BottomSignalChartMarker, BottomSignalChartPoint, BottomSignalMetric, DeepBottomSignal } from '../types/indicators';
import { analystScoreRevisionDelta7d, analystScoreRevisionDelta30d, analystScoreRevisionDelta90d, estimateRevisionDelta7d, estimateRevisionDelta30d, estimateRevisionDelta90d, getCompanyAnalystHistory, recordCompanyAnalystSnapshot } from './company-analyst-history';
import { CORE_COMPANY_TICKERS, getCompanyFilingDetailFreshMs, getCompanySecFreshMs } from './company-refresh-policy';
import { childLogger, serializeError } from './logger';
import { getAutoExpandedCompanyPeers, getResearchThemesForSectorKey, inferCompanyPeerGroup, inferResearchSectorForTicker } from './company-peer-map';
import { findBottleneckCandidateByTicker, getBottleneckThemeById } from '../domain/bottleneck/candidate-map';
import { computeBottleneckCandidateScore } from '../domain/bottleneck/bottleneck-score';
import { buildNarrativeThemesForSnapshot } from './narrative-research';
import { getSectorDefinition } from '../engines/sector-classification';
import { readSourceCache, readSourceCacheWithin, writeSourceCache } from './source-cache';

const log = childLogger({ module: 'service.company-research' });
const COMPANY_RESEARCH_LITE_CACHE_MS = 30 * 60 * 1000;
const COMPANY_RESEARCH_FULL_CACHE_MS = 15 * 60 * 1000;
const companyResearchLiteInflight = new Map<string, Promise<CompanyResearchResponse>>();
const companyResearchFullInflight = new Map<string, Promise<CompanyResearchResponse>>();

function companyResearchLiteCacheKey(ticker: string) {
  return `company-research-lite-${ticker.trim().toUpperCase()}`;
}

function companyResearchFullCacheKey(ticker: string) {
  return `company-research-full-${ticker.trim().toUpperCase()}`;
}

export function getCoreCompanyResearchTickers(): string[] {
  return [...CORE_COMPANY_TICKERS];
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function roundScore(value: number): number {
  return Math.round(clamp(value, 0, 100));
}

function bounded(value: number | null | undefined, min: number, max: number): number | null {
  if (value === null || value === undefined || Number.isNaN(value)) return null;
  return clamp(value, min, max);
}

function mean(values: number[]): number | null {
  if (!values.length) return null;
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function pctChange(current: number, previous: number): number | null {
  if (!previous) return null;
  return Number((((current - previous) / previous) * 100).toFixed(1));
}

type CompanyPriceContext = {
  drawdownFromHighPct: number | null;
  drawdownFrom120dHighPct: number | null;
  reboundFromLowPct: number | null;
  return30d: number | null;
  volumeTrend20d: number | null;
  ma20GapPct: number | null;
  ma20Below50: boolean;
  recentDrop3dPct: number | null;
  points: BottomSignalChartPoint[];
  peakPoint: BottomSignalChartPoint | null;
  lowPoint: BottomSignalChartPoint | null;
  latestPoint: BottomSignalChartPoint | null;
  retestPoint: BottomSignalChartPoint | null;
  confirmPoint: BottomSignalChartPoint | null;
  patternPhase: 'decline' | 'candidate' | 'retest' | 'confirm';
  retestGapPct: number | null;
  candidateVolumeRatio: number | null;
  confirmVolumeRatio: number | null;
  retestVolumeRatio: number | null;
  absorptionVolumeVsRecent2dRatio: number | null;
  absorptionVolumeVsRecent3dRatio: number | null;
  absorptionDropPct: number | null;
  priorDeclineDropPct: number | null;
  absorptionContractionRatio: number | null;
  absorptionDate: string | null;
  daysSinceAbsorption: number | null;
  reboundSinceAbsorptionPct: number | null;
};

function averageVolumeAround(history: Awaited<ReturnType<typeof fetchYahooHistory>>, centerDate: string | null, lookback = 20): number | null {
  if (!centerDate) return null;
  const index = history.findIndex((item) => item.date === centerDate);
  if (index < 0) return null;
  const start = Math.max(0, index - lookback);
  const window = history.slice(start, index).map((item) => item.volume ?? 0).filter((value) => value > 0);
  return mean(window);
}

function recentMaxVolumeBefore(history: Awaited<ReturnType<typeof fetchYahooHistory>>, centerDate: string | null, lookback = 3): number | null {
  if (!centerDate) return null;
  const index = history.findIndex((item) => item.date === centerDate);
  if (index < 0) return null;
  const volumes = history.slice(Math.max(0, index - lookback), index).map((item) => item.volume ?? 0).filter((value) => value > 0);
  if (!volumes.length) return null;
  return Math.max(...volumes);
}

function dailyCloseDropPct(history: Awaited<ReturnType<typeof fetchYahooHistory>>, centerDate: string | null): number | null {
  if (!centerDate) return null;
  const index = history.findIndex((item) => item.date === centerDate);
  if (index <= 0) return null;
  const current = history[index]?.close ?? 0;
  const previous = history[index - 1]?.close ?? 0;
  if (!current || !previous) return null;
  return Number((((current - previous) / previous) * 100).toFixed(1));
}

function averageClose(history: Awaited<ReturnType<typeof fetchYahooHistory>>, endIndexInclusive: number, lookback: number): number | null {
  const start = endIndexInclusive - lookback + 1;
  if (start < 0) return null;
  const values = history.slice(start, endIndexInclusive + 1).map((item) => item.close).filter((value) => value > 0);
  return mean(values);
}

function rollingHigh(history: Awaited<ReturnType<typeof fetchYahooHistory>>, endIndexInclusive: number, lookback: number): number | null {
  const start = endIndexInclusive - lookback + 1;
  if (start < 0) return null;
  const values = history.slice(start, endIndexInclusive + 1).map((item) => item.close).filter((value) => value > 0);
  return values.length ? Math.max(...values) : null;
}

function cumulativeCloseChangePct(history: Awaited<ReturnType<typeof fetchYahooHistory>>, centerDate: string | null, days: number): number | null {
  if (!centerDate) return null;
  const index = history.findIndex((item) => item.date === centerDate);
  if (index < days || index < 0) return null;
  const current = history[index]?.close ?? 0;
  const previous = history[index - days]?.close ?? 0;
  if (!current || !previous) return null;
  return Number((((current - previous) / previous) * 100).toFixed(1));
}

function buildCompanyPriceContext(history: Awaited<ReturnType<typeof fetchYahooHistory>>): CompanyPriceContext {
  const closes = history.map((item) => item.close).filter((value) => value > 0);
  const volumes = history.map((item) => item.volume ?? 0).filter((value) => value > 0);
  if (!closes.length) {
    return {
      drawdownFromHighPct: null,
      drawdownFrom120dHighPct: null,
      reboundFromLowPct: null,
      return30d: null,
      volumeTrend20d: null,
      ma20GapPct: null,
      ma20Below50: false,
      recentDrop3dPct: null,
      points: [],
      peakPoint: null,
      lowPoint: null,
      latestPoint: null,
      retestPoint: null,
      confirmPoint: null,
      patternPhase: 'decline',
      retestGapPct: null,
      candidateVolumeRatio: null,
      confirmVolumeRatio: null,
      retestVolumeRatio: null,
      absorptionVolumeVsRecent2dRatio: null,
      absorptionVolumeVsRecent3dRatio: null,
      absorptionDropPct: null,
      priorDeclineDropPct: null,
      absorptionContractionRatio: null,
      absorptionDate: null,
      daysSinceAbsorption: null,
      reboundSinceAbsorptionPct: null,
    };
  }
  const latest = closes[closes.length - 1] ?? null;
  const high52 = Math.max(...closes);
  const latestIndex = history.length - 1;
  const high120 = rollingHigh(history, latestIndex, 120);
  const low52 = Math.min(...closes);
  const drawdownFromHighPct = latest && high52 ? Number((((latest - high52) / high52) * 100).toFixed(1)) : null;
  const drawdownFrom120dHighPct = latest && high120 ? Number((((latest - high120) / high120) * 100).toFixed(1)) : null;
  const reboundFromLowPct = latest && low52 ? Number((((latest - low52) / low52) * 100).toFixed(1)) : null;
  const return30d = closes.length > 30 ? pctChange(closes[closes.length - 1] ?? 0, closes[closes.length - 31] ?? 0) : null;
  const ma20 = averageClose(history, latestIndex, 20);
  const ma50 = averageClose(history, latestIndex, 50);
  const ma20GapPct = latest && ma20 ? Number((((latest - ma20) / ma20) * 100).toFixed(1)) : null;
  const ma20Below50 = ma20 !== null && ma50 !== null ? ma20 < ma50 : false;
  const recentVol = mean(volumes.slice(-20));
  const prevVol = mean(volumes.slice(-40, -20));
  const volumeTrend20d = recentVol !== null && prevVol !== null && prevVol > 0
    ? Number((((recentVol - prevVol) / prevVol) * 100).toFixed(1))
    : null;
  const points = history.slice(-260).map((item) => ({ date: item.date, value: item.close }));
  const pattern = analyzeBottomPattern(history.map((item) => ({ date: item.date, close: item.close, volume: item.volume })));
  const candidateHistory = pattern.candidatePoint ? history.find((item) => item.date === pattern.candidatePoint?.date) ?? null : null;
  const confirmHistory = pattern.confirmPoint ? history.find((item) => item.date === pattern.confirmPoint?.date) ?? null : null;
  const retestHistory = pattern.retestPoint ? history.find((item) => item.date === pattern.retestPoint?.date) ?? null : null;
  const candidateBaseVol = averageVolumeAround(history, pattern.candidatePoint?.date ?? null);
  const confirmBaseVol = averageVolumeAround(history, pattern.confirmPoint?.date ?? null);
  const retestBaseVol = averageVolumeAround(history, pattern.retestPoint?.date ?? null);
  const absorptionDate = pattern.retestPoint?.date ?? pattern.candidatePoint?.date ?? null;
  const absorptionHistory = absorptionDate ? history.find((item) => item.date === absorptionDate) ?? null : null;
  const recent2dMaxVolume = recentMaxVolumeBefore(history, absorptionDate, 2);
  const recent3dMaxVolume = recentMaxVolumeBefore(history, absorptionDate, 3);
  const absorptionVolumeVsRecent2dRatio = absorptionHistory?.volume && recent2dMaxVolume
    ? Number((absorptionHistory.volume / recent2dMaxVolume).toFixed(2))
    : null;
  const absorptionVolumeVsRecent3dRatio = absorptionHistory?.volume && recent3dMaxVolume
    ? Number((absorptionHistory.volume / recent3dMaxVolume).toFixed(2))
    : null;
  const absorptionDropPct = dailyCloseDropPct(history, absorptionDate);
  const priorDeclineDropPct = dailyCloseDropPct(history, pattern.candidatePoint?.date ?? null);
  const absorptionContractionRatio = absorptionDropPct !== null
    && priorDeclineDropPct !== null
    && absorptionDropPct < 0
    && priorDeclineDropPct < 0
    && Math.abs(priorDeclineDropPct) > 0
      ? Number((Math.abs(absorptionDropPct) / Math.abs(priorDeclineDropPct)).toFixed(2))
      : null;
  const recentDrop3dPct = cumulativeCloseChangePct(history, absorptionDate, 3);
  const absorptionIndex = absorptionDate ? history.findIndex((item) => item.date === absorptionDate) : -1;
  const daysSinceAbsorption = absorptionIndex >= 0 ? latestIndex - absorptionIndex : null;
  const reboundSinceAbsorptionPct = absorptionHistory?.close && latest
    ? Number((((latest - absorptionHistory.close) / absorptionHistory.close) * 100).toFixed(1))
    : null;
  return {
    drawdownFromHighPct,
    drawdownFrom120dHighPct,
    reboundFromLowPct,
    return30d,
    volumeTrend20d,
    ma20GapPct,
    ma20Below50,
    recentDrop3dPct,
    points,
    peakPoint: pattern.peakPoint ? { date: pattern.peakPoint.date, value: pattern.peakPoint.close } : null,
    lowPoint: pattern.candidatePoint ? { date: pattern.candidatePoint.date, value: pattern.candidatePoint.close } : null,
    latestPoint: pattern.currentPoint ? { date: pattern.currentPoint.date, value: pattern.currentPoint.close } : null,
    retestPoint: pattern.retestPoint ? { date: pattern.retestPoint.date, value: pattern.retestPoint.close } : null,
    confirmPoint: pattern.confirmPoint ? { date: pattern.confirmPoint.date, value: pattern.confirmPoint.close } : null,
    patternPhase: pattern.phase,
    retestGapPct: pattern.retestGapPct,
    candidateVolumeRatio: candidateHistory?.volume && candidateBaseVol ? Number((candidateHistory.volume / candidateBaseVol).toFixed(2)) : null,
    confirmVolumeRatio: confirmHistory?.volume && confirmBaseVol ? Number((confirmHistory.volume / confirmBaseVol).toFixed(2)) : null,
    retestVolumeRatio: retestHistory?.volume && retestBaseVol ? Number((retestHistory.volume / retestBaseVol).toFixed(2)) : null,
    absorptionVolumeVsRecent2dRatio,
    absorptionVolumeVsRecent3dRatio,
    absorptionDropPct,
    priorDeclineDropPct,
    absorptionContractionRatio,
    absorptionDate,
    daysSinceAbsorption,
    reboundSinceAbsorptionPct,
  };
}

function buildCompanyBuyScore(
  financials: CompanyResearchResponse['financials'],
  score: CompanyResearchResponse['score'],
): CompanyBuyScore {
  const normalizedGrowth = bounded(financials.revenueGrowthYoY, -20, 80);
  const normalizedMargin = bounded(financials.operatingMargin, -10, 45);
  const normalizedEvToSales = bounded(financials.evToSales, 0, 20);
  const normalizedUpside = bounded(financials.estimateUpsidePct, -20, 40);
  const normalizedRevision = bounded(financials.estimateRevision30d, -20, 20);
  const normalizedAnalystRevision = bounded(financials.analystScoreRevision30d, -2, 2);

  const appealDrivers: number[] = [
    score.totalScore,
    score.quality.value,
    score.growth.value,
    score.balanceSheet.value,
  ];
  if (normalizedUpside !== null) appealDrivers.push(clamp(50 + normalizedUpside * 0.9, 25, 82));
  if (normalizedMargin !== null) appealDrivers.push(clamp(45 + normalizedMargin * 0.7, 20, 85));
  const appealScore = roundScore(appealDrivers.reduce((sum, item) => sum + item, 0) / appealDrivers.length);

  let crowdingBase = 20;
  if (normalizedEvToSales !== null) crowdingBase += clamp((normalizedEvToSales - 4.5) * 3.8, 0, 26);
  if (normalizedRevision !== null) crowdingBase += clamp(normalizedRevision * 1.8, 0, 12);
  if (normalizedAnalystRevision !== null) crowdingBase += clamp(normalizedAnalystRevision * 10, 0, 10);
  if (normalizedGrowth !== null) crowdingBase += clamp((normalizedGrowth - 22) * 0.45, 0, 10);
  if (normalizedMargin !== null) crowdingBase += clamp((normalizedMargin - 22) * 0.35, 0, 8);
  const crowdingScore = roundScore(crowdingBase);

  const buyScore = roundScore(appealScore * 0.72 + (100 - crowdingScore) * 0.28);
  const label: CompanyBuyScore['label'] = buyScore >= 72 ? '매수 우호' : buyScore >= 56 ? '선별 접근' : '추격 주의';

  const reasons: string[] = [];
  if (score.totalScore >= 72) reasons.push(`기초체력 점수 ${score.totalScore}/100으로 상위권`);
  if (normalizedGrowth !== null && normalizedGrowth >= 15) reasons.push(`매출 성장 ${normalizedGrowth.toFixed(1)}%로 성장 모멘텀 유지`);
  if (normalizedMargin !== null && normalizedMargin >= 18) reasons.push(`영업이익률 ${normalizedMargin.toFixed(1)}%로 수익성 우위`);
  if (normalizedUpside !== null && normalizedUpside >= 8) reasons.push(`애널리스트 업사이드 ${normalizedUpside.toFixed(1)}%`);
  if (normalizedEvToSales !== null && normalizedEvToSales >= 10) reasons.push(`EV/Sales ${normalizedEvToSales.toFixed(1)}x로 밸류 부담 존재`);
  if (crowdingScore >= 64) reasons.push(`과열도 ${crowdingScore}/100 — 좋은 기업이어도 추격은 신중`);
  if (reasons.length === 0) reasons.push('기초체력은 무난하지만 현재는 선택적 접근 구간');

  return {
    appealScore,
    crowdingScore,
    buyScore,
    label,
    reasons: reasons.slice(0, 4),
  };
}

function representativeMixEntry(label: string, percentOfTotal: number): CompanySegmentMixEntry {
  return { label, value: null, unit: null, percentOfTotal };
}

function applyRepresentativeSegmentGeoFallback(
  ticker: string,
  current: { note: string | null; segmentMix: CompanySegmentMixEntry[]; geoMix: CompanySegmentMixEntry[] },
): { note: string | null; segmentMix: CompanySegmentMixEntry[]; geoMix: CompanySegmentMixEntry[] } {
  const normalized = ticker.toUpperCase();
  const rules: Record<string, { note: string; segmentMix: CompanySegmentMixEntry[]; geoMix: CompanySegmentMixEntry[] }> = {
    NVDA: {
      note: '대표기업 fallback: Data Center / Gaming 중심, 지역은 미국·싱가포르·대만·중국 노출을 우선 참고',
      segmentMix: [
        representativeMixEntry('Data Center', 87),
        representativeMixEntry('Gaming', 8),
        representativeMixEntry('Automotive', 2),
        representativeMixEntry('Professional Visualization', 2),
        representativeMixEntry('OEM/Other', 1),
      ],
      geoMix: [
        representativeMixEntry('United States', 47),
        representativeMixEntry('Singapore', 16),
        representativeMixEntry('Taiwan', 14),
        representativeMixEntry('China/Hong Kong', 13),
        representativeMixEntry('Other', 10),
      ],
    },
    MSFT: {
      note: '대표기업 fallback: Productivity / Intelligent Cloud / More Personal Computing 구조를 우선 참고',
      segmentMix: [
        representativeMixEntry('Productivity and Business Processes', 31),
        representativeMixEntry('Intelligent Cloud', 43),
        representativeMixEntry('More Personal Computing', 26),
      ],
      geoMix: [
        representativeMixEntry('United States', 51),
        representativeMixEntry('International', 49),
      ],
    },
    GOOGL: {
      note: '대표기업 fallback: Google Services / Cloud / Other Bets, 지역은 Americas·EMEA·APAC로 단순화',
      segmentMix: [
        representativeMixEntry('Google Services', 87),
        representativeMixEntry('Google Cloud', 12),
        representativeMixEntry('Other Bets', 1),
      ],
      geoMix: [
        representativeMixEntry('Americas', 49),
        representativeMixEntry('EMEA', 31),
        representativeMixEntry('APAC', 20),
      ],
    },
    META: {
      note: '대표기업 fallback: Family of Apps / Reality Labs와 미국·유럽 중심 매출 구조를 우선 참고',
      segmentMix: [
        representativeMixEntry('Family of Apps', 97),
        representativeMixEntry('Reality Labs', 3),
      ],
      geoMix: [
        representativeMixEntry('United States & Canada', 45),
        representativeMixEntry('Europe', 24),
        representativeMixEntry('Asia-Pacific', 22),
        representativeMixEntry('Rest of World', 9),
      ],
    },
    AMZN: {
      note: '대표기업 fallback: North America / International / AWS와 북미 편중 현금창출 구조를 우선 참고',
      segmentMix: [
        representativeMixEntry('North America', 61),
        representativeMixEntry('International', 22),
        representativeMixEntry('AWS', 17),
      ],
      geoMix: [
        representativeMixEntry('United States', 69),
        representativeMixEntry('Germany', 8),
        representativeMixEntry('United Kingdom', 7),
        representativeMixEntry('Japan', 6),
        representativeMixEntry('Other', 10),
      ],
    },
    ORCL: {
      note: '대표기업 fallback: Cloud services/license support 중심과 미국·유럽 비중을 우선 참고',
      segmentMix: [
        representativeMixEntry('Cloud Services & License Support', 77),
        representativeMixEntry('Cloud License & On-Premise License', 9),
        representativeMixEntry('Hardware', 6),
        representativeMixEntry('Services', 8),
      ],
      geoMix: [
        representativeMixEntry('Americas', 56),
        representativeMixEntry('EMEA', 27),
        representativeMixEntry('APAC', 17),
      ],
    },
    ASML: {
      note: '대표기업 fallback: EUV / DUV / Applications / Service mix를 기준으로 사용',
      segmentMix: [
        representativeMixEntry('EUV', 44),
        representativeMixEntry('DUV', 31),
        representativeMixEntry('Applications', 14),
        representativeMixEntry('Service and Field Options', 11),
      ],
      geoMix: [
        representativeMixEntry('Taiwan', 36),
        representativeMixEntry('South Korea', 28),
        representativeMixEntry('China', 20),
        representativeMixEntry('United States', 8),
        representativeMixEntry('Europe/Other', 8),
      ],
    },
    TSM: {
      note: '대표기업 fallback: HPC / Smartphone 중심 매출 구조와 북미 편중 고객 노출을 우선 참고',
      segmentMix: [
        representativeMixEntry('HPC', 46),
        representativeMixEntry('Smartphone', 38),
        representativeMixEntry('IoT', 8),
        representativeMixEntry('Automotive', 5),
        representativeMixEntry('Other', 3),
      ],
      geoMix: [
        representativeMixEntry('North America', 69),
        representativeMixEntry('China', 12),
        representativeMixEntry('APAC ex-China', 10),
        representativeMixEntry('EMEA', 9),
      ],
    },
    VRT: {
      note: '대표기업 fallback: 데이터센터 전력·열관리 중심과 북미 hyperscaler CAPEX 노출을 우선 참고',
      segmentMix: [
        representativeMixEntry('Data Center Thermal', 42),
        representativeMixEntry('Power Management', 34),
        representativeMixEntry('Services/Other', 24),
      ],
      geoMix: [
        representativeMixEntry('Americas', 62),
        representativeMixEntry('EMEA', 23),
        representativeMixEntry('APAC', 15),
      ],
    },
    ETN: {
      note: '대표기업 fallback: 전력장비·전기화 CAPEX와 북미·유럽 중심 산업 노출을 우선 참고',
      segmentMix: [
        representativeMixEntry('Electrical Americas', 42),
        representativeMixEntry('Electrical Global', 24),
        representativeMixEntry('Aerospace', 19),
        representativeMixEntry('Vehicle / eMobility', 15),
      ],
      geoMix: [
        representativeMixEntry('United States', 56),
        representativeMixEntry('Europe', 23),
        representativeMixEntry('APAC', 13),
        representativeMixEntry('Other', 8),
      ],
    },
    AAPL: {
      note: '대표기업 fallback: iPhone / Mac / iPad / Wearables / Services와 Americas·Europe·Greater China 구조를 우선 참고',
      segmentMix: [representativeMixEntry('iPhone', 51), representativeMixEntry('Services', 24), representativeMixEntry('Mac', 9), representativeMixEntry('iPad', 7), representativeMixEntry('Wearables/Home/Accessories', 9)],
      geoMix: [representativeMixEntry('Americas', 45), representativeMixEntry('Europe', 25), representativeMixEntry('Greater China', 18), representativeMixEntry('Japan', 7), representativeMixEntry('Rest of APAC', 5)],
    },
    JPM: {
      note: '대표기업 fallback: CCB / CIB / AWM / Consumer와 북미 중심 금융 프랜차이즈 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Consumer & Community Banking', 40), representativeMixEntry('Corporate & Investment Bank', 31), representativeMixEntry('Asset & Wealth Management', 16), representativeMixEntry('Commercial Banking', 13)],
      geoMix: [representativeMixEntry('United States', 72), representativeMixEntry('EMEA', 13), representativeMixEntry('APAC', 9), representativeMixEntry('Latin America/Other', 6)],
    },
    XOM: {
      note: '대표기업 fallback: Upstream / Product Solutions / Low Carbon 및 미국·국제 자원 노출을 우선 참고',
      segmentMix: [representativeMixEntry('Upstream', 59), representativeMixEntry('Product Solutions', 32), representativeMixEntry('Low Carbon / Other', 9)],
      geoMix: [representativeMixEntry('United States', 36), representativeMixEntry('Europe', 18), representativeMixEntry('Asia-Pacific', 22), representativeMixEntry('Other International', 24)],
    },
    CAT: {
      note: '대표기업 fallback: Construction / Resource / Energy & Transportation 비중을 우선 참고',
      segmentMix: [representativeMixEntry('Construction Industries', 41), representativeMixEntry('Resource Industries', 19), representativeMixEntry('Energy & Transportation', 34), representativeMixEntry('Financial Products / Other', 6)],
      geoMix: [representativeMixEntry('North America', 53), representativeMixEntry('EAME', 25), representativeMixEntry('Latin America', 10), representativeMixEntry('Asia-Pacific', 12)],
    },
    PG: {
      note: '대표기업 fallback: Beauty / Grooming / Health Care / Fabric & Home Care 중심과 북미·유럽 소비 비중을 우선 참고',
      segmentMix: [representativeMixEntry('Fabric & Home Care', 35), representativeMixEntry('Baby/Feminine/Family Care', 25), representativeMixEntry('Beauty', 19), representativeMixEntry('Health Care', 11), representativeMixEntry('Grooming', 10)],
      geoMix: [representativeMixEntry('North America', 52), representativeMixEntry('Europe', 21), representativeMixEntry('Asia Pacific', 17), representativeMixEntry('Latin America/MEA', 10)],
    },
    NEE: {
      note: '대표기업 fallback: regulated utility / renewables / transmission 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Florida Power & Light', 47), representativeMixEntry('Energy Resources', 43), representativeMixEntry('Transmission / Other', 10)],
      geoMix: [representativeMixEntry('United States', 94), representativeMixEntry('Canada/Other', 6)],
    },
    LIN: {
      note: '대표기업 fallback: Americas / EMEA / APAC 산업가스와 헬스케어 가스 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Industrial Gases Americas', 34), representativeMixEntry('Industrial Gases EMEA', 28), representativeMixEntry('Industrial Gases APAC', 23), representativeMixEntry('Healthcare / Other', 15)],
      geoMix: [representativeMixEntry('Americas', 39), representativeMixEntry('EMEA', 34), representativeMixEntry('Asia Pacific', 27)],
    },
    HD: {
      note: '대표기업 fallback: DIY / Pro 고객과 미국 주택 리모델링 노출을 우선 참고',
      segmentMix: [representativeMixEntry('DIY/Consumer', 56), representativeMixEntry('Pro', 44)],
      geoMix: [representativeMixEntry('United States', 91), representativeMixEntry('Canada', 6), representativeMixEntry('Mexico', 3)],
    },
    UNH: {
      note: '대표기업 fallback: UnitedHealthcare / Optum Health / Optum Rx / Optum Insight 구조를 우선 참고',
      segmentMix: [representativeMixEntry('UnitedHealthcare', 55), representativeMixEntry('Optum Health', 20), representativeMixEntry('Optum Rx', 17), representativeMixEntry('Optum Insight', 8)],
      geoMix: [representativeMixEntry('United States', 95), representativeMixEntry('International/Other', 5)],
    },
    TSLA: {
      note: '대표기업 fallback: automotive / energy generation & storage 구조와 미국·중국·유럽 판매구조를 우선 참고',
      segmentMix: [representativeMixEntry('Automotive', 89), representativeMixEntry('Energy Generation & Storage', 11)],
      geoMix: [representativeMixEntry('United States', 47), representativeMixEntry('China', 22), representativeMixEntry('Europe', 21), representativeMixEntry('Other', 10)],
    },
    BKNG: {
      note: '대표기업 fallback: Booking / Priceline / Agoda / Rentalcars 중심의 여행 플랫폼 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Agency / Merchant Booking', 92), representativeMixEntry('Advertising / Other', 8)],
      geoMix: [representativeMixEntry('Europe', 44), representativeMixEntry('United States', 21), representativeMixEntry('Asia Pacific', 23), representativeMixEntry('Other', 12)],
    },
    MCD: {
      note: '대표기업 fallback: franchise-heavy quick service model과 미국·해외 마켓 비중을 우선 참고',
      segmentMix: [representativeMixEntry('Franchised Restaurants', 93), representativeMixEntry('Company-operated', 7)],
      geoMix: [representativeMixEntry('United States', 40), representativeMixEntry('International Operated Markets', 37), representativeMixEntry('International Developmental Licensed Markets', 23)],
    },
    CVX: {
      note: '대표기업 fallback: upstream / downstream / chemicals 구조와 국제 자원 노출을 우선 참고',
      segmentMix: [representativeMixEntry('Upstream', 69), representativeMixEntry('Downstream', 21), representativeMixEntry('Chemicals/Other', 10)],
      geoMix: [representativeMixEntry('United States', 33), representativeMixEntry('Asia Pacific', 23), representativeMixEntry('Europe', 18), representativeMixEntry('Other International', 26)],
    },
    COP: {
      note: '대표기업 fallback: lower-48 / Alaska / Canada / international upstream 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Lower 48', 56), representativeMixEntry('Alaska', 15), representativeMixEntry('Canada', 12), representativeMixEntry('International/Other', 17)],
      geoMix: [representativeMixEntry('United States', 66), representativeMixEntry('Canada', 12), representativeMixEntry('Europe', 8), representativeMixEntry('Other', 14)],
    },
    SLB: {
      note: '대표기업 fallback: digital & integration / reservoir performance / well construction / production systems 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Digital & Integration', 19), representativeMixEntry('Reservoir Performance', 27), representativeMixEntry('Well Construction', 27), representativeMixEntry('Production Systems', 27)],
      geoMix: [representativeMixEntry('Middle East & Asia', 35), representativeMixEntry('North America', 20), representativeMixEntry('Europe/CIS/Africa', 24), representativeMixEntry('Latin America', 21)],
    },
    PLD: {
      note: '대표기업 fallback: 물류/산업 부동산 중심과 미국·유럽·아시아 물류 거점 노출을 우선 참고',
      segmentMix: [representativeMixEntry('Logistics Real Estate', 88), representativeMixEntry('Strategic Capital', 12)],
      geoMix: [representativeMixEntry('United States', 67), representativeMixEntry('Europe', 16), representativeMixEntry('Asia', 12), representativeMixEntry('Latin America', 5)],
    },
    EQIX: {
      note: '대표기업 fallback: colocation / interconnection / xScale 데이터센터 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Colocation', 58), representativeMixEntry('Interconnection', 27), representativeMixEntry('xScale / Other', 15)],
      geoMix: [representativeMixEntry('Americas', 46), representativeMixEntry('EMEA', 30), representativeMixEntry('Asia Pacific', 24)],
    },
    DLR: {
      note: '대표기업 fallback: 북미·유럽·아시아 데이터센터 임대 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Data Center Leasing', 79), representativeMixEntry('Interconnection / Services', 21)],
      geoMix: [representativeMixEntry('North America', 56), representativeMixEntry('Europe', 24), representativeMixEntry('Asia Pacific', 20)],
    },
    AMT: {
      note: '대표기업 fallback: U.S. towers / international towers / data centers / fiber 구조를 우선 참고',
      segmentMix: [representativeMixEntry('U.S. Towers', 48), representativeMixEntry('International Towers', 34), representativeMixEntry('Data Centers', 10), representativeMixEntry('Fiber / Other', 8)],
      geoMix: [representativeMixEntry('United States', 52), representativeMixEntry('Latin America', 20), representativeMixEntry('Europe', 12), representativeMixEntry('Africa/Asia', 16)],
    },
    CRM: {
      note: '대표기업 fallback: subscription SaaS와 Sales / Platform / Marketing / Data Cloud 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Sales', 27), representativeMixEntry('Service', 23), representativeMixEntry('Platform & Data', 19), representativeMixEntry('Marketing/Commerce', 16), representativeMixEntry('Other', 15)],
      geoMix: [representativeMixEntry('Americas', 66), representativeMixEntry('Europe', 22), representativeMixEntry('Asia Pacific', 12)],
    },
    NOW: {
      note: '대표기업 fallback: workflow automation / ITSM / CRM / creator platform 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Technology Workflows', 36), representativeMixEntry('Employee & Customer Workflows', 32), representativeMixEntry('Creator / Other', 32)],
      geoMix: [representativeMixEntry('Americas', 63), representativeMixEntry('EMEA', 25), representativeMixEntry('APAC', 12)],
    },
    PANW: {
      note: '대표기업 fallback: network security / cloud security / platform ARR 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Next-Gen Security ARR', 58), representativeMixEntry('Network Security', 27), representativeMixEntry('Services/Other', 15)],
      geoMix: [representativeMixEntry('Americas', 54), representativeMixEntry('EMEA', 28), representativeMixEntry('APAC', 18)],
    },
    CRWD: {
      note: '대표기업 fallback: endpoint / cloud / identity / logscale 보안 플랫폼 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Endpoint Security', 41), representativeMixEntry('Cloud/Identity', 34), representativeMixEntry('Log/Observability/Other', 25)],
      geoMix: [representativeMixEntry('United States', 69), representativeMixEntry('Europe', 18), representativeMixEntry('APAC/Other', 13)],
    },
    SNOW: {
      note: '대표기업 fallback: data platform consumption model과 product revenue / services mix를 우선 참고',
      segmentMix: [representativeMixEntry('Product Revenue', 95), representativeMixEntry('Services/Other', 5)],
      geoMix: [representativeMixEntry('Americas', 74), representativeMixEntry('EMEA', 16), representativeMixEntry('APAC', 10)],
    },
    QCOM: {
      note: '대표기업 fallback: handset / automotive / IoT와 QTL licensing 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Handset', 52), representativeMixEntry('Automotive', 10), representativeMixEntry('IoT', 24), representativeMixEntry('QTL Licensing', 14)],
      geoMix: [representativeMixEntry('China', 46), representativeMixEntry('Korea/Taiwan', 16), representativeMixEntry('United States', 15), representativeMixEntry('Other', 23)],
    },
    TXN: {
      note: '대표기업 fallback: industrial / automotive / personal electronics 아날로그 반도체 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Industrial', 37), representativeMixEntry('Automotive', 35), representativeMixEntry('Personal Electronics', 15), representativeMixEntry('Other', 13)],
      geoMix: [representativeMixEntry('China', 21), representativeMixEntry('United States', 19), representativeMixEntry('Europe', 18), representativeMixEntry('Other Asia/Other', 42)],
    },
    ANET: {
      note: '대표기업 fallback: cloud networking / enterprise campus switching 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Cloud / Hyperscaler Networking', 66), representativeMixEntry('Enterprise / Campus', 34)],
      geoMix: [representativeMixEntry('United States', 62), representativeMixEntry('EMEA', 19), representativeMixEntry('APAC/Other', 19)],
    },
    AMAT: {
      note: '대표기업 fallback: Semiconductor Systems / Applied Global Services / Display 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Semiconductor Systems', 72), representativeMixEntry('Applied Global Services', 23), representativeMixEntry('Display/Other', 5)],
      geoMix: [representativeMixEntry('Asia', 76), representativeMixEntry('United States', 9), representativeMixEntry('Europe', 8), representativeMixEntry('Other', 7)],
    },
    KLAC: {
      note: '대표기업 fallback: process control / inspection / specialty semiconductor process 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Process Control', 82), representativeMixEntry('PCB/Components/Other', 18)],
      geoMix: [representativeMixEntry('Taiwan/Korea', 38), representativeMixEntry('China', 24), representativeMixEntry('United States', 12), representativeMixEntry('Other', 26)],
    },
    V: {
      note: '대표기업 fallback: payments volume / cross-border / value-added services 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Payments / Data Processing', 77), representativeMixEntry('Cross-border', 13), representativeMixEntry('Value-Added Services', 10)],
      geoMix: [representativeMixEntry('United States', 39), representativeMixEntry('Europe', 24), representativeMixEntry('Asia Pacific', 23), representativeMixEntry('Other', 14)],
    },
    MA: {
      note: '대표기업 fallback: switched transactions / cross-border / services 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Domestic & Switched Transactions', 61), representativeMixEntry('Cross-border', 18), representativeMixEntry('Services/Other', 21)],
      geoMix: [representativeMixEntry('North America', 31), representativeMixEntry('Europe', 28), representativeMixEntry('Asia Pacific', 23), representativeMixEntry('Other', 18)],
    },
    ICE: {
      note: '대표기업 fallback: exchanges / fixed income & data / mortgage tech 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Exchanges', 39), representativeMixEntry('Fixed Income & Data', 31), representativeMixEntry('Mortgage Technology', 30)],
      geoMix: [representativeMixEntry('United States', 67), representativeMixEntry('Europe', 20), representativeMixEntry('Other', 13)],
    },
    CME: {
      note: '대표기업 fallback: interest rate / equity / energy / agri derivatives 거래소 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Interest Rates', 33), representativeMixEntry('Equity Index', 24), representativeMixEntry('Energy', 17), representativeMixEntry('Agri/FX/Metals/Other', 26)],
      geoMix: [representativeMixEntry('United States', 72), representativeMixEntry('Europe', 14), representativeMixEntry('APAC/Other', 14)],
    },
    PGR: {
      note: '대표기업 fallback: personal auto / commercial / property 보험 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Personal Auto', 74), representativeMixEntry('Commercial Lines', 12), representativeMixEntry('Property / Other', 14)],
      geoMix: [representativeMixEntry('United States', 96), representativeMixEntry('Other', 4)],
    },
    CB: {
      note: '대표기업 fallback: commercial P&C / personal P&C / life insurance 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Commercial P&C', 54), representativeMixEntry('Personal P&C', 31), representativeMixEntry('Life Insurance', 15)],
      geoMix: [representativeMixEntry('North America', 53), representativeMixEntry('Europe', 22), representativeMixEntry('Asia Pacific', 16), representativeMixEntry('Other', 9)],
    },
    EOG: {
      note: '대표기업 fallback: U.S. shale 중심 upstream 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Oil', 52), representativeMixEntry('Natural Gas Liquids', 26), representativeMixEntry('Natural Gas', 22)],
      geoMix: [representativeMixEntry('United States', 92), representativeMixEntry('Trinidad/Other', 8)],
    },
    MPC: {
      note: '대표기업 fallback: refining / midstream / retail fuel 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Refining & Marketing', 73), representativeMixEntry('Midstream', 16), representativeMixEntry('Retail / Other', 11)],
      geoMix: [representativeMixEntry('United States', 94), representativeMixEntry('Other', 6)],
    },
    PSX: {
      note: '대표기업 fallback: refining / midstream / chemicals 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Refining', 56), representativeMixEntry('Midstream', 24), representativeMixEntry('Chemicals/Other', 20)],
      geoMix: [representativeMixEntry('United States', 89), representativeMixEntry('Europe/Other', 11)],
    },
    WMB: {
      note: '대표기업 fallback: natural gas transmission / gathering / storage midstream 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Gas Transmission', 49), representativeMixEntry('Gathering & Processing', 31), representativeMixEntry('Storage / Other', 20)],
      geoMix: [representativeMixEntry('United States', 97), representativeMixEntry('Other', 3)],
    },
    TRGP: {
      note: '대표기업 fallback: gathering / processing / NGL logistics midstream 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Gathering & Processing', 54), representativeMixEntry('Logistics & Transportation', 46)],
      geoMix: [representativeMixEntry('United States', 99), representativeMixEntry('Other', 1)],
    },
    SHW: {
      note: '대표기업 fallback: paint stores / consumer brands / performance coatings 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Paint Stores Group', 58), representativeMixEntry('Consumer Brands', 11), representativeMixEntry('Performance Coatings', 31)],
      geoMix: [representativeMixEntry('United States', 66), representativeMixEntry('Latin America', 12), representativeMixEntry('Europe/Other', 22)],
    },
    ECL: {
      note: '대표기업 fallback: institutional / industrial / healthcare / life sciences 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Institutional & Specialty', 38), representativeMixEntry('Industrial', 24), representativeMixEntry('Healthcare & Life Sciences', 23), representativeMixEntry('Other', 15)],
      geoMix: [representativeMixEntry('United States', 43), representativeMixEntry('EMEA', 28), representativeMixEntry('APAC', 18), representativeMixEntry('Latin America', 11)],
    },
    NUE: {
      note: '대표기업 fallback: steel mills / steel products / raw materials 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Steel Mills', 46), representativeMixEntry('Steel Products', 37), representativeMixEntry('Raw Materials', 17)],
      geoMix: [representativeMixEntry('United States', 91), representativeMixEntry('Other', 9)],
    },
    DOW: {
      note: '대표기업 fallback: packaging & specialty plastics / industrial intermediates / coatings 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Packaging & Specialty Plastics', 49), representativeMixEntry('Industrial Intermediates', 29), representativeMixEntry('Performance Materials & Coatings', 22)],
      geoMix: [representativeMixEntry('United States', 34), representativeMixEntry('Europe', 25), representativeMixEntry('Asia Pacific', 26), representativeMixEntry('Other', 15)],
    },
    CTVA: {
      note: '대표기업 fallback: seed / crop protection 농업소재 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Seed', 52), representativeMixEntry('Crop Protection', 48)],
      geoMix: [representativeMixEntry('North America', 39), representativeMixEntry('Latin America', 23), representativeMixEntry('Europe/MEA', 21), representativeMixEntry('Asia Pacific', 17)],
    },
    SO: {
      note: '대표기업 fallback: regulated electric / gas utility와 미국 남동부 전력망 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Electric Utilities', 76), representativeMixEntry('Gas Distribution', 14), representativeMixEntry('Other', 10)],
      geoMix: [representativeMixEntry('United States', 99), representativeMixEntry('Other', 1)],
    },
    DUK: {
      note: '대표기업 fallback: regulated electric / gas utility 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Electric Utilities', 81), representativeMixEntry('Gas Utilities', 11), representativeMixEntry('Other', 8)],
      geoMix: [representativeMixEntry('United States', 99), representativeMixEntry('Other', 1)],
    },
    AEP: {
      note: '대표기업 fallback: regulated electric transmission & distribution 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Vertically Integrated Utilities', 63), representativeMixEntry('Transmission & Distribution', 29), representativeMixEntry('Other', 8)],
      geoMix: [representativeMixEntry('United States', 99), representativeMixEntry('Other', 1)],
    },
    SRE: {
      note: '대표기업 fallback: California utility / Texas utility / LNG 구조를 우선 참고',
      segmentMix: [representativeMixEntry('California Utilities', 47), representativeMixEntry('Texas Utilities', 23), representativeMixEntry('Sempra Infrastructure', 30)],
      geoMix: [representativeMixEntry('United States', 86), representativeMixEntry('Mexico/Other', 14)],
    },
    SPG: {
      note: '대표기업 fallback: premium malls / outlets / mixed-use assets 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Malls', 69), representativeMixEntry('Outlets', 18), representativeMixEntry('Mixed-use / Other', 13)],
      geoMix: [representativeMixEntry('United States', 86), representativeMixEntry('Europe/Asia', 14)],
    },
    WELL: {
      note: '대표기업 fallback: senior housing / outpatient medical / health system real estate 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Senior Housing', 55), representativeMixEntry('Outpatient Medical', 28), representativeMixEntry('Health Systems/Other', 17)],
      geoMix: [representativeMixEntry('United States', 88), representativeMixEntry('Canada/UK', 12)],
    },
    PSA: {
      note: '대표기업 fallback: self-storage 중심 리츠 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Self-Storage', 91), representativeMixEntry('Ancillary / Other', 9)],
      geoMix: [representativeMixEntry('United States', 86), representativeMixEntry('Europe', 8), representativeMixEntry('Other', 6)],
    },
    CCI: {
      note: '대표기업 fallback: towers / small cells / fiber 인프라 리츠 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Towers', 44), representativeMixEntry('Small Cells', 23), representativeMixEntry('Fiber', 33)],
      geoMix: [representativeMixEntry('United States', 91), representativeMixEntry('Other', 9)],
    },
    CBRE: {
      note: '대표기업 fallback: advisory / leasing / occupier solutions / investment management 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Advisory Services', 35), representativeMixEntry('Occupier Solutions', 28), representativeMixEntry('Real Estate Investments', 22), representativeMixEntry('Other', 15)],
      geoMix: [representativeMixEntry('Americas', 57), representativeMixEntry('EMEA', 26), representativeMixEntry('Asia Pacific', 17)],
    },
    IRM: {
      note: '대표기업 fallback: records management / data center / digital services 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Storage Rental', 53), representativeMixEntry('Service', 22), representativeMixEntry('Data Center / Digital', 25)],
      geoMix: [representativeMixEntry('United States', 66), representativeMixEntry('Europe', 17), representativeMixEntry('APAC/Other', 17)],
    },
    NFLX: {
      note: '대표기업 fallback: UCAN / EMEA / LATAM / APAC 구독 구조와 광고형 요금제 확장을 우선 참고',
      segmentMix: [representativeMixEntry('Subscription Streaming', 93), representativeMixEntry('Advertising / Other', 7)],
      geoMix: [representativeMixEntry('UCAN', 44), representativeMixEntry('EMEA', 31), representativeMixEntry('LATAM', 13), representativeMixEntry('APAC', 12)],
    },
    DIS: {
      note: '대표기업 fallback: Entertainment / Sports / Experiences 구조와 북미 소비 노출을 우선 참고',
      segmentMix: [representativeMixEntry('Entertainment', 46), representativeMixEntry('Sports', 20), representativeMixEntry('Experiences', 34)],
      geoMix: [representativeMixEntry('United States & Canada', 61), representativeMixEntry('Europe', 16), representativeMixEntry('APAC', 15), representativeMixEntry('Latin America/Other', 8)],
    },
    CMCSA: {
      note: '대표기업 fallback: Connectivity / Content & Experiences / Business Services를 우선 참고',
      segmentMix: [representativeMixEntry('Connectivity & Platforms', 63), representativeMixEntry('Content & Experiences', 24), representativeMixEntry('Business Services / Other', 13)],
      geoMix: [representativeMixEntry('United States', 87), representativeMixEntry('Europe', 8), representativeMixEntry('Other', 5)],
    },
    KO: {
      note: '대표기업 fallback: Sparkling / Still beverages와 북미·EMEA·LATAM 판매구조를 우선 참고',
      segmentMix: [representativeMixEntry('Sparkling Soft Drinks', 69), representativeMixEntry('Still Beverages', 31)],
      geoMix: [representativeMixEntry('North America', 36), representativeMixEntry('EMEA', 28), representativeMixEntry('Latin America', 18), representativeMixEntry('Asia Pacific', 18)],
    },
    PEP: {
      note: '대표기업 fallback: Beverage / Frito-Lay / Quaker 구조와 북미 소비 비중을 우선 참고',
      segmentMix: [representativeMixEntry('Convenient Foods', 56), representativeMixEntry('Beverages', 44)],
      geoMix: [representativeMixEntry('North America', 61), representativeMixEntry('Latin America', 11), representativeMixEntry('Europe', 14), representativeMixEntry('AMESA/APAC', 14)],
    },
    COST: {
      note: '대표기업 fallback: membership warehouse model과 북미 중심 유통망 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Merchandise Sales', 97), representativeMixEntry('Membership Fees', 3)],
      geoMix: [representativeMixEntry('United States', 73), representativeMixEntry('Canada', 14), representativeMixEntry('Other International', 13)],
    },
    WMT: {
      note: '대표기업 fallback: Walmart U.S. / International / Sam’s Club 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Walmart U.S.', 69), representativeMixEntry('Walmart International', 19), representativeMixEntry('Sam’s Club', 12)],
      geoMix: [representativeMixEntry('United States', 83), representativeMixEntry('Mexico/Central America', 8), representativeMixEntry('China', 4), representativeMixEntry('Other', 5)],
    },
    PM: {
      note: '대표기업 fallback: smoke-free / combustible mix와 유럽·아시아 판매구조를 우선 참고',
      segmentMix: [representativeMixEntry('Combustible Products', 63), representativeMixEntry('Smoke-Free Products', 37)],
      geoMix: [representativeMixEntry('Europe', 38), representativeMixEntry('South & Southeast Asia/CIS/MEA', 33), representativeMixEntry('EA/Australia', 17), representativeMixEntry('Americas', 12)],
    },
    LLY: {
      note: '대표기업 fallback: diabetes/obesity / oncology / immunology 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Diabetes & Obesity', 58), representativeMixEntry('Oncology', 17), representativeMixEntry('Immunology', 14), representativeMixEntry('Neuroscience / Other', 11)],
      geoMix: [representativeMixEntry('United States', 59), representativeMixEntry('Europe', 19), representativeMixEntry('China', 11), representativeMixEntry('Other', 11)],
    },
    JNJ: {
      note: '대표기업 fallback: Innovative Medicine / MedTech 이중 축을 우선 참고',
      segmentMix: [representativeMixEntry('Innovative Medicine', 61), representativeMixEntry('MedTech', 39)],
      geoMix: [representativeMixEntry('United States', 53), representativeMixEntry('Europe', 22), representativeMixEntry('Asia Pacific', 17), representativeMixEntry('Other', 8)],
    },
    ABBV: {
      note: '대표기업 fallback: Immunology / Oncology / Neuroscience / Aesthetics 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Immunology', 42), representativeMixEntry('Neuroscience', 16), representativeMixEntry('Oncology', 14), representativeMixEntry('Aesthetics', 13), representativeMixEntry('Other', 15)],
      geoMix: [representativeMixEntry('United States', 74), representativeMixEntry('Europe', 14), representativeMixEntry('Other International', 12)],
    },
    ISRG: {
      note: '대표기업 fallback: da Vinci system / instruments & accessories / service 구조를 우선 참고',
      segmentMix: [representativeMixEntry('Systems', 28), representativeMixEntry('Instruments & Accessories', 55), representativeMixEntry('Service', 17)],
      geoMix: [representativeMixEntry('United States', 68), representativeMixEntry('Europe', 18), representativeMixEntry('Asia', 11), representativeMixEntry('Other', 3)],
    },
  };

  const fallback = rules[normalized];
  if (!fallback) return current;

  return {
    note: current.note ?? fallback.note,
    segmentMix: current.segmentMix.length >= 2 ? current.segmentMix : fallback.segmentMix,
    geoMix: current.geoMix.length >= 2 ? current.geoMix : fallback.geoMix,
  };
}


function verdictLabel(score: number): '우호' | '양호' | '중립' | '주의' {
  if (score >= 72) return '우호';
  if (score >= 60) return '양호';
  if (score >= 45) return '중립';
  return '주의';
}

function buildCompanyVerdicts(
  financials: CompanyResearchResponse['financials'],
  score: CompanyResearchResponse['score'],
  buyScore: CompanyBuyScore,
): CompanyVerdicts {
  const businessQualityScore = roundScore(score.totalScore * 0.4 + score.quality.value * 0.35 + score.balanceSheet.value * 0.25);
  const valuationBase = financials.evToSales === null ? 58 : roundScore(88 - clamp(financials.evToSales, 0, 20) * 3);
  const timingScore = roundScore((100 - buyScore.crowdingScore) * 0.55 + buyScore.buyScore * 0.45);
  return {
    businessQuality: {
      label: verdictLabel(businessQualityScore),
      score: businessQualityScore,
      summary: businessQualityScore >= 72 ? '사업 품질이 강합니다. 실적·수익성·재무 체력이 받쳐줍니다.' : businessQualityScore >= 60 ? '사업 품질은 양호합니다. 다만 최고 수준까지는 아닙니다.' : businessQualityScore >= 45 ? '사업 품질은 보통 수준입니다. 확실한 우위는 더 확인이 필요합니다.' : '사업 품질이 약합니다. 구조 우위가 선명하지 않습니다.',
    },
    valuation: {
      label: verdictLabel(valuationBase),
      score: valuationBase,
      summary: valuationBase >= 72 ? '가격 부담이 비교적 낮습니다.' : valuationBase >= 60 ? '밸류는 무난합니다.' : valuationBase >= 45 ? '밸류 부담이 조금 있습니다.' : '좋은 회사여도 가격 부담이 큰 구간입니다.',
    },
    timing: {
      label: verdictLabel(timingScore),
      score: timingScore,
      summary: timingScore >= 72 ? '타이밍이 우호적입니다. 분할 매수 접근이 가능합니다.' : timingScore >= 60 ? '나쁘지 않지만 서두르지 않는 편이 좋습니다.' : timingScore >= 45 ? '좋은 회사여도 지금은 관찰 구간에 가깝습니다.' : '타이밍은 불리합니다. 추격보다 대기·축소가 유리합니다.',
    },
    finalAction: {
      label: verdictLabel(buyScore.buyScore),
      score: buyScore.buyScore,
      summary: buyScore.buyScore >= 72 ? '좋은 회사이면서 지금도 비교적 좋은 투자에 가깝습니다.' : buyScore.buyScore >= 56 ? '좋은 회사일 수 있지만 지금은 선별적 접근이 더 적절합니다.' : '좋은 회사라도 지금은 좋은 투자일 가능성이 낮습니다.',
    },
    oneLiners: {
      business: businessQualityScore >= 72 ? '사업 체력은 강한 편입니다. 회사 자체는 믿고 볼 수 있습니다.' : businessQualityScore >= 60 ? '사업 체력은 양호합니다. 다만 압도적 우위까지는 아닙니다.' : businessQualityScore >= 45 ? '회사 퀄리티는 보통입니다. 확실한 우위는 더 확인이 필요합니다.' : '회사 체력은 약한 편입니다. 서두를 이유가 적습니다.',
      valuation: valuationBase >= 72 ? '가격 부담은 낮은 편이라 기업 상태 대비 무리는 적습니다.' : valuationBase >= 60 ? '가격은 무난합니다. 싸다고 보긴 어렵지만 과도하진 않습니다.' : valuationBase >= 45 ? '회사 대비 가격이 조금 앞서 있습니다. 분할 접근이 낫습니다.' : '좋은 회사여도 가격이 너무 앞서 있어 밸류 부담이 큽니다.',
      timing: timingScore >= 72 ? '타이밍은 비교적 우호적입니다. 1차 진입을 검토할 만합니다.' : timingScore >= 60 ? '회사는 좋아도 지금은 서두르지 않는 편이 낫습니다.' : timingScore >= 45 ? '지금은 관찰이 더 적절합니다. 조정이나 확인이 더 필요합니다.' : '지금은 타이밍이 불리합니다. 새 매수보다 대기가 우선입니다.',
      action: buyScore.buyScore >= 72 ? '좋은 회사이면서 지금도 매수 후보입니다. 다만 분할 기준은 지켜야 합니다.' : buyScore.buyScore >= 56 ? '좋은 회사지만 지금은 선별 접근이 맞습니다. 1차 비중만 작게 보는 편이 좋습니다.' : '좋은 회사여도 지금은 추격보다 대기나 축소가 더 나은 구간입니다.',
    },
  };
}

function bottomMetricStatus(score: number | null): BottomMetricStatus {
  if (score === null || Number.isNaN(score)) return 'neutral';
  if (score >= 68) return 'positive';
  if (score >= 45) return 'neutral';
  return 'negative';
}

function buildCompanyBottomSignal(
  financials: CompanyResearchResponse['financials'],
  score: CompanyResearchResponse['score'],
  buyScore: CompanyBuyScore,
  narrative: CompanyNarrativeInsight | null,
  cashFlowQuality: CompanyCashFlowQualityInsight | null,
  multipleInsight: CompanyMultipleInsight | null,
  guidanceInsight: CompanyGuidanceInsight | null,
  priceContext: CompanyPriceContext,
): CompanyBottomSignal {
  const valuationSupport = multipleInsight?.valuationVsPeer === '할인'
    ? 78
    : multipleInsight?.valuationVsPeer === '중립'
      ? 60
      : multipleInsight?.valuationVsPeer === '프리미엄'
        ? 38
        : financials.evToSales !== null
          ? roundScore(clamp(82 - financials.evToSales * 4, 25, 82))
          : 55;
  const businessDurability = roundScore((score.totalScore * 0.55) + ((cashFlowQuality?.cashConversionScore ?? score.balanceSheet.value) * 0.45));
  const expectationReset = roundScore(clamp(
    52
      + (financials.estimateRevision30d !== null ? clamp(financials.estimateRevision30d * 1.8, -18, 18) : 0)
      + (financials.estimateRevision7d !== null ? clamp(financials.estimateRevision7d * 1.2, -10, 10) : 0)
      + (financials.analystScoreRevision30d !== null ? clamp(financials.analystScoreRevision30d * 8, -12, 12) : 0)
      + (buyScore.crowdingScore <= 55 ? 8 : buyScore.crowdingScore >= 70 ? -12 : 0),
    15,
    90,
  ));
  const priceReset = roundScore(clamp(
    48
      + (priceContext.drawdownFromHighPct !== null ? (priceContext.drawdownFromHighPct <= -25 ? 18 : priceContext.drawdownFromHighPct <= -12 ? 9 : -6) : 0)
      + (priceContext.reboundFromLowPct !== null ? (priceContext.reboundFromLowPct >= 15 && priceContext.reboundFromLowPct <= 55 ? 14 : priceContext.reboundFromLowPct > 75 ? -8 : 0) : 0)
      + (priceContext.volumeTrend20d !== null ? clamp(priceContext.volumeTrend20d * 0.35, -8, 12) : 0)
      + (priceContext.return30d !== null ? (priceContext.return30d >= 25 ? -10 : priceContext.return30d >= 8 ? 5 : priceContext.return30d <= -12 ? -6 : 0) : 0),
    15,
    90,
  ));
  const patternScore = roundScore(clamp(
    priceContext.patternPhase === 'confirm' ? 82
      : priceContext.patternPhase === 'retest' ? 64
        : priceContext.patternPhase === 'candidate' ? 56
          : 34,
    15,
    90,
  ));
  const absorptionScore = roundScore(clamp(
    48
      + (priceContext.absorptionVolumeVsRecent3dRatio !== null ? clamp((priceContext.absorptionVolumeVsRecent3dRatio - 1) * 26, -12, 20) : 0)
      + (
        priceContext.absorptionDropPct !== null
        && priceContext.priorDeclineDropPct !== null
        && priceContext.absorptionDropPct < 0
        && priceContext.priorDeclineDropPct < 0
          ? (
            Math.abs(priceContext.absorptionDropPct) <= Math.abs(priceContext.priorDeclineDropPct) * 0.7 ? 18
              : Math.abs(priceContext.absorptionDropPct) <= Math.abs(priceContext.priorDeclineDropPct) * 0.9 ? 12
                : Math.abs(priceContext.absorptionDropPct) <= Math.abs(priceContext.priorDeclineDropPct) ? 6
                  : -10
          )
          : 0
      ),
    15,
    90,
  ));
  const volumeConfirmationScore = roundScore(clamp(
    48
      + (priceContext.candidateVolumeRatio !== null ? clamp((priceContext.candidateVolumeRatio - 1) * 20, -10, 16) : 0)
      + (priceContext.confirmVolumeRatio !== null ? clamp((priceContext.confirmVolumeRatio - 1) * 22, -8, 18) : 0)
      + (priceContext.retestVolumeRatio !== null ? clamp((1 - priceContext.retestVolumeRatio) * 18, -12, 12) : 0)
      + (priceContext.volumeTrend20d !== null ? clamp(priceContext.volumeTrend20d * 0.2, -8, 10) : 0)
      + (priceContext.absorptionVolumeVsRecent3dRatio !== null ? clamp((priceContext.absorptionVolumeVsRecent3dRatio - 1) * 18, -8, 14) : 0)
      + (
        priceContext.absorptionDropPct !== null
        && priceContext.priorDeclineDropPct !== null
        && priceContext.absorptionDropPct < 0
        && priceContext.priorDeclineDropPct < 0
          ? (
            Math.abs(priceContext.absorptionDropPct) <= Math.abs(priceContext.priorDeclineDropPct) * 0.8 ? 12
              : Math.abs(priceContext.absorptionDropPct) <= Math.abs(priceContext.priorDeclineDropPct) ? 6
                : -8
          )
          : 0
      ),
    15,
    90,
  ));
  const guidanceSupport = guidanceInsight?.stance === 'raised'
    ? 82
    : guidanceInsight?.stance === 'affirmed'
      ? 66
      : guidanceInsight?.stance === 'mixed'
        ? 48
        : guidanceInsight?.stance === 'lowered'
          ? 28
          : 52;
  const narrativeTemperature = narrative?.stage === 'EARLY'
    ? 74
    : narrative?.stage === 'MID'
      ? 58
      : 36;

  const metrics: BottomSignalMetric[] = [
    {
      key: 'valuation',
      label: '밸류 리셋',
      score: valuationSupport,
      status: bottomMetricStatus(valuationSupport),
      detail: multipleInsight?.valuationVsPeer === '할인'
        ? '동종 대비 할인권이라 바닥 후보로 해석할 여지가 있습니다.'
        : multipleInsight?.valuationVsPeer === '프리미엄'
          ? '동종 대비 프리미엄이 커 아직 바닥 확인으로 보기 어렵습니다.'
          : '동종 대비 밸류 부담은 중립 수준입니다.',
    },
    {
      key: 'business',
      label: '사업 체력',
      score: businessDurability,
      status: bottomMetricStatus(businessDurability),
      detail: cashFlowQuality?.summary ?? '실적·수익성·재무 체력이 바닥 방어력을 만듭니다.',
    },
    {
      key: 'expectation',
      label: '기대치 정리',
      score: expectationReset,
      status: bottomMetricStatus(expectationReset),
      detail: financials.estimateRevision30d !== null
        ? `30일 컨센서스 변화 ${financials.estimateRevision30d >= 0 ? '+' : ''}${financials.estimateRevision30d.toFixed(1)}%p`
        : '컨센서스 리비전 데이터가 제한적입니다.',
    },
    {
      key: 'price',
      label: '가격 리셋',
      score: priceReset,
      status: bottomMetricStatus(priceReset),
      detail: [
        priceContext.drawdownFromHighPct !== null ? `고점대비 ${priceContext.drawdownFromHighPct}%` : null,
        priceContext.reboundFromLowPct !== null ? `저점대비 +${priceContext.reboundFromLowPct}%` : null,
        priceContext.volumeTrend20d !== null ? `거래량 추세 ${priceContext.volumeTrend20d >= 0 ? '+' : ''}${priceContext.volumeTrend20d}%` : null,
      ].filter((item): item is string => Boolean(item)).join(' · ') || '가격/거래량 데이터 부족',
    },
    {
      key: 'pattern',
      label: '바닥 패턴',
      score: patternScore,
      status: bottomMetricStatus(patternScore),
      detail: priceContext.patternPhase === 'confirm'
        ? '저점 후보 이후 재시험을 거쳐 1차 확인 돌파가 나온 패턴입니다.'
        : priceContext.patternPhase === 'retest'
          ? `저점 후보 이후 재시험 진행 중입니다${priceContext.retestGapPct !== null ? ` (${priceContext.retestGapPct >= 0 ? '+' : ''}${priceContext.retestGapPct.toFixed(1)}%)` : ''}.`
          : priceContext.patternPhase === 'candidate'
            ? '저점 후보 이후 반등은 나왔지만 재시험/돌파 확인이 더 필요합니다.'
            : '아직 하락 정지보다 낙하 구간에 가깝습니다.',
    },
    {
      key: 'volume',
      label: '거래량 동반',
      score: volumeConfirmationScore,
      status: bottomMetricStatus(volumeConfirmationScore),
      detail: [
        priceContext.candidateVolumeRatio !== null ? `저점후보 거래량 ${priceContext.candidateVolumeRatio}배` : null,
        priceContext.retestVolumeRatio !== null ? `재시험 거래량 ${priceContext.retestVolumeRatio}배` : null,
        priceContext.confirmVolumeRatio !== null ? `확인돌파 거래량 ${priceContext.confirmVolumeRatio}배` : null,
      ].filter((item): item is string => Boolean(item)).join(' · ') || '거래량 확인 데이터 부족',
    },
    {
      key: 'absorption',
      label: '하락 흡수',
      score: absorptionScore,
      status: bottomMetricStatus(absorptionScore),
      detail: [
        priceContext.absorptionVolumeVsRecent3dRatio !== null ? `최근 2~3봉 대비 ${priceContext.absorptionVolumeVsRecent3dRatio}배` : null,
        priceContext.absorptionDropPct !== null ? `현재 하락 ${priceContext.absorptionDropPct}%` : null,
        priceContext.priorDeclineDropPct !== null ? `이전 하락 ${priceContext.priorDeclineDropPct}%` : null,
      ].filter((item): item is string => Boolean(item)).join(' · ') || '하락 흡수 비교 데이터 부족',
    },
    {
      key: 'guidance',
      label: '가이던스',
      score: guidanceSupport,
      status: bottomMetricStatus(guidanceSupport),
      detail: guidanceInsight?.summary ?? '가이던스 확인 전이라 실적 바닥 확정으로 보기엔 이릅니다.',
    },
    {
      key: 'narrative',
      label: '내러티브 온도',
      score: narrativeTemperature,
      status: bottomMetricStatus(narrativeTemperature),
      detail: narrative
        ? `${narrative.title} · ${narrative.stage} · heat ${narrative.heatScore}`
        : '내러티브 쏠림 데이터가 제한적입니다.',
    },
  ];

  const bottomScore = roundScore(
    valuationSupport * 0.14
    + businessDurability * 0.18
    + expectationReset * 0.14
    + priceReset * 0.12
    + patternScore * 0.14
    + volumeConfirmationScore * 0.2
    + guidanceSupport * 0.04
    + narrativeTemperature * 0.04,
  );
  const earningsBottomScore = roundScore(
    expectationReset * 0.38
    + guidanceSupport * 0.24
    + businessDurability * 0.24
    + valuationSupport * 0.14,
  );
  const priceBottomScore = roundScore(
    priceReset * 0.22
    + patternScore * 0.28
    + volumeConfirmationScore * 0.38
    + (100 - buyScore.crowdingScore) * 0.12,
  );

  const state: CompanyBottomSignal['state'] =
    volumeConfirmationScore >= 72 && priceContext.patternPhase === 'confirm' ? '구조적 바닥 가능'
      : volumeConfirmationScore >= 64 && (priceContext.patternPhase === 'confirm' || priceContext.patternPhase === 'retest') ? '1차 확인'
        : priceContext.patternPhase === 'retest' ? '재시험 구간'
          : volumeConfirmationScore >= 54 || priceContext.patternPhase === 'candidate' ? '바닥 시도'
            : '바닥 아님';
  const actionBias: CompanyBottomSignal['actionBias'] =
    volumeConfirmationScore >= 74 ? '분할 매수'
      : volumeConfirmationScore >= 60 ? '관찰 매수'
        : volumeConfirmationScore >= 48 ? '확인 우선'
          : '대기';
  const summary = state === '구조적 바닥 가능'
    ? '거래량이 붙은 확인 돌파까지 나와 진짜 바닥일 가능성이 높은 구간입니다.'
    : state === '1차 확인'
      ? '재시험 이후 거래량이 붙기 시작해 바닥 확인 신뢰도가 올라오는 구간입니다.'
      : state === '재시험 구간'
        ? '가격은 버티지만 거래량 확인이 아직 충분하지 않아 재시험 통과를 더 봐야 합니다.'
        : state === '바닥 시도'
          ? '반등은 나왔지만 거래량이 충분히 붙지 않아 진짜 바닥으로 보기엔 이릅니다.'
          : '좋은 회사일 수 있어도 거래량이 동반되지 않아 아직 바닥으로 단정하기 어렵습니다.';

  const reasons = metrics
    .filter((item) => item.status === 'positive')
    .map((item) => `${item.label}: ${item.detail}`)
    .slice(0, 4);
  const cautions = metrics
    .filter((item) => item.status === 'negative')
    .map((item) => `${item.label}: ${item.detail}`)
    .slice(0, 4);
  const failureSignals: string[] = [
    priceContext.patternPhase === 'decline' ? '아직 하락 단계라 바닥 패턴 자체가 완성되지 않았습니다.' : null,
    volumeConfirmationScore < 55 ? '반등 대비 거래량이 약해 진짜 바닥보다 기술적 반등일 가능성을 열어둬야 합니다.' : null,
    priceContext.absorptionVolumeVsRecent3dRatio !== null && priceContext.absorptionVolumeVsRecent3dRatio < 1 ? '최근 2~3개 봉 대비 거래량이 더 붙지 않아 매도 흡수 신호가 약합니다.' : null,
    priceContext.absorptionDropPct !== null && priceContext.priorDeclineDropPct !== null
      && priceContext.absorptionDropPct < 0 && priceContext.priorDeclineDropPct < 0
      && Math.abs(priceContext.absorptionDropPct) > Math.abs(priceContext.priorDeclineDropPct)
      ? '거래량은 붙어도 낙폭이 줄지 않아 아직 진바닥 흡수로 보기 어렵습니다.'
      : null,
    priceContext.patternPhase === 'retest' && priceContext.retestGapPct !== null && priceContext.retestGapPct < -4 ? '재시험이 저점 후보를 의미 있게 하회해 바닥 실패 가능성이 있습니다.' : null,
    priceContext.confirmVolumeRatio !== null && priceContext.confirmVolumeRatio < 0.95 ? '확인 돌파 구간 거래량이 약해 가짜 돌파일 수 있습니다.' : null,
    guidanceInsight?.stance === 'lowered' ? '가이던스가 하향이라 실적 바닥 실패 가능성을 열어둬야 합니다.' : null,
    financials.estimateRevision30d !== null && financials.estimateRevision30d <= -4 ? '컨센서스가 계속 내려가며 실적 바닥 확인을 지연시키고 있습니다.' : null,
    buyScore.crowdingScore >= 72 ? '과열도가 높아 바닥 확인 이후에도 추격 리스크가 큽니다.' : null,
  ].filter((item): item is string => Boolean(item));
  const failureRiskScore = roundScore(clamp(
      22
      + (priceContext.patternPhase === 'decline' ? 22 : 0)
      + (volumeConfirmationScore < 55 ? 14 : volumeConfirmationScore < 62 ? 8 : 0)
      + (priceContext.absorptionVolumeVsRecent3dRatio !== null && priceContext.absorptionVolumeVsRecent3dRatio < 1 ? 10 : 0)
      + (
        priceContext.absorptionDropPct !== null
        && priceContext.priorDeclineDropPct !== null
        && priceContext.absorptionDropPct < 0
        && priceContext.priorDeclineDropPct < 0
        && Math.abs(priceContext.absorptionDropPct) > Math.abs(priceContext.priorDeclineDropPct)
          ? 12
          : 0
      )
      + (priceContext.patternPhase === 'retest' && priceContext.retestGapPct !== null && priceContext.retestGapPct < -4 ? 18 : 0)
      + (priceContext.confirmVolumeRatio !== null && priceContext.confirmVolumeRatio < 0.95 ? 12 : 0)
      + (guidanceInsight?.stance === 'lowered' ? 18 : 0)
      + (financials.estimateRevision30d !== null ? clamp((-financials.estimateRevision30d) * 2.2, 0, 18) : 0)
      + (buyScore.crowdingScore >= 72 ? 10 : 0),
    0,
    100,
  ));
  const markers: BottomSignalChartMarker[] = [];
  if (priceContext.peakPoint) markers.push({ kind: 'peak', date: priceContext.peakPoint.date, value: priceContext.peakPoint.value, label: '하락 시작 고점' });
  if (priceContext.lowPoint) markers.push({ kind: 'candidate', date: priceContext.lowPoint.date, value: priceContext.lowPoint.value, label: '저점 후보' });
  if (priceContext.retestPoint) {
    markers.push({ kind: 'retest', date: priceContext.retestPoint.date, value: priceContext.retestPoint.value, label: '재시험 저점' });
  }
  if (priceContext.confirmPoint) {
    markers.push({ kind: 'confirm', date: priceContext.confirmPoint.date, value: priceContext.confirmPoint.value, label: '거래량 확인 돌파' });
  } else if (priceContext.lowPoint && priceContext.latestPoint && priceContext.lowPoint.date !== priceContext.latestPoint.date) {
    markers.push({
      kind: priceContext.patternPhase === 'retest' ? 'retest' : 'current',
      date: priceContext.latestPoint.date,
      value: priceContext.latestPoint.value,
      label: priceContext.patternPhase === 'retest' ? '재시험/관찰 구간' : '반등 진행 구간',
    });
  }
  if (priceContext.latestPoint) markers.push({ kind: 'current', date: priceContext.latestPoint.date, value: priceContext.latestPoint.value, label: '현재' });

  const confirmedBottom = buildConfirmedDeepBottomSignal(priceContext, failureRiskScore);

  return {
    score: bottomScore,
    state,
    actionBias,
    summary,
    earningsBottomScore,
    priceBottomScore,
    volumeConfirmationScore,
    failureRiskScore,
    metrics,
    chart: {
      points: priceContext.points,
      markers,
    },
    confirmedBottom,
    reasons: reasons.length ? reasons : ['아직 구조적 바닥으로 볼 강한 긍정 신호는 제한적입니다.'],
    cautions: cautions.length ? cautions : ['치명적인 경고는 크지 않지만 분할 접근 원칙은 유지하는 편이 좋습니다.'],
    failureSignals,
  };
}

function buildConfirmedDeepBottomSignal(
  priceContext: CompanyPriceContext,
  failureRiskScore: number | null,
): DeepBottomSignal {
  const recentVolumeRatio = priceContext.absorptionVolumeVsRecent2dRatio !== null
    && priceContext.absorptionVolumeVsRecent3dRatio !== null
    ? Math.min(
      priceContext.absorptionVolumeVsRecent2dRatio,
      priceContext.absorptionVolumeVsRecent3dRatio,
    )
    : null;
  const contractionRatio = priceContext.absorptionContractionRatio;

  const score = roundScore(clamp(
    18
      + (priceContext.drawdownFrom120dHighPct !== null
        ? priceContext.drawdownFrom120dHighPct <= -25 ? 18
          : priceContext.drawdownFrom120dHighPct <= -20 ? 14
            : priceContext.drawdownFrom120dHighPct <= -15 ? 10
              : priceContext.drawdownFrom120dHighPct <= -10 ? 4
                : -6
        : 0)
      + (recentVolumeRatio !== null
        ? recentVolumeRatio >= 1.25 ? 18
          : recentVolumeRatio >= 1.1 ? 14
            : recentVolumeRatio >= 1 ? 8
              : -8
        : 0)
      + (contractionRatio !== null
        ? contractionRatio <= 0.6 ? 18
          : contractionRatio <= 0.8 ? 14
            : contractionRatio <= 1 ? 8
              : -10
        : 0)
      + (priceContext.recentDrop3dPct !== null
        ? priceContext.recentDrop3dPct <= -10 ? 18
          : priceContext.recentDrop3dPct <= -8 ? 15
            : priceContext.recentDrop3dPct <= -5 ? 10
              : priceContext.recentDrop3dPct <= -3 ? 4
                : -6
        : 0)
      + (priceContext.ma20GapPct !== null
        ? priceContext.ma20GapPct <= -10 ? 18
          : priceContext.ma20GapPct <= -8 ? 14
            : priceContext.ma20GapPct <= -6 ? 10
              : priceContext.ma20GapPct <= -2 ? 4
                : -8
        : 0)
      + (priceContext.ma20Below50 ? 10 : -12)
      + (priceContext.daysSinceAbsorption !== null
        ? priceContext.daysSinceAbsorption > 40 ? -18
          : priceContext.daysSinceAbsorption > 25 ? -10
            : priceContext.daysSinceAbsorption > 15 ? -4
              : 4
        : 0)
      + (priceContext.reboundSinceAbsorptionPct !== null
        ? priceContext.reboundSinceAbsorptionPct > 40 ? -18
          : priceContext.reboundSinceAbsorptionPct > 25 ? -12
            : priceContext.reboundSinceAbsorptionPct > 15 ? -6
              : priceContext.reboundSinceAbsorptionPct >= 0 ? 4
                : -4
        : 0)
      + (failureRiskScore !== null ? clamp((45 - failureRiskScore) * 0.35, -16, 12) : 0),
    0,
    100,
  ));

  const state: DeepBottomSignal['state'] =
    score >= 78
      && (priceContext.daysSinceAbsorption ?? 999) <= 25
      && (priceContext.reboundSinceAbsorptionPct ?? 999) <= 25
      && recentVolumeRatio !== null && recentVolumeRatio >= 1.1
      && contractionRatio !== null
      ? '확신'
      : score >= 62
        ? '후보'
        : '미충족';

  const actionBias: DeepBottomSignal['actionBias'] =
    state === '확신' ? '분할 매수'
      : state === '후보' ? '관찰 매수'
        : '대기';

  const reasons = [
    recentVolumeRatio !== null && recentVolumeRatio >= 1.1 ? `직전 3개 거래일 최대 대비 거래량 ${recentVolumeRatio}배로 투매 흡수 흔적` : null,
    contractionRatio !== null && contractionRatio <= 0.8 ? `낙폭이 직전 하락의 ${(contractionRatio * 100).toFixed(0)}% 수준으로 둔화` : null,
    priceContext.recentDrop3dPct !== null && priceContext.recentDrop3dPct <= -5 ? `직전 3일 누적 하락 ${priceContext.recentDrop3dPct}%로 급락 구간 통과` : null,
    priceContext.ma20GapPct !== null && priceContext.ma20GapPct <= -8 ? `20일선 대비 ${priceContext.ma20GapPct}% 이격으로 과매도 구간` : null,
    priceContext.drawdownFrom120dHighPct !== null && priceContext.drawdownFrom120dHighPct <= -15 ? `120일 고점 대비 ${priceContext.drawdownFrom120dHighPct}% 하락` : null,
  ].filter((item): item is string => Boolean(item)).slice(0, 4);

  const cautions = [
    recentVolumeRatio === null ? '직전 3개 거래일과 비교할 거래량 근거가 부족합니다.' : null,
    recentVolumeRatio !== null && recentVolumeRatio < 1.1 ? '직전 3개 거래일 최대 거래량 대비 우위가 약합니다.' : null,
    contractionRatio === null ? '하락일 투매 흡수 조건이 확인되지 않았습니다.' : null,
    contractionRatio !== null && contractionRatio > 0.8 ? '낙폭 축소가 충분하지 않아 흡수 신호가 약합니다.' : null,
    priceContext.ma20GapPct !== null && priceContext.ma20GapPct > -8 ? '20일선 이격이 작아 강한 투매성 바닥으로 보기 어렵습니다.' : null,
    priceContext.daysSinceAbsorption !== null && priceContext.daysSinceAbsorption > 25 ? '신호 발생 후 시간이 지나 초기 바닥 초입 매력은 줄었습니다.' : null,
    priceContext.reboundSinceAbsorptionPct !== null && priceContext.reboundSinceAbsorptionPct > 25 ? '신호 이후 이미 많이 반등해 초기 진입 구간은 일부 지나갔습니다.' : null,
    failureRiskScore !== null && failureRiskScore >= 55 ? '실패 위험 점수가 높아 확신형 신호라도 보수적 비중이 필요합니다.' : null,
  ].filter((item): item is string => Boolean(item)).slice(0, 4);

  const summary = state === '확신'
    ? '미래 반등 확인 없이도 하락장·거래량 급증·낙폭 축소·과매도 조건이 함께 충족된 확신형 바닥 신호입니다.'
    : state === '후보'
      ? '당시 데이터만 봐도 강한 바닥 후보 조건이 일부 충족됐지만, 아직 확신형으로 부르기엔 한두 조건이 부족합니다.'
      : '현재 구간은 확신형 바닥 신호로 보기 어렵습니다. 일반 바닥 후보 정도로만 해석하는 편이 안전합니다.';

  return {
    score,
    state,
    actionBias,
    signalDate: priceContext.absorptionDate,
    daysSinceSignal: priceContext.daysSinceAbsorption,
    summary,
    recentVolumeRatio,
    contractionRatio,
    drawdown120dPct: priceContext.drawdownFrom120dHighPct,
    ma20GapPct: priceContext.ma20GapPct,
    recentDrop3dPct: priceContext.recentDrop3dPct,
    reasons,
    cautions,
  };
}

function buildReversalConfirmation(bottomSignal: CompanyBottomSignal | null): CompanyReversalConfirmation | null {
  if (!bottomSignal) return null;
  const confirmed = bottomSignal.confirmedBottom;
  const fallbackScore = roundScore(((bottomSignal.volumeConfirmationScore ?? 45) * 0.6) + ((bottomSignal.priceBottomScore ?? 45) * 0.4));
  const score = confirmed?.score ?? fallbackScore;
  const signalDate = confirmed?.signalDate ?? bottomSignal.chart.markers.find((item) => item.kind === 'confirm')?.date ?? null;
  const status: CompanyReversalConfirmation['status'] =
    confirmed?.state === '확신' && score >= 82 ? 'STRONG'
      : confirmed?.state === '확신' ? 'ON'
        : confirmed?.state === '후보' || bottomSignal.state === '구조적 바닥 가능' || bottomSignal.state === '1차 확인' ? 'EARLY'
          : 'OFF';
  const summary = status === 'STRONG'
    ? '거래량과 가격 재확인이 함께 붙어 반전 확인 신호가 강한 편입니다.'
    : status === 'ON'
      ? '반전 확인 신호가 켜졌습니다. 추가 추격보다 분할 기준을 지키며 접근할 만한 구간입니다.'
      : status === 'EARLY'
        ? '바닥 후보는 형성됐지만 아직 추세 반전 확신까지는 한두 단계가 더 필요합니다.'
        : '반전 확인 신호는 아직 꺼져 있습니다. 가격 반등만으로 확신하기는 이릅니다.';
  return {
    status,
    score,
    signalDate,
    summary,
    reasons: (confirmed?.reasons?.length ? confirmed.reasons : bottomSignal.reasons).slice(0, 4),
    cautions: (confirmed?.cautions?.length ? confirmed.cautions : bottomSignal.failureSignals ?? bottomSignal.cautions).slice(0, 4),
  };
}

function buildCorrectionAssessment(
  financials: CompanyResearchResponse['financials'],
  score: CompanyResearchResponse['score'],
  buyScore: CompanyBuyScore,
  guidanceInsight: CompanyGuidanceInsight | null,
  narrative: CompanyNarrativeInsight | null,
  bottomSignal: CompanyBottomSignal | null,
  sectorContext: CompanySectorContext | null,
  reversalConfirmation: CompanyReversalConfirmation | null,
): CompanyCorrectionAssessment {
  const failureRisk = bottomSignal?.failureRiskScore ?? null;
  const sectorBuyScore = sectorContext?.buyScore ?? null;
  const correctionScore = roundScore(clamp(
    52
      + (guidanceInsight?.stance === 'raised' ? 16 : guidanceInsight?.stance === 'affirmed' ? 12 : guidanceInsight?.stance === 'mixed' ? 4 : guidanceInsight?.stance === 'lowered' ? -22 : 0)
      + (financials.estimateRevision30d !== null ? clamp(financials.estimateRevision30d * 2.2, -16, 16) : 0)
      + (financials.estimateRevision7d !== null ? clamp(financials.estimateRevision7d * 1.3, -10, 10) : 0)
      + (financials.analystScoreRevision30d !== null ? clamp(financials.analystScoreRevision30d * 10, -10, 10) : 0)
      + (score.totalScore >= 72 ? 10 : score.totalScore >= 60 ? 4 : -10)
      + (sectorBuyScore !== null ? (sectorBuyScore >= 68 ? 8 : sectorBuyScore >= 55 ? 2 : -8) : 0)
      + (bottomSignal?.state === '구조적 바닥 가능' ? 8 : bottomSignal?.state === '1차 확인' ? 5 : bottomSignal?.state === '재시험 구간' ? 2 : 0)
      + (reversalConfirmation?.status === 'STRONG' ? 10 : reversalConfirmation?.status === 'ON' ? 7 : reversalConfirmation?.status === 'EARLY' ? 3 : 0)
      + (failureRisk !== null ? (failureRisk <= 35 ? 10 : failureRisk <= 50 ? 4 : failureRisk >= 70 ? -14 : failureRisk >= 58 ? -8 : 0) : 0)
      + (narrative?.stage === 'EARLY' ? 4 : narrative?.stage === 'OVERHEATED' ? -6 : 0)
      - (buyScore.crowdingScore >= 72 ? 6 : 0),
    0,
    100,
  ));
  const trendBreakRiskScore = roundScore(clamp(
    34
      + (guidanceInsight?.stance === 'lowered' ? 26 : guidanceInsight?.stance === 'mixed' ? 10 : guidanceInsight?.stance === 'affirmed' ? -8 : guidanceInsight?.stance === 'raised' ? -14 : 0)
      + (financials.estimateRevision30d !== null ? (financials.estimateRevision30d < 0 ? clamp((-financials.estimateRevision30d) * 2.5, 0, 20) : clamp(-financials.estimateRevision30d * 1.2, -10, 0)) : 0)
      + (financials.estimateRevision90d !== null ? (financials.estimateRevision90d < 0 ? clamp((-financials.estimateRevision90d) * 1.6, 0, 12) : clamp(-financials.estimateRevision90d * 0.9, -8, 0)) : 0)
      + (financials.analystScoreRevision30d !== null ? (financials.analystScoreRevision30d < 0 ? clamp((-financials.analystScoreRevision30d) * 12, 0, 14) : clamp(-financials.analystScoreRevision30d * 8, -10, 0)) : 0)
      + (failureRisk !== null ? clamp((failureRisk - 42) * 0.55, -8, 24) : 0)
      + (score.totalScore < 55 ? 14 : score.totalScore < 65 ? 6 : score.totalScore >= 75 ? -8 : 0)
      + (sectorBuyScore !== null ? (sectorBuyScore < 50 ? 10 : sectorBuyScore < 58 ? 4 : sectorBuyScore >= 70 ? -6 : 0) : 0)
      + (reversalConfirmation?.status === 'STRONG' ? -14 : reversalConfirmation?.status === 'ON' ? -10 : reversalConfirmation?.status === 'EARLY' ? -4 : 0)
      + (buyScore.crowdingScore >= 78 ? 6 : 0),
    0,
    100,
  ));

  const verdict: CompanyCorrectionAssessment['verdict'] =
    correctionScore >= 68 && trendBreakRiskScore <= 44
      ? '조정 우세'
      : trendBreakRiskScore >= 62
        ? '추세전환 경계'
        : '혼합';
  const actionBias: CompanyCorrectionAssessment['actionBias'] =
    verdict === '조정 우세'
      ? '눌림 매수 가능'
      : verdict === '추세전환 경계'
        ? '방어 우선'
        : '확인 후 접근';
  const summary = verdict === '조정 우세'
    ? '현재 하락은 사업가설 훼손보다 외부 충격성 조정일 가능성이 더 높습니다.'
    : verdict === '추세전환 경계'
      ? '이번 하락은 단순 조정보다 내러티브/실적 기대 훼손을 먼저 점검해야 하는 구간입니다.'
      : '조정일 수도 있지만 추세 훼손 가능성도 열려 있어 후속 실적·가이던스 확인이 필요합니다.';

  const reasons = [
    guidanceInsight?.stance === 'raised' ? '최근 가이던스가 상향돼 실적 가설이 유지됩니다.' : null,
    guidanceInsight?.stance === 'affirmed' ? '가이던스가 유지돼 숫자 가설이 아직 살아 있습니다.' : null,
    score.totalScore >= 72 ? `총점 ${score.totalScore}/100으로 사업 체력이 높습니다.` : null,
    financials.estimateRevision30d !== null && financials.estimateRevision30d >= 0 ? `30일 컨센서스 ${financials.estimateRevision30d >= 0 ? '+' : ''}${financials.estimateRevision30d.toFixed(1)}%p로 기대가 무너지지 않았습니다.` : null,
    sectorBuyScore !== null && sectorBuyScore >= 68 ? `${sectorContext.label} 섹터 B${sectorBuyScore}로 업종 바람도 우호적입니다.` : null,
    reversalConfirmation?.status === 'STRONG' || reversalConfirmation?.status === 'ON' ? `반전 확인 신호 ${reversalConfirmation.status} 상태입니다.` : null,
    failureRisk !== null && failureRisk <= 40 ? `바닥 실패 위험 ${failureRisk}/100으로 아직 통제 가능한 편입니다.` : null,
  ].filter((item): item is string => Boolean(item)).slice(0, 4);

  const risks = [
    guidanceInsight?.stance === 'lowered' ? '가이던스 하향이 이미 확인돼 단순 조정으로 보기 어렵습니다.' : null,
    guidanceInsight?.stance === 'mixed' ? '가이던스가 엇갈려 숫자 확인이 더 필요합니다.' : null,
    financials.estimateRevision30d !== null && financials.estimateRevision30d <= -4 ? `30일 컨센서스 ${financials.estimateRevision30d.toFixed(1)}%p 하향이 이어지고 있습니다.` : null,
    financials.estimateRevision90d !== null && financials.estimateRevision90d <= -6 ? `90일 누적 컨센서스 하향 ${financials.estimateRevision90d.toFixed(1)}%p로 중기 기대가 약해졌습니다.` : null,
    failureRisk !== null && failureRisk >= 60 ? `바닥 실패 위험 ${failureRisk}/100으로 재시험 실패 가능성을 열어둬야 합니다.` : null,
    sectorBuyScore !== null && sectorBuyScore < 55 ? `${sectorContext.label} 섹터 B${sectorBuyScore}로 업종 역풍이 남아 있습니다.` : null,
    buyScore.crowdingScore >= 72 ? `과열도 ${buyScore.crowdingScore}/100으로 조정이 길어질 수 있습니다.` : null,
    reversalConfirmation?.status === 'OFF' ? '반전 확인 신호가 아직 꺼져 있습니다.' : null,
  ].filter((item): item is string => Boolean(item)).slice(0, 4);

  return {
    correctionScore,
    trendBreakRiskScore,
    verdict,
    actionBias,
    summary,
    reasons: reasons.length ? reasons : ['사업 훼손 여부를 더 볼 필요가 있어 아직 조정 우세 근거가 충분하진 않습니다.'],
    risks: risks.length ? risks : ['뚜렷한 추세 훼손 신호는 크지 않지만 아직 반전 확정으로 보긴 이릅니다.'],
  };
}

function buildThesisMonitor(
  financials: CompanyResearchResponse['financials'],
  guidanceInsight: CompanyGuidanceInsight | null,
  sectorContext: CompanySectorContext | null,
  narrative: CompanyNarrativeInsight | null,
  bottleneck: CompanyBottleneckInsight | null,
  correctionAssessment: CompanyCorrectionAssessment,
): CompanyThesisMonitor {
  const status: CompanyThesisMonitor['status'] =
    correctionAssessment.verdict === '추세전환 경계' || guidanceInsight?.stance === 'lowered' || (financials.estimateRevision30d ?? 0) <= -4
      ? '훼손 경계'
      : correctionAssessment.verdict === '혼합' || guidanceInsight?.stance === 'mixed' || ((financials.estimateRevision30d ?? 0) < 0)
        ? '일부 약화'
        : '유지';
  const summary = status === '유지'
    ? '매수 이유가 아직 살아 있는 편입니다. 눌림은 조정으로 해석할 여지가 더 큽니다.'
    : status === '일부 약화'
      ? '핵심 논리는 남아 있지만 숫자나 섹터 환경이 한 단계 약해졌습니다.'
      : '매수 이유가 실제로 흔들리는 구간입니다. 추가 진입보다 논리 훼손 여부 확인이 우선입니다.';
  const sectorBuyScore = sectorContext?.buyScore ?? null;
  const reasons = [
    financials.revenueGrowthYoY !== null && financials.revenueGrowthYoY >= 10 ? `매출 성장 ${financials.revenueGrowthYoY.toFixed(1)}%로 수요 가설이 유지됩니다.` : null,
    financials.operatingMargin !== null && financials.operatingMargin >= 18 ? `영업이익률 ${financials.operatingMargin.toFixed(1)}%로 수익성 방어가 됩니다.` : null,
    guidanceInsight?.stance === 'raised' ? '가이던스가 상향돼 실적 숫자 가설이 강화됐습니다.' : null,
    guidanceInsight?.stance === 'affirmed' ? '가이던스가 유지돼 실적 가설이 아직 깨지지 않았습니다.' : null,
    financials.estimateRevision30d !== null && financials.estimateRevision30d >= 0 ? `30일 컨센서스 ${financials.estimateRevision30d >= 0 ? '+' : ''}${financials.estimateRevision30d.toFixed(1)}%p로 기대치가 유지됩니다.` : null,
    sectorBuyScore !== null && sectorBuyScore >= 68 ? `${sectorContext.label} 섹터 강도가 받쳐줘 업종 역풍이 작습니다.` : null,
    bottleneck?.conviction === 'CORE' || bottleneck?.conviction === 'STRONG' ? `병목 conviction ${bottleneck.conviction}로 구조 우위가 남아 있습니다.` : null,
    narrative?.drivers?.[0] ? `핵심 동인: ${narrative.drivers[0]}` : null,
  ].filter((item): item is string => Boolean(item)).slice(0, 4);
  const breakConditions = [
    guidanceInsight
      ? `다음 실적에서 ${guidanceInsight.revenue ? '매출' : '가이던스'} 하향 또는 마진/CAPEX 숫자 약화가 나오면 가설이 흔들립니다.`
      : '다음 실적에서 가이던스 하향이 나오면 매수 논리를 다시 검증해야 합니다.',
    '30일 컨센서스가 추가로 -4%p 이상 더 내려가면 기대치 훼손으로 해석해야 합니다.',
    sectorContext?.label ? `${sectorContext.label} 섹터 B 점수가 50 아래로 내려가면 업종 바람 약화를 반영해야 합니다.` : '섹터/업종 강도가 약해지면 종목 논리도 같이 재평가해야 합니다.',
    financials.operatingMargin !== null && financials.freeCashFlowMargin !== null
      ? `영업이익률 ${financials.operatingMargin.toFixed(1)}%·FCF 마진 ${financials.freeCashFlowMargin.toFixed(1)}%가 동반 악화되면 질 좋은 조정으로 보기 어렵습니다.`
      : '마진과 현금흐름이 함께 악화되면 좋은 조정이 아니라 구조 약화일 수 있습니다.',
  ].slice(0, 4);

  return {
    status,
    summary,
    reasons: reasons.length ? reasons : ['현재 매수 이유를 뒷받침하는 숫자/내러티브는 제한적이라 보수적 확인이 필요합니다.'],
    breakConditions,
  };
}

function buildCapitalFlowInsight(
  ticker: string,
  peerGroup: string | null,
  bottleneck: CompanyBottleneckInsight | null,
  narrative: CompanyNarrativeInsight | null,
  financials: CompanyResearchResponse['financials'],
): CompanyCapitalFlowInsight {
  const normalized = ticker.toUpperCase();
  const etfInclusion = peerGroup === 'AI_SEMIS' || peerGroup === 'SEMI_EQUIPMENT' ? '반도체/AI ETF 편입 수혜 가능' : peerGroup === 'MEGACAP_PLATFORM' ? '메가캡/클라우드 ETF 자금 유입 연동 가능' : peerGroup === 'POWER_INFRA' ? '전력/인프라 테마형 자금 유입 수혜 가능' : peerGroup === 'DEFENSE_AERO' ? '방산 ETF·정책 자금 연동 가능' : peerGroup === 'HEALTHCARE_BIO' ? '헬스케어 대형주 자금 유입 수혜 가능' : peerGroup === 'FINANCIALS' ? '금융/거래소/결제 레일 ETF 자금 연동 가능' : peerGroup === 'ENERGY_SUPPLY' ? '에너지 생산·정유 ETF 자금 연동 가능' : peerGroup === 'COMMUNICATION_MEDIA' ? '통신·스트리밍·광고 플랫폼 ETF 자금 유입 가능' : peerGroup === 'CONSUMER_FRANCHISE' ? '소비 프랜차이즈/리테일 ETF 자금 유입 가능' : peerGroup === 'CONSUMER_STAPLES' ? '필수소비재·유통 ETF 자금 유입 가능' : peerGroup === 'MATERIALS_RESOURCES' ? '산업금속·소재 사이클 자금 유입 가능' : peerGroup === 'UTILITIES_DEFENSIVE' ? '방어형 배당·전력 유틸리티 자금 유입 가능' : peerGroup === 'REAL_ASSETS' ? '리츠/타워/데이터센터 실물자산 자금 유입 가능' : peerGroup === 'INDUSTRIALS_CYCLICAL' ? '산업재·CAPEX 사이클 자금 유입 가능' : null;
  const policyTailwinds = [
    peerGroup === 'POWER_INFRA' ? '전력망·데이터센터 CAPEX 확대' : null,
    peerGroup === 'DEFENSE_AERO' ? '국방예산 확대 및 지정학 수요' : null,
    peerGroup === 'AI_SEMIS' || peerGroup === 'SEMI_EQUIPMENT' ? 'AI 인프라·반도체 투자 지속' : null,
    peerGroup === 'HEALTHCARE_BIO' ? '비만·고부가 치료 수요 장기화' : null,
    peerGroup === 'FINANCIALS' ? '거래·결제·자본시장 활동 회복 시 수혜' : null,
    peerGroup === 'ENERGY_SUPPLY' ? '에너지 공급 제약과 배당/자사주 매력' : null,
    peerGroup === 'COMMUNICATION_MEDIA' ? '광고 회복·스트리밍 수익화·5G/브로드밴드 방어 수요' : null,
    peerGroup === 'CONSUMER_FRANCHISE' ? '브랜드·유통망 기반의 방어적 소비 수요' : null,
    peerGroup === 'CONSUMER_STAPLES' ? '필수 소비와 가격 전가력 기반의 방어 수요' : null,
    peerGroup === 'MATERIALS_RESOURCES' ? '리플레이션·인프라 투자 시 수혜' : null,
    peerGroup === 'UTILITIES_DEFENSIVE' ? '방어 자금과 전력 수요 증가 수혜' : null,
    peerGroup === 'REAL_ASSETS' ? '데이터센터/타워/물류 등 실물자산 수요' : null,
    peerGroup === 'INDUSTRIALS_CYCLICAL' ? '설비투자·리쇼어링 CAPEX 수혜' : null,
  ].filter((x): x is string => Boolean(x));
  const fundingDrivers = [
    bottleneck?.score && bottleneck.score >= 60 ? `병목 점수 ${bottleneck.score}/100로 구조 자금 유입 명분 존재` : null,
    narrative ? `내러티브 ${narrative.title} ${narrative.stage} 단계` : null,
    financials.estimateRevision30d !== null && financials.estimateRevision30d > 0 ? `30일 컨센서스 상향 ${financials.estimateRevision30d.toFixed(1)}%p` : null,
    financials.estimateUpsidePct !== null && financials.estimateUpsidePct > 8 ? `애널리스트 업사이드 ${financials.estimateUpsidePct.toFixed(1)}%` : null,
  ].filter((x): x is string => Boolean(x));
  const capexLinkage = peerGroup === 'AI_SEMIS' || peerGroup === 'SEMI_EQUIPMENT' || peerGroup === 'POWER_INFRA' || peerGroup === 'INDUSTRIALS_CYCLICAL' || peerGroup === 'MATERIALS_RESOURCES' || peerGroup === 'REAL_ASSETS' || normalized in {'NVDA':1,'ASML':1,'TSM':1,'VRT':1,'ETN':1,'HUBB':1,'CAT':1,'LIN':1,'PLD':1,'EQIX':1,'DLR':1} ? 'CAPEX 확대와 직접 연결된 편입니다.' : bottleneck?.score && bottleneck.score >= 55 ? '설비투자 확대의 간접 수혜가 가능합니다.' : null;
  return { etfInclusion, policyTailwinds, capexLinkage, fundingDrivers };
}

function buildBottleneckInsight(research: CompanyResearchResponse): CompanyBottleneckInsight | null {
  const found = findBottleneckCandidateByTicker(research.profile.ticker);
  if (!found) return null;
  const scored = computeBottleneckCandidateScore(found.candidate, research);
  const theme = getBottleneckThemeById(found.themeId);
  const leadTimeSignal = scored.componentScores.supplyTightness >= 8 ? '강함' : scored.componentScores.supplyTightness >= 6 ? '보통' : '약함';
  const backlogSignal = scored.componentScores.capexLinkage >= 8 ? '강함' : scored.componentScores.capexLinkage >= 6 ? '보통' : '약함';
  const pricingPower = scored.componentScores.concentration >= 8 ? '높음' : scored.componentScores.concentration >= 6 ? '보통' : '낮음';
  return {
    themeId: found.themeId,
    title: theme?.title ?? found.themeId,
    role: scored.role,
    score: scored.score,
    conviction: scored.conviction,
    switchingCost: scored.componentScores.switchingCost,
    pricingPower,
    leadTimeSignal,
    backlogSignal,
    reasons: scored.reasons.slice(0, 4),
  };
}

function inferNarrativeThemeId(peerGroup: string | null, ticker: string): string | null {
  const normalized = ticker.toUpperCase();
  if (peerGroup === 'AI_SEMIS' || peerGroup === 'SEMI_EQUIPMENT' || peerGroup === 'MEGACAP_PLATFORM' || ['NVDA','AMD','AVGO','TSM','ASML','MRVL','AMZN','META','ORCL','AAPL','PANW','CRWD','SNOW','ANET','AMAT','LRCX','KLAC'].includes(normalized)) return 'ai-power';
  if (peerGroup === 'POWER_INFRA' || ['VRT','ETN','HUBB','NVT','GEV','PWR','TT','JCI','NEE','CEG','VST'].includes(normalized)) return 'grid-capex';
  if (peerGroup === 'INDUSTRIALS_CYCLICAL' || ['GE','CAT','DE','HON','EMR','URI','UNP','UPS','FDX'].includes(normalized)) return 'grid-capex';
  if (peerGroup === 'DEFENSE_AERO' || ['LMT','NOC','RTX','GD','LHX','GE'].includes(normalized)) return 'defense-rearm';
  if (peerGroup === 'FINANCIALS' || ['JPM','BAC','GS','MS','BLK','V','MA','ICE','CME'].includes(normalized)) return 'finance-liquidity';
  if (peerGroup === 'ENERGY_SUPPLY' || ['XOM','CVX','COP','EOG','SLB','BKR','HAL','MPC','PSX'].includes(normalized)) return 'energy-supply';
  if (peerGroup === 'COMMUNICATION_MEDIA' || ['GOOGL','META','NFLX','TMUS','CMCSA','DIS','SPOT','ROKU'].includes(normalized)) return 'digital-attention';
  if (peerGroup === 'CONSUMER_FRANCHISE' || ['AMZN','TSLA','HD','MCD','NKE','SBUX','BKNG','LOW','TJX','CMG'].includes(normalized)) return 'consumer-demand';
  if (peerGroup === 'CONSUMER_STAPLES' || ['PG','KO','PEP','WMT','COST','PM','MO','MDLZ'].includes(normalized)) return 'consumer-defensive';
  if (peerGroup === 'MATERIALS_RESOURCES' || ['LIN','APD','FCX','NEM','NUE','ALB','CF','MOS','MLM','VMC'].includes(normalized)) return 'materials-reflation';
  if (peerGroup === 'REAL_ASSETS' || ['PLD','AMT','EQIX','DLR','CCI','O','WELL','VICI'].includes(normalized)) return 'real-assets-rate';
  if (peerGroup === 'UTILITIES_DEFENSIVE' || ['SO','DUK','AEP','SRE','EXC','AWK','WEC'].includes(normalized)) return 'consumer-defensive';
  if (peerGroup === 'HEALTHCARE_BIO' || ['UNH','JNJ','MRK','ABBV','LLY','NVO','ISRG','TMO','DHR'].includes(normalized)) return 'consumer-defensive';
  if (normalized in {'GLD':1,'GOLD':1}) return 'safehaven-gold';
  return null;
}


function buildCashFlowQualityInsight(financials: CompanyResearchResponse['financials']): CompanyCashFlowQualityInsight {
  const ocfToNetIncome = financials.operatingCashFlowTtm !== null && financials.netIncomeTtm !== null && financials.netIncomeTtm > 0
    ? Number((financials.operatingCashFlowTtm / financials.netIncomeTtm).toFixed(2))
    : null;
  let cashConversionScore = 55;
  let earningsQualityScore = 55;
  const reasons: string[] = [];
  if (ocfToNetIncome !== null) {
    if (ocfToNetIncome >= 1.1) {
      cashConversionScore += 22;
      earningsQualityScore += 18;
      reasons.push(`영업현금흐름이 순이익의 ${ocfToNetIncome.toFixed(2)}배`);
    } else if (ocfToNetIncome >= 0.9) {
      cashConversionScore += 10;
      earningsQualityScore += 8;
      reasons.push(`이익과 현금흐름이 대체로 정합적`);
    } else {
      cashConversionScore -= 10;
      earningsQualityScore -= 14;
      reasons.push(`이익 대비 현금 유입이 약함 (${ocfToNetIncome.toFixed(2)}배)`);
    }
  }
  if (financials.freeCashFlowMargin !== null) {
    if (financials.freeCashFlowMargin >= 15) {
      cashConversionScore += 12;
      reasons.push(`FCF 마진 ${financials.freeCashFlowMargin.toFixed(1)}%`);
    } else if (financials.freeCashFlowMargin < 5) {
      cashConversionScore -= 10;
      reasons.push(`FCF 마진이 낮아 현금화 질 점검 필요`);
    }
  }
  if (financials.stockCompToRevenue !== null && financials.stockCompToRevenue >= 6) {
    earningsQualityScore -= 12;
    reasons.push(`주식보상/매출 ${financials.stockCompToRevenue.toFixed(1)}%로 희석 부담`);
  }
  if (financials.shareDilutionYoY !== null && financials.shareDilutionYoY >= 3) {
    earningsQualityScore -= 10;
    reasons.push(`주식수 희석 ${financials.shareDilutionYoY.toFixed(1)}%`);
  }
  if (financials.receivablesToRevenue !== null && financials.receivablesToRevenue >= 0.25) {
    earningsQualityScore -= 8;
    reasons.push(`매출채권/매출 ${financials.receivablesToRevenue.toFixed(2)}x로 회수 점검 필요`);
  }
  if (financials.inventoryToRevenue !== null && financials.inventoryToRevenue >= 0.18) {
    cashConversionScore -= 6;
    reasons.push(`재고/매출 ${financials.inventoryToRevenue.toFixed(2)}x로 재고 부담 가능`);
  }
  if (financials.currentRatio !== null && financials.currentRatio < 1) {
    cashConversionScore -= 6;
    reasons.push(`유동비율 ${financials.currentRatio.toFixed(2)}x로 단기 유동성 점검 필요`);
  }
  const receivablesRisk: '낮음' | '보통' | '높음' =
    financials.receivablesToRevenue !== null && financials.receivablesToRevenue >= 0.25 ? '높음'
      : financials.receivablesToRevenue !== null && financials.receivablesToRevenue >= 0.15 ? '보통'
        : '낮음';
  const inventoryRisk: '낮음' | '보통' | '높음' =
    financials.inventoryToRevenue !== null && financials.inventoryToRevenue >= 0.18 ? '높음'
      : financials.inventoryToRevenue !== null && financials.inventoryToRevenue >= 0.08 ? '보통'
        : '낮음';
  const liquidityLabel: '양호' | '보통' | '주의' =
    financials.currentRatio !== null && financials.currentRatio >= 1.5 ? '양호'
      : financials.currentRatio !== null && financials.currentRatio >= 1 ? '보통'
        : '주의';
  cashConversionScore = roundScore(cashConversionScore);
  earningsQualityScore = roundScore(earningsQualityScore);
  const avg = Math.round((cashConversionScore + earningsQualityScore) / 2);
  const accrualRisk: '낮음' | '보통' | '높음' = avg >= 70 ? '낮음' : avg >= 55 ? '보통' : '높음';
  const summary = avg >= 70
    ? '이익이 현금으로 비교적 잘 전환되고 working capital 부담도 크지 않습니다.'
    : avg >= 55
      ? '현금흐름 질은 보통 수준입니다. 채권·재고·유동성도 같이 보는 편이 좋습니다.'
      : '실적 대비 현금화 질을 보수적으로 봐야 합니다. 채권·재고·유동성 점검이 필요합니다.';
  return { cashConversionScore, earningsQualityScore, accrualRisk, ocfToNetIncome, receivablesRisk, inventoryRisk, liquidityLabel, summary, reasons: reasons.slice(0, 6) };
}

function buildMultipleInsight(
  financials: CompanyResearchResponse['financials'],
  narrative: CompanyNarrativeInsight | null,
  peers: CompanyPeerSummary[],
): CompanyMultipleInsight {
  const ev = financials.evToSales;
  const rateSensitivity: '낮음' | '보통' | '높음' = ev !== null && ev >= 10 ? '높음' : ev !== null && ev >= 6 ? '보통' : '낮음';
  const narrativePremium: '낮음' | '보통' | '높음' = narrative?.stage === 'OVERHEATED' || (narrative && narrative.heatScore >= 70) ? '높음' : narrative?.stage === 'MID' || (narrative && narrative.heatScore >= 50) ? '보통' : '낮음';
  const peerValues = peers.map((p) => p.evToSales).filter((x): x is number => x !== null).sort((a, b) => a - b);
  const peerAvg = peerValues.length ? peerValues.reduce((a,b)=>a+b,0)/peerValues.length : null;
  const peerMedian = peerValues.length ? (peerValues.length % 2 ? peerValues[(peerValues.length - 1) / 2] : (peerValues[peerValues.length / 2 - 1] + peerValues[peerValues.length / 2]) / 2) : null;
  const premiumPctVsPeer = ev !== null && peerAvg !== null && peerAvg > 0 ? Number((((ev - peerAvg) / peerAvg) * 100).toFixed(1)) : null;
  const premiumPctVsPeerMedian = ev !== null && peerMedian !== null && peerMedian > 0 ? Number((((ev - peerMedian) / peerMedian) * 100).toFixed(1)) : null;
  const valuationVsPeer: '할인' | '중립' | '프리미엄' | '판단불가' = ev === null || peerAvg === null ? '판단불가' : ev <= peerAvg * 0.85 ? '할인' : ev >= peerAvg * 1.15 ? '프리미엄' : '중립';
  const valuationVsInternalRange: '저평가권' | '중립권' | '고평가권' | '판단불가' =
    ev === null ? '판단불가' : ev <= 4 ? '저평가권' : ev >= 10 ? '고평가권' : '중립권';
  const multipleCompressionRisk: '낮음' | '보통' | '높음' =
    valuationVsPeer === '프리미엄' || (rateSensitivity === '높음' && narrativePremium !== '낮음') ? '높음'
      : rateSensitivity === '보통' || narrativePremium === '보통' ? '보통'
        : '낮음';
  const reasons: string[] = [];
  if (ev !== null) reasons.push(`EV/Sales ${ev.toFixed(1)}x`);
  if (peerAvg !== null) reasons.push(`peer 평균 ${peerAvg.toFixed(1)}x 대비 ${premiumPctVsPeer === null ? '—' : `${premiumPctVsPeer >= 0 ? '+' : ''}${premiumPctVsPeer}%`}`);
  if (peerMedian !== null) reasons.push(`peer 중앙값 ${peerMedian.toFixed(1)}x 대비 ${premiumPctVsPeerMedian === null ? '—' : `${premiumPctVsPeerMedian >= 0 ? '+' : ''}${premiumPctVsPeerMedian}%`}`);
  if (rateSensitivity !== '낮음') reasons.push(`금리/멀티플 민감도 ${rateSensitivity}`);
  if (narrativePremium !== '낮음') reasons.push(`내러티브 프리미엄 ${narrativePremium}`);
  reasons.push(`멀티플 압축 위험 ${multipleCompressionRisk}`);
  const summary = valuationVsPeer === '프리미엄'
    ? 'peer 평균·중앙값 대비 프리미엄이 붙어 있어 좋은 회사여도 가격 부담을 같이 봐야 합니다.'
    : valuationVsPeer === '할인'
      ? 'peer 평균·중앙값 대비 멀티플 부담은 덜한 편입니다.'
      : rateSensitivity === '높음'
        ? '고평가까지는 아니어도 멀티플 민감도가 큰 편입니다.'
        : '멀티플 해석은 중립에 가깝습니다.';
  return { rateSensitivity, narrativePremium, valuationVsPeer, multipleCompressionRisk, valuationVsInternalRange, peerAverageEvToSales: peerAvg === null ? null : Number(peerAvg.toFixed(2)), peerMedianEvToSales: peerMedian === null ? null : Number(peerMedian.toFixed(2)), premiumPctVsPeer, premiumPctVsPeerMedian, summary, reasons: reasons.slice(0, 6) };
}

function buildGuidanceInsight(filings: CompanyFilingEvent[]): CompanyGuidanceInsight | null {
  const latest = filings.find((item) => item.guidanceSummary);
  if (!latest?.guidanceSummary) return null;
  const g = latest.guidanceSummary;
  const hasConcreteValues = [g.revenueValue, g.marginValue, g.capexValue, g.fcfValue].some((item) => item && (item.min !== null || item.max !== null));
  const actionBias: CompanyGuidanceInsight['actionBias'] =
    g.stance === 'raised' ? '공격 가능'
      : g.stance === 'affirmed' || g.stance === 'mixed' ? '선별 접근'
        : '보수 접근';
  const summary = g.stance === 'raised'
    ? hasConcreteValues ? '최근 가이던스가 상향됐고 숫자 레벨도 실적 기대를 뒷받침합니다.' : '최근 가이던스가 상향되며 실적 기대를 지지합니다.'
    : g.stance === 'affirmed'
      ? hasConcreteValues ? '가이던스는 유지됐습니다. 숫자는 견조하지만 추가 상향 여지는 더 확인해야 합니다.' : '가이던스는 유지됐습니다. 숫자는 견조하지만 추가 서프라이즈는 더 확인해야 합니다.'
      : g.stance === 'lowered'
        ? hasConcreteValues ? '최근 가이던스가 하향됐고 숫자 레벨도 낮아져 신규 진입은 더 보수적으로 보는 편이 좋습니다.' : '최근 가이던스가 하향돼 신규 진입은 보수적으로 보는 편이 좋습니다.'
        : g.stance === 'mixed'
          ? '가이던스가 엇갈립니다. 일부 지표는 좋지만 바로 확신하기는 이릅니다.'
          : '가이던스 방향이 불명확합니다. 후속 실적/수급 확인이 중요합니다.';
  return {
    stance: g.stance,
    actionBias,
    summary,
    revenue: g.revenueText ?? null,
    margin: g.marginText ?? null,
    capex: g.capexText ?? null,
    fcf: g.fcfText ?? null,
    revenueValue: g.revenueValue ?? null,
    marginValue: g.marginValue ?? null,
    capexValue: g.capexValue ?? null,
    fcfValue: g.fcfValue ?? null,
    evidence: g.evidence.slice(0, 4),
  };
}

function buildTimeframeView(
  buyScore: CompanyBuyScore,
  narrative: CompanyNarrativeInsight | null,
  multipleInsight: CompanyMultipleInsight | null,
  verdicts: CompanyVerdicts,
): CompanyTimeframeView {
  const shortTerm = buyScore.crowdingScore >= 70 || narrative?.stage === 'OVERHEATED'
    ? { stance: 'REDUCE' as const, summary: '단기는 과열 부담이 커서 새 추격보다 대기·축소가 유리합니다.' }
    : buyScore.buyScore >= 72
      ? { stance: 'BUY' as const, summary: '단기는 분할 매수 가능한 구간입니다. 한 번에 크게 들어가진 않는 편이 좋습니다.' }
      : { stance: 'HOLD' as const, summary: '단기는 관찰 우위입니다. 가격 조정이나 후속 확인이 더 필요합니다.' };
  const swingTerm = verdicts.timing.score >= 65
    ? { stance: 'BUY' as const, summary: '스윙 관점에선 추세가 살아 있습니다. 1~2번 나눠 접근할 만합니다.' }
    : verdicts.timing.score >= 50
      ? { stance: 'HOLD' as const, summary: '스윙은 선택적 접근입니다. 좋은 회사여도 타이밍은 더 봐야 합니다.' }
      : { stance: 'REDUCE' as const, summary: '스윙 관점에선 가격 부담 또는 모멘텀 약화가 먼저 보입니다.' };
  const longTerm = verdicts.businessQuality.score >= 72 && multipleInsight?.valuationVsPeer !== '프리미엄'
    ? { stance: 'BUY' as const, summary: '장기는 사업 품질이 받쳐주는 편입니다. 가격이 밀리면 더 좋은 구간이 될 수 있습니다.' }
    : verdicts.businessQuality.score >= 72
      ? { stance: 'HOLD' as const, summary: '장기 보유 후보는 맞지만 지금 가격은 앞서 있을 수 있어 서두르지 않는 편이 좋습니다.' }
      : { stance: 'HOLD' as const, summary: '장기 논리는 더 검증이 필요합니다. 사업 품질이 충분히 강한지 추가 확인이 필요합니다.' };
  return { shortTerm, swingTerm, longTerm };
}

function buildSectorContext(
  ticker: string,
  snapshot: Awaited<ReturnType<typeof getSnapshot>> | null,
): CompanySectorContext | null {
  const sector = inferResearchSectorForTicker(ticker);
  if (!sector) return null;
  const topdownSector = [
    ...(snapshot?.meta.topdown?.favoredSectors ?? []),
    ...(snapshot?.meta.topdown?.avoidedSectors ?? []),
  ].find((item) => item.key === sector.sectorKey);
  const definition = getSectorDefinition(sector.sectorKey);
  const relatedThemes = getResearchThemesForSectorKey(sector.sectorKey).map((item) => ({ id: item.id, theme: item.theme }));
  const thesis = topdownSector?.buyScore !== undefined && topdownSector?.buyScore !== null
    ? topdownSector.buyScore >= 70
      ? '섹터 자체가 우호적이라 회사 선택 효과가 커질 수 있습니다.'
      : topdownSector.buyScore >= 55
        ? '섹터는 무난하지만 종목 선택이 더 중요합니다.'
        : '섹터 역풍이 있어 좋은 회사여도 진입은 더 까다롭게 봐야 합니다.'
    : '섹터 보조 해석 데이터가 아직 충분치 않습니다.';
  return {
    sectorId: sector.id,
    label: sector.label,
    sectorKey: sector.sectorKey,
    classification: definition?.classification ?? 'neutral',
    buyScore: topdownSector?.buyScore ?? null,
    qualityScore: topdownSector?.quality?.totalScore ?? null,
    appealScore: topdownSector?.appealScore ?? null,
    crowdingScore: topdownSector?.crowdingScore ?? null,
    stance: topdownSector?.stance ?? 'neutral',
    thesis,
    relatedThemes,
  };
}


function buildPositionSizingPlan(
  buyScore: CompanyBuyScore,
  verdicts: CompanyVerdicts,
  bottleneck: CompanyBottleneckInsight | null,
  narrative: CompanyNarrativeInsight | null,
  multipleInsight: CompanyMultipleInsight | null,
  correctionAssessment: CompanyCorrectionAssessment | null,
  reversalConfirmation: CompanyReversalConfirmation | null,
): CompanyPositionSizingPlan {
  const action: CompanyPositionSizingPlan['action'] = buyScore.buyScore >= 80
    ? 'STRONG BUY'
    : buyScore.buyScore >= 70
      ? 'BUY'
      : buyScore.buyScore >= 55
        ? 'HOLD'
        : buyScore.buyScore >= 40
          ? 'REDUCE'
          : 'SELL';

  let targetPositionPct = verdicts.businessQuality.score >= 75 ? 10 : verdicts.businessQuality.score >= 65 ? 8 : verdicts.businessQuality.score >= 55 ? 6 : 4;
  if (bottleneck?.conviction === 'CORE') targetPositionPct += 1;
  if (narrative?.stage === 'OVERHEATED') targetPositionPct -= 2;
  if (multipleInsight?.valuationVsPeer === '프리미엄') targetPositionPct -= 1;
  targetPositionPct = clamp(targetPositionPct, 2, 12);

  let initialEntryPctOfTarget = action === 'STRONG BUY' ? 40 : action === 'BUY' ? 30 : action === 'HOLD' ? 20 : action === 'REDUCE' ? 10 : 0;
  if (correctionAssessment?.verdict === '조정 우세' && (reversalConfirmation?.status === 'ON' || reversalConfirmation?.status === 'STRONG')) {
    initialEntryPctOfTarget += 5;
  }
  if (correctionAssessment?.verdict === '추세전환 경계') {
    initialEntryPctOfTarget -= 10;
  }
  initialEntryPctOfTarget = clamp(initialEntryPctOfTarget, 0, 45);
  const reservePctOfTarget = 100 - initialEntryPctOfTarget;
  const addOnPlan = action === 'SELL'
    ? ['새 진입보다 기존 노출 축소를 우선합니다.']
    : [
        correctionAssessment?.verdict === '조정 우세' && (reversalConfirmation?.status === 'ON' || reversalConfirmation?.status === 'STRONG')
          ? `1차 ${initialEntryPctOfTarget}%: 조정 우세 + 반전확인 ${reversalConfirmation.status}라 지금 소액 진입 허용`
          : correctionAssessment?.verdict === '추세전환 경계'
            ? `1차 ${initialEntryPctOfTarget}%: 추세훼손 경계라 확인 전에는 최소 비중만 허용`
            : `1차 ${initialEntryPctOfTarget}%: 신호는 있으나 혼합 구간이라 소액만 먼저`,
        '2차 30%: 8~12% 조정 또는 컨센서스 유지/상향 재확인 시 추가',
        '3차 잔여: 실적 확인·거래량 반전 재확인 또는 W바닥 완성 시 추가',
      ];
  const reduceTrigger = multipleInsight?.valuationVsPeer === '프리미엄'
    ? 'peer 프리미엄이 더 확대되거나 과열도 70+면 일부 축소'
    : '내러티브가 OVERHEATED로 전환되면 비중 축소를 우선 검토';
  const stopScenario = correctionAssessment?.verdict === '추세전환 경계'
    ? '가이던스 추가 하향·컨센서스 연속 하향·재시험 저점 이탈 중 하나가 나오면 신규 매수를 중단합니다.'
    : verdicts.timing.score < 45
    ? '타이밍이 더 약해지거나 실적 후 가이던스 하향이 나오면 신규 매수를 중단합니다.'
    : '사업 가설 훼손(가이던스 하향·수주 둔화·병목 약화) 시 남은 현금 투입을 보류합니다.';
  const summary = correctionAssessment?.verdict === '조정 우세'
    ? '지금 하락을 조정으로 보는 편이 우세해 1차 진입은 허용하되, 남은 현금은 후속 확인에 남겨두는 구간입니다.'
    : correctionAssessment?.verdict === '추세전환 경계'
      ? '좋은 회사일 수 있어도 이번 하락은 추세훼손 가능성을 먼저 봐야 해 새 매수보다 방어가 우선입니다.'
      : action === 'STRONG BUY'
        ? '좋은 회사이면서 타이밍도 비교적 우호적입니다. 목표 비중을 나눠 채워가는 구간입니다.'
        : action === 'BUY'
          ? '매수는 가능하지만 1차만 먼저 두고 나머지는 조정에 남겨두는 편이 좋습니다.'
          : action === 'HOLD'
            ? '회사는 괜찮지만 지금은 관찰 비중이 더 적절합니다. 1차 진입만 허용하는 구간입니다.'
            : action === 'REDUCE'
              ? '사업보다 가격 부담이 앞서는 구간입니다. 새 매수보다 비중 축소/현금 확보가 우선입니다.'
              : '좋은 회사일 수 있어도 지금은 매수보다 회피가 낫습니다.';
  const reasons = [
    `단일 종목 목표비중 ${targetPositionPct}% 제안`,
    `즉시 진입은 목표 비중의 ${initialEntryPctOfTarget}%까지`,
    correctionAssessment ? `${correctionAssessment.verdict} · 조정 ${correctionAssessment.correctionScore} / 추세훼손 ${correctionAssessment.trendBreakRiskScore}` : null,
    reversalConfirmation ? `반전확인 ${reversalConfirmation.status} ${reversalConfirmation.score}/100` : null,
    bottleneck?.conviction ? `병목 conviction ${bottleneck.conviction}` : null,
    narrative ? `내러티브 ${narrative.stage} · heat ${narrative.heatScore}` : null,
    multipleInsight?.valuationVsPeer ? `peer 대비 ${multipleInsight.valuationVsPeer}` : null,
  ].filter((item): item is string => Boolean(item)).slice(0, 5);

  return {
    action,
    targetPositionPct,
    initialEntryPctOfTarget,
    reservePctOfTarget,
    addOnPlan,
    reduceTrigger,
    stopScenario,
    summary,
    reasons,
  };
}



function companyActionLabel(action: CompanyPositionSizingPlan['action']): string {
  switch (action) {
    case 'STRONG BUY': return '적극 매수';
    case 'BUY': return '매수 가능';
    case 'HOLD': return '보유/관찰';
    case 'REDUCE': return '축소';
    case 'SELL': return '매도/회피';
  }
}

function actionRank(action: CompanyPositionSizingPlan['action']): number {
  switch (action) {
    case 'STRONG BUY': return 5;
    case 'BUY': return 4;
    case 'HOLD': return 3;
    case 'REDUCE': return 2;
    case 'SELL': return 1;
  }
}

function mapPlanActionToCompanyAction(action: string): CompanyPositionSizingPlan['action'] {
  const normalized = action.toUpperCase();
  if (normalized.includes('BUY_NOW') || normalized.includes('STRONG')) return 'BUY';
  if (normalized.includes('BUY')) return 'BUY';
  if (normalized.includes('TAKE_PROFIT') || normalized.includes('REDUCE')) return 'REDUCE';
  if (normalized.includes('SELL')) return 'SELL';
  return 'HOLD';
}

function inferLinkedAsset(peerGroup: string | null, ticker: string, narrative: CompanyNarrativeInsight | null): string | null {
  const normalized = ticker.toUpperCase();
  if (peerGroup === 'AI_SEMIS' || narrative?.themeId === 'ai-power' || ['NVDA','AMD','AVGO','MRVL','META','AMZN','ORCL','AAPL'].includes(normalized)) return 'NASDAQ';
  if (peerGroup === 'POWER_INFRA' || narrative?.themeId === 'grid-capex' || ['VRT','ETN','NVT','HUBB','GEV','PWR','TT','JCI','NEE','CEG','VST'].includes(normalized)) return 'EMERGING';
  if (peerGroup === 'DEFENSE_AERO' || narrative?.themeId === 'defense-rearm' || ['LMT','NOC','RTX','GD','LHX'].includes(normalized)) return 'GOLD';
  if (peerGroup === 'COMMUNICATION_MEDIA' || narrative?.themeId === 'digital-attention' || ['GOOGL','META','NFLX','TMUS','CMCSA','DIS'].includes(normalized)) return 'NASDAQ';
  if (peerGroup === 'ENERGY_SUPPLY' || narrative?.themeId === 'energy-supply' || ['XOM','CVX','COP','SLB','BKR'].includes(normalized)) return 'COPPER';
  if (peerGroup === 'FINANCIALS' || narrative?.themeId === 'finance-liquidity' || ['JPM','BAC','GS','MS','V','MA'].includes(normalized)) return 'NASDAQ';
  if (peerGroup === 'REAL_ASSETS' || narrative?.themeId === 'real-assets-rate' || ['PLD','AMT','EQIX','DLR','CCI'].includes(normalized)) return 'GOLD';
  if (peerGroup === 'CONSUMER_FRANCHISE' || narrative?.themeId === 'consumer-demand' || ['AMZN','TSLA','HD','BKNG','CMG','ORLY'].includes(normalized)) return 'NASDAQ';
  if (peerGroup === 'CONSUMER_STAPLES' || peerGroup === 'HEALTHCARE_BIO' || narrative?.themeId === 'consumer-defensive' || ['PG','KO','PEP','WMT','COST','UNH','JNJ','LLY'].includes(normalized)) return 'CASH';
  if (peerGroup === 'MATERIALS_RESOURCES' || narrative?.themeId === 'materials-reflation' || ['LIN','APD','FCX','NUE','ALB'].includes(normalized)) return 'COPPER';
  if (['TSM'].includes(normalized)) return 'EMERGING';
  return null;
}

function buildExecutionBridge(
  snapshot: Awaited<ReturnType<typeof getSnapshot>> | null,
  peerGroup: string | null,
  ticker: string,
  narrative: CompanyNarrativeInsight | null,
  positionSizing: CompanyPositionSizingPlan,
): CompanyExecutionBridge | null {
  const linkedAsset = inferLinkedAsset(peerGroup, ticker, narrative);
  if (!linkedAsset || !snapshot?.meta?.executionPlans?.length) return null;
  const matched = snapshot.meta.executionPlans.find((plan) => plan.asset === linkedAsset);
  if (!matched) return null;
  const assetAction = mapPlanActionToCompanyAction(matched.action);
  const companyRank = actionRank(positionSizing.action);
  const assetRank = actionRank(assetAction);
  const alignment: CompanyExecutionBridge['alignment'] =
    companyRank === assetRank ? 'aligned'
      : Math.abs(companyRank - assetRank) === 1 ? 'mixed'
        : 'conflicted';
  const summary = alignment === 'aligned'
    ? '회사 판단과 연결 자산 실행계획이 대체로 같은 방향입니다.'
    : alignment === 'mixed'
      ? '회사 자체 평가는 괜찮지만 연결 자산 타이밍은 한 단계 더 보수적이거나 공격적입니다.'
      : '회사 자체 평가와 연결 자산 실행계획이 엇갈립니다. 종목보다 자산 타이밍을 더 우선 확인해야 합니다.';
  return {
    asset: matched.asset,
    action: matched.action,
    actionLabel: matched.actionLabel,
    companyAction: positionSizing.action,
    companyActionLabel: companyActionLabel(positionSizing.action),
    targetAllocationPct: matched.targetAllocationPct,
    alignment,
    primaryReason: matched.primaryReason,
    summary,
    timingNotes: matched.timing?.notes?.slice(0, 3) ?? [],
  };
}

function buildHighlights(financials: CompanyResearchResponse['financials'], score: CompanyResearchResponse['score']): string[] {
  const highlights: string[] = [];
  if (financials.revenueGrowthYoY !== null) {
    highlights.push(
      financials.revenueGrowthYoY >= 10
        ? `매출 YoY ${financials.revenueGrowthYoY.toFixed(1)}% 성장`
        : `매출 YoY ${financials.revenueGrowthYoY.toFixed(1)}%로 성장 둔화`,
    );
  }
  if (financials.operatingMargin !== null) highlights.push(`영업이익률 ${financials.operatingMargin.toFixed(1)}%`);
  if (financials.estimateRevision7d !== null && financials.estimateRevision7d !== undefined) {
    highlights.push(`7일 업사이드 변화 ${financials.estimateRevision7d >= 0 ? '+' : ''}${financials.estimateRevision7d.toFixed(1)}%p`);
  }
  if (financials.estimateRevision30d !== null && financials.estimateRevision30d !== undefined) {
    highlights.push(`30일 업사이드 변화 ${financials.estimateRevision30d >= 0 ? '+' : ''}${financials.estimateRevision30d.toFixed(1)}%p`);
  }
  if (financials.analystScoreRevision30d !== null && financials.analystScoreRevision30d !== undefined) {
    highlights.push(`애널리스트 점수 변화 ${financials.analystScoreRevision30d >= 0 ? '+' : ''}${financials.analystScoreRevision30d.toFixed(2)}`);
  }
  if (financials.evToSales !== null) highlights.push(`EV/Sales ${financials.evToSales.toFixed(1)}x`);
  highlights.push(`종합 점수 ${score.totalScore}/100`);
  return highlights.slice(0, 5);
}

function classifyIrMaterialType(filing: CompanyFilingEvent): CompanyIrMaterial['type'] {
  const description = `${filing.primaryDocDescription ?? ''} ${filing.primaryDocument ?? ''}`.toLowerCase();
  if (/(presentation|slides|deck|supplemental|investor)/i.test(description)) return 'presentation';
  if (filing.form === '10-K') return 'annual-report';
  if (filing.form === '10-Q') return 'quarterly-report';
  if (filing.isEarningsRelated || /(earnings|results of operations|quarterly results|exhibit 99)/i.test(description)) return 'earnings-release';
  return 'other';
}

function uniqueMaterials(items: CompanyIrMaterial[]): CompanyIrMaterial[] {
  const seen = new Set<string>();
  const output: CompanyIrMaterial[] = [];
  for (const item of items) {
    const key = `${item.url}::${item.title}`;
    if (seen.has(key)) continue;
    seen.add(key);
    output.push(item);
  }
  return output;
}

async function buildIrMaterials(filings: CompanyFilingEvent[], ticker: string): Promise<CompanyIrMaterial[]> {
  const filingDetailMaxAgeMs = getCompanyFilingDetailFreshMs(ticker);
  const materials: CompanyIrMaterial[] = filings
    .filter((filing) => filing.filingUrl)
    .map((filing) => ({
      title: filing.primaryDocDescription || filing.primaryDocument || `${filing.form} filing`,
      form: filing.form,
      filingDate: filing.filingDate,
      url: filing.filingUrl!,
      type: classifyIrMaterialType(filing),
      source: 'primary',
      contentType: /\.pdf($|\?)/i.test(filing.filingUrl!) ? 'pdf' : /\.(htm|html|xml|txt)($|\?)/i.test(filing.filingUrl!) ? 'html' : 'other',
      summary: filing.summary ?? null,
    }));

  const attachmentCandidates = filings.filter((filing) => filing.filingUrl && (filing.isEarningsRelated || filing.form === '8-K')).slice(0, 3);
  for (const filing of attachmentCandidates) {
    try {
      const indexText = await fetchSecFilingIndex(filing.filingUrl!, { maxAgeMs: filingDetailMaxAgeMs });
      if (!indexText) continue;
      materials.push(...parseIrMaterialsFromIndex(indexText, filing.filingDate, filing.form));
    } catch (error) {
      log.warn({ filingUrl: filing.filingUrl, error: serializeError(error) }, 'filing index parse failed');
    }
  }

  const deduped = uniqueMaterials(materials)
    .filter((item) => item.type !== 'other' || /investor|presentation|earnings|annual|quarter|exhibit 99/i.test(item.title))
    .slice(0, 10);

  for (const item of deduped) {
    if (item.summary || item.contentType === 'pdf') continue;
    try {
      const text = await fetchSecFilingText(item.url, { maxAgeMs: filingDetailMaxAgeMs });
      item.summary = text ? summarizeIrMaterialText(text) : null;
    } catch (error) {
      log.warn({ url: item.url, error: serializeError(error) }, 'ir material summary extraction failed');
    }
  }

  return deduped;
}

async function findSegmentGeoMixData(
  filings: CompanyFilingEvent[],
  facts: Awaited<ReturnType<typeof fetchSecCompanyFacts>>,
  ticker: string,
) {
  const filingDetailMaxAgeMs = getCompanyFilingDetailFreshMs(ticker);
  const xbrlMix = extractSegmentGeoMixFromFacts(facts);
  if (xbrlMix.note) {
    return {
      note: xbrlMix.note,
      segmentMix: xbrlMix.segmentMix,
      geoMix: xbrlMix.geoMix,
    };
  }

  const candidates = filings.filter((filing) => filing.filingUrl && (filing.form === '10-K' || filing.form === '10-Q')).slice(0, 2);
  for (const filing of candidates) {
    try {
      const text = await fetchSecFilingText(filing.filingUrl!, { maxAgeMs: filingDetailMaxAgeMs });
      const note = text ? extractSegmentGeoMix(text) : null;
      const inferred = text ? inferSegmentGeoEntriesFromText(text) : { note: null, segmentMix: [], geoMix: [] };
      if (note || inferred.note) return { note: note ?? inferred.note, segmentMix: inferred.segmentMix, geoMix: inferred.geoMix };
    } catch (error) {
      log.warn({ filingUrl: filing.filingUrl, error: serializeError(error) }, 'segment/geo mix extraction failed');
    }
  }

  return { note: null, segmentMix: [], geoMix: [] };
}

function applyPeerAverages(peers: CompanyPeerSummary[]): CompanyPeerSummary[] {
  const validGrowth = peers.map((item) => item.revenueGrowthYoY).filter((x): x is number => x !== null);
  const validMargin = peers.map((item) => item.operatingMargin).filter((x): x is number => x !== null);
  const validEv = peers.map((item) => item.evToSales).filter((x): x is number => x !== null);
  const avgGrowth = validGrowth.length ? validGrowth.reduce((a, b) => a + b, 0) / validGrowth.length : null;
  const avgMargin = validMargin.length ? validMargin.reduce((a, b) => a + b, 0) / validMargin.length : null;
  const avgEv = validEv.length ? validEv.reduce((a, b) => a + b, 0) / validEv.length : null;

  return peers.map((item) => ({
    ...item,
    vsPeerAvgRevenueGrowth: item.revenueGrowthYoY !== null && avgGrowth !== null ? Number((item.revenueGrowthYoY - avgGrowth).toFixed(1)) : null,
    vsPeerAvgOperatingMargin: item.operatingMargin !== null && avgMargin !== null ? Number((item.operatingMargin - avgMargin).toFixed(1)) : null,
    vsPeerAvgEvToSales: item.evToSales !== null && avgEv !== null ? Number((item.evToSales - avgEv).toFixed(2)) : null,
  }));
}

export async function buildCompanyResearch(ticker: string): Promise<CompanyResearchResponse> {
  const normalized = ticker.trim().toUpperCase();
  const cacheKey = companyResearchFullCacheKey(normalized);
  const fresh = await readSourceCacheWithin<CompanyResearchResponse>(cacheKey, COMPANY_RESEARCH_FULL_CACHE_MS);
  if (fresh?.value) return fresh.value;

  const existing = companyResearchFullInflight.get(cacheKey);
  if (existing) return existing;

  const task = buildCompanyResearchInternal(normalized, true)
    .then(async (result) => {
      await writeSourceCache(cacheKey, result);
      return result;
    })
    .catch(async (error) => {
      const stale = await readSourceCache<CompanyResearchResponse>(cacheKey);
      if (stale?.value) {
        log.warn({ ticker: normalized, error: serializeError(error) }, 'company research full fresh build failed, returning stale cache');
        return stale.value;
      }
      throw error;
    })
    .finally(() => {
      companyResearchFullInflight.delete(cacheKey);
    });

  companyResearchFullInflight.set(cacheKey, task);
  return task;
}

export async function buildCompanyResearchLite(ticker: string): Promise<CompanyResearchResponse> {
  const normalized = ticker.trim().toUpperCase();
  const cacheKey = companyResearchLiteCacheKey(normalized);
  const fresh = await readSourceCacheWithin<CompanyResearchResponse>(cacheKey, COMPANY_RESEARCH_LITE_CACHE_MS);
  if (fresh?.value) return fresh.value;

  const existing = companyResearchLiteInflight.get(cacheKey);
  if (existing) return existing;

  const task = buildCompanyResearchInternal(normalized, false)
    .then(async (result) => {
      await writeSourceCache(cacheKey, result);
      return result;
    })
    .catch(async (error) => {
      const stale = await readSourceCache<CompanyResearchResponse>(cacheKey);
      if (stale?.value) {
        log.warn({ ticker: normalized, error: serializeError(error) }, 'company research lite fresh build failed, returning stale cache');
        return stale.value;
      }
      throw error;
    })
    .finally(() => {
      companyResearchLiteInflight.delete(cacheKey);
    });

  companyResearchLiteInflight.set(cacheKey, task);
  return task;
}

async function buildPeerSummaries(baseTicker: string, name?: string | null, sic?: string | null): Promise<CompanyPeerSummary[]> {
  const peers = getAutoExpandedCompanyPeers({ ticker: baseTicker, name, sic }).slice(0, 5);
  const summaries = await Promise.all(peers.map(async (peer) => {
    try {
      const research = await buildCompanyResearchLite(peer.ticker);
      return {
        ticker: research.profile.ticker,
        name: research.profile.name,
        relation: peer.relation,
        peerGroup: peer.peerGroup,
        totalScore: research.score.totalScore,
        revenueGrowthYoY: research.financials.revenueGrowthYoY,
        operatingMargin: research.financials.operatingMargin,
        evToSales: research.financials.evToSales,
      } satisfies CompanyPeerSummary;
    } catch (error) {
      log.warn({ ticker: peer.ticker, error: serializeError(error) }, 'peer research build failed');
      return {
        ticker: peer.ticker,
        name: peer.ticker,
        relation: peer.relation,
        peerGroup: peer.peerGroup,
        totalScore: null,
        revenueGrowthYoY: null,
        operatingMargin: null,
        evToSales: null,
      } satisfies CompanyPeerSummary;
    }
  }));
  const sortable = summaries
    .map((item) => item.totalScore === null ? null : item)
    .filter((item): item is NonNullable<typeof item> => Boolean(item))
    .sort((a, b) => (b.totalScore ?? -1) - (a.totalScore ?? -1));
  const ranked = summaries.map((item) => {
    const idx = sortable.findIndex((candidate) => candidate.ticker === item.ticker);
    if (idx === -1) return { ...item, rank: null, percentile: null };
    const percentile = sortable.length <= 1 ? 100 : Math.round((1 - idx / (sortable.length - 1)) * 100);
    return { ...item, rank: idx + 1, percentile };
  });
  return applyPeerAverages(ranked);
}

async function buildCompanyResearchInternal(ticker: string, includePeers: boolean): Promise<CompanyResearchResponse> {
  const secEntry = await lookupSecCompanyByTicker(ticker);
  if (!secEntry) throw new Error(`SEC ticker mapping not found for ${ticker}`);
  const secFreshMs = getCompanySecFreshMs(secEntry.ticker);
  const filingDetailMaxAgeMs = getCompanyFilingDetailFreshMs(secEntry.ticker);

  const [submissions, facts, quote, analystConsensus, priceHistory] = await Promise.all([
    fetchSecSubmissions(secEntry.cik, { ticker: secEntry.ticker, maxAgeMs: secFreshMs, filingDetailMaxAgeMs }),
    fetchSecCompanyFacts(secEntry.cik, { ticker: secEntry.ticker, maxAgeMs: secFreshMs }),
    fetchYahooQuote(secEntry.ticker).catch((error) => {
      log.warn({ ticker, error: serializeError(error) }, 'yahoo quote fetch failed for company research');
      return null;
    }),
    fetchAnalystConsensus().catch(() => null),
    fetchYahooHistory(secEntry.ticker, 380).catch((error) => {
      log.warn({ ticker, error: serializeError(error) }, 'yahoo history fetch failed for company research');
      return [];
    }),
  ]);

  const financials = normalizeCompanyFinancials(secEntry.ticker, secEntry.cik, facts, quote?.price ?? null);
  financials.estimateUpsidePct = analystConsensus?.perTickerUpsidePct?.[secEntry.ticker] ?? null;
  financials.analystScore = analystConsensus?.perTicker?.[secEntry.ticker] ?? null;

  const filingsPromise = fetchRecentCompanyFilings(secEntry.cik, 10, { ticker: secEntry.ticker, maxAgeMs: secFreshMs, filingDetailMaxAgeMs });
  const snapshotPromise = includePeers ? getSnapshot(DEFAULT_PROFILE).catch((error) => {
    log.warn({ ticker, error: serializeError(error) }, 'company snapshot fetch failed');
    return null;
  }) : Promise.resolve(null);

  await recordCompanyAnalystSnapshot(secEntry.ticker, financials.analystScore ?? null, financials.estimateUpsidePct ?? null);
  const [
    analystHistory,
    estimateRevision7d,
    estimateRevision30d,
    estimateRevision90d,
    analystScoreRevision7dValue,
    analystScoreRevision30dValue,
    analystScoreRevision90dValue,
    filings,
  ] = await Promise.all([
    getCompanyAnalystHistory(secEntry.ticker),
    estimateRevisionDelta7d(secEntry.ticker, financials.estimateUpsidePct ?? null),
    estimateRevisionDelta30d(secEntry.ticker, financials.estimateUpsidePct ?? null),
    estimateRevisionDelta90d(secEntry.ticker, financials.estimateUpsidePct ?? null),
    analystScoreRevisionDelta7d(secEntry.ticker, financials.analystScore ?? null),
    analystScoreRevisionDelta30d(secEntry.ticker, financials.analystScore ?? null),
    analystScoreRevisionDelta90d(secEntry.ticker, financials.analystScore ?? null),
    filingsPromise,
  ]);
  financials.estimateUpsideHistory = analystHistory
    .map((item) => ({ date: item.date, value: item.upsidePct }))
    .filter((item) => item.value !== null)
    .slice(-12);
  financials.analystScoreHistory = analystHistory
    .map((item) => ({ date: item.date, value: item.analystScore }))
    .filter((item) => item.value !== null)
    .slice(-12);
  financials.estimateRevision7d = estimateRevision7d;
  financials.estimateRevision30d = estimateRevision30d;
  financials.estimateRevision90d = estimateRevision90d;
  financials.analystScoreRevision7d = analystScoreRevision7dValue;
  financials.analystScoreRevision30d = analystScoreRevision30dValue;
  financials.analystScoreRevision90d = analystScoreRevision90dValue;

  const [segmentGeoRaw, irMaterials] = await Promise.all([
    findSegmentGeoMixData(filings, facts, secEntry.ticker),
    buildIrMaterials(filings, secEntry.ticker),
  ]);
  const segmentGeo = applyRepresentativeSegmentGeoFallback(secEntry.ticker, segmentGeoRaw);
  financials.segmentGeoMixNote = segmentGeo.note;
  financials.segmentMix = segmentGeo.segmentMix;
  financials.geoMix = segmentGeo.geoMix;

  const score = computeCompanyScore(financials);
  const buyScore = buildCompanyBuyScore(financials, score);
  const peerGroup = inferCompanyPeerGroup({ ticker: secEntry.ticker, name: submissions.profile.name || secEntry.title, sic: submissions.profile.sic });

  const baseResearch = {
    profile: {
      ...submissions.profile,
      ticker: secEntry.ticker,
      cik: secEntry.cik,
      name: submissions.profile.name || secEntry.title,
    },
    quote: {
      symbol: quote?.symbol ?? secEntry.ticker,
      price: quote?.price ?? null,
      date: quote?.date ?? null,
    },
    financials,
    score,
    buyScore,
    filings,
    irMaterials,
    highlights: buildHighlights(financials, score),
    peerGroup,
    peers: [] ,
  } satisfies CompanyResearchResponse;

  const bottleneck = buildBottleneckInsight(baseResearch);
  let narrative: CompanyNarrativeInsight | null = null;
  const peerPromise = includePeers ? buildPeerSummaries(secEntry.ticker, submissions.profile.name || secEntry.title, submissions.profile.sic) : Promise.resolve([]);
  let snapshotForResearch = await snapshotPromise;
  if (includePeers) {
    try {
      if (snapshotForResearch) {
        const states = await buildNarrativeThemesForSnapshot(snapshotForResearch);
        const target = inferNarrativeThemeId(peerGroup, secEntry.ticker);
        const matched = target ? states.find((item) => item.theme.id === target) : null;
        if (matched) {
          narrative = {
            themeId: matched.theme.id,
            title: matched.theme.title,
            stage: matched.stage,
            heatScore: matched.heatScore,
            trend: matched.trend,
            riskNote: matched.stage === 'OVERHEATED' ? '좋은 회사여도 이미 많이 알려진 구간일 수 있습니다.' : matched.stage === 'MID' ? '확산 중인 내러티브입니다. 추격보다 선별 접근이 낫습니다.' : '아직 초기 확산 단계로 볼 수 있습니다.',
            drivers: matched.drivers.slice(0, 3),
          };
        }
      }
    } catch (error) {
      log.warn({ ticker, error: serializeError(error) }, 'company narrative insight build failed');
    }
  }
  const peers = await peerPromise;
  const capitalFlow = buildCapitalFlowInsight(secEntry.ticker, peerGroup, bottleneck, narrative, financials);
  const cashFlowQuality = buildCashFlowQualityInsight(financials);
  const multipleInsight = buildMultipleInsight(financials, narrative, peers);
  const guidanceInsight = buildGuidanceInsight(filings);
  const verdicts = buildCompanyVerdicts(financials, score, buyScore);
  const bottomSignal = buildCompanyBottomSignal(
    financials,
    score,
    buyScore,
    narrative,
    cashFlowQuality,
    multipleInsight,
    guidanceInsight,
    buildCompanyPriceContext(priceHistory),
  );
  const reversalConfirmation = buildReversalConfirmation(bottomSignal);
  const sectorContext = buildSectorContext(secEntry.ticker, snapshotForResearch);
  const correctionAssessment = buildCorrectionAssessment(
    financials,
    score,
    buyScore,
    guidanceInsight,
    narrative,
    bottomSignal,
    sectorContext,
    reversalConfirmation,
  );
  const thesisMonitor = buildThesisMonitor(
    financials,
    guidanceInsight,
    sectorContext,
    narrative,
    bottleneck,
    correctionAssessment,
  );
  const timeframeView = buildTimeframeView(buyScore, narrative, multipleInsight, verdicts);
  const positionSizing = buildPositionSizingPlan(buyScore, verdicts, bottleneck, narrative, multipleInsight, correctionAssessment, reversalConfirmation);
  const executionBridge = buildExecutionBridge(snapshotForResearch, peerGroup, secEntry.ticker, narrative, positionSizing);

  return {
    profile: {
      ...submissions.profile,
      ticker: secEntry.ticker,
      cik: secEntry.cik,
      name: submissions.profile.name || secEntry.title,
    },
    quote: {
      symbol: quote?.symbol ?? secEntry.ticker,
      price: quote?.price ?? null,
      date: quote?.date ?? null,
    },
    financials,
    score,
    buyScore,
    filings,
    irMaterials,
    highlights: buildHighlights(financials, score),
    peerGroup,
    bottleneck,
    narrative,
    capitalFlow,
    cashFlowQuality,
    multipleInsight,
    guidanceInsight,
    timeframeView,
    correctionAssessment,
    thesisMonitor,
    reversalConfirmation,
    sectorContext,
    verdicts,
    bottomSignal,
    positionSizing,
    executionBridge,
    peers,
  };
}
