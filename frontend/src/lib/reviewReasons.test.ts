import assert from "node:assert/strict";
import { test } from "node:test";
import { resolveReviewRow, reviewCount } from "./reviewReasons.ts";
import type { Reviewable } from "./types.ts";

const row = (id: string, needsReview: boolean): Reviewable => ({
  id,
  needsReview,
  reasons: needsReview ? ["NEEDS_COUNT"] : [],
});
const rows = [row("a", false), row("b", true), row("c", false), row("d", true)];

test("해결해도 행 순서는 그대로, 개수 · 이유가 준다", () => {
  const next = resolveReviewRow(rows, "b");
  assert.deepEqual(
    next.map((r) => r.id),
    ["a", "b", "c", "d"],
  );
  assert.equal(reviewCount(rows), 2);
  assert.equal(reviewCount(next), 1);
  const none = resolveReviewRow(next, "d");
  assert.equal(reviewCount(none), 0);
});
