import { NextResponse } from 'next/server';
const URL = process.env.SSR_API_URL || 'http://macrosquare-server:5846';
export async function GET() {
  try {
    const r = await fetch(`${URL}/api/earnings`, { cache: 'no-store' });
    return NextResponse.json(await r.json(), { status: r.status });
  } catch (e: any) { return NextResponse.json({ error: e?.message }, { status: 500 }); }
}
