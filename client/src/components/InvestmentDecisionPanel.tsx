export type InvestmentDimensionView = {
  key: string;
  label: string;
  score: number;
  confidence: number;
  state: 'STRONG' | 'POSITIVE' | 'NEUTRAL' | 'WEAK';
  stateLabel: string;
  summary: string;
  reasons: string[];
  cautions: string[];
};

export type InvestmentDecisionView = {
  version: string;
  action: 'STRONG BUY' | 'BUY' | 'HOLD' | 'REDUCE' | 'SELL';
  actionLabel: string;
  decisionScore: number;
  investmentMeritScore: number;
  entryReadinessScore: number;
  opportunityType:
    | 'QUALITY_AT_REASONABLE_PRICE'
    | 'EARLY_CATALYST'
    | 'DEEP_VALUE_TURNAROUND'
    | 'QUALITY_BUT_EXPENSIVE'
    | 'VALUE_TRAP_RISK'
    | 'MOMENTUM_WITH_RISK'
    | 'BALANCED_WATCH'
    | 'INSUFFICIENT_EVIDENCE';
  opportunityLabel: string;
  summary: string;
  dimensions: {
    quality: InvestmentDimensionView;
    valuation: InvestmentDimensionView;
    catalyst: InvestmentDimensionView;
    sector: InvestmentDimensionView;
    timing: InvestmentDimensionView;
  };
  risk: {
    score: number;
    level: 'LOW' | 'MODERATE' | 'HIGH' | 'CRITICAL';
    levelLabel: string;
    summary: string;
    reasons: string[];
  };
  dataQuality: {
    coveragePct: number;
    confidence: number;
    level: 'HIGH' | 'MODERATE' | 'LOW';
    levelLabel: string;
    summary: string;
    warnings: string[];
  };
  scaleInEligibility?: {
    score: number;
    state: 'ELIGIBLE' | 'CONDITIONAL' | 'INELIGIBLE' | 'UNAVAILABLE';
    stateLabel: string;
    portfolioConcentrationCapPct: number;
    summary: string;
    reasons: string[];
    blockers: string[];
  };
  entryStrategy?: {
    initialEntryPctOfTarget: number;
    reservePctOfTarget: number;
    zoneLabel: string;
    summary: string;
    addConditions: string[];
    reduceConditions: string[];
  };
  forwardOutlooks: Array<{
    horizon: 'ONE_MONTH' | 'THREE_MONTHS' | 'SIX_MONTHS';
    horizonLabel: string;
    forwardTradingDays: number;
    positiveReturnLikelihoodPct: number | null;
    targetReturnPct: number | null;
    targetHitLikelihoodPct: number | null;
    averageReturnPct: number | null;
    averageMaxDrawdownPct: number | null;
    sampleCount: number;
    confidence: number;
    method: 'WALK_FORWARD' | 'SCORE_HEURISTIC';
    methodLabel: string;
    caution: string;
  }>;
  whyNow: string[];
  whyWait: string[];
  thesisBreaks: string[];
  methodology: string;
  probabilityNotice: string;
};

function actionTone(action: InvestmentDecisionView['action']): string {
  switch (action) {
    case 'STRONG BUY':
      return 'border-green-400/35 bg-green-400/10 text-green-100';
    case 'BUY':
      return 'border-emerald-400/35 bg-emerald-400/10 text-emerald-100';
    case 'HOLD':
      return 'border-cyan-400/30 bg-cyan-400/10 text-cyan-100';
    case 'REDUCE':
      return 'border-amber-400/35 bg-amber-400/10 text-amber-100';
    case 'SELL':
      return 'border-rose-400/35 bg-rose-400/10 text-rose-100';
  }
}

function stateTone(state: InvestmentDimensionView['state']): string {
  switch (state) {
    case 'STRONG':
      return 'border-emerald-500/25 bg-emerald-500/8 text-emerald-100';
    case 'POSITIVE':
      return 'border-cyan-500/25 bg-cyan-500/8 text-cyan-100';
    case 'NEUTRAL':
      return 'border-amber-500/25 bg-amber-500/8 text-amber-100';
    case 'WEAK':
      return 'border-rose-500/25 bg-rose-500/8 text-rose-100';
  }
}

function riskTone(level: InvestmentDecisionView['risk']['level']): string {
  switch (level) {
    case 'LOW':
      return 'border-emerald-500/25 bg-emerald-500/8 text-emerald-100';
    case 'MODERATE':
      return 'border-amber-500/25 bg-amber-500/8 text-amber-100';
    case 'HIGH':
    case 'CRITICAL':
      return 'border-rose-500/25 bg-rose-500/8 text-rose-100';
  }
}

