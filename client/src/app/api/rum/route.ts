import { NextRequest } from "next/server";
import { parseWebVitalPayload } from "@/lib/rum";

const MAX_BODY_BYTES = 2_048;

export async function POST(request: NextRequest) {
  if (!sameOrigin(request)) return response(403);

  const declaredLength = Number(request.headers.get("content-length") ?? "0");
  if (Number.isFinite(declaredLength) && declaredLength > MAX_BODY_BYTES) return response(413);

  let text: string;
  try {
    text = await request.text();
  } catch {
    return response(400);
  }
  if (Buffer.byteLength(text, "utf8") > MAX_BODY_BYTES) return response(413);

  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return response(400);
  }
  const metric = parseWebVitalPayload(parsed);
  if (!metric) return response(422);

  // Structured stdout is collected by Alloy/Loki. No IP, user-agent, query string,
  // cookies or persistent user identifier is retained.
  console.info(JSON.stringify({
    timestamp: new Date().toISOString(),
    level: "INFO",
    event: "browser_web_vital",
    ...metric,
  }));
  return response(204);
}

function sameOrigin(request: NextRequest): boolean {
  const origin = request.headers.get("origin");
  if (!origin) return true;
  try {
    return new URL(origin).origin === request.nextUrl.origin;
  } catch {
    return false;
  }
}

function response(status: number): Response {
  return new Response(null, {
    status,
    headers: { "cache-control": "no-store" },
  });
}
