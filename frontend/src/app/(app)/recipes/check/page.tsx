import { RecipeQuestions } from "@/components/RecipeQuestions";
import { Screen } from "@/components/Screen";

// 가게 설정 '레시피 확인': 답하지 않은 질문만. 온보딩 "N / 3" 셸 밖이다.
export default function Page() {
  return (
    <Screen title="레시피 확인">
      <RecipeQuestions unansweredOnly />
    </Screen>
  );
}
