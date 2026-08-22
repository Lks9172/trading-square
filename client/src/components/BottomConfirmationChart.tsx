type Point = {
  date: string;
  value: number;
  vwap20?: number | null;
  obvPressure20Pct?: number | null;
  sma20?: number | null;
  sma50?: number | null;
  sma100?: number | null;
  sma200?: number | null;
  channelLower?: number | null;
  channelMid?: number | null;
  channelUpper?: number | null;
};
type Marker = { kind: 'peak' | 'candidate' | 'retest' | 'confirm' | 'current'; date: string; label: string; value: number };
type PriceZone = { lower: number; upper: number; touches: number; strength: number; roleFlip: boolean };
type FibonacciLevel = { ratio: number; price: number; label: string };
type Structure = {
  supportZone?: PriceZone | null;
  resistanceZone?: PriceZone | null;
  fibonacci?: {
    levels: FibonacciLevel[];
    nearestRatio?: number | null;
    confluenceScore?: number;
  } | null;
};

function markerTone(kind: Marker['kind']) {
  switch (kind) {
    case 'peak':
      return { dot: '#f87171', text: 'text-rose-200', bg: 'border-rose-500/20 bg-rose-500/10' };
    case 'candidate':
      return { dot: '#fbbf24', text: 'text-amber-200', bg: 'border-amber-500/20 bg-amber-500/10' };
    case 'retest':
      return { dot: '#67e8f9', text: 'text-cyan-200', bg: 'border-cyan-500/20 bg-cyan-500/10' };
    case 'confirm':
      return { dot: '#34d399', text: 'text-emerald-200', bg: 'border-emerald-500/20 bg-emerald-500/10' };
    default:
      return { dot: '#ffffff', text: 'text-white', bg: 'border-white/10 bg-white/5' };
  }
}

