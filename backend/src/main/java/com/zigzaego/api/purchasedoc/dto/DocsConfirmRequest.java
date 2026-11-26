package com.zigzaego.api.purchasedoc.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 자동 확정 영수증 확정 요청 ({@code POST /receipts/docs/confirm}, S15P31A308-113). */
@Schema(description = "확정할 영수증 — GET /receipts 의 readyDocs[].docId")
public record DocsConfirmRequest(
		@Schema(description = "문서 id (문자열) 목록", example = "[\"120\", \"121\"]")
		List<String> docIds
) {
}
