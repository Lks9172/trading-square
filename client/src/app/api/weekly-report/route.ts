import { NextRequest, NextResponse } from 'next/server';
import { errorMessage } from '@/lib/server-api';

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
  } catch (error) {
    return NextResponse.json({ error: errorMessage(error) }, { status: 500 });
  }
}
