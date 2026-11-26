package com.zigzaego.api.purchasedoc.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/** 영수증 한 장의 품목 줄 ({@code GET /receipts/{docId}} 의 {@code lines[]}). */
@Schema(description = "영수증 품목 줄")
public record ReceiptLineResponse(
		@Schema(description = "줄 id (문자열) — POST /receipts/lines/confirm 의 lineId", example = "801")
		String id,

		@Schema(description = "문서 안 줄 번호(1부터)", example = "1")
		int lineNo,

		@Schema(description = "문서에 적힌 품목명 그대로", example = "대파 1단")
		String rawName,

		@Schema(description = "수량(소수 셋째 자리). 없으면 null", example = "2.000", nullable = true)
		BigDecimal qty,

		@Schema(description = "문서에 적힌 단위. 없으면 null", example = "단", nullable = true)
		String unit,

		@Schema(description = "단가(원 정수). 없으면 null", example = "3980", nullable = true)
		Long unitPrice,

		@Schema(description = "금액(원 정수). 없으면 null", example = "7960", nullable = true)
		Long amount,

		@Schema(description = "맞춘 재료 id (문자열). 없으면 null", example = "37", nullable = true)
		String ingredientId,

		@Schema(description = "매칭 확신도 0~1. 사전으로 맞췄거나 없으면 null", example = "0.9", nullable = true)
		BigDecimal confidence,

		@Schema(description = "PENDING 확인 전 · CONFIRMED 확정 · IGNORED 무시", example = "PENDING")
		String status
) {
}
