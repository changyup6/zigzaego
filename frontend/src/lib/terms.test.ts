import assert from "node:assert/strict";
import { test } from "node:test";
import { NO_TERMS, allAgreed, setAll } from "./terms.ts";

test("전체 동의가 셋을 함께 켜고 끈다", () => {
  assert.ok(allAgreed(setAll(true)));
  assert.ok(!allAgreed(setAll(false)));
});

test("하나라도 꺼져 있으면 다음이 눌리지 않는다", () => {
  assert.ok(!allAgreed(NO_TERMS));
  assert.ok(!allAgreed({ ...setAll(true), menu: false }));
});
