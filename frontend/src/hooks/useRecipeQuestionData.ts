"use client";

import { useEffect, useState } from "react";
import { getRecipeQuestions, getSavedRecipes } from "@/lib/api";
import { splitAnswered } from "@/lib/recipes";
import type { ApiError, RecipeAnswer, RecipeQuestion } from "@/lib/types";

type Data = { questions: RecipeQuestion[] | null; prior: RecipeAnswer[]; error: ApiError | null };

// GET /onboarding/recipe-questions. 가게 설정에서 들어오면 GET /onboarding/recipes 로 이미 답한 질문을 뺀다.
export function useRecipeQuestionData(unansweredOnly: boolean) {
  const [data, setData] = useState<Data>({ questions: null, prior: [], error: null });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let live = true;
    (async () => {
      const q = await getRecipeQuestions();
      if (!q.success) return live && setData({ questions: null, prior: [], error: q.error });
      if (!unansweredOnly) return live && setData({ questions: q.data, prior: [], error: null });
      const b = await getSavedRecipes();
      if (!b.success) return live && setData({ questions: null, prior: [], error: b.error });
      const { pending, prior } = splitAnswered(q.data, b.data.ingredients);
      if (live) setData({ questions: pending, prior, error: null });
    })();
    return () => {
      live = false;
    };
  }, [unansweredOnly, attempt]);

  return {
    ...data,
    retry: () => {
      setData({ questions: null, prior: [], error: null });
      setAttempt((n) => n + 1);
    },
  };
}
