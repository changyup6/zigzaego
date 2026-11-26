import { test, expect } from "@playwright/test";
import { mock } from "./refinements.fixture";

test("시연 OCR은 5초 돋보기 뒤 성공하고 서버에 업로드·확정하지 않는다", async ({ page }) => {
  await page.clock.install({ time: new Date("2027-01-01T12:00:00+09:00") });
  await mock(page);
  await page.route("**/stores/me/demo-preparation", (route) =>
    route.fulfill({ json: { success: true, data: { eligible: true }, error: null } }),
  );
  const writes: string[] = [];
  page.on("request", (request) => {
    if (request.method() !== "GET") writes.push(request.url());
  });
  await page.goto("/onboarding/receipts");
  const upload = page.getByRole("button", { name: "올리기", exact: true });
  await expect(upload).toBeEnabled();
  await page.clock.pauseAt(new Date("2027-01-01T12:01:00+09:00"));
  await upload.click();
  const picker = page.getByRole("dialog", { name: "파일 선택", exact: true });
  await picker.getByRole("checkbox").first().click();
  await picker.getByRole("button", { name: "완료", exact: true }).click();
  const spinner = page.getByRole("status", { name: "영수증 읽는 중" });
  await expect(spinner).toBeVisible();
  await page.clock.fastForward(4999);
  await expect(spinner).toBeVisible();
  await page.clock.fastForward(1);
  await expect(spinner).toHaveCount(0);
  await expect(page.getByText("분석 완료", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "확정", exact: true }).click();
  await expect(page.getByText("반영 완료", { exact: true })).toBeVisible();
  expect(writes).toEqual([]);
});

test("일반 계정의 OCR 실패는 성공으로 바꾸지 않는다", async ({ page }) => {
  await mock(page);
  let uploads = 0;
  await page.route("**/receipts", async (route) => {
    if (route.request().method() !== "POST") return route.fallback();
    uploads++;
    await route.fulfill({
      status: 503,
      json: {
        success: false,
        data: null,
        error: { code: "RECEIPT_OCR_NOT_CONFIGURED", message: "unavailable" },
      },
    });
  });
  await page.goto("/onboarding/receipts");
  const input = page.locator('input[type="file"]');
  await expect(input).toBeEnabled();
  await input.setInputFiles({
    name: "receipt.png",
    mimeType: "image/png",
    buffer: Buffer.from("bad-image"),
  });
  await expect(page.getByText("지금은 읽을 수 없음", { exact: true })).toBeVisible();
  await expect(page.getByText("분석 완료", { exact: true })).toHaveCount(0);
  expect(uploads).toBe(1);
});
