import { test, expect } from "@playwright/test";
import { mock } from "./refinements.fixture";

test.describe("수집된 한 점 선택", () => {
  test.use({ hasTouch: true, viewport: { width: 1360, height: 1000 } });
  for (const action of ["click", "tap"] as const)
    test(`거래처 한 점도 ${action}으로 선택한다`, async ({ page }) => {
      await mock(page);
      await page.route(/\/ingredients\/[^/]+\/market-price-history(?:\?.*)?$/, (route) =>
        route.fulfill({
          json: {
            success: true,
            error: null,
            data: {
              toIngredientUnitFactor: 1,
              sites: [{ site: "쿠팡", points: [{ date: "2026-11-12", unitPrice: 3 }] }],
            },
          },
        }),
      );
      await page.goto("/order");
      await page.getByRole("button", { name: /^재료A(?:\s+\d.*)?$/ }).click();
      const chart = page.getByLabel("가격 추이");
      await chart
        .locator('xpath=ancestor::div[contains(@class,"fold")][1]')
        .evaluate(async (el) => {
          await Promise.all(
            el.getAnimations({ subtree: true }).map((animation) => animation.finished),
          );
        });
      await chart.getByRole("button", { name: /11월 둘째 주.*쿠팡.*3,000원/ })[action]();
      await expect(chart.getByRole("button", { name: "쿠팡", exact: true })).toHaveAttribute(
        "aria-pressed",
        "true",
      );
    });
});

