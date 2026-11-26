import { ClosingCheck } from "@/components/ClosingCheck";
import { Screen } from "@/components/Screen";

// 마감 알림을 누르면 여기로 온다 (DECISIONS #88)
export default function Page() {
  return (
    <Screen title="마감 확인">
      <ClosingCheck />
    </Screen>
  );
}
