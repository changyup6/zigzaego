import Image from "next/image";
import Link from "next/link";
import type { ReactNode } from "react";
import type { ReviewCounts } from "@/lib/types";
import logo from "../../public/logo.png";
import wordmark from "../../public/wordmark.png";
import { AppNav } from "./AppNav";
import styles from "./AppShell.module.css";

// 시안 .top: 로고 · 알약 탭 막대 · 날짜. 폰은 탭 막대가 화면 아래에 뜬다.
// counts 가 없으면 flow 모드(로그인 · 회원가입 · 온보딩): 탭을 숨기고 액션 바는 맨 아래.
// today 는 오른쪽 알약 칸 — 탭 화면은 날짜, flow 모드는 비운다.
export function AppShell({
  storeName,
  today,
  counts,
  children,
}: {
  storeName: string;
  today?: ReactNode;
  counts?: ReviewCounts;
  children: ReactNode;
}) {
  return (
    <div className={styles.app} data-mode={counts ? undefined : "flow"}>
      <header className={styles.top}>
        <Link href="/" className={styles.logo} aria-label={`지그재고 홈 ${storeName}`}>
          <Image src={logo} alt="" width={64} className={styles.mark} priority />
          <Image src={wordmark} alt="" width={144} className={styles.wordmark} priority />
        </Link>
        {counts && <AppNav counts={counts} />}
        {today ? <div className={styles.date}>{today}</div> : <span />}
      </header>
      <main className={styles.main}>{children}</main>
    </div>
  );
}
