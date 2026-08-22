import { NextResponse } from 'next/server';
import { errorMessage } from '@/lib/server-api';
const URL = process.env.SSR_API_URL || 'http://macrosquare-server:5846';
export async function GET() {
  try {
    const r = await fetch(`${URL}/api/smart-money`, { cache: 'no-store' });
    return NextResponse.json(await r.json(), { status: r.status });
  } catch (e) { return NextResponse.json({ error: errorMessage(e) }, { status: 500 }); }
}
