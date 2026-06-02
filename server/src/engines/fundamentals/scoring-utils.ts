export function clampScore(value: number): number {
  return Math.max(0, Math.min(100, Math.round(value)));
}

export function averageScore(values: number[]): number {
  if (values.length === 0) return 0;
  return clampScore(values.reduce((sum, value) => sum + value, 0) / values.length);
}

