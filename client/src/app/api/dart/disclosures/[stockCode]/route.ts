import { NextResponse } from "next/server";
import { errorMessage, fetchServerJson } from "@/lib/server-api";

export async function GET(
  _request: Request,
  context: { params: Promise<{ stockCode: string }> },
) {
  try {
    const { stockCode } = await context.params;
    const value = await fetchServerJson<unknown>(
      `/api/dart/disclosures/${encodeURIComponent(stockCode)}`,
      { cache: "no-store", attempts: 2, timeoutMs: 4_000 },
    );
    return NextResponse.json(value ?? { status: "collecting", disclosures: [], financials: [] });
  } catch (error) {
    return NextResponse.json({ error: errorMessage(error) }, { status: 503 });
  }
}
