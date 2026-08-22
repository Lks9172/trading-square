"use client";

import { useEffect, useState } from "react";

const COMPANIES = [
  ["005930", "삼성전자"], ["000660", "SK하이닉스"], ["035420", "NAVER"],
  ["035720", "카카오"], ["005380", "현대차"],
] as const;

interface DartData {
  status: "ready" | "collecting" | "disabled" | "unavailable" | "stale";
  asOf: string | null;
  company: null | { stockCode: string; corpName: string };
  disclosures: Array<{
    receiptNumber: string;
    reportName: string;
    receivedOn: string;
    eventType: string;
    sourceUrl: string;
  }>;
  financials: Array<{
    businessYear: number;
    reportCode: string;
    statementCode: string;
    accountId: string;
    accountName: string;
    currentAmount: number | null;
    currency: string;
  }>;
  methodology: string;
}

export function DartDisclosurePanel() {
  const [stockCode, setStockCode] = useState("005930");
  const [data, setData] = useState<DartData | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    fetch(`/api/dart/disclosures/${stockCode}`, { signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<DartData>;
      })
      .then(setData)
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === "AbortError") return;
        setFailed(true);
      });
    return () => controller.abort();
  }, [stockCode]);

  const keyFinancials = (data?.financials ?? []).filter((item) =>
    /매출|영업이익|당기순이익|자산총계|부채총계/.test(item.accountName),
  ).slice(0, 6);

  return (
    <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="text-sm font-semibold text-white">한국 OpenDART 중대공시·재무</div>
          <div className="mt-1 text-xs text-[var(--muted)]">금융감독원 공식 API 원문 기반</div>
        </div>
        <div className="flex flex-wrap gap-1">
          {COMPANIES.map(([code, name]) => (
            <button
              key={code}
              type="button"
              onClick={() => {
                if (code === stockCode) return;
                setFailed(false);
                setData(null);
                setStockCode(code);
              }}
              className={`min-h-10 cursor-pointer rounded-full border px-3 text-xs ${
                stockCode === code
                  ? "border-cyan-500/40 bg-cyan-500/15 text-cyan-100"
                  : "border-white/10 bg-white/5 text-white/70 hover:bg-white/10"
              }`}
            >
              {name}
            </button>
          ))}
        </div>
      </div>
      {failed ? (
        <p className="mt-4 text-sm text-red-300">DART 데이터를 불러오지 못했습니다.</p>
      ) : !data ? (
        <p className="mt-4 text-sm text-[var(--muted)]">DART 로딩 중...</p>
      ) : data.status === "collecting" ? (
        <p className="mt-4 text-sm text-[var(--muted)]">OpenDART 첫 수집을 기다리고 있습니다.</p>
      ) : (data.status === "disabled" || data.status === "unavailable") && !data.company ? (
        <div className="mt-4 rounded-lg border border-amber-500/25 bg-amber-500/10 p-3 text-sm text-amber-100">
          {data.status === "disabled"
            ? "OpenDART 연동이 비활성화되어 한국 공시 데이터는 점수에서 제외됩니다."
            : "OpenDART API 키가 없어 한국 공시 데이터는 결측·가중치 0으로 처리됩니다."}
        </div>
      ) : (
        <div className="mt-4">
          {data.status === "stale" && (
            <div className="mb-3 rounded-lg border border-amber-500/25 bg-amber-500/10 p-2 text-xs text-amber-100">
              마지막 정상값만 참고용으로 표시합니다. 현재 수집이 비활성/결측이라 신규 판단에는 사용하지 않습니다.
            </div>
          )}
          <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
            <div>
              <div className="mb-2 text-xs font-semibold text-white/80">최근 중대공시</div>
              <div className="space-y-2">
                {data.disclosures.slice(0, 6).map((item) => (
                  <a key={item.receiptNumber} href={item.sourceUrl} target="_blank" rel="noreferrer"
                    className="block min-h-11 cursor-pointer rounded-lg border border-white/10 bg-black/15 p-2 hover:bg-white/5">
                    <div className="line-clamp-2 text-xs text-white">{item.reportName}</div>
                    <div className="mt-1 text-[10px] text-[var(--muted)]">{item.receivedOn} · {eventLabel(item.eventType)}</div>
                  </a>
                ))}
              </div>
            </div>
            <div>
              <div className="mb-2 text-xs font-semibold text-white/80">최근 연결재무 핵심 계정</div>
              <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
                {keyFinancials.map((item) => (
                  <div key={`${item.statementCode}-${item.accountId}`} className="rounded-lg border border-white/10 bg-black/15 p-2">
                    <div className="line-clamp-1 text-[10px] text-[var(--muted)]">{item.accountName}</div>
                    <div className="mt-1 font-mono text-xs text-white">{money(item.currentAmount)}</div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}
      <p className="mt-3 text-[10px] text-[var(--muted)]">{data?.methodology}</p>
    </section>
  );
}

function eventLabel(value: string): string {
  return {
    MERGER_ACQUISITION: "M&A/사업재편", EXECUTIVE_CHANGE: "경영진 변경",
    CAPITAL_ACTION: "자본 조달/환원", LITIGATION: "소송/제재",
    RESTRUCTURING: "구조조정", EARNINGS: "실적/정기보고", OTHER: "기타",
  }[value] ?? value;
}

function money(value: number | null): string {
  if (value == null || !Number.isFinite(value)) return "—";
  const absolute = Math.abs(value);
  if (absolute >= 1e12) return `${(value / 1e12).toFixed(1)}조`;
  if (absolute >= 1e8) return `${(value / 1e8).toFixed(0)}억`;
  return value.toLocaleString("ko-KR");
}
