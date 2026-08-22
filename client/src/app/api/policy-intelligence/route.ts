import { NextResponse } from "next/server";
import { errorMessage, fetchServerJson } from "@/lib/server-api";

export async function GET() {
  try {
    const value = await fetchServerJson<unknown>("/api/policy-intelligence", {
      cache: "no-store",
      attempts: 2,
      timeoutMs: 4_000,
    });
    return NextResponse.json(value ?? {
      status: "collecting",
      documents: [],
      calibration: {
        sampleCount: 0, calibratedConfidence: 0, walkForwardAccuracyPct: 0,
        brierScore: 0, enoughSamples: false, windowStart: null, windowEnd: null, methodology: "",
      },
    });
  } catch (error) {
    return NextResponse.json({ error: errorMessage(error) }, { status: 503 });
  }
}
