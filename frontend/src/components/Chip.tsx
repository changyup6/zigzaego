import type { ButtonHTMLAttributes, ReactNode } from "react";
import styles from "./Chip.module.css";

// 필터 칩(선택 가능, aria-pressed)과 태그(정적 라벨) 두 모양
export function Chip({
  selected,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { selected: boolean }) {
  return <button type="button" className={styles.chip} aria-pressed={selected} {...rest} />;
}

export const Tag = ({ children }: { children: ReactNode }) => (
  <span className={styles.tag}>{children}</span>
);
