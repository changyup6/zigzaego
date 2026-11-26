import { randomBytes } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import { test as base, expect, type APIRequestContext, type Page } from "@playwright/test";

export const API = process.env.E2E_API_URL ?? "https://zigzaego.example.com";
if (new URL(API).hostname !== "zigzaego.example.com" && new URL(API).hostname !== "localhost")
  throw new Error("E2E_API_URL 은 스테이징(zigzaego.example.com) 또는 localhost 만 허용한다");

// 한 실행 안에서 phone · desktop 이 같이 쓰는 계정. auth.setup.ts 가 만든다. 커밋하지 않는다.
export const ACCOUNT_FILE = path.join(__dirname, ".auth", "account.json");

export type Account = { email: string; password: string };

export function newAccount(): Account {
  const t = new Date().toISOString().replace(/\D/g, "").slice(0, 14);
  return {
    email: `e2e+${t}-${randomBytes(4).toString("hex")}@zigzaego.example.com`,
    password: randomBytes(12).toString("base64url"),
  };
}

export const signup = (request: APIRequestContext, a: Account) =>
  request.post(`${API}/auth/signup`, {
    data: { ...a, agreements: ["TERMS", "PRIVACY", "COLLECTION"] },
  });

export const loginApi = (request: APIRequestContext, a: Account) =>
  request.post(`${API}/auth/login`, { data: a });

// refresh token 은 한 번만 쓸 수 있고(docs/API.md) sessionStorage("wiggly-refresh", src/lib/api.ts)에 있어
// storageState 로 넘길 수 없다. 테스트마다 로그인해 새 토큰을 받아 addInitScript 로 심는다.
const RT_KEY = "wiggly-refresh";

// 새로고침해도 앱이 돌려 쓴 토큰을 덮어쓰지 않도록 비어 있을 때만 심는다
const injectRefresh = (page: Page, refreshToken: string) =>
  page.addInitScript(
    ([k, v]) => {
      if (!sessionStorage.getItem(k)) sessionStorage.setItem(k, v);
    },
    [RT_KEY, refreshToken],
  );

// 계정으로 로그인해 그 가게의 화면을 연다 (계정마다 다른 저장 상태를 쓸 때)
export async function signInAs(page: Page, request: APIRequestContext, a: Account) {
  const res = await loginApi(request, a);
  expect(res.ok()).toBeTruthy();
  await injectRefresh(page, (await res.json()).data.refreshToken);
}

const bearer = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` });

// 완료 계정: 가입한 뒤 API 로 재료 · 메뉴 · 레시피를 만들어 가게 설정의 레시피 확인까지 끝낸다.
// 메뉴 분석(외부 LLM)을 부르지 않아 스테이징에서 바로 쓸 수 있다 (docs/test/E2E_시나리오.md '테스트 계정')
export async function completeAccount(
  request: APIRequestContext,
  a: Account = newAccount(),
): Promise<Account> {
  const res = await signup(request, a);
  expect(res.status(), await res.text()).toBe(201);
  const headers = bearer((await res.json()).data.accessToken);
  const ing = await request.post(`${API}/ingredients`, {
    headers,
    data: { name: "E2E 재료", unit: "G", kind: "BOM", storageType: "CHILLED" },
  });
  expect(ing.status(), await ing.text()).toBe(201);
  const menu = await request.post(`${API}/menus`, {
    headers,
    data: { name: "E2E 메뉴", source: "POS", price: 9000 },
  });
  expect(menu.status(), await menu.text()).toBe(201);
  const recipe = await request.post(`${API}/recipes`, {
    headers,
    data: {
      menuId: (await menu.json()).data.id,
      ingredientId: (await ing.json()).data.id,
      qtyPerServing: 150,
    },
  });
  expect(recipe.ok(), await recipe.text()).toBeTruthy();
  return a;
}

// 완료 계정에 거래처를 하나 더 만든다
export async function addSupplier(
  request: APIRequestContext,
  a: Account,
  data: Record<string, unknown>,
) {
  const res = await loginApi(request, a);
  expect(res.ok()).toBeTruthy();
  const headers = bearer((await res.json()).data.accessToken);
  const created = await request.post(`${API}/suppliers`, { headers, data });
  expect(created.status(), await created.text()).toBe(201);
}

export const test = base.extend<{ account: Account; signedIn: Account }>({
  account: async ({}, provide) => provide(JSON.parse(readFileSync(ACCOUNT_FILE, "utf8"))),
  signedIn: async ({ account, page, request }, provide) => {
    await signInAs(page, request, account);
    await provide(account);
  },
});

export { expect };

export const onlyPhone = () => test.skip(test.info().project.name !== "phone", "375 전용");
export const onlyDesktop = () => test.skip(test.info().project.name !== "desktop", "1360 전용");
