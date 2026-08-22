"use client";

import { InfoTooltip } from "./InfoTooltip";
import { ScoreBadge } from "./ScoreUI";

const SIGNAL_CONFIG: Record<string, { color: string; bg: string; label: string }> = {
  STRONG_BUY: { color: "text-green-400", bg: "bg-green-500/20", label: "적극 비중확대" },
  BUY:        { color: "text-blue-400",  bg: "bg-blue-500/20",  label: "분할 접근" },
  HOLD:       { color: "text-neutral-300", bg: "bg-neutral-700", label: "대기/유지" },
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
  NASDAQ: "거시 국면·크레딧·200DMA 이격/추세·VIX·순유동성 4주 방향·과열을 결합합니다. 유동성 흡수 또는 전이 스트레스가 강하면 액션 상한을 낮춥니다.",
  KOSPI: "환율 구간·200DMA 이격/추세·글로벌 거시·DXY·반도체 상대강도·크레딧·과열·외국인 수급을 결합합니다. 구조 훼손은 매수보다 회복 확인을 우선합니다.",
  GOLD: "실질금리·DXY·금 200DMA·RSI·거시 국면·금은비·크레딧과 순유동성 확장을 결합합니다. 유동성만으로 매수하지 않고 가격·금리 확인을 함께 요구합니다.",
  SILVER: "금은비·거시 국면·구리/금 추세·DXY·크레딧·미국 M2 프록시를 결합한 상대 우호도입니다. 은 자체 가격/거래량 확인 전에는 적극 매수로 올리지 않습니다.",
  COPPER: "구리/금 추세·산업재 상대강도·거시 국면·M2·DXY·크레딧·WTI를 결합합니다. 구리 자체 가격/거래량 확인 전에는 적극 매수로 올리지 않습니다.",
  CASH: "거시 약화·크레딧·과열·VIX·재정/스태그플레이션·유동성 수축으로 현금 확대 필요성을 판단합니다. SELL은 현금 축소 의미입니다.",
  LEVERAGE: "거시·NASDAQ 추세/이격·과열·크레딧·VIX·RSI·순유동성 방향을 결합합니다. 유동성 흡수 또는 전이 스트레스가 강하면 확대를 하드 게이트로 제한합니다.",
  EMERGING: "DXY·미국 M2 프록시·거시 국면·크레딧·소재 상대강도·USDKRW·구리/금을 결합합니다. 자체 가격/펀드플로가 없어 적극 매수로 올리지 않습니다.",
};

