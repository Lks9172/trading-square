import { NextRequest, NextResponse } from 'next/server';
import { errorMessage } from '@/lib/server-api';

const INTERNAL_API_URL = process.env.SSR_API_URL || 'http://macrosquare-server:5846';

export async function DELETE(
  _request: NextRequest,
  { params }: { params: Promise<{ asset: string }> }
) {
  try {
    const { asset } = await params;
    const response = await fetch(
      `${INTERNAL_API_URL}/api/execution-plan/tranche/${encodeURIComponent(asset)}`,
      { method: 'DELETE', cache: 'no-store' }
    );
    const data = await response.json();
    return NextResponse.json(data, { status: response.status });
  } catch (error) {
    return NextResponse.json(
      { error: errorMessage(error, 'Proxy request failed') },
      { status: 500 }
    );
  }
}
