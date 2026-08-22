import { buildCompanyResearchLite } from './company-research';
import { getResearchCompanyUniverse } from './company-peer-map';
import { childLogger, serializeError } from './logger';
import { readSourceCache, writeSourceCache } from './source-cache';

export type BottomCandidateState = '미충족' | '후보' | '확신';

export type TelegramBottomCompanySummary = {
  kind: 'company';
  ticker: string;
  name: string;
  sectorLabel: string | null;
  confirmedBottomState: Exclude<BottomCandidateState, '미충족'>;
  confirmedBottomScore: number | null;
  totalScore: number;
  buyScore: number;
  action: string | null;
  signalDate: string | null;
  reasons: string[];
};

type BottomCandidateStateSnapshot = {
  state: BottomCandidateState;
  score: number | null;
  totalScore: number | null;
  buyScore: number | null;
  action: string | null;
  updatedAt: string;
};

type BottomCandidateAlertState = {
  initializedAt: string;
  tickers: Record<string, BottomCandidateStateSnapshot>;
};

const log = childLogger({ module: 'service.company-bottom-alerts' });
const ALERT_STATE_CACHE_KEY = 'telegram-bottom-company-alert-state-v2';
const TELEGRAM_COMPANY_CANDIDATE_CACHE_KEY = 'current-telegram-bottom-company-candidates-v2';
const DEFAULT_STATE: BottomCandidateAlertState = {
  initializedAt: new Date(0).toISOString(),
  tickers: {},
};

async function mapWithConcurrency<T, R>(
  items: T[],
  concurrency: number,
  worker: (item: T, index: number) => Promise<R>,
): Promise<R[]> {
  const results = new Array<R>(items.length);
  let cursor = 0;
  const runners = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (true) {
      const currentIndex = cursor++;
      if (currentIndex >= items.length) return;
      results[currentIndex] = await worker(items[currentIndex], currentIndex);
    }
  });
  await Promise.all(runners);
  return results;
}

function isBuyAction(action: string | null | undefined): boolean {
  return action === 'BUY' || action === 'STRONG BUY';
}

function isTelegramBottomCandidate(
  state: BottomCandidateState | undefined,
  buyScore: number,
  totalScore: number,
  action: string | null | undefined,
): state is Exclude<BottomCandidateState, '미충족'> {
  return (state === '후보' || state === '확신')
    && buyScore >= 70
    && totalScore >= 70
    && isBuyAction(action);
}

function compareCandidates(a: TelegramBottomCompanySummary, b: TelegramBottomCompanySummary) {
  const stateDelta = (b.confirmedBottomState === '확신' ? 1 : 0) - (a.confirmedBottomState === '확신' ? 1 : 0);
  if (stateDelta !== 0) return stateDelta;
  const bottomDelta = (b.confirmedBottomScore ?? -1) - (a.confirmedBottomScore ?? -1);
  if (bottomDelta !== 0) return bottomDelta;
  const buyDelta = b.buyScore - a.buyScore;
  if (buyDelta !== 0) return buyDelta;
  return b.totalScore - a.totalScore;
}

function toCompanySummary(
  ticker: string,
  research: Awaited<ReturnType<typeof buildCompanyResearchLite>>,
  confirmedState: Exclude<BottomCandidateState, '미충족'>,
  confirmedScore: number | null,
): TelegramBottomCompanySummary {
  return {
    kind: 'company',
    ticker,
    name: research.profile.name || ticker,
    sectorLabel: research.sectorContext?.label ?? null,
    confirmedBottomState: confirmedState,
    confirmedBottomScore: confirmedScore,
    totalScore: research.score.totalScore,
    buyScore: research.buyScore.buyScore,
    action: research.executionBridge?.companyActionLabel ?? research.positionSizing?.action ?? null,
    signalDate: research.bottomSignal?.confirmedBottom?.signalDate ?? null,
    reasons: research.bottomSignal?.confirmedBottom?.reasons ?? [],
  };
}

async function readAlertState(): Promise<BottomCandidateAlertState> {
  const cached = await readSourceCache<BottomCandidateAlertState>(ALERT_STATE_CACHE_KEY);
  if (!cached?.value || typeof cached.value !== 'object') return DEFAULT_STATE;
  return {
    initializedAt: cached.value.initializedAt || DEFAULT_STATE.initializedAt,
    tickers: cached.value.tickers || {},
  };
}

