import { completeAccount, expect, signInAs, test } from "../support";

test("[ING-01] 재료 탭에 보기 전환과 재료 찾기가 보인다", async ({ page, request }) => {
  await signInAs(page, request, await completeAccount(request));
  await page.goto("/ingredients");
  await expect(page.getByRole("heading", { name: "재료", exact: true })).toBeVisible({
    timeout: 15000,
  });
  await expect(page.getByRole("group", { name: "보기" })).toBeVisible();
  await expect(page.getByRole("searchbox", { name: "재료 찾기" })).toBeVisible();
});
