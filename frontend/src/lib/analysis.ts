import type { AnalysisMenu, AnalysisResult, Job } from "./types";

// 수집 단계(0 → 30%)가 끝나면 메뉴 이름이 온다 (설계 2.4)
export const COLLECT_DONE = 0.3;

// 단계 글자 (설계 2.4 진척도 구간, 시안 #analyze)
export function analysisPhase(job: Job | null, needsMenuPhoto: boolean): string {
  const p = job?.progress ?? 0;
  if (job?.status === "FAILED") return "가져오기 실패";
  if (needsMenuPhoto) return "메뉴 0개";
  if (job?.status === "DONE") return "분석 완료";
  if (p < COLLECT_DONE) return "메뉴 가져오는 중";
  if (p < 0.35) return "메뉴 정리 중";
  if (p < 0.9) return "재료 구성 정리 중";
  return "질문 만드는 중";
}

// 전체 진척도 하나를 메뉴 개수로 나눠 어느 메뉴까지 왔는지 정한다 (설계 2.5). 30개면 0~3.3% 가 첫 메뉴.
// 재료 구성은 DONE 에서 한꺼번에 채운다.
export function menuLabels(progress: number, menus: AnalysisMenu[], done: boolean): string[] {
  return menus.map((m, i) => {
    if (done) return `재료 ${m.ingredientCount ?? 0}개`;
    const f = progress * menus.length - i;
    return f >= 1 ? "" : f > 0 ? "정리 중" : "대기";
  });
}

export function analysisResult(job: Job | null): AnalysisResult | null {
  const r = job?.result as Partial<AnalysisResult> | undefined;
  return r && Array.isArray(r.menus) ? { menus: r.menus, needsMenuPhoto: r.needsMenuPhoto } : null;
}
