"use client";

/**
 * 7-렌즈 요약 패널 (video4 §정책·지정학·유동성·매크로·모멘텀·기관·차트)
 * 각 렌즈 = 시장 판단의 독립 축. 현 스냅샷에서 각 렌즈의 상태를 1줄로 요약.
 * "단일 지표 의존 금지" 철학을 UI 레벨에서 강제.
 */

interface RawPoint { value: number; date: string; }
interface DerivedPoint { value: number; date: string; formula: string; }

interface CalendarEvent {
  date: string;
  name: string;
  category: string;
  daysUntil: number;
  importance: 'high' | 'medium';
}

interface Props {
  raw?: Record<string, RawPoint>;
  derived?: Record<string, DerivedPoint>;
  meta?: {
    autoInputs?: { policyDirection: number; geoRisk: number; cbBuying: boolean; ismPmi: number | null } | null;
    profile?: { manualInputs?: { policyDirection: number; geoRisk: number; cbBuying: boolean; ismPmi: number | null } };
    calendar?: CalendarEvent[];
    smartMoney?: { score: number; insiderBuyRatio: number } | null;
  };
}

type LensColor = 'green' | 'blue' | 'amber' | 'red' | 'neutral';

function lensStyle(color: LensColor) {
  const m: Record<LensColor, { bg: string; border: string; text: string }> = {
    green:   { bg: 'bg-green-500/10',   border: 'border-green-500/40',   text: 'text-green-300' },
    blue:    { bg: 'bg-blue-500/10',    border: 'border-blue-500/40',    text: 'text-blue-300' },
    amber:   { bg: 'bg-amber-500/10',   border: 'border-amber-500/40',   text: 'text-amber-300' },
    red:     { bg: 'bg-red-500/10',     border: 'border-red-500/40',     text: 'text-red-300' },
    neutral: { bg: 'bg-neutral-500/10', border: 'border-neutral-500/30', text: 'text-neutral-300' },
  };
  return m[color];
}

interface Lens {
  icon: string;
  title: string;
  color: LensColor;
  status: string;
  metrics: Array<{ label: string; value: string }>;
}


function topSectorQuality(derived?: Record<string, DerivedPoint>) {
  if (!derived) return null;
  const qualityEntries = Object.entries(derived)
    .filter(([key, point]) => key.startsWith('SECTOR_QUALITY_TOTAL_') && typeof point.value === 'number')
    .sort((a, b) => b[1].value - a[1].value);
  if (qualityEntries.length === 0) return null;
  const [key, point] = qualityEntries[0];
  const suffix = key.replace('SECTOR_QUALITY_TOTAL_', '');
  return { suffix, total: point.value, policy: derived[`SECTOR_POLICY_SUPPORT_${suffix}`]?.value, demand: derived[`SECTOR_STRUCTURAL_DEMAND_${suffix}`]?.value, supply: derived[`SECTOR_SUPPLY_TIGHTNESS_${suffix}`]?.value };
}

