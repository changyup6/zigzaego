package com.zigzaego.api.ingredient.dto;

import com.zigzaego.api.ingredient.entity.IngredientKind;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.ingredient.entity.SourcingType;
import com.zigzaego.api.ingredient.entity.StorageLocation;
import com.zigzaego.api.ingredient.entity.StorageType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 보낸 칸만 바꾼다. null 이거나 빠진 칸은 그대로 둔다. */
@Schema(description = "재료 고치기 — 보낸 칸만 바뀐다")
public record IngredientUpdateRequest(
		@Schema(description = "재료 이름", example = "한우 등심", nullable = true)
		@Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*", message = "공백만으로 된 이름은 쓸 수 없습니다.")
		String name,

		@Schema(description = "기준 단위. 보내면 unitStatus=CONFIRMED. LOCKED 재료의 단위를 바꾸면 409 INGREDIENT_UNIT_LOCKED",
				example = "G", nullable = true)
		IngredientUnit unit,

		@Schema(description = "BOM / ONE_TO_ONE. 주기형 → BOM · 1:1 이동은 사장님이 여기서만 한다. "
				+ "다른 종류 → CYCLE 은 400 INGREDIENT_CYCLE_FROM_RECEIPT_ONLY", example = "BOM", nullable = true)
		IngredientKind kind,

		@Schema(description = "보관 방식. 주기형을 BOM · 1:1 로 옮길 때 없으면 400 INGREDIENT_STORAGE_TYPE_REQUIRED",
				example = "CHILLED", nullable = true)
		StorageType storageType,

		@Schema(description = "보관 위치(재료 탭 그림의 자리). 보관 방식(storageType)은 함께 바뀌지 않는다. "
				+ "정해진 4개 밖의 값은 400 COMMON_MALFORMED_REQUEST", example = "FREEZER", nullable = true)
		StorageLocation storageLocation,

		@Schema(description = "안전재고 일수 N", example = "2", nullable = true)
		@Positive @Digits(integer = 4, fraction = 2)
		BigDecimal safetyDays,

		@Schema(description = "1회 최대 주문량(기준 단위)", example = "20000", nullable = true)
		@Positive @Digits(integer = 11, fraction = 3)
		BigDecimal maxOrderQty,

		@Schema(description = "가격 연동 발주", example = "true", nullable = true)
		Boolean priceSensitive,

		@Schema(description = "유통기한(일)", example = "5", nullable = true)
		@Positive
		Integer shelfLifeDays,

		@Schema(description = "GENERIC / DEDICATED", example = "DEDICATED", nullable = true)
		SourcingType sourcingType,

		@Schema(description = "카테고리 id(문자열). 보내면 그 카테고리로 바꾸고 출처를 OWNER 로 — 자동 분류가 다시 덮어쓰지 않는다. "
				+ "다른 가게 · 없는 · 숫자 아닌 id 는 404 COMMON_NOT_FOUND", example = "12", nullable = true)
		String categoryId
) {
}
