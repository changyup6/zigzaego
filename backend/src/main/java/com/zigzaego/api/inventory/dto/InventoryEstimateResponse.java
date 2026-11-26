package com.zigzaego.api.inventory.dto;

import com.zigzaego.api.ingredient.entity.IngredientKind;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** 재료 하나의 추정 재고. 단위는 재료 기준 단위 그대로(환산 없음). */
@Schema(description = "재료별 추정 재고 한 줄")
public record InventoryEstimateResponse(
		@Schema(description = "재료 id (문자열)", example = "3")
		String ingredientId,

		@Schema(description = "재료 이름", example = "등심")
		String ingredientName,

		@Schema(description = "재료 기준 단위", example = "G")
		IngredientUnit unit,

		@Schema(description = "BOM=메뉴 판매 × 1인분 / ONE_TO_ONE=사 온 그대로 팜 / CYCLE=주기형", example = "BOM")
		IngredientKind kind,

		@Schema(description = "추정 재고(기준 단위, 소수 3자리). 음수면 0. 주기형이 아직 계산 못 하면 null",
				example = "4250.000", nullable = true)
		BigDecimal estimatedQty,

		@Schema(description = "실사 필요. 실사 기록이 없거나(BOM · 1:1) 추정 재고가 음수로 내려가 0 으로 보정했을 때 true",
				example = "false")
		boolean needsCount,

		@Schema(description = "소진 예상일 (YYYY-MM-DD). 이미 0 이면 오늘. 소비 이력이 없어 계산 못 하면 null",
				example = "2026-10-21", type = "string", format = "date", nullable = true)
		String runOutDate,

		@Schema(description = "기준점이 된 마지막 실사 시각 (ISO-8601). 실사 기록이 없으면 null",
				example = "2026-10-12T09:30:00+09:00", nullable = true)
		String lastCountedAt,

		@Schema(description = "계산 방식", example = "COUNT")
		EstimateBasis basis
) {
}
