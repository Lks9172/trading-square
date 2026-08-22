"use client";

import { useEffect, useState } from "react";

type PolicyTone = "DOVISH" | "NEUTRAL" | "HAWKISH" | "MIXED";

interface PolicyEvidence {
  phrase: string;
  direction: "DOVISH" | "HAWKISH";
  weight: number;
  excerpt: string;
}

interface PolicyDocument {
  id: string;
  source: string;
  title: string;
  type: string;
  publishedAt: string;
  url: string;
  tone: PolicyTone;
  toneScore: number;
  confidence: number;
  summary: string;
  evidence: PolicyEvidence[];
}

interface PolicyIntelligence {
  status: "ready" | "collecting";
  asOf: string;
  tone: PolicyTone;
  toneScore: number;
  confidence: number;
  documentCount: number;
  summary: string;
  documents: PolicyDocument[];
  calibration: {
    sampleCount: number;
    calibratedConfidence: number;
    walkForwardAccuracyPct: number;
    brierScore: number;
    enoughSamples: boolean;
    windowStart: string | null;
    windowEnd: string | null;
    methodology: string;
  };
  methodology: string;
}

export function PolicyIntelligencePanel() {
  const [data, setData] = useState<PolicyIntelligence | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    fetch("/api/policy-intelligence", { signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<PolicyIntelligence>;
      })
      .then(setData)
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === "AbortError") return;
        setFailed(true);
      });
    return () => controller.abort();
  }, []);

  const tone = data?.tone ?? "NEUTRAL";
  return (
    <section className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="text-base sm:text-lg font-semibold">미국 정책 원문 톤</h3>
          <p className="mt-1 text-[11px] sm:text-xs text-[var(--muted)]">Fed·재무부·USTR 공식 원문의 완화/긴축·관세 근거 문구</p>
        </div>
        <div className="text-right">
          <span className={`inline-flex rounded-full border px-2.5 py-1 text-xs font-semibold ${toneClass(tone)}`}>
            {toneLabel(tone)} {signed(data?.toneScore ?? 0)}
          </span>
          <div className="mt-1 text-[10px] text-[var(--muted)]">근거 충족도 {data?.confidence ?? 0}%</div>
        </div>
      </div>

      {failed ? (
        <p className="mt-4 text-xs text-red-300">정책 원문 분석을 불러오지 못했습니다.</p>
      ) : !data ? (
        <p className="mt-4 text-xs text-[var(--muted)]">정책 원문 로딩 중...</p>
      ) : data.documents.length === 0 ? (
        <p className="mt-4 text-xs text-[var(--muted)]">첫 Federal Reserve 원문 수집을 기다리고 있습니다.</p>
      ) : (
        <div className="mt-4 space-y-3">
          <p className="text-sm leading-relaxed">{data.summary}</p>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            <CalibrationStat label="정답 표본" value={`${data.calibration.sampleCount}`} />
            <CalibrationStat label="보정 신뢰도" value={`${data.calibration.calibratedConfidence}%`} />
            <CalibrationStat label="Walk-forward" value={`${data.calibration.walkForwardAccuracyPct.toFixed(1)}%`} />
            <CalibrationStat label="Brier" value={data.calibration.brierScore.toFixed(3)} />
          </div>
          {!data.calibration.enoughSamples ? (
            <p className="text-[10px] text-amber-200">장기 정답 표본이 아직 부족해 보정값은 진단용으로만 표시합니다.</p>
          ) : null}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-2">
            {data.documents.slice(0, 6).map((document) => (
              <details key={document.id} className="rounded-lg border border-[var(--card-border)] bg-[var(--background)]">
                <summary className="min-h-11 cursor-pointer list-none px-3 py-2.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-cyan-400/60">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <div className="line-clamp-2 text-xs font-semibold">{document.title}</div>
                      <div className="mt-1 text-[10px] text-[var(--muted)]">{date(document.publishedAt)} · {document.source} · {typeLabel(document.type)}</div>
                    </div>
                    <span className={`shrink-0 font-mono text-xs ${toneText(document.tone)}`}>
                      {signed(document.toneScore)}
                    </span>
                  </div>
                </summary>
                <div className="border-t border-[var(--card-border)] px-3 pb-3 pt-2">
                  <p className="text-[11px] text-[var(--muted)]">{document.summary}</p>
                  <div className="mt-2 space-y-1.5">
                    {document.evidence.slice(0, 5).map((evidence, index) => (
                      <div key={`${evidence.phrase}-${index}`} className="rounded bg-white/[0.03] px-2 py-1.5 text-[10px]">
                        <div className={evidence.direction === "DOVISH" ? "text-green-400" : "text-red-300"}>
                          {evidence.direction === "DOVISH" ? "완화" : "긴축"} · {evidence.phrase} · 가중 {evidence.weight}
                        </div>
                        {evidence.excerpt ? <p className="mt-1 line-clamp-3 text-[var(--muted)]">{evidence.excerpt}</p> : null}
                      </div>
                    ))}
                  </div>
                  <a
                    href={document.url}
                    target="_blank"
                    rel="noreferrer"
                    className="mt-2 inline-flex min-h-10 cursor-pointer items-center text-[11px] text-cyan-300 hover:text-cyan-200"
                  >
                    공식 원문 보기 ↗
                  </a>
                </div>
              </details>
            ))}
          </div>
        </div>
      )}
      <p className="mt-3 text-[10px] leading-relaxed text-[var(--muted)]">
        {data?.methodology ?? "원문 기반 정성 보조지표이며 가격 방향을 단독으로 예측하지 않습니다."}
      </p>
    </section>
  );
}

function CalibrationStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-white/10 bg-black/15 px-3 py-2 text-center">
      <div className="font-mono text-sm text-white">{value}</div>
      <div className="mt-0.5 text-[10px] text-[var(--muted)]">{label}</div>
    </div>
  );
}

function toneLabel(tone: PolicyTone): string {
  return { DOVISH: "완화적", HAWKISH: "긴축적", MIXED: "혼합", NEUTRAL: "중립" }[tone];
}

function toneClass(tone: PolicyTone): string {
  return {
    DOVISH: "border-green-500/40 bg-green-500/10 text-green-400",
    HAWKISH: "border-red-500/40 bg-red-500/10 text-red-300",
    MIXED: "border-amber-500/40 bg-amber-500/10 text-amber-300",
    NEUTRAL: "border-slate-500/40 bg-slate-500/10 text-slate-300",
  }[tone];
}

function toneText(tone: PolicyTone): string {
  if (tone === "DOVISH") return "text-green-400";
  if (tone === "HAWKISH") return "text-red-300";
  if (tone === "MIXED") return "text-amber-300";
  return "text-[var(--muted)]";
}

function signed(value: number): string {
  return `${value > 0 ? "+" : ""}${value}`;
}

function date(value: string): string {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleDateString("ko-KR");
}

function typeLabel(value: string): string {
  return {
    FOMC_STATEMENT: "FOMC 성명",
    FOMC_MINUTES: "FOMC 의사록",
    ECONOMIC_PROJECTIONS: "경제전망",
    DISCOUNT_RATE_MINUTES: "할인율 의사록",
    TREASURY_RELEASE: "재무부 발표",
    TARIFF_ACTION: "관세·통상 조치",
    OTHER: "통화정책 자료",
  }[value] ?? value;
}
