'use client';

/**
 * 19차 P1#1 + P3#18 통합 노출 패널 (ConvictionPanel).
 *
 * 18차 + 19차에 추가됐지만 UI 어디에도 노출 안 되던 신규 derived key 들을 묶어 표시.
 * - 7축 확신 게이지
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

export function ConvictionPanel({ derived }: Props) {
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
  const fxLevel = derived.FX_FOREIGN_DEVIATION_LEVEL;
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

  const ddayKeys = [
    ['POWELL_SPEECH_DDAY', 'Powell 발언'],
    ['BESSENT_TESTIMONY_DDAY', 'Bessent 증언'],
    ['WARSH_KEYNOTE_DDAY', 'Warsh 강연'],
    ['KDI_FORECAST_DDAY', 'KDI 전망'],
    ['IMF_WEO_DDAY', 'IMF WEO'],
    ['KIF_BIWEEKLY_DDAY', 'KIF 격주'],
  ] as const;

  const convVal = typeof conv?.value === 'number' ? conv.value : 0;
  const convAccent = convVal >= 5 ? 'border-emerald-700 bg-emerald-950/30' : convVal >= 3 ? 'border-cyan-700 bg-cyan-950/30' : convVal <= -3 ? 'border-red-700 bg-red-950/30' : 'border-slate-700';
  const levAccent = lev3?.value === 1 ? 'border-fuchsia-600 bg-fuchsia-950/30' : 'border-slate-700';
  const scenAccent = scenario?.value === 1 ? 'border-emerald-700 bg-emerald-950/30' : scenario?.value === -1 ? 'border-red-700 bg-red-950/30' : 'border-yellow-700 bg-yellow-950/20';

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-base sm:text-lg font-semibold">🧭 18·19차 통합 — 확신·게이트·D-day</h3>
        <span className="text-[10px] text-slate-500">video1+4 §확신 / 노션 §대시보드 정합</span>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        {/* 1) 핵심 통합 게이지 */}
        <Card title="🎯 7축 확신 점수" accent={convAccent}>
          <div className="flex items-baseline gap-2">
            <span className="text-2xl font-bold">{convVal >= 0 ? '+' : ''}{convVal}</span>
            <span className="text-xs text-slate-400">/ 7</span>
          </div>
          <div className="text-[11px] text-slate-400 mt-1">{conv?.interpretation || '7축 차트·유동성·정책·지정학·모멘텀·애널·매크로'}</div>
        </Card>

        <Card title="🚀 착한 레버리지 트리거" accent={levAccent}>
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
          <Stat label="평균 surprise" value={fmt(earnSurp, '%')} />
          <Stat label="beat ratio" value={fmt(earnBeat, '%')} />
          <Stat label="다음 D-day" value={fmt(earnDday, '일')} />
        </Card>
      </div>

      {/* 스마트머니 통합 */}
      <div className="mt-4 grid grid-cols-1 md:grid-cols-4 gap-3">
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
        <Card title="🎯 애널리스트 목표가 upside">
          <div className="text-xl font-bold">{fmt(upside, '%')}</div>
          <div className="text-[11px] text-slate-400">{upside?.interpretation || ''}</div>
        </Card>
      </div>

      {/* D-day 캘린더 */}
      <div className="mt-4">
        <div className="text-xs font-semibold text-slate-300 mb-2">📅 발언자 / 기관 D-day</div>
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
    </div>
  );
}
