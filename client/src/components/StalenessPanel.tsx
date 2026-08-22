"use client";

interface StalenessEntry {
  date: string;
  daysAgo: number;
  frequency: string;
  maximumAgeDays?: number;
  eligibleForSignals?: boolean;
}

const NORMAL_STALENESS: Record<string, number> = {
  '일간': 7,
  '주간': 14,
  '월간': 75,
  '분기': 200,
};

function stalenessStatus(daysAgo: number, frequency: string, maximumAgeDays?: number): { color: string; label: string } {
  const normal = maximumAgeDays || NORMAL_STALENESS[frequency] || 7;
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
  WDTGAL: "TGA 수요일 잔액",
  WTREGEN: "TGA 주간평균(참고)",
  WRMFNS: "MMF",
  PC_RATIO: "당일 P/C 합성",
  AAII_BULL_BEAR_SPREAD: "AAII 심리",
  NAAIM_EXPOSURE: "NAAIM 노출",
  KOSPI_FOREIGN_NET_1D: "KOSPI 외국인",
};

interface CollectionSourceStatus {
  status: "SUCCESS" | "DEGRADED" | "FAILED" | "STALE";
  lastAttemptStatus?: "SUCCESS" | "DEGRADED" | "FAILED";
  attemptedAt: string;
  completedAt: string;
  ageMinutes: number;
  maximumSilenceMinutes?: number;
  collected: number;
  persisted: number;
  failureKeys?: string[];
  failureType?: string;
}

interface CollectionHealth {
  status: "HEALTHY" | "DEGRADED" | "FAILED" | "UNKNOWN" | "UNAVAILABLE";
  asOf?: string;
  usedForInvestmentScores?: boolean;
  sourceCount?: number;
  degradedCount?: number;
  failedCount?: number;
  sources?: Record<string, CollectionSourceStatus>;
  reason?: string;
  policy?: string;
}

interface Props {
  staleness?: Record<string, StalenessEntry>;
  inputFreshness?: {
    rawUsable: number;
    rawExcluded: number;
    derivedUsable: number;
    derivedExcluded: number;
    excludedKeys?: string[];
    policy?: string;
  };
  collectionHealth?: CollectionHealth;
}

