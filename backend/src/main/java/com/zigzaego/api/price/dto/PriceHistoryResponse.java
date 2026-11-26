package com.zigzaego.api.price.dto;

import com.zigzaego.api.ingredient.entity.IngredientUnit;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/** 재료 하나의 단가 이력과 가격 지수. 단가는 모두 단위 단가(원 / 재료 기준 단위 1). */
@Schema(description = "재료 단가 이력 · 가격 지수")
public record PriceHistoryResponse(
		@Schema(description = "재료 id (문자열)", example = "12")
		String ingredientId,

		@Schema(description = "재료 기준 단위 (단위 단가의 분모)", example = "G")
		IngredientUnit unit,

		@ArraySchema(arraySchema = @Schema(description = "최근 8주 주별 단위 단가 중앙값 8칸. 마지막 칸 = 오늘 − 6일 ~ 오늘, "
				+ "그 앞 칸은 7일씩 앞. 관측 없는 주는 null (채우지 않는다)", example = "[4.2,4.2,4.5,null,4.4,4.4,4.6,4.8]"),
				schema = @Schema(type = "number", nullable = true))
		List<BigDecimal> weekly,

		@ArraySchema(arraySchema = @Schema(description = "최근 8주(오늘 포함 56일) 관측. 날짜 내림차순, 최대 200건"))
		List<PriceObservationResponse> observations,

		@Schema(description = "가격 지수")
		PriceIndexResponse index
) {
}
