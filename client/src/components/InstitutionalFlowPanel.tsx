"use client";

import { useEffect, useState } from "react";

interface PositionFlow {
  cusip: string;
  issuer: string;
  titleClass: string;
  putCall: string;
  currentValueUsd: number;
  valueDeltaUsd: number;
  valueDeltaPct: number | null;
  shareDelta: number;
  shareDeltaPct: number | null;
  estimatedNetFlowUsd: number;
  action: "NEW" | "INCREASE" | "UNCHANGED" | "REDUCE" | "EXIT";
  identity: SecurityIdentity | null;
}

interface SecurityIdentity {
  ticker: string;
  cik: string;
  sectorKey: string;
  confidence: number;
  source: string;
}

interface ManagerFlow {
  id: string;
  name: string;
  reportPeriod: string;
  previousReportPeriod: string | null;
  filedOn: string;
  sourceUrl: string;
  holdingCount: number;
  totalValueUsd: number;
  netValueDeltaUsd: number;
  estimatedNetFlowUsd: number;
  newPositions: number;
  increasedPositions: number;
  reducedPositions: number;
  exitedPositions: number;
  topBuys: PositionFlow[];
  topSells: PositionFlow[];
}

interface ConsensusFlow {
  cusip: string;
  issuer: string;
  titleClass: string;
  managerCount: number;
  managers: string[];
  totalValueUsd: number;
  netValueDeltaUsd: number;
  estimatedNetFlowUsd: number;
  identity: SecurityIdentity | null;
}

interface DivergenceFlow {
  ticker: string;
  issuer: string;
  sectorKey: string;
  analystScore: number;
  institutionalFlowScore: number;
  divergenceScore: number;
  managerCount: number;
  aggregateShareDeltaPct: number;
  signal: "ANALYSTS_AHEAD_OF_MONEY" | "MONEY_AHEAD_OF_ANALYSTS" | "ALIGNED";
}

interface InstitutionalFlowData {
  status: "ready" | "collecting";
  asOf: string | null;
  source: string;
  managerCount: number;
  sharedPositionCount: number;
  mappedPositionCount: number;
  unmappedPositionCount: number;
  managers: ManagerFlow[];
  consensus: ConsensusFlow[];
  divergences: DivergenceFlow[];
  methodology: string;
}

