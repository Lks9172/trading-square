"use client";

import dynamic from "next/dynamic";
import { type ComponentProps, useMemo, useState } from "react";
import type { IndicatorPanel as IndicatorPanelComponent } from "./IndicatorPanel";
import type { ManualInputsPanel as ManualInputsPanelComponent } from "./ManualInputsPanel";
import { RegimeHeader } from "./RegimeHeader";
import { SignalPanel } from "./SignalPanel";
import { AllocationPanel } from "./AllocationPanel";
import { MetaBar } from "./MetaBar";
import { SectorPanel } from "./SectorPanel";
import { RealtimePanel } from "./RealtimePanel";
import type { CalendarPanel as CalendarPanelComponent } from "./CalendarPanel";
import { MultiTimeframePanel } from "./MultiTimeframePanel";
import { ExecutionPlanPanel } from "./ExecutionPlanPanel";
import { LensPanel } from "./LensPanel";
import type { OptionsVolatilityPanel as OptionsVolatilityPanelComponent } from "./OptionsVolatilityPanel";
import type { SentimentPanel as SentimentPanelComponent } from "./SentimentPanel";
import { ConvictionPanel } from "./ConvictionPanel";
import { LiquidityImpulsePanel } from "./LiquidityImpulsePanel";
import { Onboarding } from "./Onboarding";
import { ResearchHighlightsPanel } from "./ResearchHighlightsPanel";
import { MarketBreadthGatePanel } from "./MarketBreadthGatePanel";
import { ScoreLegend } from "./ScoreUI";
import { formatKstDateTime } from "@/lib/format";
import type { StalenessPanel as StalenessPanelComponent } from "./StalenessPanel";

const ManualInputsPanel = dynamic(() => import("./ManualInputsPanel").then((mod) => mod.ManualInputsPanel), {
  loading: () => <DeferredPanelSkeleton title="수동 입력 패널 로딩 중…" />,
});
const HistoryPanel = dynamic(() => import("./HistoryPanel").then((mod) => mod.HistoryPanel), {
  loading: () => <DeferredPanelSkeleton title="히스토리 로딩 중…" />,
});
const BacktestPanel = dynamic(() => import("./BacktestPanel").then((mod) => mod.BacktestPanel), {
  loading: () => <DeferredPanelSkeleton title="백테스트 로딩 중…" />,
});
const StalenessPanel = dynamic(() => import("./StalenessPanel").then((mod) => mod.StalenessPanel), {
  loading: () => <DeferredPanelSkeleton title="staleness 로딩 중…" />,
});
const SmartMoneyPanel = dynamic(() => import("./SmartMoneyPanel").then((mod) => mod.SmartMoneyPanel), {
  loading: () => <DeferredPanelSkeleton title="스마트머니 로딩 중…" />,
});
const InstitutionalFlowPanel = dynamic(() => import("./InstitutionalFlowPanel").then((mod) => mod.InstitutionalFlowPanel), {
  loading: () => <DeferredPanelSkeleton title="기관 13F 로딩 중…" />,
});
const PolicyIntelligencePanel = dynamic(() => import("./PolicyIntelligencePanel").then((mod) => mod.PolicyIntelligencePanel), {
  loading: () => <DeferredPanelSkeleton title="정책 원문 분석 로딩 중…" />,
});
const CalendarPanel = dynamic(() => import("./CalendarPanel").then((mod) => mod.CalendarPanel), {
  loading: () => <DeferredPanelSkeleton title="캘린더 로딩 중…" />,
});
const OptionsVolatilityPanel = dynamic(() => import("./OptionsVolatilityPanel").then((mod) => mod.OptionsVolatilityPanel), {
  loading: () => <DeferredPanelSkeleton title="옵션 변동성 로딩 중…" />,
});
const SentimentPanel = dynamic(() => import("./SentimentPanel").then((mod) => mod.SentimentPanel), {
  loading: () => <DeferredPanelSkeleton title="심리 패널 로딩 중…" />,
});
const IndicatorPanel = dynamic(() => import("./IndicatorPanel").then((mod) => mod.IndicatorPanel), {
  loading: () => <DeferredPanelSkeleton title="지표 패널 로딩 중…" />,
});

function DeferredPanelSkeleton({ title }: { title: string }) {
  return (
    <section className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <div className="text-sm text-[var(--muted)]">{title}</div>
    </section>
  );
}

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
type OptionsVolatilityPanelProps = ComponentProps<typeof OptionsVolatilityPanelComponent>;
type SentimentPanelProps = ComponentProps<typeof SentimentPanelComponent>;
type CalendarPanelProps = ComponentProps<typeof CalendarPanelComponent>;
type StalenessPanelProps = ComponentProps<typeof StalenessPanelComponent>;
type IndicatorPanelProps = ComponentProps<typeof IndicatorPanelComponent>;
type ManualInputsPanelProps = ComponentProps<typeof ManualInputsPanelComponent>;

