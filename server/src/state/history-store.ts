import fs from 'fs/promises';
import path from 'path';
import { fetchFredHistoryFrom, FRED_SERIES } from '../collectors/fred';
import { fetchYahooHistoryYears, YAHOO_SYMBOLS } from '../collectors/yahoo';
import { fetchFearAndGreed } from '../collectors/cnn';
import { fetchAllSentiment } from '../collectors/sentiment';
import { classifyRegime } from '../engines/regime';
import { computeSignals } from '../engines/signals';
import { computeAllocation } from '../engines/allocation';
import { computeDerived } from '../engines/derived';
import { linearRegressionChannel } from '../engines/candles';
import type { OHLCCandle } from '../collectors/yahoo';
import { DEFAULT_PROFILE } from './cache';
import { DerivedIndicator, MarketDataPoint } from '../types/indicators';
import { withSpan } from '../observability/trace';

const DATA_DIR = path.resolve(process.cwd(), 'data');
const HISTORY_DIR = path.join(DATA_DIR, 'history');

const GUARANTEE = {
  FRED_YEARS: 10,
  YAHOO_YEARS: 5,
};

type HistoryPoint = { date: string; value: number };

function yearsAgo(years: number) {
  const d = new Date();
  d.setFullYear(d.getFullYear() - years);
  return d.toISOString().split('T')[0];
}

async function ensureDir() {
  await fs.mkdir(HISTORY_DIR, { recursive: true });
}

function filePath(source: string, key: string) {
  return path.join(HISTORY_DIR, `${source.toLowerCase()}-${key.toLowerCase()}.json`);
}

async function writeHistory(source: string, key: string, points: HistoryPoint[]) {
  await ensureDir();
  // 3차 감사 Fix #4: tmp→rename 원자적 쓰기. 크래시 중 JSON 부분쓰기로 인한
  // corruption 방지 (trancheStore 패턴과 동일).
  const finalPath = filePath(source, key);
  const tmpPath = `${finalPath}.tmp-${process.pid}-${Date.now()}`;
  await fs.writeFile(tmpPath, JSON.stringify(points));
  await fs.rename(tmpPath, finalPath);
}

function dateToTime(date: string) {
  return new Date(`${date}T00:00:00Z`).getTime();
}

function latestAtOrBefore(points: HistoryPoint[], date: string): number | null {
  const target = dateToTime(date);
  let latest: number | null = null;
  for (const p of points) {
    if (dateToTime(p.date) <= target) latest = p.value;
    else break;
  }
  return latest;
}

function buildRawForDate(date: string, histories: Record<string, HistoryPoint[]>): Record<string, MarketDataPoint> {
  const map: Record<string, MarketDataPoint> = {};
  const defs: Array<[string, string, MarketDataPoint['source']]> = [
    ['DGS10', 'fred', 'FRED'], ['T10YIE', 'fred', 'FRED'], ['T10Y2Y', 'fred', 'FRED'], ['VIXCLS', 'fred', 'FRED'],
    ['BAMLH0A0HYM2', 'fred', 'FRED'], ['STLFSI4', 'fred', 'FRED'], ['ICSA', 'fred', 'FRED'], ['UNRATE', 'fred', 'FRED'],
    ['DGS30', 'fred', 'FRED'],
    ['NASDAQ', 'yahoo', 'YAHOO'], ['GOLD', 'yahoo', 'YAHOO'], ['SILVER', 'yahoo', 'YAHOO'], ['COPPER', 'yahoo', 'YAHOO'],
    ['DXY', 'yahoo', 'YAHOO'], ['SP500', 'yahoo', 'YAHOO'], ['WTI', 'yahoo', 'YAHOO'], ['USDKRW', 'yahoo', 'YAHOO'],
    // 센티먼트 raw — KST 07:00 cron 의 appendSentimentDaily 가 daily append.
    // 과거 일자에서 PSYCH_SUBSCORE 재구성 입력이며, latestAtOrBefore 로 영업일 스냅.
    ['FEAR_GREED', 'cnn', 'CNN'],
    ['PC_RATIO_10D', 'sentiment', 'CBOE'],
    ['AAII_BULL_BEAR_SPREAD', 'sentiment', 'CALC'],
    ['NAAIM_EXPOSURE', 'sentiment', 'CALC'],
  ];

  for (const [key, source, srcType] of defs) {
    const arr = histories[`${source}:${key}`] || [];
    const value = latestAtOrBefore(arr, date);
    if (value !== null) {
      map[key] = { code: key, value, date, source: srcType };
    }
  }

  return map;
}

/**
 * date 시점 시계열로 200DMA 기반 파생지표 (SMA200/DISPARITY/DRAWDOWN/ABOVE_200DMA) 와
 * ±15% 구간 연속 일수(DISPARITY_STREAK_OVERHEATED/OVERSOLD) + CHASE_WARNING 까지 재구성.
 *
 * derived 의 라이브 동등 키 집합:
 *   {PREFIX}_SMA200, {PREFIX}_DISPARITY, {PREFIX}_DRAWDOWN, {PREFIX}_ABOVE_200DMA,
 *   {PREFIX}_DISPARITY_STREAK_OVERHEATED, {PREFIX}_DISPARITY_STREAK_OVERSOLD,
 *   {PREFIX}_CHASE_WARNING.
 */
function reconstruct200DmaSuite(
  date: string,
  history: HistoryPoint[],
  prefix: 'NASDAQ' | 'KOSPI',
): Record<string, DerivedIndicator> {
  const derived: Record<string, DerivedIndicator> = {};
  const eligible = history.filter((p) => dateToTime(p.date) <= dateToTime(date));
  if (eligible.length < 200) return derived;

  const latest200 = eligible.slice(-200);
  const sma200 = latest200.reduce((sum, p) => sum + p.value, 0) / latest200.length;
  const current = eligible[eligible.length - 1].value;
  const allTimeHigh = Math.max(...eligible.map((p) => p.value));
  const lower = prefix.toLowerCase();

  derived[`${prefix}_SMA200`] = { name: `${lower}_sma200`, value: sma200, date, formula: 'SMA200' };
  derived[`${prefix}_DISPARITY`] = {
    name: `${lower}_disparity_200`,
    value: ((current - sma200) / sma200) * 100,
    date,
    formula: '(P-SMA)/SMA*100',
  };
  derived[`${prefix}_DRAWDOWN`] = {
    name: `${lower}_drawdown`,
    value: ((current - allTimeHigh) / allTimeHigh) * 100,
    date,
    formula: '(P-ATH)/ATH*100',
  };
  derived[`${prefix}_ABOVE_200DMA`] = {
    name: `${lower}_above_200dma`,
    value: current > sma200 ? 1 : 0,
    date,
    formula: 'P>SMA200',
  };

  // 이격률 ±15% 연속 유지일 (live derived.ts 의 computeDisparityStreak 동일 로직).
  // 인덱스 0=최신 가정으로 작성된 라이브 함수 시그니처에 맞춰 reverse 시리즈 사용.
  const closesNewestFirst = [...eligible.map((p) => p.value)].reverse();
  let overStreak = 0;
  let underStreak = 0;
  for (const price of closesNewestFirst) {
    const disp = ((price - sma200) / sma200) * 100;
    if (disp >= 15) overStreak += 1;
    else break;
  }
  for (const price of closesNewestFirst) {
    const disp = ((price - sma200) / sma200) * 100;
    if (disp <= -15) underStreak += 1;
    else break;
  }
  if (overStreak > 0) {
    derived[`${prefix}_DISPARITY_STREAK_OVERHEATED`] = {
      name: `${lower}_disparity_streak_overheated`,
      value: overStreak,
      date,
      formula: '이격률 ≥ +15% 연속 유지일 (200DMA 기준)',
    };
  }
  if (underStreak > 0) {
    derived[`${prefix}_DISPARITY_STREAK_OVERSOLD`] = {
      name: `${lower}_disparity_streak_oversold`,
      value: underStreak,
      date,
      formula: '이격률 ≤ -15% 연속 유지일 (200DMA 기준)',
    };
  }
  if (overStreak >= 20 || underStreak >= 20) {
    derived[`${prefix}_CHASE_WARNING`] = {
      name: `${lower}_chase_warning`,
      value: 1,
      date,
      formula: '이격률 ±15% 구간 20일 이상 지속 → 추격/투매 경고',
    };
  }
  return derived;
}

