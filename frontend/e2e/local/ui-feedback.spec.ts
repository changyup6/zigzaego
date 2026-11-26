import { test, expect, type Page } from "@playwright/test";

const TODAY = "2026-11-15";
const places = ["FREEZER", "FRIDGE", "SHELF", "WAREHOUSE"];
const categories = [
  { id: "meat", code: "MEAT", name: "육류" },
  { id: "veg", code: "VEGETABLE", name: "채소" },
  { id: "other", code: "OTHER", name: "기타" },
];

async function mockApi(
  page: Page,
  weekly: number[] = [10, 10, 11, 11, 12, 12, 12, 13],
  unit = "EA",
  storeToday = TODAY,
) {
  await page.route("**/*", (route) => {
    const url = new URL(route.request().url());
    return url.hostname === "localhost" &&
      [
        process.env.UI_FEEDBACK_BASE_URL ? "3000" : (process.env.UI_FEEDBACK_PORT ?? "3100"),
        "8080",
      ].includes(url.port)
      ? route.fallback()
      : route.abort();
  });
  await page.clock.setFixedTime(new Date("2026-11-15T12:00:00+09:00"));
  const ingredients = places.flatMap((storageLocation, k) =>
    Array.from({ length: 18 }, (_, j) => ({
      id: `i${k}-${j}`,
      name:
        (k === 3 && j === 17) || (k === 1 && j === 2)
          ? `검증 재료 긴 상품 이름과 포장 기준을 모두 가진 보관 재료 ${k}-${j}`
          : `검증 재료 ${k}-${j}`,
      unit,
      kind: "BOM",
      unitStatus: "CONFIRMED",
      storageType: "CHILLED",
      safetyDays: 2,
      maxOrderQty: 10000,
      needsReview: false,
      reviewReasons: [],
      storageLocation,
      category: categories[Math.floor(j / 6)],
    })),
  );
  const moves: unknown[] = [];
  await page.route("http://localhost:8080/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    let data: unknown;
    if (path === "/stores/me")
      data = {
        id: "local",
        today: storeToday,
        name: "로컬 UI 검증",
        displayName: "로컬 UI 검증",
        sigungu: null,
        priceCompare: true,
        remainingSteps: 0,
        checklist: [{ step: "ONBOARDING", done: true, remaining: 0 }],
      };
    else if (path === "/suppliers")
      data = [
        {
          id: "s1",
          name: "검증 거래처",
          channel: "PHONE",
          leadTimeDays: 1,
          orderCycleDays: 1,
          cutoffTime: "21:00",
          deliveryDays: [],
          minOrderAmount: 0,
          messageTemplate: "{items}",
        },
        {
          id: "s2",
          name: "매우 긴 이름으로 등록한 지역 식자재 전문 도매 거래처",
          channel: "PHONE",
          leadTimeDays: 1,
          orderCycleDays: 1,
          cutoffTime: "21:00",
          deliveryDays: [],
          minOrderAmount: 0,
          messageTemplate: "{items}",
        },
      ];
    else if (path === "/supplier-products")
      data = ingredients.map((i) => ({
        id: `p${i.id}`,
        supplierId: ["i3-17", "i1-2"].includes(i.id) ? "s2" : "s1",
        ingredientId: i.id,
        productName: "실제 응답 상품 24개입",
        baseQtyPerPack: unit === "EA" ? 24 : 24000,
        packUnit: "상자",
        price: ["i3-17", "i1-2"].includes(i.id) ? 1234567890 : unit === "EA" ? 312 : 312000,
        primary: true,
      }));
    else if (path === "/ingredients") data = ingredients;
    else if (path.startsWith("/ingredients/") && route.request().method() === "PATCH") {
      const body = route.request().postDataJSON();
      moves.push(body);
      data = Object.assign(
        ingredients.find((i) => i.id === path.split("/")[2])!,
        body,
      );
    } else if (path === "/inventory/estimate")
      data = ingredients.map((i) => ({
        ingredientId: i.id,
        ingredientName: i.name,
        unit,
        estimatedQty: 1234.9,
        needsCount: false,
        runOutDate: null,
        lastCountedAt: null,
      }));
    else if (path === "/consumption/daily") data = [];
    else if (path === "/sales-forecast/this-week")
      data = {
        pct: 1,
        reason: "저장된 모델 결과",
        range: "11월 9일 ~ 15일",
        days: [
          ...[
            ["2026-11-12", "목", 100],
            ["2026-11-13", "금", 100],
            ["2026-11-14", "토", 102],
            ["2026-11-15", "일", 104],
          ].map(([date, label, value]) => ({
            label: date === storeToday ? "오늘" : label,
            value,
            today: date === storeToday,
          })),
        ],
        factors: [{ name: "행사", note: "모델 근거", pct: 1 }],
        analysis: "시연용 실제 API 계약입니다. 주문해 두는 걸 권해요.",
      };
    else if (path === "/receiving") data = { incoming: [], history: [], firstReceiptDone: true };
    else if (path === "/recommendations/run")
      data = {
        runDate: TODAY,
        createdCount: 0,
        existingCount: 1,
        noOrderCount: 0,
        recommendations: [
          {
            id: "r1",
            ingredientId: "i0-0",
            ingredientName: "검증 재료 0-0",
            unit,
            runDate: TODAY,
            estStock: 1234.9,
            onOrder: 0,
            leadTimeDemand: 0,
            safetyStock: 0,
            reorderPoint: 0,
            recommendedQty: 2400,
            priceRatio: null,
            priceLevel: null,
            reason: "",
            status: "PENDING",
            finalQty: null,
            runoutDate: null,
            supplierProductId: "pi0-0",
            needsReview: false,
            reviewReasons: [],
          },
        ],
      };
    else if (path.endsWith("/price-history")) data = { ingredientId: "i0-0", unit, weekly };
    else if (path.endsWith("/market-price-history"))
      data = {
        toIngredientUnitFactor: 1,
        sites: weekly.length
          ? [{ site: "식봄", delivery: "AVAILABLE", points: [{ date: TODAY, unitPrice: 9 }] }]
          : [],
      };
    else if (path.endsWith("/offers"))
      data = {
        toIngredientUnitFactor: 1,
        sites: [
          {
            site: "식봄",
            delivery: "AVAILABLE",
            bestUnitPrice: 9,
            collectedOn: TODAY,
            offers: [
              {
                url: "https://example.com/local-fixture",
                price: unit === "EA" ? 216 : 216000,
                unitPrice: 9,
                productName: "검증 시장 24개입",
                spec: unit === "EA" ? "24개" : unit === "G" ? "24kg" : "24L",
              },
            ],
          },
        ],
      };
    else throw new Error(`Unhandled local API ${route.request().method()} ${path}`);
    await route.fulfill({ json: { success: true, data, error: null } });
  });
  return moves;
}

