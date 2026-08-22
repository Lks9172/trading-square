import { NextRequest, NextResponse } from 'next/server';

const INTERNAL_API_URL =
  process.env.SSR_API_URL ||
  process.env.INTERNAL_API_URL ||
  (process.env.NODE_ENV === 'development'
    ? 'http://localhost:5846'
    : 'http://macrosquare-server:5846');

export async function GET(
  _request: NextRequest,
  context: { params: Promise<{ ticker: string }> }
) {
  try {
    const { ticker } = await context.params;
    const response = await fetch(`${INTERNAL_API_URL}/api/company/${encodeURIComponent(ticker)}`, {
      cache: 'no-store',
    });
    const data = await response.json();
    return NextResponse.json(data, { status: response.status });
  } catch (error: unknown) {
    return NextResponse.json(
      { error: error instanceof Error ? error.message : 'Proxy request failed' },
      { status: 500 }
    );
  }
}
