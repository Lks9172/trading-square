import { NextResponse } from 'next/server';
const API_BASE = process.env.SSR_API_URL || 'http://macrosquare-server:5846';
export async function GET(req: Request) {
  try {
    const qs = new globalThis.URL(req.url).search;
    const r = await fetch(`${API_BASE}/api/correlation${qs}`, { cache: 'no-store' });
    return NextResponse.json(await r.json(), { status: r.status });
  } catch (e: any) { return NextResponse.json({ error: e?.message }, { status: 500 }); }
}