test("주차 가격은 키보드와 포인터로 확인하고 포장 기준과 구분한다", async ({ page }) => {
  await mockApi(page);
  await page.goto("/order");
  await page.getByRole("button", { name: "검증 재료 0-0" }).click();
  const week = page.getByRole("button", { name: /9월 넷째 주.*10원/ });
  await expect(week).toBeVisible();
  await week.focus();
  await week.press("ArrowRight");
  await expect(page.getByRole("status", { name: "검증 거래처" })).toContainText("10월 첫째 주");
  await week.focus();
  await expect(page.getByRole("status", { name: "검증 거래처" })).toContainText("9월 넷째 주");
  await expect(page.getByText("빈 구간 · 수집 없음")).toHaveCount(0);
  await page.getByRole("button", { name: /11월 셋째 주.*13원/ }).click();
  await page.getByLabel("가격 추이").getByRole("button", { name: "식봄", exact: true }).click();
  await expect(page.getByRole("status", { name: "식봄" })).toContainText("9원");
  await expect(page.getByText("1상자 = 24개", { exact: false }).first()).toBeVisible();
  await expect(page.getByText("2,400개", { exact: true })).toBeVisible();
});

for (const weekly of [[], [0], [0, 0], [10, 10]]) {
  test(`그래프 경계값 ${JSON.stringify(weekly)}에 깨진 좌표가 없다`, async ({ page }) => {
    await mockApi(page, weekly);
    await page.goto("/order");
    await page.getByRole("button", { name: "검증 재료 0-0" }).click();
    if (weekly.length)
      await expect(page.getByRole("button", { name: /11월 셋째 주.*원/ })).toBeVisible();
    else await expect(page.getByText("단가 이력 없음")).toBeVisible();
    if (weekly.length === 1) {
      await page.getByLabel("가격 추이").getByRole("button", { name: "식봄", exact: true }).click();
      await expect(page.getByRole("status", { name: "식봄" })).toContainText("9원");
    }
    const paths = await page
      .locator("svg path")
      .evaluateAll((els) => els.map((e) => e.getAttribute("d")));
    expect(paths.join(" ")).not.toMatch(/NaN|Infinity/);
  });
}

