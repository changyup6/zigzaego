"use client";

import { useEffect, useRef, type ReactNode } from "react";
import styles from "./Modal.module.css";

// 가운데 모달 (재료 · 거래처 상세, S15P31A308-270). BottomSheet 처럼 네이티브 <dialog> 라
// Escape · 포커스 가두기는 브라우저가 한다. 머리줄(제목 · ✕)은 스크롤해도 위에 붙어 있다.
export function Modal({
  open,
  label,
  head,
  onClose,
  children,
}: {
  open: boolean;
  // 스크린리더용 이름
  label: string;
  // 머리줄 왼쪽 (제목, 고치는 중이면 입력칸)
  head: ReactNode;
  onClose: () => void;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const d = ref.current;
    if (!d) return;
    if (open && !d.open) d.showModal();
    if (!open && d.open) d.close();
    // 뒤 화면이 같이 스크롤되지 않게
    document.documentElement.style.overflow = open ? "hidden" : "";
    return () => {
      document.documentElement.style.overflow = "";
    };
  }, [open]);

  return (
    <dialog
      ref={ref}
      className={styles.modal}
      aria-label={label}
      onClose={onClose}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className={styles.head}>
        {head}
        <button type="button" className={styles.close} aria-label="닫기" onClick={onClose}>
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
      </div>
      <div className={styles.body}>{children}</div>
    </dialog>
  );
}
