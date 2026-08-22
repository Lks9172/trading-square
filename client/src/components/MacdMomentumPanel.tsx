export interface MacdSignalView {
  asOf: string;
  macd: number | null;
  signal: number | null;
  histogram: number | null;
  position: 'ABOVE_SIGNAL' | 'BELOW_SIGNAL' | 'AT_SIGNAL' | 'UNAVAILABLE';
  zeroRegime: 'ABOVE_ZERO' | 'BELOW_ZERO' | 'AT_ZERO' | 'UNAVAILABLE';
  latestCross: 'BULLISH_CROSS' | 'BEARISH_CROSS' | 'NONE' | 'UNAVAILABLE';
  crossDate: string | null;
  sessionsSinceCross: number | null;
  histogramState: 'EXPANDING_POSITIVE' | 'CONTRACTING_POSITIVE' | 'EXPANDING_NEGATIVE' | 'CONTRACTING_NEGATIVE' | 'FLAT' | 'UNAVAILABLE';
  divergence: 'BULLISH' | 'BEARISH' | 'NONE' | 'UNAVAILABLE';
  divergenceStartDate: string | null;
  divergenceEndDate: string | null;
  divergenceConfirmedDate: string | null;
  sessionsSinceDivergence: number | null;
  divergenceActive: boolean;
  sourcePointCount: number;
  methodology: string;
}

export interface MacdMultiTimeframeView {
  daily: MacdSignalView;
  weekly: MacdSignalView;
  currentWeekProvisional: boolean;
}

function number(value: number | null) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—';
  return value.toLocaleString('ko-KR', { maximumFractionDigits: 4 });
}

function crossLabel(value: MacdSignalView) {
  if (value.latestCross === 'BULLISH_CROSS') return '상방 골든크로스';
  if (value.latestCross === 'BEARISH_CROSS') return '하방 데드크로스';
  if (value.latestCross === 'NONE') return '관측 교차 없음';
  return '계산 대기';
}

function positionLabel(value: MacdSignalView) {
  if (value.position === 'ABOVE_SIGNAL') return 'MACD가 시그널 위';
  if (value.position === 'BELOW_SIGNAL') return 'MACD가 시그널 아래';
  if (value.position === 'AT_SIGNAL') return '시그널선 접점';
  return '데이터 부족';
}

function histogramLabel(value: MacdSignalView) {
  const labels: Record<MacdSignalView['histogramState'], string> = {
    EXPANDING_POSITIVE: '상승 모멘텀 확대',
    CONTRACTING_POSITIVE: '상승 모멘텀 둔화',
    EXPANDING_NEGATIVE: '하락 모멘텀 확대',
    CONTRACTING_NEGATIVE: '하락 모멘텀 둔화',
    FLAT: '모멘텀 정체',
    UNAVAILABLE: '계산 대기',
  };
  return labels[value.histogramState];
}

function divergenceLabel(value: MacdSignalView) {
  if (value.divergence === 'BULLISH') return value.divergenceActive ? '상승 다이버전스 ON' : '과거 상승 다이버전스';
  if (value.divergence === 'BEARISH') return value.divergenceActive ? '하락 다이버전스 ON' : '과거 하락 다이버전스';
  if (value.divergence === 'NONE') return '다이버전스 없음';
  return '계산 대기';
}

function tone(value: MacdSignalView) {
  if (value.divergenceActive && value.divergence === 'BEARISH') return 'border-rose-500/25 bg-rose-500/10 text-rose-50';
  if (value.divergenceActive && value.divergence === 'BULLISH') return 'border-emerald-500/25 bg-emerald-500/10 text-emerald-50';
  if (value.position === 'ABOVE_SIGNAL') return 'border-emerald-500/20 bg-emerald-500/5 text-emerald-50';
  if (value.position === 'BELOW_SIGNAL') return 'border-rose-500/20 bg-rose-500/5 text-rose-50';
  return 'border-white/10 bg-black/15 text-white/80';
}