for (const viewport of [
  { width: 1360, height: 800 },
  { width: 1088, height: 640 },
  { width: 1024, height: 768 },
  { width: 768, height: 1024 },
  { width: 820, height: 600 },
  { width: 375, height: 812 },
]) {
  test(`보관 영역 ${viewport.width}px에서 모든 쪽에 접근 가능`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await mockApi(page);
    await page.goto("/ingredients");
    await expect(page.locator("[data-ing]").first()).toBeVisible();
    if (viewport.width > 760)
      await expect
        .poll(() => page.evaluate(() => document.documentElement.scrollHeight))
        .toBeLessThanOrEqual(viewport.height + 1);
    await page.addStyleTag({ content: "nextjs-portal { visibility: hidden; }" });
    if (process.env.UI_FEEDBACK_ARTIFACT_DIR)
      await page.screenshot({
        path: `${process.env.UI_FEEDBACK_ARTIFACT_DIR}/ingredients-${viewport.width}.png`,
        fullPage: true,
      });
    for (const [k, place] of places.entries()) {
      const zone = page.locator(`[data-drop="${place}"]`);
      const scope =
        place === "SHELF"
          ? zone.locator("xpath=../..")
          : place === "WAREHOUSE"
            ? zone.locator("xpath=../../..")
            : zone;
      const found = new Set<string>();
      for (let n = 0; n < 30; n++) {
        await expect(zone).toBeVisible();
        const accessible = await zone.evaluate((el) => ({
          h: el.clientHeight,
          sh: el.scrollHeight,
          w: el.clientWidth,
          sw: el.scrollWidth,
        }));
        expect(accessible.sh).toBeLessThanOrEqual(accessible.h + 1);
        expect(accessible.sw).toBeLessThanOrEqual(accessible.w + 1);
        for (const id of await zone
          .locator("[data-ing]")
          .evaluateAll((els) => els.map((el) => (el as HTMLElement).dataset.ing!)))
          found.add(id);
        const next = scope.getByRole("button", { name: "다음", exact: true });
        if ((await next.count()) === 0 || (await next.isDisabled())) break;
        await next.click();
      }
      expect([...found].sort()).toEqual(Array.from({ length: 18 }, (_, j) => `i${k}-${j}`).sort());
    }
    await page.getByRole("button", { name: "표", exact: true }).click();
    const tableRows = new Set<string>();
    for (let n = 0; n < 80; n++) {
      if (viewport.width > 760) {
        expect(
          await page.evaluate(() => document.documentElement.scrollHeight),
        ).toBeLessThanOrEqual(viewport.height + 1);
        expect(
          await page.locator("table").evaluate((el) => el.getBoundingClientRect().bottom),
        ).toBeLessThanOrEqual(viewport.height);
      }
      for (const name of await page
        .locator("tbody tr[tabindex] td:first-child b")
        .allTextContents())
        tableRows.add(name);
      const next = page
        .getByRole("navigation", { name: "쪽", exact: true })
        .getByRole("button", { name: "다음", exact: true });
      if (await next.isDisabled()) break;
      await next.click();
    }
    expect(tableRows.size).toBe(72);
    if (process.env.UI_FEEDBACK_ARTIFACT_DIR)
      await page.screenshot({
        path: `${process.env.UI_FEEDBACK_ARTIFACT_DIR}/ingredients-table-${viewport.width}.png`,
        fullPage: true,
      });
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
    ).toBeTruthy();
  });
}