/**
 * date 시점의 HYG/IEF 비율, 252일 z-score, CREDIT_STRESS_FLAG 재구성.
 * HY OAS(BAMLH0A0HYM2) 가 raw 에 있으면 600bp 임계 OR z<=-2 로 flag 결정.
 */
function reconstructCreditStress(
  date: string,
  hygHist: HistoryPoint[],
  iefHist: HistoryPoint[],
  hyOasRawValue: number | undefined,
): Record<string, DerivedIndicator> {
  const derived: Record<string, DerivedIndicator> = {};
  const target = dateToTime(date);

  // HY OAS bp 환산 (라이브 로직과 동일).
  let hyOasBp: number | null = null;
  if (hyOasRawValue !== undefined) {
    hyOasBp = hyOasRawValue > 50 ? hyOasRawValue : hyOasRawValue * 100;
    derived.CREDIT_HY_OAS_BP = {
      name: 'credit_hy_oas_bp',
      value: parseFloat(hyOasBp.toFixed(1)),
      date,
      formula: 'BAMLH0A0HYM2 (ICE BofA US HY OAS) — bp 환산',
    };
  }

  // HYG/IEF 비율 시리즈를 date 까지로 잘라 252일 z-score 계산.
  const iefMap = new Map<string, number>();
  for (const p of iefHist) {
    if (dateToTime(p.date) <= target) iefMap.set(p.date, p.value);
  }
  const ratios: number[] = [];
  let latestRatio: number | null = null;
  for (const h of hygHist) {
    if (dateToTime(h.date) > target) break;
    const ief = iefMap.get(h.date);
    if (ief && ief > 0 && h.value > 0) {
      const r = h.value / ief;
      ratios.push(r);
      latestRatio = r;
    }
  }

  let hygIefZ: number | null = null;
  if (latestRatio !== null) {
    derived.CREDIT_HYG_IEF_RATIO = {
      name: 'credit_hyg_ief_ratio',
      value: parseFloat(latestRatio.toFixed(4)),
      date,
      formula: 'Yahoo HYG 종가 / IEF 종가',
    };
    if (ratios.length >= 252) {
      const window = ratios.slice(-252);
      const mean = window.reduce((s, v) => s + v, 0) / window.length;
      const variance = window.reduce((s, v) => s + (v - mean) ** 2, 0) / window.length;
      const stdev = Math.sqrt(variance);
      if (stdev > 0) {
        hygIefZ = (latestRatio - mean) / stdev;
        derived.CREDIT_HYG_IEF_ZSCORE = {
          name: 'credit_hyg_ief_zscore',
          value: parseFloat(hygIefZ.toFixed(2)),
          date,
          formula: '(HYG/IEF 현재 - 252일 평균) / 252일 표준편차',
        };
      }
    }
  }

  if (hyOasBp !== null || hygIefZ !== null) {
    const stressHy = hyOasBp !== null && hyOasBp >= 600;
    const stressZ = hygIefZ !== null && hygIefZ <= -2;
    derived.CREDIT_STRESS_FLAG = {
      name: 'credit_stress_flag',
      value: stressHy || stressZ ? 1 : 0,
      date,
      formula: `HY OAS ≥ 600bp(${stressHy ? 'Y' : 'N'}) OR HYG/IEF z ≤ -2(${stressZ ? 'Y' : 'N'})`,
    };
  }
  return derived;
}

/**
 * date 시점의 WM2NS YoY%, 음→양 교차 후 경과일 재구성.
 * 라이브 derived.ts === 동일 시그니처: M2_YOY_PCT, M2_YOY_DELTA_3M, M2_YOY_CROSS_DAYS.
 */
function reconstructM2Yoy(
  date: string,
  m2Hist: HistoryPoint[],
): Record<string, DerivedIndicator> {
  const derived: Record<string, DerivedIndicator> = {};
  const target = dateToTime(date);
  const eligible = m2Hist.filter((p) => dateToTime(p.date) <= target);
  if (eligible.length < 60) return derived;

  const yoySeries: { date: string; yoy: number }[] = [];
  for (let i = 0; i < eligible.length; i += 1) {
    const cur = eligible[i];
    const targetDt = new Date(cur.date);
    targetDt.setFullYear(targetDt.getFullYear() - 1);
    const targetMs = targetDt.getTime();
    let past: { date: string; value: number } | null = null;
    for (let j = i - 1; j >= 0; j -= 1) {
      if (new Date(eligible[j].date).getTime() <= targetMs) {
        past = eligible[j];
        break;
      }
    }
    if (past && past.value > 0) {
      yoySeries.push({ date: cur.date, yoy: (cur.value / past.value - 1) * 100 });
    }
  }
  if (yoySeries.length === 0) return derived;
  const latest = yoySeries[yoySeries.length - 1];
  derived.M2_YOY_PCT = {
    name: 'm2_yoy_pct',
    value: parseFloat(latest.yoy.toFixed(2)),
    date,
    formula: 'WM2NS 현재 / 52주 전 - 1 (%)',
  };
  if (yoySeries.length >= 14) {
    const past3m = yoySeries[yoySeries.length - 14];
    derived.M2_YOY_DELTA_3M = {
      name: 'm2_yoy_delta_3m',
      value: parseFloat((latest.yoy - past3m.yoy).toFixed(2)),
      date,
      formula: 'M2 YoY 현재 - 약 13주 전 YoY (포인트)',
    };
  }
  if (latest.yoy > 0) {
    let crossDate: string | null = null;
    for (let i = yoySeries.length - 1; i > 0; i -= 1) {
      const prev = yoySeries[i - 1];
      const cur = yoySeries[i];
      if (prev.yoy < 0 && cur.yoy >= 0) {
        crossDate = cur.date;
        break;
      }
    }
    if (crossDate) {
      const daysElapsed = Math.floor(
        (new Date(latest.date).getTime() - new Date(crossDate).getTime()) / 86400000,
      );
      derived.M2_YOY_CROSS_DAYS = {
        name: 'm2_yoy_cross_days',
        value: daysElapsed,
        date,
        formula: `WM2NS YoY 음→양 교차(${crossDate}) 이후 경과일`,
      };
    } else {
      derived.M2_YOY_CROSS_DAYS = {
        name: 'm2_yoy_cross_days',
        value: null,
        date,
        formula: '히스토리 내 음→양 교차 미확인',
      };
    }
  } else {
    derived.M2_YOY_CROSS_DAYS = {
      name: 'm2_yoy_cross_days',
      value: null,
      date,
      formula: '현재 YoY 음수 — 교차 미발생',
    };
  }
  return derived;
}

/**
 * date 시점 raw 로부터 PSYCH_SUBSCORE 재구성 (live derived.ts 와 동일 가중치/정규화).
 * 컴포넌트: F&G(0~100→0~1) · P/C 10D(1.2~0.7→0~1 역정규화) · AAII spread(-40~+40) · NAAIM(0~100).
 * null 컴포넌트는 스킵 후 가중치 재정규화. 4개 모두 raw 에 있으면 valid=4.
 *
 * 라이브 derived.ts § "심리 서브스코어 (PSYCH_SUBSCORE)" 와 동일 시그니처 유지가 핵심.
 * 패스스루 derived (PC_RATIO_10D / AAII_BULL_BEAR_SPREAD / NAAIM_EXPOSURE) 도 함께 발급.
 */
