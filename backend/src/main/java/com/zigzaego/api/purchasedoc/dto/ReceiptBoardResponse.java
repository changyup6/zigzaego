package com.zigzaego.api.purchasedoc.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 영수증 화면 한 장 ({@code GET /receipts}, S15P31A308-113 · FE {@code ReceiptBoard}). 바꾸는 주소도 모두 바뀐 뒤의 이 모양을
 * 돌려준다.
 */
@Schema(description = "영수증 화면: 올린 장 · 확인 묶음 · 재료 선택지 · 확정만 남은 영수증")
public record ReceiptBoardResponse(
		@Schema(description = "이 가게의 가장 최근 영수증 job id (문자열). 없으면 null", example = "42", nullable = true)
		String jobId,

		@Schema(description = "올린 장. CONFIRMED 가 아닌 장 전부 + CONFIRMED 는 올린 지 7일 안. id 오름차순")
		List<ReceiptFileResponse> files,

		@Schema(description = "확인 전 줄을 상품명 하나당 하나로 묶은 것. id 는 상품명 UTF-8 의 base64url(패딩 없음) — "
				+ "POST /receipts/matches/{matchId}/confirm 에 그대로 넣는다")
		List<ReviewMatchResponse> matches,

		@Schema(description = "고를 수 있는 재료(재료 id 오름차순)")
		List<ReviewOptionResponse> options,

		@Schema(description = "줄이 모두 자동 확정돼 확정만 하면 되는 영수증 — POST /receipts/docs/confirm")
		List<ReadyDocResponse> readyDocs
) {
}
