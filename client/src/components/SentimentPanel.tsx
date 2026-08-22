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

function pcrLabel(v: number, percentile: number | null): Badge {
  if (percentile !== null) {
    if (percentile >= 90) return { text: "풋 우위 극단", cls: "bg-green-500/20 text-green-300 border-green-500/40" };
    if (percentile <= 10) return { text: "콜 우위 극단", cls: "bg-red-500/20 text-red-300 border-red-500/40" };
    return { text: "역사 범위 중립", cls: "bg-neutral-500/20 text-neutral-300 border-neutral-500/40" };
  }
  if (v >= 1.2) return { text: "풋 우위", cls: "bg-green-500/20 text-green-300 border-green-500/40" };
  if (v <= 0.7) return { text: "콜 우위", cls: "bg-red-500/20 text-red-300 border-red-500/40" };
  return { text: "중립", cls: "bg-neutral-500/20 text-neutral-300 border-neutral-500/40" };
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

  const pcrRaw = raw["PC_RATIO"];
  const pcrDer = derived["PC_RATIO_10D"];
  const pcr = pcrDer?.value ?? pcrRaw?.value ?? null;
  const pcrIsTenDay = pcrDer?.value !== undefined;
  const pcrPercentile = derived["PC_RATIO_10D_PERCENTILE"]?.value ?? null;
  const pcrHistoryCount = derived["PC_RATIO_HISTORY_COUNT"]?.value ?? null;

  const psych = derived["PSYCH_SUBSCORE"]?.value ?? null;
  const psychCoverage = derived["PSYCH_SUBSCORE_COVERAGE"]?.value ?? null;

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <div className="mb-3">
        <h3 className="text-base sm:text-lg font-semibold">투자자 심리 지표</h3>
        <p className="text-[11px] sm:text-xs text-[var(--muted)]">
          AAII·NAAIM·P/C Ratio·PSYCH 조건 합치도. 극단 구간은 보조 경고이며 단독 매수 트리거가 아닙니다.
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
          missingReason="AAII 공개 설문 feed 수집 실패 또는 발표 주기상 최신 관측 없음"
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
          title={pcrIsTenDay ? "SPX·SPY·QQQ P/C 10D" : "SPX·SPY·QQQ P/C 1D"}
          value={pcr}
          formatted={pcr !== null ? pcr.toFixed(2) : "-"}
          unit=""
          badge={pcr !== null ? pcrLabel(pcr, pcrPercentile) : null}
          desc={pcrIsTenDay
            ? "CBOE 옵션 체인의 SPX·SPY·QQQ 일별 풋/콜 거래량 비율 최근 10개 관측 평균. 높은 값은 풋 우위지만 반등을 확정하지 않습니다."
            : "CBOE 옵션 체인의 SPX·SPY·QQQ 당일 풋/콜 거래량 비율. 10개 관측이 쌓이기 전에는 10D로 표시하지 않습니다."}
          source={pcrIsTenDay ? "CBOE 체인 기반 자체 10D" : (pcrRaw?.source || "CBOE 옵션 체인")}
          footer={`${pcrPercentile !== null ? `최대 252개 10D 관측 중 ${pcrPercentile.toFixed(0)}백분위 · ` : ''}일별 이력 ${pcrHistoryCount?.toFixed(0) ?? 0}개`}
          missingReason="CBOE SPX·SPY·QQQ 옵션 체인 수집 실패 또는 최신 관측 없음"
        />
        <SentimentCard
          title="PSYCH Subscore"
          value={psych}
          formatted={psych !== null ? psych.toFixed(3) : "-"}
          unit=""
          badge={psych !== null ? psychLabel(psych) : null}
          desc="F&G·P/C 10D·AAII·NAAIM 중 최소 2개가 있을 때만 계산. 0=공포, 1=탐욕의 조건 합치도이며 수익 확률이 아닙니다."
          source="자체 계산"
          footer={`구성: F&G · PCR · AAII · NAAIM · 커버리지 ${psychCoverage?.toFixed(0) ?? 0}%`}
        />
      </div>
      <div className="mt-3 rounded-lg border border-slate-700/70 bg-slate-950/30 px-3 py-2 text-[10px] leading-4 text-[var(--muted)]">
        종목별 공매도 잔고는 신뢰할 직접 소스가 아직 연결되지 않아 점수에 넣지 않습니다. 13F PUT/CALL이나 일일 short volume을
        공매도 잔고로 대체하지 않으며, 높은 공매도는 단독 하락 신호가 아니라 촉매 발생 시 숏커버 변동성을 키울 수 있는 조건입니다.
      </div>
    </div>
  );
}
