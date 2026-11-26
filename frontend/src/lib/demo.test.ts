import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { test } from "node:test";

// DEMO_BUILD 는 import 때 정해지므로 플래그마다 새 프로세스에서 isDemo() 를 부른다
const run = (flag: string | undefined, preset: string | null) => {
  const script = `
    const store = new Map(${preset ? `[["demo", ${JSON.stringify(preset)}]]` : ""});
    globalThis.sessionStorage = {
      getItem: (k) => store.get(k) ?? null,
      setItem: (k, v) => void store.set(k, v),
      removeItem: (k) => void store.delete(k),
    };
    const { isDemo } = await import(${JSON.stringify(new URL("./mock/orderFeed.ts", import.meta.url).href)});
    const seen = [];
    for (const search of ["?demo=1", "", "?demo=off", ""]) {
      globalThis.location = { search };
      seen.push(isDemo());
    }
    console.log(JSON.stringify({ seen, stored: store.get("demo") ?? null }));
  `;
  const env = { ...process.env, NEXT_PUBLIC_DEMO: flag };
  if (flag === undefined) delete env.NEXT_PUBLIC_DEMO;
  return JSON.parse(
    execFileSync(process.execPath, ["--input-type=module", "-e", script], { env }).toString(),
  );
};

test("플래그가 꺼지면 ?demo 와 남은 sessionStorage 를 무시하고 쓰지도 않는다", () => {
  assert.deepEqual(run(undefined, null), { seen: [false, false, false, false], stored: null });
  assert.deepEqual(run(undefined, "1"), { seen: [false, false, false, false], stored: "1" });
  assert.deepEqual(run("true", null), { seen: [false, false, false, false], stored: null });
});

test("플래그가 1 이면 ?demo 가 탭을 옮겨도 남고 ?demo=off 로 끝난다", () => {
  assert.deepEqual(run("1", null), { seen: [true, true, false, false], stored: null });
});
