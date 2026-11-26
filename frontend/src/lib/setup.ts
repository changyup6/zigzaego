import type { ReviewCounts, SetupStep, StoreMe } from "./types";

// 가게 설정 단계 (GET /stores/me checklist → 더보기 카드). 온보딩은 앱에 들어온 순간 끝났고,
// 첫 입고 확인은 화면에서 뺐다 (DECISIONS #62)
const STEPS: Record<string, Omit<SetupStep, "id" | "done">> = {
  RECIPE: { name: "레시피 확인", where: "레시피", href: "/recipes" },
  POS: { name: "POS 연결", where: "더보기", href: "/more#pos" },
  SUPPLIER: { name: "거래처 등록", where: "거래처", href: "/suppliers" },
  STOCK_COUNT: { name: "재고 확인", where: "재료", href: "/ingredients" },
};

export const setupSteps = (store: Pick<StoreMe, "checklist">): SetupStep[] =>
  store.checklist.flatMap((c) =>
    STEPS[c.step] ? [{ id: c.step, done: c.done, ...STEPS[c.step] }] : [],
  );

// 탭 배지 (GET /stores/me checklist). 레시피 = 답하지 않은 질문 수, 재료 = 재고 확인 단계.
// checklist 에 없는 탭은 0. 가게를 받기 전(null)도 0
export const reviewCounts = (store: Pick<StoreMe, "checklist"> | null): ReviewCounts => {
  const left = (step: string) => store?.checklist.find((c) => c.step === step)?.remaining ?? 0;
  return {
    order: 0,
    receiving: 0,
    recipes: left("RECIPE"),
    suppliers: left("STOCK_COUNT"),
    more: 0,
  };
};
