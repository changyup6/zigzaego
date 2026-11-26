package com.zigzaego.api.purchaseorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** 발주서 품목 한 줄. 수량은 재료 기준 단위(#42) — 포장 단위 표기는 messageText 에만 있다 (#33). */
@Schema(description = "발주서 품목 한 줄")
public record PurchaseOrderItemResponse(
		@Schema(description = "품목 줄 id (문자열)", example = "31")
		String id,

		@Schema(description = "이 줄을 만든 추천 id (문자열)", example = "12")
		String recommendationId,

		@Schema(description = "재료 id (문자열)", example = "3")
		String ingredientId,

		@Schema(description = "재료 이름", example = "대파")
		String ingredientName,

		@Schema(description = "거래처 상품 id (문자열)", example = "7")
		String supplierProductId,

		@Schema(description = "거래처 상품명 — 메시지에 적힌 이름", example = "대파")
		String productName,

		@Schema(description = "주문량(재료 기준 단위, 소수 3자리). 포장 단위로 올린 뒤의 양이라 승인 수량보다 클 수 있다 "
				+ "(1,600g 승인, 1단 = 1,000g → 2단 = 2000.000)", example = "2000.000")
		BigDecimal qty,

		@Schema(description = "입고 예정일 (YYYY-MM-DD) = 오늘 + 거래처 리드타임(모르면 1일)", example = "2026-10-15",
				type = "string", format = "date")
		String expectedDate,

		@Schema(description = "저장한 포장 1개 상당 단가(원 정수). 거래처 = 만든 순간의 상품 가격, 식봄 = 식봄 1위 단가 환산, "
				+ "쿠팡 · 가격을 모르면 null", example = "5691", nullable = true)
		Long unitPrice,

		@Schema(description = "식봄 · 쿠팡 줄의 상품 링크(쿠팡은 재료 이름 검색 주소). 거래처 줄은 null", nullable = true)
		String productUrl
) {
}
