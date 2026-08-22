import { NextRequest, NextResponse } from 'next/server';
import { errorMessage } from '@/lib/server-api';

const INTERNAL_API_URL = process.env.SSR_API_URL || 'http://macrosquare-server:5846';

type Context = {
  params: Promise<{
    source: string;
    key: string;
  }>;
};

export async function GET(_request: NextRequest, context: Context) {
  try {
    const { source, key } = await context.params;
    const response = await fetch(`${INTERNAL_API_URL}/api/history/${source}/${key}`, {
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
