package com.zigzaego.api.onboarding.dto;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 레시피 확인 질문 하나 (객관식 보기 3개). FE {@code RecipeQuestion} 과 같은 모양. */
@Schema(description = "레시피 확인 질문 — 확신이 낮은 재료의 1인분")
public record RecipeQuestionResponse(
		@Schema(description = "질문 id. 답할 때 questionId", example = "q1")
		String id,

		@Schema(description = "분석 결과 안의 재료 id (DB id 가 아니다)", example = "c1")
		String ingredientId,

		@Schema(description = "질문 문구", example = "파스타면 1인분")
		String text,

		@Schema(description = "보기 단위", example = "g")
		RecipeUnit unit,

		@ArraySchema(arraySchema = @Schema(description = "보기 3개, 낮은 순. 가운데가 추정값"),
				schema = @Schema(type = "number", example = "100"), minItems = 3, maxItems = 3)
		List<Number> options
) {
}
