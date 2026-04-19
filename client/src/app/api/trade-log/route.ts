import { NextRequest, NextResponse } from 'next/server';

const INTERNAL_API_URL = process.env.SSR_API_URL || 'http://macrosquare-server:5846';

export async function GET(req: NextRequest) {
  try {
    const limit = req.nextUrl.searchParams.get('limit') || '200';
    const response = await fetch(`${INTERNAL_API_URL}/api/trade-log?limit=${limit}`, { cache: 'no-store' });
    const data = await response.json();
    return NextResponse.json(data, { status: response.status });
  } catch (error: any) {
    return NextResponse.json({ error: error?.message || 'Proxy failed' }, { status: 500 });
  }
}

export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const response = await fetch(`${INTERNAL_API_URL}/api/trade-log`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      cache: 'no-store',
    });
    const data = await response.json();
    return NextResponse.json(data, { status: response.status });
  } catch (error: any) {
    return NextResponse.json({ error: error?.message || 'Proxy failed' }, { status: 500 });
  }
}
