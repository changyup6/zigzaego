"use client";

import { useEffect, useState } from "react";
import { authSession, getStoreMe } from "@/lib/api";
import { setToday } from "@/lib/today";
import type { StoreMe } from "@/lib/types";

export const isOnboarded = (s: StoreMe) =>
  s.checklist.find((c) => c.step === "ONBOARDING")?.done ?? false;

// GET /stores/me. 401 이면 api 가 refresh 하거나 로그인 화면으로 보낸다.
export function useStore(enabled = true, refreshKey = 0) {
  const session = authSession();
  const [saved, setStore] = useState<{ session: string; store: StoreMe | null } | null>(null);
  useEffect(() => {
    if (!enabled) return;
    let live = true;
    getStoreMe().then((r) => {
      if (live) {
        setToday(r.data?.today);
        setStore({ session, store: r.data });
      }
    });
    return () => {
      live = false;
    };
  }, [enabled, refreshKey, session]);
  return saved?.session === session ? saved.store : null;
}
