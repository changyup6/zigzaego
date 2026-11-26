"use client";

import { useEffect, useRef, useState } from "react";
import { getClosingItems, getIngredients, parseClosingText, saveClosingCheck } from "@/lib/api";
import { canSave, startValue, toBody, type ClosingItem, type ParsedLine } from "@/lib/closingCheck";
import { DEMO_CLOSING_ITEMS, DEMO_INGREDIENTS, demoParse } from "@/lib/mock/closingCheck";
import { isDemo } from "@/lib/mock/orderFeed";
import { ok } from "@/lib/mock/ok";
import type { ApiError, IngredientApi } from "@/lib/types";

export type Line = ParsedLine & { key: number };

// 마감 확인 화면 (DECISIONS #88): 확인 품목 · 자유 입력 해석 줄 · 저장
export function useClosingCheck() {
  const [items, setItems] = useState<ClosingItem[] | null>(null);
  const [ingredients, setIngredients] = useState<IngredientApi[]>([]);
  const [values, setValues] = useState<Record<string, number>>({});
  const [lines, setLines] = useState<Line[]>([]);
  const [source, setSource] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const nextKey = useRef(0);

  useEffect(() => {
    let live = true;
    (async () => {
      const [i, g] = isDemo()
        ? [ok(DEMO_CLOSING_ITEMS), ok(DEMO_INGREDIENTS)]
        : await Promise.all([getClosingItems(), getIngredients()]);
      if (!live) return;
      if (!i.success) return setError(i.error);
      setItems(i.data);
      if (g.success) setIngredients(g.data);
    })();
    return () => {
      live = false;
    };
  }, []);

  // 해석 결과는 저장 전까지 화면에만 둔다. 여러 번 보내면 아래에 이어 붙인다
  async function parse(text: string) {
    setBusy(true);
    setError(null);
    const r = isDemo() ? ok(demoParse(text)) : await parseClosingText(text);
    setBusy(false);
    if (!r.success) {
      setError(r.error);
      return false;
    }
    setLines((prev) => [...prev, ...r.data.lines.map((l) => ({ ...l, key: nextKey.current++ }))]);
    setSource((prev) => (prev ? `${prev}\n${r.data.text}` : r.data.text));
    return true;
  }

  async function save() {
    if (!items) return false;
    setBusy(true);
    setError(null);
    const body = toBody(items, values, lines, source);
    const r = isDemo() ? ok(null) : await saveClosingCheck(body);
    setBusy(false);
    if (!r.success) setError(r.error);
    return r.success;
  }

  return {
    items,
    ingredients,
    lines,
    busy,
    error,
    ready: items !== null && canSave(items, lines),
    value: (i: ClosingItem) => values[i.ingredientId] ?? startValue(i),
    setValue: (id: string, v: number) => setValues((prev) => ({ ...prev, [id]: v })),
    setLine: (key: number, patch: Partial<ParsedLine>) =>
      setLines((prev) => prev.map((l) => (l.key === key ? { ...l, ...patch } : l))),
    removeLine: (key: number) => setLines((prev) => prev.filter((l) => l.key !== key)),
    parse,
    save,
  };
}
