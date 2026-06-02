"use client";

import { useEffect, useState, useCallback } from 'react';

type ExecutionAction =
  | 'BUY_NOW'
  | 'SCALE_IN'
  | 'HOLD'
  | 'TAKE_PROFIT'
  | 'EXIT'
  | 'AVOID';

interface ExecutionStage {
  stage: 1 | 2 | 3;
  weightPct: number;
  triggerCondition: string;
  triggerPrice?: number;
  status: 'pending' | 'ready' | 'triggered' | 'filled';
}

interface ExecutionPlan {
  asset: string;
  action: ExecutionAction;
  actionLabel: string;
  currentPrice: number | null;
  targetAllocationPct: number;
  stages: ExecutionStage[];
  stopLoss: { price: number | null; condition: string };
  takeProfit: { price: number | null; condition: string };
  validityDays: number;
  primaryReason: string;
  timing?: {
    macroAligned: boolean;
    sectorAligned: boolean;
    flowConfirmed: boolean;
    chartConfirmed: boolean;
    overheatingRisk: boolean;
    notes: string[];
  };
}

interface Props {
  plans?: ExecutionPlan[];
  currentRegime?: string;
  topdown?: {
    assetRationale?: Array<{
      asset: string;
      macroReasons?: string[];
      sectorReasons?: string[];
      flowReasons?: string[];
      timingNotes?: string[];
    }>;
  };
}

interface TrancheSummary {
  asset: string;
  executedStages: number[];
  nextStage: number | null;
  latestRegime: string | null;
  latestExecutedAt: string | null;
}

// 레짐 "상승" 정의: RISK_ON > NEUTRAL > CAUTION > CORRECTION / PANIC_BUT_OK > RECESSION_RISK.
// level-up = 당시 regime 보다 현재 regime 이 더 긍정적 = 가격 상승 위험이 있는 "추격" 구간.
const REGIME_LEVEL: Record<string, number> = {
  RECESSION_RISK: 0,
  PANIC_BUT_OK: 1,
  CORRECTION: 1,
  CAUTION: 2,
  NEUTRAL: 3,
  RISK_ON: 4,
};

function isLevelUp(from: string | null, to: string | undefined): boolean {
  if (!from || !to) return false;
  const a = REGIME_LEVEL[from];
  const b = REGIME_LEVEL[to];
  if (a === undefined || b === undefined) return false;
  return b > a;
}

const ACTION_STYLE: Record<ExecutionAction, { bg: string; border: string; text: string }> = {
  BUY_NOW:     { bg: 'bg-green-500/10',  border: 'border-green-500/40',  text: 'text-green-300' },
  SCALE_IN:    { bg: 'bg-blue-500/10',   border: 'border-blue-500/40',   text: 'text-blue-300' },
  HOLD:        { bg: 'bg-neutral-500/10',border: 'border-neutral-500/30',text: 'text-neutral-300' },
  TAKE_PROFIT: { bg: 'bg-yellow-500/10', border: 'border-yellow-500/40', text: 'text-yellow-300' },
  EXIT:        { bg: 'bg-red-500/10',    border: 'border-red-500/40',    text: 'text-red-300' },
  AVOID:       { bg: 'bg-zinc-800',      border: 'border-zinc-700',     text: 'text-zinc-400' },
};

const ASSET_LABEL: Record<string, string> = {
  NASDAQ: '나스닥', KOSPI: '코스피', GOLD: '금', SILVER: '은',
  COPPER: '구리', LEVERAGE: '레버리지', EMERGING: '신흥국',
};

interface StageStatusInfo {
  icon: string;
  text: string;   // 라벨 텍스트 (아이콘 옆에 붙여서 직관 확보)
  color: string;  // Tailwind class
}


function timingBadge(active: boolean, positiveLabel: string, negativeLabel: string, tone: 'positive' | 'negative' = 'positive') {
  const positive = active;
  const label = positive ? positiveLabel : negativeLabel;
  const cls = positive
    ? tone === 'negative'
      ? 'border-red-500/40 bg-red-500/15 text-red-200'
      : 'border-emerald-500/40 bg-emerald-500/15 text-emerald-200'
    : 'border-neutral-700 bg-neutral-800/70 text-neutral-400';
  return { label, cls };
}

