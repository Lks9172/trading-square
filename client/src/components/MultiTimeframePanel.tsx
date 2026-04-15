"use client";

interface DerivedPoint {
  value: number;
  date: string;
  formula: string;
}

interface Props {
  derived?: Record<string, DerivedPoint>;
}

const ASSETS = [
  { prefix: 'NASDAQ', label: '나스닥' },
  { prefix: 'KOSPI', label: '코스피' },
];

function classifyBody(bodyPct: number, lowerWick: number, isBullish: boolean): { label: string; color: string } {
  if (bodyPct >= 90 && lowerWick < 5) return { label: isBullish ? '마루보주 양봉' : '마루보주 음봉', color: isBullish ? 'text-red-400' : 'text-blue-400' };
  if (bodyPct >= 60) return { label: isBullish ? '장대양봉' : '장대음봉', color: isBullish ? 'text-green-400' : 'text-red-400' };
  if (bodyPct < 10) return { label: '도지', color: 'text-neutral-400' };
  return { label: isBullish ? '양봉' : '음봉', color: isBullish ? 'text-green-300' : 'text-red-300' };
}

function positionBar(pos: number | null): string {
  if (pos === null) return '—';
  const filled = Math.round(pos / 10);
  return '█'.repeat(filled) + '░'.repeat(10 - filled);
}

export function MultiTimeframePanel({ derived }: Props) {
  if (!derived) return null;

  const rows = ASSETS.map(({ prefix, label }) => {
    const exhaustion = derived[`${prefix}_MONTHLY_EXHAUSTION`]?.value;
    const reversal = derived[`${prefix}_WEEKLY_REVERSAL`]?.value;
    const monthPos = derived[`${prefix}_MONTH_POS`]?.value ?? null;
    const monthBody = derived[`${prefix}_MONTHLY_BODY_PCT`]?.value ?? null;
    const monthLowerWick = derived[`${prefix}_MONTHLY_LOWER_WICK_PCT`]?.value ?? null;
    const weekBullish = derived[`${prefix}_WEEKLY_BULLISH`]?.value;
    const monthDate = derived[`${prefix}_MONTHLY_BODY_PCT`]?.date;

    const monthKind = monthBody !== null && monthLowerWick !== null
      ? classifyBody(monthBody, monthLowerWick, true /* 월봉 양봉만 있는 경우 기준 */)
      : null;

    const warnings: string[] = [];
    if (exhaustion === 1) warnings.push('⚠ 월봉 소진 (3개월 연속 장대양봉 + 아래꼬리 없음)');
    if (reversal === 1) warnings.push('⚠ 주봉 반전 (상승 추세 후 장대음봉)');
    if (monthPos !== null && monthPos >= 95) warnings.push(`⚠ 월봉 위치 ${monthPos.toFixed(0)}% (12개월 고점 근처)`);
    if (monthBody !== null && monthBody >= 90 && monthLowerWick !== null && monthLowerWick < 5) {
      warnings.push(`⚠ 마루보주 패턴 (body ${monthBody.toFixed(0)}%, 아래꼬리 ${monthLowerWick.toFixed(0)}%)`);
    }

    return { prefix, label, exhaustion, reversal, monthPos, monthBody, monthLowerWick, weekBullish, monthDate, monthKind, warnings };
  });

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <h3 className="text-base sm:text-lg font-semibold mb-1">멀티 타임프레임 캔들</h3>
      <p className="text-[11px] sm:text-xs text-[var(--muted)] mb-4">
        영상 3·4·5 "월→주→일" 위계 판단. 월봉 구조·주봉 흐름·이상 패턴 경고
      </p>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {rows.map((r) => (
          <div key={r.prefix} className="rounded-lg border border-[var(--card-border)] bg-[var(--background)] p-3">
            <div className="flex items-center justify-between mb-2">
              <h4 className="font-semibold">{r.label}</h4>
              <span className="text-[10px] text-[var(--muted)] font-mono">{r.monthDate}</span>
            </div>

            <div className="space-y-2 text-xs">
              <div className="flex items-center justify-between">
                <span className="text-[var(--muted)]">12개월 위치</span>
                <div className="flex items-center gap-2">
                  <span className={`font-mono ${r.monthPos !== null && r.monthPos >= 90 ? 'text-red-400' : r.monthPos !== null && r.monthPos <= 20 ? 'text-green-400' : 'text-neutral-300'}`}>
                    {r.monthPos !== null ? `${r.monthPos.toFixed(0)}%` : '—'}
                  </span>
                  <span className="font-mono text-[10px] text-[var(--muted)]">{positionBar(r.monthPos)}</span>
                </div>
              </div>

              <div className="flex items-center justify-between">
                <span className="text-[var(--muted)]">월봉 몸통</span>
                <span className="font-mono">
                  {r.monthBody !== null ? `${r.monthBody.toFixed(0)}%` : '—'}
                  {r.monthBody !== null && (
                    <span className="ml-2 text-[10px] text-[var(--muted)]">
                      ({r.monthBody >= 90 ? '마루보주' : r.monthBody >= 60 ? '장대봉' : r.monthBody < 10 ? '도지' : '보통'})
                    </span>
                  )}
                </span>
              </div>

              <div className="flex items-center justify-between">
                <span className="text-[var(--muted)]">월봉 아래꼬리</span>
                <span className={`font-mono ${r.monthLowerWick !== null && r.monthLowerWick < 5 ? 'text-red-400' : 'text-neutral-300'}`}>
                  {r.monthLowerWick !== null ? `${r.monthLowerWick.toFixed(0)}%` : '—'}
                </span>
              </div>

              <div className="flex items-center justify-between">
                <span className="text-[var(--muted)]">주봉</span>
                <span className={`font-mono ${r.weekBullish === 1 ? 'text-green-400' : 'text-red-400'}`}>
                  {r.weekBullish === 1 ? '양봉' : r.weekBullish === 0 ? '음봉' : '—'}
                </span>
              </div>
            </div>

            {r.warnings.length > 0 && (
              <div className="mt-3 p-2 rounded bg-red-500/5 border border-red-500/20 space-y-1">
                {r.warnings.map((w, i) => (
                  <div key={i} className="text-[10px] text-red-300">{w}</div>
                ))}
              </div>
            )}
            {r.warnings.length === 0 && (
              <div className="mt-3 text-[10px] text-green-400/80">✓ 경고 없음</div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