async function enrichTelegramBottomCandidate(ticker: string): Promise<TelegramBottomCompanySummary | null> {
  try {
    const research = await buildCompanyResearchLite(ticker);
    const confirmed = research.bottomSignal?.confirmedBottom;
    const confirmedState = confirmed?.state;
    if (!isTelegramBottomCandidate(confirmedState, research.buyScore.buyScore, research.score.totalScore, research.positionSizing?.action)) return null;
    return toCompanySummary(ticker, research, confirmedState, confirmed?.score ?? null);
  } catch (error) {
    log.warn({ ticker, error: serializeError(error) }, 'telegram bottom company enrich failed');
    return null;
  }
}

export async function getCurrentTelegramBottomCompanyCandidates(
  limit = 5,
  options?: { allowFullScan?: boolean },
): Promise<TelegramBottomCompanySummary[]> {
  const cachedSummary = await readSourceCache<TelegramBottomCompanySummary[]>(TELEGRAM_COMPANY_CANDIDATE_CACHE_KEY);
  if (cachedSummary?.value?.length) {
    return cachedSummary.value.slice().sort(compareCandidates).slice(0, limit);
  }

  if (options?.allowFullScan === false) return [];

  const universe = getResearchCompanyUniverse();
  const scanned = await mapWithConcurrency(universe, 6, async (ticker) => enrichTelegramBottomCandidate(ticker));
  return scanned
    .filter((item): item is TelegramBottomCompanySummary => Boolean(item))
    .sort(compareCandidates)
    .slice(0, limit);
}

export async function checkAndNotifyTelegramBottomCompanyCandidates(triggeredBy: string): Promise<void> {
  const universe = getResearchCompanyUniverse();
  if (!universe.length) return;

  const previousState = await readAlertState();
  const currentTickers: Record<string, BottomCandidateStateSnapshot> = {};
  const currentCandidates: TelegramBottomCompanySummary[] = [];

  await mapWithConcurrency(universe, 6, async (ticker) => {
    try {
      const research = await buildCompanyResearchLite(ticker);
      const confirmed = research.bottomSignal?.confirmedBottom;
      const state = confirmed?.state ?? '미충족';
      const score = confirmed?.score ?? null;
      const totalScore = research.score.totalScore;
      const buyScore = research.buyScore.buyScore;
      currentTickers[ticker] = {
        state,
        score,
        totalScore,
        buyScore,
        action: research.positionSizing?.action ?? null,
        updatedAt: new Date().toISOString(),
      };

      if (isTelegramBottomCandidate(state, buyScore, totalScore, research.positionSizing?.action)) {
        currentCandidates.push(toCompanySummary(ticker, research, state, score));
      }
    } catch (error) {
      if (previousState.tickers[ticker]) {
        currentTickers[ticker] = previousState.tickers[ticker];
      }
      log.warn({ ticker, triggeredBy, error: serializeError(error) }, 'telegram bottom company scan failed');
    }
  });

  const nextState: BottomCandidateAlertState = {
    initializedAt: previousState.initializedAt === DEFAULT_STATE.initializedAt
      ? new Date().toISOString()
      : previousState.initializedAt,
    tickers: currentTickers,
  };

  await writeSourceCache(ALERT_STATE_CACHE_KEY, nextState, {
    triggeredBy,
    tickerCount: Object.keys(currentTickers).length,
    candidateCount: currentCandidates.length,
  });

  const sortedCurrent = currentCandidates.slice().sort(compareCandidates);
  await writeSourceCache(
    TELEGRAM_COMPANY_CANDIDATE_CACHE_KEY,
    sortedCurrent.slice(0, 30),
    { triggeredBy, count: sortedCurrent.length },
  );

  if (previousState.initializedAt === DEFAULT_STATE.initializedAt) {
    log.info({ triggeredBy, tickerCount: universe.length }, 'telegram bottom company baseline initialized');
    return;
  }

  const newlyQualified = sortedCurrent.filter((candidate) => {
    const prev = previousState.tickers[candidate.ticker];
    if (!prev) return true;
    return !isTelegramBottomCandidate(prev.state, prev.buyScore ?? -1, prev.totalScore ?? -1, prev.action);
  });

  if (!newlyQualified.length) {
    log.info({ triggeredBy, tickerCount: universe.length }, 'telegram bottom company scan completed without new candidates');
    return;
  }

  log.info({
    triggeredBy,
    count: newlyQualified.length,
    tickers: newlyQualified.map((item) => item.ticker),
  }, 'telegram bottom company candidates updated');
}
