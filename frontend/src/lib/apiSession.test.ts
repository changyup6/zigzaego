import assert from "node:assert/strict";
import { test } from "node:test";
import { api, authSession, setTokens } from "./api.ts";

test("expired old request never retries with a newly signed-in store's token", async () => {
  const values = new Map<string, string>();
  const previousStorage = Object.getOwnPropertyDescriptor(globalThis, "sessionStorage");
  const previousFetch = globalThis.fetch;
  Object.defineProperty(globalThis, "sessionStorage", {
    configurable: true,
    value: {
      getItem: (k: string) => values.get(k) ?? null,
      setItem: (k: string, v: string) => {
        values.set(k, v);
      },
      removeItem: (k: string) => {
        values.delete(k);
      },
    },
  });
  try {
    setTokens({ accessToken: "old-store", refreshToken: "old-refresh" });
    const session = authSession();
    let release!: (response: Response) => void;
    const paths: string[] = [];
    globalThis.fetch = async (url) => {
      paths.push(String(url));
      return new Promise((resolve) => {
        release = resolve;
      });
    };
    const pending = api("/onboarding/recipes/confirm", { method: "POST" }, session);
    setTokens({ accessToken: "new-store", refreshToken: "new-refresh" });
    release(
      new Response(
        JSON.stringify({
          success: false,
          data: null,
          error: { code: "AUTH_TOKEN_EXPIRED", message: "expired" },
        }),
        { status: 401 },
      ),
    );
    const result = await pending;
    assert.equal(result.success, false);
    assert.equal(result.error?.code, "AUTH_SESSION_CHANGED");
    assert.equal(paths.length, 1);
  } finally {
    globalThis.fetch = previousFetch;
    setTokens(null);
    if (previousStorage) Object.defineProperty(globalThis, "sessionStorage", previousStorage);
    else Reflect.deleteProperty(globalThis, "sessionStorage");
  }
});

test("old in-flight refresh cannot overwrite a newly signed-in session", async () => {
  const values = new Map<string, string>();
  const previousStorage = Object.getOwnPropertyDescriptor(globalThis, "sessionStorage");
  const previousFetch = globalThis.fetch;
  Object.defineProperty(globalThis, "sessionStorage", {
    configurable: true,
    value: {
      getItem: (k: string) => values.get(k) ?? null,
      setItem: (k: string, v: string) => {
        values.set(k, v);
      },
      removeItem: (k: string) => {
        values.delete(k);
      },
    },
  });
  try {
    setTokens({ accessToken: "old-store", refreshToken: "old-refresh" });
    let started!: () => void;
    const refreshing = new Promise<void>((resolve) => {
      started = resolve;
    });
    let finish!: (response: Response) => void;
    let authorization: string | null = null;
    globalThis.fetch = async (url, init) => {
      if (String(url).endsWith("/auth/refresh")) {
        started();
        return new Promise((resolve) => {
          finish = resolve;
        });
      }
      if (String(url).endsWith("/onboarding/recipes/confirm"))
        return new Response(
          JSON.stringify({
            success: false,
            data: null,
            error: { code: "AUTH_TOKEN_EXPIRED", message: "expired" },
          }),
          { status: 401 },
        );
      authorization = new Headers(init?.headers).get("Authorization");
      return new Response(JSON.stringify({ success: true, data: {}, error: null }));
    };
    const pending = api("/onboarding/recipes/confirm", { method: "POST" });
    await refreshing;
    setTokens({ accessToken: "new-store", refreshToken: "new-refresh" });
    finish(
      new Response(
        JSON.stringify({
          success: true,
          data: { accessToken: "old-refreshed", refreshToken: "old-refresh-2" },
          error: null,
        }),
      ),
    );
    assert.equal((await pending).error?.code, "AUTH_SESSION_CHANGED");
    await api("/stores/me");
    assert.equal(authorization, "Bearer new-store");
  } finally {
    globalThis.fetch = previousFetch;
    setTokens(null);
    if (previousStorage) Object.defineProperty(globalThis, "sessionStorage", previousStorage);
    else Reflect.deleteProperty(globalThis, "sessionStorage");
  }
});
