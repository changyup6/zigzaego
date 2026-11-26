"use client";

import { usePathname } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { authSession, getStoreMe } from "@/lib/api";
import { FlowStoreContext } from "@/hooks/FlowStoreContext";
import { setToday } from "@/lib/today";
import type { StoreMe } from "@/lib/types";
import { AppShell } from "./AppShell";
import { Button } from "./Button";
import { RecipeSaveStatus } from "./RecipeSaveStatus";

// 로그인 중인 온보딩에서만 가게를 읽는다. 실패해도 빈 화면으로 막지 않는다.
export function FlowShell({ children }: { children: ReactNode }) {
  const onboarding = usePathname().startsWith("/onboarding");
  const [state, setState] = useState<{ store: StoreMe | null; failed: boolean; session: string }>({
    store: null,
    failed: false,
    session: "",
  });
  const [attempt, setAttempt] = useState(0);
  const session = authSession();
  const store = state.session === session ? state.store : null;
  useEffect(() => {
    if (!onboarding) return;
    let live = true;
    getStoreMe().then((r) => {
      if (!live) return;
      if (r.success) setToday(r.data.today);
      setState({ store: r.success ? r.data : null, failed: !r.success, session });
    });
    return () => {
      live = false;
    };
  }, [onboarding, attempt, session]);
  return (
    <AppShell storeName={store?.name ?? ""}>
      {onboarding && !store ? (
        state.failed && state.session === session ? (
          <div>
            <p role="alert">가게 정보를 불러오지 못했어요</p>
            <Button
              variant="ghost"
              onClick={() => {
                setState({ store: null, failed: false, session });
                setAttempt((n) => n + 1);
              }}
            >
              다시 불러오기
            </Button>
          </div>
        ) : (
          <p role="status">가게 정보를 불러오는 중</p>
        )
      ) : (
        <FlowStoreContext value={store}>
          {onboarding && <RecipeSaveStatus storeId={store?.id} />}
          {children}
        </FlowStoreContext>
      )}
    </AppShell>
  );
}
