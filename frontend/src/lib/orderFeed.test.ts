import assert from "node:assert/strict";
import { test } from "node:test";
import { addDays, arriveLabel, sikbomPrice, sikbomWeeks, toOrderFeed } from "./orderFeed.ts";
import type { IngredientRow, RecommendationApi, SupplierApi } from "./types";

test("도착 글자: 오늘 · 내일 · 요일", () => {
  assert.equal(arriveLabel("2026-10-30", 0), "오늘");
  assert.equal(arriveLabel("2026-10-30", 1), "내일");
  assert.equal(arriveLabel("2026-10-30", 2), "일요일");
  assert.equal(addDays("2026-10-31", 1), "2026-11-01");
});

test("추천 PENDING 줄만 거래처별로 묶고 화면 단위로 바꾼다", () => {
  const rec = (id: string, ing: string, status: RecommendationApi["status"]) =>
    ({
      id,
      ingredientId: ing,
      ingredientName: `재료${ing}`,
      unit: "G",
      runDate: "2026-10-30",
      estStock: "2000.000",
      onOrder: 0,
      leadTimeDemand: 0,
      safetyStock: "500",
      reorderPoint: "1500",
      recommendedQty: "12000.000",
      priceRatio: null,
      priceLevel: null,
      reason: "",
      status,
      finalQty: null,
      runoutDate: null,
      supplierProductId: null,
      needsReview: false,
      reviewReasons: [],
    }) satisfies RecommendationApi;
  const row = {
    id: "1",
    baseUnit: "g",
    supplierId: "s1",
    packLabel: "2kg 팩",
    packQty: 2,
    unitPrice: 9800,
    maxQty: 30,
    daily: [1, 1, 1, 1, 1, 1, 1],
  } as IngredientRow;
  const sup = {
    id: "s1",
    name: "미트",
    channel: "KAKAO",
    leadTimeDays: 1,
    cutoffTime: "15:00:00",
  } as SupplierApi;
  const feed = toOrderFeed({
    today: "2026-10-30",
    recs: [rec("10", "1", "PENDING"), rec("11", "2", "APPROVED")],
    suppliers: [sup],
    rows: [row],
    estimates: [],
    history: [],
    prices: {},
  });
  assert.equal(feed.groups.length, 1);
  const l = feed.groups[0].lines[0];
  assert.deepEqual(
    [l.id, l.qty, l.stock, l.unit, l.pack, l.arriveLabel],
    ["10", 12, 2, "kg", 2, "내일"],
  );
  assert.equal(feed.groups[0].cutoff, "15:00");
  assert.deepEqual(
    feed.calendar["2026-10-30"].map((e) => e.lineId),
    ["10"],
  );
});

test("식봄 최저가: 단가 × 계수 × 화면 단위, 오늘 수집이 아니면 수집일", () => {
  const o = {
    toIngredientUnitFactor: "0.0166667",
    sites: [
      { site: "마켓컬리", bestUnitPrice: 100, collectedOn: "2026-11-04", offers: [] },
      { site: "식봄", bestUnitPrice: "189.7", collectedOn: "2026-10-31", offers: [{ url: "u" }] },
    ],
  };
  // 계란 189.7원/개 → 1/60 → 원/g, 화면 kg 이면 × 1000
  assert.deepEqual(sikbomPrice(o, 1000, "2026-11-04"), {
    price: 3162,
    stale: true,
    on: "10/31",
    url: "u",
  });
  assert.equal(sikbomPrice(o, 1000, "2026-10-31").stale, false);
  assert.equal(sikbomPrice({ ...o, toIngredientUnitFactor: null }, 1000, "2026-11-04").price, null);
  assert.equal(sikbomPrice(undefined, 1000, "2026-11-04").price, null);
});

test("식봄 8주 칸: 7일 칸마다 최저값, 마지막 칸 = 오늘 − 6일 ~ 오늘", () => {
  const h = {
    toIngredientUnitFactor: 1,
    sites: [
      {
        site: "식봄",
        points: [
          { date: "2026-11-04", unitPrice: 5 },
          { date: "2026-10-29", unitPrice: 3 },
          { date: "2026-10-28", unitPrice: 9 },
          { date: "2026-09-01", unitPrice: 1 },
        ],
      },
    ],
  };
  assert.deepEqual(sikbomWeeks(h, 1, "2026-11-04"), [null, null, null, null, null, null, 9, 3]);
  assert.equal(sikbomWeeks({ ...h, sites: [] }, 1, "2026-11-04"), undefined);
});
