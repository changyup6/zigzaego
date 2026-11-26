import type { ReactNode } from "react";
import styles from "./Field.module.css";

// 라벨 위 입력 · 셀렉트. children 에 <input> 이나 <select> 를 넣는다
export function Field({ label, children }: { label: ReactNode; children: ReactNode }) {
  return (
    <label className={styles.field}>
      <span>{label}</span>
      {children}
    </label>
  );
}

export const FieldGrid = ({ children }: { children: ReactNode }) => (
  <div className={styles.grid2}>{children}</div>
);