function dataTone(level: InvestmentDecisionView['dataQuality']['level']): string {
  switch (level) {
    case 'HIGH':
      return 'text-emerald-200';
    case 'MODERATE':
      return 'text-amber-200';
    case 'LOW':
      return 'text-rose-200';
  }
}

function scaleInTone(state: NonNullable<InvestmentDecisionView['scaleInEligibility']>['state']): string {
  switch (state) {
    case 'ELIGIBLE':
      return 'border-emerald-500/25 bg-emerald-500/8 text-emerald-100';
    case 'CONDITIONAL':
      return 'border-amber-500/25 bg-amber-500/8 text-amber-100';
    case 'INELIGIBLE':
      return 'border-rose-500/25 bg-rose-500/8 text-rose-100';
    case 'UNAVAILABLE':
      return 'border-white/10 bg-black/15 text-white/70';
  }
}

function fmtPct(value: number | null): string {
  return typeof value === 'number' && Number.isFinite(value) ? `${value.toFixed(1)}%` : '—';
}

export function InvestmentDecisionPanel({ decision }: { decision: InvestmentDecisionView }) {
  const dimensions = [
    decision.dimensions.quality,
    decision.dimensions.valuation,
    decision.dimensions.catalyst,
    decision.dimensions.sector,
    decision.dimensions.timing,
  ];

  return (
    <section data-testid="investment-decision-panel" className={`rounded-2xl border p-5 ${actionTone(decision.action)}`}>
      <div className="flex flex-col gap-4 xl:flex-row xl:items-start xl:justify-between">
        <div className="max-w-3xl">
          <div className="text-xs font-semibold uppercase tracking-[0.14em] opacity-80">Investment decision stack</div>
          <div className="mt-2 flex flex-wrap items-center gap-2">
            <span className="rounded-full border border-current/30 bg-black/15 px-3 py-1 text-sm font-bold">
              {decision.action} · {decision.actionLabel}
            </span>
            <span className="rounded-full border border-white/10 bg-black/15 px-3 py-1 text-xs text-white/85">
              {decision.opportunityLabel}
            </span>
            <span className={`rounded-full border px-3 py-1 text-xs ${riskTone(decision.risk.level)}`}>
              위험 {decision.risk.levelLabel} · {decision.risk.score}
            </span>
          </div>
          <h2 className="mt-4 text-xl font-semibold text-white">그래서 지금 투자해도 되는가?</h2>
          <p className="mt-2 text-sm leading-relaxed text-white/85">{decision.summary}</p>
        </div>

        <div className="grid min-w-[280px] grid-cols-3 gap-2 text-center">
          <div className="rounded-xl border border-white/10 bg-black/15 p-3">
            <div className="text-[11px] text-white/60">투자 매력도</div>
            <div className="mt-1 text-2xl font-bold text-white">{decision.investmentMeritScore}</div>
            <div className="mt-1 text-[10px] text-white/55">회사·가격·촉매·섹터</div>
          </div>
          <div className="rounded-xl border border-white/10 bg-black/15 p-3">
            <div className="text-[11px] text-white/60">진입 적합도</div>
            <div className="mt-1 text-2xl font-bold text-white">{decision.entryReadinessScore}</div>
            <div className="mt-1 text-[10px] text-white/55">가격·거래량·반전</div>
          </div>
          <div className="rounded-xl border border-white/10 bg-black/15 p-3">
            <div className="text-[11px] text-white/60">최종 판단</div>
            <div className="mt-1 text-2xl font-bold text-white">{decision.decisionScore}</div>
            <div className="mt-1 text-[10px] text-white/55">위험 게이트 반영</div>
          </div>
        </div>
      </div>

      <div className="mt-5 grid grid-cols-1 gap-3 md:grid-cols-2 xl:grid-cols-5">
        {dimensions.map((dimension) => (
          <details
            key={dimension.key}
            data-testid={`investment-dimension-${dimension.key}`}
            className={`rounded-xl border p-3 ${stateTone(dimension.state)}`}
          >
            <summary className="cursor-pointer list-none select-none">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-medium">{dimension.label}</span>
                <span className="rounded-full border border-current/20 px-2 py-0.5 text-[10px]">
                  {dimension.stateLabel}
                </span>
              </div>
              <div className="mt-2 text-2xl font-semibold text-white">{dimension.score}</div>
              <div className="mt-1 text-[10px] text-white/55">근거 충족 {dimension.confidence}%</div>
              <div className="mt-2 text-[11px] leading-relaxed text-white/75">{dimension.summary}</div>
            </summary>
            <div className="mt-3 space-y-1 border-t border-white/10 pt-3 text-[11px] leading-relaxed text-white/75">
              {dimension.reasons.map((reason) => <div key={reason}>+ {reason}</div>)}
              {dimension.cautions.map((caution) => <div key={caution} className="text-amber-100/85">! {caution}</div>)}
            </div>
          </details>
        ))}
      </div>

      <div className="mt-5 grid grid-cols-1 gap-3 lg:grid-cols-2">
        <div className="rounded-xl border border-emerald-500/15 bg-emerald-500/5 p-4">
          <div className="text-sm font-medium text-emerald-100">지금 볼 매수 근거</div>
          <div className="mt-2 space-y-1 text-xs leading-relaxed text-emerald-50/85">
            {decision.whyNow.map((item) => <div key={item}>• {item}</div>)}
          </div>
        </div>
        <div className="rounded-xl border border-amber-500/15 bg-amber-500/5 p-4">
          <div className="text-sm font-medium text-amber-100">아직 기다릴 이유</div>
          <div className="mt-2 space-y-1 text-xs leading-relaxed text-amber-50/85">
            {decision.whyWait.map((item) => <div key={item}>• {item}</div>)}
          </div>
        </div>
      </div>

      {decision.scaleInEligibility ? (
        <div
          data-testid="scale-in-eligibility"
          className={`mt-5 rounded-xl border p-4 ${scaleInTone(decision.scaleInEligibility.state)}`}
        >
          <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div>
              <div className="text-sm font-medium">분할매수 전제 · 이 회사는 회복 가능한 자산인가?</div>
              <div className="mt-1 text-xs leading-relaxed text-white/75">{decision.scaleInEligibility.summary}</div>
            </div>
            <div className="grid shrink-0 grid-cols-3 gap-2 text-center text-xs">
              <div className="rounded-lg border border-current/20 bg-black/15 px-3 py-2">
                <div className="text-white/55">판정</div>
                <div className="mt-1 font-semibold text-white">{decision.scaleInEligibility.stateLabel}</div>
              </div>
              <div className="rounded-lg border border-current/20 bg-black/15 px-3 py-2">
                <div className="text-white/55">적격 점수</div>
                <div className="mt-1 text-lg font-semibold text-white">{decision.scaleInEligibility.score}</div>
              </div>
              <div className="rounded-lg border border-current/20 bg-black/15 px-3 py-2">
                <div className="text-white/55">포트 상한</div>
                <div className="mt-1 text-lg font-semibold text-white">{decision.scaleInEligibility.portfolioConcentrationCapPct}%</div>
              </div>
            </div>
          </div>
          <div className="mt-3 grid grid-cols-1 gap-3 text-[11px] leading-relaxed lg:grid-cols-2">
            <div className="rounded-lg border border-emerald-500/15 bg-emerald-500/5 p-3 text-emerald-50/85">
              <div className="mb-1 font-medium text-emerald-100">회복 가능 근거</div>
              {decision.scaleInEligibility.reasons.length
                ? decision.scaleInEligibility.reasons.map((item) => <div key={item}>• {item}</div>)
                : <div>• 아직 충분한 긍정 근거가 없습니다.</div>}
            </div>
            <div className="rounded-lg border border-rose-500/15 bg-rose-500/5 p-3 text-rose-50/85">
              <div className="mb-1 font-medium text-rose-100">물타기 금지 조건</div>
              {decision.scaleInEligibility.blockers.length
                ? decision.scaleInEligibility.blockers.map((item) => <div key={item}>• {item}</div>)
                : <div>• 현재 작동한 강제 차단 조건은 없습니다.</div>}
            </div>
          </div>
        </div>
      ) : null}

      {decision.entryStrategy ? <div className="mt-5 rounded-xl border border-cyan-500/20 bg-cyan-500/5 p-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <div className="text-sm font-medium text-cyan-100">가격 구조 기반 분할 진입</div>
            <div className="mt-1 text-xs leading-relaxed text-white/70">{decision.entryStrategy.summary}</div>
          </div>
          <div className="flex shrink-0 gap-2 text-center text-xs">
            <div className="rounded-lg border border-cyan-500/20 bg-black/15 px-3 py-2">
              <div className="text-white/55">1차</div>
              <div className="mt-1 text-lg font-semibold text-white">{decision.entryStrategy.initialEntryPctOfTarget}%</div>
            </div>
            <div className="rounded-lg border border-white/10 bg-black/15 px-3 py-2">
              <div className="text-white/55">대기 자금</div>
              <div className="mt-1 text-lg font-semibold text-white">{decision.entryStrategy.reservePctOfTarget}%</div>
            </div>
            <div className="rounded-lg border border-white/10 bg-black/15 px-3 py-2">
              <div className="text-white/55">현재 위치</div>
              <div className="mt-1 font-semibold text-white">{decision.entryStrategy.zoneLabel}</div>
            </div>
          </div>
        </div>
        <div className="mt-3 grid grid-cols-1 gap-3 text-[11px] leading-relaxed lg:grid-cols-2">
          <div className="rounded-lg border border-emerald-500/15 bg-emerald-500/5 p-3 text-emerald-50/85">
            <div className="mb-1 font-medium text-emerald-100">추가 매수 확인</div>
            {decision.entryStrategy.addConditions.map((item) => <div key={item}>• {item}</div>)}
          </div>
          <div className="rounded-lg border border-rose-500/15 bg-rose-500/5 p-3 text-rose-50/85">
            <div className="mb-1 font-medium text-rose-100">축소·청산 조건</div>
            {decision.entryStrategy.reduceConditions.map((item) => <div key={item}>• {item}</div>)}
          </div>
        </div>
      </div> : null}

      <div className="mt-5">
        <div className="flex flex-col gap-1 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <div className="text-sm font-medium text-white">1·3·6개월 전방 시나리오</div>
            <div className="mt-1 text-[11px] text-white/60">{decision.probabilityNotice}</div>
          </div>
          <div className={`text-[11px] ${dataTone(decision.dataQuality.level)}`}>
            데이터 {decision.dataQuality.levelLabel} · 커버리지 {decision.dataQuality.coveragePct}% · 신뢰도 {decision.dataQuality.confidence}%
          </div>
        </div>
        <div className="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-3">
          {decision.forwardOutlooks.map((outlook) => (
            <div key={outlook.horizon} className="rounded-xl border border-white/10 bg-black/15 p-3">
              <div className="flex items-center justify-between gap-2">
                <div className="text-sm font-medium text-white">{outlook.horizonLabel}</div>
                <span className={`rounded-full border px-2 py-0.5 text-[10px] ${outlook.method === 'WALK_FORWARD' ? 'border-emerald-500/20 bg-emerald-500/10 text-emerald-100' : 'border-amber-500/20 bg-amber-500/10 text-amber-100'}`}>
                  {outlook.methodLabel}
                </span>
              </div>
              <div className="mt-3 grid grid-cols-2 gap-2 text-xs">
                <div>
                  <div className="text-white/55">만기 양(+) 수익</div>
                  <div className="mt-1 text-lg font-semibold text-white">{fmtPct(outlook.positiveReturnLikelihoodPct)}</div>
                </div>
                <div>
                  <div className="text-white/55">
                    {outlook.targetReturnPct === null ? '기간 중 목표 도달' : `기간 중 +${outlook.targetReturnPct.toFixed(0)}% 도달`}
                  </div>
                  <div className="mt-1 text-lg font-semibold text-white">{fmtPct(outlook.targetHitLikelihoodPct)}</div>
                </div>
                <div>
                  <div className="text-white/55">과거 평균</div>
                  <div className="mt-1 text-white">{fmtPct(outlook.averageReturnPct)}</div>
                </div>
                <div>
                  <div className="text-white/55">과거 평균 MDD</div>
                  <div className="mt-1 text-white">{fmtPct(outlook.averageMaxDrawdownPct)}</div>
                </div>
              </div>
              <div className="mt-3 text-[10px] leading-relaxed text-white/55">
                {outlook.forwardTradingDays}거래일 · 표본 {outlook.sampleCount}건 · 신뢰 {outlook.confidence}%
                <br />{outlook.caution}
                {outlook.method === 'WALK_FORWARD' ? <><br />기간 중 목표를 찍은 뒤 밀리면 목표 도달률이 만기 양(+) 비율보다 높을 수 있습니다.</> : null}
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="mt-5 grid grid-cols-1 gap-3 lg:grid-cols-2">
        <details className="rounded-xl border border-rose-500/15 bg-rose-500/5 p-4">
          <summary className="cursor-pointer text-sm font-medium text-rose-100">투자 논리 파기 조건</summary>
          <div className="mt-3 space-y-1 text-xs leading-relaxed text-rose-50/80">
            {decision.thesisBreaks.map((item) => <div key={item}>• {item}</div>)}
          </div>
        </details>
        <details className="rounded-xl border border-white/10 bg-black/15 p-4">
          <summary className="cursor-pointer text-sm font-medium text-white">데이터 경고·산식 보기</summary>
          <div className="mt-3 text-xs leading-relaxed text-white/65">{decision.dataQuality.summary}</div>
          {decision.dataQuality.warnings.length > 0 ? (
            <div className="mt-2 space-y-1 text-[11px] text-amber-100/80">
              {decision.dataQuality.warnings.map((item) => <div key={item}>• {item}</div>)}
            </div>
          ) : null}
          <div className="mt-3 border-t border-white/10 pt-3 text-[11px] leading-relaxed text-white/55">
            {decision.methodology}
          </div>
        </details>
      </div>
    </section>
  );
}