function computeLenses(raw?: Record<string, RawPoint>, derived?: Record<string, DerivedPoint>, meta?: Props['meta']): Lens[] {
  const r = raw || {};
  const d = derived || {};
  const manual = meta?.profile?.manualInputs;
  const autoInp = meta?.autoInputs;
  const inputs = manual || autoInp;

  const num = (k: string): number | undefined => d[k]?.value;
  const rNum = (k: string): number | undefined => r[k]?.value;
  const fmt = (v?: number, digits = 1): string => (v === undefined || v === null ? '—' : v.toFixed(digits));

  // 1. 정책
  const policyDir = inputs?.policyDirection ?? 0;
  const fomcEvent = (meta?.calendar || []).find((e) => e.category === 'FOMC' && e.daysUntil >= 0);
  let policyColor: LensColor = 'neutral';
  if (policyDir >= 1) policyColor = 'green';
  else if (policyDir <= -1) policyColor = 'red';
  const policyStatus = policyDir >= 1 ? '완화 방향' : policyDir <= -1 ? '긴축 방향' : '중립';

  // 2. 지정학
  const geoRisk = inputs?.geoRisk ?? 2;
  const ovx = rNum('OVX');
  let geoColor: LensColor = 'neutral';
  if (geoRisk >= 3) geoColor = 'red';
  else if (geoRisk >= 2) geoColor = 'amber';
  else geoColor = 'green';

  // 3. 유동성
  const liqDir = num('LIQUIDITY_DIRECTION') ?? 0;
  let liqColor: LensColor = 'neutral';
  if (liqDir >= 2) liqColor = 'green';
  else if (liqDir >= 1) liqColor = 'blue';
  else if (liqDir <= -2) liqColor = 'red';
  else if (liqDir <= -1) liqColor = 'amber';

  // 4. 매크로
  const icsaLabel = num('ICSA_REGIME_LABEL') ?? 0;
  const cpiPressure = num('CPI_OIL_LAG_PRESSURE') ?? 0;
  const icsa = rNum('ICSA');
  let macroColor: LensColor = 'neutral';
  if (icsaLabel >= 2) macroColor = 'green';
  else if (icsaLabel === 1) macroColor = 'blue';
  else if (icsaLabel === -2) macroColor = 'red';
  else if (icsaLabel === -1) macroColor = 'amber';
  const macroStatus = {
    2: '안정 확장',
    1: '조정 매수 기회',
    0: '중립',
    '-1': '모멘텀 둔화',
    '-2': '구조적 위험',
  }[String(icsaLabel)] || '중립';

  // 5. 모멘텀
  const nasdaqDisp = num('NASDAQ_DISPARITY');
  const kospiDisp = num('KOSPI_DISPARITY');
  let momColor: LensColor = 'neutral';
  const nq = nasdaqDisp ?? 0;
  if (nq <= -20) momColor = 'green';
  else if (nq <= -10) momColor = 'blue';
  else if (nq >= 15) momColor = 'red';
  else if (nq >= 5) momColor = 'amber';

  // 6. 기관
  const smScore = meta?.smartMoney?.score ?? 0;
  const foreignNet20D = num('KOSPI_FOREIGN_NET_20D') ?? 0;
  let instColor: LensColor = 'neutral';
  if (smScore >= 1 && foreignNet20D > 0) instColor = 'green';
  else if (smScore >= 1 || foreignNet20D > 0) instColor = 'blue';
  else if (smScore <= -1 && foreignNet20D < 0) instColor = 'red';
  else if (smScore <= -1 || foreignNet20D < 0) instColor = 'amber';

  // 7. 차트
  const overheated = num('OVERHEATED');
  const monthlyExhaustion = num('NASDAQ_MONTHLY_EXHAUSTION') || num('KOSPI_MONTHLY_EXHAUSTION');
  const fiscalStress = num('FISCAL_STRESS');
  const wBottomN = num('NASDAQ_W_BOTTOM');
  const wBottomK = num('KOSPI_W_BOTTOM');
  let chartColor: LensColor = 'neutral';
  let chartStatus = '중립';
  if (overheated === 1 || monthlyExhaustion === 1) { chartColor = 'red'; chartStatus = '과열·소진 경고'; }
  else if (fiscalStress === 1) { chartColor = 'amber'; chartStatus = '재정 리스크'; }
  else if (wBottomN === 1 || wBottomK === 1) { chartColor = 'green'; chartStatus = 'W 반등 확정'; }
  else chartStatus = '정상';

  return [
    {
      icon: '🏛️',
      title: '정책',
      color: policyColor,
      status: policyStatus,
      metrics: [
        { label: 'policyDirection', value: String(policyDir) },
        { label: 'FOMC 임박', value: fomcEvent ? `D${fomcEvent.daysUntil === 0 ? '' : '+' + fomcEvent.daysUntil}` : '—' },
      ],
    },
    {
      icon: '🌍',
      title: '지정학',
      color: geoColor,
      status: geoRisk >= 3 ? '리스크 확대' : geoRisk >= 2 ? '경계' : '완화',
      metrics: [
        { label: 'GPR', value: String(geoRisk) },
        { label: 'OVX', value: fmt(ovx, 1) },
      ],
    },
    {
      icon: '💧',
      title: '유동성',
      color: liqColor,
      status: liqDir >= 2 ? '확장 가속' : liqDir >= 1 ? '완만 확장' : liqDir <= -2 ? '긴축 강화' : liqDir <= -1 ? '위축' : '중립',
      metrics: [
        { label: 'LIQ 방향', value: `${liqDir > 0 ? '+' : ''}${liqDir}` },
        { label: 'M2 YoY', value: `${fmt(num('GLOBAL_M2_PROXY'), 1)}%` },
      ],
    },
    {
      icon: '📈',
      title: '매크로',
      color: macroColor,
      status: macroStatus,
      metrics: [
        { label: 'ICSA', value: icsa ? `${Math.round(icsa / 1000)}K` : '—' },
        { label: 'CPI 유가압력', value: `${cpiPressure > 0 ? '+' : ''}${cpiPressure}` },
      ],
    },
    {
      icon: '⚡',
      title: '모멘텀',
      color: momColor,
      status: nq <= -20 ? '과매도' : nq <= -10 ? '조정' : nq >= 15 ? '과열' : nq >= 5 ? '과열 근접' : '정상',
      metrics: [
        { label: 'NASDAQ 이격', value: `${fmt(nasdaqDisp, 1)}%` },
        { label: 'KOSPI 이격', value: `${fmt(kospiDisp, 1)}%` },
      ],
    },
    {
      icon: '🏦',
      title: '기관',
      color: instColor,
      status: smScore >= 1 && foreignNet20D > 0 ? '내·외국인 매수' : smScore <= -1 || foreignNet20D < 0 ? '기관 매도 우위' : '혼조',
      metrics: [
        { label: 'SmartMoney', value: String(smScore) },
        { label: '외국인 20D', value: `${foreignNet20D >= 0 ? '+' : ''}${Math.round(foreignNet20D / 1000)}조` },
      ],
    },
    {
      icon: '📊',
      title: '차트',
      color: chartColor,
      status: chartStatus,
      metrics: [
        { label: '과열', value: overheated === 1 ? 'ON' : 'OFF' },
        { label: 'W반등', value: wBottomN === 1 ? '나스닥' : wBottomK === 1 ? '코스피' : 'OFF' },
      ],
    },
  ];
}