function SignalCard({ label, value, provisional }: { label: string; value: MacdSignalView; provisional?: boolean }) {
  const crossAge = value.sessionsSinceCross === null ? '' : ` · ${value.sessionsSinceCross}${label === '주봉' ? '주' : '거래일'} 전`;
  const crossTone = value.latestCross === 'BULLISH_CROSS'
    ? 'border-emerald-500/25 bg-emerald-500/10 text-emerald-100'
    : value.latestCross === 'BEARISH_CROSS'
      ? 'border-rose-500/25 bg-rose-500/10 text-rose-100'
      : 'border-white/10 bg-black/15 text-white/60';
  return (
    <div className={`rounded-xl border p-3 ${tone(value)}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="font-medium">{label} MACD(12·26·9)</div>
        <div className="flex gap-1 text-[10px]">
          {provisional ? <span className="rounded-full border border-amber-500/25 bg-amber-500/10 px-2 py-1 text-amber-100">진행 중 주봉</span> : null}
          <span className={`rounded-full border px-2 py-1 ${crossTone}`}>{crossLabel(value)}{crossAge}</span>
        </div>
      </div>
      <div className="mt-3 grid grid-cols-3 gap-2 text-xs">
        <div className="rounded-lg border border-white/10 bg-black/15 p-2"><div className="text-white/50">MACD</div><div className="mt-1 font-semibold">{number(value.macd)}</div></div>
        <div className="rounded-lg border border-white/10 bg-black/15 p-2"><div className="text-white/50">시그널</div><div className="mt-1 font-semibold">{number(value.signal)}</div></div>
        <div className="rounded-lg border border-white/10 bg-black/15 p-2"><div className="text-white/50">히스토그램</div><div className="mt-1 font-semibold">{number(value.histogram)}</div></div>
      </div>
      <div className="mt-3 flex flex-wrap gap-2 text-[11px]">
        <span className="rounded-full border border-white/10 bg-black/15 px-2.5 py-1">{positionLabel(value)}</span>
        <span className="rounded-full border border-white/10 bg-black/15 px-2.5 py-1">{histogramLabel(value)}</span>
        <span className={`rounded-full border px-2.5 py-1 ${value.divergenceActive && value.divergence === 'BULLISH' ? 'border-emerald-500/25 bg-emerald-500/10' : value.divergenceActive && value.divergence === 'BEARISH' ? 'border-rose-500/25 bg-rose-500/10' : 'border-white/10 bg-black/15'}`}>
          {divergenceLabel(value)}
          {value.sessionsSinceDivergence !== null ? ` · 확인 후 ${value.sessionsSinceDivergence}${label === '주봉' ? '주' : '거래일'}` : ''}
        </span>
      </div>
      {value.divergenceActive && value.divergenceStartDate && value.divergenceEndDate ? (
        <div className="mt-2 text-[10px] text-white/55">피벗 {value.divergenceStartDate} → {value.divergenceEndDate} · 우측 피벗 확인 완료</div>
      ) : null}
    </div>
  );
}

export function MacdMomentumPanel({ value }: { value: MacdMultiTimeframeView }) {
  return (
    <div data-testid="company-macd-momentum" className="mt-4 rounded-2xl border border-violet-500/20 bg-violet-500/5 p-4">
      <div className="text-sm font-medium text-violet-100">MACD 교차 · 다이버전스</div>
      <div className="mt-1 text-xs leading-relaxed text-[var(--muted)]">
        일봉은 타이밍, 주봉은 중기 확인입니다. 골든/데드크로스는 MACD와 시그널선의 교차이며 기존 50·200일 이동평균 교차와 다릅니다.
      </div>
      <div className="mt-3 grid grid-cols-1 gap-3 lg:grid-cols-2">
        <SignalCard label="일봉" value={value.daily} />
        <SignalCard label="주봉" value={value.weekly} provisional={value.currentWeekProvisional} />
      </div>
      <div className="mt-3 rounded-lg border border-amber-500/15 bg-amber-500/5 px-3 py-2 text-[11px] leading-relaxed text-amber-50/75">
        다이버전스는 종가 피벗의 우측 확인 후에만 ON 처리해 미래 데이터 누수를 막습니다. MACD는 후행 보조지표이므로 기업가치·거래량·가격 구조와 함께 사용하며 단독 매수·매도 신호가 아닙니다.
      </div>
    </div>
  );
}
