"use client";

import { useCallback, useEffect, useState } from "react";
import {
  createIngredient,
  createRecipeLine,
  deleteRecipeLine,
  getIngredients,
  getMenus,
  getRecipeLines,
  updateRecipeLine,
} from "@/lib/api";
import { toRecipeTab } from "@/lib/recipes";
import type { ApiResponse, RecipeLineDraft, RecipeTab } from "@/lib/types";

// 메뉴 목록 ↔ 메뉴 상세 (시안 state.menu). GET /menus · /recipes · /ingredients.
// 고치면 저장하고 세 목록을 다시 받아 새로고침과 같은 화면을 보인다.
export function useRecipeBook() {
  const [tab, setTab] = useState<RecipeTab | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [menuId, setMenuId] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

  const load = useCallback(async () => {
    const [m, r, i] = await Promise.all([getMenus(), getRecipeLines(), getIngredients()]);
    if (!m.success || !r.success || !i.success) return setError("불러오기 실패");
    setTab(toRecipeTab(m.data, r.data, i.data));
    setError(null);
  }, []);

  useEffect(() => {
    let live = true;
    (async () => {
      if (live) await load();
    })();
    return () => {
      live = false;
    };
  }, [load]);

  // 데스크톱은 목록 옆에 고른 메뉴(처음엔 첫 메뉴)를 늘 보인다. 폰은 opened 일 때만 상세 (시안 recipeOpen)
  const [opened, setOpened] = useState(false);
  const menu = tab?.menus.find((m) => m.id === menuId) ?? tab?.menus[0] ?? null;

  const save = async (job: () => Promise<ApiResponse<unknown>>) => {
    const r = await job();
    if (!r.success) return setError("저장 실패");
    await load();
    return true;
  };

  return {
    tab,
    error,
    menu,
    // 이 메뉴에 아직 없는 재료
    free: tab?.ingredients.filter((i) => !menu?.lines.some((l) => l.ingredientId === i.id)) ?? [],
    adding,
    opened,
    open: (id: string) => {
      setMenuId(id);
      setOpened(true);
    },
    back: () => setOpened(false),
    openAdd: () => setAdding(true),
    closeAdd: () => setAdding(false),
    edit: (lineId: string, qty: number) => save(() => updateRecipeLine(lineId, qty)),
    remove: (lineId: string) => save(() => deleteRecipeLine(lineId)),
    add: async (draft: RecipeLineDraft) => {
      if (!menu) return;
      let ingredientId: string;
      if ("ingredientId" in draft) ingredientId = draft.ingredientId;
      else {
        const c = await createIngredient(draft.name, draft.unit);
        if (!c.success) return setError("저장 실패");
        ingredientId = c.data.id;
      }
      if (await save(() => createRecipeLine(menu.id, ingredientId, draft.qty))) setAdding(false);
    },
  };
}
