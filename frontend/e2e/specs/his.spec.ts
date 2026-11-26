import { completeAccount, expect, signInAs, test } from "../support";

test("[HIS-01] 발주 이력에 오늘 입고 예정 구역이 보인다", async ({ page, request }) => {
  await signInAs(page, request, await completeAccount(request));
  await page.goto("/receiving");
  await expect(page.getByRole("heading", { name: "발주 이력" })).toBeVisible({ timeout: 15000 });
  await expect(page.getByRole("heading", { name: "오늘 입고 예정" })).toBeVisible();
});
