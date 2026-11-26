import { test, expect } from "@playwright/test";
import { tabletDocuments, SUPPLIERS } from "./tablet-documents.fixture";

test("작은 화면의 탐색 메뉴에서 뒤쪽 문서로 포커스가 넘어가지 않는다", async ({ page }) => {
  await tabletDocuments(page);
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto("/onboarding/receipts");
  await page.getByRole("button", { name: "올리기", exact: true }).click();
  const picker = page.getByRole("dialog", { name: "파일 선택", exact: true });
  const menu = picker.getByRole("button", { name: "탐색 메뉴 열기", exact: true });
  await menu.click();
  const nav = picker.getByRole("navigation", { name: "문서 위치" });
  await expect(nav.getByRole("button", { name: "최근 파일", exact: true })).toBeFocused();
  for (let i = 0; i < 8; i++) {
    await page.keyboard.press("Tab");
    await expect(picker.getByRole("button", { name: "완료", exact: true })).not.toBeFocused();
    expect(await page.evaluate(() => document.activeElement?.getAttribute("role"))).not.toBe(
      "checkbox",
    );
  }
  await page.keyboard.press("Escape");
  await expect(menu).toBeFocused();
  await expect(picker).toBeVisible();
});

for (const [width, height] of [
  [1280, 800],
  [768, 1024],
  [375, 812],
])
  test(`태블릿 선택기 화면 경계 ${width}×${height}`, async ({ page }) => {
    await tabletDocuments(page);
    await page.setViewportSize({ width, height });
    await page.goto("/onboarding/receipts");
    await page.getByRole("button", { name: "올리기", exact: true }).click();
    const picker = page.getByRole("dialog", { name: "파일 선택", exact: true });
    await expect(picker).toBeVisible();
    if (width >= 720) await expect(picker.getByText("내 파일", { exact: true })).toBeVisible();
    const box = (await picker.boundingBox())!;
    expect(box.x).toBe(0);
    expect(box.y).toBe(0);
    expect(box.width).toBe(width);
    expect(box.height).toBe(height);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(
      width,
    );
    await picker.getByRole("checkbox").last().click();
    await expect(picker.getByRole("button", { name: "완료", exact: true })).toBeVisible();
  });

test("검색·목록 전환·키보드 선택을 해도 선택한 파일을 유지한다", async ({ page }) => {
  await tabletDocuments(page);
  await page.goto("/onboarding/receipts");
  await page.getByRole("button", { name: "올리기", exact: true }).click();
  const picker = page.getByRole("dialog", { name: "파일 선택", exact: true });
  await picker.getByRole("checkbox", { name: /푸른식자재/ }).focus();
  await page.keyboard.press("Space");
  await expect(picker.getByRole("checkbox", { name: /푸른식자재/ })).toBeChecked();
  await picker.getByRole("button", { name: "파일 검색", exact: true }).click();
  await picker.getByRole("textbox", { name: "파일 이름 또는 거래처 검색" }).fill("한빛축산");
  await expect(picker.getByRole("checkbox")).toHaveCount(1);
  await picker.getByRole("button", { name: "그리드 보기", exact: true }).click();
  await picker.getByRole("checkbox").click();
  await picker.getByRole("button", { name: "검색어 지우기", exact: true }).click();
  await expect(picker.getByRole("checkbox")).toHaveCount(3);
  await expect(picker.getByRole("checkbox", { checked: true })).toHaveCount(2);
});

