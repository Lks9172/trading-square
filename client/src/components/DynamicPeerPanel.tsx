"use client";

import { useEffect, useState } from "react";
import { SmartLink } from "./SmartLink";

interface DynamicPeerData {
  status: "ready" | "collecting";
  asOf: string;
  target: null | {
    ticker: string;
    companyName: string;
    sic: number;
    sicDescription: string;
    sectorKey: string;
    validFrom: string;
    validTo: string | null;
  };
  candidateCount: number;
  peers: Array<{
    ticker: string;
    companyName: string;
    sic: number;
    sicDescription: string;
    sectorKey: string;
    similarityScore: number;
    matchLevel: string;
  }>;
  methodology: string;
}

export function DynamicPeerPanel({ ticker }: { ticker: string }) {
  const [data, setData] = useState<DynamicPeerData | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    fetch(`/api/research/peers/${encodeURIComponent(ticker)}`, { signal: controller.signal })
      .then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json() as Promise<DynamicPeerData>;
      })
      .then(setData)
      .catch((error: unknown) => {
        if (error instanceof DOMException && error.name === "AbortError") return;
        setFailed(true);
      });
    return () => controller.abort();
  }, [ticker]);

  return (
    <section className="rounded-2xl border border-[var(--card-border)] bg-[var(--card)] p-5">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <div className="text-lg font-semibold">SEC SIC 동적 Peer</div>
          <div className="mt-1 text-xs text-[var(--muted)]">
            정적 테마가 아닌 SEC 산업분류와 as-of 생존 종목 기준
          </div>
        </div>
        {data?.target ? (
          <span className="rounded-full border border-cyan-500/30 bg-cyan-500/10 px-3 py-1 text-xs text-cyan-100">
            SIC {data.target.sic} · 후보 {data.candidateCount}
          </span>
        ) : null}
      </div>
      {failed ? (
        <p className="mt-4 text-sm text-red-300">동적 peer 데이터를 불러오지 못했습니다.</p>
      ) : !data ? (
        <p className="mt-4 text-sm text-[var(--muted)]">SIC peer 로딩 중...</p>
      ) : data.status === "collecting" ? (
        <p className="mt-4 text-sm text-[var(--muted)]">이 종목의 SEC SIC taxonomy를 우선 수집 중입니다.</p>
      ) : (
        <>
          <p className="mt-3 text-xs text-[var(--muted)]">
            {data.target?.sicDescription} · 유효 시작 {data.target?.validFrom}
          </p>
          <div className="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2 xl:grid-cols-4">
            {data.peers.slice(0, 12).map((peer) => (
              <SmartLink
                key={peer.ticker}
                href={`/company/${peer.ticker}`}
                prefetch={false}
                className="cursor-pointer rounded-xl border border-white/10 bg-black/15 p-3 hover:bg-white/5"
              >
                <div className="flex items-start justify-between gap-2">
                  <div className="font-semibold text-white">{peer.ticker}</div>
                  <div className="font-mono text-xs text-cyan-200">{peer.similarityScore}</div>
                </div>
                <div className="mt-1 line-clamp-2 text-xs text-[var(--muted)]">{peer.companyName}</div>
                <div className="mt-2 text-[10px] text-cyan-200">{matchLabel(peer.matchLevel)}</div>
              </SmartLink>
            ))}
          </div>
        </>
      )}
      <p className="mt-3 text-[10px] leading-relaxed text-[var(--muted)]">
        {data?.methodology ?? "SEC taxonomy를 점진적으로 백필하고 SIC 변경·상장 유니버스 이탈을 유효기간으로 관리합니다."}
      </p>
    </section>
  );
}

function matchLabel(value: string): string {
  return {
    EXACT_SIC: "동일 SIC",
    SIC_INDUSTRY_GROUP: "동일 산업그룹",
    SIC_MAJOR_GROUP: "동일 대분류",
    STANDARD_SECTOR: "동일 표준 섹터",
  }[value] ?? value;
}
