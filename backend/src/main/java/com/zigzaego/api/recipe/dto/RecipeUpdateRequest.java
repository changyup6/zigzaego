package com.zigzaego.api.recipe.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** 사장님이 양을 고치거나, 추정값을 그대로 보내 확인한다. */
@Schema(description = "레시피 줄 고치기 · 확인하기")
public record RecipeUpdateRequest(
		@Schema(description = "1인분 양(재료 기준 단위, 소수 3자리까지). AI 추정값을 그대로 보내면 확인으로 친다", example = "160")
		@NotNull @Positive @Digits(integer = 10, fraction = 3)
		BigDecimal qtyPerServing
) {
}
