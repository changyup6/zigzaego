import { fmt } from "./format.ts";
import type { ApiUnit, RecipeUnit } from "./types";

// DB 는 g · ml · 개 (#33 · #42), 화면은 1,000 이상이면 kg · L
export const FROM_API: Record<ApiUnit, RecipeUnit> = { G: "g", ML: "ml", EA: "개" };

// unit 은 API 코드(G · ML · EA)나 화면 표기(g · ml · 개) 둘 다 받는다
export function shownQty(qty: number | string | null | undefined, unit: ApiUnit | RecipeUnit) {
  const u = unit in FROM_API ? FROM_API[unit as ApiUnit] : (unit as RecipeUnit);
  const n = Number(qty ?? 0);
  if (u === "개" || n < 1000) return { n: fmt(n), u };
  return { n: fmt(n / 1000), u: u === "g" ? "kg" : "L" };
}

// 추정 재고 표시 (S15P31A308-277): 개는 내림해 정수로, g · ml 은 fmt 그대로. 1e-6 은 2.9999999 같은 부동소수 오차를 3 으로
export const fmtStock = (n: number, unit: ApiUnit | RecipeUnit) =>
  unit === "개" || unit === "EA" ? fmt(Math.floor(n + 1e-6)) : fmt(n);
