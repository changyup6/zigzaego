"use client";

import { useEffect, useState } from "react";
import { getJob } from "@/lib/api";
import { pollJob } from "@/lib/pollJob";
import type { ApiError, Job } from "@/lib/types";

// 2초마다 GET /jobs/{id}. DONE · FAILED 에서 멈추고, 탭이 가려지거나 화면을 나가면 멈췄다가 돌아오면 다시 시작.
export function useJob(id: string | null, fetchJob: typeof getJob = getJob) {
  // id 가 바뀌면 이전 id 의 결과는 버린다 (effect 안 setState 대신 id 를 같이 저장).
  const [s, setS] = useState<{ id: string | null; job: Job | null; error: ApiError | null }>({
    id: null,
    job: null,
    error: null,
  });

  useEffect(() => {
    if (!id) return;
    return pollJob(id, fetchJob, (r) => {
      if (!r.success) setS((p) => ({ id, job: p.id === id ? p.job : null, error: r.error }));
      else setS({ id, job: r.data, error: null });
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- fetchJob 은 화면당 고정
  }, [id]);

  const cur = s.id === id;
  return { job: cur ? s.job : null, error: cur ? s.error : null };
}
