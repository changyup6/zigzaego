import assert from "node:assert/strict";
import { test } from "node:test";
import { menuNeedsReview, qtyToSave, shownQty, splitAnswered, toRecipeTab } from "./recipes.ts";
import type { RecipeBook } from "./types.ts";

const book: RecipeBook = {
  ingredients: [
    { id: "a", name: "면", unit: "g", perServing: 180, estimated: true },
    { id: "b", name: "치즈", unit: "g", perServing: 10, estimated: false },
  ],
  menus: [
    { id: "m1", name: "x", price: 1, sold: 10, lines: [{ ingredientId: "a", mult: 1 }] },
    { id: "m2", name: "y", price: 1, sold: 5, lines: [{ ingredientId: "b", mult: 2 }] },
  ],
};

test("확인 필요는 답하지 않은 재료가 있는 메뉴만", () => {
  assert.equal(menuNeedsReview(book, book.menus[0]), true);
  assert.equal(menuNeedsReview(book, book.menus[1]), false);
});

test("배수는 소수 둘째 자리까지", () => {
  assert.equal(shownQty({ ...book.ingredients[0], perServing: 3 }, 10 / 3), 10);
});

test("가게 설정은 답하지 않은 질문만 남기고 답한 값은 prior 로", () => {
  const q = (id: string, text: string) => ({
    id,
    ingredientId: id,
    text,
    unit: "g" as const,
    options: [1, 2, 3] as [number, number, number],
  });
  const r = splitAnswered(
    [q("q1", "면 1인분"), q("q2", "치즈 1인분"), q("q3", "새우 1인분")],
    book.ingredients,
  );
  assert.deepEqual(
    r.pending.map((x) => x.id),
    ["q1", "q3"],
  );
  assert.deepEqual(r.prior, [{ questionId: "q2", value: 10, unit: "g" }]);
});

test("메뉴 · 줄 · 재료를 탭으로 묶고 주기형 재료는 고르기에서 뺀다", () => {
  const tab = toRecipeTab(
    [{ id: "1", name: "파스타", price: null, needsReview: true }],
    [
      {
        id: "9",
        menuId: "1",
        ingredientId: "3",
        ingredientName: "면",
        unit: "G",
        qtyPerServing: "150.000",
        estimated: true,
      },
    ],
    [
      { id: "3", name: "면", unit: "G", kind: "BOM" },
      { id: "4", name: "포장", unit: "EA", kind: "CYCLE" },
    ],
  );
  assert.deepEqual(tab.menus[0].lines, [
    { id: "9", ingredientId: "3", name: "면", unit: "g", qty: 150, estimated: true },
  ]);
  assert.deepEqual(tab.ingredients, [{ id: "3", name: "면", unit: "g" }]);
});

test("메뉴 사진을 메뉴별로 전달하고 사진 없는 이전 API 응답은 null로 둔다", () => {
  const menus = [
    {
      id: "1",
      name: "고기만두",
      price: 6000,
      needsReview: false,
      photoUrl: "https://images.example.com/meat.jpg",
    },
    {
      id: "2",
      name: "새우만두",
      price: 6000,
      needsReview: false,
      photoUrl: "https://images.example.com/shrimp.jpg",
    },
    { id: "3", name: "김치만두", price: 6000, needsReview: false },
    { id: "4", name: "세트", price: null, needsReview: false, photoUrl: null },
  ];
  assert.deepEqual(
    toRecipeTab(menus, [], []).menus.map((m) => m.photoUrl),
    ["https://images.example.com/meat.jpg", "https://images.example.com/shrimp.jpg", null, null],
  );
});

test("비어 있거나 웹 이미지 URL이 아닌 메뉴 사진은 자리 표시로 보낸다", () => {
  const urls = [" ", "javascript:alert(1)", "data:image/svg+xml,test", "broken", "https://"];
  const menus = urls.map((photoUrl, id) => ({
    id: String(id),
    name: "만두",
    price: null,
    needsReview: false,
    photoUrl,
  }));
  assert.deepEqual(
    toRecipeTab(menus, [], []).menus.map((m) => m.photoUrl),
    urls.map(() => null),
  );
});

test("1인분 칸: 확인 전 줄은 추정값과 같아도 저장, 확인된 줄은 바뀔 때만, 빈 값 · 음수는 안 함", () => {
  const est = { qty: 150, estimated: true };
  const ok = { qty: 150, estimated: false };
  assert.equal(qtyToSave("150", est), 150);
  assert.equal(qtyToSave("150", ok), null);
  assert.equal(qtyToSave("120", ok), 120);
  assert.equal(qtyToSave("", est), null);
  assert.equal(qtyToSave("-1", est), null);
});
