"use client";

import { useEffect, useMemo, useState } from "react";

type Scenario = {
  key: string;
  label: string;
  annualNominalReturnPct: number;
  annualRealReturnPct: number;
  nominalFutureValueKrw: number;
  realFutureValueKrw: number;
  purchasingPowerRetentionPct: number;
  realGainLossKrw: number;
};

export type PurchasingPowerProjectionView = {
  principalKrw: number;
  years: number;
  inflationPct: number;
  futureCostOfTodayBasketKrw: number;
  cashLike: Scenario;
  productiveAsset: Scenario;
  productiveAssetRealAdvantageKrw: number;
  summary: string;
  methodology: string;
  cautions: string[];
};

type Inputs = {
  principalKrw: string;
  years: string;
  inflationPct: string;
  cashYieldPct: string;
  productiveAssetReturnPct: string;
};

const DEFAULT_INPUTS: Inputs = {
  principalKrw: "100000000",
  years: "30",
  inflationPct: "3",
  cashYieldPct: "2.5",
  productiveAssetReturnPct: "7",
};

function formatKrw(value: number): string {
  if (!Number.isFinite(value)) return "—";
  if (Math.abs(value) >= 100_000_000) return `${(value / 100_000_000).toFixed(1)}억원`;
  if (Math.abs(value) >= 10_000) return `${(value / 10_000).toFixed(0)}만원`;
  return `${Math.round(value).toLocaleString("ko-KR")}원`;
}

function signedKrw(value: number): string {
  return `${value >= 0 ? "+" : ""}${formatKrw(value)}`;
}

function scenarioTone(value: Scenario): string {
  return value.annualRealReturnPct >= 0
    ? "border-emerald-500/20 bg-emerald-500/5"
    : "border-rose-500/20 bg-rose-500/5";
}

