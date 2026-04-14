import { readHistory } from '../state/history-store';

type HistoryPoint = { date: string; value: number };

/** 두 시계열을 날짜 기준으로 정렬해 공통 날짜의 로그수익률 쌍을 반환. */
function alignedLogReturns(
  a: HistoryPoint[],
  b: HistoryPoint[],
  lookbackDays: number,
): { ra: number[]; rb: number[] } {
  const mapA = new Map(a.map((p) => [p.date, p.value]));
  const dates = b
    .map((p) => p.date)
    .filter((d) => mapA.has(d))
    .sort();
  const cutoff = Date.now() - lookbackDays * 86400_000;
  const kept = dates.filter((d) => new Date(d).getTime() >= cutoff);

  const ra: number[] = [];
  const rb: number[] = [];
  const bMap = new Map(b.map((p) => [p.date, p.value]));
  for (let i = 1; i < kept.length; i += 1) {
    const pa1 = mapA.get(kept[i - 1]);
    const pa2 = mapA.get(kept[i]);
    const pb1 = bMap.get(kept[i - 1]);
    const pb2 = bMap.get(kept[i]);
    if (pa1 && pa2 && pb1 && pb2 && pa1 > 0 && pb1 > 0) {
      ra.push(Math.log(pa2 / pa1));
      rb.push(Math.log(pb2 / pb1));
    }
  }
  return { ra, rb };
}

/** Pearson 상관계수. 샘플이 부족하거나 분산이 0 이면 null. */
function pearson(x: number[], y: number[]): number | null {
  if (x.length !== y.length || x.length < 10) return null;
  const n = x.length;
  const mx = x.reduce((s, v) => s + v, 0) / n;
  const my = y.reduce((s, v) => s + v, 0) / n;
  let cov = 0;
  let vx = 0;
  let vy = 0;
  for (let i = 0; i < n; i += 1) {
    const dx = x[i] - mx;
    const dy = y[i] - my;
    cov += dx * dy;
    vx += dx * dx;
    vy += dy * dy;
  }
  if (vx === 0 || vy === 0) return null;
  return cov / Math.sqrt(vx * vy);
}

/** 상관관계 매트릭스 계산 대상 자산 목록. */
const DEFAULT_ASSETS: Array<{ key: string; source: 'yahoo' | 'fred' }> = [
  { key: 'NASDAQ', source: 'yahoo' },
  { key: 'SP500', source: 'yahoo' },
  { key: 'GOLD', source: 'yahoo' },
  { key: 'SILVER', source: 'yahoo' },
  { key: 'COPPER', source: 'yahoo' },
  { key: 'WTI', source: 'yahoo' },
  { key: 'DXY', source: 'yahoo' },
  { key: 'USDKRW', source: 'yahoo' },
  { key: 'KOSPI', source: 'yahoo' },
  { key: 'DGS10', source: 'fred' },
  { key: 'VIXCLS', source: 'fred' },
];

export interface CorrelationMatrixResult {
  lookbackDays: number;
  assets: string[];
  matrix: (number | null)[][];
  missing: string[];
  asOf: string;
}

/** 주어진 lookbackDays(일) 기간의 로그수익률 기반 상관관계 매트릭스. */
export async function computeCorrelationMatrix(
  lookbackDays = 60,
  assetKeys?: string[],
): Promise<CorrelationMatrixResult> {
  const targets = assetKeys && assetKeys.length > 0
    ? DEFAULT_ASSETS.filter((a) => assetKeys.includes(a.key))
    : DEFAULT_ASSETS;

  // 히스토리 로드
  const histories: Record<string, HistoryPoint[]> = {};
  const missing: string[] = [];
  for (const t of targets) {
    const pts = await readHistory(t.source, t.key);
    if (pts.length < 20) missing.push(t.key);
    else histories[t.key] = pts;
  }

  const labels = targets.map((t) => t.key).filter((k) => histories[k]);
  const n = labels.length;
  const matrix: (number | null)[][] = Array.from({ length: n }, () =>
    Array.from({ length: n }, () => null),
  );

  for (let i = 0; i < n; i += 1) {
    for (let j = i; j < n; j += 1) {
      if (i === j) {
        matrix[i][j] = 1;
      } else {
        const { ra, rb } = alignedLogReturns(histories[labels[i]], histories[labels[j]], lookbackDays);
        const r = pearson(ra, rb);
        matrix[i][j] = r !== null ? parseFloat(r.toFixed(3)) : null;
        matrix[j][i] = matrix[i][j];
      }
    }
  }

  return {
    lookbackDays,
    assets: labels,
    matrix,
    missing,
    asOf: new Date().toISOString().split('T')[0],
  };
}
