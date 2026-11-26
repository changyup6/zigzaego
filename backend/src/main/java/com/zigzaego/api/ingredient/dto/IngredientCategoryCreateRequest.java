package com.zigzaego.api.ingredient.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 사장님이 카테고리를 만든다 (S15P31A308-275). 화면 순서는 기본 11종 뒤 · 기타 앞. */
@Schema(description = "재료 카테고리 만들기")
public record IngredientCategoryCreateRequest(
		@Schema(description = "카테고리 이름. 가게 안에서 겹치지 않는다(공백을 빼고 견준다). 앞뒤 공백은 잘라 저장",
				example = "디저트", maxLength = 30)
		@NotBlank @Size(max = 30)
		String name
) {
}
