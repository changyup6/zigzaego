import { PlaceFinder } from "@/components/PlaceFinder";
import { OnboardingStep } from "@/components/OnboardingStep";
import { Screen } from "@/components/Screen";

export default function Page() {
  return (
    <Screen title="가게 찾기">
      <OnboardingStep />
      <PlaceFinder />
    </Screen>
  );
}
