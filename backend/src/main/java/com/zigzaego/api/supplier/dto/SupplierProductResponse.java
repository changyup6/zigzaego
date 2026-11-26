package com.zigzaego.api.supplier.dto;

import static com.zigzaego.api.global.response.Amounts.qty;
import static com.zigzaego.api.global.response.Amounts.won;

import com.zigzaego.api.ingredient.entity.Ingredient;
import com.zigzaego.api.ingredient.entity.IngredientUnit;
import com.zigzaego.api.supplier.entity.Supplier;
import com.zigzaego.api.supplier.entity.SupplierProduct;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** 거래처 상품 하나. 목록 · 조회 · 생성 · 수정 응답이 같다. 모르는 값은 null. */
@Schema(description = "거래처 상품 하나")
public record SupplierProductResponse(
		@Schema(description = "거래처 상품 id (문자열)", example = "9")
		String id,

		@Schema(description = "거래처 id (문자열)", example = "4")
		String supplierId,

		@Schema(description = "거래처 이름", example = "한빛농산")
		String supplierName,

		@Schema(description = "재료 id (문자열)", example = "3")
		String ingredientId,

		@Schema(description = "재료 이름", example = "대파")
		String ingredientName,

		@Schema(description = "재료 기준 단위 (baseQtyPerPack 의 단위)", example = "G")
		IngredientUnit unit,

		@Schema(description = "상품명", example = "대파 1단")
		String productName,

		@Schema(description = "포장 크기 (소수 3자리)", example = "1.000", nullable = true)
		BigDecimal packSize,

		@Schema(description = "포장 단위 이름", example = "단", nullable = true)
		String packUnit,

		@Schema(description = "최소 주문량 (소수 3자리)", example = "1.000", nullable = true)
		BigDecimal moq,

		@Schema(description = "현재 단가(원, 정수, 포장 1개)", example = "3500", nullable = true)
		Long price,

		@Schema(description = "포장 1개 = 재료 기준 단위 몇 (소수 3자리)", example = "1000.000", nullable = true)
		BigDecimal baseQtyPerPack,

		@Schema(description = "이 재료의 대표 상품인지", example = "true")
		boolean primary
) {

	public static SupplierProductResponse of(SupplierProduct product, Supplier supplier, Ingredient ingredient) {
		return new SupplierProductResponse(
				String.valueOf(product.getId()),
				String.valueOf(supplier.getId()),
				supplier.getName(),
				String.valueOf(ingredient.getId()),
				ingredient.getName(),
				ingredient.getUnit(),
				product.getProductName(),
				qty(product.getPackSize()),
				product.getPackUnit(),
				qty(product.getMoq()),
				won(product.getPrice()),
				qty(product.getBaseQtyPerPack()),
				ingredient.isPrimarySupplierProduct(product.getId()));
	}
}
