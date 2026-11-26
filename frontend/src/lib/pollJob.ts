import type { getJob } from "@/lib/api";
import type { Job, ApiResponse } from "@/lib/types";

export const POLL_INTERVAL = 2000;

// 2초마다 조회. DONE · FAILED 에서 멈추고, 탭이 가려지면 멈췄다가 다시 보이면 재시작. 반환 함수는 완전 정지.
export function pollJob(
  id: string,
  fetchJob: typeof getJob,
  onResult: (r: ApiResponse<Job>) => void,
): () => void {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let run = 0; // 실행 토큰: 바뀌면 이전 루프는 await 이후 스스로 종료
  let finished = false;
  const stop = () => {
    run++;
    clearTimeout(timer);
  };
  const tick = async () => {
    const my = run;
    const r = await fetchJob(id);
    if (my !== run || document.visibilityState !== "visible") return;
    onResult(r);
    if (r.success && (r.data.status === "DONE" || r.data.status === "FAILED")) {
      finished = true;
      return;
    }
    timer = setTimeout(tick, POLL_INTERVAL);
  };
  const onVisibility = () => {
    stop();
    if (!finished && document.visibilityState === "visible") tick();
  };
  document.addEventListener("visibilitychange", onVisibility);
  if (document.visibilityState === "visible") tick();
  return () => {
    stop();
    document.removeEventListener("visibilitychange", onVisibility);
  };
}