export function BottomConfirmationChart({
  points,
  markers,
  structure,
}: {
  points: Point[];
  markers: Marker[];
  structure?: Structure | null;
}) {
  if (!points.length) {
    return <div className="rounded-xl border border-white/10 bg-black/15 p-4 text-xs text-[var(--muted)]">가격 차트 데이터 부족</div>;
  }

  const width = 720;
  const height = 220;
  const plottedValues = points.flatMap((point) => [
    point.value,
    point.vwap20,
    point.sma20,
    point.sma50,
    point.sma200,
    point.channelLower,
    point.channelUpper,
  ].filter((value): value is number => typeof value === 'number'));
  if (structure?.supportZone) plottedValues.push(structure.supportZone.lower, structure.supportZone.upper);
  if (structure?.resistanceZone) plottedValues.push(structure.resistanceZone.lower, structure.resistanceZone.upper);
  const fibonacciLevels = structure?.fibonacci?.levels?.filter(
    (level) => Number.isFinite(level.ratio) && Number.isFinite(level.price) && level.price > 0,
  ) ?? [];
  plottedValues.push(...fibonacciLevels.map((level) => level.price));
  const min = Math.min(...plottedValues);
  const max = Math.max(...plottedValues);
  const range = max - min || 1;
  const padX = 18;
  const padY = 16;
  const getX = (index: number) => padX + (index / Math.max(points.length - 1, 1)) * (width - padX * 2);
  const getY = (value: number) => height - padY - ((value - min) / range) * (height - padY * 2);
  const seriesPath = (field: keyof Point) => points.map((point, index) => typeof point[field] === 'number'
    ? `${index === 0 || typeof points[index - 1]?.[field] !== 'number' ? 'M' : 'L'} ${getX(index).toFixed(1)} ${getY(point[field] as number).toFixed(1)}`
    : '').filter(Boolean).join(' ');
  const path = seriesPath('value');
  const vwapPath = seriesPath('vwap20');
  const sma20Path = seriesPath('sma20');
  const sma50Path = seriesPath('sma50');
  const sma200Path = seriesPath('sma200');
  const channelLowerPath = seriesPath('channelLower');
  const channelMidPath = seriesPath('channelMid');
  const channelUpperPath = seriesPath('channelUpper');
  const channelPoints = points
    .map((point, index) => ({ point, index }))
    .filter(({ point }) => typeof point.channelLower === 'number' && typeof point.channelUpper === 'number');
  const channelArea = channelPoints.length > 1
    ? [
      ...channelPoints.map(({ point, index }, pathIndex) => `${pathIndex === 0 ? 'M' : 'L'} ${getX(index).toFixed(1)} ${getY(point.channelUpper as number).toFixed(1)}`),
      ...[...channelPoints].reverse().map(({ point, index }) => `L ${getX(index).toFixed(1)} ${getY(point.channelLower as number).toFixed(1)}`),
      'Z',
    ].join(' ')
    : '';
  const markerMap = new Map(points.map((point, index) => [point.date, index]));

  return (
    <div className="rounded-2xl border border-white/10 bg-black/15 p-4">
      <svg viewBox={`0 0 ${width} ${height}`} className="h-56 w-full">
        <line x1={padX} y1={height - padY} x2={width - padX} y2={height - padY} stroke="rgba(255,255,255,0.12)" />
        {structure?.supportZone ? (
          <rect
            x={padX}
            y={getY(structure.supportZone.upper)}
            width={width - padX * 2}
            height={Math.max(2, getY(structure.supportZone.lower) - getY(structure.supportZone.upper))}
            fill="rgba(52,211,153,0.09)"
          />
        ) : null}
        {structure?.resistanceZone ? (
          <rect
            x={padX}
            y={getY(structure.resistanceZone.upper)}
            width={width - padX * 2}
            height={Math.max(2, getY(structure.resistanceZone.lower) - getY(structure.resistanceZone.upper))}
            fill="rgba(251,113,133,0.08)"
          />
        ) : null}
        {fibonacciLevels.map((level) => {
          const nearest = typeof structure?.fibonacci?.nearestRatio === 'number'
            && Math.abs(structure.fibonacci.nearestRatio - level.ratio) < 0.0005;
          const y = getY(level.price);
          return (
            <g key={`fib-${level.ratio}`}>
              <line
                x1={padX}
                y1={y}
                x2={width - padX}
                y2={y}
                stroke={nearest ? 'rgba(250,204,21,0.85)' : 'rgba(251,191,36,0.34)'}
                strokeWidth={nearest ? 1.6 : 0.9}
                strokeDasharray={nearest ? '7 4' : '3 5'}
              />
              <text
                x={width - padX - 2}
                y={Math.max(10, y - 2)}
                textAnchor="end"
                fontSize="8"
                fill={nearest ? 'rgba(254,240,138,0.95)' : 'rgba(253,230,138,0.62)'}
              >
                {level.ratio.toFixed(3)}
              </text>
            </g>
          );
        })}
        {channelArea ? <path d={channelArea} fill="rgba(103,232,249,0.055)" /> : null}
        {channelLowerPath ? <path d={channelLowerPath} fill="none" stroke="rgba(103,232,249,0.35)" strokeWidth="1" strokeDasharray="3 4" /> : null}
        {channelMidPath ? <path d={channelMidPath} fill="none" stroke="rgba(103,232,249,0.22)" strokeWidth="1" /> : null}
        {channelUpperPath ? <path d={channelUpperPath} fill="none" stroke="rgba(103,232,249,0.35)" strokeWidth="1" strokeDasharray="3 4" /> : null}
        {sma200Path ? <path d={sma200Path} fill="none" stroke="#c084fc" strokeWidth="1.2" strokeDasharray="7 4" /> : null}
        {sma50Path ? <path d={sma50Path} fill="none" stroke="#60a5fa" strokeWidth="1.1" opacity="0.8" /> : null}
        {sma20Path ? <path d={sma20Path} fill="none" stroke="#34d399" strokeWidth="1.1" opacity="0.8" /> : null}
        <path d={path} fill="none" stroke="#67e8f9" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
        {vwapPath ? <path d={vwapPath} fill="none" stroke="#fbbf24" strokeWidth="1.8" strokeDasharray="6 4" strokeLinecap="round" strokeLinejoin="round" /> : null}
        {markers.map((marker) => {
          const idx = markerMap.get(marker.date);
          if (idx === undefined) return null;
          const x = getX(idx);
          const y = getY(marker.value);
          const tone = markerTone(marker.kind);
          return (
            <g key={`${marker.kind}-${marker.date}`}>
              <line x1={x} y1={padY} x2={x} y2={height - padY} stroke={tone.dot} strokeOpacity="0.25" strokeDasharray="4 4" />
              <circle cx={x} cy={y} r="4.5" fill={tone.dot} />
            </g>
          );
        })}
      </svg>
      <div className="mt-2 flex items-center justify-between text-[11px] text-[var(--muted)]">
        <span>{points[0]?.date}</span>
        <span>{points.at(-1)?.date}</span>
      </div>
      <div className="mt-3 flex flex-wrap gap-2 text-[11px]">
        {vwapPath ? <span className="rounded-full border border-amber-500/20 bg-amber-500/10 px-2 py-1 text-amber-200">노랑 점선 · 20일 일봉 VWAP proxy</span> : null}
        {sma20Path ? <span className="rounded-full border border-emerald-500/20 bg-emerald-500/10 px-2 py-1 text-emerald-100">초록 · 20일선</span> : null}
        {sma50Path ? <span className="rounded-full border border-blue-500/20 bg-blue-500/10 px-2 py-1 text-blue-100">파랑 · 50일선</span> : null}
        {sma200Path ? <span className="rounded-full border border-purple-500/20 bg-purple-500/10 px-2 py-1 text-purple-100">보라 점선 · 200일선</span> : null}
        {channelArea ? <span className="rounded-full border border-cyan-500/20 bg-cyan-500/10 px-2 py-1 text-cyan-100">청록 밴드 · 252일 회귀 채널</span> : null}
        {structure?.supportZone ? <span className="rounded-full border border-emerald-500/20 bg-emerald-500/10 px-2 py-1 text-emerald-100">녹색 영역 · 지지 구간</span> : null}
        {structure?.resistanceZone ? <span className="rounded-full border border-rose-500/20 bg-rose-500/10 px-2 py-1 text-rose-100">적색 영역 · 저항 구간</span> : null}
        {fibonacciLevels.length > 0 ? (
          <span className="rounded-full border border-yellow-500/20 bg-yellow-500/10 px-2 py-1 text-yellow-100">
            황색 점선 · 주요 파동 피보 0.236~0.786
            {typeof structure?.fibonacci?.confluenceScore === 'number'
              ? ` · 합치 ${structure.fibonacci.confluenceScore}`
              : ''}
          </span>
        ) : null}
        {markers.map((marker) => {
          const tone = markerTone(marker.kind);
          return (
            <span key={`${marker.kind}-${marker.date}-legend`} className={`rounded-full border px-2 py-1 ${tone.bg} ${tone.text}`}>
              {marker.label} · {marker.date}
            </span>
          );
        })}
      </div>
    </div>
  );
}
