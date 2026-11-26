package com.zigzaego.api.ingredient.dto;

import com.zigzaego.api.ingredient.entity.IngredientKind;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.ingredient.entity.SourcingType;
import com.zigzaego.api.ingredient.entity.StorageType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 사장님이 재료를 직접 만든다. 주기형(CYCLE)은 영수증으로만 생기므로 BOM · ONE_TO_ONE 만 받는다. */
@Schema(description = "재료 만들기")
public record IngredientCreateRequest(
		@Schema(description = "재료 이름. 가게 안에서 겹치지 않는다", example = "등심")
		@NotBlank @Size(max = 100)
		String name,

		@Schema(description = "기준 단위 (EA / G / ML). 사장님이 정한 단위라 unitStatus=CONFIRMED 로 만든다", example = "G")
		@NotNull
		IngredientUnit unit,

		@Schema(description = "BOM / ONE_TO_ONE. CYCLE 은 400 INGREDIENT_CYCLE_FROM_RECEIPT_ONLY", example = "BOM")
		@NotNull
		IngredientKind kind,

		@Schema(description = "보관 방식 (FROZEN / AMBIENT / CHILLED / FRESH)", example = "CHILLED")
		@NotNull
		StorageType storageType,

		@Schema(description = "안전재고 일수 N. 없으면 1", example = "1.5", nullable = true)
		@Positive @Digits(integer = 4, fraction = 2)
		BigDecimal safetyDays,

		@Schema(description = "1회 최대 주문량(기준 단위)", example = "20000", nullable = true)
		@Positive @Digits(integer = 11, fraction = 3)
		BigDecimal maxOrderQty,

		@Schema(description = "가격 연동 발주. 없으면 보관 방식으로 정한다 — 냉동 · 상온 true, 냉장 · 신선 false",
				example = "false", nullable = true)
		Boolean priceSensitive,

		@Schema(description = "유통기한(일)", example = "5", nullable = true)
		@Positive
		Integer shelfLifeDays,

		@Schema(description = "GENERIC / DEDICATED. 없으면 GENERIC", example = "GENERIC", nullable = true)
		SourcingType sourcingType,

		@Schema(description = "카테고리 id(문자열). 보내면 그 카테고리로 두고(출처 OWNER) 자동 분류를 건너뛴다. "
				+ "없으면 이름으로 자동 분류. 다른 가게 · 없는 · 숫자 아닌 id 는 404", example = "12", nullable = true)
		String categoryId
) {
}
