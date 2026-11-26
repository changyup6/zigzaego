package com.zigzaego.api.price.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** 단가 관측 한 건. 단위 단가 = 포장 1개 값 ÷ 포장 1개의 재료 기준 단위량. */
@Schema(description = "단가 관측 한 건")
public record PriceObservationResponse(
		@Schema(description = "관측 날짜 (서울, YYYY-MM-DD)", example = "2026-10-14", type = "string", format = "date")
		String observedOn,

		@Schema(description = "단위 단가(원 / 재료 기준 단위 1, 소수 4자리 반올림 — DECISIONS #57)", example = "4.8000")
		BigDecimal unitPrice,

		@Schema(description = "기록 출처", example = "MANUAL", allowableValues = {"RECEIVE", "DOC", "MANUAL"})
		String source,

		@Schema(description = "거래처 상품 id (문자열)", example = "3")
		String supplierProductId,

		@Schema(description = "거래처 상품명", example = "대파 1kg")
		String productName
) {
}
