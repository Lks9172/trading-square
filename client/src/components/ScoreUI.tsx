import { ReactNode } from 'react';

export function scoreTone(value: number | null | undefined, kind: 'buy' | 'quality' | 'crowding' | 'appeal' | 'total') {
  if (typeof value !== 'number') return 'border-white/10 bg-white/5 text-white/70';
  if (kind === 'crowding') {
    if (value >= 70) return 'border-red-500/30 bg-red-500/10 text-red-200';
    if (value >= 55) return 'border-amber-500/30 bg-amber-500/10 text-amber-200';
    return 'border-emerald-500/30 bg-emerald-500/10 text-emerald-200';
  }
  if (value >= 70) return 'border-emerald-500/30 bg-emerald-500/10 text-emerald-200';
  if (value >= 55) return 'border-cyan-500/30 bg-cyan-500/10 text-cyan-200';
  if (value >= 40) return 'border-amber-500/30 bg-amber-500/10 text-amber-200';
  return 'border-red-500/30 bg-red-500/10 text-red-200';
}

export function buyActionLabel(value: number | null | undefined): 'STRONG BUY' | 'BUY' | 'HOLD' | 'REDUCE' | 'SELL' | 'N/A' {
  if (typeof value !== 'number') return 'N/A';
  if (value >= 80) return 'STRONG BUY';
  if (value >= 70) return 'BUY';
  if (value >= 55) return 'HOLD';
  if (value >= 40) return 'REDUCE';
  return 'SELL';
}

export function buyActionKorean(label: ReturnType<typeof buyActionLabel>): string {
  switch (label) {
    case 'STRONG BUY':
      return '적극 매수';
    case 'BUY':
      return '매수 가능';
    case 'HOLD':
      return '보유/관찰';
    case 'REDUCE':
      return '축소';
    case 'SELL':
      return '매도/회피';
    default:
      return '해석 불가';
  }
}

export function buyActionHelp(value: number | null | undefined): string {
  const label = buyActionLabel(value);
  switch (label) {
    case 'STRONG BUY':
      return '복합 조건점수 80 이상입니다. 위험·데이터 게이트 확인 후 분할 접근하며 수익 확률은 아닙니다.';
    case 'BUY':
      return '복합 조건점수 70~79입니다. 우호 조건이 많지만 분할 진입과 훼손 조건 확인이 필요합니다.';
    case 'HOLD':
      return '보유/관찰: 지금은 공격 매수보다 지켜볼 구간입니다.';
    case 'REDUCE':
      return '축소: 가격 부담이 크거나 매력이 약한 구간입니다.';
    case 'SELL':
      return '매도/회피: 매력보다 위험이 큰 구간입니다.';
    default:
      return '데이터 부족으로 액션 해석 불가';
  }
}

export function HelpDot({ title, interactive = true }: { title: string; interactive?: boolean }) {
  if (!interactive) {
    return (
      <span
        className="ml-1 inline-flex h-3.5 w-3.5 items-center justify-center rounded-full border border-current/30 text-[9px] leading-none opacity-50"
        aria-hidden="true"
      >
        i
      </span>
    );
  }
  return (
    <details className="relative inline-block align-middle group">
      <summary className="ml-1 inline-flex h-3.5 w-3.5 cursor-pointer list-none items-center justify-center rounded-full border border-current/30 text-[9px] leading-none opacity-80 hover:opacity-100">
        i
      </summary>
      <div className="pointer-events-none absolute right-0 z-20 mt-2 w-64 rounded-lg border border-white/10 bg-slate-950/95 p-3 text-[11px] leading-relaxed text-slate-100 shadow-2xl">
        {title}
      </div>
    </details>
  );
}

export function InfoBadge({
  label,
  value,
  title,
  kind,
  className = '',
  interactive = true,
}: {
  label: string;
  value: number | string | null | undefined;
  title: string;
  kind: 'buy' | 'quality' | 'crowding' | 'appeal' | 'total';
  className?: string;
  interactive?: boolean;
}) {
  return (
    <span className={`rounded-full border px-1.5 py-0.5 text-[10px] ${scoreTone(typeof value === 'number' ? value : null, kind)} ${className} ${interactive ? "cursor-help" : ""}`.trim()}>
      {label} {value ?? '—'}
      <HelpDot title={title} interactive={interactive} />
    </span>
  );
}

