import type { ReactNode } from "react";
import { Num } from "./Num";
import styles from "./Stepper.module.css";

type Props = {
  value: number;
  /** 포장 단위. 한 번 누를 때 이만큼 오르내린다 */
  step: number;
  unit?: string;
  min?: number;
  max?: number;
  onChange: (next: number) => void;
  /** 오른쪽 메타 (예: `박스 × 2 · 24,000원`) */
  note?: ReactNode;
  /** 메모를 다음 줄 전폭으로 (데스크톱 상세 카드) */
  noteFull?: boolean;
};

const Icon = ({ plus }: { plus?: boolean }) => (
  <svg viewBox="0 0 24 24" className={styles.icon} aria-hidden="true">
    <path d={plus ? "M12 6v12M6 12h12" : "M6 12h12"} />
  </svg>
);

export function Stepper({ value, step, unit, min = 0, max, onChange, note, noteFull }: Props) {
  const down = Math.max(min, value - step);
  const up = max === undefined ? value + step : Math.min(max, value + step);
  return (
    <div className={styles.stepper}>
      <button
        type="button"
        aria-label={`${step}${unit ?? ""} 줄이기`}
        disabled={value <= min}
        onClick={() => onChange(down)}
      >
        <Icon />
      </button>
      <span className={styles.val}>
        <Num size={24} value={value} unit={unit} />
      </span>
      <button
        type="button"
        aria-label={`${step}${unit ?? ""} 늘리기`}
        disabled={max !== undefined && value >= max}
        onClick={() => onChange(up)}
      >
        <Icon plus />
      </button>
      {note && (
        <span className={noteFull ? `${styles.note} ${styles.full}` : styles.note}>{note}</span>
      )}
    </div>
  );
}
