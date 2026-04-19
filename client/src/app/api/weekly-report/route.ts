import { NextRequest, NextResponse } from 'next/server';

const INTERNAL_API_URL = process.env.SSR_API_URL || 'http://macrosquare-server:5846';

export async function GET(req: NextRequest) {
  try {
    const format = req.nextUrl.searchParams.get('format') || 'json';
    const response = await fetch(`${INTERNAL_API_URL}/api/weekly-report?format=${encodeURIComponent(format)}`, {
      cache: 'no-store',
    });
    if (format === 'text') {
      const txt = await response.text();
      return new NextResponse(txt, {
        status: response.status,
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
      });
    }
    const data = await response.json();
    return NextResponse.json(data, { status: response.status });
  } catch (error: any) {
    return NextResponse.json({ error: error?.message || 'Proxy failed' }, { status: 500 });
  }
}
