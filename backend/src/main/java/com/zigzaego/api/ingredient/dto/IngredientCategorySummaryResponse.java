package com.zigzaego.api.ingredient.dto;

import com.zigzaego.api.ingredient.entity.DefaultIngredientCategory;
import com.zigzaego.api.ingredient.entity.IngredientCategory;
import io.swagger.v3.oas.annotations.media.Schema;

/** 재료 응답에 붙는 카테고리 (S15P31A308-273). 목록 · 고치기 API 는 S15P31A308-275. */
@Schema(description = "재료 카테고리")
public record IngredientCategorySummaryResponse(
		@Schema(description = "카테고리 id (문자열)", example = "12")
		String id,

		@Schema(description = "카테고리 이름. 기본 11종(육류 · 수산 · 채소 · 과일 · 계란·유제품 · 곡물·면·떡 · 두부·콩 · 양념·소스 · 가공식품 · 음료 · 기타)"
				+ " 또는 사장님이 만든 이름", example = "기타")
		String name,

		@Schema(description = "기본 카테고리 코드. 이름을 바꿔도 그대로라 '기타'(OTHER) 판별 · 아이콘 매핑은 이 값으로. "
				+ "사장님이 만든 카테고리는 null", example = "OTHER", nullable = true)
		DefaultIngredientCategory code
) {

	public static IngredientCategorySummaryResponse of(IngredientCategory category) {
		return new IngredientCategorySummaryResponse(String.valueOf(category.getId()), category.getName(), category.getCode());
	}
}
