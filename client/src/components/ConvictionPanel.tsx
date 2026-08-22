'use client';

/**
 * 19차 P1#1 + P3#18 통합 노출 패널 (ConvictionPanel).
 *
 * 18차 + 19차에 추가됐지만 UI 어디에도 노출 안 되던 신규 derived key 들을 묶어 표시.
 * - 7축 조건 합치 게이지(확률 아님)
 * - 레버리지 3-of-3 트리거
 * - 시나리오 게이트 (A/B)
 * - 골디락스 신호등
 * - 안전자산 붕괴 / M2 정합 / 호르무즈 unwind / 금은비 극단 / 윗꼬리 매도세 / 페이크아웃
 * - FOMC FedWatch 확률
 * - Big Bets / cluster buy / dip buy / 애널리스트 upside
 * - 인물·기관 D-day (POWELL / BESSENT / WARSH / KDI / IMF / KIF)
 */

interface DerivedEntry {
  value: number | null;
  interpretation?: string;
  formula?: string;
  eligibleForSignals?: boolean;
  maximumAgeDays?: number;
}

interface Props {
  derived: Record<string, DerivedEntry>;
}

function Card({ title, children, accent }: { title: string; children: React.ReactNode; accent?: string }) {
  return (
    <div className={`rounded-lg border ${accent ?? 'border-slate-700'} bg-slate-950/40 p-3`}>
      <div className="text-xs font-semibold text-slate-300 mb-1">{title}</div>
      <div className="text-sm text-slate-100">{children}</div>
    </div>
  );
}

function Stat({ label, value, hint }: { label: string; value: React.ReactNode; hint?: string }) {
  return (
    <div className="flex items-center justify-between py-0.5">
      <span className="text-xs text-slate-400">{label}</span>
      <span className="text-xs font-mono text-slate-200" title={hint}>{value}</span>
    </div>
  );
}

function fmt(d: DerivedEntry | undefined, suffix = ''): string {
  if (!d || d.value === null || d.value === undefined) return '—';
  return `${d.value}${suffix}`;
}

