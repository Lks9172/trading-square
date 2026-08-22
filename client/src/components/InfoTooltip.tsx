"use client";

import { useState } from "react";

interface Props {
  title: string;
  description: string;
  frequency?: string;
  source?: string;
}

export function InfoTooltip({ title, description, frequency, source }: Props) {
  const [open, setOpen] = useState(false);

  return (
    <span className="relative inline-flex">
      <button
        type="button"
        onClick={(e) => { e.preventDefault(); e.stopPropagation(); setOpen(!open); }}
        className="ml-1 inline-flex h-4 w-4 touch-manipulation items-center justify-center rounded-full bg-neutral-700 text-[9px] font-bold text-neutral-400 transition-colors hover:bg-neutral-600 hover:text-neutral-200 sm:h-[18px] sm:w-[18px] sm:text-[10px] shrink-0"
        aria-label={`${title} 설명`}
      >
        i
      </button>
      {open && (
        <>
          <div className="fixed inset-0 z-40" onClick={(e) => { e.preventDefault(); e.stopPropagation(); setOpen(false); }} />
          <div className="absolute z-50 left-0 top-6 w-56 sm:w-64 rounded-lg border border-[var(--card-border)] bg-[#1a1a1a] p-3 shadow-xl text-xs" onClick={(e) => e.stopPropagation()}>
            <div className="font-semibold text-sm mb-1.5">{title}</div>
            <p className="text-[var(--muted)] leading-relaxed mb-2">{description}</p>
            <div className="flex flex-wrap gap-1.5">
              {frequency && (
                <span className="px-1.5 py-0.5 rounded bg-blue-500/20 text-blue-400 text-[10px]">
                  {frequency}
                </span>
              )}
              {source && (
                <span className="px-1.5 py-0.5 rounded bg-neutral-700 text-neutral-300 text-[10px]">
                  {source}
                </span>
              )}
            </div>
          </div>
        </>
      )}
    </span>
  );
}
