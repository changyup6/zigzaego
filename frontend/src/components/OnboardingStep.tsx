"use client";

import { usePathname } from "next/navigation";
import styles from "./OnboardingStep.module.css";

const STEPS = ["find", "analyze", "recipes", "closing", "receipts"];

// 온보딩 화면 제목 아래 5칸 진행 막대. 지금 단계까지 채운다.
export function OnboardingStep() {
  const [, root, step] = usePathname().split("/");
  const n = root === "onboarding" ? STEPS.indexOf(step) + 1 : 0;
  return (
    n > 0 && (
      <div
        className={styles.bar}
        role="progressbar"
        aria-label="온보딩 단계"
        aria-valuemin={1}
        aria-valuemax={STEPS.length}
        aria-valuenow={n}
      >
        {STEPS.map((s, i) => (
          <span key={s} className={i < n ? styles.done : undefined} />
        ))}
      </div>
    )
  );
}
