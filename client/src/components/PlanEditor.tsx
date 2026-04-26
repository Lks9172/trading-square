'use client';

/**
 * 실전 투자 템플릿 UI (17차 Phase 1 A1).
 *
 * 영상 원문 정합:
 *   - video1 §5부 "시계열 먼저 정해야 한다 — 시스템이 있으면 심리 싸움에서 이길 수 있다"
 *   - notion "내 투자 원칙 나만의 체크리스트 만들기"
 *
 * 기능:
 *   - InvestmentPlan CRUD (horizon / target / leverage / 분할매수)
 *   - 최근 Trade Log 50건 열람 + 관찰 기록 추가
 *   - Weekly Report 미리보기 (서버 생성 텍스트)
 *   - 7가지 렌즈 체크리스트 (경제/유동성/심리/수급/밸류/이격/모멘텀)
 *   - "내 기준은?" 프롬프트 (P3 E1)
 */

import { useState } from 'react';

interface InvestmentPlan {
  horizon: 'short' | 'medium' | 'long';
  targetReturnAnnualPct: number;
  maxDrawdownTolerancePct: number;
  rebalanceIntervalDays: number;
  leverageMaxPct: number;
  profitTakeTargetPct: number;
  stopLossPct: number;
  monthlyDCA_KRW: number;
  // 21차
  currentHoldings?: Record<string, number | undefined>;
  totalCapitalKRW?: number;
  investmentExperienceYears?: number;
  accountType?: 'general' | 'isa' | 'pension' | 'foreign';
  notes?: string;
  updatedAt: string;
}

interface TradeLogEntry {
  ts: string;
  kind: 'signal_change' | 'allocation_change' | 'user_action' | 'observation';
  asset?: string;
  from?: string;
  to?: string;
  notes?: string;
}

interface WeeklyReport {
  generatedAt: string;
  period: { from: string; to: string };
  warnings: string[];
  ruleViolations: string[];
  keySignals: Array<{ asset: string; signal: string; met: string }>;
}

interface Props {
  initialPlan: InvestmentPlan | null;
  initialLog: TradeLogEntry[];
  weeklyReport: WeeklyReport | null;
  weeklyText: string;
  // 19차 신규
  convictionScore?: number | null;          // CONVICTION_SCORE_7AXIS (-7~+7)
  trancheUsedPct?: number;
  // 22차 P1#4: 운영자 한마디 회전
  operatorQuote?: { short: string; full: string } | null;
}

