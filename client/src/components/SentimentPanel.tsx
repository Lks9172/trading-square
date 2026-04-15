"use client";

import { InfoTooltip } from "./InfoTooltip";

interface DataPoint {
  code: string;
  value: number;
  date: string;
  source: string;
}

interface DerivedPoint {
  name: string;
  value: number;
  date: string;
  formula: string;
}

interface Props {
  raw: Record<string, DataPoint>;
  derived: Record<string, DerivedPoint>;
}

interface Badge {
  text: string;
  cls: string;
}

// 7차 gap TOP 3 Fix #1: 센티먼트 UI 카드 (AAII/NAAIM/PCR/PSYCH).
// 0 대체 금지 — 결측은 "데이터 없음"으로 명시.

function aaiiLabel(v: number): Badge {
  if (v <= -20) return { text: "극공포 🔴", cls: "bg-red-500/20 text-red-300 border-red-500/40" };
  if (v < 0)    return { text: "약세",       cls: "bg-orange-500/20 text-orange-300 border-orange-500/40" };
  if (v < 20)   return { text: "중립",       cls: "bg-neutral-500/20 text-neutral-300 border-neutral-500/40" };
  return          { text: "탐욕",            cls: "bg-yellow-500/20 text-yellow-300 border-yellow-500/40" };
}

function naaimLabel(v: number): Badge {
  if (v <= 30) return { text: "방어 🔴",     cls: "bg-red-500/20 text-red-300 border-red-500/40" };
  if (v < 70)  return { text: "중립",        cls: "bg-neutral-500/20 text-neutral-300 border-neutral-500/40" };
  return         { text: "공격적 🟢",        cls: "bg-green-500/20 text-green-300 border-green-500/40" };
}

function pcrLabel(v: number): Badge {
  if (v >= 1.2) return { text: "공포 🟢",    cls: "bg-green-500/20 text-green-300 border-green-500/40" };
  if (v > 0.9)  return { text: "중립",       cls: "bg-neutral-500/20 text-neutral-300 border-neutral-500/40" };
  if (v > 0.7)  return { text: "낙관",       cls: "bg-yellow-500/20 text-yellow-300 border-yellow-500/40" };
  return          { text: "탐욕 🔴",         cls: "bg-red-500/20 text-red-300 border-red-500/40" };
}

function psychLabel(v: number): Badge {
  if (v <= 0.2) return { text: "극공포",     cls: "bg-red-500/20 text-red-300 border-red-500/40" };
  if (v <= 0.4) return { text: "공포",       cls: "bg-orange-500/20 text-orange-300 border-orange-500/40" };
  if (v <= 0.6) return { text: "중립",       cls: "bg-neutral-500/20 text-neutral-300 border-neutral-500/40" };
  if (v <= 0.8) return { text: "탐욕",       cls: "bg-yellow-500/20 text-yellow-300 border-yellow-500/40" };
  return          { text: "극탐욕",          cls: "bg-red-500/20 text-red-300 border-red-500/40" };
}

interface CardProps {
  title: string;
  value: number | null;
  formatted: string;
  unit: string;
  badge: Badge | null;
  desc: string;
  source: string;
  formula?: string;
  footer?: string;
  /** 수집 차단 사유 (무료 소스 차단 등). missing 일 때 "데이터 없음" 아래 작게 표시. */
  missingReason?: string;
}

