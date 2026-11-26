package com.zigzaego.api.purchasedoc.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 줄 단위 확정 요청 ({@code POST /receipts/lines/confirm}, S15P31A308-113 · {@link LineDecisionRequest}). */
@Schema(description = "줄 단위 확정 요청")
public record LinesConfirmRequest(
		@Schema(description = "줄마다 결정 — lineId · action(INGREDIENT · CYCLE · IGNORE) · ingredientId · baseQtyPerPack")
		List<LineDecisionRequest> lines
) {
}
