"use client";

import { type ComponentProps, useMemo, useState } from "react";
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
import { CalendarPanel } from "./CalendarPanel";
import { MultiTimeframePanel } from "./MultiTimeframePanel";
import { ExecutionPlanPanel } from "./ExecutionPlanPanel";
import { LensPanel } from "./LensPanel";
import { OptionsVolatilityPanel } from "./OptionsVolatilityPanel";
import { SentimentPanel } from "./SentimentPanel";
import { ConvictionPanel } from "./ConvictionPanel";
import { Onboarding } from "./Onboarding";
import { formatKstDateTime } from "@/lib/format";

type RegimeHeaderProps = ComponentProps<typeof RegimeHeader>;
type RealtimePanelProps = ComponentProps<typeof RealtimePanel>;
type MetaBarProps = ComponentProps<typeof MetaBar>;
type SignalPanelProps = ComponentProps<typeof SignalPanel>;
type AllocationPanelProps = ComponentProps<typeof AllocationPanel>;
type ExecutionPlanPanelProps = ComponentProps<typeof ExecutionPlanPanel>;
type SectorPanelProps = ComponentProps<typeof SectorPanel>;
type ConvictionPanelProps = ComponentProps<typeof ConvictionPanel>;
type LensPanelProps = ComponentProps<typeof LensPanel>;
type MultiTimeframePanelProps = ComponentProps<typeof MultiTimeframePanel>;
type OptionsVolatilityPanelProps = ComponentProps<typeof OptionsVolatilityPanel>;
type SentimentPanelProps = ComponentProps<typeof SentimentPanel>;
type CalendarPanelProps = ComponentProps<typeof CalendarPanel>;
type StalenessPanelProps = ComponentProps<typeof StalenessPanel>;
type IndicatorPanelProps = ComponentProps<typeof IndicatorPanel>;
type ManualInputsPanelProps = ComponentProps<typeof ManualInputsPanel>;

type RawPoint = RealtimePanelProps["raw"][string];
type DerivedPoint = RealtimePanelProps["derived"][string] & {
  interpretation?: string;
};
type CalendarEvent = NonNullable<CalendarPanelProps["events"]>[number];
type ManualInputs = ManualInputsPanelProps["initial"];
type AutoInputs = {
  policyDirection: number;
  geoRisk: number;
  cbBuying: boolean;
  ismPmi: number | null;
};
type DashboardRegime = RegimeHeaderProps["regime"];
type DashboardSignals = SignalPanelProps["signals"];
type DashboardAllocation = AllocationPanelProps["allocation"];
type DashboardExecutionPlans = ExecutionPlanPanelProps["plans"];
type DashboardSmartMoney = NonNullable<NonNullable<LensPanelProps["meta"]>["smartMoney"]>;
type DashboardStaleness = StalenessPanelProps["staleness"];
type DashboardMetaBar = NonNullable<MetaBarProps["meta"]>;
interface DashboardTopdown {
  summary?: string;
  favoredSectors?: SectorPanelProps["topdown"] extends infer T
    ? T extends { favoredSectors?: infer U }
      ? U
      : never
    : never;
  avoidedSectors?: SectorPanelProps["topdown"] extends infer T
    ? T extends { avoidedSectors?: infer U }
      ? U
      : never
    : never;
  assetRationale?: ExecutionPlanPanelProps["topdown"] extends infer T
    ? T extends { assetRationale?: infer U }
      ? U
      : never
    : never;
}

interface DashboardMeta extends DashboardMetaBar {
  usPriceSource?: RealtimePanelProps["usPriceSource"];
  topdown?: DashboardTopdown;
  profile: {
    investmentHorizon?: string;
    manualInputs?: AutoInputs;
  };
  autoInputs: AutoInputs | null;
  inputMode: ManualInputsPanelProps["inputMode"];
  executionPlans?: DashboardExecutionPlans;
  calendar?: CalendarEvent[];
  staleness?: DashboardStaleness;
  smartMoney?: DashboardSmartMoney | null;
}

interface DashboardSnapshot {
  timestamp: string;
  raw: Record<string, RawPoint>;
  derived: Record<string, DerivedPoint>;
  regime: DashboardRegime;
  signals: DashboardSignals;
  allocation: DashboardAllocation;
  meta: DashboardMeta;
}

interface Props {
  snapshot: DashboardSnapshot | null;
}