type RawPoint = RealtimePanelProps["raw"][string];
type DerivedPoint = RealtimePanelProps["derived"][string] & {
  interpretation?: string;
};
type CalendarEvent = NonNullable<CalendarPanelProps["events"]>[number];
type ManualInputs = ManualInputsPanelProps["initial"];
type AutoInputs = {
  policyDirection: number;
  policyConfidence?: number;
  policySource?: string;
  policyAsOf?: string;
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
  narrativeSummary?: string[];
  bottleneckSummary?: string[];
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
  executionPlanFreshness?: {
    source: string;
    eligibleForExecution: boolean;
    reason: string;
  };
  topdownFreshness?: {
    source: string;
    eligibleForCurrentRanking: boolean;
    reason: string;
  };
  calendar?: CalendarEvent[];
  calendarMethodology?: CalendarPanelProps["methodology"];
  staleness?: DashboardStaleness;
  inputFreshness?: {
    rawUsable: number;
    rawExcluded: number;
    derivedUsable: number;
    derivedExcluded: number;
    excludedKeys?: string[];
    policy?: string;
  };
  collectionHealth?: StalenessPanelProps["collectionHealth"];
  smartMoney?: DashboardSmartMoney | null;
  marketBreadthGate?: {
    updatedAt: string;
    mode: '실전 개선형';
    summary: string;
    markets: Array<{
      asset: 'NASDAQ' | 'SP500';
      label: string;
      mode: '실전 개선형';
      status: 'ON' | 'RECENT' | 'OFF';
      active: boolean;
      signalDate: string | null;
      daysSinceSignal: number | null;
      perYear: number;
      avg1m: number | null;
      avg2m: number | null;
      avg3m: number | null;
      win1m: number | null;
      win2m: number | null;
      win3m: number | null;
      shortThreshold: number;
      mediumOversoldThreshold: number;
      mediumRecoveryFloor: number;
      lookbackDays: number;
      regimeFilter: string;
      summary: string;
      recentSignals: Array<{
        signalDate: string;
        oneMonthReturn: number | null;
        twoMonthReturn: number | null;
        threeMonthReturn: number | null;
      }>;
    }>;
  } | null;
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
  const decisionInputs = useMemo(() => {
    const raw = Object.fromEntries(Object.entries(currentSnapshot?.raw ?? {})
      .filter(([, point]) => point.eligibleForSignals !== false)) as DashboardSnapshot["raw"];
    const derived = Object.fromEntries(Object.entries(currentSnapshot?.derived ?? {})
      .filter(([, point]) => point.eligibleForSignals !== false)) as DashboardSnapshot["derived"];
    return { raw, derived };
  }, [currentSnapshot]);

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
    overheated: decisionInputs.derived.OVERHEATED?.value === 1,
    fxComboAlert: decisionInputs.derived.FX_FOREIGN_COMBO_ALERT?.value ?? null,
  } satisfies AllocationPanelProps;
  const executionPlanProps = {
    plans: currentSnapshot.meta.executionPlans,
    currentRegime: currentSnapshot.regime.regime,
    topdown: currentSnapshot.meta.topdown,
    freshness: currentSnapshot.meta.executionPlanFreshness,
  } satisfies ExecutionPlanPanelProps;
  const sectorPanelProps = {
    derived: decisionInputs.derived,
    topdown: currentSnapshot.meta.topdown,
    topdownFreshness: currentSnapshot.meta.topdownFreshness,
  } satisfies SectorPanelProps;
  const convictionPanelProps = { derived: currentSnapshot.derived } satisfies ConvictionPanelProps;
  const lensPanelProps = {
    raw: decisionInputs.raw,
    derived: decisionInputs.derived,
    meta: currentSnapshot.meta,
  } satisfies LensPanelProps;
  const multiTimeframeProps = { derived: decisionInputs.derived } satisfies MultiTimeframePanelProps;
  const optionsVolatilityProps = {
    raw: decisionInputs.raw,
    derived: decisionInputs.derived,
  } satisfies OptionsVolatilityPanelProps;
  const sentimentPanelProps = {
    raw: decisionInputs.raw,
    derived: decisionInputs.derived,
  } satisfies SentimentPanelProps;
  const calendarPanelProps = {
    events: currentSnapshot.meta.calendar,
    methodology: currentSnapshot.meta.calendarMethodology,
  } satisfies CalendarPanelProps;
  const stalenessPanelProps = {
    staleness: currentSnapshot.meta.staleness,
    inputFreshness: currentSnapshot.meta.inputFreshness,
    collectionHealth: currentSnapshot.meta.collectionHealth,
  } satisfies StalenessPanelProps;
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

      <LiquidityImpulsePanel derived={currentSnapshot.derived} />

      {/* 19차 P1#1: 18·19차 신규 derived 통합 노출 */}
      <ConvictionPanel {...convictionPanelProps} />

      <LensPanel {...lensPanelProps} />

      <RealtimePanel {...realtimeProps} />

      <MetaBar {...metaBarProps} />

      <PolicyIntelligencePanel />

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 sm:gap-6">
        <SignalPanel {...signalPanelProps} />
        <AllocationPanel {...allocationProps} />
      </div>

      <ExecutionPlanPanel {...executionPlanProps} />

      <SectorPanel {...sectorPanelProps} />

      <section className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
        <div className="mb-3">
          <h3 className="text-base sm:text-lg font-semibold">점수 해석</h3>
          <p className="text-[11px] sm:text-xs text-[var(--muted)] mt-1">B는 지금 사도 되는지, Q는 체력/구조, 과열은 추격 위험을 뜻합니다.</p>
        </div>
        <ScoreLegend defaultOpen />
      </section>

      <MarketBreadthGatePanel gate={currentSnapshot.meta.marketBreadthGate} />

      <ResearchHighlightsPanel />

      <MultiTimeframePanel {...multiTimeframeProps} />

      <OptionsVolatilityPanel {...optionsVolatilityProps} />

      <SentimentPanel {...sentimentPanelProps} />

      <ManualInputsPanel {...manualPanelProps} />

      <HistoryPanel />

      <BacktestPanel />

      <SmartMoneyPanel />

      <InstitutionalFlowPanel />

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
