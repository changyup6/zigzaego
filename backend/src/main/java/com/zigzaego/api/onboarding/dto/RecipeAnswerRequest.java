package com.zigzaego.api.onboarding.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** 질문 하나의 답. FE {@code RecipeAnswer} 와 같은 모양. 보기에 없는 값 · 다른 단위도 받는다. */
@Schema(description = "레시피 확인 답")
public record RecipeAnswerRequest(
		@Schema(description = "질문 id", example = "q1")
		String questionId,

		@Schema(description = "1인분 양. 0보다 커야 한다", example = "180")
		BigDecimal value,

		@Schema(description = "답한 단위. 재료 단위와 다르면 재료 단위를 이 단위로 바꾼다", example = "g")
		RecipeUnit unit
) {
}
