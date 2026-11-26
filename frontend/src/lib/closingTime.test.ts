import assert from "node:assert/strict";
import { test } from "node:test";
import { toClosingTimes } from "./closingTime.ts";

const all = Array(7).fill(true);

test("고른 요일에만 같은 시각, 월 = 1 … 일 = 7", () => {
  const open = [true, true, true, true, true, true, false];
  const times = toClosingTimes("21:30", open);
  assert.equal(times.length, 6);
  assert.deepEqual(times[0], { dayOfWeek: 1, closeTime: "21:30" });
  assert.deepEqual(times[5], { dayOfWeek: 6, closeTime: "21:30" });
});

test("시각이 없거나 요일이 없으면 빈 목록", () => {
  assert.deepEqual(toClosingTimes("", all), []);
  assert.deepEqual(toClosingTimes("25:00", all), []);
  assert.deepEqual(toClosingTimes("21:30", Array(7).fill(false)), []);
});
