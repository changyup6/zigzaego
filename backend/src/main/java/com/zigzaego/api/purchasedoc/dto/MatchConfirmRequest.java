package com.zigzaego.api.purchasedoc.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** 묶음 확정 요청 ({@code POST /receipts/matches/{matchId}/confirm}, S15P31A308-113). */
@Schema(description = "묶음 확정 요청")
public record MatchConfirmRequest(
		@Schema(description = "재료 id (문자열) · \"cycle\"(새 주기형 품목) · \"ignore\"(무시)", example = "37")
		String choice,

		@Schema(description = "포장 1개 = 재료 기준 단위 몇. 선택 — 단위 미확정(unitNeeded) 묶음이면 필수", example = "1000",
				nullable = true)
		BigDecimal basePerPack
) {
}
