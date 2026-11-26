import type { Page } from "@playwright/test";
const TODAY = "2026-11-12";
export async function mock(
  page: Page,
  weekly: (number | null)[] = [4, null, 4.2, 4.1, 4, 4.3, 4.4, 5],
  longLabels = false,
) {
  await page.route("**/*", (route) => {
    const u = new URL(route.request().url());
    return u.hostname === "localhost" &&
      [process.env.UI_FEEDBACK_PORT ?? "3200", "8080"].includes(u.port)
      ? route.fallback()
      : route.abort();
  });
  await page.clock.setFixedTime(new Date("2027-01-01T12:00:00+09:00"));
  const ingredients = ["재료A", "재료B"].map((name, i) => ({
    id: `i${i}`,
    name:
      longLabels && i === 0
        ? "매우 긴 재료 이름이 줄바꿈되어도 양쪽 품목 줄이 맞아야 하는 재료"
        : name,
    unit: "G",
    kind: "BOM",
    unitStatus: "CONFIRMED",
    storageType: "CHILLED",
    storageLocation: "FRIDGE",
    safetyDays: 2,
    maxOrderQty: 20000,
    needsReview: false,
    reviewReasons: [],
    category: { id: "other", code: "OTHER", name: "기타" },
  }));
  await page.route("http://localhost:8080/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    let data: unknown;
    if (path === "/stores/me")
      data = {
        id: "local-price",
        today: TODAY,
        name: "가격 검증",
        address: "서울특별시 송파구 석촌호수로12길 22",
        displayName: "가격 검증",
        priceCompare: !longLabels,
        remainingSteps: 0,
        checklist: [{ step: "ONBOARDING", done: true, remaining: 0 }],
      };
    else if (path === "/suppliers")
      data = [
        {
          id: "s",
          name: longLabels ? "매우 긴 이름으로 등록한 지역 식자재 전문 도매 거래처" : "기존 거래처",
          channel: "PHONE",
          leadTimeDays: 1,
          orderCycleDays: 1,
          cutoffTime: "21:00",
          deliveryDays: [],
          minOrderAmount: 0,
          messageTemplate: "{items}",
        },
      ];
    else if (path === "/ingredients") data = ingredients;
    else if (path === "/supplier-products")
      data = ingredients.map((i) => ({
        id: `p${i.id}`,
        ingredientId: i.id,
        supplierId: "s",
        productName: "거래처 1kg",
        baseQtyPerPack: 1000,
        packUnit: "팩",
        price: 5000,
        primary: true,
      }));
    else if (path === "/consumption/daily") data = [];
    else if (path === "/inventory/estimate") data = [];
    else if (path === "/receiving")
      data = {
        incoming: Array.from({ length: 5 }, (_, i) => ({
          orderId: "po",
          itemId: `incoming${i}`,
          ingredientId: "i0",
          name: `입고 재료${i}`,
          supplier: "검증 거래처",
          qty: 1000,
          unit: "g",
          today: true,
          expectedDate: TODAY,
        })),
        history: [],
        firstReceiptDone: true,
      };
    else if (path === "/stores/me/demo-preparation") data = { eligible: false };
    else if (path === "/receipts")
      data = {
        jobId: null,
        files: [],
        matches: [],
        options: [{ id: "i0", name: "재료A" }],
        readyDocs: [],
      };
    else if (path === "/menus")
      data = [
        { id: "m0", name: "미확인 메뉴", price: 8000, needsReview: true },
        { id: "m1", name: "확인 메뉴", price: 8000, needsReview: false },
      ];
    else if (path === "/recipes") {
      await new Promise((resolve) => setTimeout(resolve, 700));
      data = [0, 1].map((i) => ({
        id: `recipe${i}`,
        menuId: `m${i}`,
        ingredientId: "i0",
        ingredientName: "재료A",
        qtyPerServing: 50,
        unit: "G",
        estimated: i === 0,
      }));
    } else if (path === "/sales-forecast/this-week")
      data = { pct: null, reason: null, range: "", days: [], factors: [], analysis: null };
    else if (path === "/recommendations/run")
      data = {
        runDate: TODAY,
        createdCount: 0,
        existingCount: 2,
        noOrderCount: 0,
        recommendations: ingredients.map((i, k) => ({
          id: `r${k}`,
          ingredientId: i.id,
          ingredientName: i.name,
          unit: "G",
          runDate: TODAY,
          estStock: 0,
          onOrder: 0,
          leadTimeDemand: 0,
          safetyStock: 0,
          reorderPoint: 0,
          recommendedQty: 3000,
          priceRatio: null,
          priceLevel: null,
          reason: "",
          status: "PENDING",
          finalQty: null,
          runoutDate: null,
          supplierProductId: `p${i.id}`,
          needsReview: false,
          reviewReasons: [],
        })),
      };
    else if (path.endsWith("/price-history")) data = { ingredientId: "i0", unit: "G", weekly };
    else if (path.endsWith("/offers"))
      data = {
        toIngredientUnitFactor: 1,
        sites: [
          ["쿠팡", 3, 1000, TODAY],
          ["식봄", 4, 2000, TODAY],
          ["마켓봄", 2, 1000, "2026-11-11"],
          ["오더링크", 3.5, 2000, TODAY],
        ].map(([site, unitPrice, pack, date]) => ({
          site,
          bestUnitPrice: unitPrice,
          collectedOn: date,
          delivery: "AVAILABLE",
          synthetic: true,
          offers: [
            {
              url:
                site === "식봄"
                  ? "https://www.foodspring.co.kr/goods/detail/550224"
                  : "https://example.invalid/demo",
              productName: `시연 ${Number(pack) / 1000}kg`,
              spec: `${Number(pack) / 1000}kg`,
              price: Number(unitPrice) * Number(pack),
              unitPrice,
            },
          ],
        })),
      };
    else if (path.endsWith("/market-price-history"))
      data = {
        toIngredientUnitFactor: 1,
        from: "2026-09-18",
        to: TODAY,
        sites: ["쿠팡", "식봄", "마켓봄", "오더링크"].map((site, k) => ({
          site,
          delivery: "AVAILABLE",
          synthetic: true,
          points: [
            { date: "2026-11-05", unitPrice: 4 + k * 0.1 },
            { date: TODAY, unitPrice: 3 + k * 0.1 },
          ],
        })),
      };
    else throw new Error(`Unexpected ${path}`);
    await route.fulfill({ json: { success: true, data, error: null } });
  });
}