// 21차 Phase 1#7: 외부 링크 6대 카테고리로 재구조화 — 노션 카테고리 정합.
// 운영자 시그니처 자료 (구독·100명방·MALL²·TV preset)는 별도 그룹.
interface LinkGroup {
  title: string;
  hint?: string;
  links: Array<{ label: string; url: string }>;
}
const EXTERNAL_LINK_GROUPS: LinkGroup[] = [
  {
    title: '🏛️ 자산제곱 본가 (운영자 시그니처)',
    hint: '노션 §실전투자 적용 — 운영자 본인 자료. 비공개 자료실 입장 조건은 운영자 Threads 게시물에서 확인 (본 시스템과는 무관).',
    links: [
      // 21차 P2#17: Threads .net → .com (Meta 도메인 전환 정합)
      { label: '💬 평생 무료 프로젝트방 (텔레그램)', url: 'https://t.me/+2Qw1cAZTm8FjMGNl' },
      { label: 'Threads @asset.x2', url: 'https://www.threads.com/@asset.x2' },
      { label: '자산제곱 유동성 대시보드', url: 'https://liquidity-dashboard-rho.vercel.app/' },
      { label: '자산제곱 지정학 지도', url: 'https://assetx2-geomap.vercel.app/' },
      { label: '🔒 100명 비공개 자료실', url: 'https://www.threads.com/@asset.x2' },
      { label: '🔒 핵심 전략 구독 자료', url: 'https://naver.me/FxC5ffqp' },
      // 22차 P1#7: MALL² URL 정정 (Threads → 실제 notion sub-page)
      { label: '🛒 따뜻한 제곱몰 (MALL²)', url: 'https://resilient-parade-ca9.notion.site/MALL-2e3234508a8980be87b6e9cefae4b203?source=copy_link' },
    ],
  },
  {
    title: '📊 기술적 분석 / 차트',
    hint: '노션 §기술적 분석 — TradingView preset + 학습자료',
    links: [
      { label: 'TradingView 기본', url: 'https://www.tradingview.com/' },
      { label: '🌟 자산제곱 TV 지표 (운영자 preset)', url: 'https://naver.me/FwGw5GKu' },
      { label: '대신증권 기술적 분석 PDF', url: 'https://www.daishin.com/' },
    ],
  },
  {
    title: '🌍 거시 경제 분석',
    hint: '노션 §거시경제 — 국내·해외 거시 리포트',
    links: [
      { label: 'KCIF 국제금융센터', url: 'https://www.kcif.or.kr/finance/bondList' },
      { label: 'KIF 금융브리프', url: 'https://www.kif.re.kr/kif4/publication/pub_list?mid=20' },
      { label: 'KDI 한국개발연구원', url: 'https://www.kdi.re.kr/research/economy_outlook' },
      { label: '한국은행 경제전망', url: 'https://www.bok.or.kr/portal/bbs/B0000245/list.do?menuNo=200066' },
      { label: 'IMF World Economic Outlook', url: 'https://www.imf.org/en/Publications/WEO' },
      { label: 'Fidelity Korea Insights', url: 'https://www.fidelity.co.kr/insights/' },
      { label: 'TradingEconomics', url: 'https://tradingeconomics.com/' },
      { label: 'Investing 캘린더 (KR)', url: 'https://kr.investing.com/economic-calendar/' },
      { label: 'StreetStats M2 vs S&P500', url: 'https://streetstats.finance/liquidity/money/' },
    ],
  },
  {
    title: '💼 주식·ETF·채권 (리서치 / 백테스트)',
    hint: '노션 §주식·ETF·채권',
    links: [
      { label: '신한투자증권 리서치', url: 'https://siw.shinhansec.com/siw/insights/research/list/view-popup.do' },
      { label: '미래에셋자산운용 ETF', url: 'https://www.miraeasset.com/etf/etfData.do' },
      { label: '미래에셋 미국증시', url: 'https://securities.miraeasset.com/' },
      { label: '우리자산운용 리서치', url: 'https://www.wooriam.com/research/list.do' },
      { label: 'CME FedWatch Tool', url: 'https://www.cmegroup.com/markets/interest-rates/cme-fedwatch-tool.html' },
      { label: 'CME Market Insights', url: 'https://www.cmegroup.com/ko/education/market-insights.html' },
      { label: 'Portfolio Visualizer', url: 'https://www.portfoliovisualizer.com/' },
      { label: '마이핀플 ETF 순위', url: 'https://www.myfinpl.com/investment/etf' },
      { label: 'TipRanks dashboard', url: 'https://www.tipranks.com/dashboard' },
    ],
  },
  {
    title: '🐳 스마트머니 / 내부자',
    hint: '노션 §스마트머니',
    links: [
      { label: 'Dataroma 슈퍼인베스터', url: 'https://www.dataroma.com/m/home.php' },
      { label: 'OpenInsider', url: 'http://openinsider.com/' },
      { label: 'Insider Screener', url: 'https://www.insiderscreener.com/en/' },
      { label: 'Fintel Institutional Ownership', url: 'https://fintel.io/so/us' },
      { label: 'SEC EDGAR 8-K + Form 4', url: 'https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent&type=8-K' },
    ],
  },
  {
    title: '📰 한국 거시 뉴스',
    hint: '노션 §해외증시 한국 뉴스',
    links: [
      { label: '한경 글로벌마켓', url: 'https://www.hankyung.com/globalmarket' },
      { label: '서울경제 증권', url: 'https://www.sedaily.com/JList/Stock' },
      { label: '글로벌마켓모니터 (einfomax)', url: 'https://news.einfomax.co.kr/' },
    ],
  },
];

