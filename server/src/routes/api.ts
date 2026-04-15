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

const router = Router();

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
router.post('/execution-plan/tranche', async (req: Request, res: Response) => {
  try {
    const body = req.body || {};
    const asset = String(body.asset || '').trim();
    const stage = Number(body.stage);
    if (!asset || !Number.isFinite(stage) || stage < 1 || stage > 3) {
      res.status(400).json({ error: 'asset(string) + stage(1~3) required' });
      return;
    }

    // 현재 snapshot 에서 regime/price 보강
    let regimeAtEntry: string | null = null;
    let priceAtEntry: number | null =
      typeof body.priceAtEntry === 'number' ? body.priceAtEntry : null;
    try {
      const snap = await getSnapshot(DEFAULT_PROFILE);
      regimeAtEntry = snap.regime?.regime ?? null;
      if (priceAtEntry === null) {
        const raw = (snap as any).raw as Record<string, { value: number }> | undefined;
        const candidate = raw?.[asset]?.value;
        if (typeof candidate === 'number') priceAtEntry = candidate;
      }
    } catch {
      /* snapshot 읽기 실패 시 regime/price 는 null 로 저장 */
    }

    const entry: TrancheEntry = {
      asset,
      stage,
      executedAt: new Date().toISOString(),
      priceAtEntry,
      regimeAtEntry,
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
