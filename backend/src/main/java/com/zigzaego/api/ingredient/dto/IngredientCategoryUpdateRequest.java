package com.zigzaego.api.ingredient.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 보낸 칸만 바꾼다. null 이거나 빠진 칸은 그대로 둔다 (S15P31A308-275). */
@Schema(description = "재료 카테고리 고치기 — 보낸 칸만 바뀐다")
public record IngredientCategoryUpdateRequest(
		@Schema(description = "새 이름. '기타'의 이름을 바꾸면 400 INGREDIENT_CATEGORY_OTHER_IMMUTABLE, 같은 이름이 있으면 409",
				example = "수제 소스", nullable = true, maxLength = 30)
		@Size(min = 1, max = 30) @Pattern(regexp = ".*\\S.*", message = "공백만으로 된 이름은 쓸 수 없습니다.")
		String name,

		@Schema(description = "화면 순서(오름차순, 0~98). 기본 11종은 1~10, 기타는 99 고정이라 언제나 맨 뒤", example = "12", nullable = true)
		@PositiveOrZero @Max(IngredientCategoryUpdateRequest.MAX_SORT_ORDER)
		Integer sortOrder
) {

	/** 기타(99) 앞까지만. */
	public static final int MAX_SORT_ORDER = 98;
}
