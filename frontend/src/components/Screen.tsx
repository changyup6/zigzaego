import type { ReactNode } from "react";
import styles from "./Screen.module.css";

export function Screen({
  title,
  aside,
  fitViewport,
  children,
}: {
  // 없으면 머리줄을 그리지 않는다 (레시피 탭의 메뉴 상세)
  title?: ReactNode;
  // 제목 바로 오른쪽 (재료 탭의 그림/표 전환 · 검색)
  aside?: ReactNode;
  children?: ReactNode;
  // 재료 탭만 데스크톱의 남은 viewport 높이를 사용한다.
  fitViewport?: boolean;
}) {
  return (
    <section className={styles.screen} data-fit-viewport={fitViewport || undefined}>
      {title && (
        <div className={styles.head}>
          <h1 className={aside ? `${styles.title} ${styles.fit}` : styles.title}>{title}</h1>
          {aside && <div className={styles.aside}>{aside}</div>}
        </div>
      )}
      {children}
    </section>
  );
}
