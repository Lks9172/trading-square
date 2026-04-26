"use client";

import { formatKstDateTime } from "@/lib/format";

interface Props {
  meta?: {
    fetchedAt: string;
    nextRefreshAt: string;
    sourceFrequencies: Record<string, string>;
    latestDates: Record<string, string>;
    historyGuarantee: Record<string, string>;
  };
}

export function MetaBar({ meta }: Props) {
  if (!meta) return null;

  const sourceCards = [
    { key: "FRED", label: "FRED" },
    { key: "YAHOO", label: "Yahoo" },
    { key: "CNN", label: "CNN" },
    { key: "DERIVED", label: "파생" },
  ];

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-3 sm:p-4">
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-2 mb-3">
        <div>
          <h3 className="text-sm sm:text-base font-semibold">데이터 갱신 상태</h3>
          <p className="text-[11px] sm:text-xs text-[var(--muted)]">
            서버는 5분 캐시 + 5분 cron 갱신으로 동작
          </p>
        </div>
        <div className="text-[11px] sm:text-xs text-[var(--muted)] text-left md:text-right">
          <div>마지막 스냅샷: {formatKstDateTime(meta.fetchedAt)}</div>
          <div>다음 예정 갱신: {formatKstDateTime(meta.nextRefreshAt)}</div>
        </div>
      </div>

      <div className="grid grid-cols-2 xl:grid-cols-4 gap-2">
        {sourceCards.map(({ key, label }) => (
          <div key={key} className="rounded-lg border border-[var(--card-border)] bg-[var(--background)] p-2.5 sm:p-3">
            <div className="text-xs font-semibold mb-1">{label}</div>
            <div className="text-[10px] sm:text-xs text-[var(--muted)] mb-1 break-words">
              {meta.sourceFrequencies[key] || "-"}
            </div>
            <div className="text-[10px] sm:text-xs text-blue-400 mb-1 break-words">
              보장: {meta.historyGuarantee[key] || "-"}
            </div>
            <div className="text-[10px] sm:text-xs font-mono">
              latest: {meta.latestDates[key] || "-"}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
