import assert from "node:assert/strict";
import { test } from "node:test";
import { fmtStock, shownQty } from "./units.ts";

test("1,000 이상 g · ml 은 kg · L, 개는 그대로", () => {
  assert.deepEqual(shownQty("45000.000", "G"), { n: "45", u: "kg" });
  assert.deepEqual(shownQty(2500, "ML"), { n: "2.5", u: "L" });
  assert.deepEqual(shownQty(500, "G"), { n: "500", u: "g" });
  assert.deepEqual(shownQty(120, "EA"), { n: "120", u: "개" });
  assert.deepEqual(shownQty(null, "G"), { n: "0", u: "g" });
  assert.deepEqual(shownQty("12000.000", "g"), { n: "12", u: "kg" });
});

test("추정 재고: 개는 내림해 정수, g · ml 은 그대로", () => {
  assert.equal(fmtStock(2.7, "개"), "2");
  assert.equal(fmtStock(0.4, "EA"), "0");
  assert.equal(fmtStock(3, "개"), "3");
  assert.equal(fmtStock(2.9999999, "개"), "3");
  assert.equal(fmtStock(0.3 / 0.1, "EA"), "3");
  assert.equal(fmtStock(2.5, "g"), "2.5");
  assert.equal(fmtStock(2.5, "ML"), "2.5");
});

test("개수 천 단위를 구분해도 내림 규칙을 유지한다", () => {
  assert.equal(fmtStock(1234.9, "EA"), "1,234");
  assert.deepEqual(shownQty(1234, "EA"), { n: "1,234", u: "개" });
});
