import { Suspense, type ReactNode } from "react";
import { connection } from "next/server";
import { AppGate } from "@/components/AppGate";

// 요청 시각을 읽는 부분은 Suspense 안에 둔다 (Cache Components).
async function Gate({ children }: { children: ReactNode }) {
  // 오늘 날짜는 요청 시각 기준 (가게는 한국)
  await connection();
  const today = new Intl.DateTimeFormat("ko-KR", {
    month: "long",
    day: "numeric",
    weekday: "long",
    timeZone: "Asia/Seoul",
  }).format(new Date());
  return <AppGate today={today}>{children}</AppGate>;
}

export default function AppLayout({ children }: LayoutProps<"/">) {
  return (
    <Suspense>
      <Gate>{children}</Gate>
    </Suspense>
  );
}
