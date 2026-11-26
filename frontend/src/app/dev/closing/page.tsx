"use client";

import { AppShell } from "@/components/AppShell";
import { ClosingCheck } from "@/components/ClosingCheck";
import { Screen } from "@/components/Screen";

// 로그인 없이 보는 마감 확인. /dev/closing?demo
export default function Page() {
  return (
    <AppShell storeName="">
      <Screen title="마감 확인">
        <ClosingCheck />
      </Screen>
    </AppShell>
  );
}
