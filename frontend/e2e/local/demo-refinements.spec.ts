import { test, expect } from "@playwright/test";
import { mock } from "./refinements.fixture";

test("상품 카드 링크는 새 탭을 열고 담기 버튼은 따로 동작한다", async ({ page }) => {
  await mock(page);
  await page.goto("/order");
  await page.getByRole("button", { name: /^재료A(?:\s+\d.*)?$/ }).click();
  const link = page.getByRole("link", { name: "재료A · 식봄 상품 보기 (새 탭)" });
  await expect(link).toHaveAttribute("href", "https://www.foodspring.co.kr/goods/detail/550224");
  const card = link.locator("..");
  await expect(card).not.toContainText("시연 합성 가격");
  await card.getByRole("button", { name: "담기", exact: true }).click();
  await expect(card.getByRole("button", { name: "취소", exact: true })).toBeVisible();
  const popup = page.waitForEvent("popup");
  await link.click();
  await (await popup).close();
});

test("모바일 홈·더보기·레시피·로그인에 가로 넘침이 없다", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await mock(page);
  for (const path of ["/", "/more", "/recipes", "/login"]) {
    await page.goto(path);
    await expect(page.locator("main")).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
      375,
    );
  }
});

test("긴 재료·거래처 이름이 줄바꿈되어도 대응 행 높이가 같다", async ({ page }) => {
  await mock(page, undefined, true);
  await page.goto("/");
  const order = page.getByRole("link", { name: /오늘 발주 추천 품목/ }).locator("li");
  const incoming = page.getByRole("link", { name: /오늘 입고 예정 품목/ }).locator("li");
  await expect(order).toHaveCount(2);
  for (const width of [820, 1360]) {
    await page.setViewportSize({ width, height: 1000 });
    for (let i = 0; i < 2; i++) {
      expect((await order.nth(i).boundingBox())!.height).toBe(
        (await incoming.nth(i).boundingBox())!.height,
      );
      expect((await order.nth(i).boundingBox())!.y).toBe((await incoming.nth(i).boundingBox())!.y);
    }
  }
});

test("홈 입고 5개와 거래처, 좌우 행 높이", async ({ page }, info) => {
  await page.setViewportSize({ width: 1360, height: 1000 });
  await mock(page);
  await page.goto("/");
  const incoming = page.getByRole("link", { name: /오늘 입고 예정 품목/ });
  await expect(incoming.locator("li")).toHaveCount(5);
  await expect(incoming.locator("li").first()).toContainText("검증 거래처");
  const order = page.getByRole("link", { name: /오늘 발주 추천 품목/ });
  expect((await order.locator("li").first().boundingBox())!.height).toBe(
    (await incoming.locator("li").first().boundingBox())!.height,
  );
  for (const card of [order, incoming]) {
    const outer = (await card.boundingBox())!;
    const button = (await card.locator(':scope > [class*="go"]').boundingBox())!;
    const padding = await card.evaluate((el) => parseFloat(getComputedStyle(el).paddingBottom));
    expect(outer.y + outer.height - button.y - button.height).toBeCloseTo(padding, 0);
  }
  await page.screenshot({ path: info.outputPath("home.png"), fullPage: true });
});

test("위치와 엑셀 탐색기: 선택해도 서버 전송 없음", async ({ page }) => {
  await mock(page);
  const writes: string[] = [];
  page.on("request", (r) => {
    if (r.method() !== "GET") writes.push(r.url());
  });
  await page.goto("/more");
  await expect(
    page.getByText("서울특별시 송파구 석촌호수로12길 22", { exact: true }),
  ).toBeVisible();
  const chooser = page.waitForEvent("filechooser");
  await page.getByText("엑셀 올리기", { exact: true }).click();
  await (
    await chooser
  ).setFiles({ name: "시연.csv", mimeType: "text/csv", buffer: Buffer.from("메뉴,수량\n예시,1") });
  await expect(page.getByRole("status")).toHaveText("시연.csv · 선택됨");
  expect(writes).toEqual([]);
});

test.describe("첫 레시피 로딩", () => {
  test.use({ reducedMotion: "no-preference", viewport: { width: 1360, height: 1000 } });
  test("늦게 로드한 미확인 칸도 흔들리고 확인완료 그룹은 검정", async ({ page }, info) => {
    await mock(page);
    await page.goto("/recipes");
    const field = page.locator('input[class*="nudge"]').first();
    await expect(field).toBeVisible();
    expect(await field.evaluate((el) => getComputedStyle(el).animationName)).toContain("nudge");
    const todo = page.getByText("레시피를 확인해주세요!", { exact: true });
    const done = page.getByText("확인완료", { exact: true });
    await expect(done).toBeVisible();
    expect(await done.evaluate((el) => getComputedStyle(el).color)).not.toBe(
      await todo.evaluate((el) => getComputedStyle(el).color),
    );
    expect(await page.locator('[class*="rcp"]').first().getAttribute("data-open")).toBeNull();
    await page.screenshot({ path: info.outputPath("recipes.png"), fullPage: true });
  });
});

test("동작 줄이기를 켜면 레시피 칸은 흔들리지 않는다", async ({ page }) => {
  await mock(page);
  await page.goto("/recipes");
  await expect(page.getByRole("button", { name: /미확인 메뉴/ })).toBeVisible();
  // 좁은 화면에서는 메뉴를 연 뒤 수량 칸이 보인다.
  await page.getByRole("button", { name: /미확인 메뉴/ }).click();
  await expect(page.locator('input[class*="nudge"]').first()).toHaveCSS("animation-name", "none");
});

test("로그인도 가운데 로고 없이 환영합니다", async ({ page }, info) => {
  await page.goto("/login");
  await expect(page.getByRole("heading", { name: "환영합니다", exact: true })).toBeVisible();
  await expect(page.locator('[class*="mark"] img')).toHaveCount(0);
  await page.screenshot({ path: info.outputPath("login.png"), fullPage: true });
});
