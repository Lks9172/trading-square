"use client";

interface CalendarEvent {
  date: string;
  name: string;
  category: 'FOMC' | 'CPI' | 'NFP' | 'PCE' | 'GDP' | 'OTHER';
  daysUntil: number;
  importance: 'high' | 'medium';
}

interface Props {
  events?: CalendarEvent[];
}

const CATEGORY_LABEL: Record<CalendarEvent['category'], string> = {
  FOMC: 'FOMC',
  CPI: 'CPI',
  NFP: '고용보고서',
  PCE: 'PCE',
  GDP: 'GDP',
  OTHER: '기타',
};

const CATEGORY_COLOR: Record<CalendarEvent['category'], string> = {
  FOMC: 'text-red-400 border-red-400/40',
  CPI: 'text-orange-400 border-orange-400/40',
  NFP: 'text-yellow-400 border-yellow-400/40',
  PCE: 'text-amber-400 border-amber-400/40',
  GDP: 'text-blue-400 border-blue-400/40',
  OTHER: 'text-neutral-400 border-neutral-400/40',
};

function formatDayDiff(days: number): string {
  if (days === 0) return '오늘';
  if (days === 1) return '내일';
  if (days > 0) return `D-${days}`;
  return `D+${Math.abs(days)}`;
}

export function CalendarPanel({ events }: Props) {
  if (!events || events.length === 0) {
    return (
      <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
        <h3 className="text-base sm:text-lg font-semibold mb-1">경제 캘린더</h3>
        <p className="text-xs text-[var(--muted)]">이벤트 데이터 없음</p>
      </div>
    );
  }

  // 임박(0~3), 일반(4~30), 과거(<0) 분류
  const upcoming = events.filter((e) => e.daysUntil >= 0).sort((a, b) => a.daysUntil - b.daysUntil).slice(0, 15);
  const imminent = upcoming.filter((e) => e.daysUntil <= 3);

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <h3 className="text-base sm:text-lg font-semibold mb-1">경제 캘린더</h3>
      <p className="text-[11px] sm:text-xs text-[var(--muted)] mb-3">
        FOMC · CPI · 고용 · PCE · GDP 발표 일정 (영상4 §04 노란불 트리거)
      </p>

      {imminent.length > 0 && (
        <div className="mb-4 p-3 rounded-lg bg-red-500/10 border border-red-500/30">
          <div className="text-xs text-red-300 font-semibold mb-2">⚠ 임박 (D-3 이내)</div>
          <div className="space-y-1">
            {imminent.map((e, i) => (
              <div key={i} className="flex items-center justify-between text-xs">
                <span className="font-mono text-red-200">{formatDayDiff(e.daysUntil)}</span>
                <span className="flex-1 mx-3 truncate">{e.name}</span>
                <span className={`px-1.5 py-0.5 rounded border text-[10px] font-mono ${CATEGORY_COLOR[e.category]}`}>
                  {CATEGORY_LABEL[e.category]}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="space-y-1.5">
        {upcoming.map((e, i) => (
          <div
            key={i}
            className="flex items-center justify-between rounded-lg bg-[var(--background)] border border-[var(--card-border)] px-3 py-2 text-xs"
          >
            <span className="font-mono text-[var(--muted)] w-16 shrink-0">{e.date.slice(5)}</span>
            <span className={`font-mono w-12 shrink-0 text-center ${e.daysUntil <= 3 ? 'text-red-400' : 'text-neutral-400'}`}>
              {formatDayDiff(e.daysUntil)}
            </span>
            <span className="flex-1 mx-2 truncate">{e.name}</span>
            <span className={`px-1.5 py-0.5 rounded border text-[10px] font-mono ${CATEGORY_COLOR[e.category]}`}>
              {CATEGORY_LABEL[e.category]}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