test.describe("모바일 터치 가격 확인", () => {
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });
  test("터치로 고른 주차 가격과 누락을 읽는다", async ({ page }) => {
    await mockApi(page);
    await page.goto("/order");
    await page.getByRole("button", { name: "검증 재료 0-0" }).tap();
    await page.getByRole("button", { name: /9월 넷째 주.*10원/ }).tap();
    await expect(page.getByText("빈 구간 · 수집 없음")).toHaveCount(0);
    await page.getByRole("button", { name: /11월 셋째 주.*13원/ }).tap();
    await page.getByLabel("가격 추이").getByRole("button", { name: "식봄", exact: true }).tap();
    await page.getByRole("button", { name: /11월 셋째 주.*식봄.*9원/ }).tap();
    await expect(page.getByRole("status", { name: "식봄" })).toContainText("9원");
  });
});

test("보관 위치를 바꿔도 재료와 페이지 접근을 유지한다", async ({ page }) => {
  await page.setViewportSize({ width: 1360, height: 800 });
  const moves = await mockApi(page);
  await page.goto("/ingredients");
  const source = page.locator('[data-ing="i0-0"]');
  const target = page.locator('[data-drop="WAREHOUSE"]');
  await source.scrollIntoViewIfNeeded();
  const a = (await source.boundingBox())!;
  const b = (await target.boundingBox())!;
  await page.mouse.move(a.x + 20, a.y + 20);
  await page.mouse.down();
  await page.mouse.move(a.x + 35, a.y + 25, { steps: 3 });
  await page.mouse.move(b.x + 30, b.y + 60, { steps: 10 });
  await page.mouse.up();
  await expect(target.locator('[data-ing="i0-0"]')).toBeVisible();
  expect(moves).toEqual([{ storageLocation: "WAREHOUSE" }]);
});

for (const width of [1360, 375]) {
  test(`홈·발주·예측 모달 ${width}px 시각 검증`, async ({ page }) => {
    await page.setViewportSize({ width, height: width === 375 ? 812 : 800 });
    await mockApi(page);
    const capture = async (name: string) => {
      await page.addStyleTag({ content: "nextjs-portal { visibility: hidden; }" });
      for (const plot of await page.locator('[class*="draw"]').all())
        await plot.evaluate((el) =>
          Promise.all(el.getAnimations().map((a) => a.finished.catch(() => {}))),
        );
      if (process.env.UI_FEEDBACK_ARTIFACT_DIR)
        await page.screenshot({
          path: `${process.env.UI_FEEDBACK_ARTIFACT_DIR}/${name}-${width}.png`,
          fullPage: !name.includes("modal"),
        });
    };
    await page.goto("/order");
    await page.getByRole("button", { name: "검증 재료 0-0" }).click();
    await page.getByRole("button", { name: /11월 셋째 주.*13원/ }).click();
    await capture("order");
    await page.getByRole("button", { name: /이번 주 매출 예측/ }).click();
    await expect(page.getByRole("dialog", { name: "이번 주 매출 예측" })).toBeVisible();
    await expect(page.locator('[class*="ready"]').first()).toBeVisible();
    const analysis = page.getByRole("dialog").locator('[class*="say"]');
    await expect(analysis).toHaveCSS("opacity", "1");
    const forecast = {
      success: true,
      data: { analysis: "시연용 실제 API 계약입니다. 주문해 두는 걸 권해요." },
    };
    expect(forecast.success).toBeTruthy();
    await expect(analysis.locator('[aria-hidden="true"]')).toHaveText(forecast.data!.analysis);
    await capture("forecast-modal");
    await page.goto("/");
    await expect(
      page.getByRole("heading", { name: "안녕하세요 로컬 UI 검증 사장님!", exact: true }),
    ).toBeVisible();
    await capture("home");
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
    ).toBeTruthy();
  });
}

