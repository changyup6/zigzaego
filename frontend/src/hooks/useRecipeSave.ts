"use client";

import { useEffect, useSyncExternalStore } from "react";
import { api, authSession } from "@/lib/api";
import { RecipeSaveQueue } from "@/lib/recipeSaveQueue";
import { setRecipeToast } from "@/lib/recipeToast";
import type { StoreMe } from "@/lib/types";

let queue: RecipeSaveQueue | undefined;
function getQueue() {
  if (typeof window === "undefined") return undefined;
  if (!queue) {
    queue = new RecipeSaveQueue(
      {
        getItem: (key) => sessionStorage.getItem(key),
        setItem: (key, value) => sessionStorage.setItem(key, value),
      },
      async (task) => {
        const session = authSession();
        const store = await api<StoreMe>(
          "/stores/me",
          { signal: AbortSignal.timeout(15000) },
          session,
        );
        if (!store.success || store.data.id !== task.storeId)
          return { success: false, message: "로그인한 가게를 확인한 뒤 다시 저장해 주세요" };
        const result = await api(
          `/onboarding/recipes/${task.answers.length ? "confirm" : "skip"}`,
          {
            method: "POST",
            ...(task.answers.length ? { body: JSON.stringify(task.answers) } : {}),
            signal: AbortSignal.timeout(15000),
          },
          session,
        );
        return { success: result.success, message: result.error?.message };
      },
      (task) => setRecipeToast(task.estimated),
    );
  }
  return queue;
}

const subscribe = (listener: () => void) => getQueue()?.subscribe(listener) ?? (() => {});
const snapshot = () => getQueue()?.snapshot() ?? 0;
const serverSnapshot = () => 0;
export const useRecipeSaveRevision = () =>
  useSyncExternalStore(subscribe, snapshot, serverSnapshot);

export function useRecipeSave(storeId?: string) {
  const revision = useRecipeSaveRevision();
  useEffect(() => {
    if (storeId) void getQueue()?.resume(storeId);
  }, [storeId, revision]);
  return {
    task: storeId ? getQueue()?.get(storeId) : undefined,
    retry: () => storeId && getQueue()?.retry(storeId),
  };
}

export function saveRecipesLater(
  storeId: string,
  answers: Parameters<RecipeSaveQueue["enqueue"]>[1],
  estimated: number,
) {
  const q = getQueue();
  q?.enqueue(storeId, answers, estimated);
  void q?.resume(storeId);
}
