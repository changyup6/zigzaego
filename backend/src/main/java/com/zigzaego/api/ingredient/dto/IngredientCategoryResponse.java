package com.zigzaego.api.ingredient.dto;

import com.zigzaego.api.ingredient.entity.DefaultIngredientCategory;
import com.zigzaego.api.ingredient.entity.IngredientCategory;
import io.swagger.v3.oas.annotations.media.Schema;

/** 카테고리 목록 · 만들기 · 고치기 응답 (S15P31A308-275). 재료 응답에 붙는 요약은 {@link IngredientCategorySummaryResponse}. */
@Schema(description = "재료 카테고리")
public record IngredientCategoryResponse(
		@Schema(description = "카테고리 id (문자열)", example = "12")
		String id,

		@Schema(description = "이름", example = "육류")
		String name,

		@Schema(description = "기본 카테고리 코드. 사장님이 만든 카테고리는 null. OTHER 가 '기타'(이름 변경 · 삭제 불가)",
				example = "MEAT", nullable = true)
		DefaultIngredientCategory code,

		@Schema(description = "화면 순서(오름차순)", example = "1")
		int sortOrder,

		@Schema(description = "이 카테고리에 든 재료 수", example = "7")
		long ingredientCount
) {

	public static IngredientCategoryResponse of(IngredientCategory category, long ingredientCount) {
		return new IngredientCategoryResponse(String.valueOf(category.getId()), category.getName(), category.getCode(),
				category.getSortOrder(), ingredientCount);
	}
}