for (const [unit, label] of [
  ["G", "kg"],
  ["ML", "L"],
]) {
  test(`단가 이력의 ${unit} 응답은 ${label} 표시 기준으로 환산한다`, async ({ page }) => {
    await mockApi(page, [10, 13], unit);
    await page.goto("/order");
    await page.getByRole("button", { name: "검증 재료 0-0" }).click();
    await expect(page.getByRole("status", { name: "검증 거래처" })).toContainText("13,000원");
    await expect(page.getByLabel("가격 추이")).toContainText(`원/1${label}`);
    await expect(page.getByText(`1상자 = 24${label}`, { exact: false }).first()).toBeVisible();
  });
}

test("재료 viewport 크기 변경과 모달 자체 스크롤을 유지한다", async ({ page }) => {
  await mockApi(page);
  await page.goto("/ingredients");
  await expect(page.locator("[data-ing]").first()).toBeVisible();
  for (const viewport of [
    { width: 1360, height: 800 },
    { width: 820, height: 600 },
    { width: 768, height: 1024 },
  ]) {
    await page.setViewportSize(viewport);
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollHeight))
      .toBeLessThanOrEqual(viewport.height + 1);
    for (const place of places) {
      const zone = page.locator(`[data-drop="${place}"]`);
      await expect
        .poll(() => zone.evaluate((el) => el.scrollHeight - el.clientHeight))
        .toBeLessThanOrEqual(1);
    }
  }
  await page.setViewportSize({ width: 820, height: 600 });
  await page.locator("[data-ing]").first().click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await dialog.evaluate((el) => {
    el.scrollTop = el.scrollHeight;
  });
  await expect(dialog.getByRole("button", { name: "닫기", exact: true })).toBeVisible();
  await dialog.getByRole("button", { name: "닫기", exact: true }).click();
  await expect(dialog).not.toBeVisible();
  expect(await page.evaluate(() => document.documentElement.style.overflow)).toBe("");
  await page.setViewportSize({ width: 375, height: 812 });
  await expect
    .poll(() => page.evaluate(() => document.documentElement.scrollHeight))
    .toBeGreaterThan(812);
});

test("예측 API 네 날짜를 그대로 그리며 299 색상 모핑을 보존한다", async ({ page }) => {
  await mockApi(page, undefined, undefined, "2026-11-12");
  await page.clock.setFixedTime(new Date("2026-11-16T12:00:00+09:00"));
  await page.emulateMedia({ reducedMotion: "no-preference" });
  await page.goto("/order");
  const card = page.locator('button[aria-haspopup="dialog"]');
  await expect(card.locator('[class*="wk"] i')).toHaveCount(4);
  await expect(card.locator("em")).toHaveText(["오늘", "금", "토", "일"]);
  await expect(page.getByText("11월 12일 목요일", { exact: true })).toBeVisible();
  await expect(card).toHaveCSS("background-color", "rgb(207, 34, 41)");
  await card.click();
  const modal = page.getByRole("dialog", { name: "이번 주 매출 예측" });
  await expect(modal).toHaveCSS("background-color", "rgb(243, 241, 236)");
  await expect(modal.getByText("요일별 지수 · 평소 100")).toBeVisible();
  await expect(modal.locator('[class*="fwk"] i')).toHaveCount(4);
  await modal.getByRole("button", { name: "닫기", exact: true }).click();
  await expect(modal).not.toBeVisible();
  await expect(card).toHaveCSS("background-color", "rgb(207, 34, 41)");
});

test("예측 부재는 로딩과 구분해 알린다", async ({ page }) => {
  await mockApi(page);
  await page.route("**/sales-forecast/this-week", (route) =>
    route.fulfill({
      json: {
        success: true,
        data: {
          pct: null,
          reason: null,
          range: "11월 9일 ~ 15일",
          days: [],
          factors: [],
          analysis: null,
        },
        error: null,
      },
    }),
  );
  await page.goto("/order");
  await expect(
    page.getByRole("status").filter({ hasText: "아직 매출 예측이 없어요" }),
  ).toBeVisible();
  await expect(page.locator('button[aria-haspopup="dialog"]')).toHaveCount(0);
});

