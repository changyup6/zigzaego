import assert from "node:assert/strict";
import { test } from "node:test";
import {
  api,
  putClosingTimes,
  createPurchaseOrders,
  login,
  logout,
  retryOnboardingJob,
  setTokens,
  signup,
  startOnboardingJob,
  uploadMenuPhoto,
} from "./api.ts";
import { ok } from "./mock/ok.ts";

const store = new Map<string, string>();
Object.assign(globalThis, {
  sessionStorage: {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  },
});
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
const err401 = (code: string) =>
  json(401, { success: false, data: null, error: { code, message: "" } });

function stubWindow() {
  const loc = { pathname: "/home", assigned: [] as string[] };
  Object.assign(globalThis, {
    window: { location: { ...loc, replace: (u: string) => void loc.assigned.push(u) } },
  });
  return loc.assigned;
}

test("401 → refresh 한 번(동시 요청 공유) → 새 토큰으로 원요청 재시도", async () => {
  setTokens({ accessToken: "old", refreshToken: "rt" });
  let refreshCalls = 0;
  globalThis.fetch = (async (url: string, init?: RequestInit) => {
    if (url.endsWith("/auth/refresh")) {
      refreshCalls++;
      return json(200, ok({ accessToken: "new", refreshToken: "rt2" }));
    }
    const auth = (init?.headers as Record<string, string>).Authorization;
    return auth === "Bearer new" ? json(200, ok({ v: 1 })) : err401("AUTH_TOKEN_EXPIRED");
  }) as typeof fetch;

  const [a, b] = await Promise.all([api<{ v: number }>("/x"), api<{ v: number }>("/y")]);
  assert.deepEqual(a.data, { v: 1 });
  assert.deepEqual(b.data, { v: 1 });
  assert.equal(refreshCalls, 1);
  assert.equal(store.get("wiggly-refresh"), "rt2");
});

test("access token 만료 → refresh → 원요청 재시도가 성공", async () => {
  setTokens({ accessToken: "old", refreshToken: "rt" });
  const seen: string[] = [];
  globalThis.fetch = (async (url: string, init?: RequestInit) => {
    seen.push(new URL(url).pathname);
    if (url.endsWith("/auth/refresh"))
      return json(200, ok({ accessToken: "new", refreshToken: "rt2" }));
    const auth = (init?.headers as Record<string, string>).Authorization;
    return auth === "Bearer new" ? json(200, ok({ id: "1" })) : err401("AUTH_TOKEN_EXPIRED");
  }) as typeof fetch;

  const r = await api<{ id: string }>("/stores/me");
  assert.equal(r.success, true);
  assert.deepEqual(r.data, { id: "1" });
  assert.deepEqual(seen, ["/stores/me", "/auth/refresh", "/stores/me"]);
});

test("refresh 실패 → 토큰 삭제 · 로그인 화면", async () => {
  const assigned = stubWindow();
  setTokens({ accessToken: "old", refreshToken: "rt" });
  globalThis.fetch = (async (url: string) =>
    url.endsWith("/auth/refresh")
      ? err401("AUTH_INVALID_TOKEN")
      : err401("AUTH_TOKEN_EXPIRED")) as typeof fetch;

  await api("/x");
  assert.deepEqual(assigned, ["/login"]);
  assert.equal(store.has("wiggly-refresh"), false);
});

for (const code of ["AUTH_INVALID_TOKEN", "COMMON_UNAUTHORIZED"]) {
  test(`${code} → refresh 없이 로그인 화면`, async () => {
    const assigned = stubWindow();
    setTokens({ accessToken: "a", refreshToken: "rt" });
    let refreshCalls = 0;
    globalThis.fetch = (async (url: string) => {
      if (url.endsWith("/auth/refresh")) refreshCalls++;
      return err401(code);
    }) as typeof fetch;

    await api("/x");
    assert.equal(refreshCalls, 0);
    assert.deepEqual(assigned, ["/login"]);
  });
}

test("logout → POST /auth/logout { refreshToken } 후 토큰 삭제", async () => {
  setTokens({ accessToken: "a", refreshToken: "rt" });
  const calls: [string, RequestInit | undefined][] = [];
  globalThis.fetch = (async (url: string, init?: RequestInit) => {
    calls.push([url, init]);
    return json(200, ok(null));
  }) as typeof fetch;

  await logout();
  assert.equal(calls.length, 1);
  assert.ok(calls[0][0].endsWith("/auth/logout"));
  assert.equal(calls[0][1]?.method, "POST");
  assert.deepEqual(JSON.parse(String(calls[0][1]?.body)), { refreshToken: "rt" });
  assert.equal(store.has("wiggly-refresh"), false);
});

test("login → POST /auth/login 성공이면 refresh token 저장, 틀리면 저장 안 함 · 로그인 화면 이동 없음", async () => {
  const assigned = stubWindow();
  setTokens(null);
  globalThis.fetch = (async (_url: string, init?: RequestInit) => {
    const { password } = JSON.parse(String(init?.body)) as { password: string };
    return password === "right-pass"
      ? json(200, ok({ accessToken: "a", refreshToken: "rt" }))
      : err401("AUTH_INVALID_CREDENTIALS");
  }) as typeof fetch;

  assert.equal((await login({ email: "e@x.kr", password: "wrong-pass" })).success, false);
  assert.equal(store.has("wiggly-refresh"), false);
  assert.deepEqual(assigned, []);
  assert.equal((await login({ email: "e@x.kr", password: "right-pass" })).success, true);
  assert.equal(store.get("wiggly-refresh"), "rt");
});