export function LensPanel({ raw, derived, meta }: Props) {
  const lenses = computeLenses(raw, derived, meta);
  const sectorQuality = topSectorQuality(derived);

  return (
    <div className="rounded-xl border border-[var(--card-border)] bg-[var(--card)] p-4 sm:p-5">
      <h3 className="text-base sm:text-lg font-semibold mb-1">7-렌즈 종합</h3>
      <p className="text-[11px] sm:text-xs text-[var(--muted)] mb-4">
        정책·지정학·유동성·매크로·모멘텀·기관·차트 (영상4 §04 &quot;단일 지표 의존 금지&quot;)
      </p>

      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-7 gap-2">
        {lenses.map((l) => {
          const s = lensStyle(l.color);
          return (
            <div
              key={l.title}
              className={`rounded-lg border ${s.border} ${s.bg} p-2.5 min-w-0`}
            >
              <div className="flex items-center gap-1 mb-1">
                <span className="text-base">{l.icon}</span>
                <span className="text-xs font-semibold truncate">{l.title}</span>
              </div>
              <div className={`text-xs font-semibold ${s.text} mb-2 truncate`}>{l.status}</div>
              <div className="space-y-0.5">
                {l.metrics.map((m, i) => (
                  <div key={i} className="flex items-center justify-between text-[10px]">
                    <span className="text-[var(--muted)] truncate">{m.label}</span>
                    <span className="font-mono text-neutral-300 ml-1">{m.value}</span>
                  </div>
                ))}
              </div>
            </div>
          );
        })}
      </div>

      {sectorQuality && (
        <div className="mt-3 rounded-lg border border-cyan-500/20 bg-cyan-500/5 p-3">
          <div className="text-xs font-semibold text-cyan-200 mb-1">섹터 품질 요약</div>
          <div className="flex flex-wrap items-center gap-2 text-[11px] sm:text-xs">
            <span className="font-semibold text-white">{sectorQuality.suffix}</span>
            <span className="text-[var(--muted)]">종합 {Math.round(sectorQuality.total)}</span>
            {typeof sectorQuality.policy === 'number' && <span className="text-cyan-200">정책 {Math.round(sectorQuality.policy)}</span>}
            {typeof sectorQuality.demand === 'number' && <span className="text-blue-200">수요 {Math.round(sectorQuality.demand)}</span>}
            {typeof sectorQuality.supply === 'number' && <span className="text-amber-200">공급 {Math.round(sectorQuality.supply)}</span>}
          </div>
        </div>
      )}
    </div>
  );
}
