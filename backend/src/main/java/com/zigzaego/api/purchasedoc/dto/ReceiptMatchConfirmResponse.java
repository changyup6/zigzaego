package com.zigzaego.api.purchasedoc.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/** 묶음 확정 응답 ({@code POST /receipts/matches/{matchId}/confirm}, S15P31A308-113). {@code GET /receipts} 와 같은 모양에 gain 을 더한 것. */
@Schema(description = "묶음 확정 뒤의 영수증 화면 + 늘어난 재고")
public record ReceiptMatchConfirmResponse(
		@Schema(description = "가장 최근 영수증 job id", nullable = true)
		String jobId,

		List<ReceiptFileResponse> files,

		List<ReviewMatchResponse> matches,

		List<ReviewOptionResponse> options,

		List<ReadyDocResponse> readyDocs,

		@Schema(description = "이번 확정으로 추정 재고에 더해진 양(고른 재료 기준 단위, 소수 셋째 자리). 주기형 · 무시면 0",
				example = "3000.000")
		BigDecimal gain,

		@Schema(description = "날짜를 못 읽은 영수증이라 건너뛴 줄 id — 목록의 같은 묶음에 남는다")
		List<String> skippedLineIds
) {

	public static ReceiptMatchConfirmResponse of(ReceiptBoardResponse board, BigDecimal gain,
			List<String> skippedLineIds) {
		return new ReceiptMatchConfirmResponse(board.jobId(), board.files(), board.matches(), board.options(),
				board.readyDocs(), gain, skippedLineIds);
	}
}
