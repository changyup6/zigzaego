import styles from "./ReviewBadge.module.css";

export function ReviewBadge({ count, className }: { count: number; className?: string }) {
  if (count <= 0) return null;
  return (
    <span className={`${styles.badge} ${className ?? ""}`} aria-label={`확인 필요 ${count}`}>
      {count}
    </span>
  );
}
