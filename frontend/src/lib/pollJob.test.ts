import assert from "node:assert/strict";
import { test } from "node:test";
import { pollJob } from "./pollJob.ts";
import { ok } from "./mock/ok.ts";

test("2초마다 조회 · DONE 에서 멈춤 · 가려지면 멈추고 보이면 재시작", async (t) => {
  t.mock.timers.enable({ apis: ["setTimeout"] });
  const doc = Object.assign(new EventTarget(), { visibilityState: "visible" });
  Object.assign(globalThis, { document: doc });
  let n = 0;
  const fetchJob = async () => {
    n++;
    return ok({ id: "j", status: n >= 4 ? "DONE" : "RUNNING" }) as never;
  };
  const stop = pollJob("j", fetchJob, () => {});
  const flush = () => new Promise((r) => setImmediate(r));
  await flush();
  assert.equal(n, 1);
  t.mock.timers.tick(2000);
  await flush();
  assert.equal(n, 2);

  doc.visibilityState = "hidden";
  doc.dispatchEvent(new Event("visibilitychange"));
  t.mock.timers.tick(10000);
  await flush();
  assert.equal(n, 2);

  doc.visibilityState = "visible";
  doc.dispatchEvent(new Event("visibilitychange"));
  await flush();
  assert.equal(n, 3);
  t.mock.timers.tick(2000);
  await flush();
  assert.equal(n, 4); // DONE
  t.mock.timers.tick(10000);
  await flush();
  assert.equal(n, 4);
  stop();
});