function SentimentCard({ title, value, formatted, unit, badge, desc, source, formula, footer, missingReason }: CardProps) {
  const missing = value === null;
  return (
    <div className="rounded-lg bg-[var(--background)] border border-[var(--card-border)] p-3 flex flex-col gap-1.5">
      <div className="flex items-center text-[10px] sm:text-xs text-[var(--muted)]">
        <span className="truncate">{title}</span>
        <InfoTooltip title={title} description={desc} frequency="주간 (AAII/NAAIM) / 일일 (PCR)" source={source} />
      </div>
      {missing ? (
        <div className="flex flex-col gap-0.5">
          <div className="text-sm text-[var(--muted)] italic">데이터 없음</div>
          {missingReason && (
            <div className="text-[9px] text-yellow-400/80 leading-tight" title={missingReason}>
              ⚠ {missingReason}
            </div>
          )}
        </div>
      ) : (
        <>
          <div className="flex items-baseline gap-1">
            <span className="text-base sm:text-lg font-mono font-bold leading-tight">{formatted}</span>
            {unit && <span className="text-[10px] text-[var(--muted)]">{unit}</span>}
          </div>
          {badge && (
            <span className={`self-start text-[10px] px-1.5 py-0.5 rounded border ${badge.cls}`}>
              {badge.text}
            </span>
          )}
        </>
      )}
      {formula && <div className="text-[9px] text-[var(--muted)] mt-auto">{formula}</div>}
      {footer && <div className="text-[9px] text-[var(--muted)] mt-auto">{footer}</div>}
    </div>
  );
}

export function SentimentPanel({ raw, derived }: Props) {
  const aaiiRaw = raw["AAII_BULL_BEAR_SPREAD"];
  const aaiiDer = derived["AAII_BULL_BEAR_SPREAD"];
  const aaii = aaiiRaw?.value ?? aaiiDer?.value ?? null;

  const naaimRaw = raw["NAAIM_EXPOSURE"];
  const naaimDer = derived["NAAIM_EXPOSURE"];
  const naaim = naaimRaw?.value ?? naaimDer?.value ?? null;

  const pcrRaw = raw["PC_RATIO_10D"];
  const pcrDer = derived["PC_RATIO_10D"];
  const pcr = pcrRaw?.value ?? pcrDer?.value ?? null;

  const psych = derived["PSYCH_SUBSCORE"]?.value ?? null;

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <div className="mb-3">
        <h3 className="text-base sm:text-lg font-semibold">투자자 심리 지표</h3>
        <p className="text-[11px] sm:text-xs text-[var(--muted)]">
          AAII·NAAIM·P/C Ratio·PSYCH 서브스코어. 극단 구간은 역발상 트리거.
        </p>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
        <SentimentCard
          title="AAII Bull-Bear"
          value={aaii}
          formatted={aaii !== null ? aaii.toFixed(1) : "-"}
          unit="pp"
          badge={aaii !== null ? aaiiLabel(aaii) : null}
          desc="AAII 개인투자자 설문 Bullish% - Bearish%. ≤-20 극공포(역발상), ≥20 탐욕."
          source={aaiiRaw?.source || (aaiiDer ? "자체 계산" : "AAII")}
          missingReason="무료 소스 차단됨 (AAII xls 403 · stooq 유료화)"
        />
        <SentimentCard
          title="NAAIM Exposure"
          value={naaim}
          formatted={naaim !== null ? naaim.toFixed(1) : "-"}
          unit=""
          badge={naaim !== null ? naaimLabel(naaim) : null}
          desc="NAAIM 전문 운용자 주식 노출 지수(주간). ≤30 방어, ≥70 공격적."
          source={naaimRaw?.source || (naaimDer ? "자체 계산" : "NAAIM")}
        />
        <SentimentCard
          title="P/C Ratio 10D"
          value={pcr}
          formatted={pcr !== null ? pcr.toFixed(2) : "-"}
          unit=""
          badge={pcr !== null ? pcrLabel(pcr) : null}
          desc="CBOE Put/Call Ratio 10일 이동평균. ≥1.2 공포(역발상), ≤0.7 탐욕."
          source={pcrRaw?.source || (pcrDer ? "자체 계산" : "CBOE")}
          missingReason="무료 소스 차단됨 (CBOE CSV 403 · Yahoo ^CPC 404)"
        />
        <SentimentCard
          title="PSYCH Subscore"
          value={psych}
          formatted={psych !== null ? psych.toFixed(3) : "-"}
          unit=""
          badge={psych !== null ? psychLabel(psych) : null}
          desc="F&G·P/C 10D·AAII·NAAIM 가중평균 (각 0.25). 0=극공포, 1=극탐욕. 결측 컴포넌트는 재정규화."
          source="자체 계산"
          footer="구성: F&G · PCR · AAII · NAAIM"
        />
      </div>
    </div>
  );
}
