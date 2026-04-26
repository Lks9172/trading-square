import { NextRequest, NextResponse } from 'next/server';

const INTERNAL_API_URL = process.env.SSR_API_URL || 'http://macrosquare-server:5846';

export async function GET(request: NextRequest) {
  try {
    const years = request.nextUrl.searchParams.get('years') || '3';
    const response = await fetch(`${INTERNAL_API_URL}/api/backtest/portfolio?years=${years}`, { cache: 'no-store' });
    const data = await response.json();
    return NextResponse.json(data, { status: response.status });
  } catch (error: any) {
    return NextResponse.json({ error: error?.message || 'Proxy failed' }, { status: 500 });
  }
}
