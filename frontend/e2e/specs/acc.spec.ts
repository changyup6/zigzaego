import { API, expect, loginApi, newAccount, onlyPhone, signup, test } from "../support";

test("[ACC-01] 가입하면 가게 찾기로 이동한다", async ({ page }) => {
  const a = newAccount();
  await page.goto("/signup");
  await page.getByLabel("이메일").fill(a.email);
  await page.getByLabel("비밀번호").fill(a.password);
  await page.getByText("전체 동의").click();
  await page.getByRole("button", { name: "다음" }).click();
  await expect(page).toHaveURL(/\/onboarding\/find$/);
  await expect(page.getByRole("heading", { name: "가게 찾기" })).toBeVisible();
});

test("[ACC-02] 약관을 켜지 않으면 다음이 비활성이다", async ({ page }) => {
  const a = newAccount();
  await page.goto("/signup");
  await page.getByLabel("이메일").fill(a.email);
  await page.getByLabel("비밀번호").fill(a.password);
  await expect(page.getByRole("button", { name: "다음" })).toBeDisabled();
});

test("[ACC-06] 미완료 계정은 로그인하면 가게 찾기로 이동한다", async ({ page, account }) => {
  await page.goto("/login");
  await page.getByLabel("이메일").fill(account.email);
  await page.getByLabel("비밀번호").fill(account.password);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).toHaveURL(/\/onboarding\/find$/);
});

test("[ACC-08] 틀린 비밀번호는 로그인 실패 문구를 보인다", async ({ page, account }) => {
  onlyPhone();
  await page.goto("/login");
  await page.getByLabel("이메일").fill(account.email);
  await page.getByLabel("비밀번호").fill(`${account.password}x`);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page.getByText("로그인 실패", { exact: true })).toBeVisible();
  await expect(page).toHaveURL(/\/login$/);
});

test("[ACC-10] 로그아웃한 refresh token 은 다시 쓸 수 없다", async ({ request }) => {
  onlyPhone();
  const a = newAccount();
  expect((await signup(request, a)).status()).toBe(201);
  const { refreshToken } = (await (await loginApi(request, a)).json()).data;
  const out = await request.post(`${API}/auth/logout`, { data: { refreshToken } });
  expect(out.ok()).toBeTruthy();
  const again = await request.post(`${API}/auth/refresh`, { data: { refreshToken } });
  expect(again.status()).toBe(401);
  expect((await again.json()).error.code).toBe("AUTH_INVALID_REFRESH_TOKEN");
});