export function ScoreBadge({
  label,
  value,
  title,
  kind,
  className = '',
  interactive = true,
}: {
  label: string;
  value: number | null | undefined;
  title: string;
  kind: 'buy' | 'quality' | 'crowding' | 'appeal' | 'total';
  className?: string;
  interactive?: boolean;
}) {
  return (
    <span className={`rounded-full border px-2 py-1 ${scoreTone(value, kind)} ${className} ${interactive ? "cursor-help" : ""}`.trim()}>
      {label} {typeof value === 'number' ? value : '—'}
      <HelpDot title={title} interactive={interactive} />
    </span>
  );
}

export function ActionBadge({ value, compact = false, interactive = true }: { value: number | null | undefined; compact?: boolean; interactive?: boolean }) {
  const label = buyActionLabel(value);
  const tone = label === 'STRONG BUY'
    ? 'border-green-500/30 bg-green-500/10 text-green-200'
    : label === 'BUY'
      ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-200'
      : label === 'HOLD'
        ? 'border-cyan-500/30 bg-cyan-500/10 text-cyan-200'
        : label === 'REDUCE'
          ? 'border-amber-500/30 bg-amber-500/10 text-amber-200'
          : label === 'SELL'
            ? 'border-red-500/30 bg-red-500/10 text-red-200'
            : 'border-white/10 bg-white/5 text-white/70';
  return (
    <span className={`rounded-full border ${compact ? 'px-1.5 py-0.5 text-[9px]' : 'px-2 py-1 text-[10px]'} ${tone} ${interactive ? "cursor-help" : ""}`.trim()}>
      {compact ? label : `${label} · ${buyActionKorean(label)}`}
      <HelpDot title={buyActionHelp(value)} interactive={interactive} />
    </span>
  );
}

export function ScoreLegend({ compact = false, defaultOpen = false }: { compact?: boolean; defaultOpen?: boolean }) {
  return (
    <details open={defaultOpen} className="rounded-lg border border-white/10 bg-black/15 p-3 text-[11px] text-[var(--muted)]">
      <summary className="cursor-pointer list-none select-none text-white/90 font-medium">
        점수 해석 보기
      </summary>
      <div className={`mt-3 ${compact ? 'space-y-2' : 'space-y-3'}`}>
        <div>
          <div className="font-medium text-emerald-200">B / 실행 총점</div>
          <div className="mt-1 flex flex-wrap gap-2">
            <span className="rounded-full border border-green-500/30 bg-green-500/10 px-2 py-1 text-green-200">80+ STRONG BUY · 게이트 통과 시 분할 확대</span>
            <span className="rounded-full border border-emerald-500/30 bg-emerald-500/10 px-2 py-1 text-emerald-200">70~79 BUY · 매수 가능</span>
            <span className="rounded-full border border-cyan-500/30 bg-cyan-500/10 px-2 py-1 text-cyan-200">55~69 HOLD · 보유/관찰</span>
            <span className="rounded-full border border-amber-500/30 bg-amber-500/10 px-2 py-1 text-amber-200">40~54 REDUCE · 축소</span>
            <span className="rounded-full border border-red-500/30 bg-red-500/10 px-2 py-1 text-red-200">39↓ SELL · 매도/회피</span>
          </div>
        </div>
        <div>
          <div className="font-medium text-cyan-200">Q / 기업·섹터 체력 / 매력</div>
          <div className="mt-1">70+는 구조·품질 또는 상대 매력이 강하다는 뜻이며, 그 자체로 매수 액션은 아닙니다. 가격·촉매·과열·데이터 품질을 함께 확인합니다.</div>
        </div>
        <div>
          <div className="font-medium text-amber-200">과열</div>
          <div className="mt-1 flex flex-wrap gap-2">
            <span className="rounded-full border border-emerald-500/30 bg-emerald-500/10 px-2 py-1 text-emerald-200">54↓ 안정</span>
            <span className="rounded-full border border-amber-500/30 bg-amber-500/10 px-2 py-1 text-amber-200">55~69 주의</span>
            <span className="rounded-full border border-red-500/30 bg-red-500/10 px-2 py-1 text-red-200">70+ 추격 주의</span>
          </div>
        </div>
        <div className="leading-relaxed">
          B는 여러 실행 조건의 모델 점수, Q는 <span className="text-white">섹터/기업 체력</span>을 보는 구조 점수입니다. 모든 점수는 상대 비교용이며 수익률·적중 확률이 아닙니다.
        </div>
      </div>
    </details>
  );
}

export function ScoreRow({ children }: { children: ReactNode }) {
  return <div className="mt-1 flex flex-wrap gap-1 text-[10px]">{children}</div>;
}
