package com.zigzaego.api.purchasedoc.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 줄이 모두 자동 확정된 문서 (S15P31A308-113). 문서는 PARSED 라 아직 재고에 안 들어갔다 — 사장님이
 * {@code ReceiptConfirmService.confirmDocuments} 로 확정하면 들어가고 목록에서 빠진다.
 */
@Schema(description = "줄이 모두 자동 확정된 문서")
public record ReadyDocResponse(
		@Schema(description = "문서 id (문자열)")
		String docId,

		@Schema(description = "PAST · RECEIVE")
		String kind,

		@Schema(description = "문서 날짜 (yyyy-MM-dd)")
		String date,

		@Schema(description = "문서 줄 수")
		int lines
) {
}
