"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { analysisPhase, analysisResult, menuLabels } from "@/lib/analysis";
import { retryOnboardingJob, uploadMenuPhoto } from "@/lib/api";
import type { ApiError, ApiResponse, Job } from "@/lib/types";
import { useJob } from "./useJob";

// 분석 job 폴링 + 메뉴 줄 상태 + 새 job 으로 잇기. 화면을 나가도 job 은 서버에서 계속된다.
export function useAnalysis(jobId: string | null) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [nextError, setNextError] = useState<ApiError | null>(null);
  const { job, error } = useJob(jobId);
  const result = analysisResult(job);
  const menus = result?.menus ?? [];
  const progress = job?.progress ?? 0;
  const failed = job?.status === "FAILED";
  const done = job?.status === "DONE";
  const needsMenuPhoto = done && (result?.needsMenuPhoto ?? false);

  const next = async (make: () => Promise<ApiResponse<Job>>) => {
    setBusy(true);
    const r = await make();
    setBusy(false);
    setNextError(r.success ? null : r.error);
    if (r.success) router.push(`/onboarding/analyze?job=${encodeURIComponent(r.data.id)}`);
  };

  return {
    // 폴링 실패는 useJob 이 계속 재시도하므로 표시만 한다
    error: nextError ?? error,
    progress,
    phase: analysisPhase(job, needsMenuPhoto),
    menus,
    labels: menuLabels(progress, menus, done),
    failed,
    needsMenuPhoto,
    done: done && !needsMenuPhoto,
    busy,
    retry: () => next(retryOnboardingJob),
    upload: (photo: File) => next(() => uploadMenuPhoto(photo)),
  };
}