export function ConvictionPanel({ derived: rawDerived }: Props) {
  const expiredCount = Object.values(rawDerived).filter((entry) => entry?.eligibleForSignals === false).length;
  const derived = Object.fromEntries(Object.entries(rawDerived).map(([key, entry]) => [
    key,
    entry?.eligibleForSignals === false
      ? { ...entry, value: null, interpretation: '신선도 만료 · 현재 판단 산식 제외' }
      : entry,
  ])) as Record<string, DerivedEntry>;
  const conv = derived.CONVICTION_SCORE_7AXIS;
  const lev3 = derived.LEVERAGE_TRIGGER_3OF3;
  const levCount = derived.LEVERAGE_TRIGGER_COUNT;
  const scenario = derived.SCENARIO_GATE_A_B;
  const neutralRate = derived.NEUTRAL_RATE_TEMPERATURE;
  const safeBroken = derived.BOND_SAFEHAVEN_BROKEN;
  const m2Align = derived.M2_SP500_LEAD_ALIGNMENT;
  const goldCup = derived.GOLD_LONGTERM_CUP_HANDLE;
  const gsExtreme = derived.GOLD_SILVER_RATIO_EXTREME;
  const hormuzUnwind = derived.HORMUZ_UNWIND_SEMI_TAILWIND;
  const wickLevel = derived.NASDAQ_UPPER_WICK_LEVEL;
  const fakeout = derived.NASDAQ_RANGE_BREAK_FAKEOUT;
  const helium = derived.HELIUM_AI_BOTTLENECK;
  const fxDev = derived.FX_FOREIGN_DEVIATION_RATIO;
  const horizonAlign = derived.USER_HORIZON_ALIGNMENT;
  const ksOvershoot = derived.KOSPI_HISTORIC_OVERSHOOT_FLAG;

  const cutProb = derived.FOMC_RATE_CUT_PROB_25BP;
  const hikeProb = derived.FOMC_RATE_HIKE_PROB_25BP;
  const holdProb = derived.FOMC_RATE_HOLD_PROB;

  const consTop = derived.INSTITUTIONAL_CONSENSUS_TOP_COUNT;
  const consStrong = derived.INSTITUTIONAL_CONSENSUS_STRONG_COUNT;
  const cluster = derived.SMART_MONEY_CLUSTER_BUY;
  const dip = derived.INSIDER_DIP_BUY;
  const upside = derived.ANALYST_TARGET_UPSIDE_PCT;
  const earnSurp = derived.EARNINGS_SURPRISE_PCT;
  const earnBeat = derived.EARNINGS_BEAT_RATIO;
  const earnDday = derived.EARNINGS_DDAY_MEGACAP;
  const krDisc = derived.KR_MATERIAL_DISCLOSURE_LEVEL;
  // 20차 신규
  const ksQuarterWick = derived.KOSPI_QUARTERLY_UPPER_WICK_PCT;
  const ksHalfWick = derived.KOSPI_HALFYEAR_UPPER_WICK_PCT;
  const trumpPress = derived.TRUMP_AGENDA_PRESSURE;
  const debtTraj = derived.US_DEBT_TRAJECTORY_LEVEL;
  const liqRiskTrans = derived.LIQUIDITY_RISK_TRANSMISSION;
  const dgs303w = derived.DGS30_3W_CHANGE_BPS;
  const goldParadox = derived.GOLD_GEOPOLITICAL_PARADOX;
  const retailInst = derived.RETAIL_INSTITUTION_DIVERGENCE;
  const usDisc = derived.US_MATERIAL_DISCLOSURE_LEVEL;
  const insiderLarge = derived.INSIDER_LARGE_SINGLE_BUY;
  const naveEco = derived.NAVER_ECONOMY_REPORT_DAYS_AGO;
  const ddCrisis = derived.NASDAQ_DRAWDOWN_CRISIS_LEVEL;
  // 21차 신규
  const ksLowerWick = derived.KOSPI_YEARLY_LOWER_WICK_PCT;
  const dcaStag = derived.DCA_STAGE_STAGNATION_COUNT;
  const krNews = derived.KR_NEWS_TOTAL_COUNT_24H;
  const krNewsFresh = derived.KR_NEWS_FRESHNESS_MINUTES;
  const portHoldings = derived.PORTFOLIO_HOLDINGS_TOTAL_PCT;
  // 22차 신규
  const regimeFwdRet = derived.REGIME_FORWARD_RETURN_90D_AVG;
  const sigHitRate = derived.NASDAQ_SIGNAL_HIT_RATE_30D;
  const scenarioFlips = derived.SCENARIO_TRANSITION_FLIPS_30D;

  const ddayKeys = [
    ['POWELL_SPEECH_DDAY', 'Powell 발언'],
    ['BESSENT_TESTIMONY_DDAY', 'Bessent 증언'],
    ['WARSH_KEYNOTE_DDAY', 'Warsh 강연'],
    ['KDI_FORECAST_DDAY', 'KDI 전망'],
    ['IMF_WEO_DDAY', 'IMF WEO'],
    ['KIF_BIWEEKLY_DDAY', 'KIF 격주'],
    ['BOK_QUARTERLY_OUTLOOK_DDAY', 'BOK 분기'],
  ] as const;
  // 20차 7축 mini 분해 — CONVICTION_SCORE_7AXIS 의 formula 에서 추출
  const convFormula = conv?.formula || '';
  const axesParse = (() => {
    // formula 예시: "차트+1/유동성-1/정책0/지정학+1/모멘텀+1/애널+1/매크로+1 = 5"
    const m = convFormula.match(/차트([+\-]?\d)\/유동성([+\-]?\d)\/정책([+\-]?\d)\/지정학([+\-]?\d)\/모멘텀([+\-]?\d)\/애널([+\-]?\d)\/매크로([+\-]?\d)/);
    if (!m) return null;
    return [
      ['차트', parseInt(m[1])],
      ['유동성', parseInt(m[2])],
      ['정책', parseInt(m[3])],
      ['지정학', parseInt(m[4])],
      ['모멘텀', parseInt(m[5])],
      ['애널', parseInt(m[6])],
      ['매크로', parseInt(m[7])],
    ] as Array<[string, number]>;
  })();

  const convVal = typeof conv?.value === 'number' ? conv.value : 0;
  // 22차 P2#16: RegimeHeader 색상 일관성 통일 — cyan → green/blue 계열로 정합
  const convAccent = convVal >= 5 ? 'border-green-600 bg-green-950/40' : convVal >= 3 ? 'border-green-700 bg-green-950/20' : convVal <= -3 ? 'border-red-600 bg-red-950/40' : 'border-slate-700';
  const levAccent = lev3?.value === 1 ? 'border-fuchsia-600 bg-fuchsia-950/30' : 'border-slate-700';
  const scenAccent = scenario?.value === 1 ? 'border-emerald-700 bg-emerald-950/30' : scenario?.value === -1 ? 'border-red-700 bg-red-950/30' : 'border-yellow-700 bg-yellow-950/20';

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-base sm:text-lg font-semibold">🧭 복합 조건·실행 게이트·D-day</h3>
        <span className="text-[10px] text-slate-500">video1+4 §확신 / 노션 §대시보드 정합</span>
      </div>

      {expiredCount > 0 && (
        <div className="mb-3 rounded-lg border border-amber-500/25 bg-amber-500/10 px-3 py-2 text-[11px] text-amber-100">
          신선도 만료 파생지표 {expiredCount}개는 과거값 대신 ‘산식 제외’로 표시합니다.
        </div>
      )}

      {/* 22차 P1#6: 모바일 대응 — collapsible 6영역. 첫 영역만 default open */}
      <details open className="mb-3">
        <summary className="cursor-pointer text-sm font-semibold text-slate-200 mb-2">⭐ 핵심 (조건 합치·레버리지·시나리오)</summary>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        {/* 1) 핵심 통합 게이지 */}
        <Card title="🎯 7축 조건 합치" accent={convAccent}>
          <div className="flex items-baseline gap-2">
            <span className="text-2xl font-bold">{convVal >= 0 ? '+' : ''}{convVal}</span>
            <span className="text-xs text-slate-400">/ 7</span>
          </div>
          <div className="text-[11px] text-slate-400 mt-1">{conv?.interpretation || '7축 차트·유동성·정책·지정학·모멘텀·애널·매크로'}</div>
          {axesParse && (
            <div className="mt-2 grid grid-cols-7 gap-1">
              {axesParse.map(([name, v]) => {
                const color = v > 0 ? 'bg-emerald-500' : v < 0 ? 'bg-red-500' : 'bg-slate-600';
                return (
                  <div key={name} className="text-center">
                    <div className={`h-1.5 rounded ${color}`}></div>
                    <div className="text-[8px] text-slate-400 mt-0.5">{name}</div>
                    <div className="text-[9px] font-mono text-slate-300">{v > 0 ? '+' : ''}{v}</div>
                  </div>
                );
              })}
            </div>
          )}
        </Card>

        <Card title="🚀 레버리지 3조건 트리거" accent={levAccent}>
          <div className="text-base font-semibold">
            {lev3?.value === 1 ? '🟢 3/3 발동' : `${levCount?.value ?? 0}/3 (대기)`}
          </div>
          <div className="text-[11px] text-slate-400 mt-1">
            {lev3?.interpretation || '이격도 ≤-25% AND VIX ≥35 AND ICSA <30만'}
          </div>
        </Card>

        <Card title="🎬 시나리오 게이트" accent={scenAccent}>
          <div className="text-base font-semibold">
            {scenario?.value === 1 ? '시나리오 A — 추세 재개' : scenario?.value === -1 ? '시나리오 B — 박스 하방' : '관망 — 분기점'}
          </div>
          <div className="text-[11px] text-slate-400 mt-1">{scenario?.interpretation || 'stt_kospi §4부 "두 그림"'}</div>
        </Card>
      </div>
      </details>

      <details className="mb-3">
        <summary className="cursor-pointer text-sm font-semibold text-slate-200 mb-2">🌍 매크로 (채권/유동성/금리)</summary>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        {/* 2) 매크로 / 채권 / 유동성 */}
        <Card title="🚦 골디락스 신호등 (FFR-R*)">
          <div>{neutralRate?.interpretation || fmt(neutralRate)}</div>
        </Card>
        <Card title="🛡️ Safe-haven 붕괴">
          <div>{safeBroken?.interpretation || fmt(safeBroken)}</div>
        </Card>
        <Card title="💧 M2 → S&P 13W 정합">
          <div>{m2Align?.interpretation || fmt(m2Align)}</div>
        </Card>
      </div>
      </details>

      <details className="mb-3">
        <summary className="cursor-pointer text-sm font-semibold text-slate-200 mb-2">📈 패턴·자산 구조 (금/은/구리)</summary>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        {/* 3) 패턴 / 자산 구조 */}
        <Card title="🥇 금 장기 컵앤핸들">
          <div>{goldCup?.interpretation || fmt(goldCup)}</div>
        </Card>
        <Card title="⚖️ 금은비 극단">
          <div>{gsExtreme?.interpretation || fmt(gsExtreme)}</div>
        </Card>
        <Card title="🛢️ 호르무즈 unwind → 반도체">
          <div>{hormuzUnwind?.interpretation || fmt(hormuzUnwind)}</div>
        </Card>
      </div>
      </details>

      <details className="mb-3">
        <summary className="cursor-pointer text-sm font-semibold text-slate-200 mb-2">⚠️ NASDAQ 위험 (윗꼬리·페이크아웃·헬륨)</summary>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        {/* 4) NASDAQ 위험 */}
        <Card title="📉 윗꼬리 매도세">
          <div>{wickLevel?.interpretation || fmt(wickLevel)}</div>
        </Card>
        <Card title="🪤 페이크아웃">
          <div>{fakeout?.interpretation || (fakeout?.value === 1 ? '🟠 페이크아웃' : '⚪ 정상')}</div>
        </Card>
        <Card title="🤖 헬륨 / AI 반도체 병목">
          <div>{helium?.interpretation || fmt(helium)}</div>
        </Card>
      </div>
      </details>

      <details className="mb-3">
        <summary className="cursor-pointer text-sm font-semibold text-slate-200 mb-2">🇰🇷 한국 시장 (외인·과열·공시)</summary>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        {/* 5) 한국 시장 */}
        <Card title="🇰🇷 외인 ATM화 비율">
          <div>{fxDev?.interpretation || (typeof fxDev?.value === 'number' ? `${fxDev.value}배` : '—')}</div>
          <div className="text-[10px] text-slate-500 mt-0.5">stt_kospi §3부 baseline 5조/10%↑</div>
        </Card>
        <Card title="🇰🇷 KOSPI 75% YTD 과열">
          <div>{ksOvershoot?.interpretation || (ksOvershoot?.value === 1 ? '🔴 75%+' : '⚪ 정상')}</div>
        </Card>
        <Card title="🇰🇷 DART 주요공시">
          <div>{krDisc?.interpretation || fmt(krDisc)}</div>
        </Card>
        <Card title="🇰🇷 KOSPI 연봉 아래꼬리">
          <div>{ksLowerWick?.interpretation || (typeof ksLowerWick?.value === 'number' ? `${ksLowerWick.value}%` : '—')}</div>
        </Card>
        <Card title="🇰🇷 한국 매크로 뉴스">
          <div>{krNews?.interpretation || (typeof krNews?.value === 'number' ? `${krNews.value}건/24h` : '—')}</div>
          {typeof krNewsFresh?.value === 'number' && (
            <div className="text-[10px] text-slate-500 mt-0.5">최신 {krNewsFresh.value}분 전</div>
          )}
        </Card>
      </div>
      </details>

      <details className="mb-3">
        <summary className="cursor-pointer text-sm font-semibold text-slate-200 mb-2">⏱️ 시계열·과거검증·DCA</summary>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        {/* 6) 시계열 정합 */}
        <Card title="🧭 시계열 정합">
          <div>{horizonAlign?.value === 1 ? '🟢 정합' : horizonAlign?.value === -1 ? '🟠 불일치' : '⚪ 관망'}</div>
          <div className="text-[10px] text-slate-500 mt-0.5">{horizonAlign?.formula?.split('.')[0]}</div>
        </Card>
        <Card title="💵 FedWatch (ZQ 근사)">
          <Stat label="cut 25bp" value={fmt(cutProb, '%')} />
          <Stat label="hold" value={fmt(holdProb, '%')} />
          <Stat label="hike 25bp" value={fmt(hikeProb, '%')} />
        </Card>
        <Card title="📰 메가캡 EPS 서프라이즈">
          <Stat label="평균 서프라이즈" value={fmt(earnSurp, '%')} />
          <Stat label="beat 비율" value={fmt(earnBeat, '%')} />
          <Stat label="다음 D-day" value={fmt(earnDday, '일')} />
        </Card>
        {/* 22차 P2#8: regime forward return + 신호 적중률 + 시나리오 flips */}
        <Card title="📊 과거 동일 레짐 90D 평균">
          <div>{regimeFwdRet?.interpretation || (typeof regimeFwdRet?.value === 'number' ? `${regimeFwdRet.value}%` : '—')}</div>
        </Card>
        <Card title="🎯 과거 BUY 후 30D 양(+) 비율">
          <div>{sigHitRate?.interpretation || (typeof sigHitRate?.value === 'number' ? `${sigHitRate.value}%` : '—')}</div>
        </Card>
        <Card title="🔁 시나리오 30일 전환수">
          <div>{scenarioFlips?.interpretation || (typeof scenarioFlips?.value === 'number' ? `${scenarioFlips.value}회` : '—')}</div>
        </Card>
        <Card title="⏳ DCA 분할 정체">
          <div>{dcaStag?.interpretation || (typeof dcaStag?.value === 'number' ? `${dcaStag.value}건` : '—')}</div>
        </Card>
        <Card title="📊 보유 합계">
          <div>{portHoldings?.interpretation || (typeof portHoldings?.value === 'number' ? `${portHoldings.value}%` : '—')}</div>
        </Card>
      </div>
      </details>

      {/* 20차 신규 — 분기/반기봉 + Trump + 부채 + 디커플링 + 트러스 + paradox + retail-inst */}
      <details className="mb-3">
        <summary className="cursor-pointer text-sm font-semibold text-slate-200 mb-2">🔬 20차 신규 (분기봉·트럼프·디커플링·트러스·역설·괴리)</summary>
      <div className="mt-4 grid grid-cols-2 md:grid-cols-4 gap-3">
        <Card title="📈 KOSPI 분기봉 윗꼬리">
          <div>{ksQuarterWick?.interpretation || (typeof ksQuarterWick?.value === 'number' ? `${ksQuarterWick.value}%` : '—')}</div>
        </Card>
        <Card title="📈 KOSPI 반기봉 윗꼬리">
          <div>{ksHalfWick?.interpretation || (typeof ksHalfWick?.value === 'number' ? `${ksHalfWick.value}%` : '—')}</div>
        </Card>
        <Card title="🇺🇸 트럼프 어젠다 압력">
          <div>{trumpPress?.interpretation || `${fmt(trumpPress)}/4`}</div>
        </Card>
        <Card title="💰 미국 부채 궤적">
          <div>{debtTraj?.interpretation || fmt(debtTraj)}</div>
        </Card>
        <Card title="💸 유동성→위험자산 전이">
          <div>{liqRiskTrans?.interpretation || fmt(liqRiskTrans)}</div>
        </Card>
        <Card title="📊 30Y 3주 변화 (트러스)">
          <div>{dgs303w?.interpretation || (typeof dgs303w?.value === 'number' ? `${dgs303w.value >= 0 ? '+' : ''}${dgs303w.value}bp` : '—')}</div>
        </Card>
        <Card title="🥇 금-지정학 역설">
          <div>{goldParadox?.interpretation || (goldParadox?.value === 1 ? '🟠 역설 활성' : '⚪ 미발동')}</div>
        </Card>
        <Card title="👥 개인-기관 괴리">
          <div>{retailInst?.interpretation || fmt(retailInst)}</div>
        </Card>
        <Card title="📰 SEC 8-K 미국 공시">
          <div>{usDisc?.interpretation || fmt(usDisc)}</div>
          <div className="text-[10px] text-slate-500 mt-0.5">{derived.US_MATERIAL_DISCLOSURE_8K_24H?.value ?? '-'}건/24h</div>
        </Card>
        <Card title="🐳 내부자 ≥$500k 단건">
          <div>{insiderLarge?.interpretation || (typeof insiderLarge?.value === 'number' ? `${insiderLarge.value}건` : '—')}</div>
        </Card>
        <Card title="📰 Naver 경제분석 D-day">
          <div>{naveEco?.interpretation || (typeof naveEco?.value === 'number' ? `D-${naveEco.value}` : '—')}</div>
        </Card>
        <Card title="📉 NASDAQ DD 시스템 위기">
          <div>{ddCrisis?.interpretation || fmt(ddCrisis)}</div>
        </Card>
      </div>
      </details>

      {/* 스마트머니 통합 */}
      <details className="mb-3">
        <summary className="cursor-pointer text-sm font-semibold text-slate-200 mb-2">🐳 스마트머니</summary>
      <div className="grid grid-cols-1 md:grid-cols-4 gap-3">
        <Card title="🐋 13F 2인+ 공유">
          <div className="text-xl font-bold">{fmt(consTop)}</div>
          <div className="text-[11px] text-slate-400">3인+ 강 합의: {fmt(consStrong)}</div>
        </Card>
        <Card title="🔥 내부자 cluster">
          <div className="text-xl font-bold">{fmt(cluster)}</div>
          <div className="text-[11px] text-slate-400">{cluster?.interpretation || ''}</div>
        </Card>
        <Card title="📉 dip buy 플래그">
          <div className="text-xl font-bold">{dip?.value === 1 ? '🟢 ON' : '⚪ OFF'}</div>
          <div className="text-[11px] text-slate-400">{dip?.interpretation || ''}</div>
        </Card>
        <Card title="🎯 애널리스트 목표가 상승여력">
          <div className="text-xl font-bold">{fmt(upside, '%')}</div>
          <div className="text-[11px] text-slate-400">{upside?.interpretation || ''}</div>
        </Card>
      </div>
      </details>

      {/* D-day 캘린더 */}
      <details>
        <summary className="cursor-pointer text-sm font-semibold text-slate-200 mb-2">📅 발언자 / 기관 D-day</summary>
      <div className="mt-2">
        <div className="flex flex-wrap gap-2">
          {ddayKeys.map(([k, label]) => {
            const v = derived[k]?.value;
            if (typeof v !== 'number') return null;
            const accent = v <= 3 ? 'border-amber-600 text-amber-200' : v <= 14 ? 'border-cyan-700 text-cyan-200' : 'border-slate-700 text-slate-300';
            return (
              <span key={k} className={`text-xs rounded px-2 py-1 border bg-slate-950/40 ${accent}`}>
                {label} <span className="font-mono">D-{v}</span>
              </span>
            );
          })}
        </div>
      </div>
      </details>
    </div>
  );
}
