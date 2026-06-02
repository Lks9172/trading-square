"use client";

import { InfoTooltip } from "./InfoTooltip";

const SIGNAL_CONFIG: Record<string, { color: string; bg: string; label: string }> = {
  STRONG_BUY: { color: "text-green-400", bg: "bg-green-500/20", label: "적극 매수" },
  BUY:        { color: "text-blue-400",  bg: "bg-blue-500/20",  label: "매수" },
  HOLD:       { color: "text-neutral-400", bg: "bg-neutral-700", label: "관망" },
  REDUCE:     { color: "text-yellow-400", bg: "bg-yellow-500/20", label: "축소" },
  SELL:       { color: "text-red-400",   bg: "bg-red-500/20",   label: "매도" },
};

const ASSET_LABELS: Record<string, string> = {
  NASDAQ: "나스닥 ETF",
  GOLD: "금",
  SILVER: "은",
  COPPER: "구리",
  KOSPI: "코스피",
  CASH: "현금",
  LEVERAGE: "레버리지",
  EMERGING: "신흥국",
};

const SIGNAL_DESCRIPTIONS: Record<string, string> = {
  NASDAQ: "나스닥 ETF 신호. 200DMA 위치, 실업수당, VIX, 이격도, Fear & Greed를 합산해 분할매수/위험 구간 판단",
  KOSPI: "코스피 신호. 200DMA 위치, 환율(1480/1500 기준), 이격도, 유가, VIX를 종합 판단. 환율 1500원 돌파+200DMA 하회 시 매도",
  GOLD: "금 신호. 실질금리 > DXY > 중앙은행 금 매수 > 지정학 순으로 판단",
  SILVER: "은 신호. 금은비와 경기회복(고용/ISM 계열)을 함께 확인",
  COPPER: "구리 신호. 경기 선행 지표로 보고 실업수당, ISM, 구리금비에 산업재(XLI)/에너지(XLE) 섹터 모멘텀을 보조조건으로 결합",
  CASH: "현금 신호. 현재 국면을 반대로 해석해 현금 비중을 확대/축소",
  LEVERAGE: "레버리지 허용 여부. 이격도, VIX, 실업수당 3조건을 모두 만족해야 허용",
  EMERGING: "신흥국 신호. 달러 약세, 글로벌 유동성(M2), 정책 완화 방향을 중심으로 자금 유입 가능성을 판단",
};

interface Signal {
  asset: string;
  signal: string;
  conditionsMet: number;
  conditionsTotal: number;
  weightedScore: number;
  weightedMaxScore: number;
  reasons: string[];
  unmetReasons?: string[];
  explanation?: {
    macroReasons?: string[];
    sectorReasons?: string[];
    assetReasons?: string[];
    flowReasons?: string[];
    timingNotes?: string[];
  };
}

interface Props {
  signals: Signal[];
}

function reasonSection(title: string, items?: string[], color = "text-cyan-300") {
  if (!items || items.length === 0) return null;
  return (
    <div className="mt-2 break-words">
      <p className={`text-[10px] sm:text-xs ${color} mb-1`}>{title}</p>
      {items.slice(0, 2).map((r, i) => (
        <p key={i}>{r}</p>
      ))}
    </div>
  );
}

export function SignalPanel({ signals }: Props) {
  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <div className="flex items-center justify-between mb-3 sm:mb-4 gap-2 flex-wrap">
        <h3 className="text-base sm:text-lg font-semibold">자산별 신호</h3>
        <a
          href="/plan"
          className="text-[10px] sm:text-xs text-cyan-400 hover:text-cyan-300 underline decoration-dotted"
          title="video1 §5부 — 시스템이 있어도 최종 판단은 본인"
        >
          🤔 내 기준은? →
        </a>
      </div>
      <div className="space-y-3">
        {signals.map((sig) => {
          const config = SIGNAL_CONFIG[sig.signal] || SIGNAL_CONFIG.HOLD;
          const ratio = sig.conditionsTotal > 0 ? Math.round((sig.conditionsMet / sig.conditionsTotal) * 100) : 0;
          return (
            <div key={sig.asset} className="flex items-start justify-between gap-3 pb-3 border-b border-[var(--card-border)] last:border-0 last:pb-0">
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1 flex-wrap">
                  <span className="font-medium text-sm sm:text-base">{ASSET_LABELS[sig.asset] || sig.asset}</span>
                  <InfoTooltip
                    title={ASSET_LABELS[sig.asset] || sig.asset}
                    description={SIGNAL_DESCRIPTIONS[sig.asset] || "자산별 규칙 기반 신호"}
                    frequency="5분 캐시 / 요청 시 재계산"
                    source="자체 엔진"
                  />
                  <span className={`px-2 py-0.5 rounded text-xs font-semibold ${config.bg} ${config.color}`}>
                    {config.label}
                  </span>
                </div>
                {sig.conditionsTotal > 0 && (
                  <div className="mb-2">
                    <div className="w-full h-2 rounded-full bg-neutral-800 overflow-hidden">
                      <div
                        className="h-2 rounded-full transition-all duration-500"
                        style={{
                          width: `${ratio}%`,
                          backgroundColor:
                            sig.signal === "STRONG_BUY" ? "#22c55e"
                            : sig.signal === "BUY" ? "#3b82f6"
                            : sig.signal === "REDUCE" ? "#eab308"
                            : sig.signal === "SELL" ? "#ef4444"
                            : "#737373",
                        }}
                      />
                    </div>
                    <div className="flex justify-between text-[9px] sm:text-[10px] text-[var(--muted)] mt-1">
                      <span>0%</span>
                      <span>50%</span>
                      <span>100%</span>
                    </div>
                  </div>
                )}
                <div className="text-xs leading-relaxed text-[var(--muted)] space-y-0.5 break-words">
                  {sig.reasons.length > 0 && <p className="text-[10px] sm:text-xs text-green-400 mb-1">핵심 충족 조건</p>}
                  {sig.reasons.slice(0, 2).map((r, i) => (
                    <p key={i}>{r}</p>
                  ))}
                  {reasonSection("거시 논리", sig.explanation?.macroReasons)}
                  {reasonSection("섹터 논리", sig.explanation?.sectorReasons, "text-blue-300")}
                  {reasonSection("수급/구조 자금", sig.explanation?.flowReasons, "text-emerald-300")}
                  {reasonSection("타이밍 메모", sig.explanation?.timingNotes, "text-yellow-300")}
                  {sig.unmetReasons && sig.unmetReasons.length > 0 && (
                    <>
                      <p className="text-[10px] sm:text-xs text-red-400 mt-2 mb-1">불충족 조건</p>
                      {sig.unmetReasons.slice(0, 2).map((r, i) => (
                        <p key={`u-${i}`}>{r}</p>
                      ))}
                    </>
                  )}
                </div>
              </div>
              {sig.conditionsTotal > 0 && (
                <div className="text-right shrink-0 w-14 sm:w-auto">
                  <div className="text-lg font-mono font-bold">
                    {sig.conditionsMet}/{sig.conditionsTotal}
                  </div>
                  <div className="text-[10px] text-[var(--muted)]">충족</div>
                  <div className="text-[10px] text-[var(--muted)] mt-1">
                    {sig.weightedScore}/{sig.weightedMaxScore}
                  </div>
                  <div className="text-[9px] text-[var(--muted)]">가중치</div>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
