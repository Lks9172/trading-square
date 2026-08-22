import assert from "node:assert/strict";
import { describe, test } from "node:test";

import { parseWebVitalPayload } from "../src/lib/rum.ts";

const valid = {
  name: "INP",
  value: 128.4,
  delta: 12.1,
  rating: "good",
  id: "v4-171234",
  navigationType: "navigate",
  path: "/company/NVDA",
};

describe("parseWebVitalPayload", () => {
  test("개인정보 없는 bounded Web Vital만 정규화한다", () => {
    assert.deepEqual(parseWebVitalPayload(valid), valid);
  });

  test("알 수 없는 metric과 비정상 숫자를 거부한다", () => {
    assert.equal(parseWebVitalPayload({ ...valid, name: "CUSTOM" }), null);
    assert.equal(parseWebVitalPayload({ ...valid, value: Number.NaN }), null);
  });

  test("쿼리스트링과 과도하게 긴 경로를 수집하지 않는다", () => {
    assert.equal(parseWebVitalPayload({ ...valid, path: "/company/NVDA?token=value" }), null);
    assert.equal(parseWebVitalPayload({ ...valid, path: `/${"a".repeat(300)}` }), null);
  });
});
