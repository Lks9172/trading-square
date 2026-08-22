import { NextRequest, NextResponse } from 'next/server';

const INTERNAL_API_URL = process.env.SSR_API_URL || 'http://macrosquare-server:5846';

export async function GET(request: NextRequest) {
  try {
    const tickers = request.nextUrl.searchParams.get('tickers') || '';
    const response = await fetch(
      `${INTERNAL_API_URL}/api/company-summaries?tickers=${encodeURIComponent(tickers)}`,
      { next: { revalidate: 900 } },
    );
    const data = await response.json();
    return NextResponse.json(data, { status: response.status });
  } catch (error: unknown) {
    return NextResponse.json(
      { error: error instanceof Error ? error.message : 'Proxy request failed' },
      { status: 500 },
    );
  }
}
