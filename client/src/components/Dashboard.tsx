"use client";

import { RegimeHeader } from "./RegimeHeader";
import { IndicatorPanel } from "./IndicatorPanel";
import { SignalPanel } from "./SignalPanel";
import { AllocationPanel } from "./AllocationPanel";

interface Props {
  snapshot: any;
}

export function Dashboard({ snapshot }: Props) {
  if (!snapshot) {
    return (
      <div className="flex items-center justify-center h-[60vh]">
        <div className="text-center">
          <h2 className="text-lg sm:text-xl font-semibold mb-2">데이터를 불러올 수 없습니다</h2>
          <p className="text-sm text-[var(--muted)]">서버 연결을 확인하세요</p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4 sm:space-y-6">
      <header className="flex items-center justify-between gap-2">
        <h1 className="text-xl sm:text-2xl font-bold tracking-tight">MacroSquare</h1>
        <span className="text-xs sm:text-sm text-[var(--muted)] shrink-0">
          {new Date(snapshot.timestamp).toLocaleString("ko-KR")}
        </span>
      </header>

      <RegimeHeader regime={snapshot.regime} />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 sm:gap-6">
        <SignalPanel signals={snapshot.signals} />
        <AllocationPanel allocation={snapshot.allocation} />
      </div>

      <IndicatorPanel raw={snapshot.raw} derived={snapshot.derived} />
    </div>
  );
}
