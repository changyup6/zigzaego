import { addSupplier, completeAccount, expect, signInAs, test } from "../support";

test("[SUP-01] 거래처 카드에 채널과 마감 시각이 보이고 마지막에 추가 카드가 있다", async ({
  page,
  request,
}) => {
  const a = await completeAccount(request);
  await addSupplier(request, a, {
    name: "E2E 거래처",
    channel: "KAKAO",
    cutoffTime: "21:00",
    deliveryDays: ["MON", "WED", "FRI"],
  });
  await signInAs(page, request, a);
  await page.goto("/suppliers");
  await expect(page.getByRole("heading", { name: "거래처" })).toBeVisible({ timeout: 15000 });
  await expect(page.getByText("E2E 거래처", { exact: true })).toBeVisible();
  await expect(page.getByText("마감 21:00")).toBeVisible();
  await expect(page.getByRole("button", { name: /거래처 추가/ })).toBeVisible();
});
