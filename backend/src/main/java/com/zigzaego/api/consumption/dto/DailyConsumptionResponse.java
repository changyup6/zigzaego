package com.zigzaego.api.consumption.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** 하루 · 재료 한 개의 소비량 한 줄. 단위는 재료의 기준 단위 그대로(환산 없음). */
@Schema(description = "재료별 하루 소비량 한 줄")
public record DailyConsumptionResponse(
		@Schema(description = "날짜 (YYYY-MM-DD)", example = "2026-10-01", type = "string", format = "date")
		String date,

		@Schema(description = "재료 id (문자열)", example = "3")
		String ingredientId,

		@Schema(description = "재료 이름", example = "등심")
		String ingredientName,

		@Schema(description = "재료 기준 단위", example = "G", allowableValues = {"EA", "G", "ML"})
		String unit,

		@Schema(description = "그날 소비량(기준 단위, 소수 3자리 반올림)", example = "6000.000")
		BigDecimal qty
) {
}
