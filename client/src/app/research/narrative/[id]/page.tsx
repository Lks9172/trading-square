import { fetchServerJson } from "@/lib/server-api";

export const revalidate = 300;
export const dynamic = "force-dynamic";

type SourceDiagnostic = {
  sourceKey: string;
  label: string;
  quality: string;
  status: string;
  latestObservedAt: string | null;
  lastAvailableAt: string | null;
  ageHours: number | null;
  revision: number | null;
  missingStreak: number;
  value: number | null;
  score: number | null;
  detail: string;
  sourceUrl: string;
  effectiveWeight: number;
};

type SourceHistoryPoint = {
  sourceKey: string;
  label: string;
  observationDate: string;
  observedAt: string;
  revision: number;
  quality: string;
  status: string;
  value: number | null;
  score: number | null;
  detail: string;
  sourceUrl: string;
};

type PageData = {
  theme?: { title: string; description: string; id: string };
  stage?: string;
  trend?: string;
  heatScore?: number;
  heatDelta7d?: number | null;
  heatDelta30d?: number | null;
  heatHistory?: Array<{ date: string; heatScore: number }>;
  drivers?: string[];
  risks?: string[];
  proxyScores?: Array<{ key: string; label: string; score: number; detail: string }>;
  externalSignals?: Array<{
    key: string;
    label: string;
    score: number;
    detail: string;
    value: number | null;
    quality: string;
    status: string;
    observedAt: string | null;
    revision: number | null;
    weight: number;
    sourceUrl: string;
  }>;
  sourceStatus?: string;
  sourceQualityScore?: number;
  sourceCoveragePct?: number;
  legacyFallbackUsed?: boolean;
  sourceDiagnostics?: SourceDiagnostic[];
  sourceObservationCount?: number;
  sourceRevisionCount?: number;
  sourceMissingCount?: number;
  sourceFailureCount?: number;
  sourceLastRefreshAt?: string | null;
  sourceHistory?: SourceHistoryPoint[];
  sourceHistoryTruncated?: boolean;
  sourceMethodology?: string;
};

function toSparkline(values: Array<number | null | undefined>, fallback = "—") {
  const valid = values.filter(
    (value): value is number => typeof value === "number" && Number.isFinite(value),
  );
  if (!valid.length) return fallback;
  const bars = "▁▂▃▄▅▆▇█";
  const min = Math.min(...valid);
  const max = Math.max(...valid);
  if (min === max) return bars[Math.floor((bars.length - 1) / 2)].repeat(valid.length);
  return valid
    .map((value) =>
      bars[
        Math.max(
          0,
          Math.min(
            bars.length - 1,
            Math.round(((value - min) / (max - min)) * (bars.length - 1)),
          ),
        )
      ],
    )
    .join("");
}

async function fetchData(id: string): Promise<PageData> {
  return (
    (await fetchServerJson<PageData>(`/api/narrative/themes/${encodeURIComponent(id)}`, {
      revalidate: 300,
    })) ?? {}
  );
}

