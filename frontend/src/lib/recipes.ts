import type {
  IngredientApi,
  MenuApi,
  RecipeAnswer,
  RecipeBook,
  RecipeIngredient,
  RecipeLine,
  RecipeMenu,
  RecipeApi,
  RecipeQuestion,
  RecipeTab,
} from "./types";
import { FROM_API } from "./units.ts";

const r2 = (n: number) => Math.round(n * 100) / 100;

const menuPhotoUrl = (value?: string | null): string | null => {
  if (!value?.trim()) return null;
  try {
    const url = new URL(value.trim());
    return ["https:", "http:"].includes(url.protocol) && !url.username && !url.password
      ? value.trim()
      : null;
  } catch {
    return null;
  }
};

// 메뉴에 보이는 1인분 양 = 재료 1인분 × 배수
export const shownQty = (ing: RecipeIngredient, mult: number) => r2(ing.perServing * mult);

export const menuRows = (book: RecipeBook, menu: RecipeMenu) =>
  menu.lines.flatMap((l) => {
    const ing = book.ingredients.find((i) => i.id === l.ingredientId);
    return ing ? [{ ing, mult: l.mult }] : [];
  });

// 레시피 탭 1인분 칸을 벗어날 때 저장할 값 (안 하면 null). 확인 전 줄은 추정값과 같아도 저장해 확인한다
export const qtyToSave = (raw: string, line: Pick<RecipeLine, "qty" | "estimated">) => {
  const v = Number(raw);
  if (raw.trim() === "" || Number.isNaN(v) || v < 0) return null;
  return line.estimated || v !== line.qty ? v : null;
};

// 답하지 않은 재료가 하나라도 있는 메뉴 = 이름 옆 '확인 필요'
export const menuNeedsReview = (book: RecipeBook, menu: RecipeMenu) =>
  menuRows(book, menu).some((r) => r.ing.estimated);

// 가게 설정 '레시피 확인': 질문 문구 '<재료 이름> 1인분'과 저장된 재료 이름이 같고 확인됨이면 답한 질문.
// confirm 은 다시 저장하므로 이미 답한 값은 저장된 값으로 되돌려 보낸다(prior).
export function splitAnswered(
  questions: RecipeQuestion[],
  ingredients: Pick<RecipeBook, "ingredients">["ingredients"],
) {
  const pending: RecipeQuestion[] = [];
  const prior: RecipeAnswer[] = [];
  for (const q of questions) {
    const name = q.text.replace(/ 1인분$/, "");
    const ing = ingredients.find((i) => i.name === name && !i.estimated);
    if (ing) prior.push({ questionId: q.id, value: ing.perServing, unit: ing.unit });
    else pending.push(q);
  }
  return { pending, prior };
}

// 메뉴 · 레시피 줄 · 재료 세 목록을 레시피 탭 한 덩어리로. 주기형 재료는 레시피에 넣을 수 없어 고르기에서 뺀다.
export function toRecipeTab(
  menus: MenuApi[],
  recipes: RecipeApi[],
  ingredients: IngredientApi[],
): RecipeTab {
  return {
    menus: menus.map((m) => ({
      id: m.id,
      name: m.name,
      price: m.price,
      photoUrl: menuPhotoUrl(m.photoUrl),
      needsReview: m.needsReview,
      lines: recipes
        .filter((r) => r.menuId === m.id)
        .map((r) => ({
          id: r.id,
          ingredientId: r.ingredientId,
          name: r.ingredientName,
          unit: FROM_API[r.unit],
          qty: Number(r.qtyPerServing),
          estimated: r.estimated,
        })),
    })),
    ingredients: ingredients
      .filter((i) => i.kind !== "CYCLE")
      .map((i) => ({ id: i.id, name: i.name, unit: FROM_API[i.unit] })),
  };
}
