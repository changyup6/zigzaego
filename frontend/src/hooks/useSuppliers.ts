"use client";

import { useCallback, useEffect, useState } from "react";
import {
  createSupplier,
  createSupplierProduct,
  deleteSupplier,
  getDailyConsumption,
  getInventory,
  getIngredientsFull,
  getSupplierProducts,
  getSuppliers,
  postStockCounts,
  updateIngredient,
  updateSupplier,
  updateSupplierProduct,
} from "@/lib/api";
import {
  ingredientBody,
  productPlan,
  supplierBody,
  toIngredientRows,
  unitFactor,
  withStock,
  toSupplier,
} from "@/lib/suppliers";
import type { Place } from "@/lib/stockPlace";
import { todayYmd } from "@/lib/today";
import type {
  ApiResponse,
  IngredientEdit,
  IngredientRow,
  SupplierEdit,
  SupplierProductApi,
  SuppliersScreen,
} from "@/lib/types";

// 오늘 앞 7일 (YYYY-MM-DD, 오래된 날부터)
export const lastWeek = () => {
  const base = Date.parse(todayYmd());
  const day = (back: number) => new Date(base - back * 86_400_000).toISOString().slice(0, 10);
  return Array.from({ length: 7 }, (_, i) => day(7 - i));
};

// 거래처 및 재료 탭. GET /suppliers · /supplier-products · /ingredients · /consumption/daily.
// 고치면 저장하고 목록을 다시 받아 새로고침과 같은 화면을 보인다.
export function useSuppliers() {
  const [screen, setScreen] = useState<SuppliersScreen | null>(null);
  const [products, setProducts] = useState<SupplierProductApi[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    const dates = lastWeek();
    const [s, p, i, c, e] = await Promise.all([
      getSuppliers(),
      getSupplierProducts(),
      getIngredientsFull(),
      getDailyConsumption(dates[0], dates[6]),
      getInventory(),
    ]);
    if (!s.success || !p.success || !i.success || !c.success || !e.success)
      return setError("불러오기 실패");
    setProducts(p.data);
    setScreen({
      suppliers: s.data.map(toSupplier),
      ingredients: withStock(toIngredientRows(i.data, p.data, c.data, dates), e.data),
    });
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

  const save = async (...jobs: (() => Promise<ApiResponse<unknown>>)[]) => {
    let ok = true;
    for (const job of jobs) {
      const r = await job();
      if (!r.success) {
        ok = false;
        break;
      }
    }
    await load();
    if (!ok) setError("저장 실패");
    return ok;
  };

  // 끌어 놓으면 바로 바꿔 보이고 저장한다. 실패하면 되돌린다 (S15P31A308-269)
  const moveIngredient = async (id: string, place: Place) => {
    const before = screen?.ingredients.find((i) => i.id === id)?.place;
    const put = (p: Place) =>
      setScreen(
        (sc) =>
          sc && {
            ...sc,
            ingredients: sc.ingredients.map((i) => (i.id === id ? { ...i, place: p } : i)),
          },
      );
    put(place);
    const r = await updateIngredient(id, { storageLocation: place });
    if (!r.success && before) put(before);
    return r.success;
  };

  return {
    screen,
    products,
    error,
    moveIngredient,
    // 재고 직접 입력 (화면 단위 → 기준 단위)
    countStock: (row: IngredientRow, qty: number) =>
      save(() => postStockCounts([{ ingredientId: row.id, qty: qty * unitFactor(row.baseUnit) }])),
    addSupplier: (e: SupplierEdit) => save(() => createSupplier(supplierBody(e))),
    editSupplier: (id: string, e: SupplierEdit) => save(() => updateSupplier(id, supplierBody(e))),
    removeSupplier: (id: string) => save(() => deleteSupplier(id)),
    editIngredient: (row: IngredientRow, e: IngredientEdit) => {
      const body = ingredientBody(row, e);
      const plan = productPlan(row, e, products);
      return save(
        ...(Object.keys(body).length ? [() => updateIngredient(row.id, body)] : []),
        ...(plan.op === "patch" && Object.keys(plan.body).length
          ? [() => updateSupplierProduct(plan.id, plan.body)]
          : []),
        ...(plan.op === "create" ? [() => createSupplierProduct(plan.body)] : []),
      );
    },
  };
}