function reconstructPsychSubscore(
  date: string,
  raw: Record<string, MarketDataPoint>,
): Record<string, DerivedIndicator> {
  const derived: Record<string, DerivedIndicator> = {};
  const fng = raw.FEAR_GREED?.value ?? null;
  const pcr10 = raw.PC_RATIO_10D?.value ?? null;
  const aaii = raw.AAII_BULL_BEAR_SPREAD?.value ?? null;
  const naaim = raw.NAAIM_EXPOSURE?.value ?? null;

  if (pcr10 !== null) derived.PC_RATIO_10D = { name: 'pc_ratio_10d', value: pcr10, date, formula: 'CBOE Put/Call Ratio 10일 이동평균' };
  if (aaii !== null) derived.AAII_BULL_BEAR_SPREAD = { name: 'aaii_bull_bear_spread', value: aaii, date, formula: 'AAII Bullish% - Bearish% (주간)' };
  if (naaim !== null) derived.NAAIM_EXPOSURE = { name: 'naaim_exposure', value: naaim, date, formula: 'NAAIM Exposure Index (주간 평균)' };

  const components: { v: number | null; w: number }[] = [];
  if (fng !== null && Number.isFinite(fng)) components.push({ v: Math.max(0, Math.min(1, fng / 100)), w: 0.25 });
  else components.push({ v: null, w: 0.25 });
  if (pcr10 !== null && Number.isFinite(pcr10)) {
    const c = Math.max(0.7, Math.min(1.2, pcr10));
    components.push({ v: Math.max(0, Math.min(1, (1.2 - c) / (1.2 - 0.7))), w: 0.25 });
  } else components.push({ v: null, w: 0.25 });
  if (aaii !== null && Number.isFinite(aaii)) {
    const c = Math.max(-40, Math.min(40, aaii));
    components.push({ v: (c + 40) / 80, w: 0.25 });
  } else components.push({ v: null, w: 0.25 });
  if (naaim !== null && Number.isFinite(naaim)) {
    const c = Math.max(0, Math.min(100, naaim));
    components.push({ v: c / 100, w: 0.25 });
  } else components.push({ v: null, w: 0.25 });

  const valid = components.filter((c) => c.v !== null);
  if (valid.length > 0) {
    const totalW = valid.reduce((s, c) => s + c.w, 0);
    const score = valid.reduce((s, c) => s + (c.v as number) * c.w, 0) / totalW;
    derived.PSYCH_SUBSCORE = {
      name: 'psych_subscore',
      value: parseFloat(score.toFixed(3)),
      date,
      formula: `F&G·P/C 10D·AAII·NAAIM 가중평균 (가중 0.25 각, null 스킵 후 재정규화, ${valid.length}/4)`,
    };
  }
  return derived;
}

/**
 * date 시점 OVERHEATED 재구성. live derived.ts 와 동일 룰:
 *   NASDAQ_DISPARITY > 20 AND F&G > 75   → 1 (탐욕성 과열)
 *   NASDAQ_DISPARITY > 15 AND VIX < 15   → 1 (변동성 과열)
 *   외                                   → 0 (단, 판단 입력이 모두 null 이면 키 미발급)
 *
 * NASDAQ_DISPARITY 는 reconstruct200DmaSuite 가 채운 derivedSoFar 에서 가져온다 →
 * 본 헬퍼는 반드시 200DMA suite 호출 이후에 실행.
 */
function reconstructOverheated(
  date: string,
  derivedSoFar: Record<string, DerivedIndicator>,
  raw: Record<string, MarketDataPoint>,
): Record<string, DerivedIndicator> {
  const out: Record<string, DerivedIndicator> = {};
  const nasdaqDisparity = derivedSoFar.NASDAQ_DISPARITY?.value ?? null;
  const fng = raw.FEAR_GREED?.value ?? null;
  const vixVal = raw.VIXCLS?.value ?? null;
  if (nasdaqDisparity === null) return out;
  if (nasdaqDisparity > 20 && fng !== null && fng > 75) {
    out.OVERHEATED = { name: 'overheated', value: 1, date, formula: '이격도+20%이상 AND F&G 75+ → 과열' };
  } else if (nasdaqDisparity > 15 && vixVal !== null && vixVal < 15) {
    out.OVERHEATED = { name: 'overheated', value: 1, date, formula: '이격도+15%이상 AND VIX<15 → 과열' };
  } else if (fng !== null || vixVal !== null) {
    out.OVERHEATED = { name: 'overheated', value: 0, date, formula: '과열 조건 미충족' };
  }
  return out;
}

/**
 * date 시점 BOND_VIGILANTE_SCORE/WARNING + FISCAL_STRESS 재구성.
 * 입력: dgs30Hist (FRED) · dxyHist (Yahoo) · raw.BAMLH0A0HYM2.
 *
 * 라이브 derived.ts § "채권 자경단 / 재정 리스크" 와 동일 룰:
 *   yieldRising = DGS30 20영업일 변화 ≥ +0.15%p
 *   dxyWeak     = DXY 현재 < 100  (DXY_TREND_LONG 은 history 만으론 재구성 부담 → 레벨 룰만 사용)
 *   hyWidening  = HY OAS ≥ 4.5
 * BOND_VIGILANTE_SCORE = 합. ≥2 → WARNING=1.
 * FISCAL_STRESS = (Δ20≥0.2 AND cur≥4.5) OR (Δ20≥0.3) → 1.
 *
 * STAGFLATION_WARNING/FISCAL_STRESS_HARD 는 다단계 derived 의존 (CPI_OIL_LAG_PRESSURE,
 * ICSA_REGIME_LABEL, ISM_PROXY · T10Y2Y 곡선 스티프닝) 으로 본 스코프에서는 제외 →
 * 향후 별도 헬퍼 (reconstructStagflation / reconstructFiscalHard) 로 분리.
 */
function reconstructBondVigilante(
  date: string,
  dgs30Hist: HistoryPoint[],
  dxyHist: HistoryPoint[],
  hyOasRaw: number | undefined,
): Record<string, DerivedIndicator> {
  const out: Record<string, DerivedIndicator> = {};
  const target = dateToTime(date);
  const dgs30Eligible = dgs30Hist.filter((p) => dateToTime(p.date) <= target);
  if (dgs30Eligible.length < 20) return out;

  const cur = dgs30Eligible[dgs30Eligible.length - 1].value;
  const past = dgs30Eligible[dgs30Eligible.length - 20].value;
  const delta20 = cur - past;

  out.DGS30_20D_CHANGE = {
    name: 'dgs30_20d_change',
    value: parseFloat(delta20.toFixed(3)),
    date,
    formula: 'DGS30 20영업일 변화폭 (%p)',
  };

  const fiscalStress = (delta20 >= 0.2 && cur >= 4.5) || (delta20 >= 0.3);
  out.FISCAL_STRESS = {
    name: 'fiscal_stress',
    value: fiscalStress ? 1 : 0,
    date,
    formula: `DGS30 20일 변화 ${delta20.toFixed(2)}p (현 ${cur.toFixed(2)}%) · 1=재정리스크/채권자경단 경고`,
  };

  const dxyEligible = dxyHist.filter((p) => dateToTime(p.date) <= target);
  const dxyVal = dxyEligible.length > 0 ? dxyEligible[dxyEligible.length - 1].value : null;
  const dxyWeak = dxyVal !== null && dxyVal < 100;
  const yieldRising = delta20 >= 0.15;
  const hyWidening = hyOasRaw !== undefined && hyOasRaw >= 4.5;
  const score = (yieldRising ? 1 : 0) + (dxyWeak ? 1 : 0) + (hyWidening ? 1 : 0);

  out.BOND_VIGILANTE_SCORE = {
    name: 'bond_vigilante_score',
    value: score,
    date,
    formula: `장기금리↑${yieldRising ? 'Y' : 'N'} + DXY약세${dxyWeak ? 'Y' : 'N'} + HY확대${hyWidening ? 'Y' : 'N'} → 3축 동시 (영상4 §137)`,
  };
  out.BOND_VIGILANTE_WARNING = {
    name: 'bond_vigilante_warning',
    value: score >= 2 ? 1 : 0,
    date,
    formula: score >= 2
      ? `채권 자경단 ${score}/3 충족 — 정책 신뢰 이탈 프리커서`
      : `3축 중 ${score}개만 충족 — 경보 미발동`,
  };
  return out;
}