test("signup → POST /auth/signup { email, password, 약관 셋 } 성공이면 refresh token 저장", async () => {
  setTokens(null);
  const calls: [string, RequestInit | undefined][] = [];
  globalThis.fetch = (async (url: string, init?: RequestInit) => {
    calls.push([url, init]);
    return json(201, ok({ accessToken: "a", expiresIn: 1800, refreshToken: "rt" }));
  }) as typeof fetch;

  assert.equal((await signup({ email: "e@x.kr", password: "password1234" })).success, true);
  assert.equal(calls.length, 1);
  assert.ok(calls[0][0].endsWith("/auth/signup"));
  assert.equal(calls[0][1]?.method, "POST");
  assert.deepEqual(JSON.parse(String(calls[0][1]?.body)), {
    email: "e@x.kr",
    password: "password1234",
    agreements: ["TERMS", "PRIVACY", "COLLECTION"],
  });
  assert.equal(store.get("wiggly-refresh"), "rt");
});

test("signup 409 AUTH_EMAIL_TAKEN → 코드 그대로, 토큰 저장 안 함 · 로그인 화면 이동 없음", async () => {
  const assigned = stubWindow();
  setTokens(null);
  globalThis.fetch = (async () =>
    json(409, {
      success: false,
      data: null,
      error: { code: "AUTH_EMAIL_TAKEN", message: "" },
    })) as typeof fetch;

  const r = await signup({ email: "e@x.kr", password: "password1234" });
  assert.equal(r.success, false);
  assert.equal(r.error?.code, "AUTH_EMAIL_TAKEN");
  assert.equal(store.has("wiggly-refresh"), false);
  assert.deepEqual(assigned, []);
});

test("메뉴판 사진은 multipart(Content-Type 을 fetch 에 맡김), 다시 시도는 저장한 요청을 새 job 으로", async () => {
  const calls: { url: string; init?: RequestInit }[] = [];
  globalThis.fetch = (async (url: string, init?: RequestInit) => {
    calls.push({ url, init });
    return json(200, ok({ id: "9", type: "ONBOARDING", status: "QUEUED", progress: 0 }));
  }) as typeof fetch;

  await uploadMenuPhoto(new File(["x"], "menu.png"));
  assert.ok(calls[0].init?.body instanceof FormData);
  assert.equal((calls[0].init?.headers as Record<string, string>)["Content-Type"], undefined);

  await startOnboardingJob({ placeId: "1", includeYogiyo: false });
  await retryOnboardingJob();
  assert.equal(calls[2].url.endsWith("/onboarding/jobs"), true);
  assert.equal(calls[2].init?.body, calls[1].init?.body);
});

test("마감 시각 저장은 ClosingTimesRequest 객체로 보낸다", async () => {
  let body: unknown;
  globalThis.fetch = (async (_url: string, init?: RequestInit) => {
    body = JSON.parse(init!.body as string);
    return json(200, ok({ closingTimes: [] }));
  }) as typeof fetch;
  await putClosingTimes([{ dayOfWeek: 1, closeTime: "02:00" }]);
  assert.deepEqual(body, { closingTimes: [{ dayOfWeek: 1, closeTime: "02:00" }] });
});

test("발주서 만들기: 주문처를 주면 items[{recommendationId, source}], 안 주면 recommendationIds 그대로", async () => {
  const bodies: unknown[] = [];
  globalThis.fetch = (async (_url: string, init?: RequestInit) => {
    bodies.push(JSON.parse(init!.body as string));
    return json(200, ok([]));
  }) as typeof fetch;

  await createPurchaseOrders(["1", "2"]);
  await createPurchaseOrders(["1", "2"], "SIKBOM");
  await createPurchaseOrders(["3"], "COUPANG");
  assert.deepEqual(bodies[0], { recommendationIds: ["1", "2"] });
  assert.deepEqual(bodies[1], {
    items: [
      { recommendationId: "1", source: "SIKBOM" },
      { recommendationId: "2", source: "SIKBOM" },
    ],
  });
  assert.deepEqual(bodies[2], { items: [{ recommendationId: "3", source: "COUPANG" }] });
});

test("발주서 만들기: 409 PURCHASE_ORDER_SOURCE_PRICE_UNSET 은 실패 응답 그대로 돌려준다", async () => {
  globalThis.fetch = (async () =>
    json(409, {
      success: false,
      data: null,
      error: { code: "PURCHASE_ORDER_SOURCE_PRICE_UNSET", message: "" },
    })) as typeof fetch;
  const r = await createPurchaseOrders(["1"], "SIKBOM");
  assert.equal(r.success, false);
  assert.equal(r.error?.code, "PURCHASE_ORDER_SOURCE_PRICE_UNSET");
});
