import { PlanEditor } from '@/components/PlanEditor';
import {
  PurchasingPowerPanel,
  type PurchasingPowerProjectionView,
} from '@/components/PurchasingPowerPanel';

export const dynamic = 'force-dynamic';

const SSR_API_URL = process.env.SSR_API_URL || process.env.INTERNAL_API_URL || (process.env.NODE_ENV === 'development' ? 'http://localhost:5846' : 'http://macrosquare-server:5846');

async function fetchPlan() {
  try {
    const res = await fetch(`${SSR_API_URL}/api/plan`, { cache: 'no-store' });
    if (!res.ok) return null;
    return res.json();
  } catch {
    return null;
  }
}

async function fetchTradeLog() {
  try {
    const res = await fetch(`${SSR_API_URL}/api/trade-log?limit=50`, { cache: 'no-store' });
    if (!res.ok) return null;
    return res.json();
  } catch {
    return null;
  }
}

async function fetchWeeklyReport() {
  try {
    const res = await fetch(`${SSR_API_URL}/api/weekly-report`, { cache: 'no-store' });
    if (!res.ok) return null;
    return res.json();
  } catch {
    return null;
  }
}

async function fetchSnapshot() {
  try {
    const res = await fetch(`${SSR_API_URL}/api/snapshot`, { cache: 'no-store' });
    if (!res.ok) return null;
    return res.json();
  } catch {
    return null;
  }
}

async function fetchPurchasingPower(): Promise<{ projection?: PurchasingPowerProjectionView } | null> {
  try {
    const res = await fetch(`${SSR_API_URL}/api/execution-plan/purchasing-power`, { cache: 'no-store' });
    if (!res.ok) return null;
    return res.json();
  } catch {
    return null;
  }
}

export default async function PlanPage() {
  const [planData, logData, weeklyData, snapshot, purchasingPower] = await Promise.all([
    fetchPlan(),
    fetchTradeLog(),
    fetchWeeklyReport(),
    fetchSnapshot(),
    fetchPurchasingPower(),
  ]);

  const currentDerivedValue = (key: string): number | null => {
    const point = snapshot?.derived?.[key];
    return point?.eligibleForSignals === false ? null : point?.value ?? null;
  };
  const conviction = currentDerivedValue('CONVICTION_SCORE_7AXIS');
  const trancheUsed = snapshot?.meta?.profile?.manualInputs?.trancheUsedPct;
  const quoteFormula = snapshot?.derived?.OPERATOR_PHILOSOPHY_QUOTE_INDEX?.formula ?? '';
  const shortMatch = quoteFormula.match(/오늘의 운영자 한마디: "([^"]+)"/);
  const operatorQuote = shortMatch ? { short: shortMatch[1], full: shortMatch[1] } : null;
  // 28차 영상6: 4단 우선순위 + 손익비 + 오해
  const priorityOrderScore = currentDerivedValue('INVESTOR_PRIORITY_ORDER_SCORE');
  const riskRewardRatio = currentDerivedValue('NASDAQ_RISK_REWARD_RATIO');
  const misconceptionFlags = currentDerivedValue('INVESTOR_MISCONCEPTION_FLAGS');

  return (
    <main className="flex-1 p-4 md:p-6 max-w-6xl mx-auto w-full">
      <PurchasingPowerPanel initialProjection={purchasingPower?.projection ?? null} />
      <PlanEditor
        initialPlan={planData?.plan || null}
        initialLog={logData?.entries || []}
        weeklyReport={weeklyData?.report || null}
        weeklyText={weeklyData?.text || ''}
        convictionScore={conviction}
        trancheUsedPct={trancheUsed}
        operatorQuote={operatorQuote}
        priorityOrderScore={priorityOrderScore}
        riskRewardRatio={riskRewardRatio}
        misconceptionFlags={misconceptionFlags}
      />
    </main>
  );
}
