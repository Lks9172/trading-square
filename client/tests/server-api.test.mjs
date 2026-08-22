import assert from "node:assert/strict";
import { describe, test } from "node:test";

import {
  fetchServerJson,
  ServerApiUnavailableError,
} from "../src/lib/server-api.ts";

const immediateRetry = {
  retryDelayMs: 0,
  timeoutMs: 100,
};

describe("fetchServerJson", { concurrency: false }, () => {
  test("일시적인 연결 실패 후 정상 응답을 반환한다", async (context) => {
    let calls = 0;
    context.mock.method(globalThis, "fetch", async () => {
      calls += 1;
      if (calls < 3) throw new TypeError("connection refused");
      return Response.json({ ok: true });
    });

    const result = await fetchServerJson("/api/snapshot", {
      ...immediateRetry,
      attempts: 3,
    });

    assert.deepEqual(result, { ok: true });
    assert.equal(calls, 3);
  });

  test("재시도는 실패 응답 캐시를 우회한다", async (context) => {
    const requestInits = [];
    context.mock.method(globalThis, "fetch", async (_input, init) => {
      requestInits.push(init);
      return requestInits.length === 1
        ? new Response(null, { status: 503 })
        : Response.json({ recovered: true });
    });

    const result = await fetchServerJson("/api/research/sectors", {
      ...immediateRetry,
      attempts: 2,
      revalidate: 300,
    });

    assert.deepEqual(result, { recovered: true });
    assert.deepEqual(requestInits[0].next, { revalidate: 300 });
    assert.equal(requestInits[1].cache, "no-store");
  });

  test("404는 재시도하지 않고 null로 처리한다", async (context) => {
    let calls = 0;
    context.mock.method(globalThis, "fetch", async () => {
      calls += 1;
      return new Response(null, { status: 404 });
    });

    const result = await fetchServerJson("/api/company/UNKNOWN", {
      ...immediateRetry,
      attempts: 5,
    });

    assert.equal(result, null);
    assert.equal(calls, 1);
  });

  test("지속적인 5xx는 ISR이 정상 캐시를 보존하도록 오류를 전파한다", async (context) => {
    let calls = 0;
    context.mock.method(globalThis, "fetch", async () => {
      calls += 1;
      return new Response(null, { status: 503 });
    });

    await assert.rejects(
      fetchServerJson("/api/research/sectors", {
        ...immediateRetry,
        attempts: 3,
      }),
      ServerApiUnavailableError,
    );
    assert.equal(calls, 3);
  });
});
