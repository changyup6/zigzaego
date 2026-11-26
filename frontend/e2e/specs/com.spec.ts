import {
  addSupplier,
  completeAccount,
  expect,
  onlyDesktop,
  signInAs,
  test,
} from "../support";

test("[COM-01] 로그인하지 않으면 로그인 화면으로 이동한다", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveURL(/\/login$/);
});

// 가게 간 데이터 격리 (ADR-0002). 계정 A 의 거래처가 계정 B 화면에 보이지 않는다
test("[COM-03] 다른 가게의 거래처는 보이지 않는다", async ({ page, request }) => {
  onlyDesktop();
  const a = await completeAccount(request);
  await addSupplier(request, a, { name: "A 가게 거래처" });
  const b = await completeAccount(request);
  await signInAs(page, request, b);
  await page.goto("/suppliers");
  await expect(page.getByRole("heading", { name: "거래처" })).toBeVisible({ timeout: 15000 });
  await expect(page.getByRole("button", { name: /거래처 추가/ })).toBeVisible();
  await expect(page.getByText("A 가게 거래처")).toHaveCount(0);
});
