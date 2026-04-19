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

export default async function PlanPage() {
  const [planData, logData, weeklyData] = await Promise.all([
    fetchPlan(),
    fetchTradeLog(),
    fetchWeeklyReport(),
  ]);

  return (
    <main className="flex-1 p-4 md:p-6 max-w-6xl mx-auto w-full">
      <PlanEditor
        initialPlan={planData?.plan || null}
        initialLog={logData?.entries || []}
        weeklyReport={weeklyData?.report || null}
        weeklyText={weeklyData?.text || ''}
      />
    </main>
  );
}
