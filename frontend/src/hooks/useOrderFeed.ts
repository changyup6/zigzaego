"use client";

import { useEffect, useState } from "react";
import {
  getDailyConsumption,
  getIngredientsFull,
  getInventory,
  getMarketPriceHistory,
  getOffers,
  getPriceHistory,
  getReceiving,
  getStoreMe,
  getSupplierProducts,
  getSuppliers,
  runRecommendations,
} from "@/lib/api";
import { DEMO_ORDER_FEED, isDemo } from "@/lib/mock/orderFeed";
import { toOrderFeed } from "@/lib/orderFeed";
import { toIngredientRows } from "@/lib/suppliers";
import type {
  IngredientOffersApi,
  MarketPriceHistoryApi,
  OrderFeed,
  PriceHistoryApi,
} from "@/lib/types";
import { lastWeek } from "./useSuppliers";

// 발주 탭: 오늘 추천(실행이 끝났으면 기존 것, #59) · 거래처 · 상품 · 재료 · 최근 7일 소비 · 추정 재고 · 발주 기록,
// 그다음 오늘 주문 재료의 8주 단가, 가격 비교를 켰으면 식자재몰 최저가 · 8주 일별 최저가 (#48)
export function useOrderFeed() {
  const [feed, setFeed] = useState<OrderFeed | null>(null);
  const [error, setError] = useState(false);
  useEffect(() => {
    let live = true;
    (async () => {
      if (isDemo()) return setFeed(DEMO_ORDER_FEED);
      const dates = lastWeek();
      const [r, s, i, p, c, e, v, me] = await Promise.all([
        runRecommendations(),
        getSuppliers(),
        getIngredientsFull(),
        getSupplierProducts(),
        getDailyConsumption(dates[0], dates[6]),
        getInventory(),
        getReceiving(),
        getStoreMe(),
      ]);
      if (!live) return;
      if (
        !r.success ||
        !s.success ||
        !i.success ||
        !p.success ||
        !c.success ||
        !e.success ||
        !v.success
      )
        return setError(true);
      const pending = r.data.recommendations.filter((x) => x.status === "PENDING");
      const compare = me.data?.priceCompare ?? true;
      const ids = pending.map((x) => x.ingredientId);
      const [ph, of, mh] = await Promise.all([
        Promise.all(ids.map(getPriceHistory)),
        compare ? Promise.all(ids.map(getOffers)) : [],
        compare ? Promise.all(ids.map((id) => getMarketPriceHistory(id, 56))) : [],
      ]);
      if (!live) return;
      const prices: Record<string, PriceHistoryApi> = {};
      const offers: Record<string, IngredientOffersApi> = {};
      const market: Record<string, MarketPriceHistoryApi> = {};
      ids.forEach((id, k) => {
        const [a, b, m] = [ph[k], of[k], mh[k]];
        if (a.success) prices[id] = a.data;
        if (b?.success) offers[id] = b.data;
        if (m?.success) market[id] = m.data;
      });
      setFeed(
        toOrderFeed({
          today: r.data.runDate,
          storeId: me.data?.id,
          recs: r.data.recommendations,
          suppliers: s.data,
          rows: toIngredientRows(i.data, p.data, c.data, dates),
          estimates: e.data,
          history: v.data.history,
          prices,
          offers,
          market,
        }),
      );
    })();
    return () => {
      live = false;
    };
  }, []);
  return { feed, error };
}
