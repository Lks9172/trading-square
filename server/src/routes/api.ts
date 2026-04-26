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