/**
 * date 시점 STAGFLATION_SCORE/WARNING 재구성 (4차 감사 확장).
 *
 * 라이브 derived.ts § "스태그플레이션 레짐" 과 동일 룰:
 *   inflationRising = CPI_OIL_LAG_PRESSURE >= 1   (WTI 60일 변화율 ≥ +5%)
 *   growthSlowing   = ISM_PROXY < 50  OR  ICSA_REGIME_LABEL <= -1
 *   score = (inflation?1:0) + (growth?1:0),  warning = score==2
 *
 * 입력:
 *   - wtiHist: WTI daily history (CPI_OIL_LAG_PRESSURE 재구성)
 *   - indproHist: INDPRO 월간 history (ISM_PROXY 재구성)
 *   - icsaRaw: raw.ICSA (ICSA_REGIME_LABEL 재구성 — NASDAQ_ABOVE_200DMA 는 derivedSoFar 에서)
 *   - derivedSoFar: 앞서 reconstruct200DmaSuite 가 채운 NASDAQ_ABOVE_200DMA 조회용
 *
 * 라이브와 동일하게 **중간 파생지표(CPI_OIL_LAG_PRESSURE/ISM_PROXY/ICSA_REGIME_LABEL)** 도
 * 발급 → signals.ts 의 dv() 기반 셀렉터가 자연스럽게 재사용.
 */
function reconstructStagflation(
  date: string,
  wtiHist: HistoryPoint[],
  indproHist: HistoryPoint[],
  icsaRaw: number | null,
  derivedSoFar: Record<string, DerivedIndicator>,
): Record<string, DerivedIndicator> {
  const out: Record<string, DerivedIndicator> = {};
  const target = dateToTime(date);

  // 1) CPI_OIL_LAG_PRESSURE — WTI 60영업일 변화율 bin.
  let cpiPressureScore: number | null = null;
  const wtiEligible = wtiHist.filter((p) => dateToTime(p.date) <= target);
  if (wtiEligible.length >= 60) {
    const cur = wtiEligible[wtiEligible.length - 1].value;
    const past60 = wtiEligible[wtiEligible.length - 60].value;
    if (past60 > 0) {
      const change60 = ((cur - past60) / past60) * 100;
      out.WTI_60D_CHANGE = {
        name: 'wti_60d_change',
        value: parseFloat(change60.toFixed(2)),
        date,
        formula: 'WTI 60일 변화율(%) — 2~3개월 뒤 CPI 지연 반영 방향 프록시 (영상5 §7m7s)',
      };
      let pressure: 'high_down' | 'mild_down' | 'neutral' | 'mild_up' | 'high_up';
      if (change60 <= -15) pressure = 'high_down';
      else if (change60 <= -5) pressure = 'mild_down';
      else if (change60 < 5) pressure = 'neutral';
      else if (change60 < 15) pressure = 'mild_up';
      else pressure = 'high_up';
      cpiPressureScore = { high_down: -2, mild_down: -1, neutral: 0, mild_up: 1, high_up: 2 }[pressure];
      out.CPI_OIL_LAG_PRESSURE = {
        name: 'cpi_oil_lag_pressure',
        value: cpiPressureScore,
        date,
        formula: `${pressure} — 유가 ${change60.toFixed(1)}% 기반 향후 2~3개월 CPI 압력 (-2 강한 완화 ~ +2 강한 상승)`,
      };
    }
  }

  // 2) ISM_PROXY — INDPRO 최근 3개 관측치(월간) MoM 모멘텀 기반.
  //   라이브는 fetchFredHistory('INDPRO', 6) 을 받아 latest 3개를 쓴다 → 동등하게 date <= target
  //   이고 최신 3개를 가져와 동일 식 적용.
  let ismProxy: number | null = null;
  const indproEligible = indproHist.filter((p) => dateToTime(p.date) <= target);
  if (indproEligible.length >= 3) {
    const last3 = indproEligible.slice(-3);
    const current = last3[2].value;
    const prev = last3[1].value;
    const prev2 = last3[0].value;
    if (prev > 0) {
      const momMom = ((current - prev) / prev) * 100;
      const expanding = current > prev && prev > prev2;
      const contracting = current < prev && prev < prev2;
      let proxy = 50 + momMom * 10;
      if (expanding) proxy = Math.max(proxy, 51);
      if (contracting) proxy = Math.min(proxy, 49);
      proxy = Math.max(30, Math.min(70, proxy));
      ismProxy = parseFloat(proxy.toFixed(1));
      out.ISM_PROXY = {
        name: 'ism_proxy',
        value: ismProxy,
        date,
        formula: 'INDPRO 기반 ISM PMI 프록시 (50+=확장, 50-=수축)',
      };
    }
  }

  // 3) ICSA_REGIME_LABEL — NASDAQ_ABOVE_200DMA × ICSA 저/고 2x2.
  let icsaRegime: number | null = null;
  const nasdaqAbove = derivedSoFar.NASDAQ_ABOVE_200DMA?.value;
  if (icsaRaw !== null && nasdaqAbove !== undefined) {
    const highICSA = icsaRaw >= 300000;
    const lowICSA = icsaRaw < 250000;
    const above = nasdaqAbove === 1;
    let label = 'NEUTRAL';
    let score = 0;
    if (above && lowICSA) { label = 'HEALTHY_EXPANSION'; score = 2; }
    else if (above && highICSA) { label = 'MOMENTUM_WARNING'; score = -1; }
    else if (!above && lowICSA) { label = 'CORRECTION_OPPORTUNITY'; score = 1; }
    else if (!above && highICSA) { label = 'STRUCTURAL_RISK'; score = -2; }
    icsaRegime = score;
    out.ICSA_REGIME_LABEL = {
      name: 'icsa_regime_label',
      value: score,
      date,
      formula: `${label} (200DMA ${above ? '상회' : '하회'} × ICSA ${Math.round(icsaRaw / 1000)}K). 영상3 §174 매트릭스`,
    };
  }

  // 4) 합성 STAGFLATION_SCORE/WARNING — 라이브 게이트와 동일: cpiPressureScore/ismProxy 중
  //    하나라도 null 이면 발급 안 함.
  if (cpiPressureScore !== null && ismProxy !== null) {
    const inflationRising = cpiPressureScore >= 1;
    const growthSlowing = ismProxy < 50 || (icsaRegime !== null && icsaRegime <= -1);
    const stagflationScore = (inflationRising ? 1 : 0) + (growthSlowing ? 1 : 0);
    out.STAGFLATION_SCORE = {
      name: 'stagflation_score',
      value: stagflationScore,
      date,
      formula: `인플레↑(${inflationRising ? 'Y' : 'N'}) + 성장둔화(${growthSlowing ? 'Y' : 'N'}) 동시 충족 시 2 (영상4 §145)`,
    };
    out.STAGFLATION_WARNING = {
      name: 'stagflation_warning',
      value: stagflationScore === 2 ? 1 : 0,
      date,
      formula: stagflationScore === 2
        ? '스태그플레이션 2축 동시 충족 — CPI 상승 압력 + 성장 둔화 (영상4 §145)'
        : '스태그플레이션 조건 미충족',
    };
  }
  return out;
}