test("예측 조회 실패 후 명시적 재시도로 실제 수치를 받는다", async ({ page }) => {
  await mockApi(page);
  let failed = true;
  await page.route("**/sales-forecast/this-week", (route) =>
    route.fulfill({
      status: failed ? 500 : 200,
      json: failed
        ? { success: false, data: null, error: { code: "COMMON_INTERNAL", message: "" } }
        : {
            success: true,
            data: {
              pct: 3,
              reason: null,
              range: "11월 9일 ~ 15일",
              days: [{ label: "오늘", value: 103 }],
              factors: [],
              analysis: null,
            },
            error: null,
          },
    }),
  );
  await page.goto("/order");
  await expect(
    page.getByRole("status").filter({ hasText: "매출 예측을 불러오지 못했어요" }),
  ).toBeVisible();
  failed = false;
  await page.getByRole("button", { name: "다시 불러오기" }).click();
  await expect(page.locator('button[aria-haspopup="dialog"]')).toContainText("+3");
});

for (const pct of [0, -5])
  test(`예측 ${pct}%·0 지수·분석 부재에서 안전하게 열린다`, async ({ page }) => {
    await mockApi(page);
    await page.route("**/sales-forecast/this-week", (route) =>
      route.fulfill({
        json: {
          success: true,
          data: {
            pct,
            reason: null,
            range: "11월 9일 ~ 15일",
            days: [{ label: "오늘", value: 0 }],
            factors: [{ name: "행사", note: "", pct: 2 }],
            analysis: null,
          },
          error: null,
        },
      }),
    );
    await page.goto("/order");
    await page.locator('button[aria-haspopup="dialog"]').click();
    const modal = page.getByRole("dialog", { name: "이번 주 매출 예측" });
    await expect(modal.locator('[class*="say"]')).toHaveCount(0);
    expect(
      await modal
        .locator("i")
        .evaluateAll((els) => els.map((el) => el.getAttribute("style")).join(" ")),
    ).not.toMatch(/NaN|Infinity/);
  });

test("식봄 발주 저장 실패를 유지하고 같은 수량·주문처로 재시도한다", async ({ page }) => {
  await mockApi(page);
  const decisions: unknown[] = [];
  const orders: unknown[] = [];
  await page.route("**/recommendations/r1", async (route) => {
    decisions.push(route.request().postDataJSON());
    await route.fulfill({
      status: decisions.length === 1 ? 200 : 409,
      json:
        decisions.length === 1
          ? { success: true, data: null, error: null }
          : {
              success: false,
              data: null,
              error: { code: "RECOMMENDATION_ALREADY_DECIDED", message: "" },
            },
    });
  });
  await page.route("**/purchase-orders/from-recommendations", async (route) => {
    orders.push(route.request().postDataJSON());
    await route.fulfill({
      status: orders.length === 1 ? 409 : 200,
      json:
        orders.length === 1
          ? {
              success: false,
              data: null,
              error: { code: "PURCHASE_ORDER_SOURCE_PRICE_UNSET", message: "" },
            }
          : { success: true, data: [], error: null },
    });
  });
  await page.goto("/order");
  await page.getByRole("button", { name: "검증 재료 0-0", exact: true }).click();
  await page.getByRole("button", { name: "더하기", exact: true }).click();
  await page
    .locator('[class*="offs"] > div')
    .filter({ hasText: "식봄" })
    .getByRole("button", { name: "담기", exact: true })
    .click();
  await page.getByRole("button", { name: "발주하기", exact: true }).click();
  const sheet = page.getByRole("dialog", { name: "주문 메시지" });
  await sheet.getByRole("button", { name: "주문 완료", exact: true }).click();
  await sheet.getByRole("button", { name: "완료", exact: true }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "단가를 먼저 넣어 주세요" }),
  ).toBeVisible();
  await expect(sheet).toBeVisible();
  expect(decisions[0]).toEqual({ action: "modify", finalQty: 2424 });
  expect(orders[0]).toEqual({ items: [{ recommendationId: "r1", source: "SIKBOM" }] });
  await sheet.getByRole("button", { name: "완료", exact: true }).click();
  await expect(sheet).not.toBeVisible();
  await expect(page.getByText("오늘 주문 없음", { exact: true })).toBeVisible();
  expect(orders).toHaveLength(2);
  expect(orders[1]).toEqual(orders[0]);
});

