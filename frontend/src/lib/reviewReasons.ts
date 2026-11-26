import type { Reviewable } from "@/lib/types";

// 해결해도 행은 제자리에 둔다 (DECISIONS #44: 행 순서 · 바탕은 확인 필요로 바뀌지 않는다).
export const resolveReviewRow = <T extends Reviewable>(rows: T[], id: string): T[] =>
  rows.map((r) => (r.id === id ? { ...r, needsReview: false, reasons: [] } : r));

export const reviewCount = (rows: Reviewable[]): number => rows.filter((r) => r.needsReview).length;

