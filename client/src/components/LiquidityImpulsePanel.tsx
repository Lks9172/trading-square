"use client";

import { InfoTooltip } from "./InfoTooltip";

interface DerivedEntry {
  value: number | null;
  date?: string;
  eligibleForSignals?: boolean;
}

interface Props {
  derived: Record<string, DerivedEntry>;
}

function usable(entry: DerivedEntry | undefined): number | null {
  return !entry || entry.eligibleForSignals === false || entry.value == null ? null : entry.value;
}

function signed(value: number | null, digits = 1) {
  if (value == null) return "—";
  return `${value > 0 ? "+" : ""}${value.toFixed(digits)}`;
}

function direction(state: number | null) {
  if (state == null) return { label: "자료 부족", color: "text-slate-300", border: "border-slate-700" };
  if (state >= 2) return { label: "강한 확장", color: "text-emerald-300", border: "border-emerald-500/40" };
  if (state >= 1) return { label: "확장", color: "text-green-300", border: "border-green-500/30" };
  if (state <= -2) return { label: "강한 흡수", color: "text-red-300", border: "border-red-500/40" };
  if (state <= -1) return { label: "흡수", color: "text-orange-300", border: "border-orange-500/30" };
  return { label: "방향 혼조", color: "text-blue-200", border: "border-blue-500/30" };
}

function plumbingAxis(directionValue: number | null, positiveIsSupply: boolean) {
  if (directionValue == null) return "자료 부족";
  const threshold = positiveIsSupply ? 0.5 : 1;
  if (Math.abs(directionValue) < threshold) return "중립";
  const supply = positiveIsSupply ? directionValue > 0 : directionValue < 0;
  return supply ? "공급" : "흡수";
}

function Metric({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="rounded-lg border border-slate-800 bg-slate-950/45 p-3">
      <div className="flex items-center text-[11px] text-slate-400">
        {label}
        <InfoTooltip title={label} description={hint} frequency="주간 계산" source="Fed·NY Fed·Treasury/FRED" />
      </div>
      <div className="mt-1 text-lg font-semibold text-slate-100">{value}</div>
    </div>
  );
}

/**
 * Direction-first liquidity view derived from Fed assets, TGA and ON RRP.
 * It deliberately separates the current impulse, a one-off turn event and
 * transmission stress so that a large balance-sheet level is not called bullish.
 */
