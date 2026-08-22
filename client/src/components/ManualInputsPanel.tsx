"use client";

import { useEffect, useState } from "react";

interface ManualInputs {
  policyDirection: number;
  geoRisk: number;
  cbBuying: boolean;
  ismPmi?: number | null;
}

interface AutomaticInputs extends ManualInputs {
  policyConfidence?: number;
  policySource?: string;
  policyAsOf?: string;
}

interface Props {
  initial: ManualInputs;
  autoInputs?: AutomaticInputs | null;
  inputMode?: 'auto' | 'manual';
  investmentHorizon?: string;
  loading: boolean;
  onApply: (inputs: ManualInputs, horizon?: string) => Promise<void>;
}

const POLICY_LABELS: Record<number, { label: string; color: string }> = {
  [-2]: { label: "강한 긴축", color: "text-red-400" },
  [-1]: { label: "긴축적", color: "text-orange-400" },
  [0]: { label: "중립", color: "text-neutral-400" },
  [1]: { label: "완화적", color: "text-blue-400" },
  [2]: { label: "강한 완화", color: "text-green-400" },
};

const GEO_LABELS: Record<number, { label: string; color: string }> = {
  [0]: { label: "안정", color: "text-green-400" },
  [1]: { label: "낮음", color: "text-blue-400" },
  [2]: { label: "보통", color: "text-yellow-400" },
  [3]: { label: "높음", color: "text-orange-400" },
  [4]: { label: "위기", color: "text-red-400" },
};

const HORIZON_LABELS: Record<string, string> = {
  short: "단기 (1~6개월)",
  medium: "중기 (6개월~2년)",
  long: "장기 (2년 이상)",
};

