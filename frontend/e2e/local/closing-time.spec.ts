import { expect, test } from "@playwright/test";

test("키보드로 시와 분을 고르고 기존 HH:mm 검증을 유지한다", async ({ page }) => {
  await page.goto("/dev/closing-time");
  const input = page.getByRole("combobox", { name: "마감 시각", exact: true });
  const next = page.getByRole("button", { name: "다음", exact: true });
  await expect(input).toBeVisible({ timeout: 4000 });
  await expect(next).toBeDisabled();
  await input.focus();
  await input.press("ArrowDown");
  const hour = page.getByRole("listbox", { name: "시", exact: true });
  const minute = page.getByRole("listbox", { name: "분", exact: true });
  await expect(hour).toBeFocused();
  await hour.press("End");
  await hour.press("Enter");
  await expect(input).toHaveValue("23:00");
  await expect(minute).toBeFocused();
  await minute.press("Home");
  await minute.press("ArrowDown");
  await minute.press("Enter");
  await expect(input).toHaveValue("23:01");
  await expect(page.getByRole("dialog", { name: "마감 시각 선택" })).toHaveCount(0);
  await expect(input).toBeFocused();
  await expect(next).toBeEnabled();
  await input.fill("24:00");
  await expect(next).toBeDisabled();
  await input.fill("00:00");
  await expect(next).toBeEnabled();
  await input.click();
  await input.fill("21:37");
  await input.press("ArrowDown");
  await hour.press("Enter");
  await minute.press("Enter");
  await expect(input).toHaveValue("21:37");
});

test("Escape와 바깥 클릭과 Tab은 선택값을 보존하고 닫는다", async ({ page }) => {
  await page.goto("/dev/closing-time");
  const input = page.getByLabel("마감 시각", { exact: true });
  const toggle = page.getByRole("button", { name: "마감 시각 선택", exact: true });
  const popup = page.getByRole("dialog", { name: "마감 시각 선택" });
  await input.fill("21:37");
  await toggle.click();
  await expect(page.getByRole("option", { name: "21시", exact: true })).toHaveAttribute(
    "aria-selected",
    "true",
  );
  await expect(page.getByRole("option", { name: "37분", exact: true })).toHaveAttribute(
    "aria-selected",
    "true",
  );
  await page.keyboard.press("Escape");
  await expect(popup).toHaveCount(0);
  await expect(input).toBeFocused();
  await toggle.click();
  await page.getByRole("heading", { name: "마감 시각", exact: true }).click();
  await expect(popup).toHaveCount(0);
  await expect(input).toHaveValue("21:37");
  await toggle.click();
  await expect(page.getByRole("listbox", { name: "시", exact: true })).toBeFocused();
  await page.getByRole("listbox", { name: "분", exact: true }).focus();
  await page.keyboard.press("Tab");
  await expect(popup).toHaveCount(0);
  await expect(input).toHaveValue("21:37");
});

for (const width of [375, 1360]) {
  test(`디자인 드롭다운과 44px 선택 칸이 화면 안에 표시된다 ${width}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/dev/closing-time");
    await page.getByLabel("마감 시각", { exact: true }).fill("21:37");
    await page.getByRole("button", { name: "마감 시각 선택", exact: true }).click();
    const popup = page.getByRole("dialog", { name: "마감 시각 선택" });
    await expect(popup).toBeVisible();
    const box = (await popup.boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(width);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
      width,
    );
    expect(
      (await page.getByRole("option", { name: "21시", exact: true }).boundingBox())!.height,
    ).toBeGreaterThanOrEqual(44);
    await page.screenshot({ path: info.outputPath(`closing-time-${width}.png`), fullPage: true });
    await page.getByRole("option", { name: "22시", exact: true }).click();
    await page.getByRole("option", { name: "59분", exact: true }).click();
    await expect(page.getByLabel("마감 시각", { exact: true })).toHaveValue("22:59");
    await expect(popup).toHaveCount(0);
  });
}

test("선택한 마감 시각과 영업 요일만 기존 API 형식으로 저장한다", async ({ page }) => {
  let body: unknown;
  const store = { id: "closing-test", name: "마감 검증", closingTimes: [], checklist: [] };
  await page.route("http://localhost:8080/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/stores/me/closing-times") body = route.request().postDataJSON();
    await route.fulfill({
      json: { success: true, data: path === "/stores/me" ? store : {}, error: null },
    });
  });
  await page.goto("/onboarding/closing");
  await page.getByRole("button", { name: "마감 시각 선택", exact: true }).click();
  await page.getByRole("option", { name: "02시", exact: true }).click();
  await page.getByRole("option", { name: "17분", exact: true }).click();
  await page.getByRole("button", { name: "일", exact: true }).click();
  await page.getByRole("button", { name: "다음", exact: true }).click();
  await expect
    .poll(() => body)
    .toEqual({
      closingTimes: Array.from({ length: 6 }, (_, i) => ({ dayOfWeek: i + 1, closeTime: "02:17" })),
    });
});
