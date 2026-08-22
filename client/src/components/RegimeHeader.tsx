"use client";

import { InfoTooltip } from "./InfoTooltip";

const REGIME_CONFIG: Record<string, { emoji: string; color: string; label: string; action: string }> = {
  RISK_ON:        { emoji: "🟢", color: "border-green-500/50 bg-green-500/10", label: "위험선호", action: "개별 게이트 통과 자산의 비중 확대 검토" },
  NEUTRAL:        { emoji: "🔵", color: "border-blue-500/50 bg-blue-500/10",   label: "중립",     action: "기본 비중 유지, 개별 신호 확인" },
  CAUTION:        { emoji: "🟡", color: "border-yellow-500/50 bg-yellow-500/10", label: "경계",   action: "추격 제한, 현금 비중 점검" },
  CORRECTION:     { emoji: "🟠", color: "border-orange-500/50 bg-orange-500/10", label: "조정",   action: "바닥 확인 전 대기 또는 소액 분할" },
  PANIC_BUT_OK:   { emoji: "🔴", color: "border-red-500/50 bg-red-500/10",     label: "공포(체력 유지)", action: "체력·반전 확인 자산만 분할 후보" },
  RECESSION_RISK: { emoji: "⚫", color: "border-neutral-500/50 bg-neutral-500/10", label: "침체 위험", action: "방어·현금 우선, 물타기 제한" },
  STAGFLATION:    { emoji: "🟤", color: "border-amber-700/60 bg-amber-950/30", label: "스태그플레이션", action: "원가·금리 민감 자산 축소, 방어 우선" },
  BOND_VIGILANTE: { emoji: "🟣", color: "border-purple-500/50 bg-purple-500/10", label: "채권 자경단", action: "장기금리·재정 위험, 레버리지·듀레이션 제한" },
  STAGFLATION_BOND_VIGILANTE: { emoji: "🟫", color: "border-red-700/60 bg-red-950/30", label: "스태그플레이션+채권 경계", action: "방어 우선, 신규 위험노출 최소화" },
};

const COMPONENT_DESCRIPTIONS: Record<string, string> = {
  vix: '변동성/공포 수준. 높을수록 위험자산에 부정적',
  yieldCurve: '장단기 금리차. 역전될수록 경기침체 위험 증가',
  hySpread: '하이일드 스프레드. 신용시장 스트레스 지표',
  joblessClaims: '신규 실업수당. 고용 악화 속도',
  nasdaqDisparity: '나스닥 200DMA 대비 괴리율',
  finStress: '세인트루이스 금융스트레스지수',
  dxy: '달러 강세/약세. 약세면 금·신흥국 우호',
  liquidityDir: '현재는 같은 수요일의 연준 총자산-TGA-ON RRP 미국 순유동성 4주 충격을 우선합니다. +2/+1=확장, -1/-2=흡수이며 데이터 부족 시에만 기존 복합축으로 대체합니다. 주가 방향이나 수익 확률이 아닙니다.',
  wti: 'WTI 유가. $100↑ 인플레 우려, $65↓ 경기 둔화 우려',
  globalM2: '현재는 미국 M2 YoY 단일 프록시입니다. 글로벌 유동성 전체를 뜻하지 않으며 다른 유동성 축과 함께 봅니다.',
  sectorMomentum: '경기민감 6개 ETF와 방어 3개 ETF의 1개월 수익률 차이입니다. 양수면 경기민감 리더십 우위입니다.',
  policy: '정책 방향성 (자동: EFFR 추세 기반, 수동 오버라이드 가능)',
  geoRisk: '지정학 리스크 (자동: GPR Index 기반, 수동 오버라이드 가능)',
};

const SCORE_BANDS = [
  { min: 0, label: 'RECESSION_RISK', color: '#737373' },
  { min: 10, label: 'PANIC_BUT_OK / RECESSION', color: '#ef4444' },
  { min: 25, label: 'CORRECTION', color: '#f97316' },
  { min: 40, label: 'CAUTION', color: '#eab308' },
  { min: 55, label: 'NEUTRAL', color: '#3b82f6' },
  { min: 75, label: 'RISK_ON', color: '#22c55e' },
];

interface Props {
  regime: {
    regime: string;
    score: number;
    components: Record<string, number>;
  };
  // 20차 D: 핵심 지표 prominent 노출
  derived?: Record<string, {
    value: number | null;
    interpretation?: string;
    formula?: string;
    eligibleForSignals?: boolean;
  }>;
}

