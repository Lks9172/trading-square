"use client";
import { SmartLink } from "@/components/SmartLink";

import { useMemo, useState } from 'react';

type ThemeCompanyItem = {
  rank: number;
  ticker: string;
  name: string;
  totalScore: number | null;
  buyScore: number | null;
  buyLabel: string | null;
  appealScore: number | null;
  crowdingScore: number | null;
  revenueGrowthYoY: number | null;
  operatingMargin: number | null;
  evToSales: number | null;
  error?: string;
};

function formatPct(value: number | null, digits = 1) {
  return value === null ? '—' : `${value.toFixed(digits)}%`;
}

function formatMultiple(value: number | null) {
  return value === null ? '—' : `${value.toFixed(1)}x`;
}

export function ThemeCompanyCompare({ items }: { items: ThemeCompanyItem[] }) {
  const [selected, setSelected] = useState<string[]>([]);

  const selectedItems = useMemo(
    () => items.filter((item) => selected.includes(item.ticker)),
    [items, selected],
  );

  function toggle(ticker: string) {
    setSelected((prev) => {
      if (prev.includes(ticker)) return prev.filter((item) => item !== ticker);
      return [...prev, ticker].slice(0, 4);
    });
  }

  return (
    <>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        {items.map((item) => {
          const active = selected.includes(item.ticker);
          return (
            <div key={item.ticker} className="rounded-xl border border-white/10 bg-black/15 p-4">
              <div className="flex items-start justify-between gap-3">
                <div>
                  <div className="text-xs text-cyan-200">#{item.rank}</div>
                  <div className="mt-1 font-medium text-white">{item.ticker}</div>
                  <div className="text-xs text-[var(--muted)] break-words">{item.name}</div>
                </div>
                <div className="text-right">
                  <div className="rounded-full border border-cyan-500/30 bg-cyan-500/10 px-3 py-1 text-sm text-cyan-100">
                    총점 {item.totalScore ?? '—'}
                  </div>
                  {item.buyScore !== null ? <div className="mt-2 rounded-full border border-emerald-500/20 bg-emerald-500/10 px-3 py-1 text-xs text-emerald-100">B {item.buyScore} · {item.buyLabel}</div> : null}
                </div>
              </div>

              <div className="mt-4 grid grid-cols-2 md:grid-cols-5 gap-2 text-xs">
                <div className="rounded-lg bg-white/5 p-2">
                  <div className="text-[var(--muted)]">매력도</div>
                  <div className="mt-1 text-white">{item.appealScore ?? '—'}</div>
                </div>
                <div className="rounded-lg bg-white/5 p-2">
                  <div className="text-[var(--muted)]">과열도</div>
                  <div className="mt-1 text-white">{item.crowdingScore ?? '—'}</div>
                </div>
                <div className="rounded-lg bg-white/5 p-2">
                  <div className="text-[var(--muted)]">매출 YoY</div>
                  <div className="mt-1 text-white">{formatPct(item.revenueGrowthYoY)}</div>
                </div>
                <div className="rounded-lg bg-white/5 p-2">
                  <div className="text-[var(--muted)]">영업이익률</div>
                  <div className="mt-1 text-white">{formatPct(item.operatingMargin)}</div>
                </div>
                <div className="rounded-lg bg-white/5 p-2">
                  <div className="text-[var(--muted)]">EV/Sales</div>
                  <div className="mt-1 text-white">{formatMultiple(item.evToSales)}</div>
                </div>
              </div>

              {item.error && <div className="mt-3 text-xs text-amber-200">일부 데이터 로드 실패: {item.error}</div>}

              <div className="mt-4 flex flex-wrap gap-2">
                <SmartLink
                  href={`/company/${item.ticker}`}
                  prefetch={false}
                  className="inline-flex rounded-full border border-cyan-500/30 bg-cyan-500/10 px-3 py-1.5 text-xs text-cyan-200 cursor-pointer hover:bg-cyan-500/20 active:scale-[0.99]"
                >
                  회사 상세 보기
                </SmartLink>
                <button
                  type="button"
                  onClick={() => toggle(item.ticker)}
                  className={`inline-flex rounded-full border px-3 py-1.5 text-xs ${active ? 'border-amber-500/30 bg-amber-500/10 text-amber-100' : 'border-white/10 text-white/70 cursor-pointer hover:bg-white/5 active:scale-[0.99]'}`}
                >
                  {active ? '비교 해제' : '비교 추가'}
                </button>
              </div>
            </div>
          );
        })}
      </div>

      {selectedItems.length >= 2 && (
        <div className="fixed inset-x-0 bottom-4 z-40 mx-auto w-[min(1100px,calc(100%-2rem))] rounded-2xl border border-white/10 bg-[rgba(10,14,24,0.95)] p-4 shadow-2xl backdrop-blur">
          <div className="mb-3 flex items-center justify-between gap-3">
            <div>
              <div className="text-sm font-semibold text-white">기업 비교</div>
              <div className="text-xs text-[var(--muted)]">최대 4개까지 핵심 지표를 나란히 비교합니다.</div>
            </div>
            <button type="button" onClick={() => setSelected([])} className="rounded-full border border-white/10 px-3 py-1 text-xs text-white/70 cursor-pointer hover:bg-white/5 active:scale-[0.99]">닫기</button>
          </div>
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead>
                <tr className="text-left text-[11px] text-[var(--muted)]">
                  <th className="p-2">지표</th>
                  {selectedItems.map((item) => <th key={item.ticker} className="p-2 text-white">{item.ticker}</th>)}
                </tr>
              </thead>
              <tbody>
                {([
                  { label: '총점', getter: (item: ThemeCompanyItem) => item.totalScore ?? '—' },
                  { label: 'B 점수', getter: (item: ThemeCompanyItem) => item.buyScore ?? '—' },
                  { label: '매력도', getter: (item: ThemeCompanyItem) => item.appealScore ?? '—' },
                  { label: '과열도', getter: (item: ThemeCompanyItem) => item.crowdingScore ?? '—' },
                  { label: '매출 YoY', getter: (item: ThemeCompanyItem) => formatPct(item.revenueGrowthYoY) },
                  { label: '영업이익률', getter: (item: ThemeCompanyItem) => formatPct(item.operatingMargin) },
                  { label: 'EV/Sales', getter: (item: ThemeCompanyItem) => formatMultiple(item.evToSales) },
                ] as Array<{ label: string; getter: (item: ThemeCompanyItem) => string | number }>).map(({ label, getter }) => (
                  <tr key={label} className="border-t border-white/5">
                    <td className="p-2 text-[var(--muted)]">{label}</td>
                    {selectedItems.map((item) => <td key={item.ticker} className="p-2 text-white">{String(getter(item))}</td>)}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </>
  );
}
