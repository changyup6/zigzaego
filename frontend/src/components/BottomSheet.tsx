"use client";

import { useEffect, useId, useRef, type ReactNode } from "react";
import styles from "./BottomSheet.module.css";

// 네이티브 <dialog>: role=dialog · aria-modal · Escape · 포커스 가두기는 브라우저가 한다.
export function BottomSheet({
  open,
  title,
  onClose,
  children,
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  // 렌더마다 open 에 맞춘다: 부모가 닫기를 막았는데(저장 중 · 실패) 브라우저가 닫았으면 다시 연다
  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
  });

  return (
    <dialog
      ref={ref}
      className={styles.sheet}
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
      onClose={onClose}
      // Escape 도 X 처럼 부모가 닫을지 정한다
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className={styles.in}>
        <div className={styles.grab} aria-hidden="true" />
        <div className={styles.head}>
          <h2 id={titleId}>{title}</h2>
          <button type="button" className={styles.close} aria-label="닫기" onClick={onClose}>
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>
        {children}
      </div>
    </dialog>
  );
}
