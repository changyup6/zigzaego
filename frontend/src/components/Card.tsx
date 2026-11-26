import type { ReactNode } from "react";
import styles from "./Card.module.css";

// 시안 .block: 회색 바닥 위 20px 흰 카드, 그림자 없음. 카드 안에 카드를 넣지 않는다.
export function Card({
  id,
  title,
  aside,
  className,
  children,
}: {
  id?: string;
  title?: ReactNode;
  aside?: ReactNode;
  className?: string;
  children?: ReactNode;
}) {
  return (
    <div id={id} className={className ? `${styles.card} ${className}` : styles.card}>
      {title && (
        <div className={styles.head}>
          <h2>{title}</h2>
          {aside}
        </div>
      )}
      {children}
    </div>
  );
}