/**
 * ISO 주 번호 기반 bucket key (YYYY-WWW). 월요일 시작 주 기준.
 * 단순 구현 — 목요일 규칙 ISO-8601. daily close 만 있으므로 주봉 close 는 해당 주의 마지막 영업일 close.
 */
function isoWeekKey(dateIso: string): string {
  const d = new Date(`${dateIso}T00:00:00Z`);
  // ISO-8601: 목요일 기준 연도/주 번호.
  const target = new Date(d.getTime());
  const dayNum = (target.getUTCDay() + 6) % 7; // 월=0 .. 일=6
  target.setUTCDate(target.getUTCDate() - dayNum + 3); // 같은 주 목요일
  const firstThursday = new Date(Date.UTC(target.getUTCFullYear(), 0, 4));
  const firstDayNum = (firstThursday.getUTCDay() + 6) % 7;
  firstThursday.setUTCDate(firstThursday.getUTCDate() - firstDayNum + 3);
  const week = 1 + Math.round((target.getTime() - firstThursday.getTime()) / (7 * 86400 * 1000));
  const year = target.getUTCFullYear();
  return `${year}-${String(week).padStart(2, '0')}`;
}

/**
 * date 시점 USDKRW 주봉 선형 회귀 채널 재구성.
 *
 * 라이브 derived.ts 의 `fetchYahooOHLC('KRW=X', …, '1wk')` + `linearRegressionChannel(260)` 과
 * 수치적 동등성을 목표: daily close 를 ISO 주 기준으로 버킷팅 → 각 주의 마지막 영업일 close 를
 * 주봉 close 로 사용. linearRegressionChannel 은 `c.close` 만 참조하므로 open/high/low 를
 * close 로 채워 OHLCCandle 형태로 전달해도 회귀 결과는 동일.
 *
 * lookback=260 주 ≈ 5년. date 이하 데이터만 사용 (latestAtOrBefore 원칙).
 * 주봉 데이터가 부족(<60주) 하면 빈 맵.
 */
function reconstructUsdkrwWeeklyChannel(
  date: string,
  dailyHist: HistoryPoint[],
): Record<string, DerivedIndicator> {
  const out: Record<string, DerivedIndicator> = {};
  const target = dateToTime(date);
  const eligible = dailyHist.filter((p) => dateToTime(p.date) <= target);
  if (eligible.length < 60) return out;

  // 주별로 그룹핑 — 마지막 close 유지.
  const byWeek = new Map<string, HistoryPoint>();
  for (const p of eligible) {
    byWeek.set(isoWeekKey(p.date), p); // 동일 키 나중 값이 덮어씀 → 해당 주 마지막 영업일.
  }
  const weekKeys = Array.from(byWeek.keys());
  if (weekKeys.length < 60) return out;
  const weekly: OHLCCandle[] = weekKeys.map((k) => {
    const p = byWeek.get(k)!;
    return { date: p.date, open: p.value, high: p.value, low: p.value, close: p.value, volume: 0 };
  });

  const ch = linearRegressionChannel(weekly, 260);
  if (!ch || ch.position === null) return out;
  const lastDate = weekly[weekly.length - 1].date;
  out.USDKRW_WEEKLY_CHANNEL_POSITION = {
    name: 'usdkrw_weekly_channel_position',
    value: parseFloat(ch.position.toFixed(3)),
    date: lastDate,
    formula: '5년 주봉 회귀채널 내 위치(0~1). >0.9=원화 약세 극단(외인매도 리스크), <0.1=강세 극단',
  };
  out.USDKRW_WEEKLY_UPPER = {
    name: 'usdkrw_weekly_upper',
    value: ch.upperBand,
    date: lastDate,
    formula: '5년 주봉 회귀 상단 (mid + 1σ)',
  };
  out.USDKRW_WEEKLY_MID = {
    name: 'usdkrw_weekly_mid',
    value: ch.midLine,
    date: lastDate,
    formula: '5년 주봉 회귀선 (OLS)',
  };
  out.USDKRW_WEEKLY_LOWER = {
    name: 'usdkrw_weekly_lower',
    value: ch.lowerBand,
    date: lastDate,
    formula: '5년 주봉 회귀 하단 (mid - 1σ)',
  };
  return out;
}

/**
 * date 시점 FISCAL_STRESS_HARD 재구성.
 *
 * 라이브 derived.ts § "채권 자경단 / 재정 리스크":
 *   fiscalStress = (Δ20 ≥ 0.2 AND cur ≥ 4.5) OR (Δ20 ≥ 0.3)
 *   curveSteepening = T10Y2Y > 0.1   (null 은 false 로 방어적 처리)
 *   FISCAL_STRESS_HARD = fiscalStress AND curveSteepening → 1
 *
 * reconstructBondVigilante 가 이미 FISCAL_STRESS 를 채운 상태에서 HARD 만 얹는다.
 */
function reconstructFiscalStressHard(
  date: string,
  derivedSoFar: Record<string, DerivedIndicator>,
  t10y2yRaw: number | null,
): Record<string, DerivedIndicator> {
  const out: Record<string, DerivedIndicator> = {};
  const fiscalStress = derivedSoFar.FISCAL_STRESS?.value;
  if (fiscalStress === undefined) return out;
  const curveSteepening = t10y2yRaw !== null && t10y2yRaw > 0.1;
  if (fiscalStress === 1 && curveSteepening) {
    out.FISCAL_STRESS_HARD = {
      name: 'fiscal_stress_hard',
      value: 1,
      date,
      formula: '재정리스크 + 수익률곡선 스티프닝 동시 → 채권 자경단 강경 신호 (영상4 §07)',
    };
  }
  return out;
}

/**
 * Fix(3차 감사): cachedLiveDerived 과거 일자 오염 차단.
 *
 * 변경 전: 라이브 derived 를 shallow clone 후 NASDAQ/비율만 덮어써 STAGFLATION/MTF/PSYCH 등
 * 11개+ 단발 snapshot 지표가 1,258일 전체에 오늘 값으로 주입 → 과거 신호 왜곡.
 *
 * 변경 후:
 * - todayIso === date 일 때만 cachedLiveDerived 를 전체 사용 (오늘 스냅샷은 라이브와 동일).
 * - 과거 일자에는 cachedLiveDerived 를 사용하지 않고 빈 맵에서 시작.
 * - date-aware 재계산 가능한 지표 (NASDAQ/KOSPI 200DMA suite, 가격 비율, HYG/IEF/CREDIT,
 *   M2 YoY, USDKRW 채널 등) 만 채움. 단발 snapshot 지표 (STAGFLATION/BOND_VIGILANTE/
 *   OVERHEATED/MTF/PSYCH_SUBSCORE/RRP_DIRECTION/GLOBAL_M2_PROXY/SECTOR_xxx/
 *   SMART_MONEY_xxx/KRX_xxx/FISCAL_STRESS 등) 는 명시적으로 null 유지 → signals.ts 가 null 가드로 깔끔히 스킵.
 */
