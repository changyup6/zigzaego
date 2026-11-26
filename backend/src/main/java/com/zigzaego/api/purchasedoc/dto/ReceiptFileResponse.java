package com.zigzaego.api.purchasedoc.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 올린 영수증 한 장 ({@code GET /receipts} 의 {@code files[]}, S15P31A308-113 · FE {@code ReceiptFile}). */
@Schema(description = "올린 영수증 한 장")
public record ReceiptFileResponse(
		@Schema(description = "문서 id (문자열)", example = "101")
		String id,

		@Schema(description = "올린 파일 이름. 모르면 null", example = "IMG_2031.jpg", nullable = true)
		String file,

		@Schema(description = "올린 시각 (ISO-8601, 가게 시간대)", example = "2026-10-19T08:12:00+09:00")
		String at,

		@Schema(description = "wait 대기 · read 읽는 중 · done 다 읽음 · fail 실패(다시 읽기 가능)", example = "done",
				allowableValues = {"wait", "read", "done", "fail"})
		String st,

		@Schema(description = "PAST(재고 실사 이전) · RECEIVE(이후). 날짜를 못 읽었으면 null", example = "RECEIVE",
				nullable = true)
		String kind,

		@Schema(description = "문서 날짜 YYYY-MM-DD. 못 읽었으면 null", example = "2026-10-18", nullable = true)
		String docDate,

		@Schema(description = "그 문서 줄 수(상태 무관)", example = "7")
		int lineCount,

		@Schema(description = "실패 사유 코드. 끊긴 장은 job 사유(예 SERVER_RESTARTED) 또는 RECEIPT_INTERRUPTED",
				example = "RECEIPT_NO_ITEMS", nullable = true)
		String error
) {
}
