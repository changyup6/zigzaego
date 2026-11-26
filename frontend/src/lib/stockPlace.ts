import type { IngredientKind, StorageType } from "./types";

// 재료 탭 그림 (S15P31A308-270): 냉장고 냉동칸 · 냉장칸 · 선반 · 창고. 값은 백엔드 storage_location 그대로 (S15P31A308-268)
export type Place = "FREEZER" | "FRIDGE" | "SHELF" | "WAREHOUSE";
export const PLACES: Place[] = ["FREEZER", "FRIDGE", "SHELF", "WAREHOUSE"];
export const PLACE_LABEL: Record<Place, string> = {
  FREEZER: "냉동",
  FRIDGE: "냉장",
  SHELF: "선반",
  WAREHOUSE: "창고",
};

// 보관 위치를 아직 받지 못하면(백엔드 반영 전) 보관 방식 · 종류로 정한다. 백엔드 기본값과 같은 규칙
export const defaultPlace = (storage: StorageType | null, kind: IngredientKind): Place =>
  kind === "CYCLE" || !storage
    ? "WAREHOUSE"
    : storage === "FROZEN"
      ? "FREEZER"
      : storage === "AMBIENT"
        ? "SHELF"
        : "FRIDGE";

// 프론트 쪽 넘김. 쪽 번호가 넘치면 마지막 쪽으로
export function pageOf<T>(list: T[], size: number, page: number) {
  const pages = Math.max(1, Math.ceil(list.length / Math.max(1, size)));
  const p = Math.min(Math.max(0, page), pages - 1);
  return { items: list.slice(p * size, (p + 1) * size), page: p, pages };
}

// 번호 쪽 넘김에 보일 번호 (0부터). 지금 쪽이 가운데 오게 최대 n개
export function pageWindow(page: number, pages: number, n = 5): number[] {
  const from = Math.max(0, Math.min(page - Math.floor(n / 2), pages - n));
  return Array.from({ length: Math.min(n, pages) }, (_, k) => from + k);
}

// 폭에 들어가는 칸 수 (최소 칸 폭 · 칸 사이)
export const colsFor = (width: number, min: number, gap: number) =>
  Math.max(1, Math.floor((width + gap) / (min + gap)));

// 거래처 모달 "시켰던 재료"의 가장 최근 주문일 (재료 이름 → YYYY-MM-DD). 취소한 줄은 빼고, 이 거래처 줄만
export function lastOrderDates(
  lines: { date: string; ingredientName: string; supplierName: string; status: string }[],
  supplier: string,
): Record<string, string> {
  const out: Record<string, string> = {};
  for (const l of lines)
    if (
      l.supplierName === supplier &&
      l.status !== "CANCELLED" &&
      !(out[l.ingredientName] >= l.date)
    )
      out[l.ingredientName] = l.date;
  return out;
}