export function InstitutionalFlowPanel() {
  const [data, setData] = useState<InstitutionalFlowData | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    fetch("/api/institutional-flows", { signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<InstitutionalFlowData>;
      })
      .then((value) => setData(value))
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === "AbortError") return;
        setFailed(true);
      });
    return () => controller.abort();
  }, []);

  return (
    <section className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h3 className="text-base sm:text-lg font-semibold">기관 13F 실제 베팅</h3>
          <p className="mt-1 text-[11px] sm:text-xs text-[var(--muted)]">
            SEC 13F-HR 원문 · 최신 두 분기 CUSIP별 보고 수량 변화
          </p>
        </div>
        <span className={`rounded-full border px-2 py-1 text-[10px] font-semibold ${
          data?.status === "ready"
            ? "border-green-500/40 text-green-400"
            : "border-amber-500/40 text-amber-300"
        }`}>
          {data?.status === "ready" ? `${data.managerCount}개 기관 반영` : "수집 중"}
        </span>
      </div>

      {failed ? (
        <p className="mt-4 text-xs text-red-300">13F 데이터를 불러오지 못했습니다. 다음 자동 수집 때 재시도합니다.</p>
      ) : !data ? (
        <p className="mt-4 text-xs text-[var(--muted)]">13F 데이터 로딩 중...</p>
      ) : data.managers.length === 0 ? (
        <p className="mt-4 text-xs text-[var(--muted)]">첫 SEC 13F 원문 수집을 기다리고 있습니다.</p>
      ) : (
        <div className="mt-4 space-y-4">
          {data.consensus.length > 0 ? (
            <div>
              <div className="mb-2 flex items-center justify-between gap-2">
                <h4 className="text-sm font-semibold">기관 공통 보유·변화</h4>
                <span className="text-[10px] text-[var(--muted)]">공통 {data.sharedPositionCount}종목</span>
              </div>
              <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-2">
                {data.consensus.slice(0, 6).map((item) => (
                  <div key={`${item.cusip}-${item.titleClass}`} className="rounded-lg border border-[var(--card-border)] bg-[var(--background)] px-3 py-2">
                    <div className="flex items-start justify-between gap-2">
                      <span className="truncate text-xs font-semibold" title={item.issuer}>
                        {item.identity?.ticker ? `${item.identity.ticker} · ` : ""}{item.issuer}
                      </span>
                      <span className="shrink-0 text-[10px] text-sky-300">{item.managerCount}개 기관</span>
                    </div>
                    <div className="mt-1 flex justify-between text-[10px] text-[var(--muted)]">
                      <span>{money(item.totalValueUsd)}</span>
                      <Delta value={item.estimatedNetFlowUsd} />
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ) : null}

          {data.divergences.length > 0 ? (
            <div>
              <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
                <h4 className="text-sm font-semibold">애널리스트 전망 ↔ 실제 기관 수량 괴리</h4>
                <span className="text-[10px] text-[var(--muted)]">
                  CUSIP 매핑 {data.mappedPositionCount} · 미매핑 {data.unmappedPositionCount}
                </span>
              </div>
              <div className="grid grid-cols-1 gap-2 md:grid-cols-2 xl:grid-cols-3">
                {data.divergences.slice(0, 9).map((item) => (
                  <div key={item.ticker} className="rounded-lg border border-[var(--card-border)] bg-[var(--background)] px-3 py-2">
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <div className="text-xs font-semibold">{item.ticker} · {item.issuer}</div>
                        <div className="mt-0.5 text-[10px] text-[var(--muted)]">{item.sectorKey} · {item.managerCount}개 기관</div>
                      </div>
                      <span className={`shrink-0 rounded-full border px-2 py-0.5 text-[9px] ${divergenceClass(item.signal)}`}>
                        {divergenceLabel(item.signal)}
                      </span>
                    </div>
                    <div className="mt-2 grid grid-cols-3 gap-1 text-center text-[10px]">
                      <MiniScore label="애널리스트" value={item.analystScore} />
                      <MiniScore label="기관 flow" value={item.institutionalFlowScore} />
                      <MiniScore label="괴리" value={item.divergenceScore} />
                    </div>
                  </div>
                ))}
              </div>
            </div>
          ) : null}

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-2">
            {data.managers.map((manager) => (
              <details key={manager.id} className="group rounded-lg border border-[var(--card-border)] bg-[var(--background)]">
                <summary className="min-h-11 cursor-pointer list-none px-3 py-2.5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-cyan-400/60">
                  <div className="flex items-center justify-between gap-2">
                    <div className="min-w-0">
                      <div className="truncate text-sm font-semibold">{manager.name}</div>
                      <div className="text-[10px] text-[var(--muted)]">
                        기준 {manager.reportPeriod} · {manager.holdingCount}종목
                      </div>
                    </div>
                    <div className="shrink-0 text-right">
                      <Delta value={manager.estimatedNetFlowUsd} />
                      <div className="text-[10px] text-[var(--muted)]">추정 순매매</div>
                    </div>
                  </div>
                </summary>
                <div className="border-t border-[var(--card-border)] px-3 pb-3 pt-2 text-xs">
                  <div className="mb-3 grid grid-cols-4 gap-1 text-center text-[10px]">
                    <Stat label="신규" value={manager.newPositions} positive />
                    <Stat label="확대" value={manager.increasedPositions} positive />
                    <Stat label="축소" value={manager.reducedPositions} />
                    <Stat label="청산" value={manager.exitedPositions} />
                  </div>
                  <PositionList title="상위 매수·확대" positions={manager.topBuys.slice(0, 5)} />
                  <PositionList title="상위 축소·청산" positions={manager.topSells.slice(0, 5)} />
                  <a
                    href={manager.sourceUrl}
                    target="_blank"
                    rel="noreferrer"
                    className="mt-2 inline-flex min-h-10 cursor-pointer items-center text-[11px] text-cyan-300 hover:text-cyan-200"
                  >
                    SEC 원문 보기 ↗
                  </a>
                </div>
              </details>
            ))}
          </div>
        </div>
      )}

      <p className="mt-3 text-[10px] leading-relaxed text-[var(--muted)]">
        {data?.methodology ?? "13F는 분기말 이후 지연 공시이므로 실시간 매매 신호가 아니라 실제 자금 배치의 확인 지표입니다."}
      </p>
    </section>
  );
}

function PositionList({ title, positions }: { title: string; positions: PositionFlow[] }) {
  if (positions.length === 0) return null;
  return (
    <div className="mb-2">
      <div className="mb-1 text-[10px] font-semibold text-[var(--muted)]">{title}</div>
      <div className="space-y-1">
        {positions.map((position) => (
          <div key={`${position.cusip}-${position.putCall}-${position.action}`} className="flex items-center justify-between gap-2">
            <span className="truncate" title={position.issuer}>
              {position.identity?.ticker ? `${position.identity.ticker} · ` : ""}{position.issuer}
            </span>
            <div className="shrink-0 text-right">
              <Delta value={position.estimatedNetFlowUsd} />
              <div className="text-[9px] text-[var(--muted)]">
                수량 {position.shareDeltaPct == null
                  ? "신규"
                  : `${position.shareDeltaPct > 0 ? "+" : ""}${position.shareDeltaPct.toFixed(1)}%`}
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function MiniScore({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded bg-white/[0.03] px-1 py-1">
      <div className="font-mono text-white">{Math.round(value)}</div>
      <div className="text-[var(--muted)]">{label}</div>
    </div>
  );
}

function divergenceLabel(value: DivergenceFlow["signal"]): string {
  return {
    ANALYSTS_AHEAD_OF_MONEY: "전망 선행",
    MONEY_AHEAD_OF_ANALYSTS: "자금 선행",
    ALIGNED: "정렬",
  }[value];
}

function divergenceClass(value: DivergenceFlow["signal"]): string {
  return value === "ALIGNED"
    ? "border-green-500/30 bg-green-500/10 text-green-300"
    : "border-amber-500/30 bg-amber-500/10 text-amber-200";
}

function Stat({ label, value, positive = false }: { label: string; value: number; positive?: boolean }) {
  return (
    <div className="rounded bg-white/[0.03] px-1 py-1.5">
      <div className={positive ? "font-mono text-green-400" : "font-mono text-red-300"}>{value}</div>
      <div className="text-[var(--muted)]">{label}</div>
    </div>
  );
}

function Delta({ value }: { value: number }) {
  return (
    <span className={`whitespace-nowrap font-mono text-[11px] ${
      value > 0 ? "text-green-400" : value < 0 ? "text-red-300" : "text-[var(--muted)]"
    }`}>
      {value > 0 ? "+" : ""}{money(value)}
    </span>
  );
}

function money(value: number): string {
  const absolute = Math.abs(value);
  if (absolute >= 1_000_000_000) return `${(value / 1_000_000_000).toFixed(1)}B`;
  if (absolute >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (absolute >= 1_000) return `${(value / 1_000).toFixed(1)}K`;
  return value.toFixed(0);
}
