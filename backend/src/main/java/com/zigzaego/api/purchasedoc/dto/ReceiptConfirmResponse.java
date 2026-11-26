package com.zigzaego.api.purchasedoc.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 줄 확정 · 영수증 확정 응답 ({@code POST /receipts/lines/confirm} · {@code /receipts/docs/confirm}). */
@Schema(description = "확정 뒤의 영수증 화면 + 확정 요약")
public record ReceiptConfirmResponse(
		@Schema(description = "가장 최근 영수증 job id", nullable = true)
		String jobId,

		List<ReceiptFileResponse> files,

		List<ReviewMatchResponse> matches,

		List<ReviewOptionResponse> options,

		List<ReadyDocResponse> readyDocs,

		@Schema(description = "확정 요약: 확정한 줄 · 무시한 줄 · 확정된 문서 · 새 재료 · 새 사전 상품 · 가격 이력 수")
		ConfirmResultResponse confirm
) {

	public static ReceiptConfirmResponse of(ReceiptBoardResponse board, ConfirmResultResponse confirm) {
		return new ReceiptConfirmResponse(board.jobId(), board.files(), board.matches(), board.options(),
				board.readyDocs(), confirm);
	}
}