interface Signal {
  asset: string;
  signal: string;
  conditionsMet: number;
  conditionsTotal: number;
  conditionsAvailable?: number;
  weightedScore: number;
  weightedMaxScore: number;
  dataCoveragePct?: number;
  reasons: string[];
  unmetReasons?: string[];
  missingReasons?: string[];
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


function actionLabel(asset: string, signal: string, coverage: number) {
  if (coverage < 70) return '데이터 부족';
  if (asset === 'CASH') {
    if (signal === 'STRONG_BUY') return '현금 대폭 확대';
    if (signal === 'BUY') return '현금 확대';
    if (signal === 'HOLD') return '현금 유지';
    if (signal === 'REDUCE') return '현금 축소';
    return '현금 적극 축소';
  }
  if (asset === 'LEVERAGE') return signal === 'STRONG_BUY' || signal === 'BUY' ? '조건부 허용' : '확대 불허';
  return SIGNAL_CONFIG[signal]?.label || '대기/유지';
}

function buildVerdict(asset: string, signal: string, coverage: number) {
  if (coverage < 70) return '필수 원천이 부족합니다. 점수보다 데이터 갱신을 기다립니다.';
  if (asset === 'CASH') return signal === 'STRONG_BUY' || signal === 'BUY'
    ? '방어 필요가 커 현금 비중 확대를 검토합니다.'
    : signal === 'HOLD' ? '현금 목표 비중을 유지합니다.' : '위험자산 여건이 우호적이어서 현금 축소를 검토합니다.';
  if (signal === 'STRONG_BUY') return '조건점수와 진입 게이트가 함께 우호적입니다. 확률이 아닌 분할 확대 후보입니다.';
  if (signal === 'BUY') return '우호 조건이 우세하지만 제약이 남아 소액·분할 접근이 적절합니다.';
  if (signal === 'HOLD') return '추세·유동성·가격구조 중 확인이 부족해 신규 매수보다 대기가 우선입니다.';
  if (signal === 'REDUCE') return '가격 부담이 커졌습니다. 새 매수보다 비중 축소를 먼저 봅니다.';
  return '리스크가 더 큽니다. 매수보다 회피·정리가 유리합니다.';
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
          const ratio = Math.round((sig.weightedScore / Math.max(sig.weightedMaxScore, 1)) * 100);
          const available = typeof sig.conditionsAvailable === 'number' ? sig.conditionsAvailable : sig.conditionsTotal;
          const coverage = typeof sig.dataCoveragePct === 'number' ? sig.dataCoveragePct : 100;
          const actionGates = (sig.unmetReasons || []).filter((reason) => reason.startsWith('⚠'));
          const regularUnmet = (sig.unmetReasons || []).filter((reason) => !reason.startsWith('⚠'));
          return (
            <div key={sig.asset} className="flex items-start justify-between gap-3 pb-3 border-b border-[var(--card-border)] last:border-0 last:pb-0">
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-1 flex-wrap">
                  <span className="font-medium text-sm sm:text-base">{ASSET_LABELS[sig.asset] || sig.asset}</span>
                  <InfoTooltip
                    title={ASSET_LABELS[sig.asset] || sig.asset}
                    description={SIGNAL_DESCRIPTIONS[sig.asset] || "자산별 규칙 기반 신호"}
                    frequency="화면 캐시 5분 · 원천별 일/주/월 갱신"
                    source="공식·시장 원천 + 규칙 엔진"
                  />
                  <span className={`px-2 py-0.5 rounded text-xs font-semibold ${config.bg} ${config.color}`}>
                    {actionLabel(sig.asset, sig.signal, coverage)}
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
                  <div className="mb-1 flex flex-wrap gap-2 text-[10px]">
                    <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-0.5 text-cyan-100">실행 해석 {actionLabel(sig.asset, sig.signal, coverage)}</span>
                    <ScoreBadge label="조건" value={ratio} title="현재 가용 조건의 가중 충족 점수입니다. 수익률·적중 확률이 아닙니다." kind="buy" />
                    <ScoreBadge label="데이터" value={coverage} title="필수 입력 가중치 중 현재 값이 있는 비율입니다. 70% 미만은 액션을 대기로 제한합니다." kind="quality" />
                  </div>
                  <p className={`mb-2 rounded-md border px-2 py-1 text-[11px] sm:text-xs ${sig.signal === 'STRONG_BUY' ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100' : sig.signal === 'BUY' ? 'border-cyan-500/20 bg-cyan-500/10 text-cyan-100' : sig.signal === 'HOLD' ? 'border-white/10 bg-white/5 text-white/85' : sig.signal === 'REDUCE' ? 'border-amber-500/20 bg-amber-500/10 text-amber-100' : 'border-red-500/20 bg-red-500/10 text-red-100'}`}>한 줄 판단: {buildVerdict(sig.asset, sig.signal, coverage)}</p>
                  {sig.reasons.length > 0 && <p className="text-[10px] sm:text-xs text-green-400 mb-1">핵심 충족 조건</p>}
                  {sig.reasons.slice(0, 2).map((r, i) => (
                    <p key={i}>{r}</p>
                  ))}
                  {reasonSection("거시 논리", sig.explanation?.macroReasons)}
                  {reasonSection("섹터 논리", sig.explanation?.sectorReasons, "text-blue-300")}
                  {reasonSection("수급/구조 자금", sig.explanation?.flowReasons, "text-emerald-300")}
                  {reasonSection("타이밍 메모", sig.explanation?.timingNotes, "text-yellow-300")}
                  {actionGates.length > 0 && (
                    <>
                      <p className="text-[10px] sm:text-xs text-amber-300 mt-2 mb-1">실행 제한</p>
                      {actionGates.slice(0, 3).map((r, i) => (
                        <p key={`g-${i}`} className="text-amber-100">{r}</p>
                      ))}
                    </>
                  )}
                  {regularUnmet.length > 0 && (
                    <>
                      <p className="text-[10px] sm:text-xs text-red-400 mt-2 mb-1">불충족 조건</p>
                      {regularUnmet.slice(0, 2).map((r, i) => (
                        <p key={`u-${i}`}>{r}</p>
                      ))}
                    </>
                  )}
                  {sig.missingReasons && sig.missingReasons.length > 0 && (
                    <>
                      <p className="text-[10px] sm:text-xs text-amber-300 mt-2 mb-1">데이터 누락</p>
                      {sig.missingReasons.slice(0, 2).map((r, i) => <p key={`m-${i}`}>{r}</p>)}
                    </>
                  )}
                </div>
              </div>
              {sig.conditionsTotal > 0 && (
                <div className="text-right shrink-0 w-14 sm:w-auto">
                  <div className="text-lg font-mono font-bold">
                    {sig.conditionsMet}/{available}
                  </div>
                  <div className="text-[10px] text-[var(--muted)]">충족 · 가용 {available}/{sig.conditionsTotal}</div>
                  <div className="mt-1 flex justify-end">
                    <ScoreBadge label="점수" value={ratio} title="자산 신호의 가중 조건 충족률이며 수익 확률이 아닙니다." kind={sig.signal === 'SELL' || sig.signal === 'REDUCE' ? 'crowding' : 'buy'} />
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
