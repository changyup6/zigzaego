import assert from "node:assert/strict";
import { test } from "node:test";
import {
  Z,
  capQty,
  ingredientBody,
  priceChange,
  productPlan,
  safetyStock,
  shownUnit,
  supplierBody,
  toIngredientRows,
  withStock,
} from "./suppliers.ts";
import type { IngredientEdit, IngredientRow, SupplierProductApi } from "./types.ts";

test("안전재고는 일수를 바꾸면 바뀐다", () => {
  const daily = [7.8, 8.1, 8.4, 8.6, 9.5, 10.2, 7.5];
  assert.equal(safetyStock(daily, 1), 8.6);
  assert.equal(safetyStock(daily, 2), 17.2);
});

test("보관 방식별 z", () => {
  assert.equal(Z.CHILLED, 1.65);
  assert.notEqual(Z.FRESH, Z.CHILLED);
});

test("1회 최대가 추천 수량을 자른다", () => {
  assert.equal(capQty(25, 20), 20);
  assert.equal(capQty(5, 20), 5);
});

test("단가 변화와 표시 단위", () => {
  assert.equal(priceChange(4200, 3750), 12);
  assert.equal(shownUnit("g"), "kg");
  assert.equal(shownUnit("개"), "개");
});

const ing = {
  id: "3",
  name: "대파",
  unit: "G" as const,
  kind: "BOM" as const,
  unitStatus: "CONFIRMED" as const,
  storageType: "CHILLED" as const,
  safetyDays: "1.50",
  maxOrderQty: "20000.000",
  needsReview: true,
  reviewReasons: ["NEEDS_COUNT" as const],
};
const prod: SupplierProductApi = {
  id: "9",
  supplierId: "4",
  ingredientId: "3",
  productName: "대파 1단",
  baseQtyPerPack: "1000.000",
  price: 4200,
  primary: true,
};

test("재료 줄: 주 상품 단가 · kg 환산 · 7일 소비량", () => {
  const dates = ["2026-10-17", "2026-10-18"];
  const [r] = toIngredientRows(
    [ing],
    [prod],
    [{ date: "2026-10-18", ingredientId: "3", qty: "2000" }],
    dates,
  );
  assert.equal(r.unitPrice, 4200);
  assert.equal(r.maxQty, 20);
  assert.deepEqual(r.daily, [0, 2]);
  assert.equal(r.supplierId, "4");
  assert.equal(r.needsReview, true);
});

const [row] = toIngredientRows([ing], [prod], [], []);
const edit: IngredientEdit = {
  safetyDays: 1.5,
  maxQty: 20,
  supplierId: "4",
  packLabel: "대파 1단",
  unitPrice: 4200,
  storage: "CHILLED",
  kind: "BOM",
  baseUnit: "g",
};

test("재료 저장은 바뀐 칸만 기준 단위로 보낸다", () => {
  assert.deepEqual(ingredientBody(row, edit), {});
  assert.deepEqual(ingredientBody(row, { ...edit, maxQty: 30, safetyDays: 2 }), {
    maxOrderQty: 30000,
    safetyDays: 2,
  });
});

test("거래처를 그대로 두고 단가만 바꾸면 그 상품을 고친다", () => {
  assert.deepEqual(productPlan(row, { ...edit, unitPrice: 5000 }, [prod]), {
    op: "patch",
    id: "9",
    body: { price: 5000 },
  });
});

test("다른 거래처로 바꾸면 그 거래처 상품을 새로 만든다", () => {
  const plan = productPlan(row, { ...edit, supplierId: "5" }, [prod]);
  assert.equal(plan.op, "create");
  if (plan.op === "create") assert.equal(plan.body.primary, true);
  assert.equal(productPlan(row, { ...edit, supplierId: "" }, [prod]).op, "none");
});

test("거래처 폼: 비운 칸은 보내지 않는다", () => {
  assert.deepEqual(supplierBody({ name: " 한결 ", cutoff: "", leadDays: 0, days: ["MON"] }), {
    name: "한결",
    leadTimeDays: 0,
    deliveryDays: ["MON"],
  });
});

test("추정 재고를 화면 단위로 싣고, 실사 필요면 확인 필요로", () => {
  const row = {
    id: "1",
    baseUnit: "g",
    stock: null,
    needsReview: false,
    reasons: [],
  } as unknown as IngredientRow;
  const [r] = withStock(
    [row],
    [
      {
        ingredientId: "1",
        ingredientName: "",
        unit: "G",
        estimatedQty: "4250.000",
        needsCount: true,
        runOutDate: null,
        lastCountedAt: null,
      },
    ],
  );
  assert.deepEqual([r.stock, r.needsReview, r.reasons], [4.25, true, ["NEEDS_COUNT"]]);
});
