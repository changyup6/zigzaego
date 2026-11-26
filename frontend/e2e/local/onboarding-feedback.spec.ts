import { expect, test, type Page } from "@playwright/test";
import { mkdir } from "node:fs/promises";

const STORE = {
  id: "flow-store",
  name: "온보딩 검증",
  today: "2026-11-12",
  closingTimes: [],
  priceCompare: false,
  checklist: [{ step: "ONBOARDING", done: false, remaining: 1 }],
};
const QUESTIONS = [
  { id: "q1", ingredientId: "i1", text: "돼지고기 1인분", unit: "g", options: [100, 150, 200] },
];
const ok = (data: unknown) => ({ success: true, data, error: null });
async function fixture(page: Page) {
  await page.route("**/*", (route) => {
    const url = new URL(route.request().url());
    return url.hostname === "localhost" &&
      [process.env.UI_FEEDBACK_PORT ?? "3100", "8080"].includes(url.port)
      ? route.fallback()
      : route.abort();
  });
  await page.route("http://localhost:8080/**", (route) => {
    const path = new URL(route.request().url()).pathname;
    return route.fulfill({
      json: ok(
        path === "/stores/me"
          ? STORE
          : path.endsWith("recipe-questions")
            ? QUESTIONS
            : path === "/receipts"
              ? { jobId: null, files: [], matches: [], options: [], readyDocs: [] }
              : [],
      ),
    });
  });
}

for (const width of [375, 820, 1360])
  test(`메뉴 목록 대기 중 레이아웃 스켈레톤 ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await fixture(page);
    let release!: () => void;
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route("**/onboarding/recipe-questions", async (route) => {
      await held;
      await route.fulfill({ json: ok(QUESTIONS) });
    });
    try {
      await page.goto("/onboarding/recipes");
      await expect(page.getByTestId("recipe-questions-loading")).toBeVisible();
      await expect(page.getByRole("button", { name: "건너뛰기", exact: true })).toHaveCount(0);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
        true,
      );
      await mkdir("/tmp/pao-onboarding-captures", { recursive: true });
      await page.screenshot({ path: `/tmp/pao-onboarding-captures/recipe-loading-${width}.png` });
      release();
      await expect(page.getByText("돼지고기 1인분", { exact: true })).toBeVisible();
      await expect(page.getByTestId("recipe-questions-loading")).toHaveCount(0);
    } finally {
      release();
    }
  });

test("마지막 답변 저장 대기 중 마감으로 이동, 실패는 답을 보존해 재시도", async ({ page }) => {
  await fixture(page);
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  const bodies: unknown[] = [];
  await page.route("**/onboarding/recipes/confirm", async (route) => {
    bodies.push(route.request().postDataJSON());
    if (bodies.length === 1) await held;
    await route.fulfill({
      status: bodies.length === 1 ? 503 : 200,
      json:
        bodies.length === 1
          ? { success: false, data: null, error: { code: "RETRY", message: "잠시 후 다시 시도" } }
          : ok({}),
    });
  });
  try {
    await page.goto("/onboarding/recipes");
    await page.getByRole("button", { name: /150/ }).click();
    await expect(page.getByRole("heading", { name: "마감 시각", exact: true })).toBeVisible();
    expect(bodies).toHaveLength(1);
    release();
    await expect(
      page.getByText("레시피 저장을 마치지 못했어요. 입력한 답은 보관했어요."),
    ).toBeVisible();
    await page.reload();
    await page.getByRole("button", { name: "저장 다시 시도", exact: true }).click();
    await expect.poll(() => bodies.length).toBe(2);
    expect(bodies[0]).toEqual([{ questionId: "q1", value: 150, unit: "g" }]);
    expect(bodies[1]).toEqual(bodies[0]);
    await expect(page.getByText("레시피를 저장하고 있어요")).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "마감 시각", exact: true })).toBeVisible();
  } finally {
    release();
  }
});

test("레시피 응답보다 마감 시각을 먼저 저장해도 작업을 유지한다", async ({ page }) => {
  await fixture(page);
  let release!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  let saves = 0;
  let closing: unknown;
  await page.route("**/onboarding/recipes/skip", async (route) => {
    saves++;
    await held;
    await route.fulfill({ json: ok({}) });
  });
  await page.route("**/stores/me/closing-times", async (route) => {
    closing = route.request().postDataJSON();
    await route.fulfill({ json: ok({}) });
  });
  try {
    await page.goto("/onboarding/recipes");
    await page.getByRole("button", { name: "건너뛰기", exact: true }).click();
    await page.getByLabel("마감 시각", { exact: true }).fill("21:30");
    await page.getByRole("button", { name: "다음", exact: true }).click();
    await expect(page.getByRole("heading", { name: "지난 영수증", exact: true })).toBeVisible();
    await expect(page.getByText("레시피를 저장하고 있어요")).toBeVisible();
    expect(saves).toBe(1);
    expect(closing).toEqual({
      closingTimes: Array.from({ length: 7 }, (_, i) => ({ dayOfWeek: i + 1, closeTime: "21:30" })),
    });
    release();
    await expect(page.getByText("레시피를 저장하고 있어요")).toHaveCount(0);
  } finally {
    release();
  }
});

test("메뉴 조회 실패 재시도와 정상 0건은 로딩과 구분", async ({ page }) => {
  await fixture(page);
  let failed = true;
  await page.route("**/onboarding/recipe-questions", async (route) => {
    await route.fulfill({
      status: failed ? 503 : 200,
      json: failed
        ? { success: false, data: null, error: { code: "RETRY", message: "목록 조회 실패" } }
        : ok([]),
    });
  });
  await page.goto("/onboarding/recipes");
  await expect(page.getByRole("alert").filter({ hasText: "목록 조회 실패" })).toBeVisible();
  failed = false;
  await page.getByRole("button", { name: "다시 불러오기", exact: true }).click();
  await expect(page.getByText("확인할 레시피가 없어요")).toBeVisible();
  await expect(page.getByTestId("recipe-questions-loading")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "건너뛰기", exact: true })).toBeEnabled();
});
