import type { ReactNode } from "react";
import styles from "./ReviewRow.module.css";

// 확인 필요 (DECISIONS #44): 행은 다른 행과 같은 모양. 그 칸만 표시하고 딱지 글자는 붙이지 않는다.
// 해결하면 needsReview 가 false 가 되어 테두리 · 바탕이 함께 사라진다.

// 값 칸
export function ReviewCell({
  needsReview,
  children,
}: {
  needsReview: boolean;
  children: ReactNode;
}) {
  return <span className={needsReview ? styles.cell : undefined}>{children}</span>;
}

// 표 칸(td) · 입력 · 셀렉트에 붙이는 클래스
export const reviewClass = (needsReview: boolean, kind: "td" | "field"): string | undefined =>
  needsReview ? styles[kind] : undefined;