export function ManualInputsPanel({ initial, autoInputs, inputMode, investmentHorizon, loading, onApply }: Props) {
  const [inputs, setInputs] = useState<ManualInputs>(initial);
  const [horizon, setHorizon] = useState(investmentHorizon || 'medium');

  useEffect(() => {
    setInputs(initial);
  }, [initial]);

  useEffect(() => {
    if (investmentHorizon) setHorizon(investmentHorizon);
  }, [investmentHorizon]);

  const policyMeta = POLICY_LABELS[inputs.policyDirection] || POLICY_LABELS[0];
  const geoMeta = GEO_LABELS[inputs.geoRisk] || GEO_LABELS[2];

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <div className="mb-4">
        <div className="flex items-center justify-between gap-2 mb-1">
          <h3 className="text-base sm:text-lg font-semibold">판단 입력</h3>
          <span className={`px-2 py-0.5 rounded text-[10px] sm:text-xs font-semibold ${inputMode === 'auto' ? 'bg-green-500/20 text-green-400' : 'bg-yellow-500/20 text-yellow-400'}`}>
            {inputMode === 'auto' ? '자동 (GPR + Fed 원문/FRED + 금·DXY)' : '수동 오버라이드'}
          </span>
        </div>
        <p className="text-[11px] sm:text-xs text-[var(--muted)]">
          자동: GPR 지정학 지수, Fed 공식 원문 NLP(근거 부족 시 FRED 추세), 금/달러 역행 감지로 계산.
          슬라이더 변경 후 적용하면 수동 모드로 전환됨.
        </p>
        {autoInputs && inputMode === 'auto' && (
          <div className="mt-2 flex flex-wrap gap-2 text-[10px] sm:text-xs">
            <span className="px-1.5 py-0.5 rounded bg-neutral-700">GPR→지정학: {autoInputs.geoRisk}</span>
            <span className="px-1.5 py-0.5 rounded bg-neutral-700">
              {autoInputs.policySource ? 'Fed 원문 NLP' : 'FRED 추세'}→정책: {autoInputs.policyDirection > 0 ? '+' : ''}{autoInputs.policyDirection}
              {autoInputs.policyConfidence != null ? ` · 근거 ${autoInputs.policyConfidence}%` : ''}
            </span>
            <span className="px-1.5 py-0.5 rounded bg-neutral-700">금/DXY→CB매수: {autoInputs.cbBuying ? '지속' : '둔화'}</span>
          </div>
        )}
      </div>

      <div className="space-y-4">
        <Field
          label="정책 방향"
          valueLabel={`${inputs.policyDirection > 0 ? "+" : ""}${inputs.policyDirection}`}
          description="금리 인하/인상, 감세/증세, 양적완화/긴축 등 종합 정책 방향"
          statusLabel={policyMeta.label}
          statusColor={policyMeta.color}
        >
          <input
            type="range"
            min={-2}
            max={2}
            step={1}
            value={inputs.policyDirection}
            onChange={(e) => setInputs((p) => ({ ...p, policyDirection: Number(e.target.value) }))}
            className="w-full"
          />
          <div className="flex justify-between text-[9px] sm:text-[10px] text-[var(--muted)] mt-1">
            <span>-2 강한긴축</span>
            <span>-1</span>
            <span>0 중립</span>
            <span>+1</span>
            <span>+2 강한완화</span>
          </div>
        </Field>

        <Field
          label="지정학 리스크"
          valueLabel={`${inputs.geoRisk}`}
          description="전쟁·제재·관세·외교 갈등 등 지정학적 불안 수준"
          statusLabel={geoMeta.label}
          statusColor={geoMeta.color}
        >
          <input
            type="range"
            min={0}
            max={4}
            step={1}
            value={inputs.geoRisk}
            onChange={(e) => setInputs((p) => ({ ...p, geoRisk: Number(e.target.value) }))}
            className="w-full"
          />
          <div className="flex justify-between text-[9px] sm:text-[10px] text-[var(--muted)] mt-1">
            <span>0 안정</span>
            <span>1</span>
            <span>2 보통</span>
            <span>3</span>
            <span>4 위기</span>
          </div>
        </Field>

        <div className="rounded-lg border border-[var(--card-border)] bg-[var(--background)] p-3">
          <div className="flex items-center justify-between gap-3">
            <div>
              <span className="text-sm">중앙은행 금 매수 지속</span>
              <p className="text-[10px] sm:text-[11px] text-[var(--muted)] mt-0.5">
                주요 중앙은행(중국/인도/터키 등)이 금을 계속 사들이고 있는지
              </p>
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <span className={`text-xs font-semibold ${inputs.cbBuying ? "text-green-400" : "text-neutral-500"}`}>
                {inputs.cbBuying ? "지속 중" : "중단/둔화"}
              </span>
              <button
                type="button"
                onClick={() => setInputs((p) => ({ ...p, cbBuying: !p.cbBuying }))}
                className={`w-14 h-8 rounded-full transition-colors ${inputs.cbBuying ? "bg-green-600" : "bg-neutral-700"}`}
              >
                <span className={`block h-6 w-6 rounded-full bg-white transition-transform ${inputs.cbBuying ? "translate-x-7" : "translate-x-1"}`} />
              </button>
            </div>
          </div>
        </div>

        <div className="rounded-lg border border-[var(--card-border)] bg-[var(--background)] p-3">
          <div className="flex items-center justify-between mb-2">
            <span className="text-sm">ISM 제조업 PMI</span>
            <span className="text-xs font-mono text-[var(--muted)]">{inputs.ismPmi ?? '자동 (INDPRO 프록시)'}</span>
          </div>
          <p className="text-[10px] sm:text-[11px] text-[var(--muted)] mb-2">
            기본: FRED 산업생산지수(INDPRO) 기반 자동 계산. 공식 ISM 발표값을 직접 입력하면 수동 우선 적용.
          </p>
          <input
            type="number"
            inputMode="decimal"
            placeholder="자동 수집 중 (수동 오버라이드 가능)"
            value={inputs.ismPmi ?? ''}
            onChange={(e) => setInputs((p) => ({ ...p, ismPmi: e.target.value ? parseFloat(e.target.value) : null }))}
            className="w-full rounded-lg border border-[var(--card-border)] bg-[var(--background)] px-3 py-2 text-sm font-mono text-white placeholder:text-neutral-600 focus:border-blue-500 focus:outline-none"
          />
        </div>

        <label className="block rounded-lg border border-[var(--card-border)] bg-[var(--background)] p-3">
          <span className="mb-2 block text-sm">투자 시간 프레임</span>
          <select
            value={horizon}
            onChange={(event) => setHorizon(event.target.value)}
            className="w-full rounded-lg border border-[var(--card-border)] bg-black/20 px-3 py-2 text-sm outline-none focus:border-blue-500"
          >
            {Object.entries(HORIZON_LABELS).map(([value, label]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </label>

        <div className="flex gap-2">
          <button
            type="button"
            disabled={loading}
            onClick={() => onApply(inputs, horizon)}
            className="flex-1 rounded-lg bg-blue-600 hover:bg-blue-500 disabled:bg-blue-900 disabled:text-blue-300 px-3 py-2 text-sm font-semibold transition-colors"
          >
            {loading ? "적용 중..." : "입력 적용"}
          </button>
          <button
            type="button"
            disabled={loading}
            onClick={() => setInputs(initial)}
            className="rounded-lg border border-[var(--card-border)] px-3 py-2 text-sm text-[var(--muted)] hover:text-white transition-colors"
          >
            초기화
          </button>
        </div>
      </div>
    </div>
  );
}

function Field({
  label, valueLabel, description, statusLabel, statusColor, children,
}: {
  label: string; valueLabel: string; description: string;
  statusLabel: string; statusColor: string; children: React.ReactNode;
}) {
  return (
    <div className="rounded-lg border border-[var(--card-border)] bg-[var(--background)] p-3">
      <div className="flex items-center justify-between mb-1">
        <span className="text-sm">{label}</span>
        <div className="flex items-center gap-2">
          <span className={`text-xs font-semibold ${statusColor}`}>{statusLabel}</span>
          <span className="text-xs font-mono text-[var(--muted)]">{valueLabel}</span>
        </div>
      </div>
      <p className="text-[10px] sm:text-[11px] text-[var(--muted)] mb-2">{description}</p>
      {children}
    </div>
  );
}
