import { test } from "node:test";
import assert from "node:assert/strict";
import { homeIncoming } from "./homeIncoming.ts";

test("오늘 입고 품목의 거래처와 수량을 유지하고 다른 날은 제외한다", () => {
  const items = [
    {
      itemId: "1",
      name: "새우",
      supplier: "수산 거래처",
      qty: 2000,
      unit: "g" as const,
      today: true,
    },
    {
      itemId: "2",
      name: "부추",
      supplier: "청과 거래처",
      qty: 100,
      unit: "g" as const,
      today: false,
    },
  ];
  assert.deepEqual(homeIncoming(items), [
    { id: "1", name: "새우", supplier: "수산 거래처", n: "2", u: "kg" },
  ]);
});
