package com.zigzaego.api.supplier.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 거래처 · 재료 · 상품명 말고는 비워도 된다 (설계 6절). */
@Schema(description = "거래처 상품 만들기")
public record SupplierProductCreateRequest(
		@Schema(description = "거래처 id. 다른 가게 거래처면 404", example = "4")
		@NotBlank
		String supplierId,

		@Schema(description = "재료 id. 다른 가게 재료면 404", example = "3")
		@NotBlank
		String ingredientId,

		@Schema(description = "상품명(영수증에 찍히는 이름). 한 거래처 안에서 겹치지 않는다", example = "대파 1단")
		@NotBlank @Size(max = 150)
		String productName,

		@Schema(description = "포장 크기", example = "1", nullable = true)
		@Positive @Digits(integer = 11, fraction = 3)
		BigDecimal packSize,

		@Schema(description = "포장 단위 이름 (단 · 박스 · 망)", example = "단", nullable = true)
		@Size(max = 20)
		String packUnit,

		@Schema(description = "최소 주문량(포장 수)", example = "1", nullable = true)
		@Positive @Digits(integer = 11, fraction = 3)
		BigDecimal moq,

		@Schema(description = "현재 단가(원, 포장 1개, 0 이상 정수). 넣으면 단가 이력에 한 줄", example = "3500", nullable = true)
		@PositiveOrZero @Digits(integer = 10, fraction = 0, message = "금액은 원 단위 정수여야 합니다.")
		BigDecimal price,

		@Schema(description = "포장 1개 = 재료 기준 단위 몇 (대파 1단 → 1000 g)", example = "1000", nullable = true)
		@Positive @Digits(integer = 11, fraction = 3)
		BigDecimal baseQtyPerPack,

		@Schema(description = "true 면 이 재료의 대표 상품으로 정한다", example = "true", nullable = true)
		Boolean primary
) {
}
