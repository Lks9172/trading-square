import { NextResponse } from "next/server";
import { errorMessage, fetchServerJson } from "@/lib/server-api";

export async function GET() {
  try {
    const value = await fetchServerJson<unknown>("/api/institutional-flows", {
      cache: "no-store",
      attempts: 2,
      timeoutMs: 4_000,
    });
    return NextResponse.json(value ?? {
      status: "collecting", managers: [], consensus: [], divergences: [],
      mappedPositionCount: 0, unmappedPositionCount: 0,
    });
  } catch (error) {
    return NextResponse.json({ error: errorMessage(error) }, { status: 503 });
  }
}
