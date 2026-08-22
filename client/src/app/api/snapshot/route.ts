import { NextRequest, NextResponse } from 'next/server';
import { errorMessage } from '@/lib/server-api';

const INTERNAL_API_URL = process.env.SSR_API_URL || 'http://macrosquare-server:5846';

export async function GET() {
  try {
    const response = await fetch(`${INTERNAL_API_URL}/api/snapshot`, {
      cache: 'no-store',
    });

    const data = await response.json();
    return NextResponse.json(data, { status: response.status });
  } catch (error) {
    return NextResponse.json(
      { error: errorMessage(error, 'Proxy request failed') },
      { status: 500 }
    );
  }
}

export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const response = await fetch(`${INTERNAL_API_URL}/api/snapshot`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      cache: 'no-store',
    });

    const data = await response.json();
    return NextResponse.json(data, { status: response.status });
  } catch (error) {
    return NextResponse.json(
      { error: errorMessage(error, 'Proxy request failed') },
      { status: 500 }
    );
  }
}
