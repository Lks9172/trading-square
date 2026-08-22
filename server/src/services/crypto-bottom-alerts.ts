import { buildAllCryptoResearch } from './crypto-research';
import { childLogger } from './logger';
import { readSourceCache, writeSourceCache } from './source-cache';
import type { BottomCandidateState } from './company-bottom-alerts';

export type TelegramBottomCryptoSummary = {
  kind: 'crypto';
  symbol: string;
  name: string;
  category: string;
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
  symbols: Record<string, BottomCandidateStateSnapshot>;
};

const log = childLogger({ module: 'service.crypto-bottom-alerts' });
const ALERT_STATE_CACHE_KEY = 'telegram-bottom-crypto-alert-state-v1';
const TELEGRAM_CRYPTO_CANDIDATE_CACHE_KEY = 'current-telegram-bottom-crypto-candidates-v1';
const DEFAULT_STATE: BottomCandidateAlertState = {
  initializedAt: new Date(0).toISOString(),
  symbols: {},
};

function computeCryptoTotalScore(research: Awaited<ReturnType<typeof buildAllCryptoResearch>>[number]): number {
  return Math.round(
    research.profile.foundationalScore * 0.2
    + research.bottomUp.networkScore * 0.15
    + research.bottomUp.tokenomicsScore * 0.15
    + research.bottomUp.adoptionScore * 0.15
    + research.moat.moatScore * 0.1
    + research.onchain.activityScore * 0.15
    + research.supplyPressure.floatScore * 0.1,
  );
}

function isStrongBuyAction(action: string | null | undefined): boolean {
  return action === 'STRONG BUY';
}

function isTelegramBottomCandidate(state: BottomCandidateState | undefined, buyScore: number, totalScore: number, action: string | null | undefined): state is Exclude<BottomCandidateState, '미충족'> {
  return (state === '후보' || state === '확신') && buyScore >= 70 && totalScore >= 70 && isStrongBuyAction(action);
}

function compareCandidates(a: TelegramBottomCryptoSummary, b: TelegramBottomCryptoSummary) {
  const stateDelta = (b.confirmedBottomState === '확신' ? 1 : 0) - (a.confirmedBottomState === '확신' ? 1 : 0);
  if (stateDelta !== 0) return stateDelta;
  const bottomDelta = (b.confirmedBottomScore ?? -1) - (a.confirmedBottomScore ?? -1);
  if (bottomDelta !== 0) return bottomDelta;
  const buyDelta = b.buyScore - a.buyScore;
  if (buyDelta !== 0) return buyDelta;
  return b.totalScore - a.totalScore;
}

function toCryptoSummary(
  research: Awaited<ReturnType<typeof buildAllCryptoResearch>>[number],
  totalScore: number,
  state: Exclude<BottomCandidateState, '미충족'>,
): TelegramBottomCryptoSummary {
  return {
    kind: 'crypto',
    symbol: research.profile.symbol,
    name: research.profile.name,
    category: research.profile.category,
    confirmedBottomState: state,
    confirmedBottomScore: research.bottomSignal.confirmedBottom?.score ?? null,
    totalScore,
    buyScore: research.buyScore.buyScore,
    action: research.buyScore.actionLabel ?? null,
    signalDate: research.bottomSignal.confirmedBottom?.signalDate ?? null,
    reasons: research.bottomSignal.confirmedBottom?.reasons ?? [],
  };
}

export async function getCurrentTelegramBottomCryptoCandidates(limit = 5): Promise<TelegramBottomCryptoSummary[]> {
  const cachedSummary = await readSourceCache<TelegramBottomCryptoSummary[]>(TELEGRAM_CRYPTO_CANDIDATE_CACHE_KEY);
  if (cachedSummary?.value?.length) {
    return cachedSummary.value.slice().sort(compareCandidates).slice(0, limit);
  }
  return [];
}

export async function refreshTelegramBottomCryptoCandidates(triggeredBy: string): Promise<void> {
  const previousState = (await readSourceCache<BottomCandidateAlertState>(ALERT_STATE_CACHE_KEY))?.value ?? DEFAULT_STATE;
  const all = await buildAllCryptoResearch();
  const currentSymbols: Record<string, BottomCandidateStateSnapshot> = {};
  const currentCandidates: TelegramBottomCryptoSummary[] = [];

  for (const research of all) {
    const state = research.bottomSignal.confirmedBottom?.state ?? '미충족';
    const totalScore = computeCryptoTotalScore(research);
    const buyScore = research.buyScore.buyScore;
    currentSymbols[research.profile.symbol] = {
      state,
      score: research.bottomSignal.confirmedBottom?.score ?? null,
      totalScore,
      buyScore,
      action: research.buyScore.action,
      updatedAt: new Date().toISOString(),
    };
    if (isTelegramBottomCandidate(state, buyScore, totalScore, research.buyScore.action)) {
      currentCandidates.push(toCryptoSummary(research, totalScore, state));
    }
  }

  const nextState: BottomCandidateAlertState = {
    initializedAt: previousState.initializedAt === DEFAULT_STATE.initializedAt ? new Date().toISOString() : previousState.initializedAt,
    symbols: currentSymbols,
  };

  await writeSourceCache(ALERT_STATE_CACHE_KEY, nextState, {
    triggeredBy,
    symbolCount: Object.keys(currentSymbols).length,
    candidateCount: currentCandidates.length,
  });
  await writeSourceCache(TELEGRAM_CRYPTO_CANDIDATE_CACHE_KEY, currentCandidates.slice().sort(compareCandidates).slice(0, 20), {
    triggeredBy,
    count: currentCandidates.length,
  });

  log.info({ triggeredBy, candidateCount: currentCandidates.length }, 'telegram bottom crypto candidates refreshed');
}
