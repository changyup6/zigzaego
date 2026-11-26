import assert from "node:assert/strict";
import { test } from "node:test";
import { colsFor, defaultPlace, lastOrderDates, pageOf, pageWindow } from "./stockPlace.ts";
import { packPriceLabel } from "./suppliers.ts";

test("보관 위치 기본값: 냉동 · 냉장/신선 · 실온 · 주기형", () => {
  assert.equal(defaultPlace("FROZEN", "BOM"), "FREEZER");
  assert.equal(defaultPlace("CHILLED", "BOM"), "FRIDGE");
  assert.equal(defaultPlace("FRESH", "ONE_TO_ONE"), "FRIDGE");
  assert.equal(defaultPlace("AMBIENT", "BOM"), "SHELF");
  assert.equal(defaultPlace("CHILLED", "CYCLE"), "WAREHOUSE");
  assert.equal(defaultPlace(null, "BOM"), "WAREHOUSE");
});

test("쪽 넘김: 자르기 · 넘친 쪽은 마지막 쪽", () => {
  const l = [1, 2, 3, 4, 5, 6, 7];
  assert.deepEqual(pageOf(l, 3, 1), { items: [4, 5, 6], page: 1, pages: 3 });
  assert.deepEqual(pageOf(l, 3, 9).page, 2);
  assert.deepEqual(pageOf([], 3, 0), { items: [], page: 0, pages: 1 });
});

test("번호 창: 지금 쪽이 가운데, 양 끝에서는 붙는다", () => {
  assert.deepEqual(pageWindow(0, 9), [0, 1, 2, 3, 4]);
  assert.deepEqual(pageWindow(4, 9), [2, 3, 4, 5, 6]);
  assert.deepEqual(pageWindow(8, 9), [4, 5, 6, 7, 8]);
  assert.deepEqual(pageWindow(1, 3), [0, 1, 2]);
});

test("칸 수: 최소 폭 · 사이 간격", () => {
  assert.equal(colsFor(500, 112, 4), 4);
  assert.equal(colsFor(50, 112, 4), 1);
});

test("최근 주문일: 이 거래처 · 취소 빼고 가장 늦은 날", () => {
  const l = (date: string, ingredientName: string, supplierName = "한빛", status = "SENT") => ({
    date,
    ingredientName,
    supplierName,
    status,
  });
  assert.deepEqual(
    lastOrderDates(
      [
        l("2026-11-02", "양파"),
        l("2026-11-04", "양파"),
        l("2026-10-30", "양파"),
        l("2026-11-05", "양파", "푸른"),
        l("2026-11-05", "두부", "한빛", "CANCELLED"),
        l("2026-11-01", "두부"),
      ],
      "한빛",
    ),
    { 양파: "2026-11-04", 두부: "2026-11-01" },
  );
});

test("단가 글자: 포장 · 단위 · 모름", () => {
  const r = { unitPrice: 1363, packQty: 15, packUnit: "망", baseUnit: "g" as const };
  assert.equal(packPriceLabel(r), "20,445원/1망");
  assert.equal(packPriceLabel({ ...r, packQty: 0 }), "1,363원/kg");
  assert.equal(packPriceLabel({ ...r, unitPrice: 0 }), "–");
});
