import assert from "node:assert/strict";
import { test } from "node:test";
import { reviewCounts, setupSteps } from "./setup.ts";

test("가게 설정: 온보딩 · 첫 입고 확인은 빼고 순서 그대로", () => {
  const steps = setupSteps({
    checklist: ["ONBOARDING", "RECIPE", "POS", "SUPPLIER", "FIRST_RECEIVE", "STOCK_COUNT"].map(
      (step, i) => ({ step, done: i % 2 === 0, remaining: 0 }),
    ),
  });
  assert.deepEqual(
    steps.map((s) => [s.id, s.done, s.href]),
    [
      ["RECIPE", false, "/recipes"],
      ["POS", true, "/more#pos"],
      ["SUPPLIER", false, "/suppliers"],
      ["STOCK_COUNT", false, "/ingredients"],
    ],
  );
});

test("탭 배지: 레시피 확인을 끝내면 레시피 배지가 사라진다", () => {
  const store = (recipeLeft: number, counted: boolean) => ({
    checklist: [
      { step: "ONBOARDING", done: true, remaining: 0 },
      { step: "RECIPE", done: recipeLeft === 0, remaining: recipeLeft },
      { step: "POS", done: false, remaining: 1 },
      { step: "SUPPLIER", done: false, remaining: 1 },
      { step: "STOCK_COUNT", done: counted, remaining: counted ? 0 : 1 },
    ],
  });
  const zero = { order: 0, receiving: 0, recipes: 0, suppliers: 0, more: 0 };
  assert.deepEqual(reviewCounts(store(3, false)), { ...zero, recipes: 3, suppliers: 1 });
  assert.deepEqual(reviewCounts(store(0, false)), { ...zero, suppliers: 1 });
  assert.deepEqual(reviewCounts(store(0, true)), zero);
  assert.deepEqual(reviewCounts(null), zero);
});
