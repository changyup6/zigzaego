import type { CategoryCode, IngredientCategory } from "./types";

// 재료 탭 카테고리 묶음 (S15P31A308-279, DECISIONS #77). 순서 정본은 backend DefaultIngredientCategory:
// 기본 10종 → 사장님이 만든 것(이름순) → 기타
const ORDER: CategoryCode[] = [
  "MEAT",
  "SEAFOOD",
  "VEGETABLE",
  "FRUIT",
  "EGG_DAIRY",
  "GRAIN_NOODLE",
  "TOFU_BEAN",
  "SAUCE",
  "PROCESSED",
  "BEVERAGE",
];

// 응답에 카테고리가 없거나 null 일 때
export const OTHER: IngredientCategory = { id: "", name: "기타", code: "OTHER" };

// 모르는 code 는 사장님이 만든 것처럼 기본 뒤 · 기타 앞에
const rankOf = (c: IngredientCategory) =>
  c.code === "OTHER"
    ? ORDER.length + 1
    : c.code && ORDER.includes(c.code)
      ? ORDER.indexOf(c.code)
      : ORDER.length;
// 같은 카테고리인지. 기본 것은 code 로 (응답에 없어 넣은 기타와 서버의 기타가 한 묶음)
export const keyOf = (c: IngredientCategory) => c.code ?? c.id;

type HasCategory = { category: IngredientCategory };

// 카테고리가 2개 이상일 때만 아이콘 · 묶음 머리를 보인다 (자동 분류 전처럼 전부 기타면 숨김)
export const manyCategories = (rows: HasCategory[]) =>
  new Set(rows.map((r) => keyOf(r.category))).size > 1;

export const byCategory = (a: HasCategory, b: HasCategory) =>
  rankOf(a.category) - rankOf(b.category) || a.category.name.localeCompare(b.category.name, "ko");

// 표 한 쪽: 카테고리가 바뀌는 자리에 묶음 머리. 개수는 그 쪽이 아니라 전체(all)에서 센다
export type CategoryHead = { head: IngredientCategory; n: number };
export function withHeads<T extends HasCategory>(page: T[], all: T[]): (T | CategoryHead)[] {
  return page.flatMap((r, i) =>
    i > 0 && keyOf(page[i - 1].category) === keyOf(r.category)
      ? [r]
      : [
          {
            head: r.category,
            n: all.filter((x) => keyOf(x.category) === keyOf(r.category)).length,
          },
          r,
        ],
  );
}

// 그림 보기 한 칸(냉동 · 냉장 · 선반 한 판 · 창고)의 줄: 카테고리 머리 한 줄, 또는 재료 cols 개
export type Line<T> = { head: IngredientCategory } | { items: T[] };

// 카테고리 순으로 정렬된 재료 → 줄. 카테고리마다 머리 줄 뒤 새 줄에서 시작. heads=false 면 머리 없이 cols 개씩
export function catLines<T extends HasCategory>(
  rows: T[],
  cols: number,
  heads: boolean,
): Line<T>[] {
  const out: Line<T>[] = [];
  for (let i = 0, j = 0; i < rows.length; i = j) {
    j = i;
    while (j < rows.length && (!heads || keyOf(rows[j].category) === keyOf(rows[i].category))) j++;
    if (heads) out.push({ head: rows[i].category });
    for (let k = i; k < j; k += cols) out.push({ items: rows.slice(k, Math.min(k + cols, j)) });
  }
  return out;
}

// 줄을 쪽마다 칸(room = 칸 높이, 재료 줄 = 1 · 머리 줄 = head)에 담는다.
// 머리는 다음 재료 줄과 함께 넘기고, 칸이 이어지는 재료 줄로 시작하면 머리를 다시 붙인다
export function packLines<T>(lines: Line<T>[], room: number[], head: number): Line<T>[][][] {
  const pages: Line<T>[][][] = [];
  let page: Line<T>[][] = [];
  let area: Line<T>[] = [];
  let used = 0;
  let cat: IngredientCategory | null = null;
  const close = () => {
    page.push(area);
    area = [];
    used = 0;
    if (page.length === room.length) {
      pages.push(page);
      page = [];
    }
  };
  for (const l of lines) {
    const isHead = "head" in l;
    if (isHead) cat = l.head;
    if (area.length && used + (isHead ? head + 1 : 1) > room[page.length] + 1e-9) close();
    if (!area.length && !isHead && cat) {
      area.push({ head: cat });
      used += head;
    }
    area.push(l);
    used += isHead ? head : 1;
  }
  if (area.length) close();
  if (page.length || !pages.length) pages.push(room.map((_, k) => page[k] ?? []));
  return pages;
}
