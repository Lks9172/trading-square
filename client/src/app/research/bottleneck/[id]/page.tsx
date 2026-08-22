import { fetchServerJson } from "@/lib/server-api";
export const revalidate = 900;
export const dynamic = "force-dynamic";

type PageData = {
  theme?: { title: string; description: string; id: string };
  summary?: { averageScore: number; coreCount: number; strongCount: number; topTickers: string[] };
  items?: Array<{
    ticker: string;
    company: string;
    role: string;
    score: number;
    conviction: 'WATCH' | 'STRONG' | 'CORE';
    reasons: string[];
    textMatches: Array<{ label: string; count: number; score: number; reason: string; excerpts?: string[] }>;
    componentScores: { textSignal: number; quality: number; concentration: number; supplyTightness: number; capexLinkage: number; switchingCost: number };
  }>;
  error?: string;
};

async function fetchData(id: string): Promise<PageData> {
  return (await fetchServerJson<PageData>(`/api/bottleneck/themes/${encodeURIComponent(id)}`, { revalidate: 900 })) ?? {};
}

function convictionTone(conviction: 'WATCH' | 'STRONG' | 'CORE') {
  if (conviction === 'CORE') return 'border-emerald-500/30 bg-emerald-500/10 text-emerald-100';
  if (conviction === 'STRONG') return 'border-cyan-500/30 bg-cyan-500/10 text-cyan-100';
  return 'border-amber-500/30 bg-amber-500/10 text-amber-100';
}

export default async function BottleneckThemePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const data = await fetchData(id);
  if (!data.theme) return <main className="flex-1 p-4 md:p-6 max-w-5xl mx-auto w-full"><div className="rounded-2xl border border-red-500/20 bg-red-500/10 p-5 text-sm text-red-100">병목 테마를 찾을 수 없습니다.</div></main>;
  return <main className="flex-1 p-4 md:p-6 max-w-6xl mx-auto w-full"><div className="space-y-6"><header className="space-y-2"><a href="/research" className="text-xs text-cyan-300 cursor-pointer hover:text-cyan-200">← Research로 돌아가기</a><h1 className="text-2xl font-bold tracking-tight">{data.theme.title}</h1><p className="text-sm text-[var(--muted)]">{data.theme.description}</p><div className="rounded-xl border border-cyan-500/20 bg-cyan-500/10 px-3 py-2 text-xs text-cyan-100">이 점수는 구조적 병목 후보 강도입니다. 직접적인 매수 타이밍 점수가 아닙니다.</div></header>{data.summary && <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5"><div className="flex flex-wrap gap-2 text-xs"><span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-3 py-1 text-cyan-100">평균 {data.summary.averageScore}점</span><span className="rounded-full border border-emerald-500/20 bg-emerald-500/10 px-3 py-1 text-emerald-100">CORE {data.summary.coreCount}</span><span className="rounded-full border border-sky-500/20 bg-sky-500/10 px-3 py-1 text-sky-100">STRONG {data.summary.strongCount}</span><span className="rounded-full border border-white/10 px-3 py-1 text-white/80">TOP {data.summary.topTickers.join(', ')}</span></div></section>}<section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5"><div className="grid grid-cols-1 md:grid-cols-2 gap-3">{(data.items ?? []).map((item, index) => <div key={item.ticker} className="rounded-xl border border-white/10 bg-black/15 p-4"><div className="flex items-start justify-between gap-3"><div><div className="text-xs text-cyan-200">#{index + 1}</div><div className="mt-1 font-medium text-white">{item.ticker}</div><div className="text-xs text-[var(--muted)] break-words">{item.company} · {item.role}</div></div><div className="text-right"><div className="rounded-full border border-cyan-500/30 bg-cyan-500/10 px-3 py-1 text-sm text-cyan-100">{item.score}점</div><div className={`mt-2 rounded-full border px-2.5 py-1 text-[11px] ${convictionTone(item.conviction)}`}>{item.conviction}</div></div></div><div className="mt-4 flex flex-wrap gap-2 text-[11px]">{item.reasons.slice(0,5).map((reason) => <span key={reason} className="rounded-full border border-white/10 px-2 py-1 text-slate-200">{reason}</span>)}</div>{item.textMatches.length > 0 && <div className="mt-4"><div className="text-[11px] text-cyan-200">텍스트 매치</div><div className="mt-2 flex flex-wrap gap-2">{item.textMatches.slice(0,4).map((match) => <span key={`${match.label}-${match.count}`} className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-1 text-[11px] text-cyan-100">{match.label} ×{match.count}</span>)}</div>{item.textMatches.some((match) => (match.excerpts?.length ?? 0) > 0) && <ul className="mt-3 list-disc pl-4 text-[11px] text-[var(--muted)]">{item.textMatches.flatMap((match) => match.excerpts ?? []).slice(0,3).map((excerpt, idx) => <li key={idx}>{excerpt}</li>)}</ul>}</div>}<div className="mt-4 grid grid-cols-3 md:grid-cols-6 gap-2 text-[11px]"><div className="rounded-lg bg-white/5 p-2"><div className="text-[var(--muted)]">텍스트</div><div className="mt-1 text-white">{item.componentScores.textSignal.toFixed(1)}</div></div><div className="rounded-lg bg-white/5 p-2"><div className="text-[var(--muted)]">퀄리티</div><div className="mt-1 text-white">{item.componentScores.quality.toFixed(1)}</div></div><div className="rounded-lg bg-white/5 p-2"><div className="text-[var(--muted)]">과점</div><div className="mt-1 text-white">{item.componentScores.concentration.toFixed(1)}</div></div><div className="rounded-lg bg-white/5 p-2"><div className="text-[var(--muted)]">공급</div><div className="mt-1 text-white">{item.componentScores.supplyTightness.toFixed(1)}</div></div><div className="rounded-lg bg-white/5 p-2"><div className="text-[var(--muted)]">CAPEX</div><div className="mt-1 text-white">{item.componentScores.capexLinkage.toFixed(1)}</div></div><div className="rounded-lg bg-white/5 p-2"><div className="text-[var(--muted)]">전환비용</div><div className="mt-1 text-white">{item.componentScores.switchingCost.toFixed(1)}</div></div></div><div className="mt-4"><a href={`/company/${item.ticker}`} className="inline-flex rounded-full border border-cyan-500/30 bg-cyan-500/10 px-3 py-1.5 text-xs text-cyan-200 cursor-pointer hover:bg-cyan-500/20 active:scale-[0.99]">회사 상세 보기</a></div></div>)}</div></section></div></main>;
}