export async function recomputeFullDerivedForDate(
  date: string,
  raw: Record<string, MarketDataPoint>,
  nasdaqHistory: HistoryPoint[],
  cachedLiveDerived: Record<string, DerivedIndicator>,
  ctx?: {
    todayIso: string;
    kospiHistory: HistoryPoint[];
    hygHistory: HistoryPoint[];
    iefHistory: HistoryPoint[];
    m2Wm2nsHistory: HistoryPoint[];
    dgs30History?: HistoryPoint[];
    dxyHistory?: HistoryPoint[];
    // 4차 감사 확장: STAGFLATION · FISCAL_STRESS_HARD · USDKRW 주봉 채널 재구성용.
    wtiHistory?: HistoryPoint[];
    indproHistory?: HistoryPoint[];
    usdkrwHistory?: HistoryPoint[];
  },
): Promise<Record<string, DerivedIndicator>> {
  // 오늘 날짜 anchor 면 라이브 derived 를 그대로 사용 (NEW: 단일 스냅샷 신선도 보존).
  // 그 외 모든 과거 일자는 빈 맵에서 시작 — cachedLiveDerived 를 일부러 무시한다.
  const isToday = ctx?.todayIso === date;
  const derived: Record<string, DerivedIndicator> = isToday ? { ...cachedLiveDerived } : {};

  // 가격 비율 — raw 가 있으면 date 시점으로 재계산.
  const dgs10 = raw.DGS10?.value;
  const t10yie = raw.T10YIE?.value;
  if (dgs10 !== undefined && t10yie !== undefined) {
    derived.REAL_YIELD = { name: 'real_yield', value: dgs10 - t10yie, date, formula: 'DGS10 - T10YIE' };
  }
  const gold = raw.GOLD?.value;
  const silver = raw.SILVER?.value;
  const copper = raw.COPPER?.value;
  if (gold !== undefined && silver) {
    derived.GOLD_SILVER_RATIO = { name: 'gold_silver_ratio', value: gold / silver, date, formula: 'GOLD / SILVER' };
  }
  if (gold && copper !== undefined) {
    derived.COPPER_GOLD_RATIO = { name: 'copper_gold_ratio', value: copper / gold, date, formula: 'COPPER / GOLD' };
  }

  // NASDAQ 날짜 의존 필드 + 이격 streak.
  for (const [k, v] of Object.entries(reconstruct200DmaSuite(date, nasdaqHistory, 'NASDAQ'))) {
    derived[k] = v;
  }

  if (ctx) {
    // KOSPI 200DMA suite (오늘이 아닌 일자는 위 빈 맵에서 시작했으므로 여기서 채움).
    if (ctx.kospiHistory.length >= 200) {
      for (const [k, v] of Object.entries(reconstruct200DmaSuite(date, ctx.kospiHistory, 'KOSPI'))) {
        derived[k] = v;
      }
    }
    // 크레딧 스트레스 (HY OAS + HYG/IEF z-score).
    for (const [k, v] of Object.entries(
      reconstructCreditStress(date, ctx.hygHistory, ctx.iefHistory, raw.BAMLH0A0HYM2?.value),
    )) {
      derived[k] = v;
    }
    // M2 YoY 방향 전환.
    for (const [k, v] of Object.entries(reconstructM2Yoy(date, ctx.m2Wm2nsHistory))) {
      derived[k] = v;
    }
    // 채권 자경단 / 재정 스트레스 (DGS30 + DXY history + HY OAS raw).
    if (ctx.dgs30History && ctx.dxyHistory) {
      for (const [k, v] of Object.entries(
        reconstructBondVigilante(date, ctx.dgs30History, ctx.dxyHistory, raw.BAMLH0A0HYM2?.value),
      )) {
        derived[k] = v;
      }
    }
  }

  // 센티먼트 패스스루 + PSYCH_SUBSCORE 재구성 (raw 에 cnn/sentiment 항목이 있으면 발급).
  // raw 가 비면 자연스럽게 키 미발급 → signals.ts 의 dv() null 가드로 깔끔히 스킵.
  for (const [k, v] of Object.entries(reconstructPsychSubscore(date, raw))) {
    derived[k] = v;
  }
  // OVERHEATED — NASDAQ_DISPARITY 가 위에서 채워진 상태이므로 마지막에 평가.
  for (const [k, v] of Object.entries(reconstructOverheated(date, derived, raw))) {
    derived[k] = v;
  }

  // 4차 감사 확장: STAGFLATION 합성 — NASDAQ_ABOVE_200DMA(suite) 채워진 뒤 호출.
  if (ctx?.wtiHistory && ctx?.indproHistory) {
    const icsaRaw = raw.ICSA?.value ?? null;
    for (const [k, v] of Object.entries(
      reconstructStagflation(date, ctx.wtiHistory, ctx.indproHistory, icsaRaw, derived),
    )) {
      derived[k] = v;
    }
  }
  // 4차 감사 확장: FISCAL_STRESS_HARD — FISCAL_STRESS(bondVigilante) 채워진 뒤 호출.
  {
    const t10y2yRaw = raw.T10Y2Y?.value ?? null;
    for (const [k, v] of Object.entries(reconstructFiscalStressHard(date, derived, t10y2yRaw))) {
      derived[k] = v;
    }
  }
  // 4차 감사 확장: USDKRW 주봉 선형 회귀 채널 — daily close → ISO week close 버킷팅.
  if (ctx?.usdkrwHistory) {
    for (const [k, v] of Object.entries(reconstructUsdkrwWeeklyChannel(date, ctx.usdkrwHistory))) {
      derived[k] = v;
    }
  }

  return derived;
}

function signalValue(signal: string) {
  if (signal === 'STRONG_BUY') return 100;
  if (signal === 'BUY') return 75;
  if (signal === 'HOLD') return 50;
  if (signal === 'REDUCE') return 25;
  return 0;
}