export default async function NarrativeThemePage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const data = await fetchData(id);
  const heatSpark = toSparkline((data.heatHistory ?? []).map((item) => item.heatScore));
  const sourceHistory = data.sourceHistory ?? [];
  const recentSourceHistory = [...sourceHistory]
    .sort((left, right) => right.observedAt.localeCompare(left.observedAt))
    .slice(0, 36);

  if (!data.theme) {
    return (
      <main className="mx-auto flex-1 w-full max-w-5xl p-4 md:p-6">
        <div className="rounded-2xl border border-red-500/20 bg-red-500/10 p-5 text-sm text-red-100">
          내러티브 테마를 찾을 수 없습니다.
        </div>
      </main>
    );
  }

  return (
    <main className="mx-auto flex-1 w-full max-w-6xl p-4 md:p-6">
      <div className="space-y-6">
        <header className="space-y-2">
          <a href="/research" className="cursor-pointer text-xs text-cyan-300 hover:text-cyan-200">
            ← Research로 돌아가기
          </a>
          <h1 className="text-2xl font-bold tracking-tight">{data.theme.title}</h1>
          <p className="text-sm text-[var(--muted)]">{data.theme.description}</p>
        </header>

        <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <div className="text-sm font-semibold text-white">내러티브 단계</div>
              <div className="mt-1 text-xs text-[var(--muted)]">
                열기가 높을수록 관심과 군중화가 강합니다. 높은 점수는 단독 매수 신호가 아닙니다.
              </div>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Badge>{data.stage}</Badge>
              {data.trend ? <Badge>{data.trend}</Badge> : null}
              <Badge tone="cyan">열기 {data.heatScore}점</Badge>
              {typeof data.heatDelta7d === "number" ? (
                <Badge>7D {signed(data.heatDelta7d)}</Badge>
              ) : null}
              {typeof data.heatDelta30d === "number" ? (
                <Badge>30D {signed(data.heatDelta30d)}</Badge>
              ) : null}
            </div>
          </div>

          <div className="mt-4 rounded-xl border border-white/10 bg-black/15 p-3">
            <div className="text-[11px] text-[var(--muted)]">Heat 추이</div>
            <div className="mt-2 font-mono text-lg tracking-[0.2em] text-cyan-100">{heatSpark}</div>
            <div className="mt-1 text-[11px] text-[var(--muted)]">최근 heat score history</div>
          </div>

          <div className="mt-4 rounded-xl border border-white/10 bg-black/15 p-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <div className="text-sm font-semibold text-white">외부 데이터 신뢰도</div>
                <div className="mt-1 text-xs text-[var(--muted)]">소스 등급·신선도·결측·당일 revision을 반영한 점수</div>
              </div>
              <div className="flex flex-wrap gap-2">
                <Badge tone={sourceTone(data.sourceStatus)}>{statusLabel(data.sourceStatus)}</Badge>
                <Badge tone="cyan">품질 {data.sourceQualityScore ?? 0}</Badge>
                <Badge>커버리지 {data.sourceCoveragePct ?? 0}%</Badge>
                <Badge>45일 관측 {data.sourceObservationCount ?? 0}</Badge>
                <Badge tone={(data.sourceRevisionCount ?? 0) > 0 ? "cyan" : "neutral"}>
                  리비전 {data.sourceRevisionCount ?? 0}
                </Badge>
                <Badge tone={(data.sourceFailureCount ?? 0) > 0 ? "amber" : "green"}>
                  45일 실패 {data.sourceFailureCount ?? 0}
                </Badge>
                {data.legacyFallbackUsed ? <Badge tone="amber">LEGACY FALLBACK</Badge> : null}
              </div>
            </div>
            <p className="mt-3 text-[11px] leading-relaxed text-[var(--muted)]">{data.sourceMethodology}</p>
            <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-[10px] text-white/55">
              <span>최근 갱신 {formatObservedAt(data.sourceLastRefreshAt)}</span>
              <span>결측 관측 {data.sourceMissingCount ?? 0}회</span>
              <span>수집 실패는 점수 0이 아니라 가중치 제외로 처리</span>
            </div>
            <div className="mt-3 grid grid-cols-1 gap-3 lg:grid-cols-3">
              {(data.sourceDiagnostics ?? []).map((source) => {
                const history = sourceHistory.filter((item) => item.sourceKey === source.sourceKey);
                return (
                <div key={source.sourceKey} className="rounded-lg border border-white/10 bg-white/[0.02] p-3">
                  <div className="flex items-center justify-between gap-2">
                    <div className="text-xs font-medium text-white">{source.label}</div>
                    <div className="flex gap-1">
                      <Badge compact>{qualityLabel(source.quality)}</Badge>
                      <Badge compact tone={sourceStatusTone(source.status)}>{statusLabel(source.status)}</Badge>
                    </div>
                  </div>
                  <div className="mt-2 text-[11px] leading-relaxed text-[var(--muted)]">{source.detail}</div>
                  <div className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-[10px] text-white/60">
                    <span>가중치 {(source.effectiveWeight * 100).toFixed(0)}%</span>
                    <span>rev {source.revision ?? "—"}</span>
                    <span>결측연속 {source.missingStreak}회</span>
                    <span>나이 {source.ageHours ?? "—"}h</span>
                  </div>
                  <div className="mt-2 rounded-md border border-white/5 bg-black/15 px-2 py-1.5">
                    <div className="flex items-center justify-between gap-2 text-[9px] text-white/45">
                      <span>45일 소스 점수</span>
                      <span>{history.length} 관측</span>
                    </div>
                    <div className="mt-1 overflow-hidden font-mono text-sm tracking-[0.12em] text-cyan-100">
                      {toSparkline(history.map((item) => item.score))}
                    </div>
                  </div>
                  {source.sourceUrl ? (
                    <a href={source.sourceUrl} target="_blank" rel="noreferrer" className="mt-2 inline-flex text-[10px] text-cyan-300 hover:text-cyan-200">
                      원본 소스 열기 ↗
                    </a>
                  ) : null}
                </div>
                );
              })}
            </div>

            {recentSourceHistory.length > 0 ? (
              <div className="mt-4 overflow-hidden rounded-xl border border-white/10 bg-black/15">
                <div className="flex flex-wrap items-center justify-between gap-2 border-b border-white/10 px-3 py-2.5">
                  <div>
                    <div className="text-xs font-semibold text-white">소스 관측·리비전 이력</div>
                    <div className="mt-0.5 text-[10px] text-[var(--muted)]">
                      최근 45일 중 최신 36건 · 동일 날짜 내용 변경은 rev 2 이상으로 보존
                      {data.sourceHistoryTruncated ? " · API 이력은 최신 180건으로 제한" : ""}
                    </div>
                  </div>
                  <Badge compact tone={(data.sourceFailureCount ?? 0) > 0 ? "amber" : "green"}>
                    누적 실패 {data.sourceFailureCount ?? 0}
                  </Badge>
                </div>
                <div className="max-h-[28rem] overflow-auto">
                  <table className="w-full min-w-[760px] text-left text-[10px]">
                    <thead className="sticky top-0 bg-[#121a26] text-white/50">
                      <tr>
                        <th className="px-3 py-2 font-medium">관측 시각</th>
                        <th className="px-3 py-2 font-medium">소스</th>
                        <th className="px-3 py-2 font-medium">상태</th>
                        <th className="px-3 py-2 font-medium">값 / 점수</th>
                        <th className="px-3 py-2 font-medium">리비전</th>
                        <th className="px-3 py-2 font-medium">근거</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-white/5">
                      {recentSourceHistory.map((item) => (
                        <tr key={`${item.sourceKey}-${item.observationDate}-${item.revision}-${item.observedAt}`}>
                          <td className="whitespace-nowrap px-3 py-2 text-white/55">
                            {formatObservedAt(item.observedAt)}
                          </td>
                          <td className="px-3 py-2">
                            <div className="font-medium text-white/85">{item.label}</div>
                            <div className="mt-0.5 text-[9px] text-white/35">{qualityLabel(item.quality)}</div>
                          </td>
                          <td className="px-3 py-2">
                            <Badge compact tone={sourceStatusTone(item.status)}>{statusLabel(item.status)}</Badge>
                          </td>
                          <td className="whitespace-nowrap px-3 py-2 text-white/70">
                            {formatSourceValue(item.value)} / {typeof item.score === "number" ? item.score.toFixed(1) : "—"}
                          </td>
                          <td className="px-3 py-2">
                            <Badge compact tone={item.revision > 1 ? "cyan" : "neutral"}>rev {item.revision}</Badge>
                          </td>
                          <td className="max-w-sm px-3 py-2 leading-relaxed text-white/55">
                            {item.sourceUrl ? (
                              <a href={item.sourceUrl} target="_blank" rel="noreferrer" className="hover:text-cyan-200">
                                {item.detail || "원본 소스"} ↗
                              </a>
                            ) : item.detail || "—"}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : null}
          </div>

          {(data.drivers?.length ?? 0) > 0 ? (
            <div className="mt-4">
              <div className="text-xs text-cyan-200">드라이버</div>
              <div className="mt-2 flex flex-wrap gap-2">
                {data.drivers?.map((item) => (
                  <span key={item} className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-1 text-xs text-cyan-100">{item}</span>
                ))}
              </div>
            </div>
          ) : null}

          {(data.risks?.length ?? 0) > 0 ? (
            <div className="mt-4">
              <div className="text-xs text-amber-200">리스크</div>
              <div className="mt-2 flex flex-wrap gap-2">
                {data.risks?.map((item) => (
                  <span key={item} className="rounded-full border border-amber-500/20 bg-amber-500/10 px-2 py-1 text-xs text-amber-100">{item}</span>
                ))}
              </div>
            </div>
          ) : null}

          {(data.externalSignals?.length ?? 0) > 0 ? (
            <div className="mt-5">
              <div className="text-xs text-cyan-200">외부 프록시</div>
              <div className="mt-2 grid grid-cols-1 gap-3 md:grid-cols-2">
                {data.externalSignals?.map((item) => (
                  <div key={item.key} className="rounded-xl border border-cyan-500/15 bg-cyan-500/5 p-3">
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <div className="font-medium text-white">{item.label}</div>
                        <div className="mt-1 text-xs break-words text-[var(--muted)]">{item.detail}</div>
                        <div className="mt-2 flex flex-wrap gap-1 text-[10px]">
                          <Badge compact>{qualityLabel(item.quality)}</Badge>
                          <Badge compact tone={sourceStatusTone(item.status)}>{statusLabel(item.status)}</Badge>
                          <Badge compact>가중 {(item.weight * 100).toFixed(0)}%</Badge>
                          <Badge compact>rev {item.revision ?? "—"}</Badge>
                        </div>
                      </div>
                      <div className="rounded-full border border-cyan-500/30 bg-cyan-500/10 px-3 py-1 text-xs text-cyan-100">
                        {item.score.toFixed(1)}
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ) : null}

          <div className="mt-5 grid grid-cols-1 gap-3 md:grid-cols-2">
            {(data.proxyScores ?? []).map((item) => (
              <div key={item.key} className="rounded-xl border border-white/10 bg-black/15 p-4">
                <div className="flex items-center justify-between gap-3">
                  <div>
                    <div className="font-medium text-white">{item.label}</div>
                    <div className="mt-1 text-xs break-words text-[var(--muted)]">{item.detail}</div>
                  </div>
                  <div className="rounded-full border border-cyan-500/30 bg-cyan-500/10 px-3 py-1 text-xs text-cyan-100">
                    {item.score.toFixed(1)}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </section>
      </div>
    </main>
  );
}

function Badge({
  children,
  tone = "neutral",
  compact = false,
}: {
  children: React.ReactNode;
  tone?: "neutral" | "cyan" | "green" | "amber" | "red";
  compact?: boolean;
}) {
  const color = {
    neutral: "border-white/10 bg-white/5 text-white/75",
    cyan: "border-cyan-500/30 bg-cyan-500/10 text-cyan-100",
    green: "border-emerald-500/30 bg-emerald-500/10 text-emerald-100",
    amber: "border-amber-500/30 bg-amber-500/10 text-amber-100",
    red: "border-red-500/30 bg-red-500/10 text-red-100",
  }[tone];
  return (
    <span className={`rounded-full border ${color} ${compact ? "px-1.5 py-0.5 text-[9px]" : "px-3 py-1 text-xs"}`}>
      {children}
    </span>
  );
}

function signed(value: number) {
  return `${value >= 0 ? "+" : ""}${value}`;
}

function qualityLabel(value?: string) {
  return {
    OFFICIAL_PRIMARY: "A · 공식원문",
    VERIFIED_API: "A- · 검증 API",
    PUBLIC_API: "B+ · 공개 API",
    PUBLIC_FEED: "B · 공개 feed",
    HTML_PROXY: "C · HTML proxy",
    LEGACY_UNKNOWN: "C- · legacy",
  }[value ?? ""] ?? value ?? "미분류";
}

function statusLabel(value?: string) {
  return {
    HEALTHY: "정상",
    DEGRADED: "일부 결측",
    UNAVAILABLE: "미수집",
    AVAILABLE: "최신",
    STALE: "마지막 유효값",
    MISSING: "미설정/결측",
    FAILED: "수집 실패",
  }[value ?? ""] ?? value ?? "미수집";
}

function sourceTone(value?: string): "green" | "amber" | "red" {
  if (value === "HEALTHY") return "green";
  if (value === "DEGRADED") return "amber";
  return "red";
}

function sourceStatusTone(value?: string): "green" | "amber" | "red" | "neutral" {
  if (value === "AVAILABLE") return "green";
  if (value === "STALE" || value === "MISSING") return "amber";
  if (value === "FAILED") return "red";
  return "neutral";
}

function formatObservedAt(value?: string | null) {
  if (!value) return "—";
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return value;
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(parsed);
}

function formatSourceValue(value: number | null) {
  if (typeof value !== "number" || !Number.isFinite(value)) return "—";
  return new Intl.NumberFormat("ko-KR", {
    notation: Math.abs(value) >= 10_000 ? "compact" : "standard",
    maximumFractionDigits: 1,
  }).format(value);
}
