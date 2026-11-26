import type { ClosingItem, ClosingParse } from "@/lib/closingCheck";
import type { IngredientApi } from "@/lib/types";

// ponytail: 마감 확인 API(S15P31A308-249 · 251 · 252)가 스테이징에 올라오면 이 파일과 useClosingCheck 의 ?demo 분기를 지운다
export const DEMO_CLOSING_ITEMS: ClosingItem[] = [
  {
    ingredientId: "i1",
    name: "양파",
    estimatedQty: 1450,
    baseUnit: "G",
    packUnit: "망",
    baseQtyPerPack: 1000,
    estimatedPacks: 1.5,
    step: 0.5,
  },
  {
    ingredientId: "i2",
    name: "우유",
    estimatedQty: 3000,
    baseUnit: "ML",
    packUnit: "팩",
    baseQtyPerPack: 1000,
    estimatedPacks: 3,
    step: 0.5,
  },
  {
    ingredientId: "i3",
    name: "달걀",
    estimatedQty: 42,
    baseUnit: "EA",
    packUnit: null,
    baseQtyPerPack: null,
    estimatedPacks: null,
    step: 1,
  },
];

export const DEMO_INGREDIENTS: IngredientApi[] = [
  { id: "i1", name: "양파", unit: "G", kind: "BOM" },
  { id: "i2", name: "우유", unit: "ML", kind: "BOM" },
  { id: "i3", name: "달걀", unit: "EA", kind: "BOM" },
  { id: "i4", name: "대파", unit: "G", kind: "BOM" },
  { id: "i5", name: "간장", unit: "ML", kind: "BOM" },
];

// 어떤 문장이든 같은 세 줄: 맞게 읽은 줄 · 단위를 모르는 줄 · 재료를 못 찾은 줄
export const demoParse = (text: string): ClosingParse => ({
  text,
  lines: [
    {
      ingredientId: "i1",
      ingredientName: "양파",
      action: "DISCARD",
      qty: 0.5,
      unit: "망",
      baseQty: 500,
      reason: "DISCARD",
    },
    {
      ingredientId: "i5",
      ingredientName: "간장",
      action: "DISCARD",
      qty: 1,
      unit: "국자",
      baseQty: null,
      reason: "SPILLED",
    },
    {
      ingredientId: null,
      ingredientName: "쪽파",
      action: "DISCARD",
      qty: 200,
      unit: "g",
      baseQty: null,
      reason: "SPOILED",
    },
  ],
});