export function StalenessPanel({ staleness, inputFreshness, collectionHealth }: Props) {
  if ((!staleness || !Object.keys(staleness).length) && !inputFreshness && !collectionHealth) return null;

  const entries = Object.entries(staleness || {})
    .map(([key, val]) => ({ key, label: INDICATOR_LABELS[key] || key, ...val }))
    .sort((a, b) => b.daysAgo - a.daysAgo);

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <h3 className="text-base sm:text-lg font-semibold mb-1">데이터 신선도</h3>
      <p className="text-[11px] sm:text-xs text-[var(--muted)] mb-3">
        발표 주기별 허용 기간을 넘긴 값은 화면에는 남기되 국면·매수 신호 산식에서는 제외합니다.
      </p>

      {collectionHealth && (
        <div className="mb-3 rounded-lg border border-white/10 bg-black/10 p-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <div className="text-xs font-semibold text-white">수집 실행 상태</div>
              <div className="mt-0.5 text-[10px] text-[var(--muted)]">
                수집 성공 여부와 값의 발표일 신선도는 별개입니다. 이 상태 자체는 투자 점수에 넣지 않습니다.
              </div>
            </div>
            <span className={`rounded px-2 py-1 text-[10px] font-semibold ${collectionTone(collectionHealth.status)}`}>
              {collectionLabel(collectionHealth.status)}
            </span>
          </div>
          {collectionHealth.reason && (
            <div className="mt-2 text-[10px] text-amber-200">{collectionHealth.reason}</div>
          )}
          {!!Object.keys(collectionHealth.sources ?? {}).length && (
            <div className="mt-2 grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
              {Object.entries(collectionHealth.sources ?? {}).map(([source, value]) => (
                <div key={source} className="rounded-md border border-white/10 bg-white/[0.025] p-2">
                  <div className="flex items-center justify-between gap-1">
                    <span className="truncate text-[10px] text-white/80">{collectionSourceLabel(source)}</span>
                    <span className={`shrink-0 rounded px-1 py-0.5 text-[9px] ${collectionRunTone(value.status)}`}>
                      {collectionRunLabel(value.status)}
                    </span>
                  </div>
                  <div className="mt-1 text-[9px] text-[var(--muted)]">
                    {minutesLabel(value.ageMinutes)} · 저장 {value.persisted}/{value.collected}
                  </div>
                  {!!value.failureKeys?.length && (
                    <div className="mt-1 line-clamp-2 text-[9px] text-amber-200">
                      결측 {value.failureKeys.join(", ")}
                    </div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {inputFreshness && (inputFreshness.rawExcluded + inputFreshness.derivedExcluded > 0) && (
        <div className="mb-3 rounded-lg border border-amber-500/25 bg-amber-500/10 px-3 py-2 text-xs text-amber-100">
          <div className="font-semibold">신호 산식 제외 {inputFreshness.rawExcluded + inputFreshness.derivedExcluded}개</div>
          <div className="mt-1 text-[10px] text-amber-100/80">
            원천 {inputFreshness.rawExcluded}개 · 파생 {inputFreshness.derivedExcluded}개
            {inputFreshness.excludedKeys?.length ? ` · ${inputFreshness.excludedKeys.slice(0, 6).join(', ')}` : ''}
          </div>
        </div>
      )}

      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-2">
        {entries.map((e) => {
          const status = stalenessStatus(e.daysAgo, e.frequency, e.maximumAgeDays);
          return (
            <div key={e.key} className="rounded-lg border border-[var(--card-border)] bg-[var(--background)] p-2.5">
              <div className="flex items-center justify-between mb-1">
                <span className="text-[10px] sm:text-xs truncate">{e.label}</span>
                <div className="flex items-center gap-1 shrink-0">
                  <span className={`text-[9px] px-1 py-0.5 rounded ${e.eligibleForSignals === false ? 'bg-red-500/20 text-red-300' : status.color}`}>
                    {e.eligibleForSignals === false ? '산식 제외' : status.label}
                  </span>
                  <span className="text-[10px] font-mono text-[var(--muted)]">{e.daysAgo}일</span>
                </div>
              </div>
              <div className="text-[9px] sm:text-[10px] text-[var(--muted)]">
                {e.date} · {e.frequency} · 허용 {e.maximumAgeDays || NORMAL_STALENESS[e.frequency] || 7}일
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function collectionLabel(value: CollectionHealth["status"]): string {
  return {
    HEALTHY: "전체 성공",
    DEGRADED: "부분 성공",
    FAILED: "수집 실패 포함",
    UNKNOWN: "첫 실행 대기",
    UNAVAILABLE: "진단 불가",
  }[value];
}

function collectionTone(value: CollectionHealth["status"]): string {
  if (value === "HEALTHY") return "bg-emerald-500/15 text-emerald-200";
  if (value === "DEGRADED" || value === "UNKNOWN") return "bg-amber-500/15 text-amber-200";
  return "bg-red-500/15 text-red-200";
}

function collectionRunLabel(value: CollectionSourceStatus["status"]): string {
  return value === "SUCCESS" ? "성공" : value === "DEGRADED" ? "부분" : value === "STALE" ? "지연" : "실패";
}

function collectionRunTone(value: CollectionSourceStatus["status"]): string {
  if (value === "SUCCESS") return "bg-emerald-500/15 text-emerald-200";
  if (value === "DEGRADED" || value === "STALE") return "bg-amber-500/15 text-amber-200";
  return "bg-red-500/15 text-red-200";
}

function collectionSourceLabel(value: string): string {
  return {
    FRED: "거시(FRED)",
    YAHOO: "가격",
    FEAR_GREED: "공포·탐욕",
    SENTIMENT: "심리",
    STABLECOIN: "스테이블코인",
    KRX: "국내 수급",
  }[value] ?? value;
}

function minutesLabel(value: number): string {
  if (!Number.isFinite(value) || value < 0) return "시각 불명";
  if (value < 60) return `${Math.round(value)}분 전`;
  if (value < 1_440) return `${Math.floor(value / 60)}시간 전`;
  return `${Math.floor(value / 1_440)}일 전`;
}
