"use client";

import { useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { saveRecipesLater } from "./useRecipeSave";
import type { RecipeAnswer, RecipeQuestion, RecipeUnit } from "@/lib/types";

// 질문을 한 장씩 답한다. 마지막 답 · 건너뛰기는 같은 끝:
// 답한 것만 confirm(답이 없으면 skip) → 지난 영수증 단계. 답하지 않은 재료 수는 가게 설정 '레시피 확인'으로 남는다.
// prior = 가게 설정에서 이미 답한 값. confirm 은 다시 저장하므로 함께 보낸다.
export function useRecipeQuestions(
  questions: RecipeQuestion[],
  prior: RecipeAnswer[],
  storeId: string,
) {
  const router = useRouter();
  const finishing = useRef(false);
  const [answers, setAnswers] = useState<RecipeAnswer[]>([]);
  // 답한 질문
  const [done, setDone] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  const pending = questions.filter((q) => !done.includes(q.id));
  const question = pending[0] ?? null;

  // estimated = 답하지 않고 남긴 질문 수
  const finish = (all: RecipeAnswer[], estimated: number) => {
    if (finishing.current) return;
    finishing.current = true;
    setBusy(true);
    saveRecipesLater(storeId, [...prior, ...all], estimated);
    router.push("/onboarding/closing");
  };

  const next = (a: RecipeAnswer) => {
    if (!question || busy) return;
    const all = [...answers, a];
    const ids = [...done, question.id];
    setAnswers(all);
    setDone(ids);
    if (ids.length === questions.length) finish(all, 0);
  };

  return {
    question,
    index: questions.length - pending.length,
    total: questions.length,
    busy,
    answerQty: (value: number, unit: RecipeUnit) =>
      question && next({ questionId: question.id, value, unit }),
    skip: () => finish(answers, pending.length),
  };
}
