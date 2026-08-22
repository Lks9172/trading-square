"use client";

import { ScoreBadge } from "./ScoreUI";
import { InfoTooltip } from "./InfoTooltip";

type GateStatus = 'ON' | 'RECENT' | 'OFF';

type GateRow = {
  signalDate: string;
  oneMonthReturn: number | null;
  twoMonthReturn: number | null;
  threeMonthReturn: number | null;
};

type GateMarket = {
  asset: 'NASDAQ' | 'SP500';
  label: string;
  mode: '실전 개선형';
  status: GateStatus;
  active: boolean;
  signalDate: string | null;
  daysSinceSignal: number | null;
  perYear: number;
  avg1m: number | null;
  avg2m: number | null;
  avg3m: number | null;
  win1m: number | null;
  win2m: number | null;
  win3m: number | null;
  shortThreshold: number;
  mediumOversoldThreshold: number;
  mediumRecoveryFloor: number;
  lookbackDays: number;
  regimeFilter: string;
  summary: string;
  recentSignals: GateRow[];
};

type GateSnapshot = {
  updatedAt: string;
  mode: '실전 개선형';
  summary: string;
  markets: GateMarket[];
};

function statusTone(status: GateStatus) {
  switch (status) {
    case 'ON':
      return 'border-emerald-500/30 bg-emerald-500/10 text-emerald-200';
    case 'RECENT':
      return 'border-cyan-500/30 bg-cyan-500/10 text-cyan-200';
    default:
      return 'border-white/10 bg-white/5 text-white/70';
  }
}

function formatPct(value: number | null | undefined) {
  return typeof value === 'number' ? `${value >= 0 ? '+' : ''}${value.toFixed(2)}%` : '—';
}

function formatDays(value: number | null | undefined) {
  return typeof value === 'number' ? `${value}일 전` : '—';
}

export function MarketBreadthGatePanel({ gate }: { gate?: GateSnapshot | null }) {
  if (!gate?.markets?.length) return null;

  return (
    <section className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <div className="mb-3 flex items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <h3 className="text-base sm:text-lg font-semibold">시장 Breadth 실전 게이트</h3>
          <InfoTooltip
            title="시장 Breadth 실전 게이트"
            description="개별 회사 바닥 점수와 별개로, 지금이 시장 전체에서 종목을 고를 만한 반전 구간인지 보는 상위 필터입니다. 하락장 초중반 반등은 최대한 제외하도록 200일선 추세 필터를 함께 사용합니다."
            frequency="6시간 캐시"
            source="Yahoo + breadth proxy"
          />
        </div>
        <div className="text-[11px] text-[var(--muted)]">{gate.summary}</div>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
        {gate.markets.map((market) => (
          <div key={market.asset} className="rounded-xl border border-white/10 bg-black/15 p-4">
            <div className="flex items-start justify-between gap-3">
              <div>
                <div className="font-medium text-white">{market.label}</div>
                <div className="mt-1 text-[11px] text-[var(--muted)]">
                  최근 5년 {market.recentSignals.length}회 · 연 {market.perYear.toFixed(1)}회
                </div>
              </div>
              <span className={`rounded-full border px-2 py-1 text-[10px] ${statusTone(market.status)}`}>
                {market.status}
              </span>
            </div>

            <p className="mt-3 text-[12px] leading-relaxed text-white/85">{market.summary}</p>

            <div className="mt-3 flex flex-wrap gap-2 text-[11px]">
              <ScoreBadge label="1M 평균" value={market.avg1m} title="신호 후 1개월 평균 수익률입니다." kind="buy" interactive={false} />
              <ScoreBadge label="2M 평균" value={market.avg2m} title="신호 후 2개월 평균 수익률입니다." kind="buy" interactive={false} />
              <ScoreBadge label="3M 평균" value={market.avg3m} title="신호 후 3개월 평균 수익률입니다." kind="buy" interactive={false} />
            </div>
            <div className="mt-2 flex flex-wrap gap-2 text-[11px] text-[var(--muted)]">
              <span>최근 신호 {market.signalDate ?? '—'}</span>
              <span>·</span>
              <span>{formatDays(market.daysSinceSignal)}</span>
              <span>·</span>
              <span>승률 1/2/3M {formatPct(market.win1m)} / {formatPct(market.win2m)} / {formatPct(market.win3m)}</span>
            </div>
            <div className="mt-2 text-[11px] text-[var(--muted)]">
              단기선 {market.shortThreshold} · 중기 과매도 {market.mediumOversoldThreshold} · 중기 회복 {market.mediumRecoveryFloor} · 추세 {market.regimeFilter}
            </div>

            <div className="mt-4 overflow-x-auto">
              <table className="min-w-full text-[11px]">
                <thead className="text-[var(--muted)]">
                  <tr className="border-b border-white/10">
                    <th className="px-2 py-2 text-left font-medium">신호일</th>
                    <th className="px-2 py-2 text-right font-medium">1M</th>
                    <th className="px-2 py-2 text-right font-medium">2M</th>
                    <th className="px-2 py-2 text-right font-medium">3M</th>
                  </tr>
                </thead>
                <tbody>
                  {market.recentSignals.map((row) => (
                    <tr key={`${market.asset}-${row.signalDate}`} className="border-b border-white/5 last:border-b-0">
                      <td className="px-2 py-2 text-white">{row.signalDate}</td>
                      <td className="px-2 py-2 text-right text-white/85">{formatPct(row.oneMonthReturn)}</td>
                      <td className="px-2 py-2 text-right text-white/85">{formatPct(row.twoMonthReturn)}</td>
                      <td className="px-2 py-2 text-right text-white/85">{formatPct(row.threeMonthReturn)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        ))}
      </div>
    </section>
  );
}