test("예측 기간에 가게의 오늘이 없으면 다른 날을 오늘로 바꾸지 않는다", async ({ page }) => {
  await mockApi(page, undefined, undefined, "2026-11-16");
  await page.goto("/order");
  const card = page.locator('button[aria-haspopup="dialog"]');
  await expect(card.locator("em")).toHaveText(["목", "금", "토", "일"]);
});

test("마감 시각 API 객체 저장 뒤 영수증으로 이동한다", async ({ page }) => {
  await mockApi(page);
  let body: unknown;
  await page.route("**/stores/me/closing-times", async (route) => {
    body = route.request().postDataJSON();
    await route.fulfill({ json: { success: true, data: {}, error: null } });
  });
  await page.route("**/receipts", (route) =>
    route.fulfill({
      json: {
        success: true,
        data: { jobId: null, files: [], matches: [], options: [], readyDocs: [] },
        error: null,
      },
    }),
  );
  await page.goto("/onboarding/closing");
  await expect(page.getByRole("button", { name: "다음", exact: true })).toBeDisabled();
  await page.getByLabel("마감 시각", { exact: true }).fill("22:00");
  await page.getByRole("button", { name: "일", exact: true }).click();
  await page.getByRole("button", { name: "다음", exact: true }).click();
  await expect(page).toHaveURL(/\/onboarding\/receipts$/);
  expect(body).toEqual({
    closingTimes: Array.from({ length: 6 }, (_, i) => ({ dayOfWeek: i + 1, closeTime: "22:00" })),
  });
});

test("마감 시각 조회 실패를 명시하고 재시도한다", async ({ page }) => {
  await mockApi(page);
  let failed = true;
  await page.route("**/stores/me", async (route) => {
    await route.fulfill({
      status: failed ? 500 : 200,
      json: failed
        ? { success: false, data: null, error: { code: "COMMON_INTERNAL", message: "" } }
        : { success: true, data: { closingTimes: [] }, error: null },
    });
  });
  await page.goto("/onboarding/closing");
  await expect(page.getByRole("alert").filter({ hasText: "불러오지 못했어요" })).toBeVisible();
  failed = false;
  await page.getByRole("button", { name: "다시 불러오기" }).click();
  await expect(page.getByLabel("마감 시각", { exact: true })).toBeVisible();
});

test("이미 마감 시각이 있는 가게는 다시 묻지 않는다", async ({ page }) => {
  await mockApi(page);
  await page.route("**/stores/me", (route) =>
    route.fulfill({
      json: {
        success: true,
        data: { closingTimes: [{ dayOfWeek: 1, closeTime: "02:00" }] },
        error: null,
      },
    }),
  );
  await page.route("**/receipts", (route) =>
    route.fulfill({
      json: {
        success: true,
        data: { jobId: null, files: [], matches: [], options: [], readyDocs: [] },
        error: null,
      },
    }),
  );
  await page.goto("/onboarding/closing");
  await expect(page).toHaveURL(/\/onboarding\/receipts$/);
});

test("폰 마감 요일은 44px 누르는 범위와 화면 폭을 지킨다", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await mockApi(page);
  await page.goto("/onboarding/closing");
  const days = page.getByRole("group", { name: "영업 요일" }).getByRole("button");
  await expect(days).toHaveCount(7);
  for (const day of await days.all()) {
    const box = (await day.boundingBox())!;
    expect(box.width).toBeGreaterThanOrEqual(44);
    expect(box.height).toBeGreaterThanOrEqual(44);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375);
});

test("회원가입 가운데 제목은 환영합니다만 보인다", async ({ page }) => {
  await page.goto("/signup");
  await expect(page.getByRole("heading", { level: 1, name: "환영합니다" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "회원가입" })).toHaveCount(0);
});
