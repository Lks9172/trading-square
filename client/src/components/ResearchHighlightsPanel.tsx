"use client";
import { SmartLink } from "@/components/SmartLink";

import { useEffect, useState } from 'react';
import { ActionBadge, ScoreBadge } from './ScoreUI';

type SectorItem = {
  key: string;
  label: string;
  themeId?: string | null;
  buyScore: number | null;
  buyLabel: string | null;
  qualityScore: number | null;
  stance: string;
  buyScoreDelta7d?: number | null;
  buyScoreDelta30d?: number | null;
};

type CompanyItem = {
  ticker: string;
  name: string;
  buyScore: number | null;
  buyLabel: string | null;
  totalScore: number | null;
  revenueGrowthYoY: number | null;
  estimateRevision7d?: number | null;
  estimateRevision30d?: number | null;
  companyAction?: string | null;
  companyActionLabel?: string | null;
  linkedAsset?: string | null;
  assetAction?: string | null;
  executionAlignment?: 'aligned' | 'mixed' | 'conflicted' | null;
  recommendationSummary?: string | null;
  bottomState?: string | null;
  earningsBottomScore?: number | null;
  priceBottomScore?: number | null;
  volumeConfirmationScore?: number | null;
  bottomFailureRiskScore?: number | null;
};

type HighlightsResponse = {
  sectors?: SectorItem[];
  companies?: CompanyItem[];
};

function formatPct(value: number | null) {
  return value === null ? '—' : `${value.toFixed(1)}%`;
}

function highlightVerdict(value: number | null, kind: 'sector' | 'company') {
  if (typeof value !== 'number') return kind === 'sector' ? '데이터가 더 필요합니다.' : '기초 데이터 확인이 더 필요합니다.';
  if (value >= 80) return kind === 'sector' ? '섹터 강도와 타이밍이 모두 좋은 편입니다.' : '기업 상태와 타이밍이 모두 우호적입니다.';
  if (value >= 70) return kind === 'sector' ? '매수 가능한 섹터입니다. 분할 접근이 무난합니다.' : '매수 가능한 기업입니다. 분할 접근이 무난합니다.';
  if (value >= 55) return kind === 'sector' ? '나쁘지 않지만, 지금은 선별 접근이 좋습니다.' : '좋은 편이지만, 서두르기보다 관찰이 낫습니다.';
  if (value >= 40) return kind === 'sector' ? '과열 또는 매력 약화로 축소 관점입니다.' : '가격 부담이 있어 축소 관점이 우선입니다.';
  return kind === 'sector' ? '회피가 더 유리한 구간입니다.' : '매수보다 회피·정리가 유리합니다.';
}

