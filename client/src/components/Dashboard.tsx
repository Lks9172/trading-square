"use client";

import { useMemo, useState } from "react";
import { RegimeHeader } from "./RegimeHeader";
import { IndicatorPanel } from "./IndicatorPanel";
import { SignalPanel } from "./SignalPanel";
import { AllocationPanel } from "./AllocationPanel";
import { MetaBar } from "./MetaBar";
import { ManualInputsPanel } from "./ManualInputsPanel";
import { HistoryPanel } from "./HistoryPanel";
import { BacktestPanel } from "./BacktestPanel";
import { SectorPanel } from "./SectorPanel";
import { StalenessPanel } from "./StalenessPanel";
import { RealtimePanel } from "./RealtimePanel";
import { SmartMoneyPanel } from "./SmartMoneyPanel";
import { OptionsVolatilityPanel } from "./OptionsVolatilityPanel";
import { formatKstDateTime } from "@/lib/format";

interface Props {
  snapshot: any;
}

export function Dashboard({ snapshot }: Props) {
  const [currentSnapshot, setCurrentSnapshot] = useState(snapshot);
  const [loading, setLoading] = useState(false);

  const manualInputs = useMemo(
    () => currentSnapshot?.meta?.profile?.manualInputs || { policyDirection: 0, geoRisk: 2, cbBuying: true, ismPmi: null },
    [currentSnapshot]
  );

  async function applyManualInputs(
    inputs: { policyDirection: number; geoRisk: number; cbBuying: boolean; ismPmi?: number | null },
    horizon?: string
  ) {
    setLoading(true);
    try {
      const res = await fetch(`/api/snapshot`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          manualInputs: inputs,
          investmentHorizon: horizon || currentSnapshot?.meta?.profile?.investmentHorizon || 'medium',
        }),
      });
      if (!res.ok) throw new Error("apply failed");
      const nextSnapshot = await res.json();
      setCurrentSnapshot(nextSnapshot);
    } finally {
      setLoading(false);
    }
  }

  if (!currentSnapshot) {
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
          {formatKstDateTime(currentSnapshot.timestamp)}
        </span>
      </header>

      <RegimeHeader regime={currentSnapshot.regime} />

      <RealtimePanel raw={currentSnapshot.raw} derived={currentSnapshot.derived} timestamp={currentSnapshot.timestamp} usPriceSource={currentSnapshot?.meta?.usPriceSource} />

      <MetaBar meta={currentSnapshot.meta} />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 sm:gap-6">
        <SignalPanel signals={currentSnapshot.signals} />
        <AllocationPanel allocation={currentSnapshot.allocation} overheated={currentSnapshot?.derived?.OVERHEATED?.value === 1} />
      </div>

      <SectorPanel derived={currentSnapshot.derived} />

      <OptionsVolatilityPanel raw={currentSnapshot.raw} derived={currentSnapshot.derived} />

      <ManualInputsPanel
        initial={manualInputs}
        autoInputs={currentSnapshot?.meta?.autoInputs}
        inputMode={currentSnapshot?.meta?.inputMode}
        investmentHorizon={currentSnapshot?.meta?.profile?.investmentHorizon}
        loading={loading}
        onApply={applyManualInputs}
      />

      <HistoryPanel />

      <BacktestPanel />

      <SmartMoneyPanel />

      <StalenessPanel staleness={currentSnapshot?.meta?.staleness} />

      <IndicatorPanel raw={currentSnapshot.raw} derived={currentSnapshot.derived} />
    </div>
  );
}