export function Dashboard({ snapshot }: Props) {
  const [currentSnapshot, setCurrentSnapshot] = useState(snapshot);
  const [loading, setLoading] = useState(false);

  const manualInputs = useMemo<ManualInputsPanelProps["initial"]>(
    () => ({
      policyDirection: currentSnapshot?.meta.profile.manualInputs?.policyDirection ?? 0,
      geoRisk: currentSnapshot?.meta.profile.manualInputs?.geoRisk ?? 2,
      cbBuying: currentSnapshot?.meta.profile.manualInputs?.cbBuying ?? true,
      ismPmi: currentSnapshot?.meta.profile.manualInputs?.ismPmi ?? null,
    }),
    [currentSnapshot]
  );

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

  const regimeHeaderProps: RegimeHeaderProps = {
    regime: currentSnapshot.regime,
    derived: currentSnapshot.derived,
  };
  const realtimeProps: RealtimePanelProps = {
    raw: currentSnapshot.raw,
    derived: currentSnapshot.derived,
    timestamp: currentSnapshot.timestamp,
    usPriceSource: currentSnapshot.meta.usPriceSource,
  };
  const metaBarProps: MetaBarProps = { meta: currentSnapshot.meta };
  const signalPanelProps = {
    signals: currentSnapshot.signals,
  } satisfies SignalPanelProps;
  const allocationProps = {
    allocation: currentSnapshot.allocation,
    overheated: currentSnapshot.derived.OVERHEATED?.value === 1,
    fxComboAlert: currentSnapshot.derived.FX_FOREIGN_COMBO_ALERT?.value ?? null,
  } satisfies AllocationPanelProps;
  const executionPlanProps = {
    plans: currentSnapshot.meta.executionPlans,
    currentRegime: currentSnapshot.regime.regime,
    topdown: currentSnapshot.meta.topdown,
  } satisfies ExecutionPlanPanelProps;
  const sectorPanelProps = {
    derived: currentSnapshot.derived,
    topdown: currentSnapshot.meta.topdown,
  } satisfies SectorPanelProps;
  const convictionPanelProps = { derived: currentSnapshot.derived } satisfies ConvictionPanelProps;
  const lensPanelProps = {
    raw: currentSnapshot.raw,
    derived: currentSnapshot.derived,
    meta: currentSnapshot.meta,
  } satisfies LensPanelProps;
  const multiTimeframeProps = { derived: currentSnapshot.derived } satisfies MultiTimeframePanelProps;
  const optionsVolatilityProps = {
    raw: currentSnapshot.raw,
    derived: currentSnapshot.derived,
  } satisfies OptionsVolatilityPanelProps;
  const sentimentPanelProps = {
    raw: currentSnapshot.raw,
    derived: currentSnapshot.derived,
  } satisfies SentimentPanelProps;
  const calendarPanelProps = { events: currentSnapshot.meta.calendar } satisfies CalendarPanelProps;
  const stalenessPanelProps = { staleness: currentSnapshot.meta.staleness } satisfies StalenessPanelProps;
  const indicatorPanelProps = {
    raw: currentSnapshot.raw,
    derived: currentSnapshot.derived,
  } satisfies IndicatorPanelProps;

  const manualPanelProps = {
    initial: manualInputs,
    autoInputs: currentSnapshot.meta.autoInputs,
    inputMode: currentSnapshot.meta.inputMode,
    investmentHorizon: currentSnapshot.meta.profile.investmentHorizon,
    loading,
    onApply: applyManualInputs,
  } satisfies ManualInputsPanelProps;

  async function applyManualInputs(
    inputs: ManualInputs,
    horizon?: string
  ) {
    const effectiveHorizon = horizon || currentSnapshot?.meta.profile.investmentHorizon || "medium";
    setLoading(true);
    try {
      const res = await fetch(`/api/snapshot`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          manualInputs: inputs,
          investmentHorizon: effectiveHorizon,
        }),
      });
      if (!res.ok) throw new Error("apply failed");
      const nextSnapshot = await res.json();
      setCurrentSnapshot(nextSnapshot);
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="space-y-4 sm:space-y-6">
      <header className="flex items-center justify-between gap-2">
        <h1 className="text-xl sm:text-2xl font-bold tracking-tight">MacroSquare</h1>
        <span className="text-xs sm:text-sm text-[var(--muted)] shrink-0">
          {formatKstDateTime(currentSnapshot.timestamp ?? new Date().toISOString())}
        </span>
      </header>

      <RegimeHeader {...regimeHeaderProps} />

      {/* 19차 P1#1: 18·19차 신규 derived 통합 노출 */}
      <ConvictionPanel {...convictionPanelProps} />

      <LensPanel {...lensPanelProps} />

      <RealtimePanel {...realtimeProps} />

      <MetaBar {...metaBarProps} />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 sm:gap-6">
        <SignalPanel {...signalPanelProps} />
        <AllocationPanel {...allocationProps} />
      </div>

      <ExecutionPlanPanel {...executionPlanProps} />

      <SectorPanel {...sectorPanelProps} />

      <MultiTimeframePanel {...multiTimeframeProps} />

      <OptionsVolatilityPanel {...optionsVolatilityProps} />

      <SentimentPanel {...sentimentPanelProps} />

      <ManualInputsPanel {...manualPanelProps} />

      <HistoryPanel />

      <BacktestPanel />

      <SmartMoneyPanel />

      <CalendarPanel {...calendarPanelProps} />

      <StalenessPanel {...stalenessPanelProps} />

      <IndicatorPanel {...indicatorPanelProps} />

      {/* 22차 P2#25: footer 영구 메시지 (노션 §전하는 말) */}
      <footer className="text-center text-xs text-slate-500 italic py-4 mt-6 border-t border-slate-800">
        — 우린 함께 웃자 (자산제곱)
      </footer>

      {/* 22차 P1#5: 첫 방문 onboarding 모달 + 우상단 ? 버튼 */}
      <Onboarding />
    </div>
  );
}