export function PurchasingPowerPanel({
  initialProjection,
}: {
  initialProjection: PurchasingPowerProjectionView | null;
}) {
  const [inputs, setInputs] = useState<Inputs>(DEFAULT_INPUTS);
  const [projection, setProjection] = useState<PurchasingPowerProjectionView | null>(initialProjection);
  const [status, setStatus] = useState<"idle" | "loading" | "error">("idle");

  const validQuery = useMemo(() => {
    const values = Object.values(inputs).map(Number);
    if (values.some((value) => !Number.isFinite(value))) return null;
    const principal = Number(inputs.principalKrw);
    const years = Number(inputs.years);
    if (principal < 1 || years < 1 || years > 100) return null;
    return new URLSearchParams(inputs).toString();
  }, [inputs]);

  useEffect(() => {
    if (!validQuery) return;
    const controller = new AbortController();
    const timer = window.setTimeout(async () => {
      setStatus("loading");
      try {
        const response = await fetch(`/api/execution-plan/purchasing-power?${validQuery}`, {
          cache: "no-store",
          signal: controller.signal,
        });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json() as { projection?: PurchasingPowerProjectionView };
        if (!data.projection) throw new Error("projection missing");
        setProjection(data.projection);
        setStatus("idle");
      } catch {
        if (controller.signal.aborted) return;
        setStatus("error");
      }
    }, 350);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [validQuery]);

  const set = (key: keyof Inputs, value: string) => {
    setInputs((current) => ({ ...current, [key]: value }));
  };

  return (
    <section data-testid="purchasing-power-panel" className="mb-6 rounded-2xl border border-violet-500/20 bg-violet-500/5 p-4 sm:p-5">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <div className="text-xs font-semibold uppercase tracking-[0.14em] text-violet-200">Real purchasing power</div>
          <h2 className="mt-1 text-lg font-semibold text-white">현금의 명목 잔액과 실제 구매력은 다릅니다</h2>
          <p className="mt-1 max-w-3xl text-xs leading-relaxed text-white/65">
            물가·예금금리·생산자산의 가정 수익률을 직접 바꿔 장기 복리의 실질 효과를 비교합니다. 이 계산은 현재 매수 신호가 아닙니다.
          </p>
        </div>
        <span className={`rounded-full border px-2.5 py-1 text-[11px] ${
          status === "error"
            ? "border-rose-500/20 bg-rose-500/10 text-rose-100"
            : status === "loading"
              ? "border-amber-500/20 bg-amber-500/10 text-amber-100"
              : "border-violet-500/20 bg-violet-500/10 text-violet-100"
        }`}>
          {status === "error" ? "계산 실패" : status === "loading" ? "재계산 중" : "실질가치 계산"}
        </span>
      </div>

      <div className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-5">
        <Input label="시작 원금(원)" value={inputs.principalKrw} onChange={(value) => set("principalKrw", value)} step="1000000" />
        <Input label="기간(년)" value={inputs.years} onChange={(value) => set("years", value)} step="1" />
        <Input label="연 물가상승률" value={inputs.inflationPct} onChange={(value) => set("inflationPct", value)} suffix="%" step="0.1" />
        <Input label="예금·현금 수익률" value={inputs.cashYieldPct} onChange={(value) => set("cashYieldPct", value)} suffix="%" step="0.1" />
        <Input label="생산자산 가정" value={inputs.productiveAssetReturnPct} onChange={(value) => set("productiveAssetReturnPct", value)} suffix="%" step="0.1" />
      </div>

      {projection ? (
        <>
          <div className="mt-4 rounded-xl border border-white/10 bg-black/15 p-3 text-xs text-white/75">
            지금 {formatKrw(projection.principalKrw)}로 사는 물건 묶음은 {projection.years}년 뒤 명목상
            <strong className="ml-1 text-white">{formatKrw(projection.futureCostOfTodayBasketKrw)}</strong>가 필요합니다.
          </div>
          <div className="mt-3 grid grid-cols-1 gap-3 lg:grid-cols-2">
            {[projection.cashLike, projection.productiveAsset].map((scenario) => (
              <div key={scenario.key} className={`rounded-xl border p-4 ${scenarioTone(scenario)}`}>
                <div className="flex items-center justify-between gap-2">
                  <div className="text-sm font-medium text-white">{scenario.label}</div>
                  <span className="rounded-full border border-white/10 bg-black/15 px-2 py-0.5 text-[10px] text-white/70">
                    명목 연 {scenario.annualNominalReturnPct.toFixed(2)}%
                  </span>
                </div>
                <div className="mt-3 grid grid-cols-2 gap-3 text-xs sm:grid-cols-4">
                  <Metric label="명목 미래가치" value={formatKrw(scenario.nominalFutureValueKrw)} />
                  <Metric label="오늘 돈 실질가치" value={formatKrw(scenario.realFutureValueKrw)} />
                  <Metric label="연 실질수익률" value={`${scenario.annualRealReturnPct >= 0 ? "+" : ""}${scenario.annualRealReturnPct.toFixed(2)}%`} />
                  <Metric label="구매력 유지율" value={`${scenario.purchasingPowerRetentionPct.toFixed(1)}%`} />
                </div>
                <div className={`mt-3 text-xs ${scenario.realGainLossKrw >= 0 ? "text-emerald-100" : "text-rose-100"}`}>
                  시작 원금 대비 실질 구매력 {signedKrw(scenario.realGainLossKrw)}
                </div>
              </div>
            ))}
          </div>
          <div className="mt-3 rounded-xl border border-cyan-500/15 bg-cyan-500/5 p-3 text-xs text-cyan-50/80">
            생산자산 가정의 실질 구매력 우위: <strong>{signedKrw(projection.productiveAssetRealAdvantageKrw)}</strong>
            <span className="ml-2 text-white/55">· 사용자가 입력한 수익률 가정이며 예측값이 아닙니다.</span>
          </div>
          <details className="mt-3 rounded-xl border border-white/10 bg-black/15 p-3 text-[11px] text-white/60">
            <summary className="cursor-pointer text-white/80">산식·주의사항 보기</summary>
            <div className="mt-2 leading-relaxed">{projection.methodology}</div>
            <div className="mt-2 space-y-1 text-amber-100/75">
              {projection.cautions.map((item) => <div key={item}>• {item}</div>)}
            </div>
          </details>
        </>
      ) : (
        <div className="mt-4 rounded-xl border border-white/10 bg-black/15 p-4 text-xs text-white/60">
          입력값을 확인하면 실질 구매력 시나리오가 표시됩니다.
        </div>
      )}
    </section>
  );
}

function Input({
  label,
  value,
  onChange,
  suffix,
  step,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  suffix?: string;
  step: string;
}) {
  return (
    <label className="rounded-xl border border-white/10 bg-black/15 p-3 text-[11px] text-white/60">
      <span>{label}</span>
      <span className="mt-1 flex items-center gap-1">
        <input
          className="min-w-0 flex-1 bg-transparent text-sm font-semibold text-white outline-none"
          type="number"
          inputMode="decimal"
          value={value}
          step={step}
          onChange={(event) => onChange(event.target.value)}
        />
        {suffix ? <span>{suffix}</span> : null}
      </span>
    </label>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-white/50">{label}</div>
      <div className="mt-1 font-semibold text-white">{value}</div>
    </div>
  );
}