export async function refreshComputedHistories(options?: { force?: boolean }) {
  return withSpan('macrosquare.history.refreshComputed', async (span) => {
  // 3차 감사 Fix #2: 증분 skip. signal-REGIME 마지막 date 가 오늘(UTC) 이상이면 full
  // 재계산 skip (환경변수 REFRESH_FULL=true 또는 options.force=true 로 강제 full).
  const force = options?.force === true || process.env.REFRESH_FULL === 'true';
  span.setAttribute('history.force', force);
  if (!force) {
    try {
      const regimeHist = await readHistory('signal', 'REGIME');
      const todayIso = new Date().toISOString().split('T')[0];
      const lastDate = regimeHist.length > 0 ? regimeHist[regimeHist.length - 1].date : '';
      if (lastDate >= todayIso) {
        console.info(`[history] refreshComputedHistories skipped (signal-REGIME last=${lastDate} >= today=${todayIso}). REFRESH_FULL=true 로 강제 가능.`);
        return;
      }
    } catch {
      // regime history 읽기 실패하면 full 재계산 진행
    }
  }
  const histories: Record<string, HistoryPoint[]> = {};
  const keys = ['NASDAQ', 'GOLD', 'SILVER', 'COPPER', 'SP500', 'WTI', 'USDKRW', 'DXY', 'DGS10', 'T10YIE', 'T10Y2Y', 'VIXCLS', 'BAMLH0A0HYM2', 'STLFSI4', 'ICSA', 'UNRATE'];
  const yahooKeys = new Set(['NASDAQ', 'GOLD', 'SILVER', 'COPPER', 'SP500', 'WTI', 'USDKRW', 'DXY']);
  for (const key of keys) {
    if (yahooKeys.has(key)) histories[`yahoo:${key}`] = await readHistory('yahoo', key);
    else histories[`fred:${key}`] = await readHistory('fred', key);
  }

  // 센티먼트 raw history (cnn / sentiment 소스). buildRawForDate 가 latestAtOrBefore 로 스냅.
  histories['cnn:FEAR_GREED'] = await readHistory('cnn', 'FEAR_GREED');
  histories['sentiment:PC_RATIO_10D'] = await readHistory('sentiment', 'PC_RATIO_10D');
  histories['sentiment:AAII_BULL_BEAR_SPREAD'] = await readHistory('sentiment', 'AAII_BULL_BEAR_SPREAD');
  histories['sentiment:NAAIM_EXPOSURE'] = await readHistory('sentiment', 'NAAIM_EXPOSURE');

  // Fix(3차 감사): date-aware 재계산 확장에 필요한 보조 히스토리. 없으면 빈 배열 → 해당 지표 null.
  const kospiHistory = await readHistory('yahoo', 'KOSPI');
  const hygHistory = await readHistory('yahoo', 'HYG');
  const iefHistory = await readHistory('yahoo', 'IEF');
  const m2Wm2nsHistory = await readHistory('fred', 'WM2NS');
  const dgs30History = await readHistory('fred', 'DGS30');
  const dxyHistory = await readHistory('yahoo', 'DXY');
  // 4차 감사 확장: STAGFLATION / FISCAL_STRESS_HARD / USDKRW 주봉 채널 재구성 입력.
  const wtiHistory = await readHistory('yahoo', 'WTI');
  const indproHistory = await readHistory('fred', 'INDPRO');
  const usdkrwHistory = await readHistory('yahoo', 'USDKRW');

  const base = histories['yahoo:NASDAQ'] || [];
  const computed: Record<string, HistoryPoint[]> = {
    REGIME: [],
    PORTFOLIO: [],
    NASDAQ: [],
    GOLD: [],
    SILVER: [],
    COPPER: [],
  };

  // Fix #1+#2(2차 감사): live computeDerived 를 anchor 루프 전에 **한 번만** 호출해 재사용.
  //   내부에서 Yahoo/FRED live fetch 가 많아 per-date 호출은 비용 폭발. 날짜 의존 필드는
  //   recomputeFullDerivedForDate 에서 raw/nasdaqHistory 로 덮어쓴다.
  let cachedLiveDerived: Record<string, DerivedIndicator> = {};
  try {
    const latestRaw = buildRawForDate(
      base.length > 0 ? base[base.length - 1].date : new Date().toISOString().split('T')[0],
      histories,
    );
    cachedLiveDerived = await computeDerived(latestRaw);
  } catch (error) {
    console.warn('[history] computeDerived cache failed, falling back to date-specific only:', error);
    cachedLiveDerived = {};
  }

  // 커버리지 통계 — 최근 1년 평균 재구성 키 수 점검 용도.
  const todayIso = new Date().toISOString().split('T')[0];
  const oneYearAgoMs = Date.now() - 365 * 86400 * 1000;
  let recentCoveragePoints = 0;
  let recentCoverageSum = 0;

  for (const anchor of base) {
    const raw = buildRawForDate(anchor.date, histories);
    const derived = await recomputeFullDerivedForDate(anchor.date, raw, base, cachedLiveDerived, {
      todayIso,
      kospiHistory,
      hygHistory,
      iefHistory,
      m2Wm2nsHistory,
      dgs30History,
      dxyHistory,
      wtiHistory,
      indproHistory,
      usdkrwHistory,
    });
    if (dateToTime(anchor.date) >= oneYearAgoMs) {
      const nonNullKeys = Object.values(derived).filter((d) => d.value !== null).length;
      recentCoverageSum += nonNullKeys;
      recentCoveragePoints += 1;
    }
    // smartMoneyScore: 히스토리에는 smart-money 시계열이 없으므로 0 을 명시적으로 전달.
    //   라이브 경로와 동일 시그니처 유지가 이 Fix 의 핵심.
    const regime = classifyRegime({
      raw,
      derived,
      manualInputs: DEFAULT_PROFILE.manualInputs,
      smartMoneyScore: 0,
    });
    const signals = computeSignals(raw, derived, regime, DEFAULT_PROFILE);
    const allocation = computeAllocation(regime.regime, regime.score, signals, derived, raw);
    const byAsset = Object.fromEntries(signals.map((s) => [s.asset, s]));

    computed.NASDAQ.push({ date: anchor.date, value: signalValue(byAsset['NASDAQ']?.signal || 'HOLD') });
    computed.GOLD.push({ date: anchor.date, value: signalValue(byAsset['GOLD']?.signal || 'HOLD') });
    computed.SILVER.push({ date: anchor.date, value: signalValue(byAsset['SILVER']?.signal || 'HOLD') });
    computed.COPPER.push({ date: anchor.date, value: signalValue(byAsset['COPPER']?.signal || 'HOLD') });
    computed.REGIME.push({ date: anchor.date, value: regime.score });
    computed.PORTFOLIO.push({ date: anchor.date, value: 100 - allocation.allocations.cash });
  }

  await writeHistory('signal', 'REGIME', computed.REGIME);
  await writeHistory('signal', 'PORTFOLIO', computed.PORTFOLIO);
  await writeHistory('signal', 'NASDAQ', computed.NASDAQ);
  await writeHistory('signal', 'GOLD', computed.GOLD);
  await writeHistory('signal', 'SILVER', computed.SILVER);
  await writeHistory('signal', 'COPPER', computed.COPPER);

  span.setAttribute('history.anchor_count', base.length);
  span.setAttribute('history.computed_assets', Object.keys(computed).length);
  if (recentCoveragePoints > 0) {
    const avg = recentCoverageSum / recentCoveragePoints;
    span.setAttribute('history.recent_coverage_avg', Number(avg.toFixed(1)));
    span.setAttribute('history.recent_coverage_points', recentCoveragePoints);
    console.log(
      `[history] 재구성 커버리지: 최근 1년 평균 ${avg.toFixed(1)} non-null derived keys/일자 (${recentCoveragePoints}일 기준). ` +
      `단발 snapshot 지표 (STAGFLATION/MTF/PSYCH/RRP_DIRECTION/SECTOR_*/KRX_* 등) 는 정책상 null 유지.`
    );
  }
  });
}

export async function readHistory(source: string, key: string): Promise<HistoryPoint[]> {
  try {
    const content = await fs.readFile(filePath(source, key), 'utf-8');
    return JSON.parse(content);
  } catch {
    return [];
  }
}

export async function backfillFred(apiKey: string, onlyKeys?: string[]) {
  const start = yearsAgo(GUARANTEE.FRED_YEARS);
  const entries = Object.entries(FRED_SERIES).filter(
    ([key]) => !onlyKeys || onlyKeys.includes(key)
  );
  if (entries.length === 0) return;
  const settled = await Promise.allSettled(
    entries.map(([, seriesId]) => fetchFredHistoryFrom(seriesId, apiKey, start))
  );

  for (let i = 0; i < entries.length; i += 1) {
    const [key] = entries[i];
    const result = settled[i];
    if (result.status === 'fulfilled') {
      await writeHistory('fred', key, result.value.map((p) => ({ date: p.date, value: p.value })));
    }
  }
}

export async function backfillYahoo(onlyKeys?: string[]) {
  const entries = Object.entries(YAHOO_SYMBOLS).filter(
    ([key]) => !onlyKeys || onlyKeys.includes(key)
  );
  if (entries.length === 0) return;
  const settled = await Promise.allSettled(
    entries.map(([, symbol]) => fetchYahooHistoryYears(symbol, GUARANTEE.YAHOO_YEARS))
  );

  for (let i = 0; i < entries.length; i += 1) {
    const [key] = entries[i];
    const result = settled[i];
    if (result.status === 'fulfilled') {
      await writeHistory('yahoo', key, result.value.map((p) => ({ date: p.date, value: p.close })));
    }
  }
}

