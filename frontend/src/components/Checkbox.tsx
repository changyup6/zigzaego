import type { InputHTMLAttributes, ReactNode } from "react";
import styles from "./Checkbox.module.css";

export function Checkbox({
  children,
  className,
  ...rest
}: Omit<InputHTMLAttributes<HTMLInputElement>, "type"> & { children: ReactNode }) {
  // className 은 줄(label)에 붙는다. 시안 .term--all 같은 변형용
  return (
    <label className={className ? `${styles.term} ${className}` : styles.term}>
      <input type="checkbox" className={styles.cbox} {...rest} />
      {children}
    </label>
  );
}
