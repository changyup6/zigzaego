package com.zigzaego.api.supplier.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** 보낸 칸만 바꾼다. null 이거나 빠진 칸은 그대로 둔다. 거래처 · 재료는 바꾸지 않는다 — 지우고 새로 만든다. */
@Schema(description = "거래처 상품 고치기 — 보낸 칸만 바뀐다")
public record SupplierProductUpdateRequest(
		@Schema(description = "상품명", example = "대파 1단(특)", nullable = true)
		@Size(min = 1, max = 150) @Pattern(regexp = ".*\\S.*", message = "공백만으로 된 이름은 쓸 수 없습니다.")
		String productName,

		@Schema(description = "포장 크기", example = "1", nullable = true)
		@Positive @Digits(integer = 11, fraction = 3)
		BigDecimal packSize,

		@Schema(description = "포장 단위 이름", example = "단", nullable = true)
		@Size(min = 1, max = 20)
		String packUnit,

		@Schema(description = "최소 주문량(포장 수)", example = "2", nullable = true)
		@Positive @Digits(integer = 11, fraction = 3)
		BigDecimal moq,

		@Schema(description = "현재 단가(원, 0 이상 정수). 지금 값과 다르면 단가 이력(MANUAL)에 한 줄", example = "3800", nullable = true)
		@PositiveOrZero @Digits(integer = 10, fraction = 0, message = "금액은 원 단위 정수여야 합니다.")
		BigDecimal price,

		@Schema(description = "포장 1개 = 재료 기준 단위 몇", example = "1000", nullable = true)
		@Positive @Digits(integer = 11, fraction = 3)
		BigDecimal baseQtyPerPack,

		@Schema(description = "true = 이 재료의 대표 상품으로 / false = 대표 상품이면 비운다", example = "true", nullable = true)
		Boolean primary
) {
}
