package com.zigzaego.api.ingredient.dto;

import com.zigzaego.api.global.review.ReviewReason;
import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientCategory;
import com.zigzaego.api.ingredient.entity.IngredientKind;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.ingredient.entity.SourcingType;
import com.zigzaego.api.ingredient.entity.StorageLocation;
import com.zigzaego.api.ingredient.entity.StorageType;
import com.zigzaego.api.ingredient.entity.UnitStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 재료 하나. 목록 · 조회 · 생성 · 수정 응답이 같다. */
@Schema(description = "재료 하나")
public record IngredientResponse(
		@Schema(description = "재료 id (문자열)", example = "3")
		String id,

		@Schema(description = "재료 이름", example = "등심")
		String name,

		@Schema(description = "기준 단위", example = "G")
		IngredientUnit unit,

		@Schema(description = "BOM=메뉴 판매 × 1인분 / ONE_TO_ONE=사 온 그대로 팜 / CYCLE=주기형", example = "BOM")
		IngredientKind kind,

		@Schema(description = "단위 상태 ESTIMATED → CONFIRMED → LOCKED. LOCKED 면 단위를 바꿀 수 없다", example = "CONFIRMED")
		UnitStatus unitStatus,

		@Schema(description = "보관 방식. 주기형은 모르면 null", example = "CHILLED", nullable = true)
		StorageType storageType,

		@Schema(description = "보관 위치(재료 탭 그림 배치). FREEZER 냉동 / FRIDGE 냉장 / SHELF 선반 / WAREHOUSE 창고",
				example = "FRIDGE")
		StorageLocation storageLocation,

		@Schema(description = "재료 카테고리(재료 탭 필터). 만들 때 정하지 않으면 기타")
		IngredientCategorySummaryResponse category,

		@Schema(description = "안전재고 일수 N", example = "1.00")
		BigDecimal safetyDays,

		@Schema(description = "1회 최대 주문량(기준 단위, 소수 3자리). 없으면 null", example = "20000.000", nullable = true)
		BigDecimal maxOrderQty,

		@Schema(description = "가격 연동 발주를 켜는지", example = "false")
		boolean priceSensitive,

		@Schema(description = "유통기한(일). 없으면 null", example = "5", nullable = true)
		Integer shelfLifeDays,

		@Schema(description = "GENERIC=범용 기성품 / DEDICATED=가게 전용 제휴품", example = "GENERIC")
		SourcingType sourcingType,

		@Schema(description = "확인 필요", example = "true")
		boolean needsReview,

		@Schema(description = "확인 필요 이유 코드. UNANSWERED=AI 추정 레시피 줄에 쓰임 / NEEDS_COUNT=실사 기록이 없음",
				example = "[\"NEEDS_COUNT\"]")
		List<ReviewReason> reviewReasons
) {

	public static IngredientResponse of(Ingredient ingredient, List<ReviewReason> reviewReasons,
			IngredientCategory category) {
		return new IngredientResponse(
				String.valueOf(ingredient.getId()),
				ingredient.getName(),
				ingredient.getUnit(),
				ingredient.getKind(),
				ingredient.getUnitStatus(),
				ingredient.getStorageType(),
				ingredient.getStorageLocation(),
				IngredientCategorySummaryResponse.of(category),
				ingredient.getSafetyDays().setScale(2, RoundingMode.HALF_UP),
				ingredient.getMaxOrderQty() == null ? null : ingredient.getMaxOrderQty().setScale(3, RoundingMode.HALF_UP),
				ingredient.isPriceSensitive(),
				ingredient.getShelfLifeDays(),
				ingredient.getSourcingType(),
				!reviewReasons.isEmpty(),
				reviewReasons);
	}
}
