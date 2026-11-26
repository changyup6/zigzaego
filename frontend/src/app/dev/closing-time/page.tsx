"use client";

import { AppShell } from "@/components/AppShell";
import { ClosingTimeForm } from "@/components/ClosingTimeForm";
import { Screen } from "@/components/Screen";

// 로그인 없이 보는 온보딩 마감 시각. /dev/closing-time
export default function Page() {
  return (
    <AppShell storeName="">
      <Screen title="마감 시각">
        <ClosingTimeForm onSave={async () => true} />
      </Screen>
    </AppShell>
  );
}
