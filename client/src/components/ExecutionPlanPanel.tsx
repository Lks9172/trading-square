"use client";

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
  COPPER: '구리', LEVERAGE: '레버리지',
};

function stageStatusColor(s: string): string {
  if (s === 'ready') return 'text-green-400';
  if (s === 'triggered') return 'text-blue-400';
  return 'text-neutral-500';
}

function stageStatusIcon(s: string): string {
  if (s === 'ready') return '●';
  if (s === 'triggered') return '✓';
  return '○';
}

export function ExecutionPlanPanel({ plans }: Props) {
  if (!plans || plans.length === 0) return null;

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <h3 className="text-base sm:text-lg font-semibold mb-1">실행 플레이북</h3>
      <p className="text-[11px] sm:text-xs text-[var(--muted)] mb-4">
        자산별 진입 단계·손절·익절·유효기간 (영상 공통 "진단은 지표, 실행은 규칙")
      </p>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
        {plans.map((p) => {
          const style = ACTION_STYLE[p.action];
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
                </div>
              </div>

              <div className="text-[10px] text-[var(--muted)] italic mb-2">{p.primaryReason}</div>

              {p.stages.length > 0 && (
                <div className="space-y-1 mb-2">
                  {p.stages.map((s) => (
                    <div key={s.stage} className="flex items-start gap-2 text-[11px]">
                      <span className={`font-mono ${stageStatusColor(s.status)}`}>
                        {stageStatusIcon(s.status)}
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
                    </div>
                  ))}
                </div>
              )}

              {(p.stopLoss.condition !== '— ' || p.takeProfit.condition !== '— ') && (
                <div className="mt-2 pt-2 border-t border-[var(--card-border)] space-y-1 text-[10px]">
                  {p.stopLoss.condition !== '— ' && (
                    <div className="flex items-start gap-2">
                      <span className="text-red-400 font-mono shrink-0">✗ 손절</span>
                      <span className="flex-1 text-[var(--muted)]">{p.stopLoss.condition}</span>
                      {p.stopLoss.price !== null && (
                        <span className="font-mono text-red-300">
                          @{p.stopLoss.price.toLocaleString('en-US')}
                        </span>
                      )}
                    </div>
                  )}
                  {p.takeProfit.condition !== '— ' && (
                    <div className="flex items-start gap-2">
                      <span className="text-yellow-400 font-mono shrink-0">✓ 익절</span>
                      <span className="flex-1 text-[var(--muted)]">{p.takeProfit.condition}</span>
                      {p.takeProfit.price !== null && (
                        <span className="font-mono text-yellow-300">
                          @{p.takeProfit.price.toLocaleString('en-US')}
                        </span>
                      )}
                    </div>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