export function RegimeHeader({ regime, derived }: Props) {
  const config = REGIME_CONFIG[regime.regime] || REGIME_CONFIG.NEUTRAL;
  const currentValue = (key: string): number | null | undefined => {
    const point = derived?.[key];
    return point?.eligibleForSignals === false ? null : point?.value;
  };
  // 20차 prominent 뱃지 — SCENARIO_GATE / FX_FOREIGN_DEVIATION / NEUTRAL_RATE
  const scenario = currentValue('SCENARIO_GATE_A_B');
  const scenarioLabel = scenario === 1 ? '시나리오 A 추세재개' : scenario === -1 ? '시나리오 B 박스이탈' : '시나리오 분기점';
  const scenarioColor = scenario === 1 ? 'border-emerald-600 bg-emerald-950/40 text-emerald-200' : scenario === -1 ? 'border-red-600 bg-red-950/40 text-red-200' : 'border-yellow-600 bg-yellow-950/30 text-yellow-200';
  const fxDev = currentValue('FX_FOREIGN_DEVIATION_RATIO');
  const neutralRate = currentValue('NEUTRAL_RATE_TEMPERATURE');
  const neutralRateLabel = neutralRate === 2 ? '🔴 빨간불' : neutralRate === 1 ? '🟡 노란불' : neutralRate === 0 ? '🟢 초록불' : neutralRate === -1 ? '🟢🟢 초완화' : null;
  const conviction = currentValue('CONVICTION_SCORE_7AXIS');
  const lev3 = currentValue('LEVERAGE_TRIGGER_3OF3');

  return (
    <div className={`rounded-xl border ${config.color} p-4 sm:p-6`}>
      <div className="flex items-start sm:items-center justify-between gap-3 mb-3">
        <div className="flex items-center gap-2 sm:gap-3 min-w-0">
          <span className="text-2xl sm:text-3xl shrink-0">{config.emoji}</span>
          <div className="min-w-0">
            <h2 className="text-lg sm:text-xl font-bold truncate">{config.label}</h2>
            <p className="text-xs sm:text-sm text-[var(--muted)] truncate">{config.action}</p>
          </div>
        </div>
        <div className="text-right shrink-0">
          <div className="text-2xl sm:text-3xl font-bold font-mono">{regime.score}</div>
          <div className="text-[10px] sm:text-xs text-[var(--muted)]">/ 100</div>
        </div>
      </div>

      {/* 22차 P1#4: 운영자 한마디 회전 인용 (노션 §전하는 말 9단락) */}
      {(() => {
        const formula = derived?.OPERATOR_PHILOSOPHY_QUOTE_INDEX?.formula ?? '';
        const m = formula.match(/오늘의 운영자 한마디: "([^"]+)"/);
        const short = m ? m[1] : null;
        if (!short) return null;
        return (
          <p className="text-xs text-amber-300/80 italic mb-3" title={formula}>
            💬 {short} — 자산제곱
          </p>
        );
      })()}

      {/* 20차: 핵심 게이지 prominent */}
      <div className="flex flex-wrap gap-2 mb-3">
        {typeof conviction === 'number' && (
          <span className={`text-xs rounded px-2 py-1 border ${conviction >= 3 ? 'border-emerald-600 bg-emerald-950/40 text-emerald-200' : conviction <= -3 ? 'border-red-600 bg-red-950/40 text-red-200' : 'border-slate-700 bg-slate-900/40 text-slate-200'}`}>
            🎯 7축 조건 합치 <span className="font-mono">{conviction >= 0 ? '+' : ''}{conviction}/7</span>
          </span>
        )}
        {lev3 === 1 && (
          <span className="text-xs rounded px-2 py-1 border border-fuchsia-600 bg-fuchsia-950/40 text-fuchsia-200">
            🚀 레버리지 3/3 발동
          </span>
        )}
        {scenario !== undefined && scenario !== null && (
          <span className={`text-xs rounded px-2 py-1 border ${scenarioColor}`}>
            🎬 {scenarioLabel}
          </span>
        )}
        {neutralRateLabel && (
          <span className="text-xs rounded px-2 py-1 border border-slate-700 bg-slate-900/40 text-slate-200">
            🚦 {neutralRateLabel}
          </span>
        )}
        {typeof fxDev === 'number' && fxDev >= 1.5 && (
          <span className={`text-xs rounded px-2 py-1 border ${fxDev >= 6 ? 'border-red-600 bg-red-950/40 text-red-200' : fxDev >= 3 ? 'border-orange-600 bg-orange-950/40 text-orange-200' : 'border-yellow-600 bg-yellow-950/40 text-yellow-200'}`}>
            🇰🇷 외인 ATM <span className="font-mono">{fxDev}배</span>
          </span>
        )}
      </div>

      <div className="w-full bg-neutral-800 rounded-full h-2 mt-2">
        <div
          className="h-2 rounded-full transition-all duration-500"
          style={{
            width: `${regime.score}%`,
            background: regime.score >= 75 ? "#22c55e"
              : regime.score >= 55 ? "#3b82f6"
              : regime.score >= 40 ? "#eab308"
              : regime.score >= 25 ? "#f97316"
              : "#ef4444",
          }}
        />
      </div>

      <div className="relative h-4 mt-1">
        {[0, 25, 40, 55, 75, 100].map((mark) => (
          <div
            key={mark}
            className="absolute top-0 -translate-x-1/2"
            style={{ left: `${mark}%` }}
          >
            <div className="w-px h-2 bg-neutral-500 mx-auto" />
            <div className="text-[9px] sm:text-[10px] text-[var(--muted)] mt-0.5">{mark}</div>
          </div>
        ))}
      </div>

      <div className="mt-2 grid grid-cols-3 sm:grid-cols-6 gap-1 text-[9px] sm:text-[10px] text-[var(--muted)]">
        {SCORE_BANDS.map((band) => (
          <div key={band.label} className="flex items-center gap-1">
            <span className="inline-block w-2 h-2 rounded-full" style={{ backgroundColor: band.color }} />
            <span>{band.min}+</span>
            <span className="truncate">{band.label}</span>
          </div>
        ))}
      </div>

      <div className="mt-3 sm:mt-4 flex flex-wrap gap-1.5 sm:gap-2">
        {Object.entries(regime.components).map(([key, val]) => (
          <span key={key} className={`px-1.5 sm:px-2 py-0.5 sm:py-1 rounded text-[10px] sm:text-xs font-mono inline-flex items-center ${
            val > 0 ? "bg-green-500/20 text-green-400"
            : val < 0 ? "bg-red-500/20 text-red-400"
            : "bg-neutral-700 text-neutral-400"
          }`}>
            {key}: {val > 0 ? "+" : ""}{val}
            <InfoTooltip
              title={key}
              description={COMPONENT_DESCRIPTIONS[key] || '국면 점수 구성 요소'}
              frequency="5분 캐시 / 5분 cron"
              source="자체 점수화"
            />
          </span>
        ))}
      </div>
    </div>
  );
}
