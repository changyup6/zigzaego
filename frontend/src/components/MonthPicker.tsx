"use client";

import { useState } from "react";
import styles from "./MonthPicker.module.css";

const Chev = ({ d }: { d: string }) => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d={d} />
  </svg>
);

// 연월 선택판: ‹ 연도 › + 12개월. 발주 이력 · 발주 달력이 같이 쓴다.
// enabled 가 없으면 모든 달을 고를 수 있다
export function MonthPicker({
  value,
  years,
  enabled,
  onPick,
}: {
  // "YYYY-MM"
  value: string;
  // 고를 수 있는 연도 [처음, 끝]
  years: [number, number];
  enabled?: (ym: string) => boolean;
  onPick: (ym: string) => void;
}) {
  const [year, setYear] = useState(Number(value.slice(0, 4)));
  return (
    <div className={styles.pick}>
      <div className={styles.pickY}>
        <button
          type="button"
          aria-label="지난해"
          disabled={year <= years[0]}
          onClick={() => setYear(year - 1)}
        >
          <Chev d="M15 5l-7 7 7 7" />
        </button>
        <b className="num">{year}년</b>
        <button
          type="button"
          aria-label="다음 해"
          disabled={year >= years[1]}
          onClick={() => setYear(year + 1)}
        >
          <Chev d="M9 6l6 6-6 6" />
        </button>
      </div>
      <div className={styles.pickM}>
        {Array.from({ length: 12 }, (_, k) => {
          const m = `${year}-${String(k + 1).padStart(2, "0")}`;
          return (
            <button
              type="button"
              key={m}
              disabled={enabled ? !enabled(m) : false}
              aria-current={m === value ? "true" : undefined}
              onClick={() => onPick(m)}
            >
              {k + 1}월
            </button>
          );
        })}
      </div>
    </div>
  );
}
