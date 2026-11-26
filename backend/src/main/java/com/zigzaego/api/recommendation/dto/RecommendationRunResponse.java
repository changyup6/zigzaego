package com.zigzaego.api.recommendation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 추천 생성 1회의 결과. 그날 추천 전체(이미 있던 건 포함)를 함께 돌려준다. */
@Schema(description = "추천 생성 결과")
public record RecommendationRunResponse(
		@Schema(description = "생성 기준일 (YYYY-MM-DD)", example = "2026-10-10", type = "string", format = "date")
		String runDate,

		@Schema(description = "이번 실행이 새로 만든 추천 수. 그날 끝난 실행이 있어 계산을 건너뛰었으면 0", example = "3")
		int createdCount,

		@Schema(description = "이미 있어 그대로 둔 추천 수 (같은 날 두 번 실행)", example = "0")
		int existingCount,

		@Schema(description = "계산은 했지만 발주가 필요 없어 만들지 않은 재료 수. 계산을 건너뛰었으면 0", example = "7")
		int noOrderCount,

		@Schema(description = "그날 추천 전체. 재료 id 오름차순")
		List<RecommendationResponse> recommendations
) {
}
