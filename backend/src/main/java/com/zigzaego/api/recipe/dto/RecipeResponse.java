package com.zigzaego.api.recipe.dto;

import com.zigzaego.api.global.review.ReviewReason;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.recipe.entity.Recipe;
import com.zigzaego.api.recipe.entity.RecipeSource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 레시피 한 줄 (메뉴 1인분에 드는 재료 하나). */
@Schema(description = "레시피 한 줄")
public record RecipeResponse(
		@Schema(description = "레시피 줄 id (문자열)", example = "12")
		String id,

		@Schema(description = "메뉴 id (문자열)", example = "7")
		String menuId,

		@Schema(description = "재료 id (문자열)", example = "3")
		String ingredientId,

		@Schema(description = "재료 이름", example = "등심")
		String ingredientName,

		@Schema(description = "재료 기준 단위", example = "G")
		IngredientUnit unit,

		@Schema(description = "1인분 양(기준 단위, 소수 3자리 반올림)", example = "150.000")
		BigDecimal qtyPerServing,

		@Schema(description = "MANUAL=사장님 입력 / AI=AI 추정 / OWNER_CONFIRMED=AI 추정을 사장님이 확인 / "
				+ "REGRESSION=역산 보정 / FILE=레시피 파일", example = "AI")
		RecipeSource source,

		@Schema(description = "AI 추정값인지 (source=AI)", example = "true")
		boolean estimated,

		@Schema(description = "확인 필요 — 추정값을 사장님이 아직 확인하지 않음", example = "true")
		boolean needsReview,

		@Schema(description = "확인 필요 이유 코드. UNANSWERED=AI 추정 양을 사장님이 아직 확인하지 않음", example = "[\"UNANSWERED\"]")
		List<ReviewReason> reviewReasons
) {

	public static RecipeResponse of(Recipe recipe, Ingredient ingredient) {
		boolean estimated = recipe.getSource().estimated();
		return new RecipeResponse(
				String.valueOf(recipe.getId()),
				String.valueOf(recipe.getMenuId()),
				String.valueOf(recipe.getIngredientId()),
				ingredient.getName(),
				ingredient.getUnit(),
				recipe.getQtyPerServing().setScale(3, RoundingMode.HALF_UP),
				recipe.getSource(),
				estimated,
				estimated,
				estimated ? List.of(ReviewReason.UNANSWERED) : List.of());
	}
}