/** 파일이 아예 없거나, 보장 범위 대비 포인트 수가 현저히 부족하면 재백필 대상으로 간주. */
async function identifyMissingOrShallow(
  source: 'fred' | 'yahoo',
  seriesKeys: string[],
  files: string[],
  minPoints: number,
): Promise<string[]> {
  const missing: string[] = [];
  for (const key of seriesKeys) {
    const filename = `${source}-${key.toLowerCase()}.json`;
    if (!files.includes(filename)) {
      missing.push(key);
      continue;
    }
    try {
      const raw = await fs.readFile(path.join(HISTORY_DIR, filename), 'utf-8');
      const points = JSON.parse(raw) as HistoryPoint[];
      if (points.length < minPoints) missing.push(key);
    } catch {
      missing.push(key);
    }
  }
  return missing;
}

export async function ensureBackfill(apiKey: string) {
  await ensureDir();
  const files = await fs.readdir(HISTORY_DIR).catch(() => [] as string[]);

  // 개별 시리즈 단위로 누락/부족 여부 검사.
  // FRED: 월간/주간 시리즈 섞여있어 하한을 12포인트(=최소 YoY 계산 가능)로 둠.
  // Yahoo: 일간 시리즈. 영업일 기준 120일 이상이면 정상 수집으로 간주.
  const fredMissing = await identifyMissingOrShallow(
    'fred',
    Object.keys(FRED_SERIES),
    files,
    12,
  );
  const yahooMissing = await identifyMissingOrShallow(
    'yahoo',
    Object.keys(YAHOO_SYMBOLS),
    files,
    120,
  );
  const signalMissing = !files.some((f) => f.startsWith('signal-'));

  if (fredMissing.length > 0) {
    console.log(`[backfill] FRED re-filling: ${fredMissing.join(', ')}`);
    await backfillFred(apiKey, fredMissing);
  }
  if (yahooMissing.length > 0) {
    console.log(`[backfill] Yahoo re-filling: ${yahooMissing.join(', ')}`);
    await backfillYahoo(yahooMissing);
  }
  if (fredMissing.length || yahooMissing.length || signalMissing) {
    await refreshComputedHistories();
  }
}

export async function coverage() {
  await ensureDir();
  const files = await fs.readdir(HISTORY_DIR).catch(() => [] as string[]);

  const result: Record<string, { count: number; oldest: string; newest: string; guaranteedYears: number }> = {};

  for (const file of files) {
    const content = await fs.readFile(path.join(HISTORY_DIR, file), 'utf-8');
    const points: HistoryPoint[] = JSON.parse(content);
    const name = file.replace('.json', '').toUpperCase();
    result[name] = {
      count: points.length,
      oldest: points[0]?.date || '',
      newest: points[points.length - 1]?.date || '',
        guaranteedYears: file.startsWith('fred-') ? GUARANTEE.FRED_YEARS : file.startsWith('yahoo-') ? GUARANTEE.YAHOO_YEARS : GUARANTEE.YAHOO_YEARS,
    };
  }

  return result;
}

export async function appendDailyData(apiKey: string) {
  const today = new Date().toISOString().split('T')[0];

  const fredEntries = Object.entries(FRED_SERIES);
  for (const [key, seriesId] of fredEntries) {
    try {
      const existing = await readHistory('fred', key);
      const lastDate = existing.length > 0 ? existing[existing.length - 1].date : '';
      if (lastDate >= today) continue;

      const recent = await fetchFredHistoryFrom(seriesId, apiKey, lastDate || yearsAgo(1));
      const newPoints = recent
        .filter((p) => p.date > lastDate)
        .map((p) => ({ date: p.date, value: p.value }));

      if (newPoints.length > 0) {
        await writeHistory('fred', key, [...existing, ...newPoints]);
      }
    } catch {
      void 0;
    }
  }

  const yahooEntries = Object.entries(YAHOO_SYMBOLS);
  for (const [key, symbol] of yahooEntries) {
    try {
      const existing = await readHistory('yahoo', key);
      const lastDate = existing.length > 0 ? existing[existing.length - 1].date : '';
      if (lastDate >= today) continue;

      const recent = await fetchYahooHistoryYears(symbol, 0.1);
      const newPoints = recent
        .filter((p) => p.date > lastDate)
        .map((p) => ({ date: p.date, value: p.close }));

      if (newPoints.length > 0) {
        await writeHistory('yahoo', key, [...existing, ...newPoints]);
      }
    } catch {
      void 0;
    }
  }

  // 센티먼트 daily append — KST 07:00 cron 에서 함께 호출.
  await appendSentimentDaily();
}

/**
 * CNN F&G + CBOE P/C 10D + AAII spread + NAAIM exposure 의 daily append.
 *
 * 정책:
 *   - 수집 실패/null → append skip (null 저장 금지). 라이브 스냅샷에 일시 결측이 있더라도
 *     history 는 마지막으로 성공한 영업일 값을 유지.
 *   - 동일 (source,key,date) 중복 저장 금지 — lastDate >= asOf 이면 skip (멱등).
 *   - 저장 date 필드는 각 소스의 asOf (데이터 소스 자체 영업일 = 미국/주간 발표 기준).
 *     라이브 collector 의 asOf 가 비면 KST 기준 today 로 폴백.
 *   - 5분 스냅샷에서는 호출하지 않는다 — 본 함수는 KST 07:00 cron 진입점 전용.
 */
export async function appendSentimentDaily(): Promise<void> {
  const today = new Date().toISOString().split('T')[0];

  // CNN F&G
  try {
    const fng = await fetchFearAndGreed();
    if (fng && fng.value !== null && Number.isFinite(fng.value as number)) {
      const asOf = fng.date || today;
      const existing = await readHistory('cnn', 'FEAR_GREED');
      const lastDate = existing.length > 0 ? existing[existing.length - 1].date : '';
      if (lastDate < asOf) {
        await writeHistory('cnn', 'FEAR_GREED', [...existing, { date: asOf, value: fng.value as number }]);
        console.info(`[history] sentiment append: CNN_FEAR_GREED=${fng.value} date=${asOf}`);
      }
    } else {
      // 3차 감사 Fix #3: 수집 실패 시 silent skip 이 아닌 명시적 경고.
      console.warn(`[history] sentiment skip: CNN_FEAR_GREED — value=${fng?.value} (수집 실패 또는 결측)`);
    }
  } catch (error) {
    console.warn('[history] CNN F&G append skipped:', (error as Error)?.message || error);
  }

  // sentiment 묶음 (PC_RATIO_10D, AAII_BULL_BEAR_SPREAD, NAAIM_EXPOSURE)
  try {
    const sent = await fetchAllSentiment();
    const targets = ['PC_RATIO_10D', 'AAII_BULL_BEAR_SPREAD', 'NAAIM_EXPOSURE'];
    for (const key of targets) {
      const point = sent[key];
      if (!point || point.value === null || !Number.isFinite(point.value)) {
        // 3차 감사 Fix #3: 소스별 실패 사유를 명시 로깅. silent skip 금지.
        const src = (point as { source?: string } | null)?.source ?? 'unknown';
        const err = (point as { error?: string } | null)?.error ?? 'value=null';
        console.warn(`[history] sentiment skip: key=${key} source=${src} reason="${err}"`);
        continue;
      }
      const asOf = point.date || today;
      const existing = await readHistory('sentiment', key);
      const lastDate = existing.length > 0 ? existing[existing.length - 1].date : '';
      if (lastDate < asOf) {
        await writeHistory('sentiment', key, [
          ...existing,
          { date: asOf, value: point.value },
        ]);
        console.info(`[history] sentiment append: ${key}=${point.value} date=${asOf}`);
      }
    }
  } catch (error) {
    console.warn('[history] sentiment append skipped:', (error as Error)?.message || error);
  }
}

export const HISTORY_GUARANTEE = GUARANTEE;
