"use client";

import { useEffect, useState } from "react";

interface CorrelationResult {
  lookbackDays: number;
  assets: string[];
  matrix: (number | null)[][];
  missing: string[];
  asOf: string;
}

interface RawPoint {
  value: number;
  date: string;
}

interface DerivedPoint {
  value: number;
  formula: string;
}

interface Props {
  raw?: Record<string, RawPoint>;
  derived?: Record<string, DerivedPoint>;
}

const ASSET_LABEL: Record<string, string> = {
  NASDAQ: "나스닥",
  SP500: "S&P500",
  GOLD: "금",
  SILVER: "은",
  COPPER: "구리",
  WTI: "WTI",
  DXY: "달러인덱스",
  USDKRW: "원/달러",
  KOSPI: "코스피",
  DGS10: "10Y 금리",
  VIXCLS: "VIX",
};

function corrColor(r: number | null): string {
  if (r === null) return "var(--card-border)";
  const abs = Math.abs(r);
  // 빨강(양의 상관) / 파랑(음의 상관) / 무채(낮음)
  if (r > 0) return `rgba(239, 68, 68, ${0.15 + abs * 0.85})`;
  return `rgba(59, 130, 246, ${0.15 + abs * 0.85})`;
}

function fmt(v: number | null | undefined, digits = 1): string {
  if (v === null || v === undefined || Number.isNaN(v)) return "—";
  return v.toFixed(digits);
}

export function OptionsVolatilityPanel({ raw, derived }: Props) {
  const [corr, setCorr] = useState<CorrelationResult | null>(null);
  const [lookback, setLookback] = useState<number>(60);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setLoading(true);
    fetch(`/api/correlation?lookback=${lookback}`)
      .then((r) => r.json())
      .then((d) => setCorr(d))
      .catch(() => setCorr(null))
      .finally(() => setLoading(false));
  }, [lookback]);

  const skew = raw?.SKEW?.value;
  const vvix = raw?.VVIX?.value;
  const ovx = raw?.OVX?.value;
  const sofrIorb = derived?.SOFR_IORB_SPREAD?.value;
  const sofrEffr = derived?.SOFR_EFFR_SPREAD?.value;

  return (
    <div className="space-y-4 sm:space-y-6">
      <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
        <h3 className="text-base sm:text-lg font-semibold mb-1">옵션·변동성·자금시장</h3>
        <p className="text-[11px] sm:text-xs text-[var(--muted)] mb-4">
          꼬리위험·변동성의 변동성·에너지 변동성·은행간 유동성 스트레스 지표
        </p>

        <div className="grid grid-cols-2 sm:grid-cols-5 gap-3 sm:gap-4">
          <MetricCard
            label="SKEW"
            value={fmt(skew, 1)}
            desc="꼬리위험 (100 기준, ↑=대형 급락 헤지 수요↑)"
            alert={typeof skew === "number" && skew > 145}
          />
          <MetricCard
            label="VVIX"
            value={fmt(vvix, 1)}
            desc="VIX 의 변동성 (80~100 정상, ↑=변동성 레짐 변화 선행)"
            alert={typeof vvix === "number" && vvix > 110}
          />
          <MetricCard
            label="OVX"
            value={fmt(ovx, 1)}
            desc="유가 변동성 (30~40 정상, ↑=에너지·지정학 쇼크)"
            alert={typeof ovx === "number" && ovx > 60}
          />
          <MetricCard
            label="SOFR-IORB"
            value={fmt(sofrIorb, 3)}
            desc="양수=지급준비금 부족/자금시장 긴장"
            alert={typeof sofrIorb === "number" && sofrIorb > 0.05}
          />
          <MetricCard
            label="SOFR-EFFR"
            value={fmt(sofrEffr, 3)}
            desc="담보/무담보 스프레드 (양수=repo 긴장)"
            alert={typeof sofrEffr === "number" && sofrEffr > 0.05}
          />
        </div>
      </div>

      <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
        <div className="flex flex-wrap items-center justify-between gap-2 mb-3">
          <div>
            <h3 className="text-base sm:text-lg font-semibold">자산 간 상관관계 히트맵</h3>
            <p className="text-[11px] sm:text-xs text-[var(--muted)]">
              로그수익률 기준 Pearson 상관계수. 분산투자 품질 점검
            </p>
          </div>
          <div className="flex gap-1">
            {[30, 60, 120, 250].map((n) => (
              <button
                key={n}
                onClick={() => setLookback(n)}
                className={`px-2 py-1 text-xs rounded border ${
                  lookback === n
                    ? "bg-[var(--accent)] text-white border-[var(--accent)]"
                    : "border-[var(--card-border)] text-[var(--muted)] hover:text-[var(--foreground)]"
                }`}
              >
                {n}d
              </button>
            ))}
          </div>
        </div>

        {loading && <div className="text-xs text-[var(--muted)]">로딩 중...</div>}
        {!loading && corr && corr.assets.length > 0 && (
          <div className="overflow-x-auto">
            <table className="text-[10px] sm:text-xs font-mono border-collapse">
              <thead>
                <tr>
                  <th className="p-1.5 text-left"></th>
                  {corr.assets.map((a) => (
                    <th key={a} className="p-1.5 text-[10px] text-[var(--muted)] -rotate-45 h-16 align-bottom whitespace-nowrap">
                      {ASSET_LABEL[a] || a}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {corr.assets.map((rowA, i) => (
                  <tr key={rowA}>
                    <td className="p-1.5 text-[var(--muted)] text-left whitespace-nowrap">
                      {ASSET_LABEL[rowA] || rowA}
                    </td>
                    {corr.assets.map((colA, j) => {
                      const r = corr.matrix[i][j];
                      return (
                        <td
                          key={colA}
                          className="p-1.5 text-center min-w-[42px]"
                          style={{ backgroundColor: corrColor(r), color: r !== null && Math.abs(r) > 0.5 ? "white" : undefined }}
                          title={`${rowA} vs ${colA}: ${r ?? "n/a"}`}
                        >
                          {r === null ? "—" : r.toFixed(2)}
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="mt-2 text-[10px] text-[var(--muted)]">
              기준일: {corr.asOf} · 데이터 부족 제외: {corr.missing.length > 0 ? corr.missing.join(", ") : "없음"}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function MetricCard({
  label,
  value,
  desc,
  alert,
}: {
  label: string;
  value: string;
  desc: string;
  alert?: boolean;
}) {
  return (
    <div
      className={`rounded-lg border px-3 py-2.5 ${
        alert
          ? "border-red-500/50 bg-red-500/5"
          : "border-[var(--card-border)] bg-[var(--background)]"
      }`}
    >
      <div className="flex items-center justify-between">
        <span className="text-[10px] sm:text-xs text-[var(--muted)]">{label}</span>
        {alert && <span className="text-[10px] text-red-400">⚠</span>}
      </div>
      <div className={`text-base sm:text-lg font-mono font-bold ${alert ? "text-red-400" : ""}`}>{value}</div>
      <div className="text-[9px] sm:text-[10px] text-[var(--muted)] leading-tight mt-1">{desc}</div>
    </div>
  );
}
