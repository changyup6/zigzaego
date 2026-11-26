import { RecipeQuestions } from "@/components/RecipeQuestions";
import { OnboardingStep } from "@/components/OnboardingStep";
import { Screen } from "@/components/Screen";

export default function Page() {
  return (
    <Screen title="레시피 확인">
      <OnboardingStep />
      <RecipeQuestions />
    </Screen>
  );
}