test("세 거래처 명세서를 선택하고 인식·확정해도 거래처가 유지된다", async ({ page }, info) => {
  await page.clock.install({ time: new Date("2027-01-01T12:00:00+09:00") });
  await tabletDocuments(page);
  await page.setViewportSize({ width: 1024, height: 768 });
  let nativeDialogs = 0;
  const writes: string[] = [];
  page.on("filechooser", () => nativeDialogs++);
  page.on("request", (r) => {
    if (r.method() !== "GET") writes.push(r.url());
  });
  await page.goto("/onboarding/receipts");
  const upload = page.getByRole("button", { name: "올리기", exact: true });
  await expect(upload).toBeEnabled();
  await page.clock.pauseAt(new Date("2027-01-01T12:01:00+09:00"));
  await upload.click();
  const picker = page.getByRole("dialog", { name: "파일 선택", exact: true });
  await expect(picker).toBeVisible();
  await expect(picker.getByRole("checkbox")).toHaveCount(3);
  await expect(picker.getByRole("button", { name: "완료", exact: true })).toBeDisabled();
  await page.screenshot({ path: info.outputPath("tablet-files.png") });
  for (const name of SUPPLIERS)
    await picker.getByRole("checkbox", { name: new RegExp(name) }).click();
  await expect(picker.getByText("3개 선택됨", { exact: true })).toBeVisible();
  await page.screenshot({ path: info.outputPath("tablet-files-selected.png") });
  await picker.getByRole("button", { name: "완료", exact: true }).click();
  await expect(picker).toHaveCount(0);
  const spinner = page.getByRole("status", { name: "영수증 읽는 중" });
  await expect(spinner).toBeVisible();
  await page.clock.fastForward(5000);
  const result = page.getByRole("dialog", { name: "이전 발주 거래 명세서", exact: true });
  for (const name of SUPPLIERS) await expect(result.getByText(new RegExp(name))).toBeVisible();
  await expect(result.getByText(/거래처 모름/)).toHaveCount(0);
  await page.screenshot({ path: info.outputPath("tablet-receipts-complete.png") });
  await result.getByRole("button", { name: "확정", exact: true }).click();
  await expect(page.getByText("반영 완료", { exact: true })).toHaveCount(3);
  expect(nativeDialogs).toBe(0);
  expect(writes).toEqual([]);
});

test("선택 취소·해제는 영수증을 만들지 않고 다시 열면 초기화된다", async ({ page }) => {
  await tabletDocuments(page);
  await page.goto("/onboarding/receipts");
  const upload = page.getByRole("button", { name: "올리기", exact: true });
  await upload.click();
  const picker = page.getByRole("dialog", { name: "파일 선택", exact: true });
  const first = picker.getByRole("checkbox").first();
  await first.click();
  await first.click();
  await expect(picker.getByRole("button", { name: "완료", exact: true })).toBeDisabled();
  await first.click();
  await page.keyboard.press("Escape");
  await expect(picker).toHaveCount(0);
  await expect(page.getByRole("status", { name: "영수증 읽는 중" })).toHaveCount(0);
  await upload.click();
  await expect(picker.getByRole("checkbox", { checked: true })).toHaveCount(0);
});

test("시연 자료가 PREPARING일 때는 실패 배너 없이 준비 완료 후 명세서를 자동으로 불러온다", async ({
  page,
}) => {
  await tabletDocuments(page);
  let ready = false;
  await page.route("**/stores/me/demo-preparation", (route) =>
    route.fulfill({
      json: {
        success: true,
        data: { eligible: true, status: ready ? "READY" : "PREPARING" },
        error: null,
      },
    }),
  );
  await page.route("**/suppliers", (route) =>
    route.fulfill({
      json: {
        success: true,
        data: ready ? SUPPLIERS.map((name, index) => ({ id: `s${index}`, name })) : [],
        error: null,
      },
    }),
  );
  await page.route("**/supplier-products", (route) =>
    route.fulfill({
      json: {
        success: true,
        data: ready
          ? SUPPLIERS.map((name, index) => ({
              id: `p${index}`,
              supplierId: `s${index}`,
              ingredientId: "i0",
              productName: `${name} 등록 품목`,
              baseQtyPerPack: 1000,
              packUnit: "팩",
              price: (index + 1) * 10000,
              primary: true,
            }))
          : [],
        error: null,
      },
    }),
  );

  await page.goto("/onboarding/receipts");
  const upload = page.getByRole("button", { name: "올리기", exact: true });
  await expect(upload).toBeDisabled();
  await expect(page.getByText("시연 명세서를 불러오지 못했습니다")).toHaveCount(0);

  ready = true;
  await expect(upload).toBeEnabled({ timeout: 5000 });
  await expect(page.getByText("시연 명세서를 불러오지 못했습니다")).toHaveCount(0);
  await upload.click();
  await expect(
    page.getByRole("dialog", { name: "파일 선택", exact: true }).getByRole("checkbox"),
  ).toHaveCount(3);
});
