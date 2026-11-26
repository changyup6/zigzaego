import { completeAccount, expect, signInAs, test } from "../support";

test("[HOME-01] 홈에 오늘 발주 추천과 입고 예정 구역이 보인다", async ({ page, request }) => {
  await signInAs(page, request, await completeAccount(request));
  await page.goto("/");
  await expect(page.getByRole("heading", { name: /안녕하세요/ })).toBeVisible({ timeout: 15000 });
  await expect(page.getByRole("heading", { name: "오늘 발주 추천 품목" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "오늘 입고 예정 품목" })).toBeVisible();
});
