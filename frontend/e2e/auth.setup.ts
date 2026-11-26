import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { expect, test } from "@playwright/test";
import { ACCOUNT_FILE, newAccount, signup } from "./support";

// 실행마다 새 계정을 가입한다. 정리는 운영 스크립트가 e2e+ 접두로 한다.
test("계정 가입", async ({ request }) => {
  const account = newAccount();
  const res = await signup(request, account);
  expect(res.status()).toBe(201);
  mkdirSync(path.dirname(ACCOUNT_FILE), { recursive: true });
  writeFileSync(ACCOUNT_FILE, JSON.stringify(account));
});
