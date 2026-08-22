import { NextRequest, NextResponse } from "next/server";
import { errorMessage, fetchServerJson } from "@/lib/server-api";

export async function GET(
  request: NextRequest,
  context: { params: Promise<{ ticker: string }> },
) {
  try {
    const { ticker } = await context.params;
    const search = request.nextUrl.searchParams;
    const suffix = search.size > 0 ? `?${search.toString()}` : "";
    const value = await fetchServerJson<unknown>(
      `/api/research/peers/${encodeURIComponent(ticker)}${suffix}`,
      { cache: "no-store", attempts: 2, timeoutMs: 4_000 },
    );
    return NextResponse.json(value ?? { status: "collecting", peers: [] });
  } catch (error) {
    return NextResponse.json({ error: errorMessage(error) }, { status: 503 });
  }
}
