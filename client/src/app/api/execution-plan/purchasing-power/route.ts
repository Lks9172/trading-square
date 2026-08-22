import type { NextRequest } from 'next/server';
import { NextResponse } from 'next/server';
import { errorMessage } from '@/lib/server-api';

const INTERNAL_API_URL = process.env.SSR_API_URL || 'http://macrosquare-server:5846';
const ALLOWED_PARAMS = [
  'principalKrw',
  'years',
  'inflationPct',
  'cashYieldPct',
  'productiveAssetReturnPct',
] as const;

export async function GET(request: NextRequest) {
  try {
    const query = new URLSearchParams();
    for (const key of ALLOWED_PARAMS) {
      const value = request.nextUrl.searchParams.get(key);
      if (value !== null) query.set(key, value);
    }
    const suffix = query.size ? `?${query.toString()}` : '';
    const response = await fetch(
      `${INTERNAL_API_URL}/api/execution-plan/purchasing-power${suffix}`,
      { cache: 'no-store', signal: AbortSignal.timeout(2_500) },
    );
    const data = await response.json();
    return NextResponse.json(data, { status: response.status });
  } catch (error) {
    return NextResponse.json(
      { error: errorMessage(error, 'Purchasing-power proxy failed') },
      { status: 502 },
    );
  }
}
