import assert from "node:assert/strict";
import { test } from "node:test";
import { fmt, won } from "./format.ts";

test("표시 숫자는 천 단위를 구분하고 기존 한 자리 반올림을 유지한다", () => {
  for (const [n, want] of [
    [1000000, "1,000,000"],
    [1234.5, "1,234.5"],
    [1234.56, "1,234.6"],
    [999.96, "1,000"],
    [-1234.5, "-1,234.5"],
    [0, "0"],
    [0.04, "0"],
    [-0.04, "0"],
  ] as const) {
    assert.equal(fmt(n), want);
  }
  assert.equal(won(1000000.4), "1,000,000원");
});
