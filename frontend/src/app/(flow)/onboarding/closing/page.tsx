import { ClosingTimeStep } from "@/components/ClosingTimeStep";
import { OnboardingStep } from "@/components/OnboardingStep";
import { Screen } from "@/components/Screen";

export default function Page() {
  return (
    <Screen title="마감 시각">
      <OnboardingStep />
      <ClosingTimeStep />
    </Screen>
  );
}
