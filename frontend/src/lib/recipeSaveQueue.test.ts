import assert from "node:assert/strict";
import { test } from "node:test";
import { RecipeSaveQueue } from "./recipeSaveQueue.ts";

function storage() {
  const values = new Map<string, string>();
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value);
    },
  };
}
const answers = [{ questionId: "q1", value: 150, unit: "g" as const }];

test("navigation does not await save and rapid repeated clicks send once", async () => {
  let done!: (value: { success: boolean }) => void;
  let requests = 0;
  const queue = new RecipeSaveQueue(storage(), async () => {
    requests++;
    return new Promise((resolve) => {
      done = resolve;
    });
  });
  queue.enqueue("store-a", answers, 0);
  queue.enqueue("store-a", answers, 0);
  const running = queue.resume("store-a");
  assert.equal(queue.get("store-a")?.state, "pending");
  await Promise.resolve();
  assert.equal(requests, 1);
  done({ success: true });
  await running;
  assert.equal(queue.get("store-a"), undefined);
});

test("failed answers persist across reload and explicit retry recovers", async () => {
  const saved = storage();
  const first = new RecipeSaveQueue(saved, async () => ({ success: false, message: "연결 실패" }));
  first.enqueue("store-a", answers, 2);
  await first.resume("store-a");
  assert.equal(first.get("store-a")?.state, "failed");
  const calls: unknown[] = [];
  const restored = new RecipeSaveQueue(saved, async (task) => {
    calls.push(task.answers);
    return { success: true };
  });
  await restored.resume("store-a");
  assert.equal(calls.length, 0); // 실패는 자동 무한 재시도하지 않는다
  await restored.retry("store-a");
  assert.deepEqual(calls, [answers]);
  assert.equal(restored.get("store-a"), undefined);
});

test("new store never resumes another store's pending answers", async () => {
  const saved = storage();
  const first = new RecipeSaveQueue(saved, async () => new Promise(() => {}));
  first.enqueue("store-a", answers, 0);
  let requests = 0;
  const restored = new RecipeSaveQueue(saved, async () => {
    requests++;
    return { success: true };
  });
  await restored.resume("store-b");
  assert.equal(requests, 0);
  assert.deepEqual(restored.get("store-a")?.answers, answers);
});

test("pending work survives reload without replacing an in-flight answer", async () => {
  const saved = storage();
  const queue = new RecipeSaveQueue(saved, async () => new Promise(() => {}));
  queue.enqueue("store-a", answers, 1);
  queue.enqueue("store-a", [], 4);
  const completed: number[] = [];
  const restored = new RecipeSaveQueue(
    saved,
    async () => ({ success: true }),
    (task) => {
      completed.push(task.estimated);
    },
  );
  await restored.resume("store-a");
  assert.deepEqual(completed, [1]);
});
