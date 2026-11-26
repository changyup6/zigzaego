import type { RecipeAnswer } from "./types.ts";

export type RecipeSave = {
  id: string;
  storeId: string;
  answers: RecipeAnswer[];
  estimated: number;
  state: "pending" | "failed";
  message?: string;
};
type StorageLike = Pick<Storage, "getItem" | "setItem">;
type Result = { success: boolean; message?: string };
const KEY = "wiggly-recipe-saves:v1";

// React 화면의 수명과 분리한다. 페이지 이동 뒤에도 같은 요청을 유지하고 재진입 때 복구한다.
export class RecipeSaveQueue {
  private tasks = new Map<string, RecipeSave>();
  private running = new Map<string, Promise<void>>();
  private listeners = new Set<() => void>();
  private storage: StorageLike;
  private send: (task: RecipeSave) => Promise<Result>;
  private completed: (task: RecipeSave) => void;
  private version = 0;

  constructor(
    storage: StorageLike,
    send: (task: RecipeSave) => Promise<Result>,
    completed: (task: RecipeSave) => void = () => {},
  ) {
    this.storage = storage;
    this.send = send;
    this.completed = completed;
    try {
      const saved: unknown = JSON.parse(storage.getItem(KEY) ?? "[]");
      if (Array.isArray(saved))
        for (const t of saved) {
          if (
            t &&
            typeof t.id === "string" &&
            typeof t.storeId === "string" &&
            t.storeId &&
            (t.state === "pending" || t.state === "failed") &&
            Number.isInteger(t.estimated) &&
            t.estimated >= 0 &&
            Array.isArray(t.answers) &&
            t.answers.every(
              (a: RecipeAnswer) =>
                a &&
                typeof a.questionId === "string" &&
                Number.isFinite(a.value) &&
                a.value > 0 &&
                ["g", "ml", "개"].includes(a.unit),
            )
          )
            this.tasks.set(t.storeId, t);
        }
    } catch {} // 손상된 저장본은 요청으로 보내지 않는다.
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };
  snapshot = () => this.version;
  get = (storeId: string) => this.tasks.get(storeId);

  private publish() {
    try {
      this.storage.setItem(KEY, JSON.stringify([...this.tasks.values()]));
    } catch {}
    this.version++;
    this.listeners.forEach((listener) => listener());
  }

  enqueue(storeId: string, answers: RecipeAnswer[], estimated: number) {
    if (!storeId || this.tasks.get(storeId)?.state === "pending") return;
    this.tasks.set(storeId, {
      id: crypto.randomUUID(),
      storeId,
      answers: structuredClone(answers),
      estimated,
      state: "pending",
    });
    this.publish();
  }

  resume(storeId: string): Promise<void> {
    const active = this.running.get(storeId);
    if (active) return active;
    const task = this.tasks.get(storeId);
    if (!task || task.state !== "pending") return Promise.resolve();
    // 요청 시작 전에 running을 등록해 같은 순간의 중복 요청도 막는다.
    const work = Promise.resolve()
      .then(() => this.send(task))
      .catch(() => ({
        success: false,
        message: "레시피를 저장하지 못했어요",
      }))
      .then((result) => {
        if (this.tasks.get(storeId)?.id !== task.id) return;
        if (result.success) {
          this.tasks.delete(storeId);
        } else
          this.tasks.set(storeId, {
            ...task,
            state: "failed",
            message: result.message || "레시피를 저장하지 못했어요",
          });
        this.publish();
        if (result.success) {
          try {
            this.completed(task);
          } catch {} // 토스트 실패가 저장 완료를 되돌리지 않는다.
        }
      })
      .finally(() => {
        this.running.delete(storeId);
      });
    this.running.set(storeId, work);
    return work;
  }

  retry(storeId: string) {
    const task = this.tasks.get(storeId);
    if (!task || this.running.has(storeId)) return this.running.get(storeId) ?? Promise.resolve();
    this.tasks.set(storeId, { ...task, state: "pending", message: undefined });
    this.publish();
    return this.resume(storeId);
  }
}
