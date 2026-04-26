import { PlanEditor } from '@/components/PlanEditor';

export const dynamic = 'force-dynamic';

const SSR_API_URL = process.env.SSR_API_URL || 'http://localhost:5846';

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

export default async function PlanPage() {
  const [planData, logData, weeklyData, snapshot] = await Promise.all([
    fetchPlan(),
    fetchTradeLog(),
    fetchWeeklyReport(),
    fetchSnapshot(),
  ]);

  const conviction = snapshot?.derived?.CONVICTION_SCORE_7AXIS?.value ?? null;
  const trancheUsed = snapshot?.meta?.profile?.manualInputs?.trancheUsedPct;
  // 22차 P1#4: 운영자 한마디 — derived formula 에서 short 추출
  const quoteFormula = snapshot?.derived?.OPERATOR_PHILOSOPHY_QUOTE_INDEX?.formula ?? '';
  const shortMatch = quoteFormula.match(/오늘의 운영자 한마디: "([^"]+)"/);
  const operatorQuote = shortMatch ? { short: shortMatch[1], full: shortMatch[1] } : null;

  return (
    <main className="flex-1 p-4 md:p-6 max-w-6xl mx-auto w-full">
      <PlanEditor
        initialPlan={planData?.plan || null}
        initialLog={logData?.entries || []}
        weeklyReport={weeklyData?.report || null}
        weeklyText={weeklyData?.text || ''}
        convictionScore={conviction}
        trancheUsedPct={trancheUsed}
        operatorQuote={operatorQuote}
      />
    </main>
  );
}
