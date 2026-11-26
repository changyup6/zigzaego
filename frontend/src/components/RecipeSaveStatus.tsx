"use client";

import { useRecipeSave } from "@/hooks/useRecipeSave";
import { Button } from "./Button";

export function RecipeSaveStatus({ storeId }: { storeId?: string }) {
  const { task, retry } = useRecipeSave(storeId);
  if (!task) return null;
  return (
    <div role={task.state === "failed" ? "alert" : "status"}>
      <p>
        {task.state === "failed"
          ? "레시피 저장을 마치지 못했어요. 입력한 답은 보관했어요."
          : "레시피를 저장하고 있어요"}
      </p>
      {task.state === "failed" && (
        <Button small variant="ghost" onClick={() => void retry()}>
          저장 다시 시도
        </Button>
      )}
    </div>
  );
}
