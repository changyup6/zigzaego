import { completeAccount, expect, signInAs, test } from "../support";

test("[MORE-01] 더보기에 POS 업로드, 가게 정보, 로그아웃이 보인다", async ({ page, request }) => {
  await signInAs(page, request, await completeAccount(request));
  await page.goto("/more");
  await expect(page.getByRole("heading", { name: "더보기" })).toBeVisible({ timeout: 15000 });
  await expect(page.getByRole("heading", { name: "POS 업로드" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "가게 정보" })).toBeVisible();
  await expect(page.getByText("상호명", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "로그아웃" })).toBeVisible();
});
