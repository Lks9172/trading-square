import { fetchAnalystConsensus } from '../collectors/analyst-consensus';
import { fetchSecCompanyFacts } from '../collectors/sec/companyfacts';
import { fetchSecFilingText, extractSegmentGeoMix } from '../collectors/sec/filing-detail';
import { fetchRecentCompanyFilings } from '../collectors/sec/filings';
import { fetchSecSubmissions } from '../collectors/sec/submissions';
import { lookupSecCompanyByTicker } from '../collectors/sec/ticker-map';
import { fetchYahooQuote } from '../collectors/yahoo';
import { computeCompanyScore } from '../engines/fundamentals/company-score';
import { normalizeCompanyFinancials } from '../engines/fundamentals/normalize-financials';
import { CompanyIrMaterial, CompanyPeerSummary, CompanyResearchResponse, CompanyFilingEvent } from '../types/fundamentals';
import { recordCompanyAnalystSnapshot, estimateRevisionDelta30d } from './company-analyst-history';
import { childLogger, serializeError } from './logger';
import { getAutoExpandedCompanyPeers } from './company-peer-map';

const log = childLogger({ module: 'service.company-research' });

function buildHighlights(financials: CompanyResearchResponse['financials'], score: CompanyResearchResponse['score']): string[] {
  const highlights: string[] = [];
  if (financials.revenueGrowthYoY !== null) {
    highlights.push(
      financials.revenueGrowthYoY >= 10
        ? `매출 YoY ${financials.revenueGrowthYoY.toFixed(1)}% 성장`
        : `매출 YoY ${financials.revenueGrowthYoY.toFixed(1)}%로 성장 둔화`,
    );
  }
  if (financials.operatingMargin !== null) {
    highlights.push(`영업이익률 ${financials.operatingMargin.toFixed(1)}%`);
  }
  if (financials.estimateRevision30d !== null && financials.estimateRevision30d !== undefined) {
    highlights.push(`30일 컨센서스 변화 ${financials.estimateRevision30d >= 0 ? '+' : ''}${financials.estimateRevision30d.toFixed(1)}%p`);
  }
  if (financials.evToSales !== null) {
    highlights.push(`EV/Sales ${financials.evToSales.toFixed(1)}x`);
  }
  highlights.push(`종합 점수 ${score.totalScore}/100`);
  return highlights.slice(0, 5);
}

function classifyIrMaterialType(filing: CompanyFilingEvent): CompanyIrMaterial['type'] {
  const description = `${filing.primaryDocDescription ?? ''} ${filing.primaryDocument ?? ''}`.toLowerCase();
  if (/(presentation|slides|deck|supplemental)/i.test(description)) return 'presentation';
  if (filing.form === '10-K') return 'annual-report';
  if (filing.form === '10-Q') return 'quarterly-report';
  if (filing.isEarningsRelated || /(earnings|results of operations|quarterly results)/i.test(description)) {
    return 'earnings-release';
  }
  return 'other';
}

function buildIrMaterials(filings: CompanyFilingEvent[]): CompanyIrMaterial[] {
  return filings
    .filter((filing) => filing.filingUrl)
    .map((filing) => ({
      title: filing.primaryDocDescription || filing.primaryDocument || `${filing.form} filing`,
      form: filing.form,
      filingDate: filing.filingDate,
      url: filing.filingUrl!,
      type: classifyIrMaterialType(filing),
    }))
    .filter((item) => item.type !== 'other' || /investor|presentation|earnings|annual|quarter/i.test(item.title))
    .slice(0, 8);
}

async function findSegmentGeoMixNote(filings: CompanyFilingEvent[]): Promise<string | null> {
  const candidates = filings
    .filter((filing) => filing.filingUrl && (filing.form === '10-K' || filing.form === '10-Q'))
    .slice(0, 2);

  for (const filing of candidates) {
    try {
      const text = await fetchSecFilingText(filing.filingUrl!);
      const note = text ? extractSegmentGeoMix(text) : null;
      if (note) return note;
    } catch (error) {
      log.warn({ filingUrl: filing.filingUrl, error: serializeError(error) }, 'segment/geo mix extraction failed');
    }
  }
  return null;
}

export async function buildCompanyResearch(ticker: string): Promise<CompanyResearchResponse> {
  return buildCompanyResearchInternal(ticker, true);
}

async function buildPeerSummaries(baseTicker: string, name?: string | null, sic?: string | null): Promise<CompanyPeerSummary[]> {
  const peers = getAutoExpandedCompanyPeers({ ticker: baseTicker, name, sic }).slice(0, 5);
  const summaries = await Promise.all(peers.map(async (peer) => {
    try {
      const research = await buildCompanyResearchInternal(peer.ticker, false);
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
  return summaries.map((item) => {
    const idx = sortable.findIndex((candidate) => candidate.ticker === item.ticker);
    if (idx === -1) return { ...item, rank: null, percentile: null };
    const percentile = sortable.length <= 1 ? 100 : Math.round((1 - idx / (sortable.length - 1)) * 100);
    return { ...item, rank: idx + 1, percentile };
  });
}

async function buildCompanyResearchInternal(ticker: string, includePeers: boolean): Promise<CompanyResearchResponse> {
  const secEntry = await lookupSecCompanyByTicker(ticker);
  if (!secEntry) {
    throw new Error(`SEC ticker mapping not found for ${ticker}`);
  }

  const [submissions, facts, quote, analystConsensus] = await Promise.all([
    fetchSecSubmissions(secEntry.cik),
    fetchSecCompanyFacts(secEntry.cik),
    fetchYahooQuote(secEntry.ticker).catch((error) => {
      log.warn({ ticker, error: serializeError(error) }, 'yahoo quote fetch failed for company research');
      return null;
    }),
    fetchAnalystConsensus().catch(() => null),
  ]);

  const financials = normalizeCompanyFinancials(secEntry.ticker, secEntry.cik, facts, quote?.price ?? null);
  financials.estimateUpsidePct = analystConsensus?.perTickerUpsidePct?.[secEntry.ticker] ?? null;
  financials.analystScore = analystConsensus?.perTicker?.[secEntry.ticker] ?? null;
  await recordCompanyAnalystSnapshot(secEntry.ticker, financials.analystScore, financials.estimateUpsidePct ?? null);
  financials.estimateRevision30d = await estimateRevisionDelta30d(secEntry.ticker, financials.estimateUpsidePct ?? null);

  const filings = await fetchRecentCompanyFilings(secEntry.cik, 10);
  financials.segmentGeoMixNote = await findSegmentGeoMixNote(filings);

  const score = computeCompanyScore(financials);
  const irMaterials = buildIrMaterials(filings);

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
    filings,
    irMaterials,
    highlights: buildHighlights(financials, score),
    peers: includePeers
      ? await buildPeerSummaries(secEntry.ticker, submissions.profile.name || secEntry.title, submissions.profile.sic)
      : [],
  };
}
