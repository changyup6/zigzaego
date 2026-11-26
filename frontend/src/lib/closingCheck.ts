import type { ApiUnit } from "./types";

// 마감 확인 (DECISIONS #88). 수량은 포장 단위로 묻고 기준 단위(g · ml · 개)로 저장한다
export type ClosingReason = "DISCARD" | "SPOILED" | "DAMAGED" | "SPILLED" | "OTHER";

export const REASON_LABEL: Record<ClosingReason, string> = {
  DISCARD: "폐기",
  SPOILED: "상함",
  DAMAGED: "파손",
  SPILLED: "쏟음",
  OTHER: "기타",
};

// GET /closing-check/items. 포장 환산을 모르면 packUnit · baseQtyPerPack · estimatedPacks 가 null
export type ClosingItem = {
  ingredientId: string;
  name: string;
  estimatedQty: number;
  baseUnit: ApiUnit;
  packUnit: string | null;
  baseQtyPerPack: number | null;
  estimatedPacks: number | null;
  step: number;
};

// POST /closing-check/parse 의 한 줄. 재료를 못 찾으면 ingredientId, 단위를 모르면 baseQty 가 null
export type ParsedLine = {
  ingredientId: string | null;
  ingredientName: string;
  // COUNT = 남은 양(실사), DISCARD = 버린 양(빼기)
  action: "COUNT" | "DISCARD";
  qty: number;
  unit: string;
  baseQty: number | null;
  reason: ClosingReason;
};

export type ClosingParse = { text: string; lines: ParsedLine[] };

// POST /closing-check
export type ClosingBody = {
  counts: {
    ingredientId: string;
    qty: number;
    estimatedQty?: number;
    method: "CONFIRMED" | "CORRECTED";
  }[];
  adjustments: { ingredientId: string; qty: number; reason: ClosingReason }[];
  sourceText?: string;
};

const packed = (i: ClosingItem) => i.packUnit !== null && i.baseQtyPerPack !== null;

// 화면에 처음 보이는 값: 포장 단위가 있으면 포장 수, 없으면 기준 단위 양
export const startValue = (i: ClosingItem) =>
  packed(i) ? (i.estimatedPacks ?? 0) : Math.round(i.estimatedQty);

export const toBase = (i: ClosingItem, value: number) =>
  packed(i) ? value * (i.baseQtyPerPack as number) : value;

// 재료와 기준 단위 양이 다 정해진 줄만 저장할 수 있다
export const lineReady = (l: ParsedLine) =>
  l.ingredientId !== null && l.baseQty !== null && l.baseQty > 0;

// 저장할 것이 있고, 고치지 않은 줄이 없어야 저장
export const canSave = (items: ClosingItem[], lines: ParsedLine[]) =>
  items.length + lines.length > 0 && lines.every(lineReady);

// 손대지 않은 값은 CONFIRMED, 바꾼 값은 CORRECTED. 말로 한 남은 양은 같은 재료의 확인 값을 덮는다
export function toBody(
  items: ClosingItem[],
  values: Record<string, number>,
  lines: ParsedLine[],
  sourceText: string,
): ClosingBody {
  const counts = new Map<string, ClosingBody["counts"][number]>();
  for (const i of items) {
    const v = values[i.ingredientId] ?? startValue(i);
    counts.set(i.ingredientId, {
      ingredientId: i.ingredientId,
      qty: toBase(i, v),
      estimatedQty: i.estimatedQty,
      method: v === startValue(i) ? "CONFIRMED" : "CORRECTED",
    });
  }
  const ready = lines.filter(lineReady) as (ParsedLine & {
    ingredientId: string;
    baseQty: number;
  })[];
  for (const l of ready)
    if (l.action === "COUNT")
      counts.set(l.ingredientId, {
        ...counts.get(l.ingredientId),
        ingredientId: l.ingredientId,
        qty: l.baseQty,
        method: "CORRECTED",
      });
  return {
    counts: [...counts.values()],
    adjustments: ready
      .filter((l) => l.action === "DISCARD")
      .map((l) => ({ ingredientId: l.ingredientId, qty: l.baseQty, reason: l.reason })),
    ...(lines.length > 0 && sourceText ? { sourceText } : {}),
  };
}