export function LiquidityImpulsePanel({ derived }: Props) {
  const level = usable(derived.NET_LIQUIDITY_LEVEL_TN);
  const impulse = usable(derived.NET_LIQUIDITY_IMPULSE_4W_BN);
  const acceleration = usable(derived.NET_LIQUIDITY_ACCELERATION_4W_BN);
  const state = usable(derived.NET_LIQUIDITY_IMPULSE_STATE);
  const turn = usable(derived.NET_LIQUIDITY_TURN_SIGNAL);
  const stress = usable(derived.LIQUIDITY_TRANSMISSION_STRESS_SCORE);
  const coverage = usable(derived.LIQUIDITY_TRANSMISSION_COVERAGE);
  const plumbingSignal = usable(derived.LIQUIDITY_PLUMBING_SIGNAL);
  const plumbingBullish = usable(derived.LIQUIDITY_PLUMBING_BULLISH_AXES);
  const plumbingBearish = usable(derived.LIQUIDITY_PLUMBING_BEARISH_AXES);
  const plumbingNeutral = usable(derived.LIQUIDITY_PLUMBING_NEUTRAL_AXES);
  const plumbingCoverage = usable(derived.LIQUIDITY_PLUMBING_CONFIDENCE);
  const reserveLevel = usable(derived.WRESBAL_LEVEL_TN);
  const tgaDirection = usable(derived.TGA_DIRECTION);
  const rrpDirection = usable(derived.RRP_DIRECTION);
  const reserveDirection = usable(derived.WRESBAL_DIRECTION);
  const tgaContribution = usable(derived.TGA_LIQUIDITY_CONTRIBUTION_4W_BN);
  const rrpContribution = usable(derived.RRP_LIQUIDITY_CONTRIBUTION_4W_BN);
  const tgaOffset = usable(derived.TGA_LAGGED_ISSUANCE_CONTEXT ?? derived.TGA_ISSUANCE_OFFSET_RISK) === 1;
  const issuanceSourceDate = derived.TREASURY_ISSUANCE_DIRECTION?.date;
  const rrpLow = usable(derived.RRP_BUFFER_LOW) === 1;
  const rrpRunway = usable(derived.RRP_BUFFER_PCT_OF_3Y_PEAK);
  const m2Speed = usable(derived.US_M2_3M_ANNUALIZED);
  const config = direction(state);
  const asOf = derived.NET_LIQUIDITY_IMPULSE_4W_BN?.date;
  const transmissionDataSufficient = coverage != null && coverage >= 67;

  if ([level, impulse, state].every((value) => value == null)) return null;

  return (
    <section className={`rounded-xl border ${config.border} bg-[var(--card)] p-4 sm:p-5`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <div className="flex items-center gap-1">
            <h2 className="text-base font-semibold sm:text-lg">미국 순유동성 방향·전환</h2>
            <InfoTooltip
              title="미국 순유동성 방향·전환"
              description="미국 순유동성은 같은 수요일의 연준 총자산(WALCL)에서 TGA 수요일 잔액(WDTGAL)과 ON RRP를 차감한 분석 프록시입니다. 총량보다 4주 변화와 구간 전환을 우선하며 수익 확률이나 단독 매수 신호가 아닙니다."
              frequency="주간"
              source="공식 잔액 기반 자체 계산"
            />
          </div>
          <p className="mt-1 text-[11px] text-slate-400">총량 → 방향 → 속도 → 전달 스트레스 순서로 확인</p>
        </div>
        <div className="text-right">
          <div className={`text-sm font-bold ${config.color}`}>{config.label}</div>
          <div className="text-[10px] text-slate-500">기준 {asOf ?? "—"}</div>
        </div>
      </div>

      <div className="mt-4 grid grid-cols-2 gap-2 lg:grid-cols-5">
        <Metric label="미국 순유동성" value={level == null ? "—" : `$${level.toFixed(2)}T`}
          hint="WALCL-WDTGAL-ON RRP. 같은 수요일 시점값을 사용하며 잔액이 크다는 사실만으로 상승을 뜻하지 않습니다." />
        <Metric label="4주 충격" value={impulse == null ? "—" : `${signed(impulse, 0)}B`}
          hint="현재 순유동성과 4주 전의 차이. +는 공급 방향, -는 흡수 방향입니다." />
        <Metric label="가속도" value={acceleration == null ? "—" : `${signed(acceleration, 0)}B`}
          hint="최근 4주 충격에서 직전 4주 충격을 뺀 값. 양수면 공급 속도가 개선되는 방향입니다." />
        <Metric label="4주 구간 전환" value={turn == null ? "—" : turn > 0 ? "확장 전환 ON" : turn < 0 ? "흡수 전환 ON" : "신규 전환 없음"}
          hint="최근 비중첩 4주 충격이 직전 4주 구간과 부호가 바뀌고 ±250억 달러를 넘을 때 켜집니다. 일별 정확한 교차시점이나 지속 상태가 아닙니다." />
        <Metric label="M2 최근 속도" value={m2Speed == null ? "—" : `${signed(m2Speed)}%`}
          hint="미국 M2의 최근 3개월 연율화 성장률. 월간 후행 지표이므로 순유동성 전환을 대체하지 않습니다." />
      </div>

      <div className="mt-2 grid grid-cols-2 gap-2 lg:grid-cols-5">
        <Metric label="TGA 4주 기여" value={tgaContribution == null ? "—" : `${signed(tgaContribution, 0)}B`}
          hint="TGA가 줄면 +, 재충전되면 -입니다. 현재 준비금 공급·흡수 방향이며 향후 국채 경매 예측이 아닙니다." />
        <Metric label="ON RRP 4주 기여" value={rrpContribution == null ? "—" : `${signed(rrpContribution, 0)}B`}
          hint="ON RRP 잔액 감소는 +로 표시합니다. 준비금 공급 방향이지 위험자산 직접 순유입은 아닙니다." />
        <Metric label="TGA 단기축" value={plumbingAxis(tgaDirection, false)}
          hint="최근 2주 평균과 직전 2주 평균의 변화입니다. 감소는 공급, 증가는 흡수 방향으로 분류합니다." />
        <Metric label="ON RRP 단기축" value={plumbingAxis(rrpDirection, false)}
          hint="최근 5개 관측 평균과 직전 5개 관측 평균의 변화입니다. 감소는 공급 방향이지만 실제 자산 매수를 뜻하지 않습니다." />
        <Metric label="준비금 단기축" value={plumbingAxis(reserveDirection, true)}
          hint="최근 2주 평균과 직전 2주 평균의 변화입니다. 증가는 공급, 감소는 흡수 방향으로 분류합니다." />
      </div>

      <div className="mt-3 flex flex-wrap gap-2 text-[11px]">
        {plumbingSignal != null && (
          <span className={`rounded-full px-2.5 py-1 ${plumbingSignal > 0 ? "bg-emerald-500/10 text-emerald-200" : plumbingSignal < 0 ? "bg-red-500/15 text-red-200" : "bg-blue-500/10 text-blue-200"}`}>
            현재 3축 {plumbingSignal >= 1.5 ? "전축 공급" : plumbingSignal >= .5 ? "공급 우위" : plumbingSignal <= -1.5 ? "전축 흡수" : plumbingSignal <= -.5 ? "흡수 우위" : "혼조"}
            {plumbingBullish == null || plumbingBearish == null ? "" : ` · 공급 ${plumbingBullish.toFixed(0)} / 흡수 ${plumbingBearish.toFixed(0)}${plumbingNeutral == null ? "" : ` / 중립 ${plumbingNeutral.toFixed(0)}`}`}
            {plumbingCoverage == null ? "" : ` · 데이터 ${plumbingCoverage.toFixed(0)}%`}
          </span>
        )}
        {reserveLevel != null && (
          <span className={`rounded-full px-2.5 py-1 ${reserveLevel < 3 ? "bg-orange-500/15 text-orange-200" : "bg-emerald-500/10 text-emerald-200"}`}>
            은행 준비금 ${reserveLevel.toFixed(2)}T · {reserveLevel < 3 ? "3T 모니터링선 아래" : "3T 모니터링선 이상"}
          </span>
        )}
        <span className={`rounded-full px-2.5 py-1 ${!transmissionDataSufficient ? "bg-slate-500/15 text-slate-300" : stress != null && stress >= 2 ? "bg-red-500/15 text-red-200" : "bg-emerald-500/10 text-emerald-200"}`}>
          {!transmissionDataSufficient ? "전달 데이터 부족" : `전달 스트레스 ${stress == null ? "—" : `${stress.toFixed(0)}/3`}`}{coverage == null ? "" : ` · 데이터 ${coverage.toFixed(0)}%`}
        </span>
        {tgaOffset && <span className="rounded-full bg-amber-500/15 px-2.5 py-1 text-amber-200">TGA 감소 + 최신 공표 분기 순거래 확대{issuanceSourceDate ? ` · 분기 기준 ${issuanceSourceDate}` : ""}</span>}
        {rrpLow && <span className="rounded-full bg-orange-500/15 px-2.5 py-1 text-orange-200">ON RRP 추가 감소 여지 낮음{rrpRunway == null ? "" : ` · 3년 고점의 ${rrpRunway.toFixed(1)}%`}</span>}
      </div>

      <p className="mt-3 text-[10px] leading-relaxed text-slate-500">
        미국 순유동성 확장이어도 신용·VIX·단기자금 스트레스가 높거나 데이터가 부족하면 위험자산 전달을 확정하지 않습니다. 준비금 3T는 공식 안전선이 아닌 모니터링 휴리스틱입니다. TGA 감소는 현재 준비금 공급이고 최신 공표 분기 순거래는 후행 맥락입니다. 후행 flow만으로 향후 재충전·경매를 예측하거나 현재 공급을 상쇄하지 않습니다.
      </p>
    </section>
  );
}
