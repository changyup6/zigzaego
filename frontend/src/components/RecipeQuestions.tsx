"use client";

import { useState } from "react";
import { useRecipeQuestions } from "@/hooks/useRecipeQuestions";
import { useRecipeQuestionData } from "@/hooks/useRecipeQuestionData";
import { useFlowStore } from "@/hooks/FlowStoreContext";
import { useStore } from "@/hooks/useStore";
import type { RecipeAnswer, RecipeQuestion, RecipeUnit } from "@/lib/types";
import { Button } from "./Button";
import { Card } from "./Card";
import { Num } from "./Num";
import { Skeleton } from "./Skeleton";
import styles from "./RecipeQuestions.module.css";

const UNITS: RecipeUnit[] = ["g", "ml", "개"];

// 시안 #question: 질문 카드 한 장 → 건너뛰기
// unansweredOnly: 가게 설정에서 들어오면 답하지 않은 질문만
export function RecipeQuestions({ unansweredOnly = false }: { unansweredOnly?: boolean }) {
  const data = useRecipeQuestionData(unansweredOnly);
  const flowStore = useFlowStore();
  const ownStore = useStore(!flowStore);
  const store = flowStore ?? ownStore;
  if (data.error)
    return (
      <div>
        <p role="alert" className={styles.error}>
          {data.error.message}
        </p>
        <Button variant="ghost" onClick={data.retry}>
          다시 불러오기
        </Button>
      </div>
    );
  if (!data.questions || !store) return <QuestionsLoading />;
  return (
    <Questions key={store.id} questions={data.questions} prior={data.prior} storeId={store.id} />
  );
}

function QuestionsLoading() {
  return (
    <div data-testid="recipe-questions-loading" aria-busy="true" aria-label="메뉴 목록 불러오는 중">
      <Card>
        <Skeleton w="8em" />
        <div className={styles.title}>
          <Skeleton w="60%" h="1em" />
        </div>
        <div className={styles.opts}>
          {[0, 1, 2].map((n) => (
            <Skeleton key={n} h="var(--opt-h)" r="var(--r-card)" />
          ))}
        </div>
        <div className={styles.end}>
          <Skeleton w="7em" h={44} r="var(--r-pill)" />
        </div>
      </Card>
    </div>
  );
}

function Questions({
  questions,
  prior,
  storeId,
}: {
  questions: RecipeQuestion[];
  prior: RecipeAnswer[];
  storeId: string;
}) {
  const r = useRecipeQuestions(questions, prior, storeId);

  return (
    <>
      {questions.length === 0 && <p>확인할 레시피가 없어요</p>}
      {r.question && (
        <QuestionCard
          key={r.question.id}
          q={r.question}
          index={r.index}
          total={r.total}
          busy={r.busy}
          onQty={r.answerQty}
        />
      )}
      <div className={styles.end}>
        <Button variant="quiet" disabled={r.busy} onClick={r.skip}>
          건너뛰기
        </Button>
      </div>
    </>
  );
}

function QuestionCard({
  q,
  index,
  total,
  busy,
  onQty,
}: {
  q: RecipeQuestion;
  index: number;
  total: number;
  busy: boolean;
  onQty: (value: number, unit: RecipeUnit) => void;
}) {
  const [other, setOther] = useState(false);
  const [value, setValue] = useState("");
  const [unit, setUnit] = useState<RecipeUnit>(q.unit);

  return (
    <Card>
      <div className={styles.count}>
        질문 <b>{index + 1}</b> / {total}
      </div>
      <h2 className={styles.title}>{q.text}</h2>
      <div className={styles.opts}>
        {q.options.map((o) => (
          <button
            key={o}
            type="button"
            className={styles.opt}
            disabled={busy}
            onClick={() => onQty(o, q.unit)}
          >
            <Num size={40} value={o.toLocaleString("ko-KR")} unit={q.unit} />
          </button>
        ))}
      </div>
      {other && (
        <form
          className={styles.other}
          onSubmit={(e) => {
            e.preventDefault();
            const v = parseFloat(value);
            const input = e.currentTarget.elements.namedItem("qty");
            if (Number.isNaN(v) || v < 0) {
              if (input instanceof HTMLInputElement) input.focus();
              return;
            }
            onQty(v, unit);
          }}
        >
          <input
            name="qty"
            className={`${styles.qtyIn} num n17`}
            type="number"
            inputMode="decimal"
            min={0}
            step="any"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            aria-label="1인분 양"
            autoFocus
          />
          <select
            className={styles.sel}
            value={unit}
            onChange={(e) => setUnit(e.target.value as RecipeUnit)}
            aria-label="단위"
          >
            {UNITS.map((u) => (
              <option key={u}>{u}</option>
            ))}
          </select>
          <Button type="submit" disabled={busy}>
            확인
          </Button>
        </form>
      )}
      {!other && (
        <Button
          variant="ghost"
          block
          className={styles.more}
          disabled={busy}
          onClick={() => setOther(true)}
        >
          직접 입력
        </Button>
      )}
    </Card>
  );
}
