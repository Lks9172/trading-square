"use client";

interface StalenessEntry {
  date: string;
  daysAgo: number;
  frequency: string;
}

const NORMAL_STALENESS: Record<string, number> = {
  '일간': 5,
  '주간': 12,
  '월간': 75,
};

function stalenessStatus(daysAgo: number, frequency: string): { color: string; label: string } {
  const normal = NORMAL_STALENESS[frequency] || 7;
  if (daysAgo <= normal) return { color: "bg-green-500/20 text-green-400", label: "정상" };
  if (daysAgo <= normal * 1.5) return { color: "bg-yellow-500/20 text-yellow-400", label: "지연" };
  return { color: "bg-red-500/20 text-red-400", label: "주의" };
}

const INDICATOR_LABELS: Record<string, string> = {
  ICSA: "신규실업수당",
  UNRATE: "실업률",
  M2SL: "M2 통화량",
  DGS10: "10Y 금리",
  VIXCLS: "VIX",
  T10Y2Y: "장단기 금리차",
  BAMLH0A0HYM2: "하이일드 스프레드",
  WALCL: "연준 총자산",
  WRESBAL: "지급준비금",
  RRPONTSYD: "RRP",
  WTREGEN: "TGA",
  WRMFNS: "MMF",
};

interface Props {
  staleness?: Record<string, StalenessEntry>;
}

export function StalenessPanel({ staleness }: Props) {
  if (!staleness || !Object.keys(staleness).length) return null;

  const entries = Object.entries(staleness)
    .map(([key, val]) => ({ key, label: INDICATOR_LABELS[key] || key, ...val }))
    .sort((a, b) => b.daysAgo - a.daysAgo);

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <h3 className="text-base sm:text-lg font-semibold mb-1">데이터 신선도</h3>
      <p className="text-[11px] sm:text-xs text-[var(--muted)] mb-3">
        각 지표의 발표 주기 대비 정상 범위인지 표시. 일간은 5일, 주간은 12일, 월간은 75일 이내면 정상.
      </p>

      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-2">
        {entries.map((e) => {
          const status = stalenessStatus(e.daysAgo, e.frequency);
          return (
            <div key={e.key} className="rounded-lg border border-[var(--card-border)] bg-[var(--background)] p-2.5">
              <div className="flex items-center justify-between mb-1">
                <span className="text-[10px] sm:text-xs truncate">{e.label}</span>
                <div className="flex items-center gap-1 shrink-0">
                  <span className={`text-[9px] px-1 py-0.5 rounded ${status.color}`}>{status.label}</span>
                  <span className="text-[10px] font-mono text-[var(--muted)]">{e.daysAgo}일</span>
                </div>
              </div>
              <div className="text-[9px] sm:text-[10px] text-[var(--muted)]">
                {e.date} · {e.frequency}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
