import { completeAccount, expect, signInAs, test } from "../support";

test("[RCP-01] 레시피 탭에 메뉴 카드와 저장한 메뉴가 보인다", async ({ page, request }) => {
  await signInAs(page, request, await completeAccount(request));
  await page.goto("/recipes");
  await expect(page.getByRole("heading", { name: "레시피" })).toBeVisible({ timeout: 15000 });
  await expect(page.getByRole("heading", { name: "메뉴", exact: true })).toBeVisible();
  await expect(page.getByText("E2E 메뉴").first()).toBeVisible();
});
