import assert from "node:assert/strict";
import { test } from "node:test";
import { monthRange, toTxRows } from "./history.ts";
import type { MonthHistoryApi } from "./types";

test("지난 발주 줄: 상태 글자 · kg 표시 · 금액은 서버 값, 양을 모르면 –", () => {
  const l: MonthHistoryApi["lines"][number] = {
    date: "2026-10-29",
    itemId: "1",
    ingredientName: "닭가슴살",
    supplierName: "미트",
    qty: "12000.000",
    unit: "g",
    amount: 117600,
    status: "SENT",
  };
  const h = { month: "2026-10", lines: [l], totalAmount: 117600, earliestMonth: "2025-01" };
  const [r] = toTxRows(h);
  assert.deepEqual([r.status, r.n, r.u, r.amount], ["입고 예정", "12", "kg", 117600]);
  const [c] = toTxRows({ ...h, lines: [{ ...l, status: "RECEIVED", qty: null, amount: null }] });
  assert.deepEqual([c.status, c.n, c.u, c.amount], ["입고 완료", "–", "", null]);
});

test("연월 목록은 가장 이른 발주 달부터 이번 달까지, 해를 넘긴다", () => {
  assert.deepEqual(monthRange("2025-11", "2026-01-05"), ["2025-11", "2025-12", "2026-01"]);
  assert.deepEqual(monthRange(null, "2026-10-30"), ["2026-10"]);
});
