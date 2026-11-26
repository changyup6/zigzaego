"use server";

import { redirect } from "next/navigation";

// 레시피 확인이 끝나면 마감 시각 단계로 (DECISIONS #88), 그다음 지난 영수증 (#66). 온보딩 완료 여부는 GET /stores/me 가 판단한다.
export async function completeOnboarding() {
  redirect("/onboarding/closing");
}
