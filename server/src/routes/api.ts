import { Router, Request, Response } from 'express';
import { collectAll } from '../collectors';
import { computeDerived } from '../engines/derived';
import { classifyRegime } from '../engines/regime';
import { computeSignals } from '../engines/signals';
import { computeAllocation } from '../engines/allocation';
import { UserProfile, SystemSnapshot } from '../types/indicators';

const router = Router();

const DEFAULT_PROFILE: UserProfile = {
  riskTolerance: 'moderate',
  leverageEnabled: false,
  includeCrypto: false,
  includeKR: true,
  manualInputs: {
    policyDirection: 0,
    geoRisk: 2,
    cbBuying: true,
  },
};

let cachedSnapshot: SystemSnapshot | null = null;
let cacheTime = 0;
const CACHE_TTL = 5 * 60 * 1000;

async function buildSnapshot(profile: UserProfile): Promise<SystemSnapshot> {
  const apiKey = process.env.FRED_API_KEY || '';
  const raw = await collectAll(apiKey);
  const derived = await computeDerived(raw);
  const regime = classifyRegime({ raw, derived, manualInputs: profile.manualInputs });
  const signals = computeSignals(raw, derived, regime, profile);
  const allocation = computeAllocation(regime.regime, regime.score, signals, derived, raw);

  return {
    timestamp: new Date().toISOString(),
    raw,
    derived,
    regime,
    signals,
    allocation,
  };
}

router.get('/snapshot', async (_req: Request, res: Response) => {
  try {
    const now = Date.now();
    if (cachedSnapshot && now - cacheTime < CACHE_TTL) {
      res.json(cachedSnapshot);
      return;
    }

    const snapshot = await buildSnapshot(DEFAULT_PROFILE);
    cachedSnapshot = snapshot;
    cacheTime = now;
    res.json(snapshot);
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.post('/snapshot', async (req: Request, res: Response) => {
  try {
    const profile: UserProfile = { ...DEFAULT_PROFILE, ...req.body };
    const snapshot = await buildSnapshot(profile);
    cachedSnapshot = snapshot;
    cacheTime = Date.now();
    res.json(snapshot);
  } catch (err: any) {
    res.status(500).json({ error: err.message || 'Internal server error' });
  }
});

router.get('/health', (_req: Request, res: Response) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

export default router;
