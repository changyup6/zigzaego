"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReviewCounts } from "@/lib/types";
import { ReviewBadge } from "./ReviewBadge";
import styles from "./AppNav.module.css";

// 탭 7개 (DECISIONS #61, 거래처 · 재료 분리 S15P31A308-270). 아이콘은 시안 app_all.html 의 ICON 그대로.
// label = 데스크톱 알약 탭, short = 폰 하단 탭 막대.
const TABS = [
  {
    href: "/",
    label: "홈",
    short: "홈",
    count: null,
    icon: (
      <>
        <rect x="3" y="3" width="7" height="9" rx="2" />
        <rect x="14" y="3" width="7" height="5" rx="2" />
        <rect x="14" y="12" width="7" height="9" rx="2" />
        <rect x="3" y="16" width="7" height="5" rx="2" />
      </>
    ),
  },
  {
    href: "/order",
    label: "발주",
    short: "발주",
    count: "order",
    icon: (
      <>
        <path d="M3 7h18l-2 12H5z" />
        <path d="M8 7a4 4 0 0 1 8 0" />
      </>
    ),
  },
  {
    href: "/receiving",
    label: "발주 이력",
    short: "이력",
    count: "receiving",
    icon: <path d="M4 7h16M4 12h16M4 17h10" />,
  },
  {
    href: "/recipes",
    label: "레시피",
    short: "레시피",
    count: "recipes",
    icon: (
      <>
        <path d="M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3z" />
        <path d="M5 17a3 3 0 0 1 3-3h11" />
      </>
    ),
  },
  {
    href: "/suppliers",
    label: "거래처",
    short: "거래처",
    count: null,
    icon: (
      <>
        <path d="M3 10l2-6h14l2 6z" />
        <path d="M4 10v10h16V10" />
        <path d="M10 20v-5h4v5" />
      </>
    ),
  },
  {
    href: "/ingredients",
    label: "재료",
    short: "재료",
    // 재료 확인 필요 개수 (전에는 거래처 및 재료 탭)
    count: "suppliers",
    icon: (
      <>
        <path d="M4 8l8-4 8 4v8l-8 4-8-4z" />
        <path d="M4 8l8 4 8-4M12 12v8" />
      </>
    ),
  },
  {
    href: "/more",
    label: "더보기",
    short: "더보기",
    count: "more",
    icon: (
      <>
        <circle cx="5" cy="12" r="1" />
        <circle cx="12" cy="12" r="1" />
        <circle cx="19" cy="12" r="1" />
      </>
    ),
  },
] as const;

export function AppNav({ counts }: { counts: ReviewCounts }) {
  const path = usePathname();
  return (
    <nav className={styles.nav} aria-label="화면">
      {TABS.map((t) => {
        const current = t.href === "/" ? path === "/" : path.startsWith(t.href);
        const n = t.count ? counts[t.count] : 0;
        return (
          <Link
            key={t.href}
            href={t.href}
            className={styles.tab}
            aria-current={current ? "page" : undefined}
          >
            <svg className={styles.ic} viewBox="0 0 24 24" aria-hidden="true">
              {t.icon}
            </svg>
            <span className={styles.l}>{t.label}</span>
            <span className={styles.s}>{t.short}</span>
            <ReviewBadge count={n} className={styles.dot} />
          </Link>
        );
      })}
    </nav>
  );
}