const LENSES = [
  { key: 'economy', label: '① 경제 (GDP / 고용 / 소비)' },
  { key: 'liquidity', label: '② 유동성 (M2 / Fed / RRP)' },
  { key: 'psych', label: '③ 심리 (F&G / Put-Call / VIX)' },
  { key: 'flow', label: '④ 수급 (외인·기관 / 13F)' },
  { key: 'value', label: '⑤ 밸류에이션 (PER / ERP)' },
  { key: 'disparity', label: '⑥ 이격도 (200MA / 연봉)' },
  { key: 'momentum', label: '⑦ 모멘텀 (MACD / 52W Hi / 멀티프레임)' },
];

export function PlanEditor({ initialPlan, initialLog, weeklyReport, weeklyText, convictionScore, trancheUsedPct, operatorQuote }: Props) {
  const [plan, setPlan] = useState<InvestmentPlan | null>(initialPlan);
  const [log, setLog] = useState<TradeLogEntry[]>(initialLog);
  const [savingPlan, setSavingPlan] = useState(false);
  const [noteDraft, setNoteDraft] = useState('');
  const [checks, setChecks] = useState<Record<string, boolean>>({});
  const [reason, setReason] = useState('');

  if (!plan) {
    return <div className="text-slate-400">Plan 로딩 실패 — 서버 연결을 확인해주세요.</div>;
  }

  async function savePlan(patch: Partial<InvestmentPlan>) {
    setSavingPlan(true);
    try {
      const res = await fetch('/api/plan', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(patch),
      });
      const data = await res.json();
      if (data?.plan) setPlan(data.plan);
    } finally {
      setSavingPlan(false);
    }
  }

  async function addObservation() {
    if (!noteDraft.trim()) return;
    const body = {
      kind: 'observation' as const,
      notes: noteDraft.trim(),
      context: { checklist: { ...checks }, reason },
    };
    const res = await fetch('/api/trade-log', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (res.ok) {
      setLog([{ ts: new Date().toISOString(), ...body }, ...log].slice(0, 50));
      setNoteDraft('');
      setReason('');
      setChecks({});
    }
  }

  const checkedCount = Object.values(checks).filter(Boolean).length;
  // 19차 P2#12: 확신 점수 미달(<3) 시 액션 잠금 권고
  const lowConviction = typeof convictionScore === 'number' && convictionScore < 3;
  const dcaPct = typeof trancheUsedPct === 'number' ? trancheUsedPct : 0;

  return (
    <div className="space-y-6">
      <header className="border-b border-slate-800 pb-4">
        <h1 className="text-2xl font-bold text-slate-100">🧭 나만의 투자 템플릿</h1>
        <p className="text-sm text-slate-400 mt-1">
          video1 §5부 — "시스템이 있으면 심리 싸움에서 이길 수 있다"
        </p>
        {/* 22차 P1#4: 운영자 9단락 회전 인용 (노션 §전하는 말) */}
        {operatorQuote && (
          <p className="text-xs text-amber-300/80 mt-1 italic" title={operatorQuote.full}>
            💬 {operatorQuote.short} — 자산제곱
          </p>
        )}
        {/* 22차 P2#21: 노션 §실전 투자 템플릿 정합 캡션 */}
        <p className="text-[10px] text-slate-500 mt-1">
          본 페이지는 노션 §"자산제곱 실전 투자 템플릿"의 디지털 구현체입니다.
        </p>
        {/* 19차 P2#12: 확신 점수 표시 + 미달 시 게이트 경고 */}
        {typeof convictionScore === 'number' && (
          <div className={`mt-3 rounded-lg p-3 text-sm ${lowConviction ? 'border border-amber-700 bg-amber-950/40 text-amber-200' : 'border border-emerald-800 bg-emerald-950/30 text-emerald-200'}`}>
            <span className="font-semibold">7축 확신 점수: {convictionScore >= 0 ? '+' : ''}{convictionScore}/7</span>
            {lowConviction
              ? ' — ⚠️ 확신 미달(3 미만). 새 매수 액션 자제 권고 (video1 §"확신 없으면 판단하지 마라").'
              : ' — 🟢 확신 충족 (3 이상). 계획대로 실행 가능.'}
          </div>
        )}
        {/* 19차 P3#14: DCA 진척도 */}
        <div className="mt-2">
          <div className="flex justify-between text-xs text-slate-400 mb-1">
            <span>분할매수 진척도 ({dcaPct.toFixed(0)}%)</span>
            <span>{dcaPct >= 70 ? '잔여 buffer 적음' : dcaPct >= 30 ? '진행 중' : 'buffer 충분'}</span>
          </div>
          <div className="h-2 rounded-full bg-slate-800 overflow-hidden">
            <div
              className={`h-2 rounded-full transition-all ${dcaPct >= 100 ? 'bg-orange-500' : dcaPct >= 70 ? 'bg-yellow-500' : 'bg-cyan-500'}`}
              style={{ width: `${Math.min(100, dcaPct)}%` }}
            />
          </div>
        </div>
      </header>

      {/* Plan Form */}
      <section className="rounded-xl border border-slate-800 bg-slate-900/40 p-4 md:p-6">
        <h2 className="text-lg font-semibold text-slate-100 mb-4">📋 투자 계획</h2>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <Field label="시계열">
            <select
              value={plan.horizon}
              onChange={(e) => savePlan({ horizon: e.target.value as InvestmentPlan['horizon'] })}
              className="bg-slate-800 text-slate-100 rounded px-3 py-2 w-full"
            >
              <option value="short">단기 (&lt; 6개월)</option>
              <option value="medium">중기 (6개월~3년)</option>
              <option value="long">장기 (3년+)</option>
            </select>
          </Field>
          <NumberField
            label="목표 연수익률 (%)"
            value={plan.targetReturnAnnualPct}
            onChange={(v) => savePlan({ targetReturnAnnualPct: v })}
          />
          <NumberField
            label="최대 감수 MDD (%)"
            value={plan.maxDrawdownTolerancePct}
            onChange={(v) => savePlan({ maxDrawdownTolerancePct: v })}
          />
          <NumberField
            label="리밸런싱 주기 (일)"
            value={plan.rebalanceIntervalDays}
            onChange={(v) => savePlan({ rebalanceIntervalDays: v })}
          />
          <NumberField
            label="레버리지 상한 (%)"
            value={plan.leverageMaxPct}
            onChange={(v) => savePlan({ leverageMaxPct: v })}
            hint="video1 §전략C — 최대 15%"
          />
          <NumberField
            label="익절 목표 (%)"
            value={plan.profitTakeTargetPct}
            onChange={(v) => savePlan({ profitTakeTargetPct: v })}
            hint="video1 §전략C — 20~30%"
          />
          <NumberField
            label="손절 기준 (%)"
            value={plan.stopLossPct}
            onChange={(v) => savePlan({ stopLossPct: v })}
          />
          <NumberField
            label="월 분할매수 (KRW)"
            value={plan.monthlyDCA_KRW}
            onChange={(v) => savePlan({ monthlyDCA_KRW: v })}
          />
          {/* 21차 Phase 1#8: 자본 / 연차 / 계좌 */}
          <NumberField
            label="총 운용자본 (KRW)"
            value={plan.totalCapitalKRW ?? 0}
            onChange={(v) => savePlan({ totalCapitalKRW: v })}
            hint="개인화 산출용 (선택)"
          />
          <NumberField
            label="투자 연차 (years)"
            value={plan.investmentExperienceYears ?? 0}
            onChange={(v) => savePlan({ investmentExperienceYears: v })}
          />
          <Field label="계좌 종류">
            <select
              value={plan.accountType ?? 'general'}
              onChange={(e) => savePlan({ accountType: e.target.value as InvestmentPlan['accountType'] })}
              className="bg-slate-800 text-slate-100 rounded px-3 py-2 w-full"
            >
              <option value="general">일반</option>
              <option value="isa">ISA</option>
              <option value="pension">연금저축/IRP</option>
              <option value="foreign">해외주식 전용</option>
            </select>
          </Field>
        </div>

        {/* 21차 Phase 1#2: 사용자 실제 보유 비중 입력 — 권고 vs 현재 drift 측정용 */}
        <div className="mt-4 rounded-lg border border-slate-700 bg-slate-950/50 p-3">
          <h3 className="text-sm font-semibold text-slate-200 mb-2">📊 내 실제 보유 비중 (%)</h3>
          <p className="text-[10px] text-slate-500 mb-2">권고 비중 대비 차이를 weekly-report 가 추적합니다 (≥10%p drift 시 경고).</p>
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
            {(['cash','nasdaq','leverage','gold','silver','copper','korea','emerging'] as const).map((k) => (
              <NumberField
                key={k}
                label={k}
                value={plan.currentHoldings?.[k] ?? 0}
                onChange={(v) => savePlan({ currentHoldings: { ...plan.currentHoldings, [k]: v } })}
              />
            ))}
          </div>
        </div>
        <div className="mt-4 text-xs text-slate-500">
          최종 업데이트: {new Date(plan.updatedAt).toLocaleString('ko-KR')}
          {savingPlan && <span className="ml-2 text-cyan-400">저장 중…</span>}
        </div>
      </section>

      {/* 7가지 렌즈 체크리스트 + 본인 판단 유도 */}
      <section className="rounded-xl border border-slate-800 bg-slate-900/40 p-4 md:p-6">
        <h2 className="text-lg font-semibold text-slate-100 mb-3">🔍 7 렌즈 체크리스트</h2>
        <p className="text-xs text-slate-400 mb-4">
          시스템 신호에만 의존하지 말고 본인 눈으로 교차 검증하기 위한 체크리스트입니다.
          체크된 항목 수 = {checkedCount}/{LENSES.length}
        </p>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
          {LENSES.map((l) => (
            <label key={l.key} className="flex items-center gap-2 text-sm text-slate-200 cursor-pointer">
              <input
                type="checkbox"
                checked={!!checks[l.key]}
                onChange={(e) => setChecks({ ...checks, [l.key]: e.target.checked })}
                className="accent-cyan-500"
              />
              {l.label}
            </label>
          ))}
        </div>

        <div className="mt-4">
          <label className="block text-sm text-slate-300 mb-1">🤔 내 판단 근거 (본인의 말로)</label>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={3}
            placeholder="예: 나스닥 이격도 +12% 과열이지만 어닝 시즌 직전 — 관망 우선"
            className="w-full bg-slate-800 text-slate-100 rounded px-3 py-2 text-sm"
          />
        </div>

        <div className="mt-3">
          <label className="block text-sm text-slate-300 mb-1">📝 관찰/판단 기록 (저장용 요약)</label>
          <textarea
            value={noteDraft}
            onChange={(e) => setNoteDraft(e.target.value)}
            rows={2}
            placeholder="짧은 요약 한 줄"
            className="w-full bg-slate-800 text-slate-100 rounded px-3 py-2 text-sm"
          />
          <button
            onClick={addObservation}
            disabled={!noteDraft.trim()}
            className="mt-2 px-4 py-2 rounded bg-cyan-600 hover:bg-cyan-500 disabled:bg-slate-700 disabled:text-slate-500 text-white text-sm"
          >
            Trade Log 기록
          </button>
        </div>
      </section>

      {/* Weekly Report Preview */}
      {weeklyReport && (
        <section className="rounded-xl border border-slate-800 bg-slate-900/40 p-4 md:p-6">
          <h2 className="text-lg font-semibold text-slate-100 mb-3">
            📊 주간 리포트 미리보기 ({weeklyReport.period.from} ~ {weeklyReport.period.to})
          </h2>
          {weeklyReport.ruleViolations.length > 0 && (
            <div className="mb-3 rounded border border-red-800 bg-red-950/40 p-3">
              <div className="text-sm font-semibold text-red-300 mb-1">🚨 계획 규칙 위반</div>
              <ul className="text-xs text-red-200 space-y-1">
                {weeklyReport.ruleViolations.map((v, i) => (
                  <li key={i}>• {v}</li>
                ))}
              </ul>
            </div>
          )}
          {weeklyReport.warnings.length > 0 && (
            <div className="mb-3 rounded border border-amber-800 bg-amber-950/20 p-3">
              <div className="text-sm font-semibold text-amber-300 mb-1">⚠️ 경고 신호</div>
              <ul className="text-xs text-amber-200 space-y-1">
                {weeklyReport.warnings.slice(0, 6).map((w, i) => (
                  <li key={i}>• {w}</li>
                ))}
              </ul>
            </div>
          )}
          <pre className="text-xs text-slate-300 whitespace-pre-wrap bg-slate-950/60 rounded p-3 overflow-x-auto">
            {weeklyText}
          </pre>
        </section>
      )}

      {/* 21차 Phase 1#7: 외부 리서치 링크 — 6대 카테고리 그룹 */}
      <section className="rounded-xl border border-slate-800 bg-slate-900/40 p-4 md:p-6">
        <h2 className="text-lg font-semibold text-slate-100 mb-1">🔗 외부 리서치 / 노션 정합 링크</h2>
        <p className="text-xs text-slate-400 mb-4">
          노션 6대 카테고리 정합. 자산제곱 본가 자료는 운영자 시그니처 그룹으로 분리.
        </p>
        <div className="space-y-4">
          {EXTERNAL_LINK_GROUPS.map((group) => (
            <div key={group.title}>
              <h3 className="text-sm font-semibold text-slate-200 mb-1">{group.title}</h3>
              {group.hint && <p className="text-[10px] text-slate-500 mb-2">{group.hint}</p>}
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
                {group.links.map((l) => (
                  <a
                    key={l.url + l.label}
                    href={l.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="block rounded border border-slate-700 bg-slate-950/40 p-2 text-xs text-slate-200 hover:border-cyan-600 hover:bg-cyan-950/20 transition"
                  >
                    ↗ {l.label}
                  </a>
                ))}
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* Trade Log */}
      <section className="rounded-xl border border-slate-800 bg-slate-900/40 p-4 md:p-6">
        <h2 className="text-lg font-semibold text-slate-100 mb-3">🗂️ 최근 Trade Log (50건)</h2>
        {log.length === 0 ? (
          <p className="text-sm text-slate-500">기록이 없습니다.</p>
        ) : (
          <ul className="space-y-2 max-h-96 overflow-y-auto">
            {log.map((e, i) => (
              <li key={i} className="text-xs border-b border-slate-800 pb-2">
                <div className="text-slate-400">
                  {new Date(e.ts).toLocaleString('ko-KR')} · {e.kind}
                  {e.asset ? ` · ${e.asset}` : ''}
                  {e.from && e.to ? ` · ${e.from} → ${e.to}` : ''}
                </div>
                {e.notes && <div className="text-slate-200 mt-1">{e.notes}</div>}
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="block text-sm text-slate-300 mb-1">{label}</label>
      {children}
    </div>
  );
}

function NumberField({
  label,
  value,
  onChange,
  hint,
}: {
  label: string;
  value: number;
  onChange: (v: number) => void;
  hint?: string;
}) {
  const [local, setLocal] = useState(String(value));
  return (
    <Field label={label}>
      <input
        type="number"
        value={local}
        onChange={(e) => setLocal(e.target.value)}
        onBlur={() => {
          const n = parseFloat(local);
          if (Number.isFinite(n) && n !== value) onChange(n);
        }}
        className="bg-slate-800 text-slate-100 rounded px-3 py-2 w-full"
      />
      {hint && <p className="text-xs text-slate-500 mt-1">{hint}</p>}
    </Field>
  );
}
