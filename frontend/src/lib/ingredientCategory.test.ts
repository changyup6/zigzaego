import assert from "node:assert/strict";
import { test } from "node:test";
import {
  OTHER,
  byCategory,
  catLines,
  manyCategories,
  packLines,
  withHeads,
  type CategoryHead,
  type Line,
} from "./ingredientCategory.ts";
import { toIngredientRows } from "./suppliers.ts";
import type { CategoryCode, IngredientCategory } from "./types.ts";

const cat = (id: string, name: string, code: CategoryCode | null): IngredientCategory => ({
  id,
  name,
  code,
});
const MEAT = cat("1", "육류", "MEAT");
const VEG = cat("3", "채소", "VEGETABLE");
const ETC = cat("11", "기타", "OTHER");
const MINE_B = cat("21", "튀김옷", null);
const MINE_A = cat("20", "디저트", null);
const row = (name: string, category: IngredientCategory) => ({ name, category });

test("순서: 기본 카테고리 순 → 사장님이 만든 것 이름순 → 기타", () => {
  const rows = [
    row("키친타올", ETC),
    row("빵가루", MINE_B),
    row("대파", VEG),
    row("마카롱", MINE_A),
    row("삼겹살", MEAT),
  ];
  assert.deepEqual(
    rows.sort(byCategory).map((r) => r.name),
    ["삼겹살", "대파", "마카롱", "빵가루", "키친타올"],
  );
});

test("카테고리가 같으면 원래 순서를 지킨다 (모르는 것 · 모자란 것이 앞)", () => {
  const rows = [row("양파", VEG), row("등심", MEAT), row("대파", VEG)];
  assert.deepEqual(
    rows.sort(byCategory).map((r) => r.name),
    ["등심", "양파", "대파"],
  );
});

test("응답에 카테고리가 없거나 null 이면 기타, 서버의 기타와 한 묶음", () => {
  const base = {
    unit: "G" as const,
    kind: "BOM" as const,
    unitStatus: "CONFIRMED" as const,
    storageType: "CHILLED" as const,
    safetyDays: "1.00",
    maxOrderQty: null,
    needsReview: false,
    reviewReasons: [],
  };
  const rows = toIngredientRows(
    [
      { ...base, id: "1", name: "숯" },
      { ...base, id: "2", name: "랩", category: null },
      { ...base, id: "3", name: "등심", category: MEAT },
      { ...base, id: "4", name: "호일", category: ETC },
    ],
    [],
    [],
    [],
  );
  assert.deepEqual(rows[0].category, OTHER);
  assert.deepEqual(rows[1].category, OTHER);
  const sorted = [...rows].sort(byCategory);
  assert.deepEqual(
    sorted.map((r) => r.name),
    ["등심", "숯", "랩", "호일"],
  );
  assert.deepEqual(
    withHeads(sorted, sorted).map((r) => ("head" in r ? `${r.head.name} ${r.n}` : r.name)),
    ["육류 1", "등심", "기타 3", "숯", "랩", "호일"],
  );
});

test("표 묶음 머리: 카테고리가 바뀔 때만, 개수는 전체에서, 다음 쪽에서 이어지면 다시", () => {
  const all = [row("등심", MEAT), row("삼겹살", MEAT), row("목살", MEAT), row("대파", VEG)];
  const show = (rs: ({ name: string } | CategoryHead)[]) =>
    rs.map((r) => ("head" in r ? `[${r.head.name} ${r.n}]` : r.name));
  assert.deepEqual(show(withHeads(all.slice(0, 2), all)), ["[육류 3]", "등심", "삼겹살"]);
  assert.deepEqual(show(withHeads(all.slice(2), all)), ["[육류 3]", "목살", "[채소 1]", "대파"]);
  assert.deepEqual(withHeads([], all), []);
  assert.equal(manyCategories(all), true);
  assert.equal(manyCategories(all.slice(0, 3)), false);
  assert.equal(manyCategories([]), false);
});

test("그림 보기 줄: 카테고리마다 머리 줄 뒤 새 줄, 하나뿐이면 머리 없이 cols 개씩", () => {
  const all = [row("등심", MEAT), row("삼겹살", MEAT), row("목살", MEAT), row("대파", VEG)];
  const show = (ls: Line<{ name: string }>[]) =>
    ls.map((l) => ("head" in l ? `[${l.head.name}]` : l.items.map((r) => r.name).join(",")));
  assert.deepEqual(show(catLines(all, 2, true)), [
    "[육류]",
    "등심,삼겹살",
    "목살",
    "[채소]",
    "대파",
  ]);
  assert.deepEqual(show(catLines(all, 3, false)), ["등심,삼겹살,목살", "대파"]);
  assert.deepEqual(catLines([], 3, true), []);
});

test("그림 보기 쪽: 칸 높이에 담고, 머리만 남기지 않고, 이어지는 칸은 머리를 다시 붙인다", () => {
  const all = [row("등심", MEAT), row("삼겹살", MEAT), row("목살", MEAT), row("대파", VEG)];
  const pages = (room: number[], head: number) =>
    packLines(catLines(all, 1, head > 0), room, head).map((pg) =>
      pg.map((a) => a.map((l) => ("head" in l ? `[${l.head.name}]` : l.items[0].name)).join(" ")),
    );
  // 선반 두 판(머리 + 한 줄씩)
  assert.deepEqual(pages([1.5, 1.5], 0.5), [
    ["[육류] 등심", "[육류] 삼겹살"],
    ["[육류] 목살", "[채소] 대파"],
  ]);
  // 높이 2.5 칸 하나: 육류 머리 + 두 줄, 남은 0.5 에 채소 머리만 남기지 않는다
  assert.deepEqual(pages([2.5], 0.5), [["[육류] 등심 삼겹살"], ["[육류] 목살"], ["[채소] 대파"]]);
  // 머리 없음(카테고리 하나) = 예전처럼 칸 수대로
  assert.deepEqual(pages([2], 0), [["등심 삼겹살"], ["목살 대파"]]);
  // 재료 없음: 빈 칸 하나짜리 쪽
  assert.deepEqual(packLines([], [1, 1], 0.5), [[[], []]]);
});
