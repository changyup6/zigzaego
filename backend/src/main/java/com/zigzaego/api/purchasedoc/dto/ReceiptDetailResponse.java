package com.zigzaego.api.purchasedoc.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 영수증 한 장과 줄 ({@code GET /receipts/{docId}}, S15P31A308-113). 앞 칸은 {@link ReceiptFileResponse} 와 같다. */
@Schema(description = "영수증 한 장과 품목 줄")
public record ReceiptDetailResponse(
		@Schema(description = "문서 id (문자열)", example = "101")
		String id,

		@Schema(description = "올린 파일 이름. 모르면 null", example = "IMG_2031.jpg", nullable = true)
		String file,

		@Schema(description = "올린 시각 (ISO-8601, 가게 시간대)", example = "2026-10-19T08:12:00+09:00")
		String at,

		@Schema(description = "wait · read · done · fail", example = "done")
		String st,

		@Schema(description = "PAST · RECEIVE · null", example = "RECEIVE", nullable = true)
		String kind,

		@Schema(description = "문서 날짜 YYYY-MM-DD 또는 null", example = "2026-10-18", nullable = true)
		String docDate,

		@Schema(description = "줄 수", example = "4")
		int lineCount,

		@Schema(description = "실패 사유 코드", nullable = true)
		String error,

		@Schema(description = "거래처 이름 — 등록 거래처면 그 이름, 아니면 인식한 상호, 둘 다 없으면 null", example = "위글리마트",
				nullable = true)
		String supplierName,

		@Schema(description = "품목 줄. 줄 번호 오름차순")
		List<ReceiptLineResponse> lines
) {

	public static ReceiptDetailResponse of(ReceiptFileResponse file, String supplierName,
			List<ReceiptLineResponse> lines) {
		return new ReceiptDetailResponse(file.id(), file.file(), file.at(), file.st(), file.kind(), file.docDate(),
				file.lineCount(), file.error(), supplierName, lines);
	}
}
