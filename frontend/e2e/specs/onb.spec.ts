import { expect, onlyPhone, test } from "../support";

const PLACE = {
  id: "e2e-place",
  name: "테스트 식당",
  address: "서울 어딘가",
  photoUrl: null,
  yogiyoMatch: false,
};
const places = (data: unknown[]) => ({ success: true, data, error: null });

test("[ONB-01] 미완료 계정은 홈에서 가게 찾기로 이동한다", async ({ page, signedIn }) => {
  void signedIn;
  await page.goto("/");
  await expect(page).toHaveURL(/\/onboarding\/find$/);
});

test("[ONB-02] 미완료 계정은 앱 화면마다 가게 찾기로 이동한다", async ({ page, signedIn }) => {
  void signedIn;
  onlyPhone();
  for (const p of ["/order", "/receiving", "/recipes", "/suppliers", "/ingredients", "/more"]) {
    await page.goto(p);
    await expect(page, p).toHaveURL(/\/onboarding\/find$/);
  }
});

test("[ONB-03] 가게 찾기 첫 화면", async ({ page, signedIn }) => {
  void signedIn;
  await page.goto("/onboarding/find");
  await expect(page.getByRole("heading", { name: "가게 찾기" })).toBeVisible();
  await expect(page.getByRole("searchbox", { name: "상호명" })).toBeVisible();
  await expect(page.getByRole("button", { name: "네이버 지도 링크로 찾기" })).toBeVisible();
  await expect(page.getByRole("progressbar", { name: "온보딩 단계" })).toHaveAttribute(
    "aria-valuenow",
    "1",
  );
  await expect(page.getByRole("button", { name: "다음" })).toHaveCount(0);
});

// 가게 검색은 외부 수집(Apify) 결과가 일정하지 않아 응답을 대체한다.
test("[ONB-04] 검색 결과가 없으면 지도 링크 입력이 펼쳐진다", async ({ page, signedIn }) => {
  void signedIn;
  onlyPhone();
  await page.route("**/onboarding/places?*", (r) => r.fulfill({ json: places([]) }));
  await page.goto("/onboarding/find");
  await page.getByRole("searchbox", { name: "상호명" }).fill("없는가게");
  await page.getByRole("button", { name: "검색", exact: true }).click();
  await expect(page.getByText("검색 결과 없음")).toBeVisible();
  await expect(page.getByLabel("네이버 지도 링크")).toBeVisible();
});

test("[ONB-05] 후보를 고르면 다음 버튼이 나타난다", async ({ page, signedIn }) => {
  void signedIn;
  await page.route("**/onboarding/places?*", (r) => r.fulfill({ json: places([PLACE]) }));
  await page.goto("/onboarding/find");
  await page.getByRole("searchbox", { name: "상호명" }).fill("테스트");
  await page.getByRole("button", { name: "검색", exact: true }).click();
  const cand = page.getByRole("button", { name: /테스트 식당/ });
  await cand.click();
  await expect(cand).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("button", { name: "다음" })).toBeVisible();
});
