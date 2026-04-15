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
  status: 'pending' | 'ready' | 'triggered';
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
}

interface Props {
  plans?: ExecutionPlan[];
  currentRegime?: string;
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

function stageStatusInfo(s: string): StageStatusInfo {
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

export function ExecutionPlanPanel({ plans, currentRegime }: Props) {
  const [summaries, setSummaries] = useState<Record<string, TrancheSummary>>({});
  const [pendingAsset, setPendingAsset] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      const res = await fetch('/api/execution-plan/tranche', { cache: 'no-store' });
      if (!res.ok) return;
      const data = await res.json();
      const map: Record<string, TrancheSummary> = {};
      for (const s of data.summary as TrancheSummary[]) map[s.asset] = s;
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
      if (!confirm(`${ASSET_LABEL[asset] || asset} 의 집행 기록을 전부 초기화할까요?`)) return;
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
      }
    },
    [refresh],
  );

  if (!plans || plans.length === 0) return null;

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <h3 className="text-base sm:text-lg font-semibold mb-1">실행 플레이북</h3>
      <p className="text-[11px] sm:text-xs text-[var(--muted)] mb-2">
        자산별 진입 단계·손절·익절·유효기간 (영상 공통 "진단은 지표, 실행은 규칙")
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
                      onClick={() => resetAsset(p.asset)}
                      className="mt-1 text-[10px] px-1.5 py-0.5 rounded border border-red-500/30 bg-red-500/10 text-red-300 hover:bg-red-500/20 disabled:opacity-40"
                      title="이 자산의 집행 기록 전부 초기화"
                    >
                      {pendingAsset === `${p.asset}-reset` ? '…' : '집행 취소'}
                    </button>
                  )}
                </div>
              </div>

              <div className="text-[10px] text-[var(--muted)] italic mb-2">{p.primaryReason}</div>

              {p.stages.length > 0 && (
                <div className="space-y-1 mb-2">
                  {p.stages.map((s) => {
                    const si = stageStatusInfo(s.status);
                    const executed = executedSet.has(s.stage);
                    const pendingKey = `${p.asset}-${s.stage}`;
                    const isPending = pendingAsset === pendingKey;
                    return (
                      <div key={s.stage} className="flex items-start gap-2 text-[11px]">
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
                        <span className="flex-1">{s.triggerCondition}</span>
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
    </div>
  );
}
