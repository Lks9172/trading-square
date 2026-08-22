export const WEB_VITAL_NAMES = ["CLS", "FCP", "FID", "INP", "LCP", "TTFB"] as const;

export type WebVitalName = (typeof WEB_VITAL_NAMES)[number];
export type WebVitalRating = "good" | "needs-improvement" | "poor";

export type WebVitalPayload = {
  name: WebVitalName;
  value: number;
  delta: number;
  rating: WebVitalRating;
  id: string;
  navigationType: string;
  path: string;
};

const RATINGS = new Set<WebVitalRating>(["good", "needs-improvement", "poor"]);
const NAMES = new Set<string>(WEB_VITAL_NAMES);
const MAX_METRIC_VALUE = 10_000_000;

export function parseWebVitalPayload(value: unknown): WebVitalPayload | null {
  if (!isRecord(value)) return null;
  if (typeof value.name !== "string" || !NAMES.has(value.name)) return null;
  if (!finiteMetric(value.value) || !finiteMetric(value.delta)) return null;
  if (typeof value.rating !== "string" || !RATINGS.has(value.rating as WebVitalRating)) return null;
  if (!boundedText(value.id, 1, 128)) return null;
  if (!boundedText(value.navigationType, 1, 64)) return null;
  if (!boundedPath(value.path)) return null;

  return {
    name: value.name as WebVitalName,
    value: value.value,
    delta: value.delta,
    rating: value.rating as WebVitalRating,
    id: value.id,
    navigationType: value.navigationType,
    path: value.path,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function finiteMetric(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value)
    && Math.abs(value) <= MAX_METRIC_VALUE;
}

function boundedText(value: unknown, minimum: number, maximum: number): value is string {
  return typeof value === "string" && value.length >= minimum && value.length <= maximum
    && !/[\u0000-\u001f\u007f]/.test(value);
}

function boundedPath(value: unknown): value is string {
  return boundedText(value, 1, 256) && value.startsWith("/") && !value.includes("?");
}