export function ResearchHighlightsPanel() {
  const [data, setData] = useState<HighlightsResponse | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetch('/api/research/highlights', { cache: 'no-store' })
      .then((res) => (res.ok ? res.json() : null))
      .then((json) => {
        if (!cancelled) setData(json);
      })
      .catch(() => {
        if (!cancelled) setData(null);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const sectors = data?.sectors ?? [];
  const companies = data?.companies ?? [];
  if (!sectors.length && !companies.length) return null;

  return (
    <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 sm:gap-6">
      <section className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="text-base sm:text-lg font-semibold">상위 섹터 B 점수</h3>
          <SmartLink href="/research" className="text-xs text-cyan-300 cursor-pointer hover:text-cyan-200">Research 보기</SmartLink>
        </div>
        <div className="space-y-2">
          {sectors.slice(0, 4).map((sector) => (
            <SmartLink key={sector.key} href={sector.themeId ? `/research/theme/${sector.themeId}` : `/research`} className="flex items-center justify-between gap-3 rounded-lg bg-black/15 px-3 py-2 text-sm cursor-pointer hover:bg-white/5 active:scale-[0.99]">
              <div>
                <div className="font-medium text-white">{sector.label}</div>
                <div className="mt-1 flex flex-wrap gap-2 text-[11px] text-[var(--muted)]">
                  <span>{sector.stance}</span>
                  <ScoreBadge label="Q" value={sector.qualityScore} title="Q 높을수록 구조 체력이 좋습니다." kind="quality" interactive={false} />
                  {typeof sector.buyScoreDelta7d === 'number' && <span>7D {sector.buyScoreDelta7d >= 0 ? '+' : ''}{sector.buyScoreDelta7d}</span>}
                  {typeof sector.buyScoreDelta30d === 'number' && <span>30D {sector.buyScoreDelta30d >= 0 ? '+' : ''}{sector.buyScoreDelta30d}</span>}
                </div>
                <div className="mt-1 text-[11px] text-white/80">{highlightVerdict(sector.buyScore, 'sector')}</div>
              </div>
              <div className="flex flex-col items-end gap-2">
                <ScoreBadge label="B" value={sector.buyScore} title="B는 지금 사도 되는지 보는 실행 점수입니다." kind="buy" interactive={false} />
                <ActionBadge value={sector.buyScore} interactive={false} />
              </div>
            </SmartLink>
          ))}
        </div>
      </section>

      <section className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="text-base sm:text-lg font-semibold">상위 기업 B 점수</h3>
          <SmartLink href="/research" className="text-xs text-cyan-300 cursor-pointer hover:text-cyan-200">기업 리서치</SmartLink>
        </div>
        <div className="space-y-2">
          {companies.slice(0, 4).map((company) => (
            <SmartLink key={company.ticker} href={`/company/${company.ticker}`} className="flex items-center justify-between gap-3 rounded-lg bg-black/15 px-3 py-2 text-sm cursor-pointer hover:bg-white/5 active:scale-[0.99]">
              <div>
                <div className="font-medium text-white">{company.ticker}</div>
                <div className="text-[11px] text-[var(--muted)] break-words">{company.name}</div>
                <div className="mt-1 flex flex-wrap gap-2 text-[11px] text-[var(--muted)]">
                  <ScoreBadge label="총점" value={company.totalScore} title="총점은 기업 기초체력입니다." kind="total" interactive={false} />
                  <span>매출 YoY {formatPct(company.revenueGrowthYoY)}</span>
                  {typeof company.estimateRevision7d === 'number' && <span>EPS 7D {company.estimateRevision7d >= 0 ? '+' : ''}{company.estimateRevision7d.toFixed(1)}%</span>}
                  {typeof company.estimateRevision30d === 'number' && <span>EPS 30D {company.estimateRevision30d >= 0 ? '+' : ''}{company.estimateRevision30d.toFixed(1)}%</span>}
                </div>
                <div className="mt-1 text-[11px] text-white/80">{company.recommendationSummary ?? highlightVerdict(company.buyScore, 'company')}</div>
                <div className="mt-1 flex flex-wrap gap-2 text-[10px] text-white/70">
                  {company.bottomState ? <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-0.5 text-cyan-100">바닥 {company.bottomState}</span> : null}
                  {typeof company.volumeConfirmationScore === 'number' ? <span>거래량 {company.volumeConfirmationScore}</span> : null}
                  {typeof company.priceBottomScore === 'number' ? <span>가격 {company.priceBottomScore}</span> : null}
                  {typeof company.bottomFailureRiskScore === 'number' ? <span className="text-rose-200">실패위험 {company.bottomFailureRiskScore}</span> : null}
                </div>
                {company.linkedAsset ? (
                  <div className="mt-1 flex flex-wrap gap-2 text-[10px] text-white/65">
                    <span>회사 {company.companyActionLabel ?? company.companyAction ?? '—'}</span>
                    <span>·</span>
                    <span>{company.linkedAsset} {company.assetAction ?? '—'}</span>
                    {company.executionAlignment ? <span>· {company.executionAlignment === 'aligned' ? '판단 일치' : company.executionAlignment === 'mixed' ? '부분 일치' : '판단 충돌'}</span> : null}
                  </div>
                ) : null}
              </div>
              <div className="flex flex-col items-end gap-2">
                <ScoreBadge label="B" value={company.buyScore} title="B는 지금 사도 되는지 보는 실행 점수입니다." kind="buy" interactive={false} />
                <ActionBadge value={company.buyScore} interactive={false} />
              </div>
            </SmartLink>
          ))}
        </div>
      </section>
    </div>
  );
}
