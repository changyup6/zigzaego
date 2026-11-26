"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { getStoreMe, putClosingTimes } from "@/lib/api";
import { Button } from "./Button";
import { ClosingTimeForm } from "./ClosingTimeForm";

const NEXT = "/onboarding/receipts";

// 저장된 영업시간이 있으면 건너뛰고, 조회 실패는 입력 없음과 구분한다.
export function ClosingTimeStep() {
  const router = useRouter();
  const [state, setState] = useState<"loading" | "empty" | "error" | "saved">("loading");
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let live = true;
    getStoreMe().then((r) => {
      if (!live) return;
      if (!r.success) return setState("error");
      if (r.data.closingTimes?.length) {
        setState("saved");
        router.replace(NEXT);
      } else setState("empty");
    });
    return () => {
      live = false;
    };
  }, [attempt, router]);
  if (state === "error")
    return (
      <div>
        <p role="alert">마감 시각을 불러오지 못했어요</p>
        <Button
          variant="ghost"
          onClick={() => {
            setState("loading");
            setAttempt((n) => n + 1);
          }}
        >
          다시 불러오기
        </Button>
      </div>
    );
  if (state !== "empty") return <p role="status">마감 시각을 불러오는 중</p>;
  return (
    <ClosingTimeForm
      onSave={async (times) => {
        const r = await putClosingTimes(times);
        if (r.success) router.push(NEXT);
        return r.success;
      }}
    />
  );
}