test("거래처 선택과 선 클릭이 해당 선과 범례를 빨간색으로 바꾼다", async ({ page }) => {
  await page.setViewportSize({ width: 1360, height: 1000 });
  await mock(page);
  await page.goto("/order");
  await page.getByRole("button", { name: /^재료A(?:\s+\d.*)?$/ }).click();
  const chart = page.getByLabel("가격 추이");
  const paths = chart.locator("svg path");
  const red = await paths.nth(4).evaluate((el) => getComputedStyle(el).stroke);
  await chart.getByRole("button", { name: "식봄", exact: true }).click();
  await expect(paths.nth(6)).toHaveCSS("stroke", red);
  expect(await paths.nth(4).evaluate((el) => getComputedStyle(el).stroke)).not.toBe(red);
  await expect(chart.getByRole("button", { name: "식봄", exact: true })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  const point = await paths.nth(5).evaluate((el) => {
    const path = el as SVGPathElement;
    const p = path.getPointAtLength(path.getTotalLength() / 2);
    const transformed = new DOMPoint(p.x, p.y).matrixTransform(path.getScreenCTM()!);
    return { x: transformed.x, y: transformed.y };
  });
  await page.mouse.click(point.x, point.y);
  await expect(chart.getByRole("button", { name: "쿠팡", exact: true })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await expect(paths.nth(5)).toHaveCSS("stroke", red);
});

test("선택하지 않은 거래처 점에도 주차·가격 툴팁이 보인다", async ({ page }) => {
  await page.setViewportSize({ width: 1360, height: 1000 });
  await mock(page);
  await page.goto("/order");
  await page.getByRole("button", { name: /^재료A(?:\s+\d.*)?$/ }).click();
  const chart = page.getByLabel("가격 추이");
  const point = chart.getByRole("button", { name: /11월 둘째 주.*쿠팡.*3,000원/ });
  await point.scrollIntoViewIfNeeded();
  const box = (await point.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await expect(chart.getByRole("tooltip")).toContainText("11월 둘째 주");
  await expect(chart.getByRole("tooltip")).toContainText("3,000원");
  await expect(chart.getByRole("tooltip")).toContainText("쿠팡");
  await point.focus();
  await expect(chart.getByRole("tooltip")).toBeVisible();
  await point.press("Escape");
  await expect(chart.getByRole("tooltip")).toHaveCount(0);
});
for (const width of [375, 820, 1360])
  test(`실제 다중 가격과 원형 점·축·선택 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: width === 375 ? 812 : 1000 });
    await mock(page);
    await page.goto("/order");
    await page.getByRole("button", { name: /^재료A(?:\s+\d.*)?$/ }).click();
    const chart = page.getByLabel("가격 추이");
    await expect(chart.getByText("가격 (원)", { exact: true })).toHaveCount(0);
    await expect(chart.getByText("날짜 · 7일 집계")).toHaveCount(0);
    for (const name of ["쿠팡", "식봄", "마켓봄", "오더링크"])
      await expect(chart.getByRole("button", { name, exact: true })).toBeVisible();
    expect((await chart.locator("svg").boundingBox())!.width).toBeGreaterThan(180);
    const point = chart.locator("[data-price-point]").first();
    await point.focus();
    await point.press("ArrowRight");
    await expect(chart.getByRole("status")).toContainText("10월");
    await chart.getByRole("button", { name: "쿠팡", exact: true }).click();
    const last = chart.getByRole("button", { name: /11월 둘째 주.*쿠팡.*3,000원/ });
    await last.hover();
    await last.click();
    await expect(chart.getByRole("status")).toHaveText("11월 둘째 주3,000원");
    for (const dot of await chart.locator("[data-price-point] i").all()) {
      const b = (await dot.boundingBox())!;
      expect(Math.abs(b.width - b.height)).toBeLessThan(0.2);
    }
    const offs = page.locator('[class*="offs"] > div');
    await expect(
      offs.filter({ hasText: "마켓봄" }).getByRole("button", { name: "담기" }),
    ).toBeDisabled();
    await expect(offs.filter({ hasText: "식봄" })).toContainText("8,000원 · 2kg");
    await expect(offs.filter({ hasText: "시연 합성 가격" })).toHaveCount(0);
    await expect(page.getByText("기준 수량 환산 · 배송비 제외", { exact: true })).toHaveCount(0);
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
    ).toBeTruthy();
    if (process.env.PRICE_CAPTURE_DIR) {
      await page.addStyleTag({
        content: 'nextjs-portal,[class*="__mbar"] {visibility:hidden!important}',
      });
      await chart.screenshot({
        path: `${process.env.PRICE_CAPTURE_DIR}/price-${width}.png`,
      });
    }
  });
test("홈 절감액과 수량·주문처 변경 후 기준 합계가 일치한다", async ({ page }) => {
  await mock(page);
  await page.goto("/");
  await expect(page.getByRole("link", { name: /오늘 발주 추천 품목/ })).toContainText("30,000원");
  await page.getByRole("link", { name: /오늘 발주 추천 품목/ }).click();
  await page.getByRole("button", { name: /^재료A(?:\s+\d.*)?$/ }).click();
  await page.getByRole("button", { name: "더하기", exact: true }).click();
  await page
    .locator('[class*="offs"] > div')
    .filter({ hasText: "식봄" })
    .getByRole("button", { name: "담기" })
    .click();
  await expect(
    page.locator('[class*="OrderScreen"][class*="__sum"]').filter({ hasText: "합계" }),
  ).toContainText("31,000원");
  await page.getByRole("link", { name: "홈", exact: true }).click();
  await expect(page.getByText("−4,000원", { exact: true })).toBeVisible();
});
for (const weekly of [[], [0], [4, 4], [null, null]])
  test(`누락·0·한 점·동일 값 ${JSON.stringify(weekly)}`, async ({ page }) => {
    await mock(page, weekly);
    await page.goto("/order");
    await page.getByRole("button", { name: /^재료A(?:\s+\d.*)?$/ }).click();
    expect(
      await page
        .getByLabel("가격 추이")
        .locator("svg path")
        .evaluateAll((els) => els.map((e) => e.getAttribute("d")).join(" ")),
    ).not.toMatch(/NaN|Infinity/);
  });
test.describe("터치 가격 선택", () => {
  test.use({ viewport: { width: 375, height: 812 }, hasTouch: true });
  test("판매처와 가격 점을 탭해 두 줄 가격을 읽는다", async ({ page }) => {
    await mock(page);
    await page.goto("/order");
    await page.getByRole("button", { name: /^재료A(?:\s+\d.*)?$/ }).tap();
    const chart = page.getByLabel("가격 추이");
    await chart.getByRole("button", { name: "쿠팡", exact: true }).tap();
    await chart.getByRole("button", { name: /11월 둘째 주.*쿠팡.*3,000원/ }).tap();
    await expect(chart.getByRole("status")).toHaveText("11월 둘째 주3,000원");
  });
});
