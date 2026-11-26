package com.zigzaego.api.purchaseorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * 발주 이력 한 달치 (S15P31A308-202). 보내지 않은 DRAFT 발주서는 넣지 않는다(#63). 수량은 재료 기준 단위 소수 셋째 자리(#42),
 * 금액은 원 정수(#55).
 */
@Schema(description = "발주 이력 한 달치: 그 달의 발주 줄 · 취소 뺀 합계 · 가장 이른 발주 달")
public record PurchaseOrderHistoryResponse(
		@Schema(description = "조회한 달 YYYY-MM (가게 시간대)", example = "2026-10")
		String month,

		@Schema(description = "그 달의 발주 줄. 발주일 내림차순, 같은 날이면 줄 id 내림차순. 없으면 빈 배열")
		List<Line> lines,

		@Schema(description = "그 달 줄 중 CANCELLED 를 뺀 amount 합(원 정수). amount 가 null 인 줄은 0 으로 센다", example = "85302")
		long totalAmount,

		@Schema(description = "이 가게에서 발주 기록(DRAFT 제외)이 있는 가장 이른 달 YYYY-MM. 없으면 null",
				example = "2026-09", nullable = true)
		String earliestMonth
) {

	@Schema(description = "발주 줄 하나")
	public record Line(
			@Schema(description = "발주일 YYYY-MM-DD (가게 시간대) — 발주서를 보낸 시각, 없으면 만든 시각", example = "2026-10-14")
			String date,

			@Schema(description = "발주서 id (문자열)", example = "31")
			String orderId,

			@Schema(description = "발주 줄 id (문자열)", example = "77")
			String itemId,

			@Schema(description = "재료 이름", example = "토마토")
			String ingredientName,

			@Schema(description = "거래처 이름", example = "한빛농산")
			String supplierName,

			@Schema(description = "양. 입고 확인한 줄은 받은 양, 아니면 주문한 양. 재료 기준 단위(포장 수 × 포장 1개 기준량, 소수 셋째 자리). "
					+ "포장 크기를 모르는 상품은 null — 포장 개수를 기준 단위 양처럼 내지 않는다(#42)", example = "12000.000",
					nullable = true)
			BigDecimal qty,

			@Schema(description = "qty 의 단위. 재료 기준 단위 표기 g · ml · 개 (포장 크기를 몰라 qty 가 null 이어도 재료 기준 단위)",
					example = "g")
			String unit,

			@Schema(description = "금액(원 정수, 반올림). 입고 확인한 줄은 받은 포장 수 × 받은 포장 단가, 아니면 주문 포장 수 × 지금 거래처 상품 "
					+ "포장 가격. 가격을 모르면 null", example = "46800", nullable = true)
			Long amount,

			@Schema(description = "SENT(보냄 · 입고 전) · RECEIVED(입고 확인) · CANCELLED(줄 안 옴 또는 발주서 취소)", example = "SENT")
			Status status
	) {
	}

	public enum Status {
		SENT, RECEIVED, CANCELLED
	}
}
