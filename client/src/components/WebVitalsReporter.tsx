"use client";

import { useReportWebVitals } from "next/web-vitals";

type WebVitalMetric = Parameters<Parameters<typeof useReportWebVitals>[0]>[0];

function reportWebVital(metric: WebVitalMetric) {
  const payload = JSON.stringify({
    name: metric.name,
    value: metric.value,
    delta: metric.delta,
    rating: metric.rating,
    id: metric.id,
    navigationType: metric.navigationType ?? "unknown",
    // Query strings can contain user-provided values and are deliberately excluded.
    path: window.location.pathname,
  });

  if (navigator.sendBeacon) {
    const accepted = navigator.sendBeacon(
      "/api/rum",
      new Blob([payload], { type: "application/json" }),
    );
    if (accepted) return;
  }

  void fetch("/api/rum", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: payload,
    keepalive: true,
    cache: "no-store",
  }).catch(() => {
    // RUM is diagnostic-only and must never interrupt the investment UI.
  });
}

export function WebVitalsReporter() {
  useReportWebVitals(reportWebVital);
  return null;
}
