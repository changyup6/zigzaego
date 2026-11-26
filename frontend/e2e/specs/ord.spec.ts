import { completeAccount, expect, signInAs, test } from "../support";

test("[ORD-01] 발주 탭에 오늘 발주 추천 구역이 보인다", async ({ page, request }) => {
  await signInAs(page, request, await completeAccount(request));
  await page.goto("/order");
  await expect(page.getByRole("heading", { name: "발주", exact: true })).toBeVisible({
    timeout: 15000,
  });
  await expect(page.getByRole("heading", { name: "오늘 발주 추천 품목" })).toBeVisible();
});
