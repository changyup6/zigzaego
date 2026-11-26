"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import styles from "./Toast.module.css";

// 화면이 바뀌지 않는 행동(복사 등)에만 쓴다. 2.4초 뒤 사라진다
export function useToast() {
  const [msg, setMsg] = useState("");
  const [on, setOn] = useState(false);
  const timer = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(timer.current), []);

  const show = useCallback((text: string) => {
    setMsg(text);
    setOn(true);
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setOn(false), 2400);
  }, []);

  const toast = (
    <div
      className={on ? `${styles.toast} ${styles.on}` : styles.toast}
      role="status"
      aria-live="polite"
    >
      {msg}
    </div>
  );
  return { toast, show };
}
