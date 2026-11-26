import styles from "./ProgressBar.module.css";

// 타임라인 트랙 모양 (DESIGN.md): paper-2 알약 위에 잉크 막대, 모자라면 red
export function ProgressBar({
  value,
  max = 1,
  short,
  lg,
  label,
}: {
  value: number;
  max?: number;
  short?: boolean;
  // 시안 .bar--lg: 전체 진척도 한 줄
  lg?: boolean;
  label: string;
}) {
  const pct = Math.min(100, Math.max(0, (value / max) * 100));
  return (
    <div
      className={lg ? `${styles.track} ${styles.lg}` : styles.track}
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={value}
    >
      <div
        className={`${styles.fill} ${short ? styles.short : ""}`}
        style={{ transform: `scaleX(${pct / 100})` }}
      />
    </div>
  );
}
