package com.zigzaego.api.closingcheck.dto;

import com.zigzaego.api.ingredient.entity.IngredientUnit;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** 오늘 마감 때 확인할 재료 한 줄 (S15P31A308-249). 포장 단위로 묻고 기준 단위로 저장한다 (DECISIONS #88 ②). */
@Schema(description = "마감 확인 대상 재료")
public record ClosingItemResponse(
		@Schema(description = "재료 id (문자열)", example = "12")
		String ingredientId,

		@Schema(description = "재료 이름", example = "양파")
		String name,

		@Schema(description = "추정 재고(기준 단위, 소수 3자리). 음수면 0", example = "1450.000")
		BigDecimal estimatedQty,

		@Schema(description = "재료 기준 단위", example = "G")
		IngredientUnit baseUnit,

		@Schema(description = "포장 단위(가장 최근 입고한 거래처 상품, 없으면 대표 상품). 포장 단위나 포장 환산을 모르면 null",
				example = "망", nullable = true)
		String packUnit,

		@Schema(description = "포장 1개 = 기준 단위 몇. packUnit 이 null 이면 null", example = "1000", nullable = true)
		BigDecimal baseQtyPerPack,

		@Schema(description = "추정 재고를 포장 수로, 0.5 단위 반올림. packUnit 이 null 이면 null", example = "1.5", nullable = true)
		BigDecimal estimatedPacks,

		@Schema(description = "화면 − / + 한 번에 바뀌는 양. 포장 단위가 있으면 0.5(포장), 없으면 1(기준 단위)", example = "0.5")
		BigDecimal step,

		@Schema(description = "왜 묻는지: NEEDS_COUNT 실사 필요(실사 없음 · 음수 보정) · REPEATED_LOSS 실사 차이 반복 · "
				+ "RUNNING_OUT 소진 예상 3일 이내 · STALE_COUNT 마지막 실사 7일 넘음", example = "RUNNING_OUT")
		ClosingItemReason reason
) {
}
