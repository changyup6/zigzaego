import assert from "node:assert/strict";
import { test } from "node:test";
import { canSave, startValue, toBody, type ClosingItem, type ParsedLine } from "./closingCheck.ts";

const onion: ClosingItem = {
  ingredientId: "onion",
  name: "양파",
  estimatedQty: 1450,
  baseUnit: "G",
  packUnit: "망",
  baseQtyPerPack: 1000,
  estimatedPacks: 1.5,
  step: 0.5,
};
const egg: ClosingItem = {
  ingredientId: "egg",
  name: "달걀",
  estimatedQty: 42.4,
  baseUnit: "EA",
  packUnit: null,
  baseQtyPerPack: null,
  estimatedPacks: null,
  step: 1,
};
const line = (over: Partial<ParsedLine>): ParsedLine => ({
  ingredientId: "milk",
  ingredientName: "우유",
  action: "DISCARD",
  qty: 1,
  unit: "팩",
  baseQty: 1000,
  reason: "SPOILED",
  ...over,
});

test("처음 값: 포장 단위가 있으면 포장 수, 없으면 기준 단위 반올림", () => {
  assert.equal(startValue(onion), 1.5);
  assert.equal(startValue(egg), 42);
});

test("품목만 확인: 손대지 않으면 CONFIRMED, 바꾸면 CORRECTED, 기준 단위로 환산", () => {
  const body = toBody([onion, egg], { onion: 2 }, [], "");
  assert.deepEqual(body, {
    counts: [
      { ingredientId: "onion", qty: 2000, estimatedQty: 1450, method: "CORRECTED" },
      { ingredientId: "egg", qty: 42, estimatedQty: 42.4, method: "CONFIRMED" },
    ],
    adjustments: [],
  });
});

test("자유 입력만: 버린 양은 빼기, 원문을 같이 보낸다", () => {
  const body = toBody([], {}, [line({})], "우유 한 팩 상했어");
  assert.deepEqual(body, {
    counts: [],
    adjustments: [{ ingredientId: "milk", qty: 1000, reason: "SPOILED" }],
    sourceText: "우유 한 팩 상했어",
  });
});

test("말로 한 남은 양은 같은 재료의 확인 값을 덮는다", () => {
  const said = line({ ingredientId: "onion", action: "COUNT", baseQty: 500 });
  assert.deepEqual(toBody([onion], {}, [said], "양파 반 망 남음").counts, [
    { ingredientId: "onion", qty: 500, estimatedQty: 1450, method: "CORRECTED" },
  ]);
});

test("재료를 못 찾았거나 단위를 모르는 줄이 있으면 저장할 수 없다", () => {
  assert.equal(canSave([onion], []), true);
  assert.equal(canSave([], []), false);
  assert.equal(canSave([onion], [line({ baseQty: null })]), false);
  assert.equal(canSave([], [line({ ingredientId: null })]), false);
  assert.equal(canSave([], [line({})]), true);
});
