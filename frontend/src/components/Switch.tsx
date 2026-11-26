import type { InputHTMLAttributes } from "react";
import styles from "./Switch.module.css";

// 시안 더보기의 켬/끔 줄: 왼쪽 이름, 오른쪽 스위치
export function Switch({
  label,
  ...rest
}: Omit<InputHTMLAttributes<HTMLInputElement>, "type" | "role"> & { label: string }) {
  return (
    <label className={styles.line}>
      {label}
      <input type="checkbox" role="switch" className={styles.switch} {...rest} />
    </label>
  );
}
