package com.zigzaego.api.receiving.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/**
 * 입고 탭 한 화면치 (입고 확인 API 제안 2절). 수량은 재료 기준 단위 소수 셋째 자리(DECISIONS #42), 단가는 단위 단가
 * (원 / 기준 단위 1) 소수 넷째 자리(#57). 포장 개수 · 포장 1개 값으로 바꿔 주지 않는다(#42). 모르는 값은 null.
 */
@Schema(description = "입고 탭 피드: 입고 예정 줄 · 최근 발주 · 첫 입고 여부")
public record ReceivingFeedResponse(
		@Schema(description = "입고 예정 줄 — 취소 안 됨 · 발주서 CANCELLED 아님 · 아직 입고 확인 안 됨. 도착 예정일 순(없으면 끝)")
		List<IncomingLine> incoming,

		@Schema(description = "최근 14일(오늘 포함, 가게 시간대) 발주 줄, 최대 50줄, 새 발주부터")
		List<HistoryLine> history,

		@Schema(description = "이 가게에 입고 기록(입고 확인된 발주 줄 또는 확정된 입고 영수증)이 하나라도 있는지", example = "true")
		boolean firstReceiptDone
) {

	@Schema(description = "입고 예정 줄 하나")
	public record IncomingLine(
			@Schema(description = "발주서 id (문자열)", example = "31")
			String orderId,

			@Schema(description = "발주 줄 id (문자열)", example = "77")
			String itemId,

			@Schema(description = "재료 id (문자열)", example = "12")
			String ingredientId,

			@Schema(description = "거래처 상품명", example = "토마토 캔")
			String name,

			@Schema(description = "거래처 이름", example = "OO상회")
			String supplier,

			@Schema(description = "포장 표시. 포장 1개 기준량 · 단위 · 포장 이름을 잇는다(\"3000 g 캔\"). 포장 이름이 없으면 null",
					example = "3000 g 캔", nullable = true)
			String packLabel,

			@Schema(description = "재료 기준 단위 표기 g · ml · 개", example = "g")
			String unit,

			@Schema(description = "포장 1개 = 기준 단위 몇. 모르면 null — 그때 입고 확인 요청에 basePerPack 을 넣어야 한다",
					example = "3000.000", nullable = true)
			BigDecimal basePerPack,

			@Schema(description = "주문한 양(기준 단위, 주문 포장 수 × basePerPack). basePerPack 을 모르면 null",
					example = "12000.000", nullable = true)
			BigDecimal qty,

			@Schema(description = "거래처 상품의 지금 단위 단가(원 / 기준 단위 1, 소수 넷째 자리). 단가나 basePerPack 을 모르면 null",
					example = "3.9000", nullable = true)
			BigDecimal unitPrice,

			@Schema(description = "도착 예정일 YYYY-MM-DD. 모르면 null", example = "2026-10-20", nullable = true)
			String expectedDate,

			@Schema(description = "도착 예정일이 오늘(가게 시간대)인지", example = "false")
			boolean today
	) {
	}

	@Schema(description = "최근 발주 줄 하나")
	public record HistoryLine(
			@Schema(description = "발주서 id (문자열)", example = "30")
			String orderId,

			@Schema(description = "발주 줄 id (문자열)", example = "70")
			String itemId,

			@Schema(description = "발주서 만든 날 YYYY-MM-DD (가게 시간대)", example = "2026-10-16")
			String date,

			@Schema(description = "거래처 상품명", example = "생크림 1L")
			String name,

			@Schema(description = "재료 기준 단위 표기 g · ml · 개", example = "ml")
			String unit,

			@Schema(description = "이 줄을 만든 추천의 추천량(기준 단위). 수동 주문이면 null", example = "6000.000", nullable = true)
			BigDecimal recommendedQty,

			@Schema(description = "실제 주문한 양(기준 단위, 주문 포장 수 × 포장 1개 기준량). 포장 크기를 모르면 null",
					example = "5000.000", nullable = true)
			BigDecimal finalQty,

			@Schema(description = "거래처 이름", example = "OO상회")
			String supplier,

			@Schema(description = "DRAFT(전송 대기) · SENT(보냄 · 입고 예정) · RECEIVED(입고 완료) · CANCELLED(줄 안 옴 또는 발주서 취소)",
					example = "RECEIVED")
			HistoryStatus status,

			@Schema(description = "받은 양(기준 단위). 입고 확인 전이거나 포장 크기를 모르면 null", example = "5000.000", nullable = true)
			BigDecimal receivedQty,

			@Schema(description = "입고 확인 시각 ISO-8601 (가게 시간대). 입고 확인 전이면 null",
					example = "2026-10-18T09:10:00+09:00", nullable = true)
			String receivedAt
	) {
	}

	public enum HistoryStatus {
		DRAFT, SENT, RECEIVED, CANCELLED
	}
}
