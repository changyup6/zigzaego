package com.zigzaego.api.ingredient.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** {@code POST /ingredient-categories/auto-assign} 결과 (S15P31A308-274). */
@Schema(description = "기존 재료 일괄 자동 분류 결과")
public record AutoAssignResultResponse(
		@Schema(description = "이번에 기타에서 다른 카테고리로 옮긴 재료 수", example = "12")
		int assignedCount,

		@Schema(description = "분류한 뒤에도 기타로 남은 재료 수 (사장님이 직접 기타로 고른 재료 포함)", example = "3")
		int otherCount
) {
}
