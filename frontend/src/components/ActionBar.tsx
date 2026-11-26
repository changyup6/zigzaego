import type { ButtonHTMLAttributes, ReactNode } from "react";
import { Button } from "./Button";
import styles from "./ActionBar.module.css";

// 시안 .actbar: 화면 아래 떠 있는 어두운 알약. lead 가 있으면 왼쪽 합계 + 오른쪽 버튼, 없으면 알약 없이 버튼 하나(.solo)
export function ActionBar({
  lead,
  ...props
}: Omit<ButtonHTMLAttributes<HTMLButtonElement>, "className"> & { lead?: ReactNode }) {
  return (
    <div className={lead ? styles.actbar : `${styles.actbar} ${styles.solo}`}>
      {lead && <span className={styles.lead}>{lead}</span>}
      <Button className={styles.btn} {...props} />
    </div>
  );
}
