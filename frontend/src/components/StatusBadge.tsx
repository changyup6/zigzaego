import type { ReactNode } from "react";
import styles from "./StatusBadge.module.css";

// 행 상태 글자 (DESIGN.md): 발주 필요=red, 곧 소진=amber, 승인=ok, 보류=muted
export type StatusTone = "red" | "amber" | "ok" | "muted";

export function StatusBadge({ tone, children }: { tone: StatusTone; children: ReactNode }) {
  return (
    <span className={`${styles.badge} ${styles[tone]}`}>
      {tone === "ok" && (
        <svg className={styles.ic} viewBox="0 0 24 24" aria-hidden="true">
          <path d="M5 12.5l4.5 4.5L19 7" />
        </svg>
      )}
      {children}
    </span>
  );
}
