"use client";

import { InfoTooltip } from "./InfoTooltip";

const REGIME_CONFIG: Record<string, { emoji: string; color: string; label: string; action: string }> = {
  RISK_ON:        { emoji: "🟢", color: "border-green-500/50 bg-green-500/10", label: "위험선호", action: "공격적 비중 유지" },
  NEUTRAL:        { emoji: "🔵", color: "border-blue-500/50 bg-blue-500/10",   label: "중립",     action: "현 비중 유지, 관망" },
  CAUTION:        { emoji: "🟡", color: "border-yellow-500/50 bg-yellow-500/10", label: "경계",   action: "신규 매수 자제" },
  CORRECTION:     { emoji: "🟠", color: "border-orange-500/50 bg-orange-500/10", label: "조정",   action: "분할매수 준비/시작" },
  PANIC_BUT_OK:   { emoji: "🔴", color: "border-red-500/50 bg-red-500/10",     label: "공포(체력 유지)", action: "적극 분할매수" },
  RECESSION_RISK: { emoji: "⚫", color: "border-neutral-500/50 bg-neutral-500/10", label: "침체 위험", action: "현금 비중 극대화" },
};

const COMPONENT_DESCRIPTIONS: Record<string, string> = {
  vix: '변동성/공포 수준. 높을수록 위험자산에 부정적',
  yieldCurve: '장단기 금리차. 역전될수록 경기침체 위험 증가',
  hySpread: '하이일드 스프레드. 신용시장 스트레스 지표',
  joblessClaims: '신규 실업수당. 고용 악화 속도',
  nasdaqDisparity: '나스닥 200DMA 대비 괴리율',
  finStress: '세인트루이스 금융스트레스지수',
  dxy: '달러 강세/약세. 약세면 금·신흥국 우호',
  liquidityDir: '유동성 방향. TGA/RRP/MMF 변동 종합. 양수=시장유입, 음수=흡수',
  wti: 'WTI 유가. $100↑ 인플레 우려, $65↓ 경기 둔화 우려',
  globalM2: '글로벌 M2 프록시. 유로+일본 광의통화 성장률. 양수=글로벌 유동성 확장',
  sectorMomentum: '섹터 모멘텀. XLK/XLI 강세면 공격적, XLV/XLE 우세면 방어/에너지 주도',
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
  derived?: Record<string, { value: number | null; interpretation?: string }>;
}

export function RegimeHeader({ regime, derived }: Props) {
  const config = REGIME_CONFIG[regime.regime] || REGIME_CONFIG.NEUTRAL;
  // 20차 prominent 뱃지 — SCENARIO_GATE / FX_FOREIGN_DEVIATION / NEUTRAL_RATE
  const scenario = derived?.SCENARIO_GATE_A_B?.value;
  const scenarioLabel = scenario === 1 ? '시나리오 A 추세재개' : scenario === -1 ? '시나리오 B 박스이탈' : '시나리오 분기점';
  const scenarioColor = scenario === 1 ? 'border-emerald-600 bg-emerald-950/40 text-emerald-200' : scenario === -1 ? 'border-red-600 bg-red-950/40 text-red-200' : 'border-yellow-600 bg-yellow-950/30 text-yellow-200';
  const fxDev = derived?.FX_FOREIGN_DEVIATION_RATIO?.value;
  const neutralRate = derived?.NEUTRAL_RATE_TEMPERATURE?.value;
  const neutralRateLabel = neutralRate === 2 ? '🔴 빨간불' : neutralRate === 1 ? '🟡 노란불' : neutralRate === 0 ? '🟢 초록불' : neutralRate === -1 ? '🟢🟢 초완화' : null;
  const conviction = derived?.CONVICTION_SCORE_7AXIS?.value;
  const lev3 = derived?.LEVERAGE_TRIGGER_3OF3?.value;

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

      {/* 20차: 핵심 게이지 prominent */}
      <div className="flex flex-wrap gap-2 mb-3">
        {typeof conviction === 'number' && (
          <span className={`text-xs rounded px-2 py-1 border ${conviction >= 3 ? 'border-emerald-600 bg-emerald-950/40 text-emerald-200' : conviction <= -3 ? 'border-red-600 bg-red-950/40 text-red-200' : 'border-slate-700 bg-slate-900/40 text-slate-200'}`}>
            🎯 7축 확신 <span className="font-mono">{conviction >= 0 ? '+' : ''}{conviction}/7</span>
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
