"use client";

import { usePathname, useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";
import { isOnboarded, useStore } from "@/hooks/useStore";
import { useRecipeSave, useRecipeSaveRevision } from "@/hooks/useRecipeSave";
import { RecipeSaveStatus } from "./RecipeSaveStatus";
import { reviewCounts } from "@/lib/setup";
import { AppShell } from "./AppShell";
import { HistoryLoading } from "./HistoryScreen";
import { HomeLoading } from "./HomeTiles";
import { IngredientsLoading } from "./IngredientsTab";
import { OrderLoading } from "./OrderScreen";
import { RecipesLoading } from "./RecipeBook";
import { SuppliersLoading } from "./SuppliersTab";

// 가게 정보를 받기 전에 보일 화면 틀. 화면은 온보딩을 마친 게 확인된 뒤에야 띄운다 (API 를 부르므로)
const LOADING: Record<string, () => ReactNode> = {
  "/": HomeLoading,
  "/order": OrderLoading,
  "/receiving": HistoryLoading,
  "/recipes": RecipesLoading,
  "/suppliers": SuppliersLoading,
  "/ingredients": IngredientsLoading,
};

const headerDate = (ymd: string) =>
  new Intl.DateTimeFormat("ko-KR", {
    month: "long",
    day: "numeric",
    weekday: "long",
    timeZone: "UTC",
  }).format(new Date(ymd));

// 앱 진입 판단: 가게를 불러오고, 온보딩을 안 끝냈으면 온보딩으로 보낸다.
export function AppGate({ today, children }: { today: string; children: ReactNode }) {
  const router = useRouter();
  const path = usePathname();
  const revision = useRecipeSaveRevision();
  const store = useStore(true, revision);
  const { task } = useRecipeSave(store?.id);
  const onboarded = store ? isOnboarded(store) : false;
  useEffect(() => {
    if (store && !onboarded && !task) router.replace("/onboarding/find");
  }, [store, onboarded, task, router]);
  const Loading = LOADING[path];
  return (
    <AppShell
      storeName={store?.name ?? ""}
      today={store?.today ? headerDate(store.today) : today}
      counts={reviewCounts(store)}
    >
      <RecipeSaveStatus storeId={store?.id} />
      {store && onboarded && !task ? children : Loading && <Loading />}
    </AppShell>
  );
}
