package com.zigzaego.api.recipe.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** 사장님이 레시피 줄을 직접 넣는다 (source=MANUAL). */
@Schema(description = "레시피 줄 만들기")
public record RecipeCreateRequest(
		@Schema(description = "메뉴 id. 다른 가게 메뉴면 404", example = "7")
		@NotBlank
		String menuId,

		@Schema(description = "재료 id. 다른 가게 재료면 404. 주기형 재료면 409 RECIPE_CYCLE_INGREDIENT", example = "3")
		@NotBlank
		String ingredientId,

		@Schema(description = "1인분 양(재료 기준 단위, 소수 3자리까지)", example = "150")
		@NotNull @Positive @Digits(integer = 10, fraction = 3)
		BigDecimal qtyPerServing
) {
}
