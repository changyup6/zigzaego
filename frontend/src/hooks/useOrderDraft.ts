"use client";
import { useSyncExternalStore } from "react";
import { draftKey } from "@/lib/orderDraft";
import { approveAll, initOrders, setQty, toggleApprove, type Orders } from "@/lib/orderHome";
import type { OrderFeed } from "@/lib/types";

type Draft = { orders: Orders; src: Record<string, string> };
const drafts = new Map<string, Draft>();
const listeners = new Set<() => void>();
const subscribe = (fn: () => void) => {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
};
// 가게·일자·추천 id가 같을 때만 탭 사이에서 수량과 주문처를 공유한다.
export function useOrderDraft(feed: OrderFeed, storeId?: string) {
  const lines = feed.groups.flatMap((g) => g.lines);
  const key = draftKey(
    feed.storeId ?? storeId ?? "",
    feed.today,
    lines.map((l) => l.id),
  );
  if (!drafts.has(key)) drafts.set(key, { orders: initOrders(lines), src: {} });
  const get = () => drafts.get(key)!;
  const value = useSyncExternalStore(subscribe, get, get);
  const change = (fn: (d: Draft) => Draft) => {
    drafts.set(key, fn(get()));
    listeners.forEach((f) => f());
  };
  return {
    ...value,
    approve: (id: string) => change((d) => ({ ...d, orders: toggleApprove(d.orders, id) })),
    changeQty: (id: string, q: number) =>
      change((d) => ({ ...d, orders: setQty(d.orders, id, q) })),
    fillAll: () =>
      change((d) => ({
        ...d,
        orders: approveAll(
          d.orders,
          lines.map((l) => l.id),
        ),
      })),
    setSrc: (fn: (src: Record<string, string>) => Record<string, string>) =>
      change((d) => ({ ...d, src: fn(d.src) })),
  };
}