function stageStatusInfo(s: string): StageStatusInfo {
  if (s === 'filled') {
    return { icon: '✅', text: '집행 완료', color: 'text-violet-300' };
  }
  if (s === 'triggered') {
    // 이미 조건 발동 (가장 강한 실행 상태)
    return { icon: '⚡', text: '발동', color: 'text-blue-400' };
  }
  if (s === 'ready') {
    // 지금 실행 가능
    return { icon: '✔', text: '실행 가능', color: 'text-green-400' };
  }
  // pending
  return { icon: '⏳', text: '대기', color: 'text-neutral-500' };
}

export function ExecutionPlanPanel({ plans, currentRegime, topdown }: Props) {
  const [summaries, setSummaries] = useState<Record<string, TrancheSummary>>({});
  const [pendingAsset, setPendingAsset] = useState<string | null>(null);
  const [confirmReset, setConfirmReset] = useState<string | null>(null);
  const rationaleMap = new Map((topdown?.assetRationale ?? []).map((r) => [r.asset, r]));

  const refresh = useCallback(async () => {
    try {
      const res = await fetch('/api/execution-plan/tranche', { cache: 'no-store' });
      if (!res.ok) return;
      const data = await res.json();
      // 3차 감사 Fix #5: API 계약 defensive 파싱. summary 가 배열이 아니거나 null 이면
      // 조용히 빈 상태로 복원 — crash 방지.
      const map: Record<string, TrancheSummary> = {};
      const arr = Array.isArray(data?.summary) ? (data.summary as TrancheSummary[]) : [];
      for (const s of arr) {
        if (s && typeof s.asset === 'string') map[s.asset] = s;
      }
      setSummaries(map);
    } catch {
      /* 네트워크 실패 시 기존 상태 유지 */
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const executeStage = useCallback(
    async (asset: string, stage: number, priceAtEntry: number | null) => {
      setPendingAsset(`${asset}-${stage}`);
      try {
        await fetch('/api/execution-plan/tranche', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ asset, stage, priceAtEntry }),
        });
        await refresh();
      } catch {
        /* 서버 에러 조용히 무시 — 다음 refresh 때 재동기화 */
      } finally {
        setPendingAsset(null);
      }
    },
    [refresh],
  );

  const resetAsset = useCallback(
    async (asset: string) => {
      setPendingAsset(`${asset}-reset`);
      try {
        await fetch(`/api/execution-plan/tranche/${encodeURIComponent(asset)}`, {
          method: 'DELETE',
        });
        await refresh();
      } catch {
        /* noop */
      } finally {
        setPendingAsset(null);
        setConfirmReset(null);
      }
    },
    [refresh],
  );

  if (!plans || plans.length === 0) return null;

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <h3 className="text-base sm:text-lg font-semibold mb-1">실행 플레이북</h3>
      <p className="text-[11px] sm:text-xs text-[var(--muted)] mb-2">
        자산별 진입 단계·손절·익절·유효기간 (영상 공통 &quot;진단은 지표, 실행은 규칙&quot;)
      </p>

      {/* 범례 */}
      <div className="flex flex-wrap gap-x-3 gap-y-1 mb-4 text-[10px] text-[var(--muted)]">
        <span><span className="text-green-400 mr-1">✔</span>실행 가능</span>
        <span><span className="text-blue-400 mr-1">⚡</span>발동(조건 충족)</span>
        <span><span className="text-neutral-500 mr-1">⏳</span>대기</span>
        <span className="text-red-400">🛑 손절</span>
        <span className="text-yellow-400">🎯 익절</span>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
        {plans.map((p) => {
          const style = ACTION_STYLE[p.action];
          const summary = summaries[p.asset];
          const rationale = rationaleMap.get(p.asset);
          const executedSet = new Set(summary?.executedStages ?? []);
          // 추격 경고: 이전 집행 이후 레짐이 레벨업했는데 다음 트랑셰 미집행
          const chaseWarn =
            !!summary &&
            summary.nextStage !== null &&
            !executedSet.has(summary.nextStage) &&
            isLevelUp(summary.latestRegime, currentRegime);
          return (
            <div
              key={p.asset}
              className={`rounded-lg border ${style.border} ${style.bg} p-3`}
            >
              <div className="flex items-center justify-between mb-2">
                <div>
                  <div className="flex items-center gap-2">
                    <span className="font-semibold">{ASSET_LABEL[p.asset] || p.asset}</span>
                    <span className="text-[10px] text-[var(--muted)] font-mono">
                      목표 {p.targetAllocationPct}%
                    </span>
                    {chaseWarn && (
                      <span
                        className="text-[10px] px-1.5 py-0.5 rounded bg-yellow-500/20 border border-yellow-500/40 text-yellow-300"
                        title={`이전 집행(${summary?.latestRegime}) 이후 레짐이 ${currentRegime} 로 상승 — 추격 가능성`}
                      >
                        ⚠️ 추격 경고
                      </span>
                    )}
                  </div>
                  <div className={`text-sm font-semibold ${style.text} mt-0.5`}>{p.actionLabel}</div>
                </div>
                <div className="text-right">
                  {p.currentPrice !== null && (
                    <div className="text-[10px] text-[var(--muted)]">
                      현재 <span className="font-mono">{p.currentPrice.toLocaleString('en-US')}</span>
                    </div>
                  )}
                  <div className="text-[10px] text-[var(--muted)]">
                    유효 {p.validityDays}일
                  </div>
                  {executedSet.size > 0 && (
                    <button
                      type="button"
                      disabled={pendingAsset === `${p.asset}-reset`}
                      onClick={() => setConfirmReset(p.asset)}
                      className="mt-1 text-[10px] px-1.5 py-0.5 rounded border border-red-500/30 bg-red-500/10 text-red-300 hover:bg-red-500/20 disabled:opacity-40"
                      title="이 자산의 집행 기록 전부 초기화"
                    >
                      {pendingAsset === `${p.asset}-reset` ? '…' : '집행 취소'}
                    </button>
                  )}
                </div>
              </div>

              <div className="text-[10px] leading-relaxed break-words text-[var(--muted)] italic mb-2">{p.primaryReason}</div>

              {(rationale || p.timing) && (
                <div className="mb-2 space-y-2">
                  {rationale && (
                    <div className="grid grid-cols-1 gap-1 text-[10px] leading-relaxed break-words text-[var(--muted)]">
                      {rationale.macroReasons && rationale.macroReasons.length > 0 && (
                        <div><span className="text-cyan-300 font-semibold mr-1">거시</span>{rationale.macroReasons.slice(0, 1).join(' · ')}</div>
                      )}
                      {rationale.sectorReasons && rationale.sectorReasons.length > 0 && (
                        <div><span className="text-blue-300 font-semibold mr-1">섹터</span>{rationale.sectorReasons.slice(0, 1).join(' · ')}</div>
                      )}
                      {rationale.flowReasons && rationale.flowReasons.length > 0 && (
                        <div><span className="text-emerald-300 font-semibold mr-1">수급</span>{rationale.flowReasons.slice(0, 1).join(' · ')}</div>
                      )}
                      {rationale.timingNotes && rationale.timingNotes.length > 0 && (
                        <div><span className="text-yellow-300 font-semibold mr-1">타이밍</span>{rationale.timingNotes.slice(0, 1).join(' · ')}</div>
                      )}
                    </div>
                  )}

                  {p.timing && (() => {
                    const badges = [
                      timingBadge(p.timing.macroAligned, '거시 일치', '거시 재확인'),
                      timingBadge(p.timing.sectorAligned, '섹터 정합', '섹터 약함'),
                      timingBadge(p.timing.flowConfirmed, '수급 확인', '수급 대기'),
                      timingBadge(p.timing.chartConfirmed, '차트 확인', '트리거 대기'),
                      timingBadge(p.timing.overheatingRisk, '과열 주의', '과열 제한적', 'negative'),
                    ];
                    return (
                      <div className="rounded-lg border border-[var(--card-border)] bg-black/15 p-2">
                        <div className="mb-1 text-[10px] font-semibold text-[var(--muted)]">Execution timing</div>
                        <div className="flex flex-wrap gap-1">
                          {badges.map((badge) => (
                            <span key={badge.label} className={`rounded-full border px-2 py-0.5 text-[10px] ${badge.cls}`}>
                              {badge.label}
                            </span>
                          ))}
                        </div>
                        {p.timing.notes.length > 0 && (
                          <div className="mt-1 text-[10px] leading-relaxed break-words text-[var(--muted)]">
                            {p.timing.notes.slice(0, 2).join(' · ')}
                          </div>
                        )}
                      </div>
                    );
                  })()}
                </div>
              )}

              {p.stages.length > 0 && (
                <div className="space-y-1 mb-2">
                  {p.stages.map((s) => {
                    const si = stageStatusInfo(s.status);
                    const executed = executedSet.has(s.stage);
                    const pendingKey = `${p.asset}-${s.stage}`;
                    const isPending = pendingAsset === pendingKey;
                    return (
                      <div key={s.stage} className="flex items-start gap-2 text-[11px] break-words">
                        <input
                          type="checkbox"
                          checked={executed}
                          readOnly
                          aria-label={`${s.stage}차 집행 상태`}
                          title={executed ? '집행됨' : '미집행 — 우측 집행 버튼 사용'}
                          className="mt-0.5 shrink-0 accent-green-500 cursor-default"
                        />
                        <span className={`${si.color} shrink-0`} title={si.text}>
                          {si.icon}
                        </span>
                        <span className={`${si.color} text-[10px] font-semibold shrink-0 w-12`}>
                          {si.text}
                        </span>
                        <span className="text-[var(--muted)] font-mono shrink-0">
                          {s.stage}차 {s.weightPct}%
                        </span>
                        <span className="flex-1 leading-relaxed">{s.triggerCondition}</span>
                        {s.triggerPrice !== undefined && (
                          <span className="font-mono text-[10px] text-neutral-400 shrink-0">
                            @{s.triggerPrice.toLocaleString('en-US')}
                          </span>
                        )}
                        {!executed && (
                          <button
                            type="button"
                            disabled={isPending}
                            onClick={() =>
                              executeStage(p.asset, s.stage, p.currentPrice ?? null)
                            }
                            className="shrink-0 text-[10px] px-1.5 py-0.5 rounded border border-blue-500/40 bg-blue-500/10 text-blue-200 hover:bg-blue-500/20 disabled:opacity-40"
                          >
                            {isPending ? '…' : `${s.stage}차 집행`}
                          </button>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}

              {(p.stopLoss.condition !== '— ' || p.takeProfit.condition !== '— ') && (
                <div className="mt-2 pt-2 border-t border-[var(--card-border)] space-y-1 text-[10px]">
                  {p.stopLoss.condition !== '— ' && (
                    <div className="flex items-start gap-2">
                      <span className="text-red-400 font-semibold shrink-0 w-14">🛑 손절</span>
                      <span className="flex-1 text-[var(--muted)]">{p.stopLoss.condition}</span>
                      {p.stopLoss.price !== null && (
                        <span className="font-mono text-red-300 shrink-0">
                          @{p.stopLoss.price.toLocaleString('en-US')}
                        </span>
                      )}
                    </div>
                  )}
                  {p.takeProfit.condition !== '— ' && (
                    <div className="flex items-start gap-2">
                      <span className="text-yellow-400 font-semibold shrink-0 w-14">🎯 익절</span>
                      <span className="flex-1 text-[var(--muted)]">{p.takeProfit.condition}</span>
                      {p.takeProfit.price !== null && (
                        <span className="font-mono text-yellow-300 shrink-0">
                          @{p.takeProfit.price.toLocaleString('en-US')}
                        </span>
                      )}
                    </div>
                  )}
                </div>
              )}

              {/* 범례: 첫 카드 하단에만 간단한 legend (UI 명확성) */}
            </div>
          );
        })}
      </div>

      {/* 커스텀 확인 모달 (native confirm 대체 — 디자인 통일) */}
      {confirmReset && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 backdrop-blur-sm p-4"
          onClick={() => setConfirmReset(null)}
        >
          <div
            className="max-w-sm w-full rounded-xl border border-[var(--card-border)] bg-[var(--card)] shadow-2xl overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="px-4 py-3 border-b border-[var(--card-border)] flex items-center gap-2">
              <span className="text-red-400">🗑️</span>
              <h4 className="text-sm font-semibold">집행 기록 초기화</h4>
            </div>
            <div className="px-4 py-4 space-y-2 text-sm">
              <p>
                <span className="font-semibold text-white">
                  {ASSET_LABEL[confirmReset] || confirmReset}
                </span>
                <span className="text-[var(--muted)]"> 의 집행 기록을 전부 초기화할까요?</span>
              </p>
              <p className="text-[11px] text-[var(--muted)]">
                되돌릴 수 없습니다. 진입 단계 체크와 추격 경고가 모두 리셋됩니다.
              </p>
            </div>
            <div className="px-4 py-3 border-t border-[var(--card-border)] flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setConfirmReset(null)}
                className="text-xs px-3 py-1.5 rounded-lg border border-[var(--card-border)] bg-transparent text-[var(--muted)] hover:bg-white/5"
              >
                취소
              </button>
              <button
                type="button"
                disabled={pendingAsset === `${confirmReset}-reset`}
                onClick={() => resetAsset(confirmReset)}
                className="text-xs px-3 py-1.5 rounded-lg border border-red-500/40 bg-red-500/15 text-red-200 hover:bg-red-500/25 disabled:opacity-40 font-semibold"
              >
                {pendingAsset === `${confirmReset}-reset` ? '처리 중…' : '초기화'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
